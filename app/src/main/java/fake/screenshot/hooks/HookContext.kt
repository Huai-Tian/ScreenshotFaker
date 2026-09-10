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

    /**
     * 截屏应用白名单（scope 泛滥防御 + E3a 前台判定的"自己人"排除集）。
     * 入口类装配与引擎前台解析共用同一来源，防双份漂移
     */
    val SCREENSHOT_PACKAGES = setOf(
        "com.android.systemui",
        "com.flyme.systemuiex",
        "com.miui.screenshot",
        "com.oplus.appplatform",
        "com.oplus.screenshot",
    )

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
        if (raw != null) {
            configSynced = true
            log(Log.INFO, "config synced (templates=${config.templates.size})")
        } else {
            // 热重载时序竞争（实测：连续热重载后 RemotePreferences 桥推送
            // 丢失，config 停留 DEFAULT → 全引擎判定静默失效）：退避重拉
            scheduleConfigRetry()
        }
    }

    /** 配置是否已从远端同步（false = 停留 DEFAULT，触发退避重拉） */
    @Volatile
    private var configSynced = false

    /** 退避重拉（1s/2s/4s/8s/16s；期间收到推送则 configSynced=true 退出） */
    private fun scheduleConfigRetry() {
        if (configSynced || retryScheduled) return
        synchronized(this) {
            if (configSynced || retryScheduled) return
            retryScheduled = true
        }
        Thread({
            for (i in 0 until 5) {
                runCatching { Thread.sleep(1000L shl i) }
                if (configSynced) break
                log(Log.WARN, "config retry #${i + 1} (still unsynced)")
                val ok = runCatching {
                    val raw = prefs?.getString(HookConfigCodec.REMOTE_KEY, null)
                    if (raw != null) {
                        config = HookConfigCodec.decode(raw)
                        reloadListeners.forEach { runCatching(it) }
                        configSynced = true
                        true
                    } else false
                }.getOrDefault(false)
                if (ok) {
                    log(Log.INFO, "config retry synced (templates=${config.templates.size})")
                    break
                }
            }
            retryScheduled = false
        }, "sf-config-retry").apply { isDaemon = true }.start()
    }

    @Volatile
    private var retryScheduled = false

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

    /** E2c：检测者的悬浮窗直接信号是否屏蔽（触摸遮挡标志） */
    fun maskOverlayDetection(pkg: String?): Boolean =
        config.templateFor(pkg)?.maskOverlayDetection ?: false

    /**
     * E2c 生效条件：任一模板启用悬浮窗屏蔽。trustedOverlay 是模块自有
     * 窗口的属性，作用于全部下方窗口——无法按检测者包名区分，故任一
     * 开启即全局标记（开关语义见 OverlayStealthHook 头注释）
     */
    fun anyOverlayMaskOn(): Boolean =
        config.templates.any { it.maskOverlayDetection }

    /** E2d：检测者的窗口焦点丢失信号是否隐瞒（仅焦点直接信号） */
    fun maskFocusDetection(pkg: String?): Boolean =
        config.templateFor(pkg)?.maskFocusDetection ?: false

    /** E2e：检测者的可信呈现信号是否屏蔽（窗口显示完整性） */
    fun maskPresentationDetection(pkg: String?): Boolean =
        config.templateFor(pkg)?.maskPresentationDetection ?: false

    /**
     * E2a：激进检测过滤（媒体域 ContentObserver 注册全吞）。
     * per-app 独立开关，不依赖模板分配（应用详情页设置）
     */
    fun aggressiveFilter(pkg: String?): Boolean =
        pkg != null && config.aggressiveFilter.contains(pkg)

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
     * 优先级：前台者的显式模板图 > 全局替换（开关开启且已配置图）。
     * 图片本体的远程密文加载/解码/缓存由 [ReplaceImageStore] 负责，
     * 引擎装配时 ensureInstalled。
     */
    fun replacementImageId(fgPkg: String?): String? {
        val c = config
        c.templateFor(fgPkg)?.imageId?.let { return it }
        if (c.globalReplaceEnabled && c.globalReplaceImage != null) return c.globalReplaceImage
        return null
    }

    /** E3b 门控：任何替换配置存在（聚合口径，与 hasAllowPolicy 同模式） */
    fun hasReplacePolicy(): Boolean {
        val c = config
        return (c.globalReplaceEnabled && c.globalReplaceImage != null) ||
                c.templates.any { it.imageId != null }
    }

    /**
     * E3 图片远程文件读取（[ReplaceImageStore] 消费，包内可见）。
     * fd 由框架托管区派发，直接读流即得密文，无 binder 1MB 限制
     */
    fun openRemoteImage(name: String): android.os.ParcelFileDescriptor? =
        runCatching { module?.openRemoteFile(name) }.getOrNull()

    /** E3：配置内全部替换图 id（预热/失效口径，[ReplaceImageStore] 消费） */
    fun activeImageIds(): Set<String> {
        val c = config
        return buildSet {
            c.templates.forEach { it.imageId?.let { id -> add(id) } }
            if (c.globalReplaceEnabled && c.globalReplaceImage != null) add(c.globalReplaceImage)
        }
    }

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
