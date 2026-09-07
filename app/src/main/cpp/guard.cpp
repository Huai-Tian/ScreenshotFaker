// 执行路径劫持检测雷管（libmemsys.so——中性化库名，maps 可见；源码内称 guard）
//
// 威胁：root 取证者劫持本进程 native 执行路径以窃取门禁密码或 DK——
// hook 关键 libc 函数令检测/读文件"撒谎"、inline patch/蹦床劫持雷管
// 函数、驻留信号 handler 拦截信号；以及 GG 修改器类扫描器扫取内存
// （需 ptrace attach）。Java 层检测可被同层 hook 拦截（一行
// hook 让检测函数恒返回"干净"），因此检测与引爆均在 native：
// - 自主 watchdog 线程：不依赖任何 Java 调用驱动，Java 层被完全接管仍工作
// - 引爆动作：覆写（零填充+fsync）并 unlink 密文文件 → SIGKILL 自身。
//   SIGKILL 不可被任何信号 handler/注入代码拦截；密文先于进程死亡被销毁
// - Java 侧主动检查入口（nativeCheck）：命中时由 Java 走完整销毁序列
//   （含 Keystore 条目删除与 daemon 停止，比 native 单删文件更彻底），
//   native 引爆是 Java 层被拦截时的兜底
//
// 自我 hook 审计三线（DuckDetector 方法论）：不检测注入框架的存在
// （框架痕迹检测在匿名内存装载/静态链入时代已失效），检测自己的
// 执行路径是否被劫持——hook 是结构性动作（改机器码或改符号解析），
// 与注入载体的隐蔽方式无关：
// - 线1 GOT/PLT 解析审计：dlsym 解析 12 个感知面符号（open/read/mmap/
//   dlopen 等——隐藏/操纵框架要控制 app 感知的必经之路），解析地址必须
//   落在预期系统模块（libc/libdl/linker）范围内；越界或 unmapped
//   = 符号解析被劫持
// - 线2 入口序言审计：读函数入口指令——branch-like（B/BL/BR/BLR）且
//   非正常编译器序言（stp/sub sp/nop/paciasp/adrp 白名单），或
//   trampoline handoff（LDR literal + BR xN）= inline hook。审计对象
//   含 libc 12 符号与本库雷管函数（detonate/shred_file/ct_eq_*/
//   canary_check 等——攻击者要废雷管必须 hook 它们，入口必变）
// - 线3 信号 handler 审计：SIGTRAP/SIGBUS/SIGSEGV/SIGILL 的 handler
//   指向匿名映射或无映射 = 可疑（hook 框架驻留内存 handler 拦截信号/
//   反调试）；指向有文件路径的正常模块豁免（ART fault manager 在
//   libart.so、本库崩溃信号链的 guard_crash_handler 在本库）
//
// 与既有检测线的互补分工：
// - 自完整性校验：函数内部 patch（nop 掉 detonate 内部的 kill、改检测
//   线内部判定常量）——序言审计只看入口，函数内部只有它覆盖
// - 双实现常量时间比较 + canary：硬件断点 hook 比较函数
// - TracerPid 快轮询：交互式扫描器与 attach 瞬态窗口（frida-server
//   attach→注入→detach 常短于 2s 确认窗，但注入完成即留下 inline
//   hook/蹦床，由线1/线2 接续覆盖；zygote fork 链路注入本就无
//   ptrace 瞬态，直接由三线覆盖）
//
// 诚实边界：
// - root 无需 attach 即可经 /proc/pid/mem、process_vm_readv 静默直读
//   （TracerPid 恒 0），原理上不可检测——唯一缓解是 Java 侧自动锁定
//   缩小 DK 驻留
// - 定向 patch 本库 GOT 项（如 dlsym 调用点）可令审计拿到假地址：
//   需先注入代码（装载面政策上不检测）+ 逆向 stripped 库定位 RELRO
//   段内特定 GOT 项 + mprotect 改写；自完整性/TracerPid/canary 独立
//   于该路径仍然工作
// - Java 层 hook（LSPlant/ArtMethod swap）不动 native 机器码，三线
//   不命中——由 v3 解密式验证 + 双实现比较 + canary 承担
//
// 误报控制（引爆 = 用户数据销毁，代价极高，规则保守）：
// - 符号解析失败（dlsym null）/ dlopen 失败：跳过该符号（怪环境不误杀）
// - maps 读取失败 / libc 不在 maps：跳过本轮，不视为命中
// - 入口序言白名单涵盖常见编译器序言；32 位 ABI（arm Thumb/x86）仅检
//   最强蹦床特征（LDR PC,[PC,#-4] / JMP rel32 / PUSH imm32;RET）从宽
// - 序言审计不越界读：入口距映射尾部不足 12 字节跳过
// - crash 时 debuggerd/tombstoned 会 ptrace attach：三重白名单防误杀——
//   ①tracer 进程名白名单（debuggerd/tombstoned）②本进程崩溃信号标记
//   （崩溃后 30s 内的 attach 一律放过，覆盖未知名的 OEM 崩溃收集器）
//   ③连续 2 轮（2s）确认（`debuggerd -b` 等瞬态合法 attach 活不过确认期）
// - debug build：Java 侧不调用 nativeInit，watchdog 不启动
//
// 静默性：无任何日志输出，strip-all 去符号。

#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <dirent.h>
#include <signal.h>
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
// （不视为命中，防误杀）。noinline：序言审计对象（kSelfFunctions）
__attribute__((noinline))
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
// （已知边界），此白名单不为对抗 root 而设，只为不误杀。
// noinline：序言审计对象（kSelfFunctions）
__attribute__((noinline))
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

// ===================== 自我 hook 审计（三线，DuckDetector 方法论）=====================

// 前向声明：自身雷管函数表引用文件后部定义的函数（noinline 在各自
// 定义处，确保独立函数体与稳定入口——序言审计的前提）
static void detonate();
static void shred_file(const char* path);
static void shred_dir(const char* path, int depth);
static void* watchdog_main(void* arg);
static void guard_crash_handler(int sig, siginfo_t* info, void* ctx);
static int ct_eq_byte(const volatile unsigned char* a,
                      const volatile unsigned char* b, size_t n);
static int ct_eq_word(const volatile unsigned char* a,
                      const volatile unsigned char* b, size_t n);
static bool canary_check();
static bool audit_execution_paths();

// 线2 审计对象之一：本库雷管函数。攻击者要废雷管必须 hook/patch 它们，
// 入口必变。表内容为函数地址（无名字符串——静默性，strip 后无信息量）
static const void* const kSelfFunctions[] = {
        reinterpret_cast<const void*>(&detonate),
        reinterpret_cast<const void*>(&shred_file),
        reinterpret_cast<const void*>(&shred_dir),
        reinterpret_cast<const void*>(&watchdog_main),
        reinterpret_cast<const void*>(&read_tracer_pid),
        reinterpret_cast<const void*>(&tracer_whitelisted),
        reinterpret_cast<const void*>(&guard_crash_handler),
        reinterpret_cast<const void*>(&audit_execution_paths),
        reinterpret_cast<const void*>(&ct_eq_byte),
        reinterpret_cast<const void*>(&ct_eq_word),
        reinterpret_cast<const void*>(&canary_check),
};

// 线1 审计对象：libc 感知面符号（隐藏/操纵框架要控制 app 感知的必经
// 之路）。allowLinker：dl* 家族在不同 Android 版本由 libc/linker 提供
static const struct {
    const char* name;
    bool allowLinker;
} kSenseSymbols[] = {
        {"open", false},     {"openat", false},  {"read", false},
        {"write", false},    {"stat", false},    {"access", false},
        {"readlink", false}, {"mmap", false},    {"mprotect", false},
        {"dlopen", true},    {"dlsym", true},    {"dlclose", true},
};

// 待查地址的 maps 定位结果
struct AddrPlacement {
    uintptr_t addr;
    uintptr_t mapEnd;  // 所在映射结束地址（序言读越界防护）
    bool resolved;     // 定位到映射
    bool readable;     // 映射可读
    bool hasPath;      // 映射有文件路径（非匿名）
};

// 预期模块地址范围（线1 判定基准）
struct AddrRange {
    uintptr_t start;
    uintptr_t end;
};

// 读 4 字节（memcpy 防对齐/别名 UB）。仅 aarch64/arm 的指令模式判定使用
#if defined(__aarch64__) || defined(__arm__)
static inline uint32_t read32_at(uintptr_t addr) {
    uint32_t v;
    memcpy(&v, reinterpret_cast<const void*>(addr), sizeof(v));
    return v;
}
#endif

// 入口是否被 hook。掩码/模式与 DuckDetector function_hook_detector
// 一致；32 位 ABI 从宽（仅最强蹦床特征，正常函数入口不可能命中）
#if defined(__aarch64__)
static bool entry_is_hooked(uintptr_t addr) {
    uint32_t first = read32_at(addr);
    uint32_t second = read32_at(addr + 4);
    // trampoline handoff：LDR Xn, [pc]; BR Xn（蹦床标志，双指令模式）
    if ((first & 0xFF000000u) == 0x58000000u &&
        (second & 0xFFFFFC00u) == 0xD61F0000u) {
        return true;
    }
    // branch-like：B/BL（>>26 判定）或 BR/BLR xN
    bool branch = (first >> 26) == 0x05 || (first >> 26) == 0x25 ||
                  (first & 0xFFFFFC1Fu) == 0xD61F0000u ||
                  (first & 0xFFFFFC1Fu) == 0xD63F0000u;
    if (!branch) return false;
    // 正常编译器序言白名单：stp x29,x30,[sp,#-N]!（任意 imm）/ sub sp,sp,#N /
    // mov x29,sp / nop / paciasp / adrp。
    // 注①：DuckDetector 原掩码 (0xFFC003FF==0xA9BF7BFD) 恒假（常量的
    // 被掩蔽位非零）——上游 stp 条目从未真正比较过；此处改为保留
    // opcode+Rt2+Rn+Rt、imm 任意的正确掩码。
    // 注②：白名单仅在首指令 branch-like 时参与判定，而表中序言编码
    // 均非 branch-like——此层为纵深防御（防未来指令集扩展的意外交叠），
    // 实际判定力来自 branch-like 检测本身。bti c（0xD503245F）经核算
    // 非 branch-like（>>26==3，BR/BLR 掩码不匹配），BTI 入口安全
    bool normalPrologue = (first & 0xFFC07FFFu) == 0xA9807BFDu ||
                          (first & 0xFF8003FFu) == 0xD10003FFu ||
                          first == 0x910003FDu ||
                          first == 0xD503201Fu ||
                          first == 0xD503233Fu ||
                          (first & 0x9F000000u) == 0x90000000u;
    return !normalPrologue;
}
#elif defined(__x86_64__)
static bool entry_is_hooked(uintptr_t addr) {
    const uint8_t* b = reinterpret_cast<const uint8_t*>(addr);
    if (b[0] == 0xFF && b[1] == 0x25) return true;  // JMP [rip+disp32]
    if (b[0] == 0x48 && b[1] == 0xB8 &&            // MOV rax, imm64
        b[10] == 0xFF && b[11] == 0xE0) return true; // JMP rax
    if (b[0] == 0xE9 || b[0] == 0xEB) return true; // JMP rel32/rel8
    return false;
}
#elif defined(__i386__)
static bool entry_is_hooked(uintptr_t addr) {
    const uint8_t* b = reinterpret_cast<const uint8_t*>(addr);
    if (b[0] == 0xE9 || b[0] == 0xEB) return true;  // JMP rel32/rel8
    if (b[0] == 0xFF && b[1] == 0x25) return true;  // JMP [disp32]
    if (b[0] == 0x68 && b[5] == 0xC3) return true;  // PUSH imm32; RET
    return false;
}
#elif defined(__arm__)
static bool entry_is_hooked(uintptr_t addr) {
    if (addr & 1u) {  // Thumb 模式（函数指针 bit0 置位）
        addr &= ~(uintptr_t)1u;
        uint16_t h0, h1;
        memcpy(&h0, reinterpret_cast<const void*>(addr), 2);
        memcpy(&h1, reinterpret_cast<const void*>(addr + 2), 2);
        return h0 == 0xF85Fu && h1 == 0xF004u;  // LDR.W PC,[PC,#-4]
    }
    return read32_at(addr) == 0xE51FF004u;      // LDR PC,[PC,#-4]
}
#else
static bool entry_is_hooked(uintptr_t) { return false; }  // 未知 ABI：从宽
#endif

// 单遍 maps 流式定位：地址落入当前行即记录映射特征
static void place_addrs(AddrPlacement* items, int count, uintptr_t start,
                        uintptr_t end, bool readable, bool hasPath) {
    for (int i = 0; i < count; i++) {
        if (!items[i].resolved && items[i].addr >= start && items[i].addr < end) {
            items[i].resolved = true;
            items[i].readable = readable;
            items[i].hasPath = hasPath;
            items[i].mapEnd = end;
        }
    }
}

static bool in_ranges(const AddrRange* ranges, int count, uintptr_t addr) {
    for (int i = 0; i < count; i++) {
        if (addr >= ranges[i].start && addr < ranges[i].end) return true;
    }
    return false;
}

// 三线审计主入口。返回 true = 执行路径被劫持（引爆/交 Java 完整销毁）；
// maps 不可读或 libc 不在 maps：返回 false（怪环境不误杀，沿用旧原则）
__attribute__((noinline))
static bool audit_execution_paths() {
    struct HookAudit {
        AddrPlacement syms[12];    // dlsym 解析的感知面符号
        bool allowLinker[12];
        int symCount;
        AddrPlacement selfs[11];   // 本库雷管函数
        int selfCount;
        AddrPlacement handlers[4]; // 非默认信号 handler
        int handlerCount;
        AddrRange libcRanges[32];
        int libcRangeCount;
        AddrRange linkerRanges[32]; // libdl.so / linker*
        int linkerRangeCount;
    } a{};
    if (sizeof(a.syms) / sizeof(a.syms[0]) < sizeof(kSenseSymbols) / sizeof(kSenseSymbols[0]) ||
        sizeof(a.selfs) / sizeof(a.selfs[0]) < sizeof(kSelfFunctions) / sizeof(kSelfFunctions[0])) {
        return false;  // 静态防御（编译期可证，运行期不可能）
    }

    // 线3 前置：采集非默认信号 handler 地址
    static const int kWatchedSigs[4] = {SIGTRAP, SIGBUS, SIGSEGV, SIGILL};
    for (int sig : kWatchedSigs) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        if (sigaction(sig, nullptr, &sa) != 0) continue;
        uintptr_t handler = (sa.sa_flags & SA_SIGINFO) != 0
                            ? reinterpret_cast<uintptr_t>(sa.sa_sigaction)
                            : reinterpret_cast<uintptr_t>(sa.sa_handler);
        if (handler == 0 ||
            handler == reinterpret_cast<uintptr_t>(SIG_DFL) ||
            handler == reinterpret_cast<uintptr_t>(SIG_IGN)) {
            continue;
        }
        a.handlers[a.handlerCount++].addr = handler;
    }

    // 线1/线2 前置：dlsym 解析感知面符号。dlopen/dlsym 失败（怪环境或
    // 被 hook 到失效）只影响 libc 符号线；自身雷管函数审计不依赖 dlsym
    void* globalHandle = dlopen(nullptr, RTLD_NOW);
    if (globalHandle != nullptr) {
        for (const auto& sym : kSenseSymbols) {
            void* p = dlsym(globalHandle, sym.name);
            if (p == nullptr) continue;  // 解析失败跳过（怪环境不误杀）
            a.syms[a.symCount].addr = reinterpret_cast<uintptr_t>(p);
            a.allowLinker[a.symCount] = sym.allowLinker;
            a.symCount++;
        }
    }
    for (const void* fn : kSelfFunctions) {
        a.selfs[a.selfCount++].addr = reinterpret_cast<uintptr_t>(fn);
    }

    // 单遍读 maps：收集预期模块范围 + 定位全部待查地址
    FILE* f = fopen("/proc/self/maps", "re");
    if (f == nullptr) return false;
    char line[4096];
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s = 0, e = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &s, &e) != 2 || e <= s) continue;
        char* sp = strchr(line, ' ');
        if (sp == nullptr) continue;
        bool readable = sp[1] == 'r';
        char* pathField = strstr(sp, " /");  // 匿名映射（含 [anon:...]）无 " /"
        const char* path = pathField != nullptr ? pathField + 1 : nullptr;
        // 收集预期模块范围（子串从宽匹配：范围集偏宽只降低线1 灵敏度，
        // 不产生误报；痕迹对抗不在本线射程）
        if (path != nullptr) {
            if (strstr(path, "libc.so") != nullptr) {
                if (a.libcRangeCount < (int)(sizeof(a.libcRanges) / sizeof(a.libcRanges[0]))) {
                    a.libcRanges[a.libcRangeCount++] = {s, e};
                }
            } else if (strstr(path, "libdl.so") != nullptr ||
                       strstr(path, "linker") != nullptr) {
                if (a.linkerRangeCount < (int)(sizeof(a.linkerRanges) / sizeof(a.linkerRanges[0]))) {
                    a.linkerRanges[a.linkerRangeCount++] = {s, e};
                }
            }
        }
        place_addrs(a.syms, a.symCount, s, e, readable, path != nullptr);
        place_addrs(a.selfs, a.selfCount, s, e, readable, path != nullptr);
        place_addrs(a.handlers, a.handlerCount, s, e, readable, path != nullptr);
    }
    fclose(f);
    if (a.libcRangeCount == 0) return false;  // libc 不在 maps：环境异常，跳过

    // 线1 GOT/PLT 解析审计：感知面符号必须解析进预期系统模块。
    // unmapped 同样命中（dlsym 返回的活代码地址必然在 maps；
    // 不在 = maps 被伪造/对我们隐藏）
    for (int i = 0; i < a.symCount; i++) {
        bool ok = in_ranges(a.libcRanges, a.libcRangeCount, a.syms[i].addr) ||
                  (a.allowLinker[i] &&
                   in_ranges(a.linkerRanges, a.linkerRangeCount, a.syms[i].addr));
        if (!ok) return true;
    }
    // 线2 入口序言审计（libc 符号 + 本库雷管函数）。
    // 未定位/不可读/入口距映射尾不足 12 字节：跳过（防越界读崩溃；
    // 本库代码完整性另由自完整性校验覆盖）
    for (int i = 0; i < a.symCount; i++) {
        const AddrPlacement& it = a.syms[i];
        if (!it.resolved || !it.readable || it.mapEnd - it.addr < 12) continue;
        if (entry_is_hooked(it.addr)) return true;
    }
    for (int i = 0; i < a.selfCount; i++) {
        const AddrPlacement& it = a.selfs[i];
        if (!it.resolved || !it.readable || it.mapEnd - it.addr < 12) continue;
        if (entry_is_hooked(it.addr)) return true;
    }
    // 线3 信号 handler 审计：非默认 handler 指向匿名映射/无映射 = 命中。
    // 指向有路径的正常模块豁免（ART fault manager 在 libart.so、
    // guard_crash_handler 在本库——均有路径）
    for (int i = 0; i < a.handlerCount; i++) {
        if (!a.handlers[i].resolved || !a.handlers[i].hasPath) return true;
    }
    return false;
}

// ===================== 自完整性校验（反 inline patch）=====================
//
// 威胁：ptrace POKE 单发 patch 本库 .text（如把 nativeConstantTimeEquals
// 改为恒真）——序言审计只看函数入口，抓不住"改函数内部"，这是
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
// 文件系统层面不可恢复优先，尽力而为。
// noinline：序言审计对象（kSelfFunctions）
__attribute__((noinline))
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
// noinline：序言审计对象（kSelfFunctions）
__attribute__((noinline))
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

// 引爆：密文覆写销毁 → SIGKILL（不可拦截）。全程无 Java 调用。
// noinline：序言审计对象（kSelfFunctions）
__attribute__((noinline))
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

// noinline：序言审计对象（kSelfFunctions）
__attribute__((noinline))
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
            // 自我 hook 审计三线（原黑名单扫描继任者）：命中 = 执行路径
            // 被劫持（驻留态非瞬态，不存在 crash 误报源）→ 立即引爆
            if (audit_execution_paths()) {
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
    if (audit_execution_paths()) return JNI_TRUE;
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
// 哨兵只测函数语义，无需密码学随机。
// noinline：序言审计对象（kSelfFunctions）
__attribute__((noinline))
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
