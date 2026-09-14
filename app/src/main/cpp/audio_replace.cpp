// E3c-N：native 层音频采集拦截（libaudioclient AudioRecord 数据出口替换）。
//
// 背景（真机实证 2026-09-13/14，ColorOS 15 / com.oplus.screenrecorder）：
// - 录屏器走 C++ AudioRecord（libaudioclient），Java AudioRecord 类从未
//   实例化（Java 层 E3c 钩子全天零触发）。
// - 数据路径按采集引擎分形态（2aqwvd 轮日志矩阵实证）：回调模式
//   （AudioRecordThread → processAudioBuffer → C 回调 mCbf）与 OBTAIN
//   模式（原生引擎 COUIAudioWorkHa 线程直调公有 obtainBuffer）——两条
//   路径都不经 out-of-line 的 read/私有 obtainBuffer 符号（LTO 内联）。
//
// 架构（AOSP android15-release 源码验证）：
// - 主腿（v3 C 回调拦截）：processAudioBuffer hook 首调时读 AudioRecord
//   对象 +MCBF_OFF（LP64 实证 +144）处回调描述结构指针 → *(cb) 即
//   callback_fn 函数指针（dladdr 双验证）→ shadowhook_hook_func_addr
//   挂地址 → proxy_cbf 按 AOSP callback_t 签名 void(int, void*, void*)
//   转发，event==EVENT_MORE_DATA(0) 时 info = AudioRecord::Buffer*，
//   pre-call 三态填充（raw/mSize 由内联 obtainBuffer 已填好）。回调描述
//   结构是全局静态（跨录制会话同址复用），一次 hook 进程内终身有效。
//   会话键 = g_last_ar_obj（pab 每次刷新；录屏器单录制会话，pab 必先于
//   cbf 触发故非 null）。
// - 汇聚腿（私有 obtainBuffer）：read()/回调线程等 out-of-line 路径的
//   通用覆盖。ColorOS 上被 LTO 内联（零触发）但保留。
// - 汇聚腿（公有 obtainBuffer，waitCount 包装）：TRANSFER_OBTAIN 直调
//   路径。2aqwvd 轮实证（2026-09-14）：ColorOS 原生采集引擎（COUIAudio
//   WorkHa 线程）的数据出口不经过 read/pab/obtainPriv/AAudio 任何一个
//   已挂符号（全零触发）但 stop() 正常——排除法定位为公有版直调（其
//   内部对私有版的转发被 LTO 内联）。与私有腿无条件并存，thread_local
//   去重守卫防双填充（双填充会双倍推进假音频帧位）。
// - post-call 内容替换，非阻断：真实数据管线（audioserver → 共享环缓冲）
//   原样运转，产出节奏完全原生（虚拟时钟 pacing 问题天然消失）。Buffer.
//   mSize = frameCount × mServerFrameSize 由 obtainBuffer 自己写入（字节量
//   精确、与客户端格式无关）。
// - obtainBuffer 返回的 chunk 是客户端独占区（直到 releaseBuffer 归还），
//   覆写与 audioserver 生产无竞争；填充与消费者同线程，无并发窗口。
//
// 策略（对齐 Java 层 E3c 语义）：
// - 首次数据出口懒登记：JNI 上调 Kotlin 解析三态策略（recordAudioPolicy
//   ——录屏替换的声音部分，仅录屏视频替换命中时非 OFF）+ 视频 id 快照。
//   OFF → TTL 内放行（不重复上调）；REPLACE/MIX → 会话内持续填充。
// - stop() hook 清会话（录屏会话边界，对齐 Java stop 腿）；TTL 60s 重判
//   （兜底：实例销毁地址复用导致会话陈旧、策略中途变更）。
// - fail-safe：Buffer 布局合理性校验（OEM 布局漂移防御）失败 → 放行 +
//   一次性 WARN（可观测的 fail-open，绝不盲目覆写未知内存）。
//
// 符号（NDK shim 编译实证，LP64/ILP32 的 size_t 差异两套）：
// - _ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_P{m,j}
// - _ZN7android11AudioRecord18processAudioBufferEv（AOSP android15 返回
//   nsecs_t 下次唤醒时间——bool 签名截断（1ns）会致 AudioRecordThread
//   忙转，LP64 int64_t 转发修复）
// - _ZN7android11AudioRecord4stopEv（会话边界清理）
//
// 热路径开销：trampoline + status 检查 + map 查找 + memset（~每 20ms/流），
// 无 JNI（策略上调只在会话懒登记/TTL 时发生，attach 不 detach——AudioRecord
// 采集线程可能是 ART 已附着线程，detach 会破坏 ART 线程归属）。

#include <jni.h>
// ShadowHook 头按架构分流：vendored 版仅支持 ARM（shadowhook.h 只在
// __aarch64__/__arm__ 下定义 shadowhook_cpu_context_t），x86 直接包含会
// 编译失败。x86 分支用文件内最小桩声明兜底——目的不是运行（x86 不产
// libmediafx.so，见 CMakeLists ABI 守卫），而是让本文件在**所有 ABI
// 配置里都有编译命令**：Android Studio 的 C/C++ 语言服务（CLion 引擎）
// 按编译数据库判定文件是否属于项目，无编译命令 = "不属于任何项目目标"
// 误报（HEADER_FILE_ONLY 同样无效，它也不产生编译命令）。桩语义：
// SHADOWHOOK_CALL_PREV 展开为对 proxy 自身的调用——若真被链接执行会
// 无限递归，但 x86 构建产物不进任何目标，仅为类型正确
#if defined(__arm__) || defined(__aarch64__)
#include <shadowhook.h>
#else
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef enum {
    SHADOWHOOK_MODE_SHARED = 0,
    SHADOWHOOK_MODE_UNIQUE = 1,
} shadowhook_mode_t;
#define SHADOWHOOK_ERRNO_PENDING 1
int shadowhook_init(shadowhook_mode_t default_mode, bool debuggable);
void *shadowhook_hook_func_addr(void *func_addr, void *new_addr, void **orig_addr);
void *shadowhook_hook_sym_name(const char *lib_name, const char *sym_name, void *new_addr,
                               void **orig_addr);
int shadowhook_get_errno(void);
const char *shadowhook_to_errmsg(int error_number);
#ifdef __cplusplus
}
#endif
#define SHADOWHOOK_CALL_PREV(func, ...) (func)(__VA_ARGS__)
class ShadowhookStackScope {
public:
    ShadowhookStackScope(void * /*return_address*/) {}
};
#define SHADOWHOOK_STACK_SCOPE() \
  ShadowhookStackScope shadowhook_stack_scope_obj(__builtin_return_address(0))
#endif

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <time.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>

// android::AudioRecord::Buffer（android15-release 布局，NDK shim
// static_assert 实证：LP64 sizeof=32 / ILP32 sizeof=16）
//   frameCount@0（公有，输入=请求帧数，输出=实际可得帧数）
//   mSize@8|@4（私有，输出=可得字节数 = frameCount × serverFrameSize）
//   raw@16|@8（union，输出=数据指针，指向客户端独占的环缓冲 chunk）
//   sequence@24|@12（IAudioRecord 代序号，releaseBuffer 校验用）
struct ArBuffer {
    size_t frameCount;
    size_t mSize;
    void* raw;
    uint32_t sequence;
};

// ==================== 符号名（ABI 分套，见文件头） ====================

#if defined(__LP64__)
#define SYM_OBTAIN_PRIV "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_Pm"
#define SYM_OBTAIN_PUB "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEiPm"
#else
#define SYM_OBTAIN_PRIV "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_Pj"
#define SYM_OBTAIN_PUB "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEiPj"
#endif
#define SYM_STOP "_ZN7android11AudioRecord4stopEv"
#define SYM_PAB "_ZN7android11AudioRecord18processAudioBufferEv"
#define LIB_AUDIOCLIENT "libaudioclient.so"

// v3 C 回调拦截（见文件头）：
// - MCBF_OFF：LP64 AudioRecord 对象内回调描述结构指针的偏移，真机日志
//   实证 +144（指向 libpermission.so RELRO 全局结构，首字段为
//   callback 函数指针——非 sp<> 对象 vptr）
#if defined(__LP64__)
#define MCBF_OFF 144
#else
// 32 位不支持 v3（try_hook_cbf 主体已 #if 挡下，宏值仅为可编译占位）
#define MCBF_OFF 0
#endif

// AOSP AudioRecord C 风格回调事件常量（callback_t 语义）
enum {
    CB_EVENT_MORE_DATA = 0,  // info = AudioRecord::Buffer*（数据就绪）
};

// ==================== 策略常量（对齐 HookConfig 三态） ====================

enum {
    POLICY_OFF = 0,      // 原声：不干预
    POLICY_REPLACE = 1,  // 替换：替换视频音轨覆写；落空（无音轨/超限）回落静音（防泄漏铁律）
    POLICY_MIX = 2,      // 叠加：真实 + 替换视频音轨相加；落空回落原声
};

// 会话 TTL 重判（对齐 Java 层 IGNORE_TTL_MS：OFF→REPLACE/MIX 切换与
// 实例地址复用陈旧会话的刷新上限）
static constexpr int64_t SESSION_TTL_MS = 60'000;

// ==================== JNI 桥缓存 ====================

static JavaVM* g_vm = nullptr;
static jobject g_bridge = nullptr;            // AudioRecordNativeBridge 单例（全局引用）
static jmethodID g_mid_query_policy = nullptr;
static jmethodID g_mid_note_video_id = nullptr;
static jmethodID g_mid_fill_pcm = nullptr;
static jmethodID g_mid_on_log = nullptr;
// 假音频 PCM 拉取复用缓冲（全局引用 jbyteArray；容量 = PCM_BUF_MAX）
static jobject g_pcm_jbuf = nullptr;
static constexpr size_t PCM_BUF_MAX = 64 * 1024;
// PCM 帧位假设的流采样率（Android 音频 HAL 标准 48k；REMOTE_SUBMIX 与
// mic 采集事实标准。真实率偏离时仅音调偏移，无结构破坏）
static constexpr int ASSUMED_RATE = 48000;

static pthread_mutex_t g_bridge_mutex = PTHREAD_MUTEX_INITIALIZER;

// 填充串行化：录屏器多采集线程并发（iilmsg 实证 3 条 AudioBoost 线程），
// 共享 JNI 复用缓冲（g_pcm_jbuf）与 MIX 静态缓冲（session_fill 内 fake）
// 均单份——REPLACE 上调与 MIX 上调+相加全程互斥（20ms 节拍 × 毫秒级
// 工作，串行开销可忽略；换取零数据竞争）
static pthread_mutex_t g_fill_mutex = PTHREAD_MUTEX_INITIALIZER;

// ==================== 会话表 ====================

struct Session {
    int policy;               // POLICY_*：OFF=放行 REPLACE=覆写 MIX=相加
    int64_t queried_at_ms;    // 策略判定时刻（TTL 重判基准）
    int64_t position_frames;  // 假音频目标流帧位（会话内累计，随真实管线节奏）
    bool logged_first;        // 首次填充已打点
    bool warned_implausible;  // 布局异常已告警（每会话一次）
};

static std::mutex g_sessions_mutex;
static std::map<void*, Session> g_sessions;

// v3：最近的 AudioRecord 对象（proxy_pab 每次调用刷新，录屏器单录制
// 会话前提下作为 cbf 回调的会话键——pab 必先于 cbf 触发故非 null）
static std::atomic<void*> g_last_ar_obj{nullptr};

// pab 首调标记：callback_fn hook 一次性安装（cb 为全局静态，跨会话同址）
static std::atomic<bool> g_cbf_hook_tried{false};

// ==================== 日志上调（低频，attach 不 detach） ====================

static int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1'000'000;
}

static void upcall_log(int prio, const char* fmt, ...) __attribute__((format(printf, 2, 3)));

static void upcall_log(int prio, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    pthread_mutex_lock(&g_bridge_mutex);
    jobject bridge = g_bridge;
    jmethodID mid = g_mid_on_log;
    pthread_mutex_unlock(&g_bridge_mutex);
    if (bridge == nullptr || mid == nullptr || g_vm == nullptr) return;

    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
    // 不 Detach：调用线程可能是 ART 已附着线程（app 线程经 JNI 进 native 后
    // 调 C++ AudioRecord），detach 会破坏其 ART 线程归属；原生采集线程保持
    // 附着也无害（ART 自带 pthread key 退出清理）
    jstring jmsg = env->NewStringUTF(buf);
    if (jmsg != nullptr) {
        env->CallVoidMethod(bridge, mid, (jint) prio, jmsg);
        env->DeleteLocalRef(jmsg);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ==================== 策略上调（会话懒登记时，AudioRecord 采集线程） ====================

static int upcall_query_policy() {
    pthread_mutex_lock(&g_bridge_mutex);
    jobject bridge = g_bridge;
    jmethodID mid = g_mid_query_policy;
    pthread_mutex_unlock(&g_bridge_mutex);
    if (bridge == nullptr || mid == nullptr || g_vm == nullptr) return POLICY_OFF;

    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return POLICY_OFF;
    jint policy = env->CallIntMethod(bridge, mid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return POLICY_OFF;  // 上调失败 fail-open（与 Java 层口径一致：解析异常放行）
    }
    if (policy < POLICY_OFF || policy > POLICY_MIX) return POLICY_OFF;
    // 视频 id 快照同步（Kotlin 侧 nativeVideoId——声音源 = 替换视频
    // 音轨，fillPcm 消费）：与策略同一时刻读，保证会话内 id/策略一致性
    pthread_mutex_lock(&g_bridge_mutex);
    jmethodID noteMid = g_mid_note_video_id;
    pthread_mutex_unlock(&g_bridge_mutex);
    if (noteMid != nullptr) {
        env->CallVoidMethod(bridge, noteMid);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    return (int) policy;
}

// ==================== 假音频 PCM 拉取（REPLACE/MIX 填充路径，~每 20ms） ====================

// 上调 fillPcm(positionFrames, frames, sampleRate, channels, byte[]) → 写入帧数；
// 桥/缓冲未就绪或数据落空返回 -1（调用方按策略回落）。分块：请求超
// PCM_BUF_MAX 时循环拉满。g_fill_mutex 持有下调用（无锁版本——锁由
// REPLACE 直调 / MIX 整段持有者统一负责）
static int upcall_fill_pcm_locked(int64_t position, int frames, int channels, uint8_t* out) {
    if (frames <= 0 || channels <= 0) return -1;
    pthread_mutex_lock(&g_bridge_mutex);
    jobject bridge = g_bridge;
    jmethodID mid = g_mid_fill_pcm;
    jobject jbufObj = g_pcm_jbuf;
    pthread_mutex_unlock(&g_bridge_mutex);
    if (bridge == nullptr || mid == nullptr || jbufObj == nullptr || g_vm == nullptr) return -1;
    jbyteArray jbuf = (jbyteArray) jbufObj;

    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return -1;

    int done = 0;
    while (done < frames) {
        int chunk = frames - done;
        int maxFramesChunk = (int) (PCM_BUF_MAX / (size_t) (channels * 2));
        if (chunk > maxFramesChunk) chunk = maxFramesChunk;
        jint n = env->CallIntMethod(bridge, mid, (jlong) (position + done), (jint) chunk,
                                    (jint) ASSUMED_RATE, (jint) channels, jbuf);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return done > 0 ? done : -1;
        }
        if (n <= 0) return done > 0 ? done : -1;  // 落空（未配置/未就绪）
        if (n > chunk) n = chunk;
        jboolean isCopy = JNI_FALSE;
        jbyte* elems = env->GetByteArrayElements(jbuf, &isCopy);
        if (elems == nullptr) return done > 0 ? done : -1;
        memcpy(out + (size_t) done * channels * 2, elems, (size_t) n * channels * 2);
        env->ReleaseByteArrayElements(jbuf, elems, JNI_ABORT);
        done += n;
        if (n < chunk) break;  // 源短于请求（尾段）：余下静音/不叠加
    }
    return done;
}

// REPLACE 直调入口（互斥包一层；MIX 走 session_fill 整段持锁 + _locked）
static int upcall_fill_pcm(int64_t position, int frames, int channels, uint8_t* out) {
    pthread_mutex_lock(&g_fill_mutex);
    int n = upcall_fill_pcm_locked(position, frames, channels, out);
    pthread_mutex_unlock(&g_fill_mutex);
    return n;
}

// ==================== 填充与 Buffer 合理性 ====================

// PCM 采集的帧字节数上界（16 声道 × 4B float；REMOTE_SUBMIX 常态立体声
// 16bit = 4B）。布局漂移防御：mSize 必须落在 [frameCount, frameCount×64]
// 区间且 raw 至少 4 字节对齐——Android ≤12 的旧 Buffer 布局（raw@0）会被
// 此校验拦下（读出的 mSize 是指针片段，量级不符）
static bool buffer_plausible(const ArBuffer* b) {
    if (b->frameCount == 0 || b->frameCount > 1'000'000) return false;
    if (b->mSize < b->frameCount || b->mSize > b->frameCount * 64) return false;
    if (b->raw == nullptr || ((uintptr_t) b->raw & 0x3) != 0) return false;
    return true;
}

// 会话填充（三态核心）：frameSize = mSize/frameCount 反推声道数
// （I16 交错假设：ch = frameSize/2，非整数帧尺寸回退 2 声道）。REPLACE
// 覆写 + 落空静音；MIX 相加 clamp + 落空原声。position 随真实帧节奏推进
static void session_fill(void* thiz, Session& s, const ArBuffer* b) {
    size_t frameSize = b->frameCount > 0 ? b->mSize / b->frameCount : 0;
    int channels = frameSize >= 2 && (frameSize & 1) == 0 ? (int) (frameSize / 2) : 2;

    bool filled = false;
    if (s.policy == POLICY_REPLACE) {
        int n = upcall_fill_pcm(s.position_frames, (int) b->frameCount, channels,
                                (uint8_t*) b->raw);
        if (n > 0) {
            // 源短于 chunk 的尾段清零（覆写语义：无数据处必须静音）
            size_t gotBytes = (size_t) n * channels * 2;
            if (gotBytes < b->mSize) memset((char*) b->raw + gotBytes, 0, b->mSize - gotBytes);
            filled = true;
        } else {
            memset(b->raw, 0, b->mSize);  // 落空静音（防泄漏铁律）
            filled = true;
        }
    } else if (s.policy == POLICY_MIX) {
        static uint8_t fake[PCM_BUF_MAX];  // g_fill_mutex 保护（多采集线程并发）
        // 整段持锁：fake 静态缓冲与 JNI 复用缓冲的读写都在临界区内
        // （上调用 _locked 无锁版本，防递归死锁）
        pthread_mutex_lock(&g_fill_mutex);
        size_t off = 0;
        int64_t pos = s.position_frames;
        while (off < b->mSize) {
            size_t remain = b->mSize - off;
            size_t chunk = remain > PCM_BUF_MAX ? PCM_BUF_MAX : remain;
            int frames = (int) (chunk / (size_t) (channels * 2));
            if (frames <= 0) break;
            int n = upcall_fill_pcm_locked(pos, frames, channels, fake);
            if (n <= 0) break;  // 落空：余下保持原声
            // I16 相加 clamp
            int16_t* dst = (int16_t*) ((char*) b->raw + off);
            const int16_t* src = (const int16_t*) fake;
            size_t samples = (size_t) n * channels;
            for (size_t i = 0; i < samples; i++) {
                int32_t v = (int32_t) dst[i] + (int32_t) src[i];
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                dst[i] = (int16_t) v;
            }
            off += samples * 2;
            pos += n;
            filled = true;
            if (n < frames) break;
        }
        pthread_mutex_unlock(&g_fill_mutex);
    }
    s.position_frames += (int64_t) b->frameCount;
    if (!filled) return;  // MIX 落空全程未触：不计填充
    if (!s.logged_first) {
        s.logged_first = true;
        upcall_log(ANDROID_LOG_INFO,
                   "E3c-N first fill (obj=%p, %zu frames / %zu bytes, policy=%d, ch=%d)",
                   thiz, b->frameCount, b->mSize, s.policy, channels);
    }
}

// 会话查询（热路径）：返回是否处于覆写态。miss → 懒登记（JNI 上调，
// 仅此处有上行开销）；OFF/过期 → TTL 重判
static bool session_active(void* thiz) {
    int64_t now = now_ms();
    {
        std::lock_guard<std::mutex> lk(g_sessions_mutex);
        auto it = g_sessions.find(thiz);
        if (it != g_sessions.end()) {
            Session& s = it->second;
            if (s.policy == POLICY_OFF) {
                if (now - s.queried_at_ms < SESSION_TTL_MS) return false;
            } else {
                // 活跃会话同样 TTL 重判：策略中途变更生效上限 60s，
                // 且兜底"实例销毁地址复用"的陈旧会话（stop 未覆盖的路径）
                if (now - s.queried_at_ms < SESSION_TTL_MS) return true;
            }
        }
    }
    // 判定在锁外做（JNI 上调可能耗时 ~秒级：冷启动配置有界等待）
    int policy = upcall_query_policy();
    int64_t queried_at = now_ms();
    std::lock_guard<std::mutex> lk(g_sessions_mutex);
    Session& s = g_sessions[thiz];
    bool was_active = s.policy == POLICY_REPLACE || s.policy == POLICY_MIX;
    s.policy = policy;
    s.queried_at_ms = queried_at;
    if (!was_active) {
        s.position_frames = 0;
        s.logged_first = false;
        s.warned_implausible = false;
    }
    upcall_log(ANDROID_LOG_INFO, "E3c-N capture registered (obj=%p, policy=%d)",
               thiz, policy);
    return policy == POLICY_REPLACE || policy == POLICY_MIX;
}

// 活跃会话填充入口（三态）：session_active 判活后锁内取 Session 引用
// 调 session_fill（map 结点稳定——erase 仅发生在 stop/懒登记覆写，而
// 两者与填充同线程或之后发生；懒登记覆写不清 position）
static void fill_if_active(void* thiz, const ArBuffer* b) {
    if (!session_active(thiz)) return;
    std::lock_guard<std::mutex> lk(g_sessions_mutex);
    auto it = g_sessions.find(thiz);
    if (it == g_sessions.end()) return;
    Session& s = it->second;
    if (s.policy == POLICY_OFF) return;
    if (!buffer_plausible(b)) {
        if (!s.warned_implausible) {
            s.warned_implausible = true;
            upcall_log(ANDROID_LOG_WARN,
                       "E3c-N implausible Buffer layout (fc=%zu, size=%zu, raw=%p) -> passthrough",
                       b->frameCount, b->mSize, b->raw);
        }
        return;
    }
    session_fill(thiz, s, b);
}

// ==================== hook 桩句柄（幂等重试） ====================

static void* g_stub_obtain_priv = nullptr;
static void* g_stub_obtain_pub = nullptr;
static void* g_stub_stop = nullptr;
static void* g_stub_pab = nullptr;
// v3：callback_fn 地址 hook（cb 描述结构首字段）
static void* g_stub_cbf = nullptr;
static void* g_orig_cbf = nullptr;

// 热重载让位标志：模块热更新后 LSPosed 把新实例注入本进程，但本实例
// （旧代码）仍持钩且配置推送桥已断——策略读取停留在旧值，继续填充 =
// 用陈旧策略污染产物（实测：用户已切原声，旧实例仍按上次同步的 MIX
// 叠加）。Kotlin 桥 watcher 检测到新实例 APK 映射后调 nativeDisable()：
// 本实例所有填充短路透传（不 unhook——摘钩有崩溃风险），音频回到原生
// 直到进程重启（新实例接管的正常路径）。fail-open 方向正确：宁可暂时
// 不替换，不可替换错内容
static std::atomic<bool> g_disabled{false};

// ==================== hook 代理 ====================

// 公有 obtainBuffer 去重守卫：公有版（waitCount 包装）内部转发私有版——
// AOSP 未内联时私有 hook 已填充，公有 hook 不得重复（REPLACE 双填充会
// 双倍推进假音频帧位）。thread_local "本调用已处理" 标记：私有 proxy
// 填充前置位，公有 proxy 调用 orig 前清零、返回后检查——同线程嵌套
// 转发必然命中，跨线程无假阳性
static thread_local bool tl_priv_handled = false;

// 汇聚腿：私有 obtainBuffer（read()/公有 obtain/回调线程三路 out-of-line
// 汇聚点——AOSP 常规构建的通用覆盖；ColorOS 上被 LTO 内联进 pab/公有版，
// 由 pab-cbf 腿与公有腿分别补位）
static int proxy_obtain_priv(void* thiz, void* buf, const struct timespec* requested,
                             struct timespec* elapsed, size_t* nonContig) {
    SHADOWHOOK_STACK_SCOPE();
    int status = SHADOWHOOK_CALL_PREV(proxy_obtain_priv, thiz, buf, requested, elapsed, nonContig);
    if (status == 0 && buf != nullptr && !g_disabled.load(std::memory_order_relaxed)) {
        // NO_ERROR 才有有效 chunk（错误路径 mSize=0）
        ArBuffer* b = (ArBuffer*) buf;
        if (b->mSize > 0) {
            tl_priv_handled = true;  // 公有转发链的去重标记（见声明注释）
            fill_if_active(thiz, b);
        }
    }
    return status;
}

// 汇聚腿（公有版，waitCount 包装）：TRANSFER_OBTAIN 直调路径。2aqwvd 轮
// 实证（2026-09-14）：ColorOS 录屏器的原生采集引擎数据出口不走
// read/pab/obtainPriv/AAudio 任何一个已挂符号（全零触发）但 stop() 正常
// 触发——排除法只剩公有 obtainBuffer（其内部对私有版的转发被 LTO 内联，
// 私有 hook 不可见）。与私有腿无条件并存 + 去重守卫（私有已填则跳过）
static int proxy_obtain_pub(void* thiz, void* buf, int32_t waitCount, size_t* nonContig) {
    SHADOWHOOK_STACK_SCOPE();
    // 私有腿在位时武装去重标记（内部转发若经私有 hook 会置位）
    if (g_stub_obtain_priv != nullptr) tl_priv_handled = false;
    int status = SHADOWHOOK_CALL_PREV(proxy_obtain_pub, thiz, buf, waitCount, nonContig);
    bool handled = tl_priv_handled;
    tl_priv_handled = false;
    if (status == 0 && buf != nullptr && !handled &&
        !g_disabled.load(std::memory_order_relaxed)) {
        ArBuffer* b = (ArBuffer*) buf;
        if (b->mSize > 0) fill_if_active(thiz, b);
    }
    return status;
}

// 会话边界：stop 清会话（复用实例下一会话首次数据出口重判策略，
// 对齐 Java 层 stop 腿语义）
static void proxy_stop(void* thiz) {
    SHADOWHOOK_STACK_SCOPE();
    SHADOWHOOK_CALL_PREV(proxy_stop, thiz);
    {
        std::lock_guard<std::mutex> lk(g_sessions_mutex);
        g_sessions.erase(thiz);
    }
    upcall_log(ANDROID_LOG_INFO, "E3c-N session cleared on stop (obj=%p)", thiz);
}

// ==================== v3：C 回调拦截（callback_t pre-call 三态填充） ====================

// pab 首调（AudioRecordThread 采集线程）时执行：obj+MCBF_OFF → cb 描述
// 结构 → fn = *(cb)（首字段函数指针）地址 hook。防御链：cb/fn 两级
// dladdr（垃圾指针挡在 hook 前）。回调描述结构是全局静态（跨录制会话
// 同址复用），一次 hook 进程内终身有效
static void proxy_cbf(int event, void* user, void* info);

static void try_hook_cbf(void* ar_obj) {
#if defined(__LP64__)
    void* cb = *(void**) ((char*) ar_obj + MCBF_OFF);
    if (cb == nullptr) {
        upcall_log(ANDROID_LOG_WARN, "E3c-N cb null at +%d (obj=%p) -> v3 skip", MCBF_OFF,
                   ar_obj);
        return;
    }
    Dl_info info;
    if (dladdr(cb, &info) == 0 || info.dli_fname == nullptr) {
        upcall_log(ANDROID_LOG_WARN, "E3c-N cb not in module (cb=%p) -> v3 skip", cb);
        return;
    }
    void* fn = *(void**) cb;
    if (fn == nullptr || dladdr(fn, &info) == 0 || info.dli_fname == nullptr) {
        upcall_log(ANDROID_LOG_WARN, "E3c-N cb fn invalid (cb=%p, fn=%p) -> v3 skip", cb, fn);
        return;
    }

    void* orig = nullptr;
    void* stub = shadowhook_hook_func_addr(fn, (void*) proxy_cbf, &orig);
    if (stub == nullptr) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        upcall_log(ANDROID_LOG_WARN, "E3c-N hook cbf failed: errno=%d %s", err,
                   msg != nullptr ? msg : "");
        return;
    }
    g_orig_cbf = orig;
    g_stub_cbf = stub;
    upcall_log(ANDROID_LOG_INFO, "E3c-N hooked cbf (cb=%p, fn=%p, owner=%p)", cb, fn, ar_obj);
#endif  // __LP64__
}

// callback_t(event, user, info)：event==EVENT_MORE_DATA(0) 时 info =
// AudioRecord::Buffer*（raw/mSize 由内联 obtainBuffer 已填好）——pre-call
// 三态填充后原回调消费的就是替换数据。防御：buffer_plausible 布局校验，
// 误判形态时零副作用
static void proxy_cbf(int event, void* user, void* info) {
    SHADOWHOOK_STACK_SCOPE();
    ArBuffer* b = (ArBuffer*) info;

    // pre-call 三态填充（仅 EVENT_MORE_DATA；会话键 = 最近 AudioRecord
    // 对象，pab 必先于 cbf 触发故非 null；打点由 fill_if_active/session_fill
    // 统一负责——first fill 日志含 policy）。热重载让位后透传（g_disabled）
    if (!g_disabled.load(std::memory_order_relaxed) &&
        event == CB_EVENT_MORE_DATA && b != nullptr && b->mSize > 0) {
        void* owner = g_last_ar_obj.load();
        if (owner != nullptr) fill_if_active(owner, b);
    }

    SHADOWHOOK_CALL_PREV(proxy_cbf, event, user, info);
}

// AOSP android15：nsecs_t AudioRecord::processAudioBuffer()——返回下次预期
// 唤醒时间（AudioRecordThread 据此 sleep）。bool 签名截断（返回 1ns）致
// 忙转，LP64 int64_t 转发修复
static int64_t proxy_pab(void* thiz) {
    SHADOWHOOK_STACK_SCOPE();
    int64_t ret = SHADOWHOOK_CALL_PREV(proxy_pab, thiz);
    // v3 会话键刷新：cbf 回调以最近 AudioRecord 对象为会话键（每次调用
    // 都刷，纳秒级原子写，采集线程自身无竞态）
    g_last_ar_obj.store(thiz, std::memory_order_relaxed);
    // 首调时安装 callback_fn hook（一次性；cb 构造期已就位，采集线程
    // 自身安装无竞态）
    if (!g_cbf_hook_tried.exchange(true)) try_hook_cbf(thiz);
    return ret;
}

// ==================== 安装（幂等，加载监听重试驱动） ====================

static std::atomic<int> g_sh_inited{0};

// 装配互斥：初始装配（installRecorderApp 线程）与加载监听重试（监听线程）
// 可能并发进入——SHARED 模式下同符号二次 hook 会成链（代理套代理重复
// 覆写），串行化 + 桩句柄幂等共同保证单次安装
static pthread_mutex_t g_install_mutex = PTHREAD_MUTEX_INITIALIZER;

static void* hook_sym(const char* lib, const char* sym, void* proxy, void** orig,
                      void** stub_slot, const char* label) {
    if (*stub_slot != nullptr) return *stub_slot;  // 已在位（幂等重入）
    void* stub = shadowhook_hook_sym_name(lib, sym, proxy, orig);
    int err = shadowhook_get_errno();
    if (stub == nullptr) {
        // NOT_FOUND = 库未加载（加载监听会重试）或符号不存在（OEM 改名）
        const char* msg = shadowhook_to_errmsg(err);
        upcall_log(ANDROID_LOG_INFO, "E3c-N hook %s failed: errno=%d %s", label, err,
                   msg != nullptr ? msg : "");
    } else if (err == SHADOWHOOK_ERRNO_PENDING) {
        // 库未加载：返回值是 pending task（非 NULL）而非生效桩——不占据
        // stub_slot，否则幂等检查会永久挡掉重试（dl 回调已随 linker
        // init 降级失效，pending task 永不完成，重试必须新建 task）
        upcall_log(ANDROID_LOG_INFO, "E3c-N hook %s pending (lib not loaded, watcher will retry)", label);
    } else {
        *stub_slot = stub;
        upcall_log(ANDROID_LOG_INFO, "E3c-N hooked %s (%s)", label, sym);
    }
    return stub;
}

static void refresh_bridge_ref(JNIEnv* env, jobject bridge) {
    pthread_mutex_lock(&g_bridge_mutex);
    jobject old = g_bridge;
    pthread_mutex_unlock(&g_bridge_mutex);
    if (old != nullptr) env->DeleteGlobalRef(old);

    jobject ref = env->NewGlobalRef(bridge);
    jclass cls = env->GetObjectClass(ref);
    jmethodID q = env->GetMethodID(cls, "queryPolicy", "()I");
    jmethodID nid = env->GetMethodID(cls, "noteVideoId", "()V");
    jmethodID f = env->GetMethodID(cls, "fillPcm", "(JIII[B)I");
    jmethodID l = env->GetMethodID(cls, "onLog", "(ILjava/lang/String;)V");
    // PCM 复用缓冲（全局引用；热路径免每次 NewByteArray）
    jbyteArray jbuf = env->NewByteArray((jsize) PCM_BUF_MAX);
    jobject jbufRef = jbuf != nullptr ? env->NewGlobalRef(jbuf) : nullptr;
    if (jbuf != nullptr) env->DeleteLocalRef(jbuf);
    env->DeleteLocalRef(cls);

    pthread_mutex_lock(&g_bridge_mutex);
    jobject oldBuf = g_pcm_jbuf;
    g_bridge = ref;
    g_mid_query_policy = q;
    g_mid_note_video_id = nid;
    g_mid_fill_pcm = f;
    g_mid_on_log = l;
    if (jbufRef != nullptr || oldBuf != nullptr) {
        if (oldBuf != nullptr) env->DeleteGlobalRef(oldBuf);
        g_pcm_jbuf = jbufRef;
    }
    pthread_mutex_unlock(&g_bridge_mutex);
}

// 安装核心（幂等，g_install_mutex 持有下调用）：ShadowHook 初始化 + 全量
// 符号 hook。由 nativeInstall 驱动（installRecorderApp 装配尾 + Kotlin
// 加载监听重试），桩句柄幂等保证同库单次安装
static void install_hooks_locked() {
    if (!g_sh_inited.load()) {
        int r = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
        if (r != 0) {
            // 2.0.1 so 未导出 shadowhook_get_init_errno（头文件声明与导出表
            // 不一致的已知差异），get_errno 在 init 失败后同线程取同一错误码
            const char* msg = shadowhook_to_errmsg(shadowhook_get_errno());
            upcall_log(ANDROID_LOG_WARN, "E3c-N shadowhook_init failed: %d (%s)", r,
                       msg != nullptr ? msg : "");
            return;
        }
        g_sh_inited.store(1);
    }

    static void* orig_obtain_priv = nullptr;
    static void* orig_obtain_pub = nullptr;
    static void* orig_stop = nullptr;
    static void* orig_pab = nullptr;

    // 汇聚腿：私有 obtainBuffer（read/公有 obtain/回调线程三路 out-of-line
    // 汇聚点——AOSP 常规构建的通用覆盖；ColorOS 被 LTO 内联但不影响在位）
    hook_sym(LIB_AUDIOCLIENT, SYM_OBTAIN_PRIV, (void*) proxy_obtain_priv,
             &orig_obtain_priv, &g_stub_obtain_priv, "obtainBuffer(private)");

    // 汇聚腿：公有 obtainBuffer（TRANSFER_OBTAIN 直调——ColorOS 原生采集
    // 引擎的数据出口，见 proxy_obtain_pub 注释）。无条件与私有腿并存，
    // 去重守卫防双填充
    hook_sym(LIB_AUDIOCLIENT, SYM_OBTAIN_PUB, (void*) proxy_obtain_pub,
             &orig_obtain_pub, &g_stub_obtain_pub, "obtainBuffer(public)");

    // 会话边界：stop 清会话
    hook_sym(LIB_AUDIOCLIENT, SYM_STOP, (void*) proxy_stop, &orig_stop,
             &g_stub_stop, "stop");

    // 主腿（v3）：processAudioBuffer——回调模式数据路径，首调安装
    // callback_fn hook + cbf 会话键刷新
    hook_sym(LIB_AUDIOCLIENT, SYM_PAB, (void*) proxy_pab, &orig_pab,
             &g_stub_pab, "processAudioBuffer");
}

// 安装摘要（进程一次）
static std::atomic<int> g_summary_logged{0};

static void log_install_summary() {
    if (g_summary_logged.exchange(1) != 0) return;
    upcall_log(ANDROID_LOG_INFO,
               "E3c-N installed (obtainPriv=%d, obtainPub=%d, stop=%d, pab=%d)",
               g_stub_obtain_priv != nullptr ? 1 : 0,
               g_stub_obtain_pub != nullptr ? 1 : 0,
               g_stub_stop != nullptr ? 1 : 0,
               g_stub_pab != nullptr ? 1 : 0);
}

// 热重载让位（Kotlin 桥 watcher 调用，一次性）：填充短路透传（见
// g_disabled 注释）
extern "C" JNIEXPORT void JNICALL
Java_fake_screenshot_hooks_AudioRecordNativeBridge_nativeDisable(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_disabled.exchange(true)) {
        upcall_log(ANDROID_LOG_WARN,
                   "E3c-N disabled: newer module instance hot-loaded into this process; "
                   "stale-policy fills stopped (passthrough until process restart)");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fake_screenshot_hooks_AudioRecordNativeBridge_nativeInstall(JNIEnv* env, jobject thiz) {
    if (g_vm == nullptr) {
        // JNI_OnLoad 未被调用（本路径理论不可达：OnLoad 在 dlopen 期必然
        // 执行；保留为装载链路 tripwire——静默 false 会致双腿加载成功
        // 却零日志，难以为继）
        __android_log_print(ANDROID_LOG_WARN, "SF",
                            "E3c-N nativeInstall refused: g_vm==null (JNI_OnLoad not exported/run)");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_install_mutex);

    // 热重载：新代桥实例接管上调（旧 classloader 的 HookContext 已注销
    // listener，旧实例引用必须换掉，否则策略读取停留在旧配置）
    refresh_bridge_ref(env, thiz);

    install_hooks_locked();
    log_install_summary();

    jboolean core = g_stub_obtain_priv != nullptr ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_install_mutex);
    return core;
}

// ==================== 库加载 ====================

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
