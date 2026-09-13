// E3c-N：native 层音频采集拦截（libaudioclient AudioRecord 数据出口替换）。
//
// 背景（真机实证链 2026-09-13，ColorOS 15 / com.oplus.screenrecorder）：
// - Java 层 E3c 12 个钩子（ctor×5/build/start×2/read×4 + deoptimize）全天
//   零触发；产物带 AAC 音轨、audioserver AudioBoost boost 录屏器 3 条采集
//   线程 → Java AudioRecord 类从未实例化，录屏器走 C++ AudioRecord。
// - 探针轮（41zaym）：录屏器进程加载 libaudioclient.so（及 libaaudio 全家
//   桶），出现 "AudioRecord"/"AudioRecorder"/"COUIAudioWorkHa" 线程——
//   "AudioRecord" 线程是 AudioRecordThread::run() 的命名（AOSP set() 中
//   mCallback 非空时创建），即 C++ AudioRecord 回调/OBTAIN 模式实锤。
//
// 架构（AOSP android15-release 源码验证）：
// - **私有 obtainBuffer(Buffer*, const timespec*, timespec*, size_t*) 是
//   全部数据出口的汇聚点**：read()（TRANSFER_SYNC，read 循环直接调用它）、
//   公有 obtainBuffer API（TRANSFER_OBTAIN，经 waitCount 包装转发）、
//   processAudioBuffer()（TRANSFER_CALLBACK/OBTAIN 的 "AudioRecord" 内部
//   线程）三条路径全部经过它 → 一处 hook 覆盖所有读取形态（对齐 Java 层
//   native_read_in_* 汇聚点的设计思想）。
// - **post-call 内容替换，非阻断**：真实数据管线（audioserver → 共享环
//   缓冲）原样运转，obtainBuffer 返回后对 [raw, raw+mSize) 就地覆写。
//   产出节奏完全原生（虚拟时钟 pacing 问题天然消失——这是相对 Java 层
//   阻断式替换的架构优势）。Buffer.mSize = frameCount × mServerFrameSize
//   由 obtainBuffer 自己写入（字节量精确、与客户端格式无关；read() 的
//   格式转换从 raw 拷贝，覆写后的零值经 memcpy_by_audio_format 转换仍
//   是零值 = 静音）。
// - obtainBuffer 返回的 chunk 是客户端独占区（直到 releaseBuffer 归还），
//   覆写与 audioserver 生产无竞争；填充在 orig 返回后、proxy 返回前完成
//   ——与消费者同线程，无并发窗口。
//
// 策略（对齐 Java 层 E3c 语义）：
// - 首次 obtainBuffer（成功）懒登记：JNI 上调 Kotlin 解析三态策略
//   （recordAudioPolicy，独立于画面替换配置——用户三态选择解耦铁律）。
//   OFF → TTL 内放行（不重复上调）；MUTE/REPLACE → 会话内持续覆写。
// - v1 填充一律静音（REPLACE 数据源管线后续落地；Java 层同为"替换落空
//   回落静音"——显式选择替换后放行真实音频 = 泄漏）。
// - stop() hook 清会话（录屏会话边界，对齐 Java stop 腿）；TTL 60s 重判
//   （兜底：实例销毁地址复用导致会话陈旧、策略中途变更）。
// - fail-safe：Buffer 布局合理性校验（OEM 布局漂移防御）失败 → 放行 +
//   一次性 WARN（可观测的 fail-open，绝不盲目覆写未知内存）。
//
// 符号（NDK shim 编译实证，LP64/ILP32 的 size_t 差异两套）：
// - 主：_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_P{m,j}
// - 备：_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEiP{m,j}（waitCount
//   包装版，私有符号缺失时兜底——只覆盖 OBTAIN 路径）
// - 诊断：_ZN7android11AudioRecord4readEPvm{m,j}b（私有 obtainBuffer 在位时
//   纯打点；不在位时 post-call 零填 app 缓冲作降级替换）
// - 诊断+兜底：libaaudio 三件套（openStream 流属性捕获 / read 打点+MMAP
//   兜底 / close 清理）。AAudio 采集形态与覆盖关系：legacy read 与 legacy
//   callback 内部都走 AudioRecord → obtainBuffer 已覆盖；MMAP blocking read
//   绕过 AudioRecord → read 腿兜底填充（仅当私有 obtainBuffer 不在位，
//   单一填充归属防双重覆写）；MMAP callback（AAudio 内部线程直调 app 回调）
//   v1 不覆盖——openStream 打点输入流属性 + read 计数为零即其指纹，
//   v2 采纳 CamSwap 的 setDataCallback wrap 蓝本
// - 会话：_ZN7android11AudioRecord4stopEv + AAudioStream_close
// - gn0h3h 诊断：start 三候选 + processAudioBuffer + AudioRecordThread::
//   threadLoop（回调模式数据路径定位，纯打点，见「gn0h3h 诊断腿」注释块）
//
// ==================== peo05t 实证 + v2 回调拦截（onMoreData） ====================
// peo05t 真机日志（21:33:42 全 hook 在位，pab=1）：
// - 21:33:48.755 processAudioBuffer 首调（obj=0xb400007c60c59900）→ 内联
//   实锤：数据消费在 pab 内部完成，out-of-line obtainBuffer/read 符号零调用
// - 21:33:48.757 对象扫描 [+0=libaudioclient.so+0xfc870(vtable)] [+144=
//   libpermission.so+0x12370]——两轮日志（gn0h3h/peo05t）+144 偏移一致且
//   指向值稳定 = AudioRecord::mCbf（sp<IAudioRecordCallback>），指向
//   libpermission.so RELRO 段的全局回调对象（r--p 0x10000-0x13000，
//   .data.rel.ro；两轮同址 = 全局静态对象跨录制会话复用）
// - v2 路线：pab 首调时读 mCbf → 解引用得 vtable → dump 槽位 → 对
//   vtable[6]（onMoreData）shadowhook_hook_func_addr 挂地址 → proxy 内
//   pre-call 静音填充（Buffer.raw/mSize 由内联 obtainBuffer 已填好）
// - 槽位依据（Itanium ABI，x86_64 GCC 与 arm64 NDK clang 双实验一致，
//   /tmp/shim2/shim6.cpp 地址比较 + no-pie vtable 静态解析）：
//   IAudioRecordCallback : public virtual RefBase 的 vtable（address point 起）：
//     slot 0,1 = D1/D0 析构；slot 2-5 = RefBase 虚基类函数
//     （onFirstRef/onLastStrongRef/onIncStrongAttempted/onLastWeakRef）；
//     slot 6 = onMoreData(const AudioRecord::Buffer&)；
//     slot 7-10 = onOverrun/onMarker/onNewPos/onNewIAudioRecord
//   （gn0h3h 轮总结的 slot5 系模拟 RefBase 缺 onIncStrongAttempted/
//    onLastWeakRef 两函数所致；真实布局以本次双架构实验为准）
// - 布局漂移防御：vtable 全槽位 dump 入日志 + buffer_plausible 校验 +
//   仅 slot6 单点 hook（误挂低频槽位时 buffer 参数为垃圾 → 校验拦截）
// - onMoreData 返回 consumed bytes（0 = 全消费），转发原值不改语义
// - proxy_pab 返回类型修正：AOSP 为 nsecs_t（下次唤醒时间），旧 bool
//   签名截断返回值 → AudioRecordThread sleep 1ns 忙转（peo05t heartbeat
//   ~73µs/次 vs 正常 ~20ms/次实锤）
//
// 热路径开销：trampoline + status 检查 + map 查找 + memset（~每 20ms/流），
// 无 JNI（策略上调只在会话懒登记/TTL 时发生，attach 不 detach——AudioRecord
// 采集线程可能是 ART 已附着线程，detach 会破坏 ART 线程归属）。

#include <jni.h>
#include <shadowhook.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/types.h>
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
#define SYM_READ "_ZN7android11AudioRecord4readEPvmb"
#else
#define SYM_OBTAIN_PRIV "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEPK8timespecPS3_Pj"
#define SYM_OBTAIN_PUB "_ZN7android11AudioRecord12obtainBufferEPNS0_6BufferEiPj"
#define SYM_READ "_ZN7android11AudioRecord4readEPvjb"
#endif
#define SYM_STOP "_ZN7android11AudioRecord4stopEv"
// gn0h3h 轮诊断符号：stop 外部调用腿触发但 obtainBuffer/read 零触发
// （TRANSFER_CALLBACK 模式：app 只调 start/stop，数据消费在 AudioRecordThread
// 内部循环；obtainBuffer 疑被 LTO 内联进 processAudioBuffer）。start 三候选
// 覆盖 AOSP 历代签名形态（AudioSystem::sync_event_t / android::sync_event_t /
// legacy int,int——ABI 层等价，仅符号名差异，按序试装首个成功者）
#define SYM_START_ASYS "_ZN7android11AudioRecord5startENS_11AudioSystem12sync_event_tEi"
#define SYM_START_SYNC "_ZN7android11AudioRecord5startENS_12sync_event_tEi"
#define SYM_START_INT "_ZN7android11AudioRecord5startEii"
#define SYM_PAB "_ZN7android11AudioRecord18processAudioBufferEv"
#define SYM_TL "_ZN7android17AudioRecordThread10threadLoopEv"
#define LIB_AUDIOCLIENT "libaudioclient.so"
#define LIB_AAUDIO "libaaudio.so"
#define LIB_MEDIANDK "libmediandk.so"

// v2 回调拦截（peo05t 实证，见文件头「v2 回调拦截」注释块）：
// - MCBF_OFF：LP64 AudioRecord 对象内 mCbf（sp<IAudioRecordCallback>）
//   的偏移，两轮真机日志实证 +144
// - VT_SLOT_ON_MORE：IAudioRecordCallback vtable（address point 起）中
//   onMoreData 的槽位——Itanium ABI，x86_64 GCC 与 arm64 clang 双实验一致
#if defined(__LP64__)
#define MCBF_OFF 144
#define VT_SLOT_ON_MORE 6
#define VT_DUMP_SLOTS 10  // dump slot 0..10（全虚函数覆盖）
#else
// 32 位不支持 v2（try_hook_onmore 主体已 #if 挡下，宏值仅为可编译占位）
#define MCBF_OFF 0
#define VT_SLOT_ON_MORE 0
#define VT_DUMP_SLOTS 0
#endif

// ==================== 策略常量（对齐 HookConfig 三态） ====================

enum {
    POLICY_OFF = 0,
    POLICY_MUTE = 1,
    POLICY_REPLACE = 2,
};

// 会话 TTL 重判（对齐 Java 层 IGNORE_TTL_MS：OFF→MUTE/REPLACE 切换与
// 实例地址复用陈旧会话的刷新上限）
static constexpr int64_t SESSION_TTL_MS = 60'000;

// ==================== JNI 桥缓存 ====================

static JavaVM* g_vm = nullptr;
static jobject g_bridge = nullptr;            // AudioRecordNativeBridge 单例（全局引用）
static jmethodID g_mid_query_policy = nullptr;
static jmethodID g_mid_on_log = nullptr;

static pthread_mutex_t g_bridge_mutex = PTHREAD_MUTEX_INITIALIZER;

// ==================== 会话表 ====================

struct Session {
    int policy;               // 0=放行 1=静音 2=替换（v1 均静音填充）
    int64_t queried_at_ms;    // 策略判定时刻（TTL 重判基准）
    int64_t fill_count;       // 覆写次数（日志限流）
    int64_t filled_bytes;     // 累计覆写字节（周期性观测）
    bool logged_first;        // 首次填充已打点
    bool warned_implausible;  // 布局异常已告警（每会话一次）
};

static std::mutex g_sessions_mutex;
static std::map<void*, Session> g_sessions;

// v2：mCbf 回调对象 → owner AudioRecord 映射（onMoreData 会话键回溯，
// hook 安装时登记 / stop 清理；定义提前——proxy_stop 先于 v2 段使用）
static std::mutex g_mcbf_mutex;
static std::map<void*, void*> g_mcbf_owner;

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
    if (policy < POLICY_OFF || policy > POLICY_REPLACE) return POLICY_OFF;
    return (int) policy;
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

// 会话填充：v1 MUTE/REPLACE 均静音覆写（REPLACE 数据源管线后续接入）
static void session_fill(void* thiz, Session& s, const ArBuffer* b) {
    memset(b->raw, 0, b->mSize);
    s.fill_count++;
    s.filled_bytes += (int64_t) b->mSize;
    if (!s.logged_first) {
        s.logged_first = true;
        upcall_log(ANDROID_LOG_INFO,
                   "E3c-N first obtain fill (obj=%p, %zu frames / %zu bytes, policy=%d)",
                   thiz, b->frameCount, b->mSize, s.policy);
    } else if ((s.fill_count & 0xFFF) == 0) {  // 每 4096 次一条心跳（~80s@20ms）
        upcall_log(ANDROID_LOG_INFO, "E3c-N fill heartbeat (obj=%p, n=%lld, bytes=%lld)",
                   thiz, (long long) s.fill_count, (long long) s.filled_bytes);
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
    bool was_active = s.policy == POLICY_MUTE || s.policy == POLICY_REPLACE;
    s.policy = policy;
    s.queried_at_ms = queried_at;
    if (!was_active) {
        s.fill_count = 0;
        s.filled_bytes = 0;
        s.logged_first = false;
        s.warned_implausible = false;
    }
    upcall_log(ANDROID_LOG_INFO, "E3c-N capture registered (obj=%p, policy=%d%s)",
               thiz, policy,
               policy == POLICY_REPLACE ? ", source pending -> silence" : "");
    return policy == POLICY_MUTE || policy == POLICY_REPLACE;
}

// ==================== hook 桩句柄（幂等重试 + 降级判定） ====================

static void* g_stub_obtain_priv = nullptr;
static void* g_stub_obtain_pub = nullptr;
static void* g_stub_read = nullptr;
static void* g_stub_stop = nullptr;
static void* g_stub_start = nullptr;
static void* g_stub_pab = nullptr;
static void* g_stub_tl = nullptr;
static void* g_stub_amc_queue = nullptr;
static void* g_stub_aaudio_open = nullptr;
static void* g_stub_aaudio_read = nullptr;
static void* g_stub_aaudio_close = nullptr;
// v2：onMoreData 地址 hook（mCbf 对象 vtable[6]）
static void* g_stub_onmore = nullptr;
static void* g_orig_onmore = nullptr;
static void* g_last_vt = nullptr;  // 已 hook 的 vtable 地址（复用观测）

// ==================== 诊断辅助（gn0h3h 轮：数据路径定位） ====================

// 返回地址 → 调用方库名（1 帧回溯，dladdr 解析；__builtin_return_address
// 自动处理 ARM64 PAC）。stop/start 等外部调用腿的调用方揭示录屏器音频
// 框架的承载库（自有 lib / libaudioclientextimpl / libaudioclient 直调）
static void log_caller_lib(const char* tag, int prio) {
    void* ra = __builtin_return_address(0);
    Dl_info info;
    if (ra != nullptr && dladdr(ra, &info) != 0 && info.dli_fname != nullptr) {
        const char* slash = strrchr(info.dli_fname, '/');
        upcall_log(prio, "E3c-N %s caller: %s+0x%lx", tag,
                   slash != nullptr ? slash + 1 : info.dli_fname,
                   (unsigned long) ((char*) ra - (char*) info.dli_fbase));
    } else {
        upcall_log(prio, "E3c-N %s caller: <unresolved %p>", tag, ra);
    }
}

// 对象内代码指针扫描（只读，每对象一次）：枚举对象前 448B 的 8 字节对齐
// 指针，dladdr 能解析到 so 模块的值打日志——AudioRecord::mCbf（app 回调
// 函数指针，指向录屏器自身代码/OPLUS 扩展库）由此显形，为 v2 回调 wrap
// （CamSwap setDataCallback 蓝本）定位目标。数据指针（堆地址）dladdr 必败
// 自然过滤；AudioRecord 非 polymorphic（无 vtable 噪音）
static std::mutex g_dumped_mutex;
static std::map<void*, int> g_dumped;

static void dump_object_code_ptrs(const char* tag, void* obj) {
    {
        std::lock_guard<std::mutex> lk(g_dumped_mutex);
        if (g_dumped.count(obj) != 0) return;
        g_dumped[obj] = 1;
    }
    char line[512];
    int pos = snprintf(line, sizeof(line), "E3c-N %s object scan (obj=%p):", tag, obj);
    int entries = 0;
    for (size_t off = 0; off < 448 && entries < 8; off += sizeof(void*)) {
        void* p = *(void**) ((char*) obj + off);
        if (p == nullptr) continue;
        Dl_info info;
        if (dladdr(p, &info) == 0 || info.dli_fname == nullptr) continue;  // 堆/无效
        const char* slash = strrchr(info.dli_fname, '/');
        const char* base = slash != nullptr ? slash + 1 : info.dli_fname;
        int n = snprintf(line + pos, sizeof(line) - (size_t) pos, " [+%zu=%s+0x%lx]",
                         off, base, (unsigned long) ((char*) p - (char*) info.dli_fbase));
        if (n <= 0 || (size_t) (pos + n) >= sizeof(line) - 1) break;
        pos += n;
        entries++;
    }
    upcall_log(ANDROID_LOG_INFO, "%s%s", line, entries == 0 ? " (no code ptrs)" : "");
}

// ==================== hook 代理 ====================

// 主 hook：私有 obtainBuffer（read/公有 obtain/回调线程三路汇聚点）
static int proxy_obtain_priv(void* thiz, void* buf, const struct timespec* requested,
                             struct timespec* elapsed, size_t* nonContig) {
    SHADOWHOOK_STACK_SCOPE();
    int status = SHADOWHOOK_CALL_PREV(proxy_obtain_priv, thiz, buf, requested, elapsed, nonContig);
    if (status == 0 && buf != nullptr) {  // NO_ERROR 才有有效 chunk（错误路径 mSize=0）
        ArBuffer* b = (ArBuffer*) buf;
        if (b->mSize > 0 && session_active(thiz)) {
            if (buffer_plausible(b)) {
                std::lock_guard<std::mutex> lk(g_sessions_mutex);
                auto it = g_sessions.find(thiz);
                if (it != g_sessions.end()) session_fill(thiz, it->second, b);
            } else {
                std::lock_guard<std::mutex> lk(g_sessions_mutex);
                auto it = g_sessions.find(thiz);
                if (it != g_sessions.end() && !it->second.warned_implausible) {
                    it->second.warned_implausible = true;
                    upcall_log(ANDROID_LOG_WARN,
                               "E3c-N implausible Buffer layout (obj=%p, fc=%zu, size=%zu, raw=%p) -> passthrough",
                               thiz, b->frameCount, b->mSize, b->raw);
                }
            }
        }
    }
    return status;
}

// 备用：公有 obtainBuffer（waitCount 包装）。私有符号缺失时才安装；
// 覆盖 TRANSFER_OBTAIN 直调路径（read/回调不经过它）
static int proxy_obtain_pub(void* thiz, void* buf, int32_t waitCount, size_t* nonContig) {
    SHADOWHOOK_STACK_SCOPE();
    int status = SHADOWHOOK_CALL_PREV(proxy_obtain_pub, thiz, buf, waitCount, nonContig);
    if (status == 0 && buf != nullptr) {
        ArBuffer* b = (ArBuffer*) buf;
        if (b->mSize > 0 && session_active(thiz) && buffer_plausible(b)) {
            std::lock_guard<std::mutex> lk(g_sessions_mutex);
            auto it = g_sessions.find(thiz);
            if (it != g_sessions.end()) session_fill(thiz, it->second, b);
        }
    }
    return status;
}

// 诊断 + 降级：read（私有 obtainBuffer 在位时纯打点——read 内部经私有
// obtainBuffer 已被覆写，post-call 再填只是同值覆写；不在位时（私有符号
// hook 失败的兜底场景）零填 app 缓冲——ret 即实读字节数，量精确）
static std::atomic<int64_t> g_read_calls{0};

static ssize_t proxy_read(void* thiz, void* buffer, size_t size, bool blocking) {
    SHADOWHOOK_STACK_SCOPE();
    ssize_t ret = SHADOWHOOK_CALL_PREV(proxy_read, thiz, buffer, size, blocking);
    int64_t n = g_read_calls.fetch_add(1) + 1;
    if (n == 1) {
        upcall_log(ANDROID_LOG_INFO, "E3c-N read() observed (obj=%p, size=%zu, blocking=%d, ret=%zd)",
                   thiz, size, (int) blocking, ret);
    }
    if (ret > 0 && buffer != nullptr && g_stub_obtain_priv == nullptr &&
        session_active(thiz)) {
        memset(buffer, 0, (size_t) ret);
    }
    return ret;
}

// 会话边界：stop 清会话（复用实例下一会话首次 obtain 重判策略，
// 对齐 Java 层 stop 腿语义）。gn0h3h 轮起附 caller 回溯：揭示录屏器
// 音频框架的承载库（自有 lib / libaudioclientextimpl / 直调）；
// v2 起同步清 mCbf→owner 映射（onMoreData 会话键回溯链失效防陈旧）
static void proxy_stop(void* thiz) {
    SHADOWHOOK_STACK_SCOPE();
    log_caller_lib("stop", ANDROID_LOG_INFO);
    SHADOWHOOK_CALL_PREV(proxy_stop, thiz);
    {
        std::lock_guard<std::mutex> lk(g_sessions_mutex);
        g_sessions.erase(thiz);
    }
    {
        std::lock_guard<std::mutex> lk(g_mcbf_mutex);
        for (auto it = g_mcbf_owner.begin(); it != g_mcbf_owner.end();) {
            if (it->second == thiz) it = g_mcbf_owner.erase(it);
            else ++it;
        }
    }
    upcall_log(ANDROID_LOG_INFO, "E3c-N session cleared on stop (obj=%p)", thiz);
}

// ==================== gn0h3h 诊断腿（数据路径定位） ====================
//
// gn0h3h 真机日志（21:05:48 全 hook 在位 → 21:05:54 "AudioRecord" 线程
// 出现（AudioRecordThread::run 命名，回调/OBTAIN 模式指纹）→ 录制 ~8s →
// stop×3（同一对象，teardown 级联）→ obtainBuffer/read 全程零调用）：
// 数据消费不经 libaudioclient 的 out-of-line obtainBuffer/read 符号
// → obtainBuffer 被 LTO 内联进 processAudioBuffer 的最大嫌疑。本组
// 代理纯打点定位（无覆写——回调模式下数据在 processAudioBuffer 内部
// 经 mCbf 直达 app 回调，v2 按对象扫描出的 mCbf 地址 hook_func_addr
// 包一层做 pre-callback 静音，CamSwap setDataCallback 蓝本）：
// - start 三候选：session 级触发（低频全记）+ caller 回溯 + 对象扫描
// - processAudioBuffer：内联嫌疑本体——本 hook 触发而 obtainBuffer 零
//   触发 = 内联实锤
// - AudioRecordThread::threadLoop：虚函数经 vtable 调用同样命中 inline
//   hook（patch 在函数入口），回调循环在线指纹

static int proxy_start(void* thiz, int event, int triggerSession) {
    SHADOWHOOK_STACK_SCOPE();
    int status = SHADOWHOOK_CALL_PREV(proxy_start, thiz, event, triggerSession);
    upcall_log(ANDROID_LOG_INFO,
               "E3c-N start() observed (obj=%p, event=%d, trigger=%d, status=%d)",
               thiz, event, triggerSession, status);
    log_caller_lib("start", ANDROID_LOG_INFO);
    dump_object_code_ptrs("start", thiz);
    return status;
}

static std::atomic<int64_t> g_pab_calls{0};

// ==================== v2：mCbf 回调拦截（onMoreData pre-call 静音） ====================
// 映射表 g_mcbf_owner/g_mcbf_mutex 见「会话表」段（定义提前）

// vtable 槽位 dump（每 vtable 一次）：dladdr 解析各槽位函数指针的归属库，
// 布局漂移（OPLUS 定制回调类多继承/重排）由此暴露于日志
static void dump_vtable_slots(void* cb, void** vt) {
    char line[768];
    int pos = snprintf(line, sizeof(line), "E3c-N mCbf vtable scan (cb=%p, vt=%p):", cb,
                       (void*) vt);
    for (int i = 0; i <= VT_DUMP_SLOTS; i++) {
        void* f = vt[i];
        if (f == nullptr) continue;
        Dl_info info;
        int n;
        if (dladdr(f, &info) != 0 && info.dli_fname != nullptr) {
            const char* slash = strrchr(info.dli_fname, '/');
            const char* base = slash != nullptr ? slash + 1 : info.dli_fname;
            n = snprintf(line + pos, sizeof(line) - (size_t) pos, " [%d=%s+0x%lx%s]", i, base,
                         (unsigned long) ((char*) f - (char*) info.dli_fbase),
                         i == VT_SLOT_ON_MORE ? "(hook)" : "");
        } else {
            n = snprintf(line + pos, sizeof(line) - (size_t) pos, " [%d=%p]", i, f);
        }
        if (n <= 0 || (size_t) (pos + n) >= sizeof(line) - 1) break;
        pos += n;
    }
    upcall_log(ANDROID_LOG_INFO, "%s", line);
}

// pab 首调（AudioRecordThread 采集线程）时执行：mCbf → vtable → slot6 地址
// hook。防御链：cb/vt/target 三级 dladdr（垃圾指针挡在解引用前）；
// g_stub_onmore 幂等（全局静态回调对象跨会话复用，vtable 地址不变——
// 变化时 WARN 观测，v2 扩展点）
static size_t proxy_onmore(void* cbThis, const void* buffer);

static void try_hook_onmore(void* ar_obj) {
#if defined(__LP64__)
    if (g_stub_onmore != nullptr) {
        void* cb = *(void**) ((char*) ar_obj + MCBF_OFF);
        if (cb != nullptr) {
            void** vt = *(void***) cb;
            if (vt != g_last_vt && vt != nullptr) {
                Dl_info info;
                if (dladdr(vt, &info) != 0 && info.dli_fname != nullptr) {
                    upcall_log(ANDROID_LOG_WARN,
                               "E3c-N mCbf vtable changed (obj=%p, vt=%p != %p) -> onMoreData "
                               "hook may miss this session",
                               ar_obj, (void*) vt, g_last_vt);
                }
            }
        }
        return;
    }

    void* cb = *(void**) ((char*) ar_obj + MCBF_OFF);
    if (cb == nullptr) {
        upcall_log(ANDROID_LOG_WARN, "E3c-N mCbf null at +%d (obj=%p) -> v2 skip", MCBF_OFF,
                   ar_obj);
        return;
    }
    Dl_info info;
    if (dladdr(cb, &info) == 0 || info.dli_fname == nullptr) {
        // 非 so 映射内的地址（堆回调对象）——v2 依赖全局对象假设，堆对象时
        // vtable 解引用仍可行（对象第一字段必为 vptr），不挡，继续
        upcall_log(ANDROID_LOG_INFO, "E3c-N mCbf not in module (cb=%p, heap?)", cb);
    }
    void** vt = *(void***) cb;
    if (vt == nullptr || dladdr(vt, &info) == 0 || info.dli_fname == nullptr) {
        upcall_log(ANDROID_LOG_WARN, "E3c-N mCbf vtable unresolved (cb=%p, vt=%p) -> v2 skip",
                   cb, (void*) vt);
        return;
    }
    dump_vtable_slots(cb, vt);

    void* target = vt[VT_SLOT_ON_MORE];
    if (target == nullptr || dladdr(target, &info) == 0 || info.dli_fname == nullptr) {
        upcall_log(ANDROID_LOG_WARN, "E3c-N onMoreData slot %d invalid (vt=%p) -> v2 skip",
                   VT_SLOT_ON_MORE, (void*) vt);
        return;
    }
    void* orig = nullptr;
    void* stub = shadowhook_hook_func_addr(target, (void*) proxy_onmore, &orig);
    if (stub == nullptr) {
        int err = shadowhook_get_errno();
        const char* msg = shadowhook_to_errmsg(err);
        upcall_log(ANDROID_LOG_WARN, "E3c-N hook onMoreData failed: errno=%d %s", err,
                   msg != nullptr ? msg : "");
        return;
    }
    g_orig_onmore = orig;
    g_stub_onmore = stub;
    g_last_vt = vt;
    {
        std::lock_guard<std::mutex> lk(g_mcbf_mutex);
        g_mcbf_owner[cb] = ar_obj;
    }
    upcall_log(ANDROID_LOG_INFO, "E3c-N hooked onMoreData (cb=%p, owner=%p, target=%p)",
               cb, ar_obj, target);
#endif  // __LP64__
}

static std::atomic<int64_t> g_onmore_calls{0};
static std::atomic<int64_t> g_onmore_fills{0};

// onMoreData(this=mCbf 对象, const AudioRecord::Buffer&)：pre-call 静音——
// processAudioBuffer 内联 obtainBuffer 已把 chunk 就绪，此处覆写后原回调
// 消费的就是静音数据。返回 consumed bytes 转发原值（0=全消费语义不变）
static size_t proxy_onmore(void* cbThis, const void* buffer) {
    SHADOWHOOK_STACK_SCOPE();
    ArBuffer* b = (ArBuffer*) buffer;

    void* owner = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_mcbf_mutex);
        auto it = g_mcbf_owner.find(cbThis);
        if (it != g_mcbf_owner.end()) owner = it->second;
    }

    int64_t n = g_onmore_calls.fetch_add(1) + 1;
    if (n == 1) {
        upcall_log(ANDROID_LOG_INFO,
                   "E3c-N onMoreData first call (cb=%p, owner=%p, fc=%zu, size=%zu, raw=%p)",
                   cbThis, owner, b != nullptr ? b->frameCount : 0,
                   b != nullptr ? b->mSize : 0, b != nullptr ? b->raw : nullptr);
    } else if ((n & 0x3FFF) == 0) {  // 每 16384 次一条心跳（~5min@20ms）
        upcall_log(ANDROID_LOG_INFO, "E3c-N onMoreData heartbeat (n=%lld, fills=%lld)",
                   (long long) n, (long long) g_onmore_fills.load());
    }

    // pre-call 静音填充（owner miss 时以 cbThis 为会话键兜底——同 proxy
    // 可能拦截到非本路径登记的回调对象，如多录制器共享 slot6 函数）
    void* session_key = owner != nullptr ? owner : cbThis;
    if (b != nullptr && b->mSize > 0 && session_active(session_key) &&
        buffer_plausible(b)) {
        memset(b->raw, 0, b->mSize);
        int64_t f = g_onmore_fills.fetch_add(1) + 1;
        if (f == 1) {
            upcall_log(ANDROID_LOG_INFO,
                       "E3c-N first onMoreData fill (cb=%p, owner=%p, %zu frames / %zu bytes)",
                       cbThis, owner, b->frameCount, b->mSize);
        } else if ((f & 0x3FFF) == 0) {
            upcall_log(ANDROID_LOG_INFO, "E3c-N onMoreData fill heartbeat (n=%lld)",
                       (long long) f);
        }
    }

    size_t ret = SHADOWHOOK_CALL_PREV(proxy_onmore, cbThis, buffer);
    return ret;
}

// AOSP android15：nsecs_t AudioRecord::processAudioBuffer()——返回下次预期
// 唤醒时间（AudioRecordThread 据此 sleep）。bool 签名截断（返回 1ns）致
// 忙转（peo05t heartbeat ~73µs/次实锤），LP64 int64_t 转发修复
static int64_t proxy_pab(void* thiz) {
    SHADOWHOOK_STACK_SCOPE();
    int64_t ret = SHADOWHOOK_CALL_PREV(proxy_pab, thiz);
    int64_t n = g_pab_calls.fetch_add(1) + 1;
    if (n == 1) {
        upcall_log(ANDROID_LOG_INFO, "E3c-N processAudioBuffer first call (obj=%p, ret=%lld ns)",
                   thiz, (long long) ret);
        dump_object_code_ptrs("pab", thiz);
        // v2：数据路径实锤处（pab 触发而 obtainBuffer/read 零调用），就地
        // 解析 mCbf vtable 并挂 onMoreData——采集线程自身安装，无竞态
        try_hook_onmore(thiz);
    } else if ((n & 0xFFF) == 0) {  // 每 4096 次一条心跳（~80s@20ms）
        upcall_log(ANDROID_LOG_INFO, "E3c-N processAudioBuffer heartbeat (n=%lld)",
                   (long long) n);
    }
    return ret;
}

static std::atomic<int64_t> g_tl_calls{0};

static bool proxy_tl(void* thiz) {
    SHADOWHOOK_STACK_SCOPE();
    bool ret = SHADOWHOOK_CALL_PREV(proxy_tl, thiz);
    int64_t n = g_tl_calls.fetch_add(1) + 1;
    if (n == 1) {
        upcall_log(ANDROID_LOG_INFO, "E3c-N AudioRecordThread::threadLoop first call (obj=%p)",
                   thiz);
    } else if ((n & 0xFFF) == 0) {
        upcall_log(ANDROID_LOG_INFO, "E3c-N threadLoop heartbeat (n=%lld)", (long long) n);
    }
    return ret;
}

// ==================== AAudio 腿（流属性诊断 + MMAP 兜底，CamSwap 蓝本） ====================
//
// AAudio 采集形态与覆盖关系（见文件头）：legacy 两形态由 obtainBuffer 覆盖；
// MMAP blocking read 由本腿兜底（obtainBuffer 缺位时）；MMAP callback 是
// v1 缺口（诊断指纹：openStream 打点输入流 + read 计数为零）。
//
// 流属性获取（CamSwap hook_aaudio.cpp 模式）：openStream post-call 用
// dlsym 查询函数（getDirection/getSampleRate/getChannelCount/getFormat，
// 不 hook）读流属性缓存 stream map；close 清理。安全回退（对齐 CamSwap）：
// channelCount≤0→按 1、format 未知→按 I16 推算帧字节数——静音填充下
// 低估字节数只会欠填（不越界），高估才危险而回退方向恒为低估
//
// 会话键：AAudioStream* 与 AudioRecord* 共用 g_sessions（void* 键，
// 活对象地址不冲突）；close 清 aaudio 会话（对齐 stop 语义）。

typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

enum { AAUDIO_DIRECTION_INPUT = 1, AAUDIO_FORMAT_PCM_I16 = 1, AAUDIO_FORMAT_PCM_FLOAT = 2 };

typedef int32_t (*fn_aaudio_query)(AAudioStream*);

struct AStreamInfo {
    bool isInput;
    int32_t sampleRate;
    int32_t channelCount;
    int32_t format;   // 0 = 未知（按 I16 推算）
};

static fn_aaudio_query g_q_direction = nullptr;
static fn_aaudio_query g_q_sample_rate = nullptr;
static fn_aaudio_query g_q_channel_count = nullptr;
static fn_aaudio_query g_q_format = nullptr;

static std::mutex g_astreams_mutex;
static std::map<AAudioStream*, AStreamInfo> g_astreams;

static std::atomic<int64_t> g_aaudio_reads{0};

static void resolve_aaudio_queries() {
    // dlopen 已加载库只加引用计数；handle 不 dlclose——查询函数需长期可用
    void* h = dlopen(LIB_AAUDIO, RTLD_NOW);
    if (h == nullptr) return;
    g_q_direction = (fn_aaudio_query) dlsym(h, "AAudioStream_getDirection");
    g_q_sample_rate = (fn_aaudio_query) dlsym(h, "AAudioStream_getSampleRate");
    g_q_channel_count = (fn_aaudio_query) dlsym(h, "AAudioStream_getChannelCount");
    g_q_format = (fn_aaudio_query) dlsym(h, "AAudioStream_getFormat");
}

// 静音填充的帧字节数（低估方向安全）
static int32_t aaudio_frame_bytes(const AStreamInfo& info) {
    int32_t ch = info.channelCount > 0 ? info.channelCount : 1;
    int32_t bps = (info.format == AAUDIO_FORMAT_PCM_FLOAT) ? 4 : 2;
    return ch * bps;
}

static int32_t proxy_aaudio_open_stream(void* builder, void** streamOut) {
    SHADOWHOOK_STACK_SCOPE();
    int32_t ret = SHADOWHOOK_CALL_PREV(proxy_aaudio_open_stream, builder, streamOut);
    if (ret == 0 && streamOut != nullptr && *streamOut != nullptr) {
        AAudioStream* s = (AAudioStream*) *streamOut;
        AStreamInfo info{};
        if (g_q_direction != nullptr)
            info.isInput = g_q_direction(s) == AAUDIO_DIRECTION_INPUT;
        if (g_q_sample_rate != nullptr) info.sampleRate = g_q_sample_rate(s);
        if (g_q_channel_count != nullptr) info.channelCount = g_q_channel_count(s);
        if (g_q_format != nullptr) info.format = g_q_format(s);
        {
            std::lock_guard<std::mutex> lk(g_astreams_mutex);
            g_astreams[s] = info;
        }
        if (info.isInput) {
            upcall_log(ANDROID_LOG_INFO,
                       "E3c-N AAudio input stream opened (stream=%p, rate=%d, ch=%d, fmt=%d)",
                       s, info.sampleRate, info.channelCount, info.format);
        }
    }
    return ret;
}

static int32_t proxy_aaudio_read(void* stream, void* buffer, int32_t numFrames,
                                 int64_t timeoutNanos) {
    SHADOWHOOK_STACK_SCOPE();
    int32_t ret = SHADOWHOOK_CALL_PREV(proxy_aaudio_read, stream, buffer, numFrames, timeoutNanos);
    int64_t n = g_aaudio_reads.fetch_add(1) + 1;
    AStreamInfo info{};
    bool known = false;
    {
        std::lock_guard<std::mutex> lk(g_astreams_mutex);
        auto it = g_astreams.find((AAudioStream*) stream);
        if (it != g_astreams.end()) {
            info = it->second;
            known = true;
        }
    }
    if (n == 1 || (n & 0xFFFF) == 0) {
        upcall_log(ANDROID_LOG_INFO,
                   "E3c-N AAudioStream_read observed (n=%lld, frames=%d, ret=%d, input=%d%s)",
                   (long long) n, numFrames, ret, known && info.isInput ? 1 : 0,
                   known ? "" : ", stream unknown");
    }
    // MMAP 兜底：输入流 + 私有 obtainBuffer 不在位 + 会话活跃（MUTE/REPLACE）
    // → 静音填充。obtainBuffer 在位时本腿纯诊断（legacy 数据已在汇聚点覆写，
    // 单一填充归属；v2 REPLACE 数据源下双重填充会双倍推进播放位置，规则同源）
    if (ret > 0 && buffer != nullptr && known && info.isInput &&
        g_stub_obtain_priv == nullptr && session_active(stream)) {
        memset(buffer, 0, (size_t) ret * (size_t) aaudio_frame_bytes(info));
    }
    return ret;
}

static int32_t proxy_aaudio_close(void* stream) {
    SHADOWHOOK_STACK_SCOPE();
    int32_t ret = SHADOWHOOK_CALL_PREV(proxy_aaudio_close, stream);
    {
        std::lock_guard<std::mutex> lk(g_astreams_mutex);
        g_astreams.erase((AAudioStream*) stream);
    }
    {
        std::lock_guard<std::mutex> lk(g_sessions_mutex);
        g_sessions.erase(stream);
    }
    return ret;
}

// ==================== 安装（幂等，探针重试驱动） ====================

static std::atomic<int> g_sh_inited{0};

// 装配互斥：初始装配（installRecorderApp 线程）与探针重试（探针线程）
// 可能并发进入——SHARED 模式下同符号二次 hook 会成链（代理套代理重复
// 覆写/重复计数），串行化 + 桩句柄幂等共同保证单次安装
static pthread_mutex_t g_install_mutex = PTHREAD_MUTEX_INITIALIZER;

static void* hook_sym(const char* lib, const char* sym, void* proxy, void** orig,
                      void** stub_slot, const char* label) {
    if (*stub_slot != nullptr) return *stub_slot;  // 已在位（幂等重入）
    void* stub = shadowhook_hook_sym_name(lib, sym, proxy, orig);
    int err = shadowhook_get_errno();
    if (stub == nullptr) {
        // NOT_FOUND = 库未加载（探针见到 so 加载会重试）或符号不存在
        // （OEM 改名——read=0 诊断腿会给出全量 dump 定位）
        const char* msg = shadowhook_to_errmsg(err);
        upcall_log(ANDROID_LOG_INFO, "E3c-N hook %s failed: errno=%d %s", label, err,
                   msg != nullptr ? msg : "");
    } else if (err == SHADOWHOOK_ERRNO_PENDING) {
        // 库未加载：返回值是 pending task（非 NULL）而非生效桩——不占据
        // stub_slot，否则幂等检查会永久挡掉探针重试（dl 回调已随 linker
        // init 降级失效，pending task 永不完成，重试必须新建 task）
        upcall_log(ANDROID_LOG_INFO, "E3c-N hook %s pending (lib not loaded, probe will retry)", label);
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
    jmethodID l = env->GetMethodID(cls, "onLog", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);

    pthread_mutex_lock(&g_bridge_mutex);
    g_bridge = ref;
    g_mid_query_policy = q;
    g_mid_on_log = l;
    pthread_mutex_unlock(&g_bridge_mutex);
}

// 安装核心（幂等，g_install_mutex 持有下调用）：ShadowHook 初始化 + 全量
// 符号 hook。由 JNI_OnLoad（自装）与 nativeInstall（探针重试）双驱动，
// 桩句柄幂等保证同库单次安装
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
    static void* orig_read = nullptr;
    static void* orig_stop = nullptr;
    static void* orig_start = nullptr;
    static void* orig_pab = nullptr;
    static void* orig_tl = nullptr;
    static void* orig_aaudio_open = nullptr;
    static void* orig_aaudio_read = nullptr;
    static void* orig_aaudio_close = nullptr;

    // 主 hook 优先：私有 obtainBuffer（三路汇聚点）
    hook_sym(LIB_AUDIOCLIENT, SYM_OBTAIN_PRIV, (void*) proxy_obtain_priv,
             &orig_obtain_priv, &g_stub_obtain_priv, "obtainBuffer(private)");

    // 私有符号缺失 → 公有包装版兜底（只覆盖 OBTAIN 直调；read/回调线程
    // 由 read 诊断腿的降级填充兜底）
    if (g_stub_obtain_priv == nullptr) {
        hook_sym(LIB_AUDIOCLIENT, SYM_OBTAIN_PUB, (void*) proxy_obtain_pub,
                 &orig_obtain_pub, &g_stub_obtain_pub, "obtainBuffer(public)");
    }

    hook_sym(LIB_AUDIOCLIENT, SYM_READ, (void*) proxy_read, &orig_read,
             &g_stub_read, "read");
    hook_sym(LIB_AUDIOCLIENT, SYM_STOP, (void*) proxy_stop, &orig_stop,
             &g_stub_stop, "stop");

    // gn0h3h 诊断腿：start 三候选（ABI 等价，首个成功者胜出；全败 = OEM
    // 签名漂移，start 打点缺席但 pab/tl 仍独立有效）+ processAudioBuffer +
    // AudioRecordThread::threadLoop（见「gn0h3h 诊断腿」注释块）
    if (g_stub_start == nullptr) {
        hook_sym(LIB_AUDIOCLIENT, SYM_START_ASYS, (void*) proxy_start,
                 &orig_start, &g_stub_start, "start(asys)");
    }
    if (g_stub_start == nullptr) {
        hook_sym(LIB_AUDIOCLIENT, SYM_START_SYNC, (void*) proxy_start,
                 &orig_start, &g_stub_start, "start(sync)");
    }
    if (g_stub_start == nullptr) {
        hook_sym(LIB_AUDIOCLIENT, SYM_START_INT, (void*) proxy_start,
                 &orig_start, &g_stub_start, "start(int)");
    }
    hook_sym(LIB_AUDIOCLIENT, SYM_PAB, (void*) proxy_pab, &orig_pab,
             &g_stub_pab, "processAudioBuffer");
    hook_sym(LIB_AUDIOCLIENT, SYM_TL, (void*) proxy_tl, &orig_tl,
             &g_stub_tl, "AudioRecordThread::threadLoop");

    // AAudio 腿：查询函数 dlsym 解析（openStream post-call 读取流属性）+
    // openStream/read/close 三 hook（libaaudio 未加载则 NOT_FOUND，探针重试）
    resolve_aaudio_queries();
    hook_sym(LIB_AAUDIO, "AAudioStreamBuilder_openStream", (void*) proxy_aaudio_open_stream,
             &orig_aaudio_open, &g_stub_aaudio_open, "AAudioStreamBuilder_openStream");
    hook_sym(LIB_AAUDIO, "AAudioStream_read", (void*) proxy_aaudio_read,
             &orig_aaudio_read, &g_stub_aaudio_read, "AAudioStream_read");
    hook_sym(LIB_AAUDIO, "AAudioStream_close", (void*) proxy_aaudio_close,
             &orig_aaudio_close, &g_stub_aaudio_close, "AAudioStream_close");
}

// 安装摘要（每库一次；首驱动方标注来源——onLoad=JNI_OnLoad 自装 /
// install=nativeInstall 路径，热重载诊断指纹）
static std::atomic<int> g_summary_logged{0};

static void log_install_summary(const char* via) {
    if (g_summary_logged.exchange(1) != 0) return;
    upcall_log(ANDROID_LOG_INFO,
               "E3c-N installed (via=%s, obtainPriv=%d, obtainPub=%d, read=%d, stop=%d, "
               "start=%d, pab=%d, tl=%d, onmore=%d, aaudioOpen=%d, aaudioRead=%d, aaudioClose=%d)",
               via,
               g_stub_obtain_priv != nullptr ? 1 : 0, g_stub_obtain_pub != nullptr ? 1 : 0,
               g_stub_read != nullptr ? 1 : 0, g_stub_stop != nullptr ? 1 : 0,
               g_stub_start != nullptr ? 1 : 0, g_stub_pab != nullptr ? 1 : 0,
               g_stub_tl != nullptr ? 1 : 0, g_stub_onmore != nullptr ? 1 : 0,
               g_stub_aaudio_open != nullptr ? 1 : 0, g_stub_aaudio_read != nullptr ? 1 : 0,
               g_stub_aaudio_close != nullptr ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fake_screenshot_hooks_AudioRecordNativeBridge_nativeInstall(JNIEnv* env, jobject thiz) {
    if (g_vm == nullptr) {
        // JNI_OnLoad 未被调用（本路径理论不可达：OnLoad 在 dlopen 期必然
        // 执行；保留为装载链路 tripwire——lqxfdf 轮此路径静默 false 曾致
        // 双腿加载成功却零日志，难以为继）
        __android_log_print(ANDROID_LOG_WARN, "SF",
                            "E3c-N nativeInstall refused: g_vm==null (JNI_OnLoad not exported/run)");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_install_mutex);

    // 热重载：新代桥实例接管上调（旧 classloader 的 HookContext 已注销
    // listener，旧实例引用必须换掉，否则策略读取停留在旧配置）
    refresh_bridge_ref(env, thiz);

    install_hooks_locked();
    log_install_summary("install");

    jboolean core = g_stub_obtain_priv != nullptr ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_install_mutex);
    return core;
}

// ==================== 库加载 ====================

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
