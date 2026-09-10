package fake.screenshot.hooks

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * E2d 焦点/遮挡检测隐身引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者的悬浮窗感知通道（对照 ScreenshotDetector 实测源码）：
 *
 * 【通道 1：窗口焦点丢失轮询（主归因通道，本引擎目标）】
 * 检测者以 200ms 轮询 hasWindowFocus()：RESUMED + 亮屏 + 持续失焦
 * （≥600ms）→ FOCUS_LOSS，再经用量统计归因为 FLOATING_WINDOW（悬浮
 * 窗）/ FREEFORM_WINDOW（自由小窗）。焦点信号链（AOSP 栈帧实证）：
 * WindowState#reportFocusChangedSerialized(focused, inTouchMode) →
 * IWindow$Stub$Proxy#windowFocusChanged → binder → 检测者进程
 * ViewRootImpl.mHasWindowFocus。
 *
 * ColorOS 15 形变：reportFocusChangedSerialized 仅 1 参（OEM 改签名，
 * 参数位含义不明——上代仅靠猜参数位改写实测无效）。双保险：
 * - A（WindowState 层）：以 mFocused 成员字段判定真实失焦，proceed
 *   前临时翻转字段（派发若读字段则生效）+ 全 boolean 参数位改 true
 *   （派发若读参数则生效），finally 恢复字段——两种取值路径全覆盖；
 * - B（IWindow 协议层，兜底主力）：IWindow.aidl 是跨版本稳定协议接口
 *   （windowFocusChanged(hasFocus, inTouchMode)，OEM 不改 framework
 *   公开 binder 面），hook Stub$Proxy 派发点按协议首位改写 hasFocus，
 *   与 WindowState 内部实现解耦。归属表：A 层每次焦点事件登记
 *   mClient(proxy) → 包名，B 层查表定位 masked。
 *
 * 信号源断开后：FOCUS_LOSS / FLOATING_WINDOW / FREEFORM_WINDOW 全链
 * 路失活（检测者的三个检测项共享焦点轮询信号）。
 * 副作用：masked 应用在真实失焦期间自认有焦点（输入框光标等显示态
 * 微失真）——mask 语义内的可接受代价；真实切后台先 onPause，检测
 * 轮询的 RESUMED 前置条件立即失效，不受影响。
 *
 * 【已知无杠杆边界（文档化接受）】
 * - FLAG_WINDOW_IS_OBSCURED / PARTIALLY_OBSCURED：InputDispatcher
 *   native 层计算（Java hook 不可达；检测者触摸时才消费，非轮询）；
 * - 无障碍窗口快照（需检测者自启无障碍服务，罕见）；
 * - TrustedPresentation：检测器未使用此 API（实测源码确认），不设腿。
 */
object FocusStealthHook {

    // ---- 反射单点缓存 ----

    /** WindowState#getOwningPackage */
    private var getOwningPackage: Method? = null

    /** WindowState#mClient（IWindow binder proxy，协议层归属表的 key） */
    private var clientField: Field? = null

    /** WindowState#mFocused（AOSP 标准字段，真实焦点态的可靠读源） */
    private var focusedField: Field? = null

    /** IWindow proxy → 包名（A 层登记 / B 层查询；弱引用随窗口销毁清理） */
    private val clientOwners =
        Collections.synchronizedMap(WeakHashMap<Any, String>())

    /** 一次性诊断（签名 + 首次事件快照，后续静默） */
    private var focusDiagLogged = false

    fun installSystemServer(classLoader: ClassLoader) {
        installFocusLeg(classLoader)
    }

    // ==================== 焦点丢失隐瞒 ====================

    private fun installFocusLeg(classLoader: ClassLoader) {
        runCatching {
            val wsClass = classLoader.loadClass("com.android.server.wm.WindowState")
            getOwningPackage = methodInHierarchy(wsClass, "getOwningPackage")?.apply {
                isAccessible = true
            }
            clientField = fieldInHierarchy(wsClass, "mClient")
            focusedField = fieldInHierarchy(wsClass, "mFocused")
            val reports = hierarchyOf(wsClass)
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name == "reportFocusChangedSerialized" }
                .toList()
            if (reports.isEmpty()) {
                // OEM 改名校准弹药：WindowState + WMS 侧焦点派发痕迹
                val trace = listOf(wsClass.name, "com.android.server.wm.WindowManagerService")
                    .mapNotNull { runCatching { classLoader.loadClass(it) }.getOrNull() }
                    .flatMap { hierarchyOf(it) }
                    .flatMap { it.declaredMethods.toList() }
                    .filter { it.name.contains("FocusChanged", true) }
                    .joinToString { "${it.name}(${it.parameterCount})" }
                HookContext.log(Log.WARN, "E2d focus leg abort: $trace")
                return@runCatching
            }
            var hooked = 0
            reports.forEach { m ->
                val boolIdx = m.parameterTypes.indexOfFirst { it == java.lang.Boolean.TYPE }
                m.isAccessible = true
                HookContext.hookE("E2d", m).intercept { chain ->
                    // A 层：登记归属 + 字段/参数双路改写（见类注释）
                    val ws = chain.thisObject
                    registerClientOwner(ws)
                    val pkg = owningPackageOf(ws)
                    if (pkg == null || !masked(pkg)) return@intercept chain.proceed()
                    val focusedNow = runCatching { focusedField?.getBoolean(ws) }.getOrNull()
                    if (focusedNow == null || focusedNow) return@intercept chain.proceed()
                    if (!focusDiagLogged) {
                        focusDiagLogged = true
                        HookContext.log(
                            Log.INFO,
                            "E2d focus leg diag: ${m.name}(${m.parameterCount}) " +
                                    "args=${chain.args} mFocused=$focusedNow " +
                                    "mClient=${clientField != null}"
                        )
                    }
                    HookContext.log(Log.INFO, "E2d focus loss concealed: $pkg")
                    focusedField?.let { f -> runCatching { f.set(ws, true) } }
                    try {
                        val patched = chain.args.toTypedArray()
                        if (boolIdx >= 0) patched[boolIdx] = true
                        chain.proceed(patched)
                    } finally {
                        focusedField?.let { f -> runCatching { f.set(ws, false) } }
                    }
                }
                hooked++
            }
            HookContext.log(
                Log.INFO,
                "E2d focus conceal hooked: $hooked " +
                        "(${reports.joinToString { "${it.name}(${it.parameterCount})" }} " +
                        "mFocused=${focusedField != null} mClient=${clientField != null})"
            )
        }.onFailure { HookContext.log(Log.WARN, "E2d focus leg error: ${it.message}") }
        installIWindowLeg(classLoader)
    }

    // ==================== 协议层兜底（IWindow binder 面） ====================

    /**
     * B 层：IWindow$Stub$Proxy#windowFocusChanged(hasFocus, inTouchMode)。
     * aidl 生成的协议接口跨版本稳定，参数位协议级确定（首位 = hasFocus）。
     * 与 WindowState 内部签名形变完全解耦——A 层登记归属，本层改写派发。
     */
    private fun installIWindowLeg(classLoader: ClassLoader) {
        runCatching {
            val proxyClass = classLoader.loadClass("android.view.IWindow\$Stub\$Proxy")
            val methods = proxyClass.declaredMethods.filter { it.name == "windowFocusChanged" }
            if (methods.isEmpty()) {
                HookContext.log(Log.WARN, "E2d iwindow leg abort: no windowFocusChanged")
                return
            }
            methods.forEach { m ->
                m.isAccessible = true
                HookContext.hookE("E2d", m).intercept { chain ->
                    val focused = chain.args.getOrNull(0) as? Boolean
                    if (focused == null || focused) return@intercept chain.proceed()
                    val pkg = clientOwners[chain.thisObject] ?: return@intercept chain.proceed()
                    if (!masked(pkg)) return@intercept chain.proceed()
                    HookContext.log(Log.INFO, "E2d focus concealed (iwindow): $pkg")
                    val patched = chain.args.toTypedArray()
                    patched[0] = true
                    chain.proceed(patched)
                }
            }
            // 小方法体高危内联（调用方 WindowState 直接内联 binder 调用），
            // 去优化强制派发路径经过 hook 入口
            HookContext.deoptimizeMethods(proxyClass, "windowFocusChanged")
            HookContext.log(Log.INFO, "E2d iwindow leg hooked: ${methods.size}")
        }.onFailure { HookContext.log(Log.WARN, "E2d iwindow leg error: ${it.message}") }
    }

    // ==================== 工具 ====================

    /** FOCUS_LOSS 与悬浮窗/小窗归因共享信号源：任一开关启用即隐瞒 */
    private fun masked(pkg: String): Boolean =
        HookContext.maskFocusDetection(pkg) || HookContext.maskOverlayDetection(pkg)

    /** 焦点事件帧登记 proxy 归属（幂等；B 层的归属表数据源） */
    private fun registerClientOwner(ws: Any?) {
        if (ws == null) return
        val f = clientField ?: return
        runCatching {
            val client = f.get(ws) ?: return
            val pkg = owningPackageOf(ws) ?: return
            clientOwners[client] = pkg
        }
    }

    private fun owningPackageOf(ws: Any?): String? =
        runCatching { getOwningPackage?.invoke(ws) as? String }.getOrNull()

    private fun hierarchyOf(cls: Class<*>): Sequence<Class<*>> =
        generateSequence(cls) { it.superclass }

    private fun methodInHierarchy(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = runCatching { c!!.getDeclaredMethod(name, *params) }.getOrNull()
            if (m != null) return m
            c = c.superclass
        }
        return null
    }

    private fun fieldInHierarchy(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }
}
