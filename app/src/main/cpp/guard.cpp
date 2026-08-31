// 注入检测雷管（libmemsys.so——中性化库名，maps 可见；源码内称 guard）
//
// 威胁：root 取证者向 app 进程注入（Frida attach/spawn、LSPosed 定向 hook、
// Substrate/SandHook 等）或用 GG 修改器类扫描器扫取内存（均需 ptrace
// attach）以窃取门禁密码或 DK。Java 层检测可被同层 hook 拦截（一行
// hook 让检测函数恒返回"干净"），因此检测与引爆均在 native：
// - 自主 watchdog 线程：不依赖任何 Java 调用驱动，Java 层被完全接管仍工作
// - 引爆动作：覆写（零填充+fsync）并 unlink 密文文件 → SIGKILL 自身。
//   SIGKILL 不可被任何信号 handler/注入代码拦截；密文先于进程死亡被销毁
// - Java 侧主动检查入口（nativeCheck）：命中时由 Java 走完整销毁序列
//   （含 Keystore 条目删除与 daemon 停止，比 native 单删文件更彻底），
//   native 引爆是 Java 层被拦截时的兜底
//
// 反扫描（GG 修改器类）：
// - 扫描器必须 ptrace attach → TracerPid 快轮询（1s）2s 内引爆；
//   GG 是交互式工具（会话持续数分钟），必被捕获
// - PR_SET_DUMPABLE=0：非 root 攻击者（Shizuku 级）从此无法 attach，
//   而不只是"被检测"
// - 根本边界（诚实声明）：root 无需 attach 即可经 /proc/pid/mem、
//   process_vm_readv 静默直读（TracerPid 恒 0），内核无任何"内存被读"
//   通知机制，原理上不可检测——唯一缓解是 Java 侧自动锁定缩小 DK 驻留
//
// 自完整性校验（反 inline patch，见"自完整性校验"节）：
// - 本库 .text 与磁盘基准逐字节比对，抓"改已有代码"（maps 黑名单
//   只抓"注入新库"）——单发 POKE patch 掉常量时间比较即全绕过门禁，
//   这是必须封堵的一行攻击路径
//
// 反硬件断点内核外挂（内核态裸写 DBGBCR/DBGWVR hook 用户态函数）：
// - 无法阻止也无法检测寄存器被写（用户态读不到调试寄存器）；占用
//   断点资源（perf_event_open）对裸写寄存器的内核外挂无效
// - 对策是"断点资源耗尽"策略：关键比较提供两个结构不同的独立实现
//   （ct_eq_byte/ct_eq_word），验证方要求两者结果一致——每个关键点
//   必须同时挂两个断点，ARM64 有限的断点资源（典型 4-6 个）被成倍
//   消耗（见"常量时间比较"节）
// - canary 哨兵自检（watchdog 周期）：随机数据验证比较函数语义
//   （相同→true/不同→false，双实现一致）——hook 成恒真/恒假/
//   结果反转均被抓。精确 hook（识别调用来源选择性撒谎）抓不住，
//   但需要 hook 代码做栈回溯甄别，成本数量级提升
//
// 误报控制（引爆 = 用户数据销毁，代价极高，规则保守）：
// - 黑名单只列注入框架特征库名；刻意排除 magisk/zygisk/riru/xposed 等
//   root 框架名——它们由 zygote preload，出现在所有进程的 maps 中，
//   列入会导致所有 root 用户被误杀
// - "gum" 单独是误报源（webview 的 libgumbo），必须用完整 "gum-js"
// - crash 时 debuggerd/tombstoned 会 ptrace attach：三重白名单防误杀——
//   ①tracer 进程名白名单（debuggerd/tombstoned）②本进程崩溃信号标记
//   （崩溃后 30s 内的 attach 一律放过，覆盖未知名的 OEM 崩溃收集器）
//   ③连续 2 轮（2s）确认（`debuggerd -b` 等瞬态合法 attach 活不过确认期）
// - maps/status 读取失败：跳过本轮（怪异 ROM 不得误杀），不视为命中
// - debug build：Java 侧不调用 nativeInit，watchdog 不启动
//
// 静默性：无任何日志输出，strip-all 去符号。

#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <csignal>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <cinttypes>
#include <ctime>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

// ===================== 配置 =====================

// TracerPid 快轮询间隔（秒）——扫描器（GG 类）与注入器均在 attach 后
// 持续操作，2s 确认窗口足够命中且容忍瞬态合法 attach
static const int kTracerPollSec = 1;
// maps 黑名单扫描间隔（轮询计数）：maps 文件较大，维持 10s 一次
static const int kMapsEveryNPolls = 10;
// 引爆确认所需连续命中轮数
static const int kConfirmRounds = 2;
// 崩溃标记有效期（秒）：覆盖 crash 收集全程（debuggerd dump 约 2-10s）
static const int kCrashWindowSec = 30;

// 可执行映射路径黑名单（子串匹配，大小写敏感）
// 全部为注入框架专属名，正常 app/系统库不含
static const char* kBlacklist[] = {
        "frida",       // frida-agent / frida-gadget（改名 gadget 的变体覆盖不到，接受）
        "gum-js",      // frida gum JS 引擎（勿用 "gum"：撞 webview 的 libgumbo）
        "linjector",   // frida 轻量注入器
        "libdobby",    // inline hook 框架（LSPosed 生态常用）
        "liblsplant",  // Java 方法 hook 核心（本进程被任何模块 hook 即出现）
        "libsubstrate",// SaurikSubstrate
        "libsandhook", // SandHook
        "libepic",     // Epic hook 框架
};

// ===================== 状态（init 后只读） =====================

static std::vector<std::string> g_target_files;  // 覆写+删除的密文文件
static std::string g_target_dir;                 // 递归清空的目录（datastore）
static volatile bool g_watchdog_running = false;

// ===================== 检测 =====================

// 崩溃标记：崩溃信号 handler 置位（debuggerd 只在崩溃后 attach）。
// ASYNC 语义：flag 与时间戳均为 sig_atomic/字长，读写无撕裂。
// 有效期窗口：ART 可恢复故障（隐式空指针检查）也会触发 SIGSEGV 并被
// handler 恢复——时间窗保证 flag 不会永久关闭 tracer 检测
static volatile sig_atomic_t g_crash_flag = 0;
static volatile time_t g_crash_time = 0;

static bool in_crash_window() {
    if (!g_crash_flag) return false;
    time_t now = time(nullptr);
    time_t t = g_crash_time;
    return (now >= t) && (now - t) < kCrashWindowSec;
}

// 读 /proc/self/status 的 TracerPid。0 = 无 tracer；-1 = 读取失败
// （不视为命中，防误杀）
static int read_tracer_pid() {
    FILE* f = fopen("/proc/self/status", "re");
    if (!f) return -1;
    char line[512];
    int pid = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            pid = (int) strtol(line + 10, nullptr, 10);
            break;
        }
    }
    fclose(f);
    return pid;
}

// tracer 进程名白名单：系统崩溃收集器（crash 时合法 attach）。
// /proc/<pid>/stat 全局可读（comm 字段不受 ptrace 限制）。
// root 攻击者可把扫描进程改名为 debuggerd 绕过——但其本可静默直读
// （已知边界），此白名单不为对抗 root 而设，只为不误杀
static bool tracer_whitelisted(int pid) {
    if (pid <= 0) return false;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    FILE* f = fopen(path, "re");
    if (!f) return false;  // 读不到：交给崩溃窗口与确认轮数兜底
    char buf[1024];
    bool ok = fgets(buf, sizeof(buf), f) != nullptr;
    fclose(f);
    if (!ok) return false;
    char* close = strrchr(buf, ')');
    if (!close) return false;
    char* open = nullptr;
    for (char* p = close; p > buf; p--) {
        if (*p == '(') { open = p; break; }
    }
    if (!open) return false;
    *close = '\0';
    const char* comm = open + 1;
    return strncmp(comm, "debuggerd", 9) == 0 ||
           strncmp(comm, "tombstoned", 10) == 0;
}

// /proc/self/maps 扫描：可执行映射路径命中黑名单
// 解析不依赖地址段长度（perms 定位到首个空格后第 3 字符），路径取 " /" 之后
static bool scan_maps() {
    FILE* f = fopen("/proc/self/maps", "re");
    if (!f) return false;
    char line[4096];
    bool bad = false;
    while (!bad && fgets(line, sizeof(line), f)) {
        char* sp = strchr(line, ' ');
        if (!sp || sp[3] != 'x') continue;  // perms 4 字符：r/x 位于 sp[1]、sp[3]
        char* path = strstr(sp, " /");
        if (!path) continue;                 // 匿名映射无路径
        path += 1;                           // 指向 '/'
        for (const char* pat : kBlacklist) {
            if (strstr(path, pat) != nullptr) {
                bad = true;
                break;
            }
        }
    }
    fclose(f);
    return bad;
}

// ===================== 自完整性校验（反 inline patch）=====================
//
// 威胁：ptrace POKE 单发 patch 本库 .text（如把 nativeConstantTimeEquals
// 改为恒真）——maps 黑名单只抓"注入新库"，抓不住"改已有代码"，这是
// 一行绕过门禁的真实路径。
//
// 防御：watchdog 周期性把本库全部可执行映射与磁盘 .so 文件逐字节比对
// （基准是加载来源文件，绕过必须同时 patch 内存与磁盘并留下持久痕迹）。
//
// 无状态设计：每次校验重新解析 maps + 重新 open 文件——.data/.bss 中
// 不存放可被单独抹除的区域清单。armed 布防标志仍是可写内存（已知
// 边界：冻结 + 单点改标志 = 关闭校验，但该成本与直接 patch .text 等
// 同，均在"冻结会话"已接受边界内）。
//
// 诚实边界（无外部锚点）：攻击者在进程启动前 patch 磁盘 .so（或
// bind-mount 覆盖路径）→ 内存与基准同为篡改版，校验自洽而不可见。
// 游戏反作弊靠内核驱动/服务器做锚点；本项目无此基础设施，此校验的
// 定位是抬高成本（留下持久化磁盘痕迹 + 需逆向 stripped 库），不是
// 绝对防御。启动前的完整 APK/签名校验是另一层（独立于本机制）。
//
// 返回：1 = 检测到篡改（内容不一致）；0 = 校验通过；
//      -1 = 无法校验（maps/文件不可读——怪异 ROM 不得误杀）

struct TextRegion {
    uintptr_t start;
    uintptr_t end;
    off_t off;
    char path[256];
};

static volatile sig_atomic_t g_integrity_state = 0;  // 0=未探测 1=已布防 2=不可用

// 收集本库全部可执行文件映射（路径含 "/libmemsys.so"——库名与 CMake
// OUTPUT_NAME / System.loadLibrary("memsys") 三处同步，改名必须全链路一致，
// 否则自完整性校验静默失效）
static int collect_self_text_regions(TextRegion* out, int max) {
    FILE* f = fopen("/proc/self/maps", "re");
    if (!f) return -1;
    char line[4096];
    int n = 0;
    while (fgets(line, sizeof(line), f)) {
        char* sp = strchr(line, ' ');
        if (!sp || sp[1] != 'r' || sp[3] != 'x') continue;  // 仅 r-xp/r-xs
        // 路径 = 行内首个 '/'（perms/offset/dev/inode 字段不含 '/'）
        char* path = strchr(line, '/');
        if (!path) continue;                                 // 匿名映射
        size_t plen = strlen(path);
        if (plen > 0 && path[plen - 1] == '\n') path[plen - 1] = '\0';
        if (strstr(path, "/libmemsys.so") == nullptr) continue;
        uintptr_t s = 0, e = 0;
        uintptr_t o = 0;
        char perms[8] = {0};
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %7s %" SCNxPTR,
                   &s, &e, perms, &o) != 4) continue;
        if (n >= max) break;
        if (strlen(path) >= sizeof(out[n].path)) continue;
        out[n].start = s;
        out[n].end = e;
        out[n].off = (off_t) o;
        strcpy(out[n].path, path);
        n++;
    }
    fclose(f);
    return n;
}

// 逐字节比对内存与文件（volatile 读阻止编译器优化为 memcmp——
// memcmp 是可被 hook 的库调用；显式 noinline 防 LTO 内联后重排序）
__attribute__((noinline))
static bool mem_equals_file(const volatile unsigned char* mem, int fd, off_t off, size_t len) {
    unsigned char buf[4096];
    size_t done = 0;
    while (done < len) {
        size_t chunk = len - done < sizeof(buf) ? len - done : sizeof(buf);
        ssize_t r = pread(fd, buf, (size_t) chunk, off + (off_t) done);
        if (r != (ssize_t) chunk) return false;  // 短读=文件不覆盖映射区间
        unsigned char diff = 0;
        for (size_t i = 0; i < chunk; i++) {
            diff |= (unsigned char) (mem[done + i] ^ buf[i]);
        }
        if (diff != 0) return false;
        done += chunk;
    }
    return true;
}

static int check_self_integrity() {
    TextRegion regions[16];
    int n = collect_self_text_regions(regions, 16);
    if (n <= 0) return -1;
    for (int i = 0; i < n; i++) {
        int fd = open(regions[i].path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) return -1;
        bool ok = mem_equals_file(
                (const volatile unsigned char*) regions[i].start,
                fd, regions[i].off,
                (size_t) (regions[i].end - regions[i].start)
        );
        close(fd);
        if (!ok) return 1;
    }
    return 0;
}

// ===================== 引爆 =====================

// 覆写（零填充 + fsync）后删除单个文件。覆写失败仍尝试 unlink：
// 文件系统层面不可恢复优先，尽力而为
static void shred_file(const char* path) {
    struct stat st;
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) return;
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd >= 0) {
        char buf[4096];
        memset(buf, 0, sizeof(buf));
        long long left = (long long) st.st_size;
        while (left > 0) {
            size_t chunk = left < (long long) sizeof(buf) ? (size_t) left : sizeof(buf);
            ssize_t n = write(fd, buf, chunk);
            if (n <= 0) break;
            left -= n;
        }
        fsync(fd);
        close(fd);
    }
    unlink(path);
}

// 递归清空目录内容（目录本身保留，避免 DataStore 路径判定的额外痕迹）
static void shred_dir(const char* path, int depth) {
    if (depth > 8) return;  // 防异常深链/环
    DIR* d = opendir(path);
    if (!d) return;
    struct dirent* e;
    while ((e = readdir(d)) != nullptr) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        char full[4096];
        if (snprintf(full, sizeof(full), "%s/%s", path, e->d_name) >= (int) sizeof(full)) continue;
        struct stat st;
        if (stat(full, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            shred_dir(full, depth + 1);
        } else {
            shred_file(full);
        }
    }
    closedir(d);
}

// 引爆：密文覆写销毁 → SIGKILL（不可拦截）。全程无 Java 调用
static void detonate() {
    for (const std::string& p : g_target_files) {
        shred_file(p.c_str());
    }
    if (!g_target_dir.empty()) {
        shred_dir(g_target_dir.c_str(), 0);
    }
    kill(getpid(), SIGKILL);
    _exit(0);  // SIGKILL 兜底
}

// ===================== 崩溃信号链 =====================

// 快轮询下防 crash 误杀：崩溃信号先于 debuggerd attach 到达，置位标记
// 后链回原 handler（保留 ART 故障管理器行为——可恢复故障由其恢复）。
// handler 只做置位+调用（async-signal-safe）
static const int kCrashSignals[] = {SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL};
static const size_t kCrashSignalCount = sizeof(kCrashSignals) / sizeof(kCrashSignals[0]);
static struct sigaction g_old_actions[8];

static int crash_signal_index(int sig) {
    for (size_t i = 0; i < kCrashSignalCount; i++) {
        if (kCrashSignals[i] == sig) return (int) i;
    }
    return -1;
}

static void guard_crash_handler(int sig, siginfo_t* info, void* ctx) {
    g_crash_time = time(nullptr);
    g_crash_flag = 1;
    int idx = crash_signal_index(sig);
    if (idx < 0) return;
    struct sigaction* old = &g_old_actions[idx];
    if (old->sa_flags & SA_SIGINFO) {
        old->sa_sigaction(sig, info, ctx);
    } else if (old->sa_handler != nullptr &&
               old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
    } else {
        // 无原 handler：恢复默认并重发（进程按默认路径终止）
        signal(sig, SIG_DFL);
        raise(sig);
    }
}

static void install_crash_chain() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = guard_crash_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    for (size_t i = 0; i < kCrashSignalCount; i++) {
        memset(&g_old_actions[i], 0, sizeof(struct sigaction));
        if (sigaction(kCrashSignals[i], nullptr, &g_old_actions[i]) == 0) {
            sigaction(kCrashSignals[i], &sa, nullptr);
        }
    }
}

// ===================== 自主 watchdog 线程 =====================

// 前向声明（定义在文件尾"常量时间比较"节，先于此使用）
static bool canary_check();

static void* watchdog_main(void*) {
    int tracerHits = 0;
    int tick = 0;
    usleep(500 * 1000);  // 首检：启动后 0.5s（冷启动注入是主攻击场景）
    while (g_watchdog_running) {
        if (tick % kMapsEveryNPolls == 0) {
            // maps 命中 = 注入库已驻留本进程（非瞬态，不存在 crash 误报源）
            // → 立即引爆
            if (scan_maps()) {
                detonate();
            }
            // canary 哨兵：比较函数语义被 hook（恒真/恒假/反转/双实现分歧）
            // → 引爆（反硬件断点 hook 的检测面）
            if (!canary_check()) {
                detonate();
            }
            // 自完整性：内存 .text ≠ 磁盘基准 = inline patch = 引爆。
            // 布防语义：首验通过（0）才布防；首验不可校验（-1，怪异 ROM）
            // 则该特性永久禁用——绝不因环境问题误杀；已布防后转为
            // 不可校验（文件被换/截断）同样按篡改处理
            int integ = check_self_integrity();
            if (integ == 1) {
                detonate();
            } else if (integ == 0) {
                if (g_integrity_state == 0) g_integrity_state = 1;
            } else {
                if (g_integrity_state == 1) detonate();
                if (g_integrity_state == 0) g_integrity_state = 2;
            }
        }
        // tracer 检测（1s 快轮询）：三重白名单外的持续 attach = 攻击
        // （GG 类扫描器/注入器会话持续数分钟，2s 确认必命中；
        //   `debuggerd -b` 等瞬态合法 attach 活不过确认期）
        int tracer = read_tracer_pid();
        if (tracer > 0 && !in_crash_window() && !tracer_whitelisted(tracer)) {
            if (++tracerHits >= kConfirmRounds) {
                detonate();
            }
        } else {
            tracerHits = 0;
        }
        tick++;
        sleep(kTracerPollSec);
    }
    return nullptr;
}

// ===================== JNI 入口 =====================

extern "C" JNIEXPORT void JNICALL
Java_fake_screenshot_defense_GuardManager_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jobjectArray files, jstring dir) {
    if (g_watchdog_running) return;  // 幂等：多入口（Application/Activity）重复 init
    jsize n = env->GetArrayLength(files);
    for (jsize i = 0; i < n; i++) {
        auto* s = (jstring) env->GetObjectArrayElement(files, i);
        if (!s) continue;
        const char* cs = env->GetStringUTFChars(s, nullptr);
        if (cs) {
            g_target_files.emplace_back(cs);
            env->ReleaseStringUTFChars(s, cs);
        }
        env->DeleteLocalRef(s);
    }
    if (dir != nullptr) {
        const char* cs = env->GetStringUTFChars(dir, nullptr);
        if (cs) {
            g_target_dir = cs;
            env->ReleaseStringUTFChars(dir, cs);
        }
    }
    g_watchdog_running = true;
    // 反扫描加固①：崩溃信号链（快轮询的误杀防线）
    install_crash_chain();
    // 反扫描加固②：非 root 攻击者（Shizuku 级/同 uid 其他进程）从此
    // 无法 ptrace attach 与读 /proc/self/mem——不只是"被检测"。
    // root（CAP_SYS_PTRACE）不受 Yama/dumpable 约束，由检测层覆盖
    prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    pthread_t t;
    if (pthread_create(&t, nullptr, watchdog_main, nullptr) == 0) {
        pthread_detach(t);
    } else {
        g_watchdog_running = false;  // 线程启动失败：不驻留半初始化状态
    }
}

// 单次同步检查（不引爆）：命中由 Java 侧走完整销毁序列（含 Keystore/daemon）。
// 与 watchdog 同规则：崩溃窗口与 debuggerd/tombstoned 白名单内不算命中；
// 完整性以"确证篡改"（1）为准——无法校验（-1）不在此路径引爆
// （watchdog 的布防状态机负责该语义）
extern "C" JNIEXPORT jboolean JNICALL
Java_fake_screenshot_defense_GuardManager_nativeCheck(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (scan_maps()) return JNI_TRUE;
    if (check_self_integrity() == 1) return JNI_TRUE;
    int tracer = read_tracer_pid();
    return (tracer > 0 && !in_crash_window() && !tracer_whitelisted(tracer))
           ? JNI_TRUE : JNI_FALSE;
}

// ===================== 常量时间比较（双实现，反硬件断点 hook）=====================
//
// 硬件断点内核外挂（内核态裸写调试寄存器）可 hook 单一比较函数恒真，
// 一行绕过门禁。对策为"断点资源耗尽"：两个结构不同的独立实现，
// 验证方（GuardManager.constantTimeEquals）要求两者结果一致——
// 每个关键点必须同时消耗两个断点，ARM64 有限的断点资源被成倍消耗。
// canary 哨兵（canary_check）周期性验证两实现的语义正确性。

// 实现A：逐字节累积异或
__attribute__((noinline))
static int ct_eq_byte(const volatile unsigned char* a,
                      const volatile unsigned char* b, size_t n) {
    unsigned char diff = 0;
    for (size_t i = 0; i < n; i++) {
        diff |= (unsigned char) (a[i] ^ b[i]);
    }
    return diff == 0;
}

// 实现B：结构不同（逐 8 字节拼宽比较 + 尾部逐字节），无共享代码路径，
// hook 单个实现无效。volatile 读 + 手工拼字节：不调用 memcmp/memcpy
// （库函数可被 hook，且 hook 它们会破坏全系统 libc 使用者）
__attribute__((noinline))
static int ct_eq_word(const volatile unsigned char* a,
                      const volatile unsigned char* b, size_t n) {
    unsigned long long diff = 0;
    size_t i = 0;
    for (; i + 8 <= n; i += 8) {
        unsigned long long x = 0, y = 0;
        for (int k = 7; k >= 0; k--) {
            x = (x << 8) | (unsigned long long) a[i + (size_t) k];
            y = (y << 8) | (unsigned long long) b[i + (size_t) k];
        }
        diff |= x ^ y;
    }
    for (; i < n; i++) {
        diff |= (unsigned long long) (unsigned char) (a[i] ^ b[i]);
    }
    return diff == 0;
}

// canary 哨兵自检：随机数据下两实现必须语义正确且交叉一致。
// 抓：恒真 hook（diff 期望 false 却得 true）、恒假、结果反转、
// 单实现被 hook 导致两实现分歧。种子用地址/时间/线程 id 熵——
// 哨兵只测函数语义，无需密码学随机
static bool canary_check() {
    unsigned char x[32], y[32], z[32];
    uintptr_t entropy = (uintptr_t) &x ^ (uintptr_t) time(nullptr)
                        ^ (uintptr_t) pthread_self();
    for (size_t i = 0; i < sizeof(x); i++) {
        x[i] = (unsigned char) (entropy >> ((i % sizeof(uintptr_t)) * 8));
        y[i] = x[i];
        z[i] = (unsigned char) (x[i] ^ (1u << (i % 8)));  // 每字节都不同
    }
    bool sameA = ct_eq_byte(x, y, sizeof(x)) == 1;
    bool sameB = ct_eq_word(x, y, sizeof(x)) == 1;
    bool diffA = ct_eq_byte(x, z, sizeof(x)) == 0;
    bool diffB = ct_eq_word(x, z, sizeof(x)) == 0;
    return sameA && sameB && diffA && diffB;
}

static jboolean jni_ct_eq(JNIEnv* env, jbyteArray a, jbyteArray b,
                          int (*impl)(const volatile unsigned char*,
                                      const volatile unsigned char*, size_t)) {
    if (a == nullptr || b == nullptr) return JNI_FALSE;
    jsize la = env->GetArrayLength(a);
    jsize lb = env->GetArrayLength(b);
    if (la != lb || la < 0) return JNI_FALSE;
    jbyte* pa = env->GetByteArrayElements(a, nullptr);
    jbyte* pb = env->GetByteArrayElements(b, nullptr);
    if (pa == nullptr || pb == nullptr) {
        if (pa != nullptr) env->ReleaseByteArrayElements(a, pa, JNI_ABORT);
        if (pb != nullptr) env->ReleaseByteArrayElements(b, pb, JNI_ABORT);
        return JNI_FALSE;
    }
    int r = impl((const volatile unsigned char*) pa,
                 (const volatile unsigned char*) pb, (size_t) la);
    env->ReleaseByteArrayElements(a, pa, JNI_ABORT);
    env->ReleaseByteArrayElements(b, pb, JNI_ABORT);
    return r == 1 ? JNI_TRUE : JNI_FALSE;
}

// 常量时间字节序列比较（防时序侧信道；native 化防 Java hook isEqual 绕过门禁）
extern "C" JNIEXPORT jboolean JNICALL
Java_fake_screenshot_defense_GuardManager_nativeConstantTimeEquals(
        JNIEnv* env, jobject /*thiz*/, jbyteArray a, jbyteArray b) {
    return jni_ct_eq(env, a, b, ct_eq_byte);
}

// 备用实现（结构不同）：调用方要求与主实现结果一致（交叉验证）
extern "C" JNIEXPORT jboolean JNICALL
Java_fake_screenshot_defense_GuardManager_nativeConstantTimeEqualsAlt(
        JNIEnv* env, jobject /*thiz*/, jbyteArray a, jbyteArray b) {
    return jni_ct_eq(env, a, b, ct_eq_word);
}
