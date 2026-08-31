package fake.screenshot.defense

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest

/**
 * L0 注入检测雷管（Java 侧编排，检测与兜底引爆在 libmemsys.so）。
 *
 * 覆盖：注入框架（Frida/Substrate/SandHook 等，maps 黑名单）与
 * ptrace 型内存扫描器（GG 修改器类——扫描必须 attach，TracerPid
 * 1s 快轮询 2s 内引爆；PR_SET_DUMPABLE=0 同时令非 root 攻击者
 * 完全无法 attach）。root 经 /proc/mem 的静默直读原理上不可检测，
 * 由会话自动锁定缩小 DK 驻留窗口缓解（见包 README 威胁模型）。
 *
 * 防绕过分层：
 * 1. native 自主线程（nativeInit 启动，不依赖 Java 调用驱动）——
 *    Java 层被完全接管（hook 检测函数/心跳循环）仍持续检测，命中时
 *    native 直接覆写销毁密文并 SIGKILL（不可拦截）；
 * 2. Java 主动检查（[checkNow]，Application/Activity 启动时调用）——
 *    命中走 [DefenseProtocol.destroyForCoercion] 完整销毁序列
 *    （含 Keystore 条目删除与 daemon 停止，比 native 单删文件更彻底）。
 *
 * native 引爆的文件清单与胁迫销毁序列严格对齐（hw_key.bin 及其备份/
 * 临时文件、tink keyset、datastore 目录），sync_preferences（验证器）
 * 保留——门禁行为前后一致，不暴露"引爆发生过"。
 *
 * debug build 不启动（开发调试需要 jdwp/ptrace）。
 * 库加载失败不引爆（正常 ROM 不会失败；失败本身不构成注入证据）。
 *
 * JNI 符号（guard.cpp）：Java_fake_screenshot_defense_GuardManager_*
 * ——本类的包名/类名是 JNI 契约的一部分，重命名必须同步 guard.cpp。
 * 库文件名 libmemsys.so（CMake OUTPUT_NAME，中性化——maps 可见，勿改回"guard"）。
 */
object GuardManager {

    @Volatile
    private var nativeReady = false

    fun init(context: Context) {
        // debug 构建不启动（开发调试需要 jdwp/ptrace）。
        // 以 debuggable 标志判定（AGP 默认不生成 BuildConfig）
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        runCatching {
            System.loadLibrary("memsys")
            val app = context.applicationContext
            val filesDir = app.filesDir
            val targets = arrayListOf(
                File(filesDir, "hw_key.bin").absolutePath,
                File(filesDir, "hw_key.bin.bak").absolutePath,
                File(filesDir, "hw_key.bin.tmp").absolutePath,
                // tink keyset（SharedPreferences xml；删除后即使 master key
                // 条目残留于 Keystore 也无密文可解）
                File(app.filesDir.parentFile, "shared_prefs/tink_prefs.xml").absolutePath
            )
            nativeInit(targets.toTypedArray(), File(filesDir, "datastore").absolutePath)
            nativeReady = true
        }
    }

    /**
     * 单次同步注入检查（不引爆）。
     * @return true = 检测到注入（调用方执行完整销毁）；false = 干净或库不可用
     */
    fun checkNow(): Boolean =
        nativeReady && runCatching { nativeCheck() }.getOrDefault(false)

    /**
     * 常量时间字节序列比较（双实现交叉验证）。
     *
     * 反硬件断点内核外挂：主实现（逐字节）与备用实现（8 字节步进，
     * 结构不同无共享代码路径）必须结果一致——攻击者要 hook 单个
     * 比较函数恒真绕过门禁，必须同时挂两个断点在两个不同地址上，
     * ARM64 有限的断点资源（典型 4-6 个）被成倍消耗。native canary
     * 哨兵（watchdog 周期）持续验证两实现语义，恒真/恒假/反转即引爆。
     *
     * 库不可用退回平台实现（MessageDigest.isEqual）。
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        if (nativeReady) {
            val r1 = runCatching { nativeConstantTimeEquals(a, b) }.getOrNull()
            val r2 = runCatching { nativeConstantTimeEqualsAlt(a, b) }.getOrNull()
            // 双实现都可用：交叉一致才通过（单实现被 hook 即分歧）
            if (r1 != null && r2 != null) return r1 && r2
            // 仅一个可用（理论上不应发生，防御性处理）：用可用的那个
            if (r1 != null) return r1
            if (r2 != null) return r2
        }
        return MessageDigest.isEqual(a, b)
    }

    private external fun nativeInit(files: Array<String>, dir: String)
    private external fun nativeCheck(): Boolean
    private external fun nativeConstantTimeEquals(a: ByteArray, b: ByteArray): Boolean
    private external fun nativeConstantTimeEqualsAlt(a: ByteArray, b: ByteArray): Boolean
}
