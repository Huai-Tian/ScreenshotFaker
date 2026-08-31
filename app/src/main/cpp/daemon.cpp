#include "auxiliary.h"
#include <libssh2.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <dirent.h>

string capture_gesture;
string capture_command;
string record_gesture;
string record_command;
string share_gesture;
string share_command;
string ssh_options;
atomic_bool auto_encrypt = false;
// 自定义输出文件 mtime（epoch 秒；-1 = 禁用）。
// custom_mtime_raw 为原始输入串（"yyyy-M-d H:m"，config_mutex 保护，detail 输出用），
// 用户填什么就写什么，不做任何调整
atomic<long long> custom_mtime{-1};
string custom_mtime_raw;
string scrcpy_path;
vector<unsigned char> scrcpy_data;
// 通信密钥（main 从 stdin 读取后填充，之后只读；filter 线程加密输出使用）
vector<unsigned char> g_key;
atomic_bool filter_update = false;
mutex config_mutex;

// ===================== 超时看门狗状态 =====================
// deadline 为墙钟绝对时刻（秒，0=未启用），由 app 侧经 config/renew 下发；
// daemon 自持 uptime/wall 双锚点（密文存随机 tmp 文件）防冻结与回拨：
// - /proc/uptime 基于内核 boottime（含 suspend），root 改不动 jiffies——
//   冻结墙钟而 uptime 走表 → 漂移超限 → 引爆
// - 重启（uptime 回退）后墙钟必须至少前进 MIN_REBOOT_WALL_ELAPSED_SEC，
//   否则 = 冻结+重启绕过计时 → 引爆
atomic<long long> idle_deadline_sec{0};
// 本实例是否已从 app 侧收到过有效 config/renew（含合法死线）。
// 看门狗死线引爆的前提：锚点里的死线是"本实例知情"的死线——否则它是
// 上一实例（SIGKILL 残留）的陈旧锚点，app 侧锚点可能已续期而 config
// 尚未送达（startDaemon 2s 探测超时后短路返回不再补发），此时引爆 =
// 对活跃用户误毁。收到 config/renew 即置位并同步重置锚点基线
atomic<bool> config_synced{false};
string app_data_dir;            // config 下发，root 模式过期擦除范围（config_mutex）
atomic<int> app_uid{-1};
string g_self_path;             // 自身可执行文件路径（tmp 随机名或 apk 内路径）
string g_anchor_path;           // 看门狗锚点文件路径（密钥派生随机名）
mutex anchor_mutex;
// 录屏明文 tmp 路径（detonate/SIGTERM 时删除，防明文残留）。
// 双表示：std::string 供线程内加锁访问；定长缓冲 + sig_atomic 长度供
// 信号 handler 无锁读取（先清长度后拷贝，读取侧只见完整或空两种状态）
string g_record_tmp;            // record_mutex 保护
static char g_rec_tmp_buf[256];
static volatile sig_atomic_t g_rec_tmp_len = 0;
// 共享 server 落地文件路径（detonate 时删除）
string g_share_server_file;     // record_mutex 复用保护（低频写，无竞争热点）
// 录屏明文 tmp 指针文件路径（key 派生随机隐藏名，main 初始化后只读）：
// 明文落盘期间存在，收尾/清理路径删除；SIGKILL 后由下次启动的孤儿回收消费
string g_rec_mark;

// 录屏 tmp 路径维护（record_mutex 下调用）
static void set_record_tmp(const string &p) {
    g_rec_tmp_len = 0;
    if (p.size() < sizeof(g_rec_tmp_buf)) {
        memcpy(g_rec_tmp_buf, p.c_str(), p.size());
        g_rec_tmp_len = static_cast<sig_atomic_t>(p.size());
    }
    g_record_tmp = p;
    // 指针文件：明文 tmp 落盘期间在 key 派生随机隐藏名文件里留一份路径，
    // 收尾完成即删。SIGKILL 兜底路径（stopDaemon 升级 / OOM）跳过所有
    // handler 清理，下次启动按指针回收孤儿明文（见 main 的孤儿回收）
    if (!g_rec_mark.empty()) {
        string tmp = g_rec_mark + ".t";
        FILE *f = fopen(tmp.c_str(), "wb");
        if (f) {
            fwrite(p.data(), 1, p.size(), f);
            fclose(f);
            chmod(tmp.c_str(), 0600);
            rename(tmp.c_str(), g_rec_mark.c_str());
        }
    }
}

static void clear_record_tmp() {
    g_rec_tmp_len = 0;
    g_record_tmp.clear();
    // 收尾完成：明文已删，指针文件一并清除（不存在时 remove 无害）
    if (!g_rec_mark.empty()) remove(g_rec_mark.c_str());
}

/**
 * SIGTERM 兜底清理（kill-by-discovery 路径：app 侧信道不可用时按端口杀进程）。
 * 信号安全说明：锚点/自拷贝路径在 main 初始化后只读（其他线程不写），
 * 读取 .c_str()/.empty() 安全；录屏 tmp 走 sig_atomic 长度前缀缓冲。
 * 不 memset g_key：信号可投递到任意线程，handler 与其他核上看门狗的
 * anchor_load 并发——清钥瞬间解密得坏密文会被按"锚点篡改"引爆
 * （root 模式 rm -rf app 数据）。_exit 后密钥随进程地址空间消亡，
 * 零化收益可忽略（能读死者内存的攻击者本可直读存活进程）
 */
static void term_handler(int) {
    if (g_rec_tmp_len > 0) {
        char buf[256];
        memcpy(buf, g_rec_tmp_buf, static_cast<size_t>(g_rec_tmp_len));
        buf[g_rec_tmp_len] = 0;
        remove(buf);
    }
    if (!g_anchor_path.empty()) remove(g_anchor_path.c_str());
    if (!g_rec_mark.empty()) remove(g_rec_mark.c_str());
    if (g_self_path.rfind("/data/local/tmp/", 0) == 0) {
        remove(g_self_path.c_str());
    }
    _exit(0);
}

// 看门狗参数
static const int WATCHDOG_INTERVAL_SEC = 30;
// 墙钟合理性下限（2020-01-01 epoch 秒，与 app 侧 IdleWatchdog.WC0_MIN 对齐）：
// 低于此值视为 RTC 耗尽/未同步的错钟，跳过本轮判定（防误毁）
static const long long WC0_MIN_SEC = 1577836800LL;
// wall 与 uptime 漂移容差（秒）：正常 NTP 校正远小于此值
static const long long ANCHOR_FREEZE_TOLERANCE_SEC = 120;
// 真实重启至少耗费的墙钟时间（秒）：跨重启墙钟增量低于此值 = 冻结
static const long long MIN_REBOOT_WALL_ELAPSED_SEC = 20;

// ===================== 录屏 toggle 状态 =====================
// 匹配触发一次开始录制，再次触发发 SIGINT 停止（参考录屏磁贴服务）
mutex record_mutex;
atomic_bool record_running = false;
pid_t record_pid = -1;
// record 子进程 spawn 时刻的 starttime（record_mutex 保护）——延迟补杀
// （stop/detach/引爆/日志开关停止）前的 PID 身份复核依据，防 PID 复用误杀
long long record_pid_start = -1;

// ===================== 屏幕共享运行状态 =====================
// 匹配触发一次开启，再次触发关闭，循环往复
static const char *RELAY_MARKER = "vendor.entry.Main";
atomic_bool share_running = false;
atomic_bool share_stop_requested = false;
atomic<pid_t> share_server_pid{-1};
// share server 子进程 spawn 时刻的 starttime（与 share_server_pid 同步更新）
// ——延迟补杀（stop/detach/引爆/toggle）前的 PID 身份复核依据
static atomic<long long> share_server_pid_start{-1};
static thread share_supervisor;

void daemonize() {
    pid_t pid = fork();
    if (pid < 0) _exit(EXIT_FAILURE);
    if (pid > 0) _exit(EXIT_SUCCESS);
    if (setsid() < 0) _exit(EXIT_FAILURE);
    pid = fork();
    if (pid < 0) _exit(EXIT_FAILURE);
    if (pid > 0) _exit(EXIT_SUCCESS);
    if (chdir("/") < 0) _exit(EXIT_FAILURE);
    umask(0);
    int fd = open("/dev/null", O_RDWR);
    if (fd != -1) {
        dup2(fd, STDIN_FILENO);
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
    }
}

// sh 安全引用：单引号包裹，内部单引号转义为 '\''（与 app 侧
// DaemonManager.shellQuote 同一语义）。凡用户可控字符串进入
// sh -c 命令体（detonate 的数据目录、filter 的输出路径）必须经此
static string shell_quote(const string &s) {
    string r = "'";
    for (char c: s) {
        if (c == '\'') r += "'\\''";
        else r += c;
    }
    r += "'";
    return r;
}

// ===================== 通用工具 =====================

// 随机临时文件名：/data/local/tmp/ 下长度随机（20..35）的字母数字串，
// 不含任何工具特征字样（隐藏性要求）
string random_tmp_name() {
    static mt19937 gen{random_device{}()};
    uniform_int_distribution<int> dist(20, 35);
    return "/data/local/tmp/" + getRandomString(dist(gen));
}

// 隐藏随机临时文件名（'.' 前缀，ls 默认不可见）：自拷贝落地与锚点文件用
string random_hidden_tmp_name() {
    static mt19937 gen{random_device{}()};
    uniform_int_distribution<int> dist(20, 35);
    return "/data/local/tmp/." + getRandomString(dist(gen));
}

// 阻塞写全量（EINTR 容忍）
static void write_all_buffer(int fd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, buf + off, len - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            return;
        }
        off += static_cast<size_t>(w);
    }
}

// 读 /proc/uptime（内核 boottime，含 suspend；root 无法冻结 jiffies）。
// 失败返回 -1
static double read_proc_uptime() {
    FILE *f = fopen("/proc/uptime", "r");
    if (!f) return -1.0;
    double up = -1.0;
    if (fscanf(f, "%lf", &up) != 1) up = -1.0;
    fclose(f);
    return up;
}

// 锚点文件路径：密钥哈希前 8 字节 hex——仅持密钥者可推导文件名，
// tmp 目录扫描者无法定位（配合 '.' 前缀隐藏）
static string anchor_path_for_key(const vector<unsigned char> &key) {
    unsigned char md[EVP_MAX_MD_SIZE];
    unsigned int mdlen = 0;
    if (EVP_Digest(key.data(), key.size(), md, &mdlen, EVP_sha256(), nullptr) != 1 || mdlen < 8) {
        return "/data/local/tmp/.anchor";
    }
    char hex[17];
    for (int i = 0; i < 8; ++i) snprintf(hex + i * 2, 3, "%02x", md[i]);
    hex[16] = '\0';
    return string("/data/local/tmp/.") + hex;
}

// 录屏明文 tmp 指针文件路径：同一密钥哈希的第 9..16 字节 hex——与锚点
// 同构（仅持密钥者可推导文件名，'.' 前缀对 ls 默认隐藏），且不与锚点冲突
static string record_mark_path_for_key(const vector<unsigned char> &key) {
    unsigned char md[EVP_MAX_MD_SIZE];
    unsigned int mdlen = 0;
    if (EVP_Digest(key.data(), key.size(), md, &mdlen, EVP_sha256(), nullptr) != 1 || mdlen < 16) {
        return "/data/local/tmp/.recmark";
    }
    char hex[17];
    for (int i = 0; i < 8; ++i) snprintf(hex + i * 2, 3, "%02x", md[8 + i]);
    hex[16] = '\0';
    return string("/data/local/tmp/.") + hex;
}

// 锚点文件内容（加密前）："deadline,lastwall,lastuptime"
// exists=false：文件不存在（首启/被清理）；valid=false：存在但解密/解析失败 = 篡改
struct AnchorState {
    bool exists = false;
    bool valid = false;
    long long deadline = 0;
    long long lastwall = 0;
    double lastuptime = -1.0;
};

static AnchorState anchor_load() {
    AnchorState st;
    FILE *f = fopen(g_anchor_path.c_str(), "rb");
    if (!f) return st;                       // 不存在
    st.exists = true;
    vector<unsigned char> data;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        data.insert(data.end(), buf, buf + n);
    }
    fclose(f);
    string plain = decrypt_data(g_key, data);
    if (plain.empty()) return st;            // 存在但解不开 = 篡改
    auto parts = split(plain, ',');
    if (parts.size() != 3) return st;
    st.deadline = strtoll(parts[0].c_str(), nullptr, 10);
    st.lastwall = strtoll(parts[1].c_str(), nullptr, 10);
    st.lastuptime = strtod(parts[2].c_str(), nullptr);
    if (st.lastwall <= 0 || st.lastuptime < 0) return st;
    st.valid = true;
    return st;
}

// 原子写锚点（tmp + rename）：并发写最坏"后写者胜"，绝不产生半截文件
// （半截文件下次加载解密失败会被判篡改引爆——原子性防误炸）。
// 写失败防线：磁盘满/IO 错误的短写同样产生半截密文——fwrite/fclose
// 失败时放弃 rename（保留旧锚点，本轮基线不推进），半截 tmp 下轮覆盖。
// 不用 fsync：锚点丢失（崩溃后无锚点）走 !st.exists 重建分支，语义
// 是"重新基线化"而非引爆——fsync 只为极端掉电下减少该窗口，代价是
// 每 30s 一次同步 IO（唤醒磁盘），收益不成比例，不加。
// 密钥长度守卫：stop/term 清钥与看门狗 tick 并发时，g_key 可能已清零
// （vector::clear 保留 capacity，data() 仍可读出 32 字节零）——用零钥
// 加密会落盘"毒锚点"（下次启动用真钥解不开 → 按篡改引爆），必须拒绝
static void anchor_save(long long deadline, long long wall, double uptime) {
    if (g_key.size() != KEY_LEN) return;
    string plain = to_string(deadline) + "," + to_string(wall) + "," +
                   to_string(uptime);
    vector<unsigned char> enc = encrypt_data(g_key, plain);
    if (enc.empty()) return;
    string tmp = g_anchor_path + ".t";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    bool ok = fwrite(enc.data(), 1, enc.size(), f) == enc.size();
    if (fclose(f) != 0) ok = false;
    if (!ok) {
        remove(tmp.c_str());
        return;
    }
    chmod(tmp.c_str(), 0600);
    rename(tmp.c_str(), g_anchor_path.c_str());
}

// 自拷贝：把 /proc/self/exe 复制到随机 tmp 路径
static bool copy_self_to(const string &dst) {
    ifstream in("/proc/self/exe", ios::binary);
    if (!in) return false;
    ofstream out(dst, ios::binary | ios::trunc);
    if (!out) return false;
    out << in.rdbuf();
    out.flush();
    bool ok = out.good();
    out.close();
    if (ok) chmod(dst.c_str(), 0700);
    return ok;
}

// "yyyy-M-d H:m" 原始串 → epoch 秒（按设备本地时区，与 Kotlin 侧
// ZoneId.systemDefault() 语义一致；bionic 无 TZ 时读 persist.sys.timezone）。
// strptime 逐字符严格匹配且校验尾部无残留；空/格式非法 → -1（禁用）。
// 防崩兜底而非校验：真正的格式把关在 UI
long long parse_defined_mtime(const string &text) {
    if (text.empty()) return -1;
    struct tm t{};
    t.tm_isdst = -1;                            // DST 交由系统判定
    const char *rest = strptime(text.c_str(), "%Y-%m-%d %H:%M", &t);
    if (rest == nullptr || *rest != '\0') return -1;
    time_t secs = mktime(&t);
    return secs < 0 ? -1 : static_cast<long long>(secs);
}

// 自定义输出文件 mtime：原样写入用户指定的 epoch 秒。
// atime 设为同一假值——"截图后从未查看"是常态（创建即最后访问），
// 留真实 atime 会泄露实际生成时刻；ctime 由内核垄断无法伪造，接受残留。
// 必须在最终落盘（含加密回写）之后调用；
// 失败静默——只是时间戳，不影响产物
void set_custom_mtime(const string &path) {
    long long base = custom_mtime.load();
    if (base < 0) return;
    timespec ts[2]{};
    ts[0].tv_sec = static_cast<time_t>(base);    // atime = 假 mtime
    ts[1].tv_sec = static_cast<time_t>(base);    // mtime = 用户指定值
    utimensat(AT_FDCWD, path.c_str(), ts, 0);
}

// 流式 AES-256-GCM 文件加密：输出 nonce(12) + 密文 + tag(16)，
// 与 Kotlin 端 EncryptManager 软件加密互通（软件解密输入通信密码即可解开）
bool encrypt_file(const vector<unsigned char> &key, const string &plain_path,
                  const string &cipher_path) {
    if (key.empty()) return false;
    FILE *in = fopen(plain_path.c_str(), "rb");
    if (!in) return false;
    FILE *out = fopen(cipher_path.c_str(), "wb");
    if (!out) {
        fclose(in);
        return false;
    }
    bool ok = false;
    unsigned char nonce[NONCE_LEN];
    EVP_CIPHER_CTX *ctx = nullptr;
    do {
        if (RAND_bytes(nonce, NONCE_LEN) != 1) break;
        ctx = EVP_CIPHER_CTX_new();
        if (!ctx) break;
        if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), nonce) != 1) break;
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_LEN, nullptr) != 1) break;
        if (fwrite(nonce, 1, NONCE_LEN, out) != NONCE_LEN) break;
        unsigned char buf[65536];
        unsigned char enc[sizeof(buf) + TAG_LEN];
        size_t n;
        int len;
        bool io_failed = false;
        while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
            if (EVP_EncryptUpdate(ctx, enc, &len, buf, static_cast<int>(n)) != 1) {
                io_failed = true;
                break;
            }
            if (fwrite(enc, 1, static_cast<size_t>(len), out) != static_cast<size_t>(len)) {
                io_failed = true;
                break;
            }
        }
        if (io_failed || ferror(in)) break;
        if (EVP_EncryptFinal_ex(ctx, enc, &len) != 1) break;
        if (fwrite(enc, 1, static_cast<size_t>(len), out) != static_cast<size_t>(len)) break;
        unsigned char tag[TAG_LEN];
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_LEN, tag) != 1) break;
        if (fwrite(tag, 1, TAG_LEN, out) != TAG_LEN) break;
        ok = true;
    } while (false);
    if (ctx) EVP_CIPHER_CTX_free(ctx);
    fclose(in);
    if (fclose(out) != 0) ok = false;
    if (!ok) remove(cipher_path.c_str());
    return ok;
}

// spawn shell 命令，stdout/stderr 重定向 /dev/null（防止管道写满阻塞子进程）
pid_t spawn_shell_command(const string &command) {
    vector<string> args = {"/system/bin/sh", "-c", command};
    vector<char *> argv;
    argv.reserve(args.size() + 1);
    for (auto &a: args) argv.push_back(const_cast<char *>(a.c_str()));
    argv.push_back(nullptr);
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, "/dev/null", O_WRONLY, 0);
    posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0);
    posix_spawnattr_t attr;
    posix_spawnattr_init(&attr);
    pid_t pid = -1;
    int ret = posix_spawn(&pid, argv[0], &actions, &attr, argv.data(), environ);
    posix_spawnattr_destroy(&attr);
    posix_spawn_file_actions_destroy(&actions);
    if (ret != 0) return -1;
    return pid;
}

// 进程是否存活（kill 0 探测）。SIGCHLD 为 SIG_IGN 时子进程被自动回收，
// waitpid 恒返回 ECHILD——所有等待一律改为存在性轮询。
// PID 复用会造成极小概率的"多等一会"，方向安全（不会提前收尾）
static bool process_alive(pid_t pid) {
    if (pid <= 0) return false;
    if (kill(pid, 0) == 0) return true;
    return errno == EPERM;   // 存在但非我们的进程（被复用）也视为存活
}

// 读取 /proc/<pid>/stat 的 starttime（第 22 字段，boot 后 tick 数）：进程
// 生命周期内恒定，PID 复用后必变。跨线程延迟 kill（stop/detach/toggle/
// detonate 对 record_pid / share_server_pid 的补杀——距 spawn 可达数十秒）
// 前用它复核身份，杜绝 PID 复用窗口内误杀无关进程（root 模式下 SIGKILL
// 误杀系统进程不可逆）。读取失败（已退出/权限）返回 -1，调用方按身份
// 不符处理——"不杀"是安全方向（子进程已死则无杀必要；无法核实宁可放其
// 自然退出，也不冒误杀险）
static long long proc_start_ticks(pid_t pid) {
    if (pid <= 0) return -1;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char buf[2048];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    if (n == 0) return -1;
    buf[n] = '\0';
    // comm 字段（第 2 字段）可含空格与括号——定位最后一个 ')' 再按空白
    // 分词：')' 后首 token 为第 3 字段（state），starttime 为第 22 字段
    char *close_paren = strrchr(buf, ')');
    if (!close_paren) return -1;
    long long start = -1;
    char *p = close_paren + 1;
    int field = 2;
    while (*p) {
        while (*p == ' ') ++p;
        if (!*p) break;
        ++field;
        if (field == 22) {
            start = strtoll(p, nullptr, 10);
            break;
        }
        while (*p && *p != ' ') ++p;
    }
    return start;
}

// pid 与 spawn 时捕获的 starttime 仍同一进程？
static bool pid_is_same(pid_t pid, long long start_ticks) {
    if (pid <= 0 || start_ticks < 0) return false;
    return proc_start_ticks(pid) == start_ticks;
}

// 等待进程退出（轮询版：SIGCHLD=SIG_IGN 下 waitpid 不可用）
void wait_process(pid_t pid) {
    while (process_alive(pid)) {
        usleep(100000);
    }
}

// ===================== 残留 server 进程扫描/清理 =====================

// 扫描 /proc 找出 cmdline 含 Relay 入口类的进程（app 进程重启后标志丢失时以此为准）
vector<pid_t> find_relay_pids() {
    vector<pid_t> pids;
    DIR *d = opendir("/proc");
    if (!d) return pids;
    struct dirent *e;
    char path[64], buf[4096];
    while ((e = readdir(d)) != nullptr) {
        if (e->d_type != DT_DIR) continue;
        int pid = static_cast<int>(strtol(e->d_name, nullptr, 10));
        if (pid <= 0 || pid == getpid()) continue;
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';
        for (ssize_t i = 0; i < n; ++i) if (buf[i] == '\0') buf[i] = ' ';
        if (strstr(buf, RELAY_MARKER) != nullptr) pids.push_back(static_cast<pid_t>(pid));
    }
    closedir(d);
    return pids;
}

// 先 SIGINT 让 server 走 CleanUp 正常收尾，1s 后仍存活则 SIGKILL 兜底
void kill_relay_processes() {
    auto pids = find_relay_pids();
    if (pids.empty()) return;
    for (pid_t pid: pids) kill(pid, SIGINT);
    for (int i = 0; i < 10; ++i) {
        if (find_relay_pids().empty()) return;
        usleep(100000);
    }
    for (pid_t pid: find_relay_pids()) kill(pid, SIGKILL);
}

/**
 * 清扫 app 侧共享（磁贴/页面启动的 relay 与其 sh 守护循环）。
 * 顺序关键：先杀守护 sh——否则 server 被杀后 1s 内被循环重新拉起；
 * 再杀 relay server；最后清理停止标记与脚本文件。
 * sh 匹配模式锚定行尾（\.sh$）：sh -c 包装进程自身命令行以引号收尾，
 * 不会被匹配（pkill 自杀的经典足枪规避）。
 * 使用场景：detonate（看门狗引爆——用户停止使用后 relay 仍可能在推流）
 * 与 purge 命令（胁迫销毁——app 侧 stopScreenShare 无特权时的兜底）。
 */
static void purge_app_side_share() {
    pid_t p1 = spawn_shell_command("pkill -f '\\.w_[A-Za-z0-9_-]+\\.sh$'");
    if (p1 > 0) {
        for (int i = 0; i < 30 && process_alive(p1); ++i) usleep(100000);
    }
    kill_relay_processes();
    pid_t p2 = spawn_shell_command("rm -f /data/local/tmp/.s_* /data/local/tmp/.w_*.sh");
    if (p2 > 0) {
        for (int i = 0; i < 50 && process_alive(p2); ++i) usleep(100000);
    }
}

// ===================== SSH 隧道（libssh2 远程端口转发） =====================
// 等价 ssh -R remote_port:127.0.0.1:local_port，供接收端通过 SSH 服务器接入。
// session 以非阻塞模式轮询 accept；每个转发连接由两个线程双向搬运，
// 所有 libssh2 调用统一持锁（session 非线程安全）。

/**
 * 带超时的主机名解析：gethostbyname 无超时（解析失败最长阻塞 ~30s），
 * supervisor 线程卡在 DNS 期间 share_stop_requested 不可见 → stop 命令的
 * share_supervisor.join() 无界等待。分离线程解析 + 有界轮询：超时按
 * 失败处理，解析线程持有 shared_ptr 自行收尾（成功/失败/超时皆无泄漏、
 * 无悬垂——不使用 std::async，其 future 析构会阻塞直到任务完成）。
 */
static bool resolve_host_timeout(const string &host, struct in_addr *out, int timeout_ms) {
    struct ResolveCtx {
        atomic<int> result{-1};     // -1 未完成 / 0 成功 / 1 失败
        struct in_addr addr{};
    };
    auto ctx = make_shared<ResolveCtx>();
    auto weak = weak_ptr<ResolveCtx>(ctx);
    thread([weak, host]() {
        auto c = weak.lock();
        if (!c) return;
        struct hostent *he = gethostbyname(host.c_str());
        if (he && he->h_addrtype == AF_INET && he->h_addr_list[0]) {
            memcpy(&c->addr, he->h_addr_list[0], sizeof(struct in_addr));
            c->result.store(0);
        } else {
            c->result.store(1);
        }
    }).detach();
    const auto deadline = chrono::steady_clock::now() + chrono::milliseconds(timeout_ms);
    while (chrono::steady_clock::now() < deadline) {
        int r = ctx->result.load();
        if (r != -1) {
            if (r == 0) memcpy(out, &ctx->addr, sizeof(struct in_addr));
            return r == 0;
        }
        usleep(50000);
    }
    return false;
}

struct SshTunnel {
    int sock = -1;
    LIBSSH2_SESSION *session = nullptr;
    LIBSSH2_LISTENER *listener = nullptr;
    mutex mtx;
    atomic_bool stopping{false};
    atomic<int> active_bridges{0};
    thread acceptor;
    int local_port = 0;

    struct BridgeCtx {
        LIBSSH2_CHANNEL *ch;
        int local_fd;
        SshTunnel *tunnel;
        atomic<int> alive{2};
    };

    static int connect_with_timeout(const string &host, int port, int timeout_ms) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return -1;
        int flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(static_cast<uint16_t>(port));
        if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
            // DNS 解析经 [resolve_host_timeout]（3s 上限）：见其文档
            if (!resolve_host_timeout(host, &addr.sin_addr, 3000)) {
                close(fd);
                return -1;
            }
        }
        int r = connect(fd, (struct sockaddr *) &addr, sizeof(addr));
        if (r != 0 && errno != EINPROGRESS) {
            close(fd);
            return -1;
        }
        if (r != 0) {
            struct pollfd pfd{fd, POLLOUT, 0};
            if (poll(&pfd, 1, timeout_ms) <= 0) {
                close(fd);
                return -1;
            }
            int err = 0;
            socklen_t len = sizeof(err);
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len);
            if (err != 0) {
                close(fd);
                return -1;
            }
        }
        fcntl(fd, F_SETFL, flags);
        return fd;
    }

    static void write_all_fd(int fd, const char *buf, size_t len) {
        size_t off = 0;
        while (off < len) {
            ssize_t w = write(fd, buf + off, len - off);
            if (w < 0) {
                if (errno == EINTR) continue;
                return;
            }
            off += static_cast<size_t>(w);
        }
    }

    bool start(const string &host, int port, const string &user, const string &password,
               int remote_port, int local_server_port) {
        local_port = local_server_port;
        if (libssh2_init(0) != 0) return false;
        do {
            sock = connect_with_timeout(host, port, 8000);
            if (sock < 0) break;
            session = libssh2_session_init();
            if (!session) break;
            libssh2_session_set_blocking(session, 1);
            if (libssh2_session_handshake(session, sock) != 0) break;
            if (libssh2_userauth_password(session, user.c_str(), password.c_str()) != 0) break;
            listener = libssh2_channel_forward_listen_ex(session, nullptr, remote_port, nullptr,
                                                         16);
            if (!listener) break;
            libssh2_session_set_blocking(session, 0);
            stopping.store(false);
            acceptor = thread(&SshTunnel::accept_loop, this);
            return true;
        } while (false);
        cleanup();
        return false;
    }

    void accept_loop() {
        while (!stopping.load()) {
            LIBSSH2_CHANNEL *ch = nullptr;
            {
                lock_guard<mutex> lock(mtx);
                ch = libssh2_channel_forward_accept(listener);
            }
            if (ch) {
                spawn_bridge(ch);
            } else {
                usleep(200000);
            }
        }
    }

    void spawn_bridge(LIBSSH2_CHANNEL *ch) {
        int local_fd = connect_with_timeout("127.0.0.1", local_port, 3000);
        if (local_fd < 0) {
            lock_guard<mutex> lock(mtx);
            libssh2_channel_free(ch);
            return;
        }
        // 读超时让 l2c 线程能周期性检查停止标志
        struct timeval tv{0, 200000};
        setsockopt(local_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        // 发送超时同理：c2l 的 write_all_fd 是无超时阻塞写——本地 server
        // 停止读取且缓冲填满时（共享停止/对端卡顿）线程无法检查停止标志；
        // stop() 只等 supervisor 3s，超时后栈上 SshTunnel 析构而 bridge
        // 线程仍在写 → use-after-free 崩溃。EAGAIN 时 write_all_fd 按既有
        // 语义返回丢弃，c2l 循环顶检查 stopping 退出
        struct timeval tv_snd{2, 0};
        setsockopt(local_fd, SOL_SOCKET, SO_SNDTIMEO, &tv_snd, sizeof(tv_snd));
        auto *ctx = new BridgeCtx{ch, local_fd, this};
        active_bridges.fetch_add(1);
        thread(&SshTunnel::bridge_c2l, ctx).detach();
        thread(&SshTunnel::bridge_l2c, ctx).detach();
    }

    // 远端 → 本地 server
    static void bridge_c2l(BridgeCtx *ctx) {
        SshTunnel *t = ctx->tunnel;
        char buf[16384];
        while (!t->stopping.load()) {
            ssize_t n;
            {
                lock_guard<mutex> lock(t->mtx);
                n = libssh2_channel_read(ctx->ch, buf, sizeof(buf));
            }
            if (n > 0) {
                write_all_fd(ctx->local_fd, buf, static_cast<size_t>(n));
            } else if (n == 0) {
                shutdown(ctx->local_fd, SHUT_WR);
                break;
            } else if (n == LIBSSH2_ERROR_EAGAIN) {
                usleep(50000);
            } else {
                break;
            }
        }
        finish_bridge(ctx);
    }

    // 本地 server → 远端
    static void bridge_l2c(BridgeCtx *ctx) {
        SshTunnel *t = ctx->tunnel;
        char buf[16384];
        while (!t->stopping.load()) {
            ssize_t n = recv(ctx->local_fd, buf, sizeof(buf), 0);
            if (n > 0) {
                size_t off = 0;
                bool error = false;
                while (off < static_cast<size_t>(n)) {
                    if (t->stopping.load()) {
                        error = true;
                        break;
                    }
                    ssize_t w;
                    {
                        lock_guard<mutex> lock(t->mtx);
                        w = libssh2_channel_write(ctx->ch, buf + off, static_cast<size_t>(n) - off);
                    }
                    if (w > 0) {
                        off += static_cast<size_t>(w);
                    } else if (w == LIBSSH2_ERROR_EAGAIN) {
                        usleep(20000);
                    } else {
                        error = true;
                        break;
                    }
                }
                if (error) break;
            } else if (n == 0) {
                break;
            } else {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
                break;
            }
        }
        {
            lock_guard<mutex> lock(t->mtx);
            libssh2_channel_send_eof(ctx->ch);
        }
        finish_bridge(ctx);
    }

    static void finish_bridge(BridgeCtx *ctx) {
        if (ctx->alive.fetch_sub(1) == 1) {
            SshTunnel *t = ctx->tunnel;
            {
                lock_guard<mutex> lock(t->mtx);
                libssh2_channel_free(ctx->ch);
            }
            close(ctx->local_fd);
            delete ctx;
            t->active_bridges.fetch_sub(1);
        }
    }

    void stop() {
        if (sock < 0 && !session) return;
        stopping.store(true);
        if (acceptor.joinable()) acceptor.join();
        // bridge 线程在停止标志 + 200ms 超时机制下快速退出，最多等 3s
        for (int i = 0; i < 30 && active_bridges.load() > 0; ++i) usleep(100000);
        cleanup();
    }

    void cleanup() {
        if (listener) {
            libssh2_channel_forward_cancel(listener);
            listener = nullptr;
        }
        if (session) {
            libssh2_session_disconnect(session, "");
            libssh2_session_free(session);
            session = nullptr;
        }
        if (sock >= 0) {
            close(sock);
            sock = -1;
        }
        libssh2_exit();
    }
};

// ===================== 屏幕共享子系统的实现 =====================

// 快照当前共享配置（config 线程与 supervisor 之间的数据竞争防护）
bool build_share_snapshot(string &cmd, int &local_port, bool &ssh_enabled, string &ssh_host,
                          int &ssh_port, string &ssh_user, string &ssh_pass, int &ssh_remote_port,
                          vector<unsigned char> &server_data) {
    string share_cmd_copy, ssh_copy;
    {
        lock_guard<mutex> lock(config_mutex);
        if (share_command.empty() || scrcpy_data.empty()) return false;
        share_cmd_copy = share_command;
        ssh_copy = ssh_options;
        server_data = scrcpy_data;
    }
    auto sp = split(ssh_copy, '\x1F');
    ssh_enabled = !sp.empty() && sp[0] == "true";
    if (ssh_enabled) {
        ssh_host = sp.size() > 1 ? sp[1] : "";
        ssh_port = sp.size() > 2 ? static_cast<int>(strtol(sp[2].c_str(), nullptr, 10)) : 22;
        ssh_user = sp.size() > 3 ? sp[3] : "";
        ssh_pass = sp.size() > 4 ? sp[4] : "";
        ssh_remote_port = sp.size() > 5 ? static_cast<int>(strtol(sp[5].c_str(), nullptr, 10)) : 0;
    }
    // 从 base 命令提取 tcp_port（server 的本地监听端口）
    size_t p = share_cmd_copy.find("tcp_port=");
    if (p == string::npos) return false;
    local_port = static_cast<int>(strtol(share_cmd_copy.c_str() + p + 9, nullptr, 10));
    if (local_port < 1024 || local_port > 65535) return false;
    // 远程端口未配置（0 或越界）时回退本地端口，与 app 端逻辑一致
    if (ssh_remote_port < 1024 || ssh_remote_port > 65535) ssh_remote_port = local_port;
    // server 落地文件用随机名替换 Kotlin 端的占位符（隐藏性：文件名无特征）
    string random_name = random_tmp_name();
    cmd = replace_all(share_cmd_copy, "FullRandomName",
                      random_name.substr(random_name.rfind('/') + 1));
    // 拼接成完整 shell 命令：base + 空格分隔的 key=value 参数
    auto parts = split(cmd, '\x1F');
    string full_cmd;
    for (const auto &part: parts) {
        if (part.empty()) continue;
        if (!full_cmd.empty()) full_cmd += ' ';
        full_cmd += part;
    }
    // SSH 模式下 server 只监听回环，防止局域网直连绕过隧道
    if (ssh_enabled && full_cmd.find("tcp_local_only=") == string::npos) {
        full_cmd += " tcp_local_only=true";
    }
    cmd = full_cmd;
    return !cmd.empty();
}

// supervisor 线程：落地 server → 建隧道 → 拉起并守护 server 进程。
// server 异常退出 1s 后自动重启；连续 3 次快速退出（<30s）说明无法正常启动，放弃。
void share_supervisor_main() {
    string cmd, ssh_host, ssh_user, ssh_pass;
    int local_port = 0, ssh_port = 22, ssh_remote_port = 0;
    bool ssh_enabled = false;
    vector<unsigned char> server_data;
    if (!build_share_snapshot(cmd, local_port, ssh_enabled, ssh_host, ssh_port, ssh_user,
                              ssh_pass, ssh_remote_port, server_data)) {
        share_running.store(false);
        return;
    }
    string server_file = random_tmp_name();
    {
        ofstream f(server_file, ios::binary | ios::trunc);
        if (!f) {
            share_running.store(false);
            return;
        }
        f.write(reinterpret_cast<const char *>(server_data.data()),
                static_cast<streamsize>(server_data.size()));
        if (!f.good()) {
            remove(server_file.c_str());
            share_running.store(false);
            return;
        }
    }
    chmod(server_file.c_str(), 0600);
    // 记录落地路径（detonate 时删除；supervisor 退出时清除）
    {
        lock_guard<mutex> lock(record_mutex);
        g_share_server_file = server_file;
    }
    SshTunnel tunnel;
    if (ssh_enabled) {
        if (!tunnel.start(ssh_host, ssh_port, ssh_user, ssh_pass, ssh_remote_port, local_port)) {
            remove(server_file.c_str());
            {
                lock_guard<mutex> lock(record_mutex);
                g_share_server_file.clear();
            }
            share_running.store(false);
            return;
        }
    }
    int fast_exit = 0;
    while (!share_stop_requested.load()) {
        pid_t pid = spawn_shell_command(cmd);
        if (pid <= 0) {
            this_thread::sleep_for(chrono::seconds(1));
            continue;
        }
        // spawn 即刻捕获身份（starttime 恒定），后续补杀前复核——
        // PID 复用后 kill(0) 仍命中但 starttime 已变
        long long pid_start = proc_start_ticks(pid);
        share_server_pid.store(pid);
        share_server_pid_start.store(pid_start);
        auto start_time = chrono::steady_clock::now();
        bool sigint_sent = false;
        auto sigint_time = chrono::steady_clock::now();
        // SIGCHLD=SIG_IGN 下 waitpid 不可用：存在性 + 身份复合轮询探测退出
        //（复用后的 PID 按"已退出"处理——既防补杀误杀，也防永等复用者）
        while (process_alive(pid)) {
            if (!pid_is_same(pid, pid_start)) break;
            if (share_stop_requested.load()) {
                if (!sigint_sent) {
                    kill(pid, SIGINT);
                    sigint_sent = true;
                    sigint_time = chrono::steady_clock::now();
                } else if (chrono::steady_clock::now() - sigint_time > chrono::seconds(1)) {
                    kill(pid, SIGKILL);
                }
            }
            usleep(200000);
        }
        share_server_pid.store(-1);
        share_server_pid_start.store(-1);
        if (share_stop_requested.load()) break;
        auto elapsed = chrono::duration_cast<chrono::seconds>(
                chrono::steady_clock::now() - start_time).count();
        if (elapsed >= 30) fast_exit = 0;
        else ++fast_exit;
        if (fast_exit >= 3) break;
        this_thread::sleep_for(chrono::seconds(1));
    }
    tunnel.stop();
    remove(server_file.c_str());
    {
        lock_guard<mutex> lock(record_mutex);
        g_share_server_file.clear();
    }
    share_running.store(false);
}

// 共享 toggle：未运行 → 清理残留并启动；运行中 → 停止（supervisor 异步完成收尾）
void toggle_share() {
    if (share_running.load()) {
        share_stop_requested.store(true);
        pid_t pid = share_server_pid.load();
        // 身份复核后再补杀（见 share_server_pid_start 注释）
        if (pid_is_same(pid, share_server_pid_start.load())) kill(pid, SIGINT);
        share_running.store(false);
        return;
    }
    // daemon 未管理共享：先清理可能残留的 server（daemon 重启场景）
    kill_relay_processes();
    if (share_supervisor.joinable()) share_supervisor.join();
    share_stop_requested.store(false);
    share_running.store(true);
    share_supervisor = thread(share_supervisor_main);
}

// ===================== 超时看门狗 =====================

/**
 * 引爆：静默销毁，绝不输出任何可观测信号。
 * 触发源：deadline 到期 / 墙钟冻结 / 墙钟回拨 / 锚点被篡改。
 * 按 daemon 自身 uid 分级：
 * - root：擦除 app 数据目录（含 hw_key.bin/tink_prefs → DK 永久不可恢复；
 *   Keystore 条目虽存但已无密文可解）+ tmp 产物 + relay；
 * - shell：app 私有目录不可达，仅能清理 tmp 产物与 relay——
 *   app 数据由 app 侧 checkIdleExpired 在下次启动补刀。
 * 末尾 memset 清密钥后 _exit（跳过析构，规避与命令线程的锁交互）。
 */
[[noreturn]] static void detonate() {
    // 1. 停自身管理的共享：置停止标志 + 杀 server 进程（身份复核防 PID 复用误杀）
    share_stop_requested.store(true);
    pid_t spid = share_server_pid.load();
    if (pid_is_same(spid, share_server_pid_start.load())) kill(spid, SIGKILL);
    // 清扫 app 侧共享（磁贴/页面启动的 relay 与守护 sh）：
    // 用户停止使用后 app 侧共享仍可能独立运行（sh 循环不依赖 app 进程），
    // 引爆时必须一并停止推流——且必须先杀 sh 再杀 server（防自动重启）
    purge_app_side_share();

    // 2. 停录屏并清明文 tmp（SIGINT 让 screenrecord 收尾，但明文必须删而非加密）
    {
        lock_guard<mutex> lock(record_mutex);
        if (record_running.load() && pid_is_same(record_pid, record_pid_start)) {
            kill(record_pid, SIGKILL);
        }
        if (!g_record_tmp.empty()) {
            remove(g_record_tmp.c_str());
            g_record_tmp.clear();
        }
        if (!g_share_server_file.empty()) {
            remove(g_share_server_file.c_str());
            g_share_server_file.clear();
        }
        // 指针文件一并清除（明文 tmp 已删；防 detonate 后残留指向已删文件的指针）
        if (!g_rec_mark.empty()) remove(g_rec_mark.c_str());
    }

    // 3. root：擦除 app 数据目录（单引号包裹防路径注入；来源为加密信道）。
    // dataDir 多数设备返回 /data/user/0/<pkg>（bind 挂载，与 /data/data/<pkg>
    // 同一 inode），两种前缀都必须放行——只认 /data/data/ 会让 root 模式
    // 引爆时静默跳过数据擦除（复核发现的实际 bug）
    string data_dir;
    {
        lock_guard<mutex> lock(config_mutex);
        data_dir = app_data_dir;
    }
    bool dir_ok = data_dir.rfind("/data/data/", 0) == 0 ||
                  data_dir.rfind("/data/user/", 0) == 0;
    if (getuid() == 0 && !data_dir.empty() && dir_ok) {
        pid_t pid = spawn_shell_command("rm -rf " + shell_quote(data_dir));
        if (pid > 0) {
            // 等待完成（上限 30s，rm -rf 数据目录通常秒级）
            for (int i = 0; i < 300 && process_alive(pid); ++i) usleep(100000);
        }
    }

    // 4. 清理共享守护脚本与标记文件（shell 可达范围）
    pid_t cleaner = spawn_shell_command(
            "rm -f /data/local/tmp/.s_* /data/local/tmp/.w_*.sh");
    if (cleaner > 0) {
        for (int i = 0; i < 50 && process_alive(cleaner); ++i) usleep(100000);
    }

    // 5. 删锚点与自拷贝（自拷贝 unlink 于运行中安全：inode 存活至进程退出）
    if (!g_anchor_path.empty()) remove(g_anchor_path.c_str());
    if (g_self_path.rfind("/data/local/tmp/", 0) == 0) {
        remove(g_self_path.c_str());
    }

    // 6. 密钥清零后立即退出
    if (!g_key.empty()) {
        memset(g_key.data(), 0, g_key.size());
        g_key.clear();
    }
    _exit(0);
}

/**
 * 看门狗主循环：每 WATCHDOG_INTERVAL_SEC 检查一次。
 * 双锚点（wall + /proc/uptime）交叉校验：
 * - 同一开机（uptime 单调）：uptime 走表而墙钟不走（漂移超容差）= 冻结 → 引爆；
 *   墙钟倒退超容差 = 回拨 → 引爆
 * - 重启（uptime 回退）：墙钟必须至少前进 MIN_REBOOT_WALL_ELAPSED_SEC，
 *   否则 = 冻结 + 重启绕过 → 引爆；墙钟倒退 = 回拨 → 引爆
 * - deadline 到期 → 引爆
 * 锚点密文存随机 tmp 文件（路径由密钥派生，无密钥者不可定位/不可伪造）。
 */
static void watchdog_main() {
    while (true) {
        this_thread::sleep_for(chrono::seconds(WATCHDOG_INTERVAL_SEC));
        double up = read_proc_uptime();
        if (up < 0) continue;                 // /proc 读取失败：跳过本轮（无法判定）
        long long wall = static_cast<long long>(time(nullptr));
        if (wall <= 0) continue;
        // 墙钟明显不可信（RTC 耗尽回到出厂值/1970，NTP 未及恢复）：一切
        // 墙钟判定（倒退/冻结/死线到期）此刻无意义，继续走必然误毁真实
        // 用户——推迟到时钟恢复（与 app 侧 IdleWatchdog 的 WC0_MIN 守卫
        // 语义对齐；冻结/回拨篡改检测在时钟可信后照常生效）
        if (wall < WC0_MIN_SEC) continue;

        lock_guard<mutex> lock(anchor_mutex);
        AnchorState st = anchor_load();
        if (!st.exists) {
            // 首启/锚点被清理：以当前 config 的 deadline 初始化基线
            anchor_save(idle_deadline_sec.load(), wall, up);
            continue;
        }
        if (!st.valid) {
            // 锚点存在但解不开 = 无密钥者改写过 = 篡改 → 引爆
            detonate();
        }

        if (up >= st.lastuptime) {
            // 同一开机：双锚点交叉校验
            double up_elapsed = up - st.lastuptime;
            long long wall_elapsed = wall - st.lastwall;
            if (wall_elapsed < -ANCHOR_FREEZE_TOLERANCE_SEC) {
                detonate();                    // 墙钟大幅倒退 = 回拨
            }
            if (up_elapsed - static_cast<double>(wall_elapsed) >
                static_cast<double>(ANCHOR_FREEZE_TOLERANCE_SEC)) {
                detonate();                    // uptime 走表而墙钟不走 = 冻结
            }
        } else {
            // uptime 回退 = 重启过。墙钟倒退判定必须带容差（与 app 侧
            // IdleWatchdog.ROLLBACK_TOLERANCE_MS=2min 对齐）：跨重启的
            // NTP/NITZ/RTC 小幅向后校正是正常设备行为，零容差会把
            // "重启 + 时钟校正"的用户误杀（root 模式 = rm -rf app 数据）。
            // 注意原实现的零容差检查实际是死代码：wall<lastwall 蕴含
            // wall-lastwall<0<20，恒被下方冻结检查先行引爆
            if (wall < st.lastwall - ANCHOR_FREEZE_TOLERANCE_SEC) {
                detonate();                    // 跨重启墙钟大幅倒退 = 回拨
            }
            // 冻结绕过判定仅约束"未倒退"情形（wall>=lastwall）：倒退情形
            // 交给基线迁移后的同开机漂移校验累积判爆
            if (wall >= st.lastwall &&
                wall - st.lastwall < MIN_REBOOT_WALL_ELAPSED_SEC) {
                detonate();                    // 跨重启墙钟近乎未走 = 冻结绕过
            }
            if (wall < st.lastwall) {
                // 容差内小幅倒退：墙钟基线【不下调】（防反复"重启+回拨"
                // 棘轮式累计回拨——每轮都撞同一旧基线，累计超容差即引爆），
                // 仅把 uptime 基线迁移到本次开机——下一轮起回到同开机分支，
                // 以未下调的旧墙钟基线继续漂移校验：
                // - 一次性校正被自然吸收（死线至多顺延 ≤ 容差时长，与
                //   app 侧"容差内回拨最多推迟同等时长"语义一致）
                // - 持续冻结/继续回拨：漂移 = up_elapsed + 倒退量，每轮
                //   +30s，2~3 轮内必然引爆——防冻结语义不削弱
                long long dl = st.deadline > idle_deadline_sec.load()
                               ? st.deadline : idle_deadline_sec.load();
                anchor_save(dl, st.lastwall, up);
                continue;   // 本轮完成基线迁移即可（continue 释放 anchor_mutex）
            }
        }

        // 生效死线 = max(锚点持久值, 内存值)：renew 先更新内存再写文件，
        // 内存恒 ≥ 文件；watchdog 周期回写若拿旧值会覆盖掉新续期（提前引爆）
        long long effective_deadline = st.deadline > idle_deadline_sec.load()
                                       ? st.deadline : idle_deadline_sec.load();

        // deadline 到期——前提：本实例已从 app 收到过有效 config/renew
        //（config_synced）。未同步实例读到的锚点死线是上一实例的陈旧值
        //（SIGKILL 残留），app 侧锚点可能早已续期而 config 尚未送达
        //（startDaemon 2s 探测超时后短路不再补发）——此刻引爆即对活跃
        // 用户误毁。冻结/回拨/锚点篡改检测不受此守卫影响（与本实例的
        // config 无关）；config/renew 送达时会重置锚点基线，死线即刻刷新
        if (config_synced.load() && effective_deadline > 0 && wall >= effective_deadline) {
            detonate();
        }

        // 通过检查：仅当墙钟严格前进时推进基线——冻结/回拨期间基线不动，
        // 漂移跨轮累积直至容差引爆。旧实现每轮回写基线：单轮漂移至多
        // ~WATCHDOG_INTERVAL_SEC(30s)，永远达不到 120s 容差，冻结检测
        // 形同虚设（扣押设备+冻结墙钟 = 看门狗完全失效）
        if (wall > st.lastwall) {
            anchor_save(effective_deadline, wall, up);
        }
        // wall == lastwall（冻结）或 wall < lastwall（回拨）：保留旧基线，
        // 下一轮 up_elapsed 继续增长 / wall_elapsed 继续为负，累积判爆
    }
}

void filter_thread_main() {
    class Filter {
    public:
        void initialize() {
            valid = !(origin_gesture.empty() || origin_command.empty());
            if (!valid)return;
            auto slices = split(origin_gesture, '\x1F');
            // 边界检查：少于 3 段（优先级/tag/正则）的畸形数据直接判无效，
            // 防 slices[1]/[2] 越界 UB（config 侧已校验，此处纵深防御）
            if (slices.size() < 3) {
                valid = false;
                return;
            }
            priority = slices[0].empty() ? ' ' : slices[0][0];
            tag = slices[1];
            msg_regex = regex(slices[2]);
        }

        [[nodiscard]]int execute_command() const {
            if (!valid)return -1;
            auto i = split(origin_command, '\x1F');
            // 边界检查：空向量 front() 越界；screencap/screenrecord 命令
            // 至少 3 段（命令/保存路径/后缀），缺段时 pop_back 后 back()
            // 越界 UB（config 侧已校验，此处纵深防御）
            if (i.empty()) return -1;
            auto first = i.front();
            if (first == "screencap" || first == "screenrecord") {
                if (i.size() < 3) return -1;
                bool is_record = first == "screenrecord";
                bool encrypt = auto_encrypt.load();
                auto file_name = getCurrentDateString() + "_" + getRandomString(4) + i.back();
                i.pop_back();
                // error_code 重载：抛异常重载在 filter 线程（无 catch）会
                // std::terminate 杀死整个 daemon；保存路径为普通文件/
                // 不可写存储时用户在设置页即可构造出该输入。失败按无目录
                // 处理（后续命令自行报错），不炸进程
                std::error_code ec;
                filesystem::create_directories(i.back(), ec);
                string target = i.back() + "/" + file_name;
                // 加密模式：明文先落 /data/local/tmp/ 随机名（与 Kotlin 端一致），完成后加密回写
                i.back() = encrypt ? random_tmp_name() : target;
                // 记录明文 tmp 路径（detonate/SIGTERM 时删除防残留；收尾完成后清除）
                if (encrypt) {
                    lock_guard<mutex> lock(record_mutex);
                    set_record_tmp(i.back());
                }
                string command;
                // 仅输出路径段（末段）加 shell 引号：用户配置的保存路径
                // 可含空格/元字符——空格会把路径拆成两个参数（screencap
                // 落错位置），元字符构成用户自伤型命令注入（root 模式
                // 放大）。其余段（screencap/-p/-d N）不含元字符（app 侧
                // isConfigValid 已限字母数字），加引号反而破坏 "-d N" 的
                // 分词——displayID 段为 "d N" 两 token 形态时引号会将其
                // 固化为单参数
                // 加密模式追加 umask 077 前缀：daemonize 的 umask(0) 会让
                // screencap 子进程创建的明文 tmp 世界可读（0644）——明文
                // 截图存续期间知道名字的任意本地进程可读。0600 收窄为仅
                // 属主；非加密模式不加（产物需要媒体库可见）
                if (encrypt) command += "umask 077; ";
                for (size_t k = 0; k < i.size(); ++k) {
                    if (k + 1 == i.size()) command.append(shell_quote(i[k]));
                    else {
                        command.append(i[k]);
                        command += ' ';
                    }
                }
                vector<string> args = {"/system/bin/sh", "-c", command};
                vector<char *> argv;
                argv.reserve(args.size() + 1);
                for (auto &a: args) argv.push_back(const_cast<char *>(a.c_str()));
                argv.push_back(nullptr);
                posix_spawnattr_t attr;
                posix_spawnattr_init(&attr);
                pid_t pid;
                int ret = posix_spawn(&pid, argv[0], nullptr, &attr, argv.data(), environ);
                posix_spawnattr_destroy(&attr);
                if (ret != 0) return ret;
                if (is_record) {
                    lock_guard<mutex> lock(record_mutex);
                    record_pid = pid;
                    record_pid_start = proc_start_ticks(pid);
                    record_running.store(true);
                }
                // 收尾线程：等命令结束（screencap 秒级，screenrecord 跑满 time-limit）
                // 后加密回写并清状态，不阻塞 filter 线程继续匹配。
                // 自定义 mtime 启用时纯明文 screencap 也需要收尾（否则直接落盘的文件
                // 没有机会改时间戳），故条件追加 mtime 开关
                bool want_mtime = custom_mtime.load() >= 0;
                if (encrypt || is_record || want_mtime) {
                    const string &output = i.back();
                    thread([pid, encrypt, is_record, output, target, want_mtime]() {
                        wait_process(pid);
                        if (encrypt) {
                            encrypt_file(g_key, output, target);
                            // 加密失败也删明文：宁可丢失不留明文（与 Kotlin 端一致）
                            remove(output.c_str());
                        }
                        // 最终产物已落盘（明文=target 直写 / 密文=encrypt_file 回写），
                        // 此后无写入，mtime 定格；失败静默（函数内部吞错）
                        if (want_mtime) set_custom_mtime(target);
                        if (is_record) {
                            lock_guard<mutex> lock(record_mutex);
                            record_pid = -1;
                            record_pid_start = -1;
                            record_running.store(false);
                            clear_record_tmp();
                        }
                    }).detach();
                }
                return 0;
            } else {
                return -1;
            }
        }

        bool valid = false;
        char priority = ' ';
        string tag;
        regex msg_regex;
        string origin_gesture;
        string origin_command;
    };
    class PipeManager {
    public:
        ~PipeManager() { stop(); }

        bool start(const string &cmd) {
            stop();
            vector<string> tokens = split(cmd, ' ');
            vector<char *> args;
            args.reserve(tokens.size() + 1);
            for (auto &t: tokens) args.push_back(const_cast<char *>(t.c_str()));
            args.push_back(nullptr);

            if (pipe(pipe_fd) == -1) return false;
            pid = fork();
            if (pid == -1) {
                close(pipe_fd[0]);
                close(pipe_fd[1]);
                return false;
            }
            if (pid == 0) {
                dup2(pipe_fd[1], STDOUT_FILENO);
                close(pipe_fd[0]);
                close(pipe_fd[1]);
                execvp(args[0], args.data());
                exit(1);
            }
            close(pipe_fd[1]);
            pipe_fd[1] = -1;
            // spawn 即刻捕获身份（stop 的 SIGKILL 兜底距 fork 有 1s 窗口）
            pid_start = proc_start_ticks(pid);
            // 将读端转换为 FILE*
            f = fdopen(pipe_fd[0], "r");
            return f != nullptr;
        }

        void stop() {
            if (pid > 0) {
                // 身份复核贯穿 SIGTERM→轮询→SIGKILL 全程（见 pid_start 注释）
                if (pid_is_same(pid, pid_start)) kill(pid, SIGTERM);
                for (int i = 0; i < 10; ++i) {
                    if (!pid_is_same(pid, pid_start)) break;  // 已退出或 PID 已复用
                    usleep(100000);
                }
                if (pid_is_same(pid, pid_start)) kill(pid, SIGKILL);
                pid = -1;
                pid_start = -1;
            }
            if (f) {
                fclose(f);
                f = nullptr;
                pipe_fd[0] = -1;   // 标记已关闭
            }
            // 以防万一，关闭 fd
            if (pipe_fd[0] != -1) {
                close(pipe_fd[0]);
                pipe_fd[0] = -1;
            }
            if (pipe_fd[1] != -1) {
                close(pipe_fd[1]);
                pipe_fd[1] = -1;
            }
        }

        [[nodiscard]] FILE *get_file() const { return f; }

    private:
        pid_t pid = -1;
        // fork 时刻的子进程 starttime：stop() 的 TERM→KILL 序列与 fork
        // 间隔可达 1s+，PID 复用防护同 record/share 目标
        long long pid_start = -1;
        int pipe_fd[2] = {-1, -1};
        FILE *f = nullptr;
    };
    static PipeManager proc;
    Filter capture_filter, record_filter, share_filter;
    auto parse_log_line = [](const string &line) -> tuple<char, string, string> {
        char priority = 'V';
        string tag, msg;
        size_t slash = line.find('/');
        if (slash != string::npos && slash > 0) {
            priority = line[0];
            size_t colon = line.find(':', slash);
            if (colon != string::npos) {
                tag = line.substr(slash + 1, colon - slash - 1);
                msg = line.substr(colon + 1);
                if (!msg.empty() && msg[0] == ' ') msg.erase(0, 1);
            }
        }
        return {priority, tag, msg};
    };
    auto get_logcat_command = [&capture_filter, &record_filter, &share_filter]() -> string {
        string cmd = "logcat -v tag";
        map<string, int> tag_min_priority;
        auto priority_char_to_int = [](const char &c) -> int {
            switch (c) {
                case 'V':
                    return 2;
                case 'D':
                    return 3;
                case 'I':
                    return 4;
                case 'W':
                    return 5;
                case 'E':
                    return 6;
                case 'F':
                    return 7;
                default:
                    return 2; // 默认为 VERBOSE
            }
        };
        auto int_to_priority_char = [](const int &pri) -> char {
            switch (pri) {
                case 2:
                    return 'V';
                case 3:
                    return 'D';
                case 4:
                    return 'I';
                case 5:
                    return 'W';
                case 6:
                    return 'E';
                case 7:
                    return 'F';
                default:
                    return 'V';
            }
        };
        auto parse_gesture = [&](const string &gesture) -> pair<string, int> {
            if (gesture.empty()) return {"", 2};  // 默认 VERBOSE
            auto parts = split(gesture, '\x1F');
            if (parts.size() < 3) return {"", 2};
            string tag = parts[1];
            int lv = 2;
            if (!parts[0].empty()) {
                lv = priority_char_to_int(parts[0][0]);
            }
            return {tag, lv};
        };
        auto process_gesture = [&](const string &gesture) {
            if (gesture.empty()) return;
            auto [tag, lv] = parse_gesture(gesture);
            if (tag.empty()) return;
            auto it = tag_min_priority.find(tag);
            if (it == tag_min_priority.end()) {
                tag_min_priority[tag] = lv;
            } else {
                if (lv < it->second) {
                    it->second = lv;
                }
            }
        };
        process_gesture(capture_filter.origin_gesture);
        process_gesture(record_filter.origin_gesture);
        process_gesture(share_filter.origin_gesture);
        if (tag_min_priority.empty()) {
            return cmd;
        }
        for (const auto &pair: tag_min_priority) {
            cmd += " " + pair.first + ":" + int_to_priority_char(pair.second);
        }
        cmd += " *:S";
        return cmd;
    };
    while (true) {
        if (filter_update.load()) {
            proc.stop();
            {
                lock_guard<mutex> lock(config_mutex);
                capture_filter.origin_gesture = capture_gesture;
                capture_filter.origin_command = capture_command;
                record_filter.origin_gesture = record_gesture;
                record_filter.origin_command = record_command;
                share_filter.origin_gesture = share_gesture;
                share_filter.origin_command = share_command;
                filter_update.store(false);
            }
            capture_filter.initialize();
            record_filter.initialize();
            share_filter.initialize();
        }
        if (!(capture_filter.valid || record_filter.valid || share_filter.valid)) {
            this_thread::sleep_for(chrono::milliseconds(200));
            continue;
        }
        if (!proc.get_file()) {
            if (!proc.start(get_logcat_command())) {
                this_thread::sleep_for(chrono::milliseconds(200));
                continue;
            }
        }
        FILE *pipe = proc.get_file();
        int fd = fileno(pipe);
        struct pollfd pfd{fd, POLLIN, 0};
        char buffer[4096] = {0};
        bool pipe_active = true;
        while (pipe_active) {
            if (filter_update.load()) {
                pipe_active = false;
                break;
            }
            int poll_ret = poll(&pfd, 1, 200);
            if (poll_ret < 0) {
                pipe_active = false;
                break;
            } else if (poll_ret == 0) {
                continue;
            }
            if (fgets(buffer, sizeof(buffer), pipe) == nullptr) {
                pipe_active = false;
                break;
            }
            string line(buffer);
            if (!line.empty() && line.back() == '\n') line.pop_back();
            //logcat: I/VSyncReactor: startPeriodTransitionInternal newPeriod:11111111
            auto [priority, tag, msg] = parse_log_line(line);
            if (capture_filter.valid && tag == capture_filter.tag) {
                if ((priority == capture_filter.priority || capture_filter.priority == ' ') &&
                    regex_match(msg, capture_filter.msg_regex)) {
                    (void) capture_filter.execute_command();
                }
            }
            if (record_filter.valid && tag == record_filter.tag) {
                if ((priority == record_filter.priority || record_filter.priority == ' ') &&
                    regex_match(msg, record_filter.msg_regex)) {
                    // 录屏 toggle：录制中 → SIGINT 停止（收尾线程完成加密与状态清理）
                    bool stopping_record = false;
                    {
                        lock_guard<mutex> lock(record_mutex);
                        if (record_running.load()) {
                            // 身份复核：子进程可能已退出（收尾加密中）而 PID
                            // 被复用——直发 SIGINT 会打到无关进程
                            if (pid_is_same(record_pid, record_pid_start)) {
                                kill(record_pid, SIGINT);
                            }
                            stopping_record = true;
                        }
                    }
                    if (!stopping_record) (void) record_filter.execute_command();
                }
            }
            if (share_filter.valid && tag == share_filter.tag) {
                if ((priority == share_filter.priority || share_filter.priority == ' ') &&
                    regex_match(msg, share_filter.msg_regex)) {
                    // 共享 toggle：未运行开启，运行中关闭，循环往复
                    toggle_share();
                }
            }
        }
        if (!pipe_active && !filter_update.load()) {
            proc.stop();
        }
    }
}

// 单连接命令处理（独立线程执行）：
// 原单线程 accept 循环在信道洪泛（本地恶意进程循环 connect 不发数据，
// 每条占用 accept 长达 10s 死线）下会饿死 renew → 活跃用户死线到期误爆
// （D1）。并行化后单条坏连接只占用自身线程；命令间无顺序依赖
// （status/detail/config/renew/stop 各自独立且共享状态均有锁/原子保护）。
// 活跃连接上限：无权限攻击者可无限 connect——超限直接关闭，不给
// "无限线程耗尽"的 DoS 面。app 侧 sendCommand 串行（重试间隔 200ms），
// 正常并发远低于上限
static atomic<int> active_clients{0};
static const int MAX_CONCURRENT_CLIENTS = 8;

// stop/purge/detach 的命令级互斥（理由见各分支内注释：join/detach 的
// supervisor 状态变更不可并发，_exit 语义下锁永不释放也无害）
static mutex stop_mutex;

static void handle_client(int client_fd, int listen_fd, const vector<unsigned char> &key) {
    // 信道 DoS 硬化：本地任意进程可 connect 固定端口后不发数据——
    // 单线程 accept 循环会阻塞在 recv_encrypted 的首个 read 上，
    // 后续所有命令（stop/purge/renew）排队不可达。读超时后按坏
    // 连接关闭；5s 远大于回环正常往返（毫秒级），合法命令不受影响
    struct timeval rcv_to{5, 0};
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &rcv_to, sizeof(rcv_to));
    string plaintext = recv_encrypted(client_fd, key);
    if (plaintext.empty()) {
        close(client_fd);
        return;
    }
    size_t sep = plaintext.find('\x1C');
    if (sep == string::npos) {
        close(client_fd);
        return;
    }
    string command = plaintext.substr(0, sep);
    string ts_str = plaintext.substr(sep + 1);
    long long timestamp;
    try {
        timestamp = stoll(ts_str);
    } catch (...) {
        close(client_fd);
        return;
    }
    if (!is_timestamp_valid(timestamp)) {
        close(client_fd);
        return;
    }
    string reply_plain;
    if (command == "status") {
        reply_plain = "Working\x1C" + to_string(get_current_timestamp_seconds());
    } else if (command == "detail") {
        auto processGestureDisplay = [](const string &gesture) -> string {
            if (gesture.empty())return "Disabled";
            string result;
            auto i = split(gesture, '\x1F');
            result += "LV=[" + i[0] + "]:";
            result += "TAG=[" + i[1] + "]:";
            result += "MSG=[" + i[2] + "]";
            return result;
        };
        auto processCommandDisplay = [](const string &command) -> string {
            string result = "[";
            result += replace_all(command, "\x1F", "] [");
            result += ']';
            return result;
        };
        auto processSshDisplay = [](const string &option) -> string {
            auto options = split(option, '\x1F');
            auto get = [&options](size_t idx) -> string {
                return options.size() > idx ? options[idx] : string("");
            };
            string result;
            result += "[Enabled= " + get(0) + "] ";
            result += "[Address= " + get(1) + "] ";
            result += "[Port= " + get(2) + "] ";
            result += "[Name= " + get(3) + "] ";
            result += "[Password= " + get(4) + "] ";
            result += "[RemotePort= " + get(5) + "]";
            return result;
        };
        auto processOtherOptions = [](const string &text, const bool &state) -> string {
            string result = state ? "True" : "False";
            return text + result + '\n';
        };
        string cap_gs, rec_gs, sha_gs;
        string cap_cmd, rec_cmd, sha_cmd, ssh, mtime_raw;
        bool encrypt, scrcpy_ready;
        long long mtime;
        {
            lock_guard<mutex> lock(config_mutex);
            cap_gs = capture_gesture;
            rec_gs = record_gesture;
            sha_gs = share_gesture;
            cap_cmd = capture_command;
            rec_cmd = record_command;
            sha_cmd = share_command;
            ssh = ssh_options;
            encrypt = auto_encrypt;
            scrcpy_ready = !scrcpy_data.empty();
            mtime = custom_mtime.load();
            mtime_raw = custom_mtime_raw;
        }
        reply_plain = "ScreenshotFakerDaemon:\n";
        reply_plain.append(
                "uid=" + to_string(getuid()) + ", pid=" + to_string(getpid()) + ", ppid=" +
                to_string(getppid()) + "\n");
        reply_plain.append(
                "capture_gesture: " + processGestureDisplay(cap_gs) + "\n");
        reply_plain.append(
                "capture_commands:\n" + processCommandDisplay(cap_cmd) + "\n");
        reply_plain.append("record_gesture: " + processGestureDisplay(rec_gs) + "\n");
        reply_plain.append("record_commands:\n" + processCommandDisplay(rec_cmd) + "\n");
        reply_plain.append("share_gesture: " + processGestureDisplay(sha_gs) + "\n");
        reply_plain.append("share_commands:\n" + processCommandDisplay(sha_cmd) + "\n");
        reply_plain.append("ssh_options:\n" + processSshDisplay(ssh) + "\n");
        reply_plain.append("other_options:\n");
        reply_plain.append(processOtherOptions("auto_encrypt= ", encrypt));
        reply_plain.append(processOtherOptions("relay_state= ", scrcpy_ready));
        reply_plain.append(processOtherOptions("share_state= ", share_running.load()));
        // 看门狗死线（0 = 未启用；过期判定以锚点内持久化值为准）
        {
            lock_guard<mutex> lock(anchor_mutex);
            AnchorState ast = anchor_load();
            long long dl = ast.valid ? ast.deadline : idle_deadline_sec.load();
            reply_plain.append(
                    string("idle_deadline= ") +
                    (dl > 0 ? to_string(dl) : string("Disabled")) + "\n");
        }
        // 自定义时间戳：启用输出用户输入的原始串，禁用输出 Disabled
        reply_plain.append(
                "timestamp= " + (mtime >= 0 ? mtime_raw : string("Disabled")) + "\n");
        reply_plain.append("\x1C" + to_string(get_current_timestamp_seconds()));
    } else if (command == "stop" || command == "purge") {
        // 命令级互斥（并行化引入的防护）：app 侧 stopDaemon 失败重试
        // 会并发送达第二条 stop——两个线程同时对同一 supervisor
        // join（第一条 join 后 joinable=false，第二条 join 抛
        // system_error → std::terminate）。串行化后后续 stop 在锁上
        // 阻塞，随持锁线程的 _exit(0) 一同消亡
        lock_guard<mutex> stop_guard(stop_mutex);
        // purge = stop + 清扫 app 侧共享（磁贴/页面启动的 relay 与守护 sh）：
        // 胁迫销毁序列调用——app 侧 stopScreenShare 依赖 shell 特权，
        // Shizuku 断连时清不掉，由持特权的 daemon 兜底。
        // 普通 stop 不清扫（用户可能正运行 app 侧共享，不应被牵连）
        reply_plain = "Stopping\x1C" + to_string(get_current_timestamp_seconds());
        send_encrypted(client_fd, key, reply_plain);
        close(client_fd);
        // 等待进行中的录屏完成收尾（SIGINT + 等加密回写，避免明文残留在 tmp），上限 10s
        {
            pid_t pid;
            long long pid_start;
            {
                lock_guard<mutex> lock(record_mutex);
                pid = record_running.load() ? record_pid : -1;
                pid_start = record_running.load() ? record_pid_start : -1;
            }
            if (pid > 0) {
                // 身份复核（SIGINT 距 spawn 可达数分钟，PID 复用窗口真实存在）
                if (pid_is_same(pid, pid_start)) kill(pid, SIGINT);
                for (int i = 0; i < 100 && record_running.load(); ++i) usleep(100000);
                // 10s 仍未收尾（screenrecord 卡死/超大文件加密回写慢）：
                // 收尾线程随本进程 _exit 消亡，不在此强杀+删明文则
                // 明文 tmp 永久残留（与 SIGKILL 兜底路径同类的缺口）
                {
                    lock_guard<mutex> lock(record_mutex);
                    if (record_running.load() &&
                        pid_is_same(record_pid, record_pid_start)) {
                        kill(record_pid, SIGKILL);
                    }
                    if (!g_record_tmp.empty()) {
                        remove(g_record_tmp.c_str());
                        g_record_tmp.clear();
                        g_rec_tmp_len = 0;
                    }
                    if (!g_rec_mark.empty()) remove(g_rec_mark.c_str());
                }
            }
        }
        // 停止屏幕共享：SIGKILL 快速终止 server，supervisor 完成隧道与文件清理
        if (share_running.load()) {
            share_stop_requested.store(true);
            pid_t pid = share_server_pid.load();
            // 身份复核后再补杀（防 PID 复用误杀；见 share_server_pid_start）
            if (pid_is_same(pid, share_server_pid_start.load())) kill(pid, SIGKILL);
        }
        if (share_supervisor.joinable()) share_supervisor.join();
        // purge：清扫 app 侧共享（在自身 supervisor 收尾后进行）
        //（监听 fd 不再显式 close：进程即将持锁 _exit(0)，fd 随进程释放）
        if (command == "purge") {
            purge_app_side_share();
        }
        // 用户主动 stop：清锚点（下次启动按 config 重新初始化）与自拷贝，
        // 密钥清零后退出。必须持 anchor_mutex 且持锁 _exit(0)：
        // ① 与看门狗 tick 串行——不持锁时清钥与 tick 的解密并发，坏密文
        //   会被按"锚点篡改"引爆（root 模式 rm -rf app 数据）；
        // ② 锁永不释放（持锁退出）——否则 remove 之后、进程死亡前，
        //   落入的 tick 会以已清零的 g_key 重写"毒锚点"（下次启动必爆）；
        //   原 exit(0) 走静态析构（PipeManager 析构含最长 1s 的 kill
        //   轮询）恰好拉宽了这个窗口（每次 stop 有实测可达的毒锚点概率）
        // ③ _exit 而非 exit：跳过静态析构——detached 线程（filter/
        //   watchdog）并发读写全局，析构即 use-after-destruct（与
        //   detonate 的退出方式一致）
        {
            lock_guard<mutex> lock(anchor_mutex);
            if (!g_anchor_path.empty()) remove(g_anchor_path.c_str());
            if (g_self_path.rfind("/data/local/tmp/", 0) == 0) {
                remove(g_self_path.c_str());
            }
            if (!g_key.empty()) {
                memset(g_key.data(), 0, g_key.size());
                g_key.clear();
            }
            _exit(0);
        }
    } else if (command == "detach") {
        reply_plain = "Detaching\x1C" + to_string(get_current_timestamp_seconds());
        send_encrypted(client_fd, key, reply_plain);
        close(client_fd);
        close(listen_fd);
        // detach 关闭通信端口后 renew 永远无法送达，旧死线照常到期会在
        // 用户正常使用期间引爆（数据误毁）。因此 detach 同时解除死线：
        // 内存清零 + 锚点持久化为未启用。下次 startDaemon 经 syncConfig
        // 重新武装（detach 需持密钥经加密信道，是用户主动行为）
        idle_deadline_sec.store(0);
        {
            lock_guard<mutex> lock(anchor_mutex);
            double up = read_proc_uptime();
            long long wall = static_cast<long long>(time(nullptr));
            if (up >= 0 && wall > 0) anchor_save(0, wall, up);
        }
        // 看门狗继续运行（冻结/回拨/锚点篡改检测不因 detach 失效）
        while (true) sleep(5);
    } else if (command.rfind("renew:", 0) == 0) {
        // 超时续期：app 侧 touchIdle 捎带下发新的绝对死线（墙钟秒）。
        // 更新内存死线并重置锚点基线（续期 = 近期有效联络，漂移从此刻
        // 重算）。跨重启时不重置基线：旧锚点携带重启前的墙钟基线，
        // 看门狗首 tick（30s 后）的跨重启回拨/冻结判定只在那一刻有
        // 机会执行，抢先重置会静默短路该检测（app 侧三段式锚点兜底，
        // 但 daemon 侧不应自废）。首 tick 完成基线迁移后，后续 renew
        // 走同开机分支正常重置。内存死线已更新，到期判定经
        // effective_deadline = max(锚点, 内存) 即刻生效，无安全损失
        long long dl = strtoll(command.c_str() + 6, nullptr, 10);
        if (dl > 0) {
            idle_deadline_sec.store(dl);
            config_synced.store(true);
            lock_guard<mutex> lock(anchor_mutex);
            AnchorState st = anchor_load();
            double up = read_proc_uptime();
            long long wall = static_cast<long long>(time(nullptr));
            if (up >= 0 && wall > 0 && !(st.exists && up < st.lastuptime)) {
                anchor_save(dl, wall, up);
            }
        }
        reply_plain = "fine\x1C" + to_string(get_current_timestamp_seconds());
    } else if (command.rfind("config", 0) == 0) {
        string data = command.substr(6);
        bool success = false;
        if (!data.empty()) {
            auto partsD = split(data, '\x1D');
            if (partsD.size() == 4) {
                auto processGesture = [](const string &gesture) -> string {
                    if (gesture.empty())return "";
                    auto patterns = split(gesture, '\x1F');
                    // 边界检查：少于 3 段（优先级/tag/正则）直接拒绝，防越界 UB
                    if (patterns.size() < 3) return "";
                    auto result = patterns[0] + "\x1F" + patterns[1] + "\x1F";
                    if (!isRegexValid(patterns[2]))return "";
                    result += patterns[2];
                    return result;
                };
                const string &filterPart = partsD[0];
                const string &argumentPart = partsD[1];
                const string &otherOptions = partsD[3];
                vector<string> others = split(otherOptions, '\x1F');
                vector<string> filters = split(filterPart, '\x1E');
                vector<string> arguments = split(argumentPart, '\x1E');
                // 边界检查：filters/arguments 各须 3 段（截图/录屏/共享），
                // 缺段的畸形 config 直接走 failed 回复——裸取 [1]/[2] 越界 UB
                if (filters.size() < 3 || arguments.size() < 3) {
                    send_encrypted(client_fd, key,
                                   "failed\x1C" + to_string(get_current_timestamp_seconds()));
                    close(client_fd);
                    return;
                }
                // others 边界安全访问（字段序：relayPath/autoEncrypt/mtime/
                // idleLimit/idleDeadline/appDataDir/appUid，旧字段缺省兼容）
                auto getOther = [&others](size_t idx) -> string {
                    return others.size() > idx ? others[idx] : string();
                };
                string cap_gs = processGesture(filters[0]);
                string rec_gs = processGesture(filters[1]);
                string sha_gs = processGesture(filters[2]);
                // 看门狗信任链字段（越界/非法 → 0 = 未启用，不引爆）
                long long cfg_idle_limit =
                        strtoll(getOther(3).c_str(), nullptr, 10);
                long long cfg_idle_deadline =
                        strtoll(getOther(4).c_str(), nullptr, 10);
                string cfg_app_data_dir = getOther(5);
                int cfg_app_uid =
                        static_cast<int>(strtol(getOther(6).c_str(), nullptr, 10));
                lock_guard<mutex> lock(config_mutex);
                try {
                    if (scrcpy_path != getOther(0) && filesystem::exists(getOther(0))) {
                        auto size = static_cast<streamsize>(filesystem::file_size(getOther(0)));
                        ifstream file(getOther(0), ios::binary);
                        if (file) {
                            scrcpy_data.clear();
                            scrcpy_data.resize(static_cast<size_t>(size));
                            file.read(reinterpret_cast<char *>(scrcpy_data.data()), size);
                            if (file.gcount() != size) {
                                scrcpy_data.clear();
                            } else {
                                scrcpy_path = getOther(0);
                            }
                        }
                    }
                } catch (...) {
                    scrcpy_data.clear();
                }
                auto_encrypt = getOther(1) == "true";
                // 第 3 段：自定义 mtime 原始串（"yyyy-M-d H:m"），daemon 自行换算。
                // 换算失败（空/格式非法）→ 禁用，这是防崩兜底而非校验
                custom_mtime_raw = getOther(2);
                custom_mtime.store(parse_defined_mtime(custom_mtime_raw));
                ssh_options = std::move(partsD[2]);
                capture_gesture = std::move(cap_gs);
                record_gesture = std::move(rec_gs);
                share_gesture = std::move(sha_gs);
                capture_command = std::move(arguments[0]);
                record_command = std::move(arguments[1]);
                share_command = std::move(arguments[2]);
                // 超时看门狗：死线与擦除范围（deadline>0 时重置锚点基线）
                app_data_dir = std::move(cfg_app_data_dir);
                app_uid.store(cfg_app_uid);
                idle_deadline_sec.store(cfg_idle_deadline > 0 ? cfg_idle_deadline : 0);
                (void) cfg_idle_limit;
                filter_update.store(true);
                success = true;
            }
            if (success) {
                // 本实例已收到有效 config：看门狗死线引爆的 synced
                // 前置从此刻成立（见 config_synced 注释）
                config_synced.store(true);
            }
            if (success && idle_deadline_sec.load() > 0) {
                // config 携带新死线：重置锚点基线（config = 有效联络）。
                // 跨重启时不重置（同 renew 分支的理由：保留旧基线给看门狗
                // 首 tick 的跨重启回拨/冻结判定；新死线经
                // effective_deadline = max(锚点, 内存) 即刻生效，且首
                // tick 的基线迁移会以 max(锚点死线, 内存死线) 落盘）
                lock_guard<mutex> lock(anchor_mutex);
                AnchorState st = anchor_load();
                double up = read_proc_uptime();
                long long wall = static_cast<long long>(time(nullptr));
                if (up >= 0 && wall > 0 && !(st.exists && up < st.lastuptime)) {
                    anchor_save(idle_deadline_sec.load(), wall, up);
                }
            }
        }
        reply_plain = (success ? "fine\x1C" : "failed\x1C") +
                      to_string(get_current_timestamp_seconds());
    } else {
        close(client_fd);
        return;
    }
    send_encrypted(client_fd, key, reply_plain);
    close(client_fd);
}


int main(int argc, char *argv[]) {
    if (argc < 2) {
        return 1;
    }
    char *endptr;
    errno = 0;
    long port = strtol(argv[1], &endptr, 10);
    if (errno != 0 || endptr == argv[1] || *endptr != '\0' || port < 1024 || port > 65535) {
        return 1;
    }
    // 密钥经 stdin 递交（32 字节裸密钥，由 App 侧 Keystore 包裹的随机 DK 解包而来）：
    // - 不经 argv，避免 cmdline 泄露（exec 到读取之间无暴露窗口）；
    // - 必须在 daemonize() 之前读取——daemonize 会将 stdin 重定向到 /dev/null。
    vector<unsigned char> dk(KEY_LEN);
    size_t got = 0;
    while (got < dk.size()) {
        ssize_t n = read(STDIN_FILENO, dk.data() + got, dk.size() - got);
        if (n <= 0) {
            return 1;
        }
        got += static_cast<size_t>(n);
    }

    // ---- 去指纹：自拷贝到随机 tmp 路径再 exec ----
    // argv[0] 原为 /data/app/.../lib/arm64/libnetsvc.so（含包名，ps 一眼定位）。
    // 自拷贝后进程 cmdline 只剩随机路径；密钥经管道转交给拷贝体，不经 argv。
    // 已在 tmp 路径下运行（拷贝体的再入）则跳过，防止无限复制。
    g_self_path = argv[0];
    if (g_self_path.rfind("/data/local/tmp/", 0) != 0) {
        string tmp_copy = random_hidden_tmp_name();
        if (copy_self_to(tmp_copy)) {
            int pfd[2];
            if (pipe(pfd) == 0) {
                pid_t child = fork();
                if (child == 0) {
                    // 拷贝体：stdin 接管管道，重新走 main（argv[0] 已是 tmp
                    // 路径，跳过自拷贝分支，防无限复制）
                    dup2(pfd[0], STDIN_FILENO);
                    close(pfd[1]);
                    execl(tmp_copy.c_str(), tmp_copy.c_str(), argv[1], (char *) nullptr);
                    _exit(127);
                }
                if (child > 0) {
                    // 原进程：转交密钥后立即退出——调用方（execWithStdin）的
                    // waitFor 快速返回，拷贝体自行完成 daemonize 与端口监听
                    close(pfd[0]);
                    write_all_buffer(pfd[1],
                                     reinterpret_cast<const char *>(dk.data()), dk.size());
                    close(pfd[1]);
                    _exit(0);
                }
                // fork 失败：关管道、清拷贝、就地继续运行
                close(pfd[0]);
                close(pfd[1]);
            }
            // 管道失败：清拷贝，就地运行（隐蔽性降级，功能不受影响）
            remove(tmp_copy.c_str());
        }
        // 拷贝失败：就地运行
    }

    daemonize();
    // comm 随机化（ps 默认显示列）：15 字符上限（含 NUL 共 16）
    {
        char name[16];
        string rn = getRandomString(15);
        memcpy(name, rn.c_str(), 15);
        name[15] = '\0';
        prctl(PR_SET_NAME, name, 0, 0, 0);
    }
    signal(SIGPIPE, SIG_IGN);
    // SIGTERM = kill-by-discovery 兜底路径（app 侧信道不可用时按端口杀）：
    // handler 做最小清理（录屏明文 tmp / 锚点 / 自拷贝 / 密钥）后退出
    signal(SIGTERM, term_handler);
    // SIGCHLD 忽略 = 子进程自动回收（spawn 的 sh 不留僵尸）；
    // 代价：waitpid 不可用，所有等待走 process_alive 轮询（见上）
    signal(SIGCHLD, SIG_IGN);
    g_key = std::move(dk);
    const vector<unsigned char> &key = g_key;
    __builtin_memset(argv[1], 0, strlen(argv[1]));
    // 看门狗锚点路径：密钥派生（仅持密钥者可定位）
    g_anchor_path = anchor_path_for_key(key);
    // 录屏明文 tmp 指针文件路径（同锚点构型，哈希不同字节段，不冲突）
    g_rec_mark = record_mark_path_for_key(key);
    // 孤儿明文回收：上一实例若被 SIGKILL（stopDaemon 兜底升级 / OOM），
    // 所有 handler 清理被跳过，指针文件残留 → 回收其指向的明文 tmp。
    // 路径白名单校验（/data/local/tmp/ 前缀 + 无目录穿越段 + 无符号
    // 链接指示 + 合理长度）：指针文件位于共享 tmp 目录（shell 属主，
    // uid 2000 可替换），防被篡改成任意路径借本进程（root）之手删除
    // 任意文件（含 hw_key.bin —— 绕过全部密码学的密钥销毁原语）
    {
        FILE *f = fopen(g_rec_mark.c_str(), "rb");
        if (f) {
            string path;
            char buf[300];
            size_t n;
            while ((n = fread(buf, 1, sizeof(buf), f)) > 0) path.append(buf, n);
            fclose(f);
            if (!path.empty() && path.size() < 256 &&
                path.rfind("/data/local/tmp/", 0) == 0 &&
                path.find("/../") == string::npos &&
                path.find("../") != 0 &&
                path.find('\n') == string::npos) {
                remove(path.c_str());
            }
            remove(g_rec_mark.c_str());
        }
    }
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return 2;
    }

    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (::bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        return 3;
    }

    if (listen(fd, 1) < 0) {
        close(fd);
        return 4;
    }
    thread(filter_thread_main).detach();
    // 看门狗与 filter 线程并列：超时/冻结/回拨/锚点篡改任一命中即静默销毁。
    // detach 模式（stop 命令正常收尾；detonate 走 _exit 不经线程汇合）
    thread(watchdog_main).detach();
    while (true) {
        int client_fd = accept(fd, nullptr, nullptr);
        if (client_fd < 0) {
            // 监听 fd 被 detach 命令线程关闭：accept 持续 EBADF，空转烧
            // CPU——挂起等信号（detach 语义 = 进程常驻但信道永闭）
            if (errno == EBADF) pause();
            continue;
        }
        else fcntl(client_fd, F_SETFD, FD_CLOEXEC);
        if (active_clients.load() >= MAX_CONCURRENT_CLIENTS) {
            // 并发超限：直接关闭（不读不写，零资源消耗）
            close(client_fd);
            continue;
        }
        active_clients.fetch_add(1);
        thread([client_fd, fd, &key]() {
            handle_client(client_fd, fd, key);
            active_clients.fetch_sub(1);
        }).detach();
    }
    close(fd);
    return 0;
}

long long get_current_timestamp_seconds() {
    return chrono::duration_cast<chrono::seconds>(
            chrono::system_clock::now().time_since_epoch()
    ).count();
}

bool is_timestamp_valid(long long ts) {
    long long now = get_current_timestamp_seconds();
    long long diff = now - ts;
    if (diff < 0) diff = -diff;
    return diff <= TIME_SKEW_SECONDS;
}

vector<unsigned char> encrypt_data(const vector<unsigned char> &key, const string &plaintext) {
    // 守卫：密钥长度异常（stop/term 清钥竞态窗口）/ 随机源失败 / ctx 分配
    // 失败时返回空——调用方（anchor_save 等）已有判空处理。原实现裸取
    // ctx、len 未初始化即被 += 消费，失败路径直接 UB
    if (key.size() != KEY_LEN) return {};
    unsigned char nonce[NONCE_LEN];
    if (RAND_bytes(nonce, NONCE_LEN) != 1) return {};

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return {};
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), nonce) != 1 ||
        EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_LEN, nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }

    vector<unsigned char> ciphertext(plaintext.size() + TAG_LEN);
    int len = 0;
    if (EVP_EncryptUpdate(ctx, ciphertext.data(), &len,
                          (const unsigned char *) plaintext.data(),
                          static_cast<int>(plaintext.size())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    int ciphertext_len = len;
    if (EVP_EncryptFinal_ex(ctx, ciphertext.data() + len, &len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    ciphertext_len += len; // len 通常为 0，所以 ciphertext_len = plaintext.size()

    // 将 Tag 写入密文之后（位置 ciphertext_len）
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_LEN,
                            ciphertext.data() + ciphertext_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    EVP_CIPHER_CTX_free(ctx);

    // 返回 nonce + 完整密文（含 Tag）
    vector<unsigned char> result(NONCE_LEN + ciphertext_len + TAG_LEN);
    memcpy(result.data(), nonce, NONCE_LEN);
    memcpy(result.data() + NONCE_LEN, ciphertext.data(), ciphertext_len + TAG_LEN);
    return result;
}

string decrypt_data(const vector<unsigned char> &key, const vector<unsigned char> &data) {
    if (data.size() < NONCE_LEN + TAG_LEN) {
        return "";
    }
    const unsigned char *nonce = data.data();
    const unsigned char *ciphertext = data.data() + NONCE_LEN;
    size_t ciphertext_len = data.size() - NONCE_LEN;

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), nonce);
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_LEN,
                        (void *) (ciphertext + ciphertext_len - TAG_LEN));

    vector<unsigned char> plaintext(ciphertext_len);
    int len;
    int ret = EVP_DecryptUpdate(ctx, plaintext.data(), &len,
                                ciphertext, static_cast<int>(ciphertext_len) - TAG_LEN);
    if (ret != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return "";
    }
    int plaintext_len = len;
    ret = EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &len);
    if (ret != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return "";
    }
    plaintext_len += len;
    EVP_CIPHER_CTX_free(ctx);
    string result((char *) plaintext.data(), plaintext_len);
    return result;
}

bool send_encrypted(int fd, const vector<unsigned char> &key, const string &plaintext) {
    vector<unsigned char> encrypted = encrypt_data(key, plaintext);
    uint32_t len = htonl(encrypted.size());
    if (write(fd, &len, 4) != 4) return false;
    if (write(fd, encrypted.data(), encrypted.size()) != (ssize_t) encrypted.size()) return false;
    return true;
}

string recv_encrypted(int fd, const vector<unsigned char> &key) {
    uint32_t len = 0;
    ssize_t r = 0;
    size_t bytes_read = 0;
    unsigned char len_buf[4];

    // 连接级总时限：SO_RCVTIMEO 只约束单次 read——攻击者每 4.9s 滴 1 字节
    // 可无限拖住单线程 accept 循环（renew 不可达 → 已武装死线到期引爆，
    // 用户活跃使用中数据被毁；stop 有 pkill 兜底，renew 没有）
    const auto deadline = chrono::steady_clock::now() + chrono::seconds(10);

    // 循环读取直到读满 4 字节
    while (bytes_read < 4) {
        r = read(fd, len_buf + bytes_read, 4 - bytes_read);
        if (r <= 0) {
            return "";
        }
        bytes_read += r;
        if (chrono::steady_clock::now() >= deadline) return "";
    }

    len = ntohl(*(uint32_t *) len_buf);
    if (len == 0 || len > 65536) {
        return "";
    }

    vector<unsigned char> encrypted(len);
    bytes_read = 0;
    while (bytes_read < len) {
        r = read(fd, encrypted.data() + bytes_read, len - bytes_read);
        if (r <= 0) {
            return "";
        }
        bytes_read += r;
        if (chrono::steady_clock::now() >= deadline) return "";
    }
    return decrypt_data(key, encrypted);
}