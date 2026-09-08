// vault.cpp —— 独立密钥保管进程（打包为 libsyncsvc.so，源码内称 vault）
//
// 职责：DK（数据密钥）的唯一持有者与全部 DK/CK 密码学操作的唯一执行者。
// 主 app 进程（含被 hook 的 Java 层）永远只能拿到密文与"请求-结果"接口，
// 拿不到 DK 本身——结构上封堵"Java 层操控 native 交出密码"（LSPlant/
// 自研注入框架对无 ART 进程无处下钩；同 uid ptrace 被 dumpable=0 内核拒绝）。
//
// 进程形态：app 侧 native（libmemsys.so 内 vault_client）double-fork+execve
// 启动，协议跑在 fork 前 socketpair 的 dup2(fd,0/1) 上——无文件系统路径、
// 无 listen，物理上只有主进程持对端。argv[1] = filesDir（与 daemon 的端口
// 同等地位的中性参数）。
//
// 磁盘格式（filesDir/sync_key.bin，单文件=tmp+rename 原子写，状态机内
// 无跨文件窗口；键名沿用 sync_* 中性命名族）：
//   [0]'K' [1]ver=2 [2]state
//   state 1 LIVE_PW （有门禁，恒 185B）:
//     安全验证项 [16B salt][12B nonce][GCM_{Argon2id(sec,salt)}(MARK_SEC)]
//     胁迫验证项 [16B salt][12B nonce][GCM_{Argon2id(coe,salt)}(MARK_COE)]
//     DK 包裹 [16B keySalt][12B nonce][GCM_{Argon2id(pw,keySalt)}(DK 32B)]
//   state 2 LIVE_WK （无门禁，恒 79B）:
//     DK 包裹 [16B keySalt][12B nonce][GCM_WK(DK 32B)]
//   state 3 DEAD_PW （OP_DESTROY 销毁态，恒 109B，验证器保留——门禁
//     行为前后一致）：仅经 OP_DESTROY 产生（注入检测/超时销毁等非演出
//     路径；胁迫解锁不再进入此态——见下"胁迫语义"）；与 LIVE_PW 相同的
//     双验证项，无 DK 包裹。任一验证项命中即以该密码重生回 LIVE_PW
//
// 恒定结构（v2 存在理由）：胁迫验证项恒写——未设置胁迫密码时为
// rand_bytes 直填的哑项（与真项计算不可区分：真项 salt/nonce 本就
// 随机、ct 为 GCM 密文，哑项三段皆均匀随机）。文件不编码"是否配置
// 胁迫密码"：无标志字节、长度恒定、解析严格（r.left==0 否则
// CORRUPT）——COE 存在性只存在于用户记忆中，拖库者读文件与读
// 随机数等价。时序抹平配套（见"解锁时序零差"）：哑项使错误密码
// 验证成本恒为 2 次 Argon2id（DK 包裹解开失败 + 胁迫项实测，哑项
// 必败 2^-128），安全/胁迫路径由陪跑与缓存键对齐到同值。
//
// 验证哲学（消灭比较点）：密码正确性 = GCM 解密 tag 校验（ARMv8 密码学
// 层），vault 内不存在可被 hook 的应用层比较函数。LIVE_PW 下 DK 包裹的
// 解开本身即安全密码验证；验证项仅在
//   a) 胁迫密码判定（解开安全包裹失败后）
//   b) DEAD_PW 状态下的密码判定（DK 包裹已随销毁删除——sec/coe
//      任一命中即以该密码重生）
// 时参与。
//
// 解锁时序零差（v2 配套）：LIVE_PW 三路径（安全/错误/胁迫）恒定
// 2 次 Argon2id——
//   安全：unwrap（1）+ 验证项等时陪跑（2，结果不参与判定——unwrap
//         的 GCM tag 已是权威，验证项 bit-rot 不应拒绝正确密码）
//   错误：unwrap 失败（1）+ 胁迫项实测（2，哑项必败 2^-128）
//   胁迫：unwrap 失败（1，派生键缓存）+ 胁迫项实测（2）→ 重生复用
//         缓存键（见 rebirth），零额外派生
// 退避拒绝同样 2 次哑派生陪跑（返回码已与 BAD 同码，时序不应成为
// 旁路信道）。残余差仅胁迫路径多一次 185B 原子写（fsync 百 ms 级，
// 处于 UI 渲染噪声内）。DEAD_PW：错误/胁迫同为 2 次；sec 首解 3 次
// （仅合法用户可见——销毁后回归的本人，无演出对象）。LIVE_WK/
// NOTHING/CORRUPT 即时返回（门禁模式本身已由 UI 显现，非秘密）。
//
// 胁迫语义（重生式）：UNLOCK 命中胁迫项 → 就地重生——新随机 DK 以
// 缓存的 unwrap 派生键包裹改写文件（keySalt 原样保留：缓存键本就
// 派生自 keySalt，复用后文件三 salt 依旧互异、无重生痕迹，且省第
// 3 次 Argon2id——与错误密码路径等时，见"解锁时序零差"；旧 DK
// 包裹被覆盖 = 密钥立即死亡，历史密文永久孤儿化），会话以新 DK
// 全功能继续（演出：该密码正常解锁；同会话新建凭据/启动共享不穿帮
// ——若清场后功能全废，胁迫者当场试用即识破）+ 返回 COERCION 由
// Java 侧执行完整销毁序列（DataStore/daemon/Keystore；vault 层已
// 完成换钥，Java 侧须跳过 OP_DESTROY，见 DefenseProtocol.
// keepVaultSession）。双层独立引爆保持：Java 被拦截时 vault 侧已
// 先完成换钥（旧数据已死）。
// 代价（声明）：重生后原安全密码失效（DK 包裹已易主胁迫密码）；设备
// 归胁迫密码所有，用户获释后以胁迫密码进入并 MIGRATE 重设——与
// "烧毁设备纪律"一致，被胁迫过的设备本就不应再信任。
// 取证边界（诚实声明）：重生改写 DK 包裹段而验证项照抄——持有前后
// 快照的实时 root 可识别"换钥未换验证器"。旧实现同样暴露（文件长度
// 185→109 跳变，且销毁后功能全废更易识破）；Java 侧 DataStore 清扫
// 对实时 root 本就不可隐藏。演出针对的是无快照能力的现场胁迫者：
// 事后取证者只见 LIVE_PW 185B，与从未被胁迫的设备不可区分。
//
// 信道密钥：CK = HMAC-SHA256(DK, "ScreenshotFaker/channel/v1")，确定性
// 派生——锁定/解锁循环后 daemon 仍持旧 CK，信道自动恢复（与旧实现
// "同 DK 重组装后信道恢复"同语义）。DK 永不出 vault，出vault的 CK 单独
// 派生（HMAC 单向，CK 不泄露 DK）。
//
// UNLOCK 在线限速（补齐旧实现缺失的在线爆破防线）：连续 5 次失败起
// 指数退避（1s,2s,4s…封顶 60s），成功即复位。计数器仅驻内存——vault
// 被杀重启即复位，但每次尝试本身要付一次 Argon2id（64MiB 内存硬，
// ~数百 ms），重杀重启的攻击成本不低于等待退避。
//
// 诚实边界（README 威胁表同步）：
// - 被劫持的主进程可在解锁会话内主动调 OPEN/COMPOSE 逐条取明文
//   （RPC oracle）——与"用户主动查看凭据"同级（T3），但偷不到 DK，
//   无法离线/锁后解密：密钥不泄露，仅操作可冒用
// - root 可 dump vault 内存拿 DK（dumpable=0 仅挡同 uid）——DK 驻留
//   窗口 = 解锁会话（门禁用户），与旧实现声明边界一致
// - 无门禁（LIVE_WK）模式 DK 常驻 vault 内存（等价旧单段模式从盘
//   自动恢复的自选弱保护）；LOCK 对该模式无意义，跳过
// - WK（无门禁包裹密钥）在 vault 冷启动时经主进程瞬时转交——无门禁
//   模式固有下界
//
// 静默性：无日志输出，strip-all。malformed 帧（超长）= 流失步 → 退出
// （fail-closed）；坏 op/坏参数 → ERR 响应继续（Java bug 不致死进程）。

#include <openssl/crypto.h>   // OPENSSL_cleanse
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "argon2.h"

// ===================== 常量 =====================

static const char KFILE_NAME[] = "sync_key.bin";
static const size_t DK_LEN = 32;
static const size_t SALT_LEN = 16;
static const size_t NONCE_LEN = 12;
static const size_t TAG_LEN = 16;
// 验证项明文标记：内容不参与任何比较（checkEntry 只看 GCM tag 与
// 长度），解密成功时的填充物——固定伪随机样式，不含身份特征
//（历史值 "SF-GATE-1/2" 是 APK 内可提取的应用指纹）
static const size_t MARK_LEN = 9;
static const uint8_t MARK_SEC[MARK_LEN] =
        {0xA7, 0x3C, 0x91, 0x5E, 0xD2, 0x48, 0x0B, 0xF6, 0x73};
static const uint8_t MARK_COE[MARK_LEN] =
        {0x4E, 0xB9, 0x06, 0xC5, 0x1D, 0x8A, 0xE3, 0x37, 0xD0};

// Argon2id 生产参数：t=3, m=64MiB, p=1（OWASP 推荐档）
static const uint32_t ARGON_T = 3;
static const uint32_t ARGON_M = 65536;
static const uint32_t ARGON_P = 1;

// 帧上限（双向）：防恶意巨帧 DoS；密码 u16 长度域对人类输入等同无限制
static const size_t FRAME_MAX = 131072;
static const size_t FSTREAM_CHUNK_MAX = 60000;

// 磁盘状态
enum DiskState {
    ST_NOTHING = 0,  // 无文件（首装 / 无门禁 LIVE_WK 销毁后）
    ST_LIVE_PW = 1,  // 有门禁，DK 在盘（密码包裹）
    ST_LIVE_WK = 2,  // 无门禁，DK 在盘（WK 包裹）
    ST_DEAD_PW = 3,  // 销毁后，验证项保留
    ST_CORRUPT = 4,  // 文件不可解析（fail-closed）
};

// 操作码（payload[0]）
enum Op {
    OP_PING = 0x01,
    OP_UNLOCK = 0x02,
    OP_LOCK = 0x03,
    OP_SEAL = 0x04,
    OP_OPEN = 0x05,
    OP_ENABLE_GATE = 0x06,
    OP_MIGRATE = 0x07,
    OP_REMOVE_GATE = 0x08,
    OP_SETWK = 0x09,
    OP_GETCK = 0x0A,
    OP_OPENCH = 0x0B,  // 响应信道密文解密（发送方向统一走 COMPOSE）
    OP_COMPOSE = 0x0C,
    OP_FSEAL_INIT = 0x0D,
    OP_FSEAL_UPDATE = 0x0E,
    OP_FSEAL_FINAL = 0x0F,
    // 0x10-0x12（FOPEN 流式解密）已删：全仓无调用方（加密产物为
    // write-only 设计）；空位不复用，OP_DESTROY 固定 0x13
    OP_DESTROY = 0x13,
};

// UNLOCK 结果码
enum UnlockResult {
    UR_SECURITY = 0,
    UR_COERCION = 1,
    UR_BAD = 2,
    UR_RATE = 3,
};

// MIGRATE/REMOVE_GATE 结果码
enum VerifyResult {
    VR_OK = 0,
    VR_BAD_CURRENT = 1,
    VR_ERROR = 2,
};

// STATUS 响应 mode 字节（与 DiskState 对齐）
static const uint8_t STATUS_GATE_ON_MASK = 0x80;  // 置位 = 门禁启用

// ===================== 基础工具 =====================

static void wipe(void* p, size_t n) { OPENSSL_cleanse(p, n); }

static bool read_file_all(const std::string& path, std::vector<uint8_t>& out) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0 || st.st_size > 65536) {
        close(fd);
        return false;
    }
    out.resize((size_t) st.st_size);
    size_t done = 0;
    while (done < out.size()) {
        ssize_t r = read(fd, out.data() + done, out.size() - done);
        if (r <= 0) {
            close(fd);
            return false;
        }
        done += (size_t) r;
    }
    close(fd);
    return true;
}

// 原子写：tmp + fsync + rename（中途死进程不留半截密文）
static bool write_file_atomic(const std::string& path, const uint8_t* data, size_t n) {
    std::string tmp = path + ".tmp";
    int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    size_t done = 0;
    bool ok = true;
    while (done < n) {
        ssize_t w = write(fd, data + done, n - done);
        if (w <= 0) {
            ok = false;
            break;
        }
        done += (size_t) w;
    }
    if (ok) ok = fsync(fd) == 0;
    close(fd);
    if (!ok) {
        unlink(tmp.c_str());
        return false;
    }
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        unlink(tmp.c_str());
        return false;
    }
    return true;
}

static bool rand_bytes(uint8_t* out, size_t n) { return RAND_bytes(out, (int) n) == 1; }

// ===================== AES-256-GCM 原语（EVP）=====================

static bool gcm_encrypt(const uint8_t key[32], const uint8_t nonce[12],
                        const uint8_t* pt, size_t ptLen,
                        uint8_t* outCt /* ptLen+16 */, size_t* outLen) {
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return false;
    bool ok = EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1 &&
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) == 1 &&
              EVP_EncryptInit_ex(ctx, nullptr, nullptr, key, nonce) == 1;
    int len = 0;
    if (ok && ptLen > 0) {
        ok = EVP_EncryptUpdate(ctx, outCt, &len, pt, (int) ptLen) == 1;
    }
    if (ok) {
        ok = EVP_EncryptFinal_ex(ctx, outCt + ptLen, &len) == 1 &&
             EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, outCt + ptLen) == 1;
    }
    if (ok && outLen) *outLen = ptLen + 16;
    EVP_CIPHER_CTX_free(ctx);
    return ok;
}

// ctLen 含尾部 16B tag。tag 校验失败/任何错误 → false（即密码错误判定点）
static bool gcm_decrypt(const uint8_t key[32], const uint8_t nonce[12],
                        const uint8_t* ct, size_t ctLen,
                        uint8_t* ptOut, size_t* ptLen) {
    if (ctLen < 16) return false;
    size_t body = ctLen - 16;
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return false;
    bool ok = EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) == 1 &&
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) == 1 &&
              EVP_DecryptInit_ex(ctx, nullptr, nullptr, key, nonce) == 1;
    int len = 0;
    if (ok && body > 0) {
        ok = EVP_DecryptUpdate(ctx, ptOut, &len, ct, (int) body) == 1;
    }
    if (ok) {
        uint8_t tag[16];
        memcpy(tag, ct + body, 16);
        ok = EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, 16, tag) == 1 &&
             EVP_DecryptFinal_ex(ctx, ptOut + (body > 0 ? len : 0), &len) == 1;
    }
    if (ok && ptLen) *ptLen = body;
    EVP_CIPHER_CTX_free(ctx);
    return ok;
}

static bool argon_derive(const uint8_t* pw, size_t pwLen, const uint8_t salt[16],
                         uint8_t out[32]) {
    return argon2_hash(ARGON_T, ARGON_M, ARGON_P, pw, pwLen, salt, 16,
                       out, 32, nullptr, 0, Argon2_id, ARGON2_VERSION_NUMBER) == ARGON2_OK;
}

// CK = HMAC-SHA256(DK, info)：daemon 信道密钥（确定性派生，锁-解循环后
// 信道自动恢复；HMAC 单向，CK 泄露不危及 DK）
static const char CK_INFO[] = "ScreenshotFaker/channel/v1";
static void derive_ck(const uint8_t dk[32], uint8_t out[32]) {
    unsigned int len = 32;
    HMAC(EVP_sha256(), dk, 32, (const unsigned char*) CK_INFO, sizeof(CK_INFO) - 1,
         out, &len);
}

// ===================== 帧编解码辅助 =====================

struct Reader {
    const uint8_t* p;
    size_t left;
    bool bad;
    explicit Reader(const uint8_t* data, size_t n) : p(data), left(n), bad(false) {}
    uint8_t u8() {
        if (bad || left < 1) { bad = true; return 0; }
        uint8_t v = p[0];
        p += 1; left -= 1;
        return v;
    }
    uint16_t u16() {
        if (bad || left < 2) { bad = true; return 0; }
        uint16_t v = (uint16_t) ((p[0] << 8) | p[1]);
        p += 2; left -= 2;
        return v;
    }
    uint32_t u32() {
        if (bad || left < 4) { bad = true; return 0; }
        uint32_t v = ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) |
                     ((uint32_t) p[2] << 8) | (uint32_t) p[3];
        p += 4; left -= 4;
        return v;
    }
    // 取 n 字节（返回内部指针，不拷贝）
    const uint8_t* take(size_t n) {
        if (bad || left < n) { bad = true; return nullptr; }
        const uint8_t* v = p;
        p += n; left -= n;
        return v;
    }
};

struct Writer {
    std::vector<uint8_t> buf;
    void u8(uint8_t v) { buf.push_back(v); }
    void u16(uint16_t v) { buf.push_back((uint8_t) (v >> 8)); buf.push_back((uint8_t) v); }
    void u32(uint32_t v) {
        buf.push_back((uint8_t) (v >> 24)); buf.push_back((uint8_t) (v >> 16));
        buf.push_back((uint8_t) (v >> 8)); buf.push_back((uint8_t) v);
    }
    void bytes(const uint8_t* p, size_t n) { buf.insert(buf.end(), p, p + n); }
};

// ===================== VaultCore：状态机与操作 =====================

struct VaultCore {
    std::string dir;

    // 会话态
    uint8_t dk[32];
    bool dkValid;
    // 在线试密码限速
    int failCount;
    int64_t blockedUntilSec;

    // 磁盘态缓存（loadState 解析；写操作后同步更新）。
    // 双验证项恒缓存——vault 不感知第二项是真是假（恒定结构）
    int state;
    uint8_t secSalt[16], secNonce[12], secCt[MARK_LEN + 16];
    uint8_t coeSalt[16], coeNonce[12], coeCt[MARK_LEN + 16];
    uint8_t keySalt[16], wrapNonce[12], wrapCt[DK_LEN + 16];

    // 流式加密上下文（单活跃流，Java 侧串行化；仅 seal 方向）
    EVP_CIPHER_CTX* fsCtx;
    bool fsActive;

    std::string kpath() const { return dir + "/" + KFILE_NAME; }

    VaultCore() : state(ST_NOTHING), dkValid(false), failCount(0),
                  blockedUntilSec(0), fsCtx(nullptr), fsActive(false) {
        memset(dk, 0, sizeof(dk));
        memset(secSalt, 0, sizeof(secSalt)); memset(secNonce, 0, sizeof(secNonce));
        memset(secCt, 0, sizeof(secCt));
        memset(coeSalt, 0, sizeof(coeSalt)); memset(coeNonce, 0, sizeof(coeNonce));
        memset(coeCt, 0, sizeof(coeCt));
        memset(keySalt, 0, sizeof(keySalt)); memset(wrapNonce, 0, sizeof(wrapNonce));
        memset(wrapCt, 0, sizeof(wrapCt));
    }

    ~VaultCore() {
        wipe(dk, sizeof(dk));
        if (fsCtx) EVP_CIPHER_CTX_free(fsCtx);
    }

    void resetSession() {
        wipe(dk, sizeof(dk));
        dkValid = false;
        if (fsCtx) { EVP_CIPHER_CTX_free(fsCtx); fsCtx = nullptr; }
        fsActive = false;
    }

    // ---- 磁盘解析 ----

    void loadState() {
        state = ST_NOTHING;
        std::vector<uint8_t> data;
        if (!read_file_all(kpath(), data)) {
            // 文件不存在 → NOTHING；读失败（IO）按 CORRUPT 处理（fail-closed）
            struct stat st;
            state = (stat(kpath().c_str(), &st) == 0) ? ST_CORRUPT : ST_NOTHING;
            return;
        }
        if (data.size() < 3 || data[0] != 'K' || data[1] != 2) {
            state = ST_CORRUPT;
            return;
        }
        Reader r(data.data() + 2, data.size() - 2);
        uint8_t st = r.u8();
        if (st == ST_LIVE_WK) {
            const uint8_t* salt = r.take(16);
            const uint8_t* nonce = r.take(12);
            const uint8_t* ct = r.take(DK_LEN + 16);
            // 严格解析（恒定结构配套）：长度必须精确——截断/追加均 CORRUPT
            if (!salt || !nonce || !ct || r.left != 0) { state = ST_CORRUPT; return; }
            memcpy(keySalt, salt, 16);
            memcpy(wrapNonce, nonce, 12);
            memcpy(wrapCt, ct, DK_LEN + 16);
            state = ST_LIVE_WK;
            return;
        }
        if (st != ST_LIVE_PW && st != ST_DEAD_PW) {
            state = ST_CORRUPT;
            return;
        }
        // 双验证项恒在读入（真项或哑项——vault 不感知、不区分）
        const uint8_t* ss = r.take(16), *sn = r.take(12), *sc = r.take(MARK_LEN + 16);
        const uint8_t* cs = r.take(16), *cn = r.take(12), *cc = r.take(MARK_LEN + 16);
        if (!ss || !sn || !sc || !cs || !cn || !cc) { state = ST_CORRUPT; return; }
        memcpy(secSalt, ss, 16); memcpy(secNonce, sn, 12); memcpy(secCt, sc, MARK_LEN + 16);
        memcpy(coeSalt, cs, 16); memcpy(coeNonce, cn, 12); memcpy(coeCt, cc, MARK_LEN + 16);
        if (st == ST_DEAD_PW) {
            if (r.left != 0) { state = ST_CORRUPT; return; }
            state = ST_DEAD_PW;
            return;
        }
        const uint8_t* ks = r.take(16), *wn = r.take(12), *wc = r.take(DK_LEN + 16);
        if (!ks || !wn || !wc || r.left != 0) { state = ST_CORRUPT; return; }
        memcpy(keySalt, ks, 16); memcpy(wrapNonce, wn, 12); memcpy(wrapCt, wc, DK_LEN + 16);
        state = ST_LIVE_PW;
    }

    // ---- 磁盘构造 ----

    void putEntry(Writer& w, const uint8_t salt[16], const uint8_t nonce[12],
                  const uint8_t ct[MARK_LEN + 16]) {
        w.bytes(salt, 16);
        w.bytes(nonce, 12);
        w.bytes(ct, MARK_LEN + 16);
    }

    // 生成一个验证项（随机 salt + Argon2id(pw) 包裹 MARK）
    bool makeEntry(const uint8_t* pw, size_t pwLen, const uint8_t mark[MARK_LEN],
                   uint8_t saltOut[16], uint8_t nonceOut[12], uint8_t ctOut[MARK_LEN + 16]) {
        uint8_t k[32];
        if (!rand_bytes(saltOut, 16) || !rand_bytes(nonceOut, 12)) return false;
        if (!argon_derive(pw, pwLen, saltOut, k)) { wipe(k, sizeof(k)); return false; }
        bool ok = gcm_encrypt(k, nonceOut, mark, MARK_LEN, ctOut, nullptr);
        wipe(k, sizeof(k));
        return ok;
    }

    // 写 LIVE_WK：DK 以 wkRaw 包裹
    bool writeLiveWk(const uint8_t dkIn[32], const uint8_t wkRaw[32]) {
        uint8_t salt[16], nonce[12], ct[DK_LEN + 16];
        if (!rand_bytes(salt, 16) || !rand_bytes(nonce, 12)) return false;
        if (!gcm_encrypt(wkRaw, nonce, dkIn, DK_LEN, ct, nullptr)) return false;
        Writer w;
        w.u8('K'); w.u8(2); w.u8((uint8_t) ST_LIVE_WK);
        w.bytes(salt, 16); w.bytes(nonce, 12); w.bytes(ct, DK_LEN + 16);
        if (!write_file_atomic(kpath(), w.buf.data(), w.buf.size())) return false;
        memcpy(keySalt, salt, 16); memcpy(wrapNonce, nonce, 12);
        memcpy(wrapCt, ct, DK_LEN + 16);
        state = ST_LIVE_WK;
        return true;
    }

    // 写 LIVE_PW：验证项(sec,coe) + DK 以 Argon2id(secPw) 包裹。
    // 恒定结构：胁迫验证项恒写——未设置胁迫密码（coeLen==0）时为
    // rand_bytes 直填的哑项（salt/nonce/ct 三段皆均匀随机，与真项
    // 计算不可区分；对任何密码 GCM tag 必然失败 2^-128）。文件长度
    // 与内容分布均不编码"是否配置胁迫密码"
    bool writeLivePw(const uint8_t* secPw, size_t secLen, const uint8_t* coePw, size_t coeLen,
                     const uint8_t dkIn[32]) {
        if (coeLen > 0 && coePw == nullptr) return false;
        uint8_t sSalt[16], sNonce[12], sCt[MARK_LEN + 16];
        uint8_t cSalt[16], cNonce[12], cCt[MARK_LEN + 16];
        if (!makeEntry(secPw, secLen, MARK_SEC, sSalt, sNonce, sCt)) return false;
        bool coeOk = coeLen > 0
                     ? makeEntry(coePw, coeLen, MARK_COE, cSalt, cNonce, cCt)
                     : (rand_bytes(cSalt, 16) && rand_bytes(cNonce, 12) &&
                        rand_bytes(cCt, MARK_LEN + 16));
        if (!coeOk) return false;
        uint8_t k[32], salt[16], nonce[12], ct[DK_LEN + 16];
        bool ok = rand_bytes(salt, 16) && rand_bytes(nonce, 12) &&
                  argon_derive(secPw, secLen, salt, k) &&
                  gcm_encrypt(k, nonce, dkIn, DK_LEN, ct, nullptr);
        wipe(k, sizeof(k));
        if (!ok) return false;
        Writer w;
        w.u8('K'); w.u8(2); w.u8((uint8_t) ST_LIVE_PW);
        putEntry(w, sSalt, sNonce, sCt);
        putEntry(w, cSalt, cNonce, cCt);
        w.bytes(salt, 16); w.bytes(nonce, 12); w.bytes(ct, DK_LEN + 16);
        if (!write_file_atomic(kpath(), w.buf.data(), w.buf.size())) return false;
        memcpy(secSalt, sSalt, 16); memcpy(secNonce, sNonce, 12); memcpy(secCt, sCt, MARK_LEN + 16);
        memcpy(coeSalt, cSalt, 16); memcpy(coeNonce, cNonce, 12); memcpy(coeCt, cCt, MARK_LEN + 16);
        memcpy(keySalt, salt, 16); memcpy(wrapNonce, nonce, 12); memcpy(wrapCt, ct, DK_LEN + 16);
        state = ST_LIVE_PW;
        return true;
    }

    // 写 LIVE_PW：保留现有验证项（DEAD_PW → 销毁后首解的 DK 重生路径——
    // 验证项含胁迫密码，随重生丢失会让胁迫保护静默失效）。
    // 双验证项按字节照抄缓存（哑照哑、真照真）——本路径必须对第二项
    // 真假无感知（恒定结构），否则重生即泄露 COE 配置差异
    bool writeLivePwPreserving(const uint8_t* secPw, size_t secLen, const uint8_t dkIn[32]) {
        uint8_t k[32], salt[16], nonce[12], ct[DK_LEN + 16];
        bool ok = rand_bytes(salt, 16) && rand_bytes(nonce, 12) &&
                  argon_derive(secPw, secLen, salt, k) &&
                  gcm_encrypt(k, nonce, dkIn, DK_LEN, ct, nullptr);
        wipe(k, sizeof(k));
        if (!ok) return false;
        Writer w;
        w.u8('K'); w.u8(2); w.u8((uint8_t) ST_LIVE_PW);
        putEntry(w, secSalt, secNonce, secCt);
        putEntry(w, coeSalt, coeNonce, coeCt);
        w.bytes(salt, 16); w.bytes(nonce, 12); w.bytes(ct, DK_LEN + 16);
        if (!write_file_atomic(kpath(), w.buf.data(), w.buf.size())) return false;
        memcpy(keySalt, salt, 16); memcpy(wrapNonce, nonce, 12); memcpy(wrapCt, ct, DK_LEN + 16);
        state = ST_LIVE_PW;
        return true;
    }

    // 写 DEAD_PW：保留现有验证项（就地取缓存——coercion 路径与销毁路径
    // 共用；双项照抄，哑照哑真照真）
    bool writeDeadPw() {
        Writer w;
        w.u8('K'); w.u8(2); w.u8((uint8_t) ST_DEAD_PW);
        putEntry(w, secSalt, secNonce, secCt);
        putEntry(w, coeSalt, coeNonce, coeCt);
        if (!write_file_atomic(kpath(), w.buf.data(), w.buf.size())) return false;
        state = ST_DEAD_PW;
        return true;
    }

    // 写 LIVE_PW：DK 以外部提供的键包裹，keySalt 原样保留（LIVE_PW
    // 胁迫重生专用：缓存键派生自 keySalt 本身——复用后文件三 salt
    // 依旧互异、无重生痕迹；仅 fresh nonce + 新密文）
    bool writeLivePwWrapCached(const uint8_t key[32], const uint8_t dkIn[32]) {
        uint8_t nonce[12], ct[DK_LEN + 16];
        if (!rand_bytes(nonce, 12)) return false;
        if (!gcm_encrypt(key, nonce, dkIn, DK_LEN, ct, nullptr)) return false;
        Writer w;
        w.u8('K'); w.u8(2); w.u8((uint8_t) ST_LIVE_PW);
        putEntry(w, secSalt, secNonce, secCt);
        putEntry(w, coeSalt, coeNonce, coeCt);
        w.bytes(keySalt, 16); w.bytes(nonce, 12); w.bytes(ct, DK_LEN + 16);
        if (!write_file_atomic(kpath(), w.buf.data(), w.buf.size())) return false;
        memcpy(wrapNonce, nonce, 12);
        memcpy(wrapCt, ct, DK_LEN + 16);
        state = ST_LIVE_PW;
        return true;
    }

    // ---- 验证原语 ----

    bool tryUnwrapDk(const uint8_t* pw, size_t pwLen, uint8_t dkOut[32]) {
        uint8_t k[32];
        bool derived = false;
        bool ok = tryUnwrapDkKey(pw, pwLen, dkOut, k, &derived);
        wipe(k, sizeof(k));
        return ok;
    }

    // tryUnwrapDk 扩展：派生键与"派生是否成功"外传——LIVE_PW 胁迫
    // 路径复用该键作重生包裹键（该键派生自 keySalt，对正确的胁迫
    // 密码即合法包裹键；GCM 失败只说明密码非当前包裹密码，键本身与
    // 任何 Argon2id 输出同分布），省第 3 次 Argon2id（解锁时序零差）
    bool tryUnwrapDkKey(const uint8_t* pw, size_t pwLen, uint8_t dkOut[32],
                        uint8_t kOut[32], bool* derived) {
        *derived = false;
        if (state != ST_LIVE_PW) return false;
        if (!argon_derive(pw, pwLen, keySalt, kOut)) return false;
        *derived = true;
        size_t n = 0;
        return gcm_decrypt(kOut, wrapNonce, wrapCt, DK_LEN + 16, dkOut, &n) && n == DK_LEN;
    }

    bool checkEntry(const uint8_t* pw, size_t pwLen, const uint8_t salt[16],
                    const uint8_t nonce[12], const uint8_t ct[MARK_LEN + 16]) {
        uint8_t k[32], plain[MARK_LEN + 16];
        if (!argon_derive(pw, pwLen, salt, k)) { wipe(k, sizeof(k)); return false; }
        size_t n = 0;
        bool ok = gcm_decrypt(k, nonce, ct, MARK_LEN + 16, plain, &n) && n == MARK_LEN;
        wipe(k, sizeof(k));
        wipe(plain, sizeof(plain));
        return ok;
    }

    // 恒定结构下胁迫验证项恒在（真项或哑项）：直接实测。哑项对任何
    // 密码 GCM tag 必然失败（2^-128）——错误密码验证成本恒为 2 次
    // Argon2id（unwrap 失败 + 本实测），时序抹平由文件内哑项天然
    // 承继；vault 永远不知道第二项是真是假
    bool tryCoe(const uint8_t* pw, size_t pwLen) {
        return checkEntry(pw, pwLen, coeSalt, coeNonce, coeCt);
    }

    bool trySec(const uint8_t* pw, size_t pwLen) {
        return checkEntry(pw, pwLen, secSalt, secNonce, secCt);
    }

    // 限速记录（BAD 路径）
    void recordFailure() {
        failCount++;
        if (failCount >= 5) {
            int64_t backoff = 1LL << (failCount - 5);
            if (backoff > 60) backoff = 60;
            blockedUntilSec = (int64_t) time(nullptr) + backoff;
        }
    }

    void resetFailure() {
        failCount = 0;
        blockedUntilSec = 0;
    }

    // 重生：新随机 DK 就地改写包裹（验证项照抄——恒定结构不变，状态
    // 字节与文件长度前后一致），会话即刻以新 DK 全功能可用。旧 DK
    // 包裹被覆盖 = 历史密文立即孤儿化（DK 是唯一解路径，与"删包裹"
    // 等强度的密钥死亡）。包裹键两种来源：
    // - cachedKey（LIVE_PW 胁迫命中）：复用 unwrap 已派生的键
    //   Argon2id(pw, keySalt)，keySalt 原样保留——省第 3 次派生，与
    //   错误密码路径等时（解锁时序零差）
    // - 现派生（DEAD_PW 安全/胁迫命中）：销毁态无包裹盐缓存，
    //   fresh salt + Argon2id(pw, salt)
    // 三个调用点语义同源：
    // - LIVE_PW 胁迫命中：旧钥死亡 + 会话无缝续演（同会话新建凭据/
    //   启动共享照常——封堵"销毁后功能全废"的演出穿帮）
    // - DEAD_PW 安全密码首解：销毁后的正常恢复（原有语义）
    // - DEAD_PW 胁迫命中：与 LIVE_PW 路径演出一致
    bool rebirth(const uint8_t* pw, size_t pwLen, const uint8_t* cachedKey = nullptr) {
        uint8_t ndk[DK_LEN];
        if (!rand_bytes(ndk, DK_LEN)) return false;
        bool ok = cachedKey != nullptr
                  ? writeLivePwWrapCached(cachedKey, ndk)
                  : writeLivePwPreserving(pw, pwLen, ndk);
        if (!ok) {
            wipe(ndk, DK_LEN);
            return false;
        }
        memcpy(dk, ndk, DK_LEN);
        wipe(ndk, DK_LEN);
        dkValid = true;
        resetFailure();
        return true;
    }

    // ---- 操作实现（resp 写入 out；返回 false = 致命错误应退出）----

    // 响应 [0]=status [1]=mode(gateOn 位) [2]=dkReady（3 字节）
    void opStatus(Writer& out) {
        uint8_t mode = (uint8_t) state;
        if (state == ST_LIVE_PW || state == ST_DEAD_PW || state == ST_CORRUPT) {
            mode |= STATUS_GATE_ON_MASK;
        }
        out.u8(0);
        out.u8(mode);
        out.u8(dkValid ? 1 : 0);
    }

    void opUnlock(const uint8_t* pw, size_t pwLen, Writer& out) {
        if ((int64_t) time(nullptr) < blockedUntilSec) {
            // 等时陪跑（2 次哑派生，与 BAD 路径同成本）：返回码已与
            // BAD 同码，快速返回会让"处于限速窗"从时序侧信道泄露
            uint8_t sink[32], salt[16];
            if (rand_bytes(salt, 16)) argon_derive(pw, pwLen, salt, sink);
            if (rand_bytes(salt, 16)) argon_derive(pw, pwLen, salt, sink);
            wipe(sink, sizeof(sink));
            wipe(salt, sizeof(salt));
            out.u8(UR_RATE);
            return;
        }
        if (state == ST_LIVE_PW) {
            uint8_t cand[32], k[32];
            bool keyDerived = false;
            if (tryUnwrapDkKey(pw, pwLen, cand, k, &keyDerived)) {
                memcpy(dk, cand, DK_LEN);
                wipe(cand, sizeof(cand));
                wipe(k, sizeof(k));
                dkValid = true;
                resetFailure();
                // 等时陪跑（第 2 次 Argon2id，结果不参与判定）：安全
                // 路径与错误/胁迫路径同成本——否则解锁耗时本身即
                // "密码类型"信道（胁迫者可计时区分正常解锁与胁迫解锁）
                checkEntry(pw, pwLen, secSalt, secNonce, secCt);
                out.u8(UR_SECURITY);
                return;
            }
            wipe(cand, sizeof(cand));
            if (tryCoe(pw, pwLen)) {
                // 胁迫命中：就地重生——旧 DK 包裹被新随机 DK 覆盖（Java
                // 被拦截时密钥死亡同样已完成），会话以新 DK 全功能继续
                //（演出：用该密码正常解锁）。交 Java 跑完整序列
                //（DataStore/daemon/Keystore；vault 层已完成，Java 侧须
                // 跳过 OP_DESTROY——见 DefenseProtocol keepVaultSession）。
                // 包裹键复用 unwrap 缓存（keySalt 不变，见 rebirth）——
                // 零额外 Argon2id，与错误密码路径时序一致。写盘失败不
                // 暴露命中（与密码错同响应，销毁不发生）
                bool rb = keyDerived ? rebirth(pw, pwLen, k) : rebirth(pw, pwLen);
                wipe(k, sizeof(k));
                out.u8(rb ? UR_COERCION : UR_BAD);
                return;
            }
            wipe(k, sizeof(k));
            recordFailure();
            out.u8(UR_BAD);
            return;
        }
        if (state == ST_DEAD_PW) {
            if (tryCoe(pw, pwLen)) {
                // 销毁态胁迫命中：同样以胁迫密码重生（演出与 LIVE_PW 路径
                // 一致——销毁后首输胁迫密码 = 正常进入全新空 app）
                out.u8(rebirth(pw, pwLen) ? UR_COERCION : UR_BAD);
                return;
            }
            if (trySec(pw, pwLen)) {
                // 销毁后首解：新生 DK（历史密文已孤儿化，新会话全新密钥），
                // 验证项原样保留（含胁迫密码——胁迫保护跨销毁存活）
                if (rebirth(pw, pwLen)) {
                    out.u8(UR_SECURITY);
                } else {
                    recordFailure();
                    out.u8(UR_BAD);
                }
                return;
            }
            recordFailure();
            out.u8(UR_BAD);
            return;
        }
        // LIVE_WK（无门禁无解锁语义）/NOTHING/CORRUPT
        out.u8(UR_BAD);
    }

    void opLock(Writer& out) {
        // 无门禁（LIVE_WK）跳过：DK 常驻等价旧单段模式自选弱保护；
        // 有门禁清 DK 收窄驻留窗口（息屏/后台/前台无操作锁定）
        if (state == ST_LIVE_PW || state == ST_DEAD_PW) {
            resetSession();
        }
        out.u8(0);
    }

    void opSeal(const uint8_t* pt, size_t n, Writer& out) {
        if (!dkValid || n > 65536) { out.u8(1); return; }
        uint8_t nonce[12], ct[65536 + 16];
        if (!rand_bytes(nonce, 12) || !gcm_encrypt(dk, nonce, pt, n, ct, nullptr)) {
            out.u8(1);
            return;
        }
        out.u8(0);
        out.bytes(nonce, 12);
        out.bytes(ct, n + 16);
    }

    void opOpen(const uint8_t* blob, size_t n, Writer& out) {
        if (!dkValid || n < 12 + 16 || n > 65536 + 16) { out.u8(1); return; }
        uint8_t pt[65536];
        size_t pn = 0;
        if (!gcm_decrypt(dk, blob, blob + 12, n - 12, pt, &pn)) {
            out.u8(1);
            return;
        }
        out.u8(0);
        out.u16((uint16_t) pn);
        out.bytes(pt, pn);
        wipe(pt, sizeof(pt));
    }

    // cur 验证 + dk 解出（MIGRATE/REMOVE_GATE 共用）。
    // ok: dkOut 有效；badCurrent: 密码错（计入限速）；error: 状态不可用
    int verifyCurrent(const uint8_t* cur, size_t curLen, uint8_t dkOut[32]) {
        if (state == ST_LIVE_PW) {
            if (tryUnwrapDk(cur, curLen, dkOut)) return VR_OK;
            if (tryCoe(cur, curLen)) {
                // 胁迫密码改密：孤儿化重生成（门禁照常，历史密文永不可解）
                if (!rand_bytes(dkOut, DK_LEN)) return VR_ERROR;
                return VR_OK;
            }
            return VR_BAD_CURRENT;
        }
        if (state == ST_DEAD_PW) {
            if (trySec(cur, curLen) || tryCoe(cur, curLen)) {
                if (!rand_bytes(dkOut, DK_LEN)) return VR_ERROR;
                return VR_OK;
            }
            return VR_BAD_CURRENT;
        }
        return VR_ERROR;
    }

    void opEnableGate(const uint8_t* sec, size_t secLen, const uint8_t* coe, size_t coeLen,
                      Writer& out) {
        if (state != ST_LIVE_WK || !dkValid || secLen == 0) { out.u8(1); return; }
        if (coeLen > 0 && coeLen == secLen && memcmp(sec, coe, secLen) == 0) {
            // 两级密码相同 = 胁迫密码永不可达（UNLOCK 先试 DK 包裹）= 死密码
            out.u8(1);
            return;
        }
        if (!writeLivePw(sec, secLen, coeLen > 0 ? coe : nullptr, coeLen, dk)) {
            out.u8(1);
            return;
        }
        out.u8(0);
    }

    void opMigrate(const uint8_t* cur, size_t curLen, const uint8_t* sec, size_t secLen,
                   const uint8_t* coe, size_t coeLen, Writer& out) {
        if ((state != ST_LIVE_PW && state != ST_DEAD_PW) || secLen == 0) { out.u8(VR_ERROR); return; }
        if (coeLen > 0 && coeLen == secLen && memcmp(sec, coe, secLen) == 0) {
            out.u8(VR_ERROR);
            return;
        }
        // 退避窗内拒绝：与 UNLOCK 共享限速计数（封堵经 MIGRATE 旁路
        // 在线试当前密码）；返回码与密码错一致，不暴露限速存在
        if ((int64_t) time(nullptr) < blockedUntilSec) {
            out.u8(VR_BAD_CURRENT);
            return;
        }
        uint8_t dk2[32];
        int v = verifyCurrent(cur, curLen, dk2);
        if (v == VR_BAD_CURRENT) {
            recordFailure();
            out.u8(VR_BAD_CURRENT);
            return;
        }
        if (v == VR_ERROR || !writeLivePw(sec, secLen, coeLen > 0 ? coe : nullptr, coeLen, dk2)) {
            wipe(dk2, sizeof(dk2));
            out.u8(VR_ERROR);
            return;
        }
        memcpy(dk, dk2, DK_LEN);
        wipe(dk2, sizeof(dk2));
        dkValid = true;
        resetFailure();
        out.u8(VR_OK);
    }

    void opRemoveGate(const uint8_t* cur, size_t curLen, Writer& out) {
        if (state != ST_LIVE_PW && state != ST_DEAD_PW) { out.u8(VR_ERROR); return; }
        // 退避窗内拒绝（同 opMigrate：与 UNLOCK 共享限速计数）
        if ((int64_t) time(nullptr) < blockedUntilSec) {
            out.u8(VR_BAD_CURRENT);
            return;
        }
        uint8_t dk2[32], nwk[32];
        int v = verifyCurrent(cur, curLen, dk2);
        if (v == VR_BAD_CURRENT) {
            recordFailure();
            out.u8(VR_BAD_CURRENT);
            return;
        }
        if (v == VR_ERROR || !rand_bytes(nwk, 32) || !writeLiveWk(dk2, nwk)) {
            wipe(dk2, sizeof(dk2)); wipe(nwk, sizeof(nwk));
            out.u8(VR_ERROR);
            return;
        }
        memcpy(dk, dk2, DK_LEN);
        wipe(dk2, sizeof(dk2));
        dkValid = true;
        resetFailure();
        out.u8(VR_OK);
        out.bytes(nwk, 32);  // 新 WK 交 app 侧 Keystore 包裹落盘
    }

    void opSetwk(const uint8_t* wkIn, size_t n, Writer& out) {
        if (n != 32) { out.u8(1); return; }
        if (state == ST_LIVE_WK) {
            uint8_t dk2[32];
            size_t pn = 0;
            bool ok = gcm_decrypt(wkIn, wrapNonce, wrapCt, DK_LEN + 16, dk2, &pn) &&
                      pn == DK_LEN;
            if (ok) {
                memcpy(dk, dk2, DK_LEN);
                dkValid = true;
            }
            wipe(dk2, sizeof(dk2));
            out.u8(ok ? 0 : 1);
            return;
        }
        if (state == ST_NOTHING) {
            uint8_t ndk[32];
            if (!rand_bytes(ndk, DK_LEN) || !writeLiveWk(ndk, wkIn)) {
                wipe(ndk, sizeof(ndk));
                out.u8(1);
                return;
            }
            memcpy(dk, ndk, DK_LEN);
            wipe(ndk, sizeof(ndk));
            dkValid = true;
            out.u8(0);
            return;
        }
        // 有门禁状态：WK 无语义（PW 包裹）；CORRUPT：fail-closed
        out.u8(1);
    }

    void opGetck(Writer& out) {
        if (!dkValid) { out.u8(1); return; }
        uint8_t ck[32];
        derive_ck(dk, ck);
        out.u8(0);
        out.bytes(ck, 32);
        wipe(ck, sizeof(ck));
    }

    void ckOf(uint8_t ck[32]) { derive_ck(dk, ck); }

    void opOpench(const uint8_t* blob, size_t n, Writer& out) {
        if (!dkValid || n < 12 + 16 || n > 65536 + 16) { out.u8(1); return; }
        uint8_t ck[32], pt[65536];
        ckOf(ck);
        size_t pn = 0;
        if (!gcm_decrypt(ck, blob, blob + 12, n - 12, pt, &pn)) {
            wipe(ck, sizeof(ck));
            out.u8(1);
            return;
        }
        wipe(ck, sizeof(ck));
        out.u8(0);
        out.u16((uint16_t) pn);
        out.bytes(pt, pn);
        wipe(pt, sizeof(pt));
    }

    // 组装 daemon 信道帧：parts 交错拼接（kind1 = DK 解密的 _sec 槽位），
    // 追加 "\x1C<unix秒>"，CK 加密返回（凭据明文不进 Java）
    void opCompose(Reader& r, Writer& out) {
        if (!dkValid) { out.u8(1); return; }
        uint32_t nParts = r.u32();
        if (r.bad || nParts == 0 || nParts > 64) { out.u8(1); return; }
        std::vector<uint8_t> plain;
        for (uint32_t i = 0; i < nParts; i++) {
            uint8_t kind = r.u8();
            uint32_t len = r.u32();
            if (r.bad || len > 65536) { out.u8(1); return; }
            const uint8_t* data = r.take(len);
            if (r.bad || data == nullptr) { out.u8(1); return; }
            if (kind == 0) {
                plain.insert(plain.end(), data, data + len);
            } else if (kind == 1) {
                if (len < 12 + 16) { out.u8(1); return; }
                uint8_t pt[65536];
                size_t pn = 0;
                if (!gcm_decrypt(dk, data, data + 12, len - 12, pt, &pn)) {
                    wipe(pt, sizeof(pt));
                    out.u8(1);
                    return;
                }
                plain.insert(plain.end(), pt, pt + pn);
                wipe(pt, sizeof(pt));
            } else {
                out.u8(1);
                return;
            }
        }
        // 时间戳（daemon 侧协议：cmd\x1Cts；ts 由 vault 内置时钟生成）
        char ts[24];
        snprintf(ts, sizeof(ts), "%lld", (long long) time(nullptr));
        const char sep = '\x1C';
        plain.push_back((uint8_t) sep);
        plain.insert(plain.end(), ts, ts + strlen(ts));
        if (plain.size() > 65536) { out.u8(1); return; }
        // CK 加密（与 daemon recv_encrypted 的 [len][nonce][ct] 帧中
        // nonce+ct 部分对应；长度前缀由 Java 侧写 socket 时补）
        uint8_t ck[32], nonce[12], ct[65536 + 16];
        ckOf(ck);
        bool enc = rand_bytes(nonce, 12) &&
                   gcm_encrypt(ck, nonce, plain.data(), plain.size(), ct, nullptr);
        size_t plainLen = plain.size();
        wipe(ck, sizeof(ck));
        wipe(plain.data(), plainLen);
        if (!enc) { out.u8(1); return; }
        out.u8(0);
        out.bytes(nonce, 12);
        out.bytes(ct, plainLen + 16);
    }

    // ---- 流式文件加密（磁贴大文件；格式 [12B nonce][ct...][16B tag]
    // 与旧 CipherOutputStream 产物同构）----

    void freeStream() {
        if (fsCtx) { EVP_CIPHER_CTX_free(fsCtx); fsCtx = nullptr; }
        fsActive = false;
    }

    void opFsealInit(Writer& out) {
        if (!dkValid) { out.u8(1); return; }
        freeStream();
        uint8_t nonce[12];
        if (!rand_bytes(nonce, 12)) { out.u8(1); return; }
        fsCtx = EVP_CIPHER_CTX_new();
        if (!fsCtx ||
            EVP_EncryptInit_ex(fsCtx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1 ||
            EVP_CIPHER_CTX_ctrl(fsCtx, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr) != 1 ||
            EVP_EncryptInit_ex(fsCtx, nullptr, nullptr, dk, nonce) != 1) {
            freeStream();
            out.u8(1);
            return;
        }
        fsActive = true;
        out.u8(0);
        out.bytes(nonce, 12);
    }

    void opFsealUpdate(const uint8_t* pt, size_t n, Writer& out) {
        if (!fsActive || n > FSTREAM_CHUNK_MAX || n == 0) { out.u8(1); return; }
        std::vector<uint8_t> ct(n);
        int len = 0;
        if (EVP_EncryptUpdate(fsCtx, ct.data(), &len, pt, (int) n) != 1 || len != (int) n) {
            freeStream();
            out.u8(1);
            return;
        }
        out.u8(0);
        out.u32(n);
        out.bytes(ct.data(), n);
    }

    void opFsealFinal(Writer& out) {
        if (!fsActive) { out.u8(1); return; }
        uint8_t tag[16];
        int len = 0;
        bool ok = EVP_EncryptFinal_ex(fsCtx, tag, &len) == 1 &&
                  EVP_CIPHER_CTX_ctrl(fsCtx, EVP_CTRL_GCM_GET_TAG, 16, tag) == 1;
        freeStream();
        if (!ok) { out.u8(1); return; }
        out.u8(0);
        out.bytes(tag, 16);
    }

    void opDestroy(Writer& out) {
        if (state == ST_LIVE_PW) {
            // 验证项保留（门禁行为一致）；DK 包裹随改写消失
            if (writeDeadPw()) {
                resetSession();
                out.u8(0);
            } else {
                out.u8(1);
            }
            return;
        }
        if (state == ST_LIVE_WK) {
            // 无门禁：验证项无需保留，直接删文件（下次 SETWK 走首建）
            resetSession();
            unlink(kpath().c_str());
            unlink((kpath() + ".tmp").c_str());
            loadState();
            out.u8(0);
            return;
        }
        // DEAD_PW / NOTHING / CORRUPT：幂等
        resetSession();
        out.u8(0);
    }

    // ---- 帧分发 ----
    // 返回值：true = 正常（resp 已写）；false = 流失步（调用方应退出）
    bool process(const uint8_t* payload, size_t len, std::vector<uint8_t>& respOut) {
        if (len < 1 || len > FRAME_MAX) return false;
        Writer out;
        Reader r(payload + 1, len - 1);
        switch (payload[0]) {
            case OP_PING:
                opStatus(out);
                break;
            case OP_UNLOCK: {
                uint16_t n = r.u16();
                const uint8_t* pw = r.take(n);
                if (r.bad || pw == nullptr) { out.u8(UR_BAD); break; }
                opUnlock(pw, n, out);
                break;
            }
            case OP_LOCK:
                opLock(out);
                break;
            case OP_SEAL: {
                uint16_t n = r.u16();
                const uint8_t* pt = r.take(n);
                if (r.bad || pt == nullptr) { out.u8(1); break; }
                opSeal(pt, n, out);
                break;
            }
            case OP_OPEN: {
                // 帧内剩余全部 = nonce(12) + ct + tag(16)
                size_t total = r.left;
                if (r.bad || total < 12 + 16) { out.u8(1); break; }
                const uint8_t* blob = r.take(total);
                if (blob == nullptr) { out.u8(1); break; }
                opOpen(blob, total, out);
                break;
            }
            case OP_ENABLE_GATE: {
                uint16_t sn = r.u16();
                const uint8_t* sec = r.take(sn);
                uint16_t cn = r.u16();
                const uint8_t* coe = cn > 0 ? r.take(cn) : nullptr;
                if (r.bad || sec == nullptr || (cn > 0 && coe == nullptr)) { out.u8(1); break; }
                opEnableGate(sec, sn, coe, cn, out);
                break;
            }
            case OP_MIGRATE: {
                uint16_t un = r.u16();
                const uint8_t* cur = r.take(un);
                uint16_t sn = r.u16();
                const uint8_t* sec = r.take(sn);
                uint16_t cn = r.u16();
                const uint8_t* coe = cn > 0 ? r.take(cn) : nullptr;
                if (r.bad || cur == nullptr || sec == nullptr || (cn > 0 && coe == nullptr)) {
                    out.u8(VR_ERROR);
                    break;
                }
                opMigrate(cur, un, sec, sn, coe, cn, out);
                break;
            }
            case OP_REMOVE_GATE: {
                uint16_t un = r.u16();
                const uint8_t* cur = r.take(un);
                if (r.bad || cur == nullptr) { out.u8(VR_ERROR); break; }
                opRemoveGate(cur, un, out);
                break;
            }
            case OP_SETWK: {
                const uint8_t* wkIn = r.take(32);
                if (r.bad || wkIn == nullptr) { out.u8(1); break; }
                opSetwk(wkIn, 32, out);
                break;
            }
            case OP_GETCK:
                opGetck(out);
                break;
            case OP_OPENCH: {
                size_t total = r.left;
                if (r.bad || total < 12 + 16) { out.u8(1); break; }
                const uint8_t* blob = r.take(total);
                if (blob == nullptr) { out.u8(1); break; }
                opOpench(blob, total, out);
                break;
            }
            case OP_COMPOSE:
                opCompose(r, out);
                break;
            case OP_FSEAL_INIT:
                opFsealInit(out);
                break;
            case OP_FSEAL_UPDATE: {
                uint32_t n = r.u32();
                const uint8_t* pt = r.take(n);
                if (r.bad || pt == nullptr) { out.u8(1); break; }
                opFsealUpdate(pt, n, out);
                break;
            }
            case OP_FSEAL_FINAL:
                opFsealFinal(out);
                break;
            case OP_DESTROY:
                opDestroy(out);
                break;
            default:
                out.u8(1);
                break;
        }
        // 所有分支至少写 1 字节状态码（handler 契约——调用方按
        // [4B 长度][≥1B] 帧格式消费，空响应即失步）
        respOut.swap(out.buf);
        return true;
    }
};

// ===================== 进程入口与帧循环 =====================

#ifndef VAULT_TEST

static bool read_full(int fd, uint8_t* buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t r = read(fd, buf + done, n - done);
        if (r <= 0) return false;
        done += (size_t) r;
    }
    return true;
}

static bool write_full(int fd, const uint8_t* buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t w = write(fd, buf + done, n - done);
        if (w <= 0) return false;
        done += (size_t) w;
    }
    return true;
}

int main(int argc, char* argv[]) {
    if (argc != 2) return 126;

    signal(SIGPIPE, SIG_IGN);

    // exec 后 dumpable 复位为 1：立即压回 0（同 uid ptrace/proc 读写全拒）
    prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    // 自检：fork→exec 窗口被 attach 的 tracer 此刻仍在（exec 不脱钩）
    FILE* f = fopen("/proc/self/status", "re");
    if (!f) return 126;
    char line[512];
    int tracer = 0;
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            tracer = (int) strtol(line + 10, nullptr, 10);
            found = true;
            break;
        }
    }
    fclose(f);
    if (!found || tracer != 0) return 125;

    umask(0077);

    static VaultCore core;
    core.dir = argv[1];
    core.loadState();

    for (;;) {
        uint8_t hdr[4];
        if (!read_full(0, hdr, 4)) break;
        uint32_t len = ((uint32_t) hdr[0] << 24) | ((uint32_t) hdr[1] << 16) |
                       ((uint32_t) hdr[2] << 8) | (uint32_t) hdr[3];
        if (len == 0 || len > FRAME_MAX) break;  // 失步：退出（fail-closed）
        std::vector<uint8_t> req(len);
        if (!read_full(0, req.data(), len)) break;
        std::vector<uint8_t> resp;
        if (!core.process(req.data(), req.size(), resp)) break;
        uint8_t rhdr[4];
        uint32_t rlen = (uint32_t) resp.size();
        rhdr[0] = (uint8_t) (rlen >> 24); rhdr[1] = (uint8_t) (rlen >> 16);
        rhdr[2] = (uint8_t) (rlen >> 8); rhdr[3] = (uint8_t) rlen;
        if (!write_full(1, rhdr, 4) || !write_full(1, resp.data(), resp.size())) break;
    }
    // 对端死亡（主进程退出）/失步：清密钥退出
    core.resetSession();
    return 0;
}

#endif  // VAULT_TEST
