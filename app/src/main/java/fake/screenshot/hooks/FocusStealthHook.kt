package fake.screenshot.hooks

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * E2d 焦点丢失隐瞒引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者信号链（对照 ScreenshotDetector 实测源码）：200ms 轮询自身
 * ViewRootImpl.mAttachInfo.mHasWindowFocus（activity.hasWindowFocus() +
 * ownViewRoots().any{ hasWindowFocus() }），RESUMED + 亮屏 + 持续失焦
 * （≥600ms）→ FOCUS_LOSS；悬浮窗（FLOATING_WINDOW）/自由小窗
 * （FREEFORM_WINDOW）归因共用该焦点信号。Android 12+ 该缓存由 native
 * InputDispatcher 经 input channel FocusEvent 直推（不经 Java binder
 * 方法），焦点推送在本类内唯一收口是 InputMonitor#requestFocus(token,
 * name) → mInputTransaction.setFocusedWindow——拦截必须在此或更早。
 *
 * 腿结构：
 * - C1 populateInputWindowHandle 登记：WindowState→meta 与 InputChannel
 *   token→meta 双表（requestFocus 参数是 token，需反查窗口身份；
 *   populate 循环先于焦点决策执行，表必先热）；
 * - C4 requestFocus 收口重定向（主力腿）：存在 masked 且 server 侧
 *   ActivityRecord 仍 RESUMED 的应用窗期间，一切非自身焦点请求（窃贼
 *   窗/悬浮窗/系统截图 UI/通知面板/launcher/recents 手势输入消费者
 *   token/清焦）一律重定向回该窗——纯状态判定零时窗。豁免仅 keyguard
 *   (2004)/IME(2011/2014)（解锁与键盘输入刚性依赖）。真实切走：
 *   server 先置 AR=PAUSED → 替代源消失自然放行，client 的 onPause 由
 *   同一事务队列 FIFO 送达，必然先于下一轮 200ms 轮询——检测前置
 *   条件先死。mInputFocus 记账稳定在应用窗 token，后续遍历幂等早退，
 *   无 FocusEvent；悬浮窗照常注册可触控；
 * - C2 updateInputFocusRequest：lastGood 焦点跟踪（真实焦点 =
 *   thisObject.mDisplayContent.mCurrentFocus；悬浮窗持焦期间不更新）；
 * - C3 setInputFocusLw 重定向（≤13 路径兜底）：同 C4 规则，args[0]
 *   替换为应用窗；
 * - A 腿（reportFocusChangedSerialized 吞失焦帧）：Java 派发残余的
 *   安全网；B 腿（IWindow 协议层）仅标准 AOSP 有效（ColorOS 15 的
 *   IWindow 接口面无焦点方法）。
 * 替代源：lastGoodFocus（最近一次 masked+RESUMED 焦点窗）+ populate
 * 表回落扫描。
 *
 * 信号源断开后 FOCUS_LOSS 失活（仅此：开关语义 = 每个检测项只屏蔽
 * 自身的直接信号）。检测者由焦点信号推理出的悬浮窗/小窗归因随焦点
 * 失明而失活（推理链的天然副作用，非本引擎目标）；悬浮窗的直接信号
 * 通道（触摸遮挡标志）归 E2c OverlayStealthHook，纯逻辑推断不处理。
 * 副作用：masked 应用在真实失焦期间自认有焦点（输入框光标等显示态
 * 微失真）；freeform/分屏 multi-resume 下他窗取焦被重定向（键盘不可
 * 达）——mask 语义内接受；真实切后台先 onPause，检测轮询的 RESUMED
 * 前置条件立即失效，不受影响。
 *
 * 【已知无杠杆边界（文档化接受）】
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

    /** IWindow proxy → 包名（A 腿登记 / B 腿查询；弱引用随窗口销毁清理） */
    private val clientOwners =
        Collections.synchronizedMap(WeakHashMap<Any, String>())

    fun installSystemServer(classLoader: ClassLoader) {
        // A 腿（吞失焦通知安全网）+ B 腿（IWindow 协议层，标准 AOSP）
        installFocusLeg(classLoader)
        // C 腿（输入焦点重定向主力）：FocusEvent 由 native InputDispatcher
        // 直推，拦截位为焦点迁移起点（≤13 setInputFocusLw / 15 requestFocus
        // 收口），重定向而非吞帧（保住悬浮窗触控）
        installInputFocusRedirect(classLoader)
    }

    // ==================== A 腿：失焦通知吞帧（安全网） ====================

    private fun installFocusLeg(classLoader: ClassLoader) {
        runCatching {
            val wsClass = classLoader.loadClass("com.android.server.wm.WindowState")
            getOwningPackage = methodInHierarchy(wsClass, "getOwningPackage")?.apply {
                isAccessible = true
            }
            clientField = fieldInHierarchy(wsClass, "mClient")
            // OEM 字段形变（ColorOS 15 实测 mFocused 被移除）：多候选解析；
            // 全部缺失时 hook 内回落协议参数位判定
            focusedField = fieldInHierarchy(wsClass, "mFocused")
                ?: fieldInHierarchy(wsClass, "mIsFocused")
            val reports = hierarchyOf(wsClass)
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name == "reportFocusChangedSerialized" }
                .toList()
            if (reports.isEmpty()) {
                HookContext.log(Log.WARN, "E2d focus leg abort: no dispatch method")
                return@runCatching
            }
            var hooked = 0
            reports.forEach { m ->
                val boolIdx = m.parameterTypes.indexOfFirst { it == java.lang.Boolean.TYPE }
                m.isAccessible = true
                HookContext.hookE("E2d", m).intercept { chain ->
                    // 吞掉 masked 应用的失焦派发（不 proceed）：客户端
                    // mWindowFocus 冻结 true，检测者 unfocusedPolls 不增长。
                    // 获焦帧照常放行（正常恢复同步）。主信号（FocusEvent）
                    // 已由 C 腿在源头重定向，本腿封 Java 派发残余。
                    val ws = chain.thisObject
                    registerClientOwner(ws)
                    val pkg = owningPackageOf(ws)
                    if (pkg == null || !masked(pkg)) return@intercept chain.proceed()
                    // 真实焦点判定：mFocused 字段（AOSP 标准）→ 协议参数位
                    // 回落（1 参形 boolean 即 focused）
                    val argFocused = if (boolIdx >= 0) chain.args.getOrNull(boolIdx) as? Boolean else null
                    val focusedNow = runCatching { focusedField?.getBoolean(ws) }.getOrNull()
                        ?: argFocused
                    if (focusedNow == null || focusedNow) return@intercept chain.proceed()
                    HookContext.log(Log.INFO, "E2d focus loss suppressed: $pkg")
                    // 不 proceed：整帧吞掉，失焦通知不出 system_server
                    null
                }
                hooked++
            }
            HookContext.log(
                Log.INFO,
                "E2d focus conceal hooked: $hooked " +
                        "mFocused=${focusedField != null} mClient=${clientField != null})"
            )
        }.onFailure { HookContext.log(Log.WARN, "E2d focus leg error: ${it.message}") }
        installIWindowLeg(classLoader)
    }

    // ==================== B 腿：协议层兜底（IWindow binder 面） ====================

    /**
     * IWindow$Stub$Proxy#windowFocusChanged(hasFocus, inTouchMode)。
     * aidl 生成的协议接口跨版本稳定，参数位协议级确定（首位 = hasFocus）。
     * 与 WindowState 内部签名形变完全解耦——A 腿登记归属，本层改写派发。
     * 仅标准 AOSP 有效（ColorOS 15 的 IWindow 接口面无焦点方法）。
     */
    private fun installIWindowLeg(classLoader: ClassLoader) {
        runCatching {
            val proxyClass = classLoader.loadClass("android.view.IWindow\$Stub\$Proxy")
            // OEM 协议形变：宽容匹配 FocusChanged 语义族
            val methods = proxyClass.declaredMethods
                .filter { it.name == "windowFocusChanged" }
                .ifEmpty { proxyClass.declaredMethods.filter { it.name.contains("ocusChanged", true) } }
            if (methods.isEmpty()) return
            methods.forEach { m ->
                val boolIdx = m.parameterTypes.indexOfFirst { it == java.lang.Boolean.TYPE }
                if (boolIdx < 0) return@forEach
                m.isAccessible = true
                HookContext.hookE("E2d", m).intercept { chain ->
                    val focused = chain.args.getOrNull(boolIdx) as? Boolean
                    if (focused == null || focused) return@intercept chain.proceed()
                    val pkg = clientOwners[chain.thisObject] ?: return@intercept chain.proceed()
                    if (!masked(pkg)) return@intercept chain.proceed()
                    HookContext.log(Log.INFO, "E2d focus concealed (iwindow): $pkg")
                    val patched = chain.args.toTypedArray()
                    patched[boolIdx] = true
                    chain.proceed(patched)
                }
            }
            // 小方法体高危内联（调用方 WindowState 直接内联 binder 调用），
            // 去优化强制派发路径经过 hook 入口
            HookContext.deoptimizeMethods(proxyClass, *methods.map { it.name }.toTypedArray())
            HookContext.log(
                Log.INFO,
                "E2d iwindow leg hooked: ${methods.map { it.name }.distinct()}"
            )
        }.onFailure { HookContext.log(Log.WARN, "E2d iwindow leg error: ${it.message}") }
    }

    // ==================== C 腿：输入焦点重定向（主力） ====================

    /** 窗口元数据（populate 帧登记，Z 序遍历全窗口） */
    private class HandleMeta(
        val ws: Any, val type: Int, val pkg: String?,
        val token: Any?, val winName: String?
    ) {
        val isOverlay: Boolean get() = type == 2002 || type == 2003 || type == 2038

        /** 请求时点复核：masked 开关仍开 + 应用窗 AR 仍 RESUMED */
        fun maskedResumedNow(): Boolean {
            val p = pkg ?: return false
            if (!masked(p)) return false
            return stillResumed(ws) == true
        }
    }

    /** WindowState → 元数据 */
    private val wsMeta =
        Collections.synchronizedMap(WeakHashMap<Any, HandleMeta>())

    /** InputChannel token → 元数据（C4 参数反查；弱引用随窗口销毁清理） */
    private val tokenMeta =
        Collections.synchronizedMap(WeakHashMap<Any, HandleMeta>())

    /** 最近一次非悬浮窗焦点目标（重定向的替代源） */
    @Volatile private var lastGoodFocus: HandleMeta? = null

    /** 上次重定向状态（token→替代窗；状态变化才打日志，防遍历刷屏） */
    @Volatile private var lastRedirect: Pair<Any?, HandleMeta?>? = null

    /** 高频反射缓存：类 → (字段名 → Field)（populate 每帧每窗口，禁反复查找） */
    private val fieldCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Field?>>()

    private fun cachedField(cls: Class<*>, name: String): Field? =
        fieldCache.getOrPut(cls) { ConcurrentHashMap() }.computeIfAbsent(name) {
            runCatching {
                var c: Class<*>? = cls
                while (c != null) {
                    val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
                    if (f != null) {
                        f.isAccessible = true
                        return@computeIfAbsent f
                    }
                    c = c.superclass
                }
                null
            }.getOrNull()
        }

    private fun installInputFocusRedirect(classLoader: ClassLoader) {
        var legs = 0
        runCatching {
            val imClass = classLoader.loadClass("com.android.server.wm.InputMonitor")
            // ---- C1：populate 登记（双表）----
            imClass.declaredMethods.filter { it.name == "populateInputWindowHandle" }.forEach { m ->
                m.isAccessible = true
                HookContext.hookE("E2d", m).intercept { chain ->
                    runCatching {
                        chain.args.firstOrNull {
                            it?.javaClass?.name?.endsWith("WindowState") == true
                        }?.let { metaFor(it) }
                    }
                    chain.proceed()
                }
                legs++
            }
            // ---- C2：updateInputFocusRequest（15：真实焦点在 mCurrentFocus）----
            imClass.declaredMethods
                .filter { it.name == "updateInputFocusRequest" && it.parameterCount == 1 }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E2d", m).intercept { chain ->
                        runCatching {
                            val focus = currentFocusOf(chain.thisObject)
                            if (focus != null) {
                                val meta = metaFor(focus)
                                // 仅 masked+RESUMED 时更新——否则窃贼窗
                                // （type=1 非悬浮窗）也会混入 lastGood
                                if (!meta.isOverlay && meta.maskedResumedNow()) {
                                    lastGoodFocus = meta
                                }
                            }
                        }
                        chain.proceed()
                    }
                    legs++
                }
            // ---- C4：requestFocus 收口重定向（主力腿，无条件规则）----
            // masked 应用 server 侧仍 RESUMED 期间，一切非自身焦点请求
            // （窃贼窗/输入消费者 token/清焦）一律重定向回该窗；AR=PAUSED
            // （server 权威态=真实切走）时替代源自然消失放行。豁免：
            // keyguard(2004)/IME(2011/2014)——解锁与键盘输入刚性依赖。
            val reqFocus = imClass.declaredMethods
                .filter {
                    it.name == "requestFocus" && it.parameterCount == 2 &&
                            it.parameterTypes[0].name == "android.os.IBinder"
                }
                // OEM 签名形变兜底：放宽为同名 2 参
                .ifEmpty {
                    imClass.declaredMethods.filter {
                        it.name == "requestFocus" && it.parameterCount == 2
                    }
                }
            reqFocus.forEach { m ->
                m.isAccessible = true
                HookContext.hookE("E2d", m).intercept { chain ->
                    val token = chain.args.getOrNull(0)
                    val meta = token?.let { tokenMeta[it] }
                    val good = pickSubstitute()
                    if (good == null || good.token == null || good.token == token) {
                        lastRedirect = null
                        return@intercept chain.proceed()
                    }
                    if (meta != null && (meta.pkg == good.pkg || meta.type == 2004 ||
                                meta.type == 2011 || meta.type == 2014)
                    ) {
                        lastRedirect = null
                        return@intercept chain.proceed()
                    }
                    if (lastRedirect != token to good) {
                        lastRedirect = token to good
                        HookContext.log(
                            Log.INFO,
                            "E2d focus redirected " +
                                    (meta?.let { "thief(type=${it.type} pkg=${it.pkg})" }
                                        ?: "consumer") +
                                    " -> ${good.pkg}"
                        )
                    }
                    val patched = chain.args.toTypedArray()
                    patched[0] = good.token
                    good.winName?.let { patched[1] = it }
                    chain.proceed(patched)
                }
                legs++
            }
            // ---- C3：setInputFocusLw 重定向（≤13 兜底，同 C4 规则）----
            imClass.declaredMethods.filter { it.name == "setInputFocusLw" }.forEach { m ->
                m.isAccessible = true
                HookContext.hookE("E2d", m).intercept { chain ->
                    val newWin = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                    val newMeta = metaFor(newWin)
                    val good = pickSubstitute()
                    if (good != null && good.ws !== newWin && newMeta.pkg != good.pkg &&
                        newMeta.type != 2004 && newMeta.type != 2011 && newMeta.type != 2014
                    ) {
                        HookContext.log(
                            Log.INFO,
                            "E2d setInputFocusLw redirected " +
                                    "thief(type=${newMeta.type} pkg=${newMeta.pkg}) -> ${good.pkg}"
                        )
                        val patched = chain.args.toTypedArray()
                        patched[0] = good.ws
                        return@intercept chain.proceed(patched)
                    }
                    if (!newMeta.isOverlay && newMeta.maskedResumedNow()) {
                        lastGoodFocus = newMeta
                    }
                    chain.proceed()
                }
                legs++
            }
            // 小方法体防内联（调用方 updateFocusedWindowLocked/遍历消费者）
            if (legs > 0) {
                HookContext.deoptimizeMethods(
                    imClass, "populateInputWindowHandle",
                    "updateInputFocusRequest", "setInputFocusLw", "requestFocus"
                )
            }
        }.onFailure {
            HookContext.log(Log.INFO, "E2d redirect: InputMonitor absent (${it.message})")
        }
        HookContext.log(Log.INFO, "E2d redirect legs=$legs")
    }

    /** ws→meta 幂等登记（type 解析失败时每次 populate 重试，成功即缓存） */
    private fun metaFor(ws: Any): HandleMeta {
        wsMeta[ws]?.let { if (it.type != 0) return it }
        val meta = HandleMeta(
            ws, attrsTypeOf(ws), owningPackageOf(ws), tokenOf(ws), nameOf(ws)
        )
        wsMeta[ws] = meta
        meta.token?.let { tokenMeta[it] = meta }
        return meta
    }

    /** WindowState.mInputChannelToken（requestFocus 的 token 身份源） */
    private fun tokenOf(ws: Any): Any? = runCatching {
        cachedField(ws.javaClass, "mInputChannelToken")?.get(ws)
    }.getOrNull()

    /** WindowState.getName()（requestFocus 第二参） */
    private fun nameOf(ws: Any): String? = runCatching {
        methodInHierarchy(ws.javaClass, "getName")?.invoke(ws) as? String
    }.getOrNull()

    /** InputMonitor.mDisplayContent.mCurrentFocus（15 真实焦点目标） */
    private fun currentFocusOf(monitor: Any?): Any? {
        if (monitor == null) return null
        return runCatching {
            val dc = cachedField(monitor.javaClass, "mDisplayContent")?.get(monitor)
                ?: return null
            cachedField(dc.javaClass, "mCurrentFocus")?.get(dc)
        }.getOrNull()
    }

    /** 替代源：lastGood 焦点窗（masked+RESUMED）→ wsMeta 回落扫描 */
    private fun pickSubstitute(): HandleMeta? {
        lastGoodFocus?.let { if (it.maskedResumedNow()) return it }
        return synchronized(wsMeta) {
            wsMeta.values
                .filter { !it.isOverlay && it.maskedResumedNow() }
                .lastOrNull()
        }
    }

    /** WindowState.mActivityRecord.mState == RESUMED？（null=无法判定） */
    private fun stillResumed(ws: Any): Boolean? = runCatching {
        val ar = fieldInHierarchy(ws.javaClass, "mActivityRecord")?.get(ws)
            ?: return@runCatching null
        if (ar == null) return@runCatching false // 无 AR（子窗口等）：非候选
        val state = fieldInHierarchy(ar.javaClass, "mState")?.get(ar)
            ?: return@runCatching null
        state.toString().contains("RESUMED")
    }.getOrNull()

    /** WindowState.mAttrs.type（0=不可判定） */
    private fun attrsTypeOf(ws: Any): Int = runCatching {
        val attrs = fieldInHierarchy(ws.javaClass, "mAttrs")?.get(ws) ?: return 0
        fieldInHierarchy(attrs.javaClass, "type")?.getInt(attrs) ?: 0
    }.getOrDefault(0)

    // ==================== 工具 ====================

    /** 本引擎唯一开关：焦点检测屏蔽（悬浮窗直接信号归 E2c） */
    private fun masked(pkg: String): Boolean =
        HookContext.maskFocusDetection(pkg)

    /** 焦点事件帧登记 proxy 归属（幂等；B 腿的归属表数据源） */
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
