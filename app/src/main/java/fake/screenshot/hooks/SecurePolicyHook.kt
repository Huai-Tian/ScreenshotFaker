package fake.screenshot.hooks

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.BiConsumer
import java.util.function.BiPredicate

/**
 * E1 截屏限制引擎（三态：FOLLOW 跟随应用 / ALLOW 强制允许 / DENY 强制禁止）。
 *
 * 两腿架构（对标 DFS hookSystemServer/hookPackage 实测矩阵，ColorOS 15 / A35
 * 为首验目标；DFS 无条件放行处，本引擎一律以"存在 ALLOW 态"为门控）：
 *
 * 【system_server 腿】
 * - WindowState#isSecureLocked 三态塑形（核心）：
 *   ALLOW：截屏决策路径返回 false（穿透），但 surface 创建帧豁免——窗口保留
 *   SF 层 secure 标记，MediaProjection/VD 等非授权路径仍被 SF 物理拦截。
 *   这是 DFS 的关键设计：只放开"系统截屏决策"，不做 SF 层安全降级。
 *   DENY：全调用点（含 surface 创建帧）返回 true——窗口获得 SF 层 secure
 *   标记，系统截屏被拒的同时 VD/adb 路径同步黑块，强禁止语义。
 *   FOLLOW：原样放行。
 * - DENY 主动生效（reconcile，E4 同款机制）：isSecureLocked 只在 surface
 *   创建/relayout 时被消费——模块加载前/切 DENY 前已存在的窗口（桌面是
 *   典型：开机即存在且不 relayout）的 layer secure 标记已按原生值定型，
 *   单靠 hook 不生效。因此 surface 事件帧对窗口 layer 主动打
 *   setSecure(deny || native)，配置重载时持 WM 全局锁全量遍历窗口树重算
 *   （切 DENY 即时黑块、切回即时恢复），与 isSecureLocked hook 双轨互备。
 * - 前置 deoptimize：isSecureLocked 会被 JIT 内联进 relayoutWindow 等热路径
 *   调用方，hook 入口对已内联调用点无效（DFS 实测的 A14+ 陷阱）。
 * - 捕获管线三态（WMS 截图服务路径）：ALLOW 强制捕获 secure 层 +
 *   containsSecureLayers→false（实测 ColorOS 15：保存放行，内容真实）；
 *   DENY：reconcile 兜底后可见捕获路径直接失败（null）——但 ColorOS 15
 *   截屏应用的捕获走 OEM 自有通道不经此处，DENY 的真正落闸是截屏应用
 *   腿的 containsSecureLayers→true（实测：截屏应用捕获后立即消费该判定，
 *   true 即放弃保存，MediaStore insert 不发生）；TaskSnapshot（最近任务
 *   缩略图）例外维持 secure 排除黑块（原生 FLAG_SECURE 语义，避免系统
 *   UI 异常）；FOLLOW 原生。
 * - OEM 判定（CNFE 容错，非本 ROM 静默跳过）：OneUI canBeScreenshotTarget /
 *   HyperOS notAllowCaptureDisplay / ColorOS 长截图 hasSecure（三态化）。
 * - A13- 黑屏权限改写（CAPTURE_BLACKOUT_CONTENT→READ_FRAME_BUFFER，门控）。
 *
 * 【截屏应用腿】矩阵与 DFS hookPackage 逐行对齐（A35 视角）：
 * - com.oplus.screenshot：setUid + containsSecureLayers + captureFlags
 * - com.oplus.appplatform：containsSecureLayers + captureFlags
 * - com.flyme.systemuiex：containsSecureLayers（captureFlags 仅 A13-）
 * - com.android.systemui / com.miui.screenshot：仅 A13- captureFlags
 * （A14+ SystemUI/MIUI 不装配 captureFlags：SystemUI 的任务快照等系统捕获
 * 必须维持 secure 层排除，强制捕获会把 secure 内容泄入最近任务缩略图）
 *
 * 【ColorOS 长截图】OplusLongshotMainWindow#hasSecure
 * 长截图聚合多窗口转储，此调用点拿不到单一目标归属者，无法 per-app
 * 判定——按策略聚合近似：存在任何 DENY → fail-closed 拒绝长截图；
 * 否则存在 ALLOW → 放行；否则原生。已知边界：单个应用的 DENY 会全局
 * 关闭长截图（ColorOS 专属路径，文档化接受）。
 *
 * 已知边界（DENY×ALLOW 并存的整屏捕获）：captureFlags 是 display 级
 * 而非窗口级——同屏 DENY 窗口的 SF-secure 层会随 ALLOW 的放行一并被
 * 捕获（DFS 同款全局行为，分屏混用两态时的固有粒度限制）。
 *
 * 本引擎不涉及的 DFS 同伴 hook（分期归属）：
 * registerScreenCaptureObserver/registerScreenRecordingCallback 吞噬 → E2a/E2b；
 * DisplayControl/VirtualDisplayAdapter secure 标记 → E3b（MediaProjection 域）。
 */
object SecurePolicyHook {

    /** surface 创建路径帧：必须看到原始 secure 位（豁免名单，DFS 同款） */
    private val SURFACE_CREATION_FRAMES = setOf("setInitialSurfaceControlProperties", "createSurfaceLocked")

    // ---- DENY 主动生效（reconcile）反射单点缓存 ----

    /** WindowState#isSecureLocked 原方法（bypass 通道 invoke 拿原生 secure 位） */
    private var isSecureLockedM: Method? = null
    private var getOwningPackageM: Method? = null
    private var wsSurfaceField: Field? = null
    private var getSurfaceControlM: Method? = null
    private var wsSessionField: Field? = null
    private var sessionServiceField: Field? = null
    private var wmsRootField: Field? = null
    private var wmsLockField: Field? = null
    /** RootWindowContainer#forAllWindows(callback, boolean)，callback 形参类型运行时探测 */
    private var forAllWindowsM: Method? = null
    private var txnCtor: Constructor<*>? = null
    private var txnApply: Method? = null
    private var txnClose: Method? = null
    private var txnSetSecure: Method? = null

    /** WM 全局锁与根容器实例（isSecureLocked/surface 事件帧现取缓存；遍历必须持锁） */
    @Volatile
    private var wmGlobalLock: Any? = null

    @Volatile
    private var rootContainer: Any? = null

    /** 诊断日志单次开关（refs 捕获失败 / reconcile 早退，避免高频刷屏） */
    @Volatile
    private var refsDiagLogged = false

    @Volatile
    private var reconcileDiagLogged = false

    /**
     * 原生值读取通道：reconcile 计算 target = deny || native 时，经此标志
     * 让 isSecureLocked hook 放行原方法（thread-local，reconcile 同线程
     * 开-invoke-关，无并发窗口）
     */
    private val nativeBypass = ThreadLocal.withInitial { false }

    /** 最近已应用 secure 态（按 surface 弱键去重；surface 重建后事件帧自动重打） */
    private class Applied(val pkg: String?, val secure: Boolean)

    private val applied: MutableMap<Any, Applied> =
        Collections.synchronizedMap(WeakHashMap<Any, Applied>())

    @SuppressLint("PrivateApi")
    fun installSystemServer(classLoader: ClassLoader) {
        // 前置去优化（先于 hook 装配）：强制调用方解释执行，使后续 isSecureLocked
        // 调用经过 hook 入口。逐项容错——OEM 类名差异不阻断主 hook
        runCatching { deoptimizeInlineCallers(classLoader) }
            .onFailure { HookContext.log(Log.WARN, "E1 deoptimize partial failure: ${it.message}") }

        // ---- 核心：isSecureLocked 三态 ----
        try {
            val wsClass = classLoader.loadClass("com.android.server.wm.WindowState")
            val getOwningPackage = wsClass.getDeclaredMethod("getOwningPackage")
                .apply { isAccessible = true }
            getOwningPackageM = getOwningPackage
            isSecureLockedM = wsClass.getDeclaredMethod("isSecureLocked").apply { isAccessible = true }
            val systemCl = wsClass.classLoader
            HookContext.hookE("E1", isSecureLockedM!!).intercept { chain ->
                // 顺带捕获 WMS 根容器/全局锁引用（全量 reconcile 依赖）。
                // isSecureLocked 调用高频且必经（截屏判定/relayout/snapshot），
                // 比依赖 surface 事件 hook 帧捕获更可靠；rootContainer 已
                // 缓存时此调用为单 volatile 读，零开销
                runCatching { captureWmsRefs(chain.thisObject) }
                if (nativeBypass.get()) {
                    // reconcile 的原生值读取：不塑形
                    chain.proceed()
                } else when (HookContext.screenshotPolicy(ownerPackage(chain.thisObject, getOwningPackage))) {
                    HookConfig.SECURE_ALLOW ->
                        if (inSurfaceCreationFrame(systemCl)) chain.proceed() else false
                    HookConfig.SECURE_DENY -> true
                    else -> chain.proceed()
                }
            }
            HookContext.log(Log.INFO, "E1 isSecureLocked hooked (3-state)")
        } catch (t: Throwable) {
            HookContext.log(Log.ERROR, "E1 hook isSecureLocked failed", t)
        }

        // ---- DENY 主动生效：surface 事件 reconcile（失败不阻断其余 hook）----
        installCatching("system", "denyReconcile") { installDenyReconcile(classLoader) }

        // ---- WMS 截图服务路径捕获放行（门控 ALLOW）----
        installCatching("system", "captureFlags") { hookCaptureFlags(classLoader) }
        installCatching("system", "containsSecureLayers") { hookContainsSecureLayers(classLoader) }

        // ---- A13- 黑屏权限改写（S~T 的截屏拒绝路径，门控 ALLOW）----
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            installCatching("system", "blackoutPermission") { hookBlackoutPermission(classLoader) }
        }

        // ---- OEM 判定（CNFE 容错：非本 ROM 静默跳过）----
        installCatching("system", "oneUI") { hookOneUI(classLoader) }
        installCatching("system", "hyperOS") { hookHyperOS(classLoader) }
        installCatching("system", "oplusLongshot") { hookOplusLongshot(classLoader) }
    }

    /**
     * 截屏应用进程装配。矩阵（DFS hookPackage 逐行对齐，A35 视角）：
     * - com.oplus.screenshot：setUid + containsSecureLayers + captureFlags
     * - com.oplus.appplatform：containsSecureLayers + captureFlags
     * - com.flyme.systemuiex：containsSecureLayers（captureFlags 仅 A13-）
     * - com.android.systemui / com.miui.screenshot：仅 A13- captureFlags
     */
    fun installScreenshotApp(packageName: String, classLoader: ClassLoader) {
        val isOplus = packageName == "com.oplus.screenshot" || packageName == "com.oplus.appplatform"
        val isFlyme = packageName == "com.flyme.systemuiex"
        if (!isOplus && !isFlyme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        if (isOplus || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            installCatching(packageName, "captureFlags") { hookCaptureFlags(classLoader) }
        }
        if (isOplus || isFlyme) {
            installCatching(packageName, "containsSecureLayers") { hookContainsSecureLayers(classLoader) }
        }
        if (packageName == "com.oplus.screenshot" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        ) {
            installCatching(packageName, "oplusSetUid") { hookOplusScreenCapture(classLoader) }
        }
    }

    /** DFS 同粒度容错：类不存在（CNFE）= 版本矩阵不适用，静默；其余记日志 */
    private inline fun installCatching(pkg: String, what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t !is ClassNotFoundException) {
                HookContext.log(Log.ERROR, "E1 $what hook failed for $pkg", t)
            }
        }
    }

    // ==================== system_server 侧实现 ====================

    /**
     * JIT 内联调用方去优化（DFS 实测清单）：surface 创建/relayout 热路径
     * 与截屏判定的 lambda（合成类名，A14+ 由 d8 生成 ExternalSyntheticLambda
     * /匿名内部类两种形态）。捕获路径的 lambda 逐索引探测（0..19），类型
     * 不匹配静默跳过。
     */
    @SuppressLint("PrivateApi")
    private fun deoptimizeInlineCallers(classLoader: ClassLoader) {
        HookContext.deoptimizeMethods(
            classLoader.loadClass("com.android.server.wm.WindowStateAnimator"), "createSurfaceLocked"
        )
        HookContext.deoptimizeMethods(
            classLoader.loadClass("com.android.server.wm.WindowManagerService"), "relayoutWindow"
        )
        for (i in 0 until 20) {
            runCatching {
                val c = classLoader.loadClass("com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$i")
                if (BiConsumer::class.java.isAssignableFrom(c)) HookContext.deoptimizeMethods(c, "accept")
            }
            runCatching {
                val c = classLoader.loadClass("com.android.server.wm.DisplayContent\$$i")
                if (BiPredicate::class.java.isAssignableFrom(c)) HookContext.deoptimizeMethods(c, "test")
            }
        }
    }

    /** 窗口归属包名（反射单点缓存 invoke；失败按未配置处理→回落全局态） */
    private fun ownerPackage(thisObject: Any?, getOwningPackage: Method): String? =
        runCatching { getOwningPackage.invoke(thisObject) as? String }.getOrNull()

    /**
     * 当前调用是否来自 surface 创建路径（豁免判定，仅 ALLOW 态使用）。
     * A14+ StackWalker（RETAIN_CLASS_REFERENCE 直取 ClassLoader 比对），
     * A13- 回落 Throwable stackTrace（类加载后比对——系统类由引导/系统
     * ClassLoader 加载，loadClass 可解析）。
     */
    private fun inSurfaceCreationFrame(systemCl: ClassLoader): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
                frames.anyMatch {
                    it.declaringClass.classLoader == systemCl && it.methodName in SURFACE_CREATION_FRAMES
                }
            }
        }
        return runCatching {
            Throwable().stackTrace.any {
                it.methodName in SURFACE_CREATION_FRAMES &&
                        runCatching { Class.forName(it.className, false, systemCl).classLoader == systemCl }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    // ==================== DENY 主动生效（reconcile） ====================

    /**
     * 装配 surface 事件 reconcile（E4 同款双 hook 点）：
     * - WindowState#prepareWindowToDisplayDuringRelayout（A12+，每次 relayout）
     * - WindowStateAnimator#createSurfaceLocked（A11 兜底 + surface 重建）
     * after 阶段按 [reconcileWindow] 重算窗口 layer 的 secure 位；配置重载
     * 监听全量重算（切 DENY/切回即时生效，覆盖"桌面等不 relayout 的存量窗口"）。
     */
    @SuppressLint("PrivateApi")
    private fun installDenyReconcile(classLoader: ClassLoader) {
        val wsClass = classLoader.loadClass("com.android.server.wm.WindowState")
        val wsaClass = classLoader.loadClass("com.android.server.wm.WindowStateAnimator")
        val scClass = classLoader.loadClass("android.view.SurfaceControl")
        val txnClass = classLoader.loadClass("android.view.SurfaceControl\$Transaction")

        wsSurfaceField = runCatching {
            wsClass.getDeclaredField("mSurfaceControl").apply { isAccessible = true }
        }.getOrNull()
        // 兜底通道：surface 存于 WindowContainer 基类或 OEM 改名时经方法取
        getSurfaceControlM = methodInHierarchy(wsClass, "getSurfaceControl")
        wsSessionField = fieldInHierarchy(wsClass, "mSession")
        sessionServiceField = wsSessionField?.type?.let { fieldInHierarchy(it, "mService") }
        wmsRootField = sessionServiceField?.type?.let { fieldInHierarchy(it, "mRoot") }
        wmsLockField = sessionServiceField?.type?.let { fieldInHierarchy(it, "mGlobalLock") }

        // WindowContainer#forAllWindows(callback, boolean)：callback 形参类型
        // 跨版本漂移且存在 ToBooleanFunction/Predicate 双重载，按签名收集取首。
        // 解析失败仅损失"重载全量遍历"能力（存量窗口需等下次 relayout 事件），
        // 事件帧单窗口 reconcile 不受影响，不作为装配终止条件
        wmsRootField?.type?.let { rootType ->
            runCatching {
                var c: Class<*>? = rootType
                while (c != null && forAllWindowsM == null) {
                    c.declaredMethods.firstOrNull {
                        it.name == "forAllWindows" && it.parameterCount == 2 &&
                                it.parameterTypes[1] == java.lang.Boolean.TYPE
                    }?.apply {
                        isAccessible = true
                        forAllWindowsM = this
                    }
                    c = c.superclass
                }
            }
        }

        txnCtor = txnClass.getDeclaredConstructor().apply { isAccessible = true }
        txnApply = methodInHierarchy(txnClass, "apply")
        txnClose = methodInHierarchy(txnClass, "close")
        txnSetSecure = methodInHierarchy(txnClass, "setSecure", scClass, java.lang.Boolean.TYPE)

        var hooked = false
        // A12+ 主路径：relayout 后 surface 就绪
        methodInHierarchy(wsClass, "prepareWindowToDisplayDuringRelayout", java.lang.Boolean.TYPE)?.let { m ->
            HookContext.hookE("E1", m).intercept { chain ->
                val result = chain.proceed()
                runCatching { captureWmsRefs(chain.thisObject) }
                reconcileWindow(chain.thisObject)
                result
            }
            hooked = true
        }
        // A11 兜底 + surface 重建
        methodInHierarchy(wsaClass, "createSurfaceLocked")?.let { m ->
            val wsaWin = fieldInHierarchy(wsaClass, "mWin")
            HookContext.hookE("E1", m).intercept { chain ->
                val result = chain.proceed()
                val win = runCatching { wsaWin?.get(chain.thisObject) }.getOrNull()
                runCatching { captureWmsRefs(win) }
                reconcileWindow(win)
                result
            }
            hooked = true
        }

        HookContext.addConfigReloadListener(::reconcileAll)
        HookContext.log(
            Log.INFO,
            "E1 denyReconcile installed (hooked=$hooked, txn=${txnSetSecure != null}, " +
                    "forAll=${forAllWindowsM != null}, lock=${wmsLockField != null})"
        )
    }

    /**
     * 单窗口重算：target = DENY || 原生 secure 位。置 false 与置 true 同等
     * 一等公民——DENY→FOLLOW 切换经 [reconcileAll] 即时恢复原生。
     * 必须在 WM 全局锁内调用（surface 事件帧天然持锁；重载监听器显式加锁）。
     * 返回是否新打了 DENY 标记（全量遍历的统计口径）。
     */
    private fun reconcileWindow(ws: Any?): Boolean {
        if (ws == null || (wsSurfaceField == null && getSurfaceControlM == null)) {
            if (!reconcileDiagLogged) {
                reconcileDiagLogged = true
                HookContext.log(Log.WARN, "E1 denyReconcile surface accessor unavailable")
            }
            return false
        }
        val pkg = runCatching { getOwningPackageM?.invoke(ws) as? String }.getOrNull()
        val deny = HookContext.screenshotPolicy(pkg) == HookConfig.SECURE_DENY
        val target = deny || nativeSecureOf(ws)
        val sc = surfaceOf(ws) ?: run {
            if (deny && !reconcileDiagLogged) {
                reconcileDiagLogged = true
                HookContext.log(Log.WARN, "E1 denyReconcile surface null for ${pkg ?: "(system)"}")
            }
            return false
        }
        val cur = applied[sc]
        if (cur != null && cur.pkg == pkg && cur.secure == target) return false
        if (applySecureTransaction(sc, target)) {
            applied[sc] = Applied(pkg, target)
            if (deny) HookContext.log(Log.INFO, "E1 deny marked secure: ${pkg ?: "(system)"}")
            return deny
        }
        return false
    }

    /** 窗口 surface 获取双通道：WindowState.mSurfaceControl 字段 → getSurfaceControl() 方法兜底 */
    private fun surfaceOf(ws: Any): Any? =
        runCatching { wsSurfaceField?.get(ws) ?: getSurfaceControlM?.invoke(ws) }.getOrNull()

    /** 原生 secure 位读取：bypass 通道 invoke 原 isSecureLocked（见 nativeBypass 注释） */
    private fun nativeSecureOf(ws: Any): Boolean {
        val m = isSecureLockedM ?: return false
        nativeBypass.set(true)
        return try {
            runCatching { m.invoke(ws) as? Boolean }.getOrNull() ?: false
        } finally {
            nativeBypass.set(false)
        }
    }

    /**
     * 全量窗口树重算（存量窗口——桌面/后台任务——的 secure 位只有这里能
     * 触及；applied 缓存只覆盖打过的，因此必须全量遍历而非仅缓存）。
     * 触发点：配置重载监听 + DENY 捕获前兜底（[hookCaptureFlags]）。
     * 遍历持 WM 全局锁（与 WMS 内部协议一致，不嵌套其他锁，无死锁面；
     * 锁缺失则放弃——无锁遍历窗口树有并发修改风险）。
     */
    private fun reconcileAll() {
        val root = rootContainer
        val forAll = forAllWindowsM
        val lock = wmGlobalLock
        if (root == null || forAll == null || lock == null) {
            if (!reconcileDiagLogged) {
                reconcileDiagLogged = true
                HookContext.log(
                    Log.WARN,
                    "E1 denyReconcile skipped: root=${root != null} " +
                            "forAll=${forAll != null} lock=${lock != null}"
                )
            }
            return
        }
        runCatching {
            var marked = 0
            synchronized(lock) {
                val cbType = forAll.parameterTypes[0]
                val visitor = Proxy.newProxyInstance(
                    cbType.classLoader, arrayOf(cbType)
                ) { _, m, args ->
                    // 仅回调方法（单参 = WindowState）触发重算；恒 false = 继续遍历
                    if (m.parameterCount == 1 && reconcileWindow(args?.firstOrNull())) marked++
                    if (m.returnType == java.lang.Boolean::class.javaPrimitiveType ||
                        m.returnType == java.lang.Boolean::class.java
                    ) java.lang.Boolean.FALSE else null
                }
                forAll.invoke(root, visitor, true)
            }
            if (marked > 0) HookContext.log(Log.INFO, "E1 denyReconcile full pass: $marked windows marked")
        }.onFailure { HookContext.log(Log.WARN, "E1 denyReconcile reload failed: ${it.message}") }
    }

    /**
     * 从窗口实例沿 mSession→mService 捕获 WMS 根容器与全局锁（一次性缓存）。
     * 失败单次日志（字段路径为静态解析结果，重试无意义）；成功静默。
     */
    private fun captureWmsRefs(ws: Any?) {
        if (ws == null || rootContainer != null) return
        runCatching {
            val session = wsSessionField?.get(ws)
            val service = session?.let { sessionServiceField?.get(it) }
            val root = service?.let { wmsRootField?.get(it) }
            val lock = service?.let { wmsLockField?.get(it) }
            if (root != null && lock != null) {
                rootContainer = root
                wmGlobalLock = lock
            } else if (!refsDiagLogged) {
                refsDiagLogged = true
                HookContext.log(
                    Log.WARN,
                    "E1 denyReconcile refs capture failed: session=${session != null} " +
                            "service=${service != null} root=${root != null} lock=${lock != null}"
                )
            }
        }
    }

    /** 提交单 setSecure 事务（WMS 在锁内 apply 事务是常规操作） */
    private fun applySecureTransaction(sc: Any, secure: Boolean): Boolean {
        val ctor = txnCtor ?: return false
        val setSecure = txnSetSecure ?: return false
        val applyM = txnApply ?: return false
        var txn: Any? = null
        return try {
            txn = ctor.newInstance()
            setSecure.invoke(txn, sc, secure)
            applyM.invoke(txn)
            true
        } catch (t: Throwable) {
            HookContext.log(Log.WARN, "E1 denyReconcile apply failed: ${t.message}")
            false
        } finally {
            runCatching { txnClose?.invoke(txn) }
        }
    }

    /** A13- 黑屏权限检查改写：CAPTURE_BLACKOUT_CONTENT 检查按 READ_FRAME_BUFFER 放行 */
    @SuppressLint("PrivateApi")
    private fun hookBlackoutPermission(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.am.ActivityManagerService")
        val method = clazz.getDeclaredMethod(
            "checkPermission", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        HookContext.hookE("E1", method).intercept { chain ->
            if (chain.args[0] != "android.permission.CAPTURE_BLACKOUT_CONTENT" || !HookContext.hasAllowPolicy()) {
                chain.proceed()
            } else {
                val args = chain.args.toTypedArray()
                args[0] = "android.permission.READ_FRAME_BUFFER"
                chain.proceed(args)
            }
        }
    }

    /** OneUI 截图目标判定（WmScreenshotController#canBeScreenshotTarget→true，门控） */
    @SuppressLint("PrivateApi")
    private fun hookOneUI(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.wm.WmScreenshotController")
        clazz.declaredMethods.filter { it.name == "canBeScreenshotTarget" }.forEach { method ->
            HookContext.hookE("E1", method).intercept { chain ->
                if (HookContext.hasAllowPolicy()) true else chain.proceed()
            }
        }
    }

    /** HyperOS 截屏禁止判定（WindowManagerServiceImpl#notAllowCaptureDisplay→false，门控） */
    @SuppressLint("PrivateApi")
    private fun hookHyperOS(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl")
        clazz.declaredMethods.filter { it.name == "notAllowCaptureDisplay" }.forEach { method ->
            HookContext.hookE("E1", method).intercept { chain ->
                if (HookContext.hasAllowPolicy()) false else chain.proceed()
            }
        }
    }

    /** ColorOS 长截图判定（hasSecure 三态聚合，见类注释） */
    @SuppressLint("PrivateApi")
    private fun hookOplusLongshot(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.wm.OplusLongshotMainWindow")
        clazz.declaredMethods.filter { it.name == "hasSecure" }.forEach { method ->
            HookContext.hookE("E1", method).intercept { chain ->
                when {
                    HookContext.hasDenyPolicy() -> true
                    HookContext.hasAllowPolicy() -> false
                    else -> chain.proceed()
                }
            }
        }
        HookContext.log(Log.INFO, "E1 oplus longshot hooked")
    }

    // ==================== 两进程共享的捕获管线三态实现 ====================

    /**
     * nativeCaptureDisplay / nativeCaptureLayers 三态：
     * ALLOW 强制捕获 secure 层；DENY：
     * 1) system_server 侧先做全量 reconcile 兜底（applied 空时才遍历——
     *    覆盖"切 DENY 前已存在的存量窗口"与配置监听链路断裂两种情形），
     *    使 SF 层 secure 标记在捕获读取前落位（MediaProjection/adb 物理拦截）；
     * 2) 可见的截屏捕获路径直接失败（null）；TaskSnapshot（最近任务缩略图）
     *    例外——维持 secure 排除黑块（原生 FLAG_SECURE 语义）。
     *    实测 ColorOS 15：截屏应用的捕获走 OEM 自有通道，本 hook 不触发
     *    ——DENY 的真正落闸在截屏应用腿的 [hookContainsSecureLayers]；
     *    null 路径是 AOSP 系截屏应用的兜底。
     * FOLLOW 原生。
     */
    @SuppressLint("PrivateApi")
    private fun hookCaptureFlags(classLoader: ClassLoader) {
        // Baklava 36.1+ 类更名 ScreenCaptureInternal 且字段语义变为 int 策略
        // （1=ALLOW 捕获 / 2=DISALLOW 黑块，AOSP ScreenCapture 定义）；
        // 纯 36.0 仍用 ScreenCapture/mCaptureSecureLayers（DFS 的 SDK_INT_FULL 判定）
        val baklava1 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        val screenCaptureClazz: Class<*>
        val captureArgsClazz: Class<*>
        val fieldName: String
        when {
            baklava1 -> {
                screenCaptureClazz = classLoader.loadClass("android.window.ScreenCaptureInternal")
                captureArgsClazz = classLoader.loadClass($$"android.window.ScreenCaptureInternal$CaptureArgs")
                fieldName = "mSecureContentPolicy"
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                screenCaptureClazz = classLoader.loadClass("android.window.ScreenCapture")
                captureArgsClazz = classLoader.loadClass($$"android.window.ScreenCapture$CaptureArgs")
                fieldName = "mCaptureSecureLayers"
            }
            else -> {
                screenCaptureClazz = classLoader.loadClass("android.view.SurfaceControl")
                captureArgsClazz = classLoader.loadClass($$"android.view.SurfaceControl$CaptureArgs")
                fieldName = "mCaptureSecureLayers"
            }
        }
        val field = captureArgsClazz.getDeclaredField(fieldName).apply { isAccessible = true }

        for (name in listOf("nativeCaptureDisplay", "nativeCaptureLayers")) {
            screenCaptureClazz.declaredMethods.filter { it.name == name }.forEach { method ->
                HookContext.hookE("E1", method).intercept { chain ->
                    // deny 优先：两态并存时 fail-closed（display 级粒度已知边界）
                    val allow = HookContext.hasAllowPolicy() && !HookContext.hasDenyPolicy()
                    val deny = HookContext.hasDenyPolicy()
                    when {
                        !allow && !deny -> chain.proceed()
                        deny -> {
                            // 全量 reconcile 兜底：仅 system_server 且尚未标记任何
                            // 窗口时（applied 为空）遍历——覆盖存量窗口与监听链路
                            // 断裂；此后由 applied 缓存去重，常规捕获零遍历开销
                            if (HookContext.kind == HookContext.ProcessKind.SYSTEM_SERVER &&
                                applied.isEmpty()
                            ) {
                                runCatching { reconcileAll() }
                            }
                            if (!method.returnType.isPrimitive && !isTaskSnapshotFrame()) {
                                HookContext.log(Log.INFO, "E1 capture denied ($name)")
                                null
                            } else {
                                // TaskSnapshot（最近任务缩略图）/ 原始返回类型
                                // （老版本 native）：secure 层排除 → 黑块
                                for (arg in chain.args) {
                                    if (captureArgsClazz.isInstance(arg)) {
                                        if (baklava1) field.setInt(arg, 2)
                                        else field.setBoolean(arg, false)
                                    }
                                }
                                chain.proceed()
                            }
                        }
                        else -> {
                            // CaptureArgs 按类型扫描参数位（不假设固定下标，native 签名随版本漂移）
                            var hit = false
                            for (arg in chain.args) {
                                if (captureArgsClazz.isInstance(arg)) {
                                    if (baklava1) field.setInt(arg, 1)
                                    else field.setBoolean(arg, true)
                                    hit = true
                                }
                            }
                            if (hit) HookContext.log(Log.INFO, "E1 capture secure layers forced")
                            chain.proceed()
                        }
                    }
                }
            }
        }
        HookContext.log(Log.INFO, "E1 captureFlags hooked (${captureArgsClazz.name})")
    }

    /** 最近任务缩略图捕获帧判定（DENY 例外路径：黑块而非失败，维持原生语义） */
    private fun isTaskSnapshotFrame(): Boolean = runCatching {
        // Throwable 栈扫描（API 1+）：StackWalker 在 Android 平台 API 34 才
        // 引入（minSdk 30 不可用）；DENY 捕获为每次截屏一次的低频路径，
        // 栈填充开销可忽略
        Throwable().stackTrace.any { it.className.contains("TaskSnapshot") }
    }.getOrDefault(false)

    /**
     * 结果缓冲区 containsSecureLayers 三态（实测 ColorOS 15：此判定是 DENY
     * 的最终落闸点——截屏应用捕获后立即消费它，true 即放弃保存，MediaStore
     * insert 不发生；false 才走保存流程）。DENY→true（deny 优先 fail-closed，
     * 与捕获管线一致——ALLOW×DENY 并存时缓冲区可能已含 DENY 内容，必须
     * 拒绝保存兜底）；ALLOW→false（强制捕获的 secure 内容不再被判含安全层，
     * 保存放行）；FOLLOW 原生。
     */
    private fun hookContainsSecureLayers(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                $$"android.window.ScreenCapture$ScreenshotHardwareBuffer"
            else $$"android.view.SurfaceControl$ScreenshotHardwareBuffer"
        )
        clazz.declaredMethods.filter { it.name == "containsSecureLayers" }.forEach { method ->
            HookContext.hookE("E1", method).intercept { chain ->
                when {
                    HookContext.hasDenyPolicy() -> true
                    HookContext.hasAllowPolicy() -> false
                    else -> chain.proceed()
                }
            }
        }
        HookContext.log(Log.INFO, "E1 containsSecureLayers hooked")
    }

    /**
     * ColorOS 截屏应用自有目标 uid 安全检查旁路（A15+）：纯 ALLOW 态改写
     * -1 使捕获获特权（SF 含 secure 层 → 真实内容，DFS 同款）。DENY 态
     * 存在时保持原值——特权捕获会把 DENY 窗口的 secure 内容拉进缓冲区，
     * 宁可黑块（与捕获管线 allow = allow && !deny 口径一致）。
     */
    private fun hookOplusScreenCapture(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass($$"com.oplus.screenshot.OplusScreenCapture$CaptureArgs$Builder")
        val method = clazz.getDeclaredMethod("setUid", java.lang.Long.TYPE)
        HookContext.hookE("E1", method).intercept { chain ->
            if (HookContext.hasAllowPolicy() && !HookContext.hasDenyPolicy()) {
                chain.proceed(arrayOf<Any>(-1L))
            } else {
                chain.proceed()
            }
        }
        HookContext.log(Log.INFO, "E1 oplus setUid hooked")
    }

    // ==================== 反射工具（reconcile 专用） ====================

    /** 沿类层级找声明方法（WindowState 的部分方法/字段声明在基类 WindowContainer） */
    private fun methodInHierarchy(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = runCatching { c.getDeclaredMethod(name, *params) }.getOrNull()
            if (m != null) {
                m.isAccessible = true
                return m
            }
            c = c.superclass
        }
        return null
    }

    private fun fieldInHierarchy(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }
}
