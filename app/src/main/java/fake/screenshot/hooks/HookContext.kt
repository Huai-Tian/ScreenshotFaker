package fake.screenshot.hooks

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.concurrent.CopyOnWriteArraySet

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
        log(Log.INFO, "initialized, kind=$kind, templates=${config.templates.size}")
    }

    private fun reloadFromPrefs() {
        val raw = prefs?.getString(HookConfigCodec.REMOTE_KEY, null)
        config = HookConfigCodec.decode(raw)
        // 有状态引擎（surface 上留有标记类副作用）按新配置重算，如 E4
        reloadListeners.forEach { runCatching(it) }
    }

    /** 配置重载监听（引擎订阅；同为 listener，热重载时随 prefs 一并清理） */
    private val reloadListeners = CopyOnWriteArraySet<() -> Unit>()

    fun addConfigReloadListener(listener: () -> Unit) {
        reloadListeners.add(listener)
    }

    // ==================== 热重载生命周期 ====================

    /** 旧代清理（onHotReloading 内调用）：注销 listener 后本对象即无外部触点 */
    fun prepareHotReload() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        reloadListeners.clear()
    }

    /** 新代重建（onHotReloaded 内调用）：新 classloader 的单例从零装配 */
    fun resetForHotReload(module: XposedModule, isSystemServer: Boolean) {
        prefs = null
        init(module, if (isSystemServer) ProcessKind.SYSTEM_SERVER else ProcessKind.SCREENSHOT_APP)
    }

    // ==================== 查询面（引擎消费，全部无锁快照读） ====================

    /** E1：被截者管控三态（显式模板 → 全局三态） */
    fun screenshotPolicy(pkg: String?): Int = config.securePolicyFor(pkg)

    /** E2a：检测者的截屏侦听回调是否吞噬 */
    fun maskCaptureDetection(pkg: String?): Boolean =
        config.templateFor(pkg)?.maskCaptureDetection ?: false

    /** E2b：检测者的录屏侦听回调是否吞噬 */
    fun maskRecordDetection(pkg: String?): Boolean =
        config.templateFor(pkg)?.maskRecordDetection ?: false

    /** E2d：检测者的遮挡/呈现侦听信号是否剥离 */
    fun maskOverlayDetection(pkg: String?): Boolean =
        config.templateFor(pkg)?.maskOverlayDetection ?: false

    /** E4：被配置应用的自由浮窗是否穿透（对截图/录屏隐身） */
    fun piercesFreeform(pkg: String?): Boolean =
        config.templateFor(pkg)?.pierceFreeform ?: false

    /**
     * 策略聚合查询（截屏应用进程侧引擎用——捕获调用无法定位逐窗口归属，
     * 只能按"配置中是否存在某态"整体放行/收紧捕获管线）。
     * 模板列表极小（用户手建），逐次线性扫描无性能问题。
     */
    fun hasAllowPolicy(): Boolean {
        val c = config
        return c.globalSecurePolicy == HookConfig.SECURE_ALLOW ||
                c.templates.any { it.securePolicy == HookConfig.SECURE_ALLOW }
    }

    fun hasDenyPolicy(): Boolean {
        val c = config
        return c.globalSecurePolicy == HookConfig.SECURE_DENY ||
                c.templates.any { it.securePolicy == HookConfig.SECURE_DENY }
    }

    /**
     * E3：前台者的替换图 id。null = 不替换（原生截图）。
     * 替换的适用域即"模板绑定了图的显式配置应用"，"无论如何都会替换
     * （全局）"由 E3a+E3b 覆盖全部截屏路径达成（实现性质）。图片本体
     * 的远程文件加载由 E3 引擎自管；globalReplace* 全局字段目前纯 UI
     * 阶段，E3 落地时统一接入。
     */
    fun replacementImageId(fgPkg: String?): String? =
        config.templateFor(fgPkg)?.imageId

    // ==================== uid 解析（E2 检测者引擎共用） ====================

    /**
     * system_server 专用的 uid → 包名解析（Binder 调用方 → per-app 策略）。
     * PackageManager 本地解析 + 全量缓存（uid→包名集系统生命周期内稳定，
     * 应用安装/卸载仅影响未出现过的 uid）。解析失败（非 system_server /
     * PM 不可用）返回 null，调用方按"无策略"处理。
     */
    private val uidPkgsCache = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<List<String>>>()

    fun packagesForUid(uid: Int): List<String>? =
        uidPkgsCache.computeIfAbsent(uid) {
            java.util.Optional.ofNullable(
                runCatching {
                    // 隐藏类纯反射（AppGlobals/IPackageManager 不在公开 SDK）
                    val pm = Class.forName("android.app.AppGlobals")
                        .getMethod("getPackageManager").invoke(null) ?: return@runCatching null
                    val getPkgs = Class.forName("android.content.pm.IPackageManager")
                        .getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
                    (getPkgs.invoke(pm, uid) as? Array<String>)?.toList()
                }.getOrNull()
            )
        }.orElse(null)

    /** uid 下任一包名命中 [predicate]（uid 无包名/解析失败 → false） */
    fun anyPkgForUid(uid: Int, predicate: (String) -> Boolean): Boolean =
        packagesForUid(uid)?.any(predicate) == true

    // ==================== Hook 装配（引擎统一入口） ====================

    /** 已安装 hook 的 id 集（hookE 登记） */
    private val hookedIds = HashSet<String>()

    /** 热重载后判断旧句柄是否被新代以同 id 重新安装（是则框架已原子去重） */
    fun isHookInstalled(id: String?): Boolean = hookedIds.contains(id)

    /**
     * 带热重载 id 的 hook 装配（DFS 同款）：id 使热重载时同一 method 的
     * 新旧 hook 被框架识别为同一个（原子替换而非链上追加）。
     * id 必须含引擎 owner 前缀——多引擎 hook 同一 method（如 E1/E4 共用
     * prepareWindowToDisplayDuringRelayout）时，裸方法签名 id 会令后装
     * 引擎原子替换先装引擎的 hook（同 id = 替换），静默丢失先装的拦截器；
     * 带稳定 owner 前缀后：不同引擎共存成链，同引擎跨代同 id 原子替换。
     * 引擎一律经此入口装配，不得直接调 module.hook。
     */
    fun hookE(owner: String, executable: Executable): XposedInterface.HookBuilder {
        val m = module ?: throw IllegalStateException("HookContext not initialized")
        val builder = m.hook(executable)
        if (m.apiVersion >= 102) {
            val id = "$owner|${executable.toGenericString()}"
            builder.setId(id)
            hookedIds.add(id)
        }
        return builder
    }

    /**
     * 方法去优化门面（按名批量）：被 hook 的方法若被 JIT 内联进这些调用方，
     * hook 入口对已内联调用点无效——去优化强制解释执行，调用重新经过
     * hook 入口。类不存在/方法缺失静默跳过（版本矩阵差异）。
     */
    fun deoptimizeMethods(clazz: Class<*>, vararg names: String) {
        val m = module ?: return
        val list = names.toList()
        clazz.declaredMethods.filter { it.name in list }.forEach { m.deoptimize(it) }
    }

    // ==================== 日志门面 ====================

    /** 经框架写入 LSPosed 日志（root 可见），hook 进程内不落私有日志文件 */
    fun log(priority: Int, msg: String, tr: Throwable? = null) {
        module?.log(priority, TAG, msg, tr)
    }
}
