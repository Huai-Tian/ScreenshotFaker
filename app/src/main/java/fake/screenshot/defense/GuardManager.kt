package fake.screenshot.defense

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * L0 执行路径劫持检测雷管（Java 侧编排，检测与兜底引爆在 libmemsys.so）。
 *
 * 覆盖（自我 hook 审计三线，DuckDetector 方法论——不检测注入框架的
 * 存在（痕迹检测在匿名装载时代已失效），检测自己的执行路径是否被
 * 劫持；hook 是结构性动作，与注入载体的隐蔽方式无关）：
 * - GOT/PLT 解析劫持（dlsym 感知面符号越出预期系统模块）
 * - inline hook/蹦床（关键 libc 函数与本库雷管函数入口指令异常）
 * - 驻留信号 handler（SIGTRAP/BUS/SEGV/ILL 指向匿名映射）
 * - 自完整性校验（本库 .text vs 磁盘基准，反函数内部 patch）
 * 以及 ptrace 型内存扫描器（GG 修改器类——扫描必须 attach，TracerPid
 * 1s 快轮询；PR_SET_DUMPABLE=0 同时令非 root 攻击者完全无法 attach）。
 * root 经 /proc/mem 的静默直读原理上不可检测，由结构隔离（vault 进程）
 * 与会话自动锁定缩小 DK 驻留窗口缓解（见包 README 威胁模型）。
 *
 * 防绕过分层：
 * 1. native 自主线程（nativeInit 启动，不依赖 Java 调用驱动）——
 *    Java 层被完全接管（hook 检测函数/心跳循环）仍持续检测，命中时
 *    native 直接覆写销毁密文并 SIGKILL（不可拦截）；
 * 2. Java 主动检查（[checkNow]，Application/Activity 启动时调用）——
 *    命中走 [DefenseProtocol.destroyForCoercion] 完整销毁序列
 *    （含 Keystore 条目删除与 daemon 停止，比 native 单删文件更彻底）。
 *
 * 栈流审计/常量时间双实现比较已随 vault 结构隔离退役：其守护对象
 * （DK 组装/敏感读写/门禁比较/配置下发的 Java 执行路径）全部移入
 * 无 ART 的 vault 进程——LSPlant/自研框架对不存在的运行时无处下钩，
 * 检测对象消失（非撤销武装，是武器失去靶标；详见 README 威胁表 #23）。
 *
 * native 引爆的文件清单与销毁序列严格对齐（sync_key.bin 及 tmp、
 * WK 包裹文件、tink keyset、datastore 目录），sync_check
 * 语义由 sync_key.bin 内嵌验证项承载并保留——门禁行为前后一致，
 * 不暴露"引爆发生过"。
 *
 * debug build 不启动（开发调试需要 jdwp/ptrace）。
 * 库加载失败不引爆（正常 ROM 不会失败；失败本身不构成劫持证据）。
 *
 * JNI 符号（guard.cpp / vault_client.cpp）：
 * Java_fake_screenshot_defense_GuardManager_* 与
 * Java_fake_screenshot_defense_VaultClient_*——本包的类名是 JNI 契约
 * 的一部分，重命名必须同步 native 侧。库文件名 libmemsys.so
 * （CMake OUTPUT_NAME，中性化——maps 可见，勿改回"guard"）。
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
                // vault 密钥文件（DK 包裹；验证项内嵌其中按状态字节保留）
                File(filesDir, "sync_key.bin").absolutePath,
                File(filesDir, "sync_key.bin.tmp").absolutePath,
                // WK 包裹文件（无门禁模式的 DK 通道）
                File(filesDir, "sync_wrap.bin").absolutePath,
                File(filesDir, "sync_wrap.bin.tmp").absolutePath,
                // tink keyset（SharedPreferences xml；删除后即使 master key
                // 条目残留于 Keystore 也无密文可解）
                File(app.filesDir.parentFile, "shared_prefs/tink_prefs.xml").absolutePath
            )
            nativeInit(targets.toTypedArray(), File(filesDir, "datastore").absolutePath)
            nativeReady = true
        }
    }

    /**
     * 单次同步执行路径检查（不引爆）。
     * @return true = 检测到劫持（调用方执行完整销毁）；false = 干净或库不可用
     */
    fun checkNow(): Boolean =
        nativeReady && runCatching { nativeCheck() }.getOrDefault(false)

    private external fun nativeInit(files: Array<String>, dir: String)
    private external fun nativeCheck(): Boolean
}
