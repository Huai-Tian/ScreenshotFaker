package fake.screenshot.hooks

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * Hook 进程侧共享 spine：配置加载/缓存/查询面 + 进程判定 + 日志门面。
 * 各引擎（同目录单文件）唯一共享点，互相不引用。
 *
 * 体例铁律（热重载安全）：本对象只持有静态缓存与 listener，不创建线程、
 * 不触碰 native、不注册系统回调——onHotReloading 注销 listener 即达
 * "无残留"标准，框架随后可整体卸载旧代 classloader。
 *
 * 配置流转：RemotePreferences（LSPosed 推送）→ 解码为 [HookConfig] 全量
 * 换入 @Volatile 引用。查询面在 hook 拦截器内被高频调用（逐事件级），
 * 因此走无锁快照读：解码开销只发生在变更瞬间，拦截器内只有引用读 +
 * map 查找。任何解码失败回落全关默认态（= 原生行为），绝不抛出。
 */
object HookContext {
    enum class ProcessKind { SYSTEM_SERVER, SCREENSHOT_APP, OTHER }

    private const val TAG = "SF"

    @Volatile
    private var config: HookConfig = HookConfig.DEFAULT

    @Volatile
    var kind: ProcessKind = ProcessKind.OTHER
        private set

    private var module: XposedModule? = null
    private var prefs: SharedPreferences? = null

    // listener 持有 prefs/codec 引用（同 classloader），热重载时注销即断链
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == HookConfigCodec.REMOTE_KEY) reloadFromPrefs()
        }

    /**
     * 进程装配入口（由模块入口类在每个 hook 进程的首个生命周期回调调用，
     * 幂等）。getRemotePreferences 每进程只取一次并缓存——重复获取可能
     * 得到相互独立的实例与 listener 泄漏。
     */
    fun init(module: XposedModule, kind: ProcessKind) {
        this.module = module
        this.kind = kind
        if (prefs == null) {
            prefs = module.getRemotePreferences(HookConfigCodec.REMOTE_GROUP).also { p ->
                reloadFromPrefs()
                p.registerOnSharedPreferenceChangeListener(prefsListener)
            }
        }
        log(Log.INFO, "initialized, kind=$kind, master=${config.masterEnabled}, templates=${config.templates.size}")
    }

    private fun reloadFromPrefs() {
        val raw = prefs?.getString(HookConfigCodec.REMOTE_KEY, null)
        config = HookConfigCodec.decode(raw)
    }

    // ==================== 热重载生命周期 ====================

    /** 旧代清理（onHotReloading 内调用）：注销 listener 后本对象即无外部触点 */
    fun prepareHotReload() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    /** 新代重建（onHotReloaded 内调用）：新 classloader 的单例从零装配 */
    fun resetForHotReload(module: XposedModule, isSystemServer: Boolean) {
        prefs = null
        init(module, if (isSystemServer) ProcessKind.SYSTEM_SERVER else ProcessKind.SCREENSHOT_APP)
    }

    // ==================== 查询面（引擎消费，全部无锁快照读） ====================

    /** E1：被截者管控三态（显式模板 → 全局三态；总开关关闭 = 跟随） */
    fun screenshotPolicy(pkg: String?): Int {
        val c = config
        if (!c.masterEnabled) return HookConfig.SECURE_FOLLOW
        return c.securePolicyFor(pkg)
    }

    /** E2a：检测者的截屏侦听回调是否吞噬 */
    fun maskCaptureDetection(pkg: String?): Boolean {
        val c = config
        return c.masterEnabled && (c.templateFor(pkg)?.maskCaptureDetection ?: false)
    }

    /** E2b：检测者的录屏侦听回调是否吞噬 */
    fun maskRecordDetection(pkg: String?): Boolean {
        val c = config
        return c.masterEnabled && (c.templateFor(pkg)?.maskRecordDetection ?: false)
    }

    /** E2d：检测者的遮挡/呈现侦听信号是否剥离 */
    fun maskOverlayDetection(pkg: String?): Boolean {
        val c = config
        return c.masterEnabled && (c.templateFor(pkg)?.maskOverlayDetection ?: false)
    }

    /**
     * E3：前台者的替换图 id。null = 不替换（原生截图）。
     * 无独立全局替换开关：替换的适用域即"模板绑定了图的显式配置应用"，
     * "无论如何都会替换（全局）"由 E3a+E3b 覆盖全部截屏路径达成（实现
     * 性质），不构成配置轴。图片本体的远程文件加载由 E3 引擎自管。
     */
    fun replacementImageId(fgPkg: String?): String? {
        val c = config
        if (!c.masterEnabled) return null
        return c.templateFor(fgPkg)?.imageId
    }

    // ==================== Hook 装配（引擎统一入口） ====================

    /** 已安装 hook 的 id 集（hookE 登记） */
    private val hookedIds = HashSet<String>()

    /** 热重载后判断旧句柄是否被新代以同 id 重新安装（是则框架已原子去重） */
    fun isHookInstalled(id: String?): Boolean = hookedIds.contains(id)

    /**
     * 带热重载 id 的 hook 装配（DFS 同款）：id 使热重载时同一 method 的
     * 新旧 hook 被框架识别为同一个（原子替换而非链上追加）。
     * 引擎一律经此入口装配，不得直接调 module.hook。
     */
    fun hookE(executable: Executable): XposedInterface.HookBuilder {
        val m = module ?: throw IllegalStateException("HookContext not initialized")
        val builder = m.hook(executable)
        if (m.apiVersion >= 102) {
            val id = executable.toGenericString()
            builder.setId(id)
            hookedIds.add(id)
        }
        return builder
    }

    // ==================== 日志门面 ====================

    /** 经框架写入 LSPosed 日志（root 可见），hook 进程内不落私有日志文件 */
    fun log(priority: Int, msg: String, tr: Throwable? = null) {
        module?.log(priority, TAG, msg, tr)
    }
}
