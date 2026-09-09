package fake.screenshot.hooks

import android.os.Bundle
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * E4 自由浮窗穿透引擎（纯 system_server，被穿透 app 零注入）。
 *
 * 机制：被配置应用的浮窗在 surface 就绪时对 SurfaceControl 打
 * skipScreenshot 标记：SF 捕获遍历剔除该 layer 子树，系统截图 /
 * MediaProjection / adb 特权捕获全部穿透（露出下层内容），截屏操作
 * 本身与窗口在屏显示零干扰。SF 只认 layer flag 不关心设置者，因此在
 * system_server 内直接提交事务即可，无需注入被穿透进程。
 *
 * 与 FLAG_SECURE 的语义区分（选型依据）：secure = 内容保护（拒截/黑块）；
 * skipScreenshot = layer 对捕获不可见（穿透）。后者才是"穿透"的正确语义。
 *
 * 浮窗形态判定（ColorOS 15 实证校准）：
 * - AOSP 标准：windowing mode == FREEFORM(5)（MIUI 小窗等）
 * - ColorOS flexible task：windowing mode 不变（实测浮窗期间窗口配置
 *   仍为 fullscreen，Task 以 leash 缩放显示 LaunchScale≈0.24）——浮窗
 *   状态在 TaskInfo 的 OPPO 扩展 bundle：key_flexible_task_state
 *   （实测 2=浮窗 / 0=常规；SceneSDK 的 windowMode=100 是 OPPOSDK
 *   自有枚举，与 WindowConfiguration 无关，勿混）。检测双路径：
 *   - Path A（拉取式）：Task 的 no-arg TaskInfo getter → 扩展 bundle
 *     读 state，实时无事件依赖（热重载时浮窗已存在的场景也正确）
 *   - Path B（事件式）：hook OplusFlexibleWindowMinimizedManagerHelper
 *     #onTaskInfoChanged 维护浮窗 taskId 集合，Task.mTaskId 成员判定
 *   两路径 OR，运行时自适应解析；均解析失败时输出探针日志（Task
 *   flexible 字段清单）供下轮校准。
 * - 未知 windowing mode：一次性诊断日志（已知常规值不刷屏）
 *
 * 标记目标三处（TSS 实证矩阵，全打冗余覆盖）：
 * - buffer surface：WindowStateAnimator.mSurfaceController
 *   → WindowSurfaceController.mSurfaceControl（app 实际渲染的 layer）
 * - 容器 surface：WindowState.mSurfaceControl（WMS 窗口容器 layer）
 * - Task leash：ColorOS 浮窗即 leash 缩放显示，leash 是首要目标
 *
 * 双向 reconcile：每次 surface 事件按当前配置 × 当前形态重算并置
 * true/false——退出浮窗的 relayout / 重新全屏显示自动清除标记；配置
 * 变更经 [HookContext] 重载监听即时重算。ColorOS 浮窗退出为
 * exit-to-back（窗口转不可见，标记无害），下次全屏显示的事件帧纠正。
 *
 * Hook 点与 E1 共用方法（TSS 实证 + E1 协同去优化）：
 * - WindowState#prepareWindowToDisplayDuringRelayout（A12+ 主路径，
 *   ColorOS 进入浮窗时窗口 relayout 实证触发）
 * - WindowStateAnimator#createSurfaceLocked（A11 兜底 + surface 重建）
 * 两引擎以 owner 前缀 id 共存成链（见 [HookContext.hookE]），互不覆盖。
 *
 * 已知边界：浮窗的标题栏/装饰层若由 SysUI（shell 进程）独立绘制而非
 * task leash 子树，则不在标记范围，截图中可能残留边框——待实测确认。
 *
 * 降级链：setSkipScreenshot（S+ hidden，部分 ROM 删除）「从未成功过」
 * 才判定运行时损坏并一次性永久降级 setSecure（体验降级为该窗口黑块
 * 而非穿透）；成功过的单次失败 = SC 已释放（退出浮窗时装饰层先行
 * 销毁），仅作废重试不降级（否则首个会话退出后即永久黑块）。
 */
object FreeformPierceHook {

    /** WindowConfiguration.WINDOWING_MODE_FREEFORM（boot classpath 常量，固定值） */
    private const val WINDOWING_MODE_FREEFORM = 5

    /** 已知常规 windowing mode（undefined/fullscreen/pinned/multi-window/freeform/半屏）——诊断排除名单 */
    private val KNOWN_MODES = setOf(0, 1, 2, 4, 5, 6)

    /** ColorOS TaskInfo 扩展 bundle 的浮窗状态键（实测 2=浮窗，0=常规） */
    private const val KEY_FLEXIBLE_STATE = "key_flexible_task_state"

    /** ColorOS 浮窗监听 helper 候选 FQCN（找不到全部 CNFE 容错跳过） */
    private val FLEXIBLE_HELPER_CLASSES = listOf(
        "com.android.server.wm.OplusFlexibleWindowMinimizedManagerHelper",
        "com.oplus.server.wm.OplusFlexibleWindowMinimizedManagerHelper",
    )

    // ---- 反射单点缓存（install 解析一次，拦截器内只 invoke）----

    private var getOwningPackage: Method? = null
    private var getWindowConfiguration: Method? = null
    private var getWindowingMode: Method? = null
    /** WindowState 声明的窗口容器 surface（S+；仅本类声明——超类同名是容器 leash） */
    private var wsSurfaceField: Field? = null
    private var wsAnimatorField: Field? = null
    private var wsaWinField: Field? = null
    private var wsaControllerField: Field? = null
    private var controllerSurfaceField: Field? = null
    private var getTask: Method? = null
    private var taskSurface: Method? = null
    /** WindowState.mAttrs（WindowManager.LayoutParams，decor 腿标题源） */
    private var wsAttrsField: Field? = null
    private var attrsGetTitle: Method? = null
    private var txnCtor: Constructor<*>? = null
    private var txnApply: Method? = null
    private var txnClose: Method? = null

    @Volatile
    private var txnSetSkip: Method? = null

    @Volatile
    private var txnSetSecure: Method? = null

    /** setSkipScreenshot 是否成功过（运行期区分「方法损坏」与「对象级失败」） */
    @Volatile
    private var skipEverSucceeded = false

    // ---- ColorOS 浮窗探测通道 ----

    /** Path A：Task 层级 no-arg TaskInfo getter（AOSP 无此形则 null） */
    private var taskInfoM: Method? = null

    /** Path B：Task.mTaskId 字段（浮窗 taskId 集合成员判定） */
    private var taskMTaskIdField: Field? = null

    // ---- WMS 全量遍历通道（E1 同款：decor 窗口时序兜底） ----

    private var wsSessionField: Field? = null
    private var sessionServiceField: Field? = null
    private var wmsRootField: Field? = null
    private var wmsLockField: Field? = null

    /** RootWindowContainer#forAllWindows(callback, boolean) */
    private var forAllWindowsM: Method? = null

    @Volatile
    private var rootContainer: Any? = null

    @Volatile
    private var wmGlobalLock: Any? = null

    /** Path B：当前浮窗态 taskId 集合（onTaskInfoChanged 事件维护） */
    private val flexibleTaskIds = Collections.synchronizedSet(HashSet<Int>())

    /** Path B：taskId → 包名（flex 事件源 TaskInfo.baseActivity，decor 腿门控） */
    private val flexibleTaskPkgs = Collections.synchronizedMap(HashMap<Int, String>())

    /** SystemUI 浮窗装饰窗口在 applied 缓存中的 pkg 前缀（区别于真实包名） */
    private const val DECOR_PKG = "#flexible-decor"

    /** TaskInfo 实际类层级中的扩展 Bundle 字段（含未命中缓存，防重复扫描） */
    private val bundleFieldCache = ConcurrentHashMap<Class<*>, Optional<Field>>()

    /** 最近已应用态（按 surface 对象去重 + surface 重建后自动重打；弱键随 surface 回收） */
    private class Applied(val pkg: String, val form: Boolean, val flag: Boolean)

    private val applied: MutableMap<Any, Applied> =
        Collections.synchronizedMap(WeakHashMap<Any, Applied>())

    /** 每包名一次的 mode 值诊断（未知 OEM mode 校准用；已知常规值不打） */
    private val modeDiagLogged = Collections.synchronizedSet(HashSet<String>())

    /**
     * 裸 SurfaceControl 装饰层注册表（ColorOS 15 实证补丁）：浮窗装饰
     * （FlexibleTaskCaptionView 卡片/IndicatorView 把手）不是 WindowState
     * ——FlexibleWindowManagerService 直接创建/管理裸 SC（system_server
     * 线程对其提交事务），窗口树全量遍历不可达。经 Builder#build() 按
     * layer 名捕获入册，flex event / 配置重载时统一重算标记。弱键随
     * surface 销毁自动出册。
     */
    private val decorSurfaces: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    /** full pass refs 缺失一次性诊断 */
    private var fullPassDiagLogged = false

    fun installSystemServer(classLoader: ClassLoader) {
        val wsClass = classLoader.loadClass("com.android.server.wm.WindowState")
        val wsaClass = classLoader.loadClass("com.android.server.wm.WindowStateAnimator")
        val scClass = classLoader.loadClass("android.view.SurfaceControl")
        val txnClass = classLoader.loadClass("android.view.SurfaceControl\$Transaction")

        getOwningPackage = methodInHierarchy(wsClass, "getOwningPackage")
        getWindowConfiguration = methodInHierarchy(wsClass, "getWindowConfiguration")
        getWindowingMode = getWindowConfiguration?.let { wc ->
            runCatching {
                wc.returnType.getDeclaredMethod("getWindowingMode").apply { isAccessible = true }
            }.getOrNull()
        }
        wsSurfaceField = runCatching {
            wsClass.getDeclaredField("mSurfaceControl").apply { isAccessible = true }
        }.getOrNull()
        wsAnimatorField = fieldInHierarchy(wsClass, "mWinAnimator")
        wsaWinField = fieldInHierarchy(wsaClass, "mWin")
        wsaControllerField = fieldInHierarchy(wsaClass, "mSurfaceController")
        controllerSurfaceField = wsaControllerField?.type?.let { fieldInHierarchy(it, "mSurfaceControl") }
        getTask = methodInHierarchy(wsClass, "getTask")
        wsAttrsField = fieldInHierarchy(wsClass, "mAttrs")
        attrsGetTitle = wsAttrsField?.let { methodInHierarchy(it.type, "getTitle") }
        // WMS 全量遍历通道（E1 同款反射路径）
        wsSessionField = fieldInHierarchy(wsClass, "mSession")
        sessionServiceField = wsSessionField?.type?.let { fieldInHierarchy(it, "mService") }
        wmsRootField = sessionServiceField?.type?.let { fieldInHierarchy(it, "mRoot") }
        wmsLockField = sessionServiceField?.type?.let { fieldInHierarchy(it, "mGlobalLock") }
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
        runCatching {
            val taskClass = classLoader.loadClass("com.android.server.wm.Task")
            taskSurface = methodInHierarchy(taskClass, "getSurfaceControl")
        }.onFailure { taskSurface = null }

        txnCtor = txnClass.getDeclaredConstructor().apply { isAccessible = true }
        txnApply = methodInHierarchy(txnClass, "apply")
        txnClose = methodInHierarchy(txnClass, "close")
        txnSetSkip = methodInHierarchy(txnClass, "setSkipScreenshot", scClass, java.lang.Boolean.TYPE)
        txnSetSecure = methodInHierarchy(txnClass, "setSecure", scClass, java.lang.Boolean.TYPE)
        if (txnSetSkip == null && txnSetSecure == null) {
            HookContext.log(Log.ERROR, "E4 abort: no setSkipScreenshot/setSecure on this ROM")
            return
        }

        var hooked = false
        // A12+ 主路径：relayout 后 surface 就绪（ColorOS 进浮窗实测触发）
        methodInHierarchy(wsClass, "prepareWindowToDisplayDuringRelayout", java.lang.Boolean.TYPE)?.let { m ->
            HookContext.hookE("E4", m).intercept { chain ->
                val result = chain.proceed()
                reconcileWindow(chain.thisObject)
                result
            }
            hooked = true
        }
        // A11 兜底 + surface 重建（freeform resize/最小化恢复）
        methodInHierarchy(wsaClass, "createSurfaceLocked")?.let { m ->
            HookContext.hookE("E4", m).intercept { chain ->
                val result = chain.proceed()
                val win = runCatching { wsaWinField?.get(chain.thisObject) }.getOrNull()
                reconcileWindow(win)
                result
            }
            hooked = true
        }
        if (!hooked) {
            HookContext.log(Log.ERROR, "E4 abort: no hook point on this ROM")
            return
        }

        resolveOplusProbes(classLoader)
        installFlexibleTaskInfoHook(classLoader)
        installDecorSurfaceCapture()
        HookContext.addConfigReloadListener(::onConfigReloaded)
        HookContext.log(
            Log.INFO,
            "E4 installed (skip=${txnSetSkip != null}, secure=${txnSetSecure != null}, " +
                    "winCfg=${getWindowConfiguration != null && getWindowingMode != null}, " +
                    "buffer=${wsaControllerField != null && controllerSurfaceField != null}, " +
                    "oplusTaskInfo=${taskInfoM != null}, oplusTaskId=${taskMTaskIdField != null})"
        )
    }

    // ==================== ColorOS 探测通道解析 ====================

    /** Path A（TaskInfo getter）+ Path B（mTaskId 字段）运行时解析，失败输出探针日志 */
    private fun resolveOplusProbes(classLoader: ClassLoader) {
        runCatching {
            val taskInfoClass = Class.forName("android.app.TaskInfo")
            val taskClass = classLoader.loadClass("com.android.server.wm.Task")
            taskInfoM = hierarchyOf(taskClass)
                .flatMap { it.declaredMethods.toList() }
                .firstOrNull { it.parameterCount == 0 && taskInfoClass.isAssignableFrom(it.returnType) }
                ?.apply { isAccessible = true }
            taskMTaskIdField = hierarchyOf(taskClass)
                .flatMap { it.declaredFields.toList() }
                .firstOrNull { it.name == "mTaskId" }
                ?.apply { isAccessible = true }
            HookContext.log(
                Log.INFO,
                "E4 oplus probe: taskInfo=${taskInfoM?.name ?: "none"}, mTaskId=${taskMTaskIdField != null}"
            )
            if (taskInfoM == null && taskMTaskIdField == null) {
                // 下轮校准弹药：Task 上的 flexible/freeform 痕迹清单
                val trace = hierarchyOf(taskClass)
                    .flatMap { it.declaredFields.toList() }
                    .filter { it.name.contains("flexible", true) || it.name.contains("freeform", true) }
                    .joinToString { "${it.name}:${it.type.simpleName}" }
                HookContext.log(Log.INFO, "E4 oplus probe fail, taskFlexFields=${trace.ifEmpty { "none" }}")
            }
        }.onFailure { HookContext.log(Log.WARN, "E4 oplus probe error: ${it.message}") }
    }

    /**
     * Path B 事件源：hook OEM 浮窗监听 helper 的 onTaskInfoChanged——
     * TaskInfo 变更必经（实测浮窗进出各触发多次），从中读 taskId 与
     * 扩展 bundle 的 flexible state，维护浮窗 taskId 集合。CNFE 容错。
     */
    private fun installFlexibleTaskInfoHook(classLoader: ClassLoader) {
        runCatching {
            val taskInfoClass = Class.forName("android.app.TaskInfo")
            val taskIdField = taskInfoClass.getField("taskId")
            val baseActivityField = runCatching {
                taskInfoClass.getField("baseActivity").apply { isAccessible = true }
            }.getOrNull()
            for (fqcn in FLEXIBLE_HELPER_CLASSES) {
                val helperClass = runCatching { classLoader.loadClass(fqcn) }.getOrNull() ?: continue
                var hooked = 0
                hierarchyOf(helperClass).flatMap { it.declaredMethods.toList() }
                    .filter { it.name == "onTaskInfoChanged" }
                    .forEach { m ->
                        val idx = m.parameterTypes.indexOfFirst { taskInfoClass.isAssignableFrom(it) }
                        if (idx >= 0) {
                            m.isAccessible = true
                            HookContext.hookE("E4", m).intercept { chain ->
                                runCatching {
                                    val info = chain.args[idx] ?: return@runCatching
                                    val taskId = taskIdField.getInt(info)
                                    val flexible = flexibleStateOf(info) != 0
                                    if (flexible != (taskId in flexibleTaskIds)) {
                                        if (flexible) {
                                            flexibleTaskIds.add(taskId)
                                            // decor 腿门控数据：浮窗归属包名
                                            runCatching {
                                                (baseActivityField?.get(info) as? android.content.ComponentName)
                                                    ?.packageName?.let { flexibleTaskPkgs[taskId] = it }
                                            }
                                        } else {
                                            flexibleTaskIds.remove(taskId)
                                            flexibleTaskPkgs.remove(taskId)
                                        }
                                        HookContext.log(Log.INFO, "E4 flex event: task=$taskId flexible=$flexible")
                                        // 仅状态翻转时触发重算（onTaskInfoChanged 高频
                                        // 回调，任意 task 变更都进来）：全树窗口标记
                                        // 补算 + 裸 SC 装饰层 gate 重算
                                        runCatching { reconcileAllWindows() }
                                        runCatching { recomputeDecorSurfaces() }
                                    }
                                }
                                chain.proceed()
                            }
                            hooked++
                        }
                    }
                if (hooked > 0) {
                    HookContext.log(Log.INFO, "E4 oplus taskinfo hook: $hooked on ${helperClass.simpleName}")
                    return
                }
            }
            HookContext.log(Log.INFO, "E4 oplus taskinfo hook: helper not found")
        }.onFailure { HookContext.log(Log.WARN, "E4 oplus taskinfo hook error: ${it.message}") }
    }

    /**
     * 裸 SC 装饰层双捕获通道（build 期 + 挂树期，ColorOS 15 实证）：
     * - SurfaceControl.Builder#build()：system_server 内新建 layer 名含
     *   FlexibleTask 即入册——FlexibleWindowManagerService 创建装饰层的
     *   主通道（实测捕获 CaptionView/Leash、IndicatorView/Surface/Leash、
     *   ExtraView、DragLeash 七类；build 为 surface 生命周期级低频路径）
     * - Transaction#reparent(child, parent)：跨进程创建的装饰层被
     *   system_server 挂树时兜底捕获（层级变更级低频）
     * 捕获即按当前 gate 现算标记；gate 翻转由 flex event / 配置重载的
     * [recomputeDecorSurfaces] 统一重算。
     */
    private fun installDecorSurfaceCapture() {
        var builderHooked = false
        var reparentHooked = false
        runCatching {
            scNameField = fieldInHierarchy(
                Class.forName("android.view.SurfaceControl"), "mName"
            )
            val builderClass = Class.forName("android.view.SurfaceControl\$Builder")
            builderClass.declaredMethods
                .filter { it.name == "build" && it.parameterCount == 0 }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E4", m).intercept { chain ->
                        val sc = chain.proceed()
                        captureDecorIfMatch(sc, "build")
                        sc
                    }
                    builderHooked = true
                }
        }.onFailure { HookContext.log(Log.WARN, "E4 decor builder hook error: ${it.message}") }
        runCatching {
            val scClass = Class.forName("android.view.SurfaceControl")
            val txnClass = Class.forName("android.view.SurfaceControl\$Transaction")
            txnClass.declaredMethods
                .filter { it.name == "reparent" && it.parameterCount == 2 &&
                        it.parameterTypes.all { p -> p == scClass } }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E4", m).intercept { chain ->
                        val r = chain.proceed()
                        captureDecorIfMatch(chain.args.getOrNull(0), "reparent")
                        r
                    }
                    reparentHooked = true
                }
        }.onFailure { HookContext.log(Log.WARN, "E4 decor reparent hook error: ${it.message}") }
        HookContext.log(
            Log.INFO,
            "E4 decor capture: builder=$builderHooked reparent=$reparentHooked"
        )
    }

    /** SurfaceControl.mName 字段（build hook 负例快速过滤，零字符串分配） */
    private var scNameField: Field? = null

    /** 名字命中 FlexibleTask 即入册并按当前 gate 落标（一次性日志） */
    private fun captureDecorIfMatch(sc: Any?, via: String) {
        if (sc == null) return
        runCatching {
            if (decorSurfaces.contains(sc)) return
            val name = scNameField?.get(sc) as? String ?: sc.toString()
            if (!name.contains("FlexibleTask")) return
            decorSurfaces.add(sc)
            HookContext.log(Log.INFO, "E4 decor sc captured ($via): $name")
            recomputeDecorSurfaces()
        }
    }

    /** 装饰层注册表统一重算（gate = 存在配置了穿透的 flexible task） */
    private fun recomputeDecorSurfaces() {
        val active = anyPiercedFlexibleTask()
        val snapshot: List<Any> = synchronized(decorSurfaces) { decorSurfaces.toList() }
        for (sc in snapshot) {
            val name = runCatching { scNameField?.get(sc) as? String ?: sc.toString() }
                .getOrNull() ?: continue
            mark(sc, "$DECOR_PKG:$name", active, active)
        }
    }

    /** TaskInfo 扩展 bundle 的 flexible state（bundle 字段按实际类层级解析并缓存） */
    private fun flexibleStateOf(info: Any): Int {
        val cls = info.javaClass
        val field = bundleFieldCache.getOrPut(cls) {
            Optional.ofNullable(
                hierarchyOf(cls)
                    .flatMap { h -> h.declaredFields.toList() }
                    .filter { f -> f.type == Bundle::class.java }
                    .sortedBy { f -> if (f.name.contains("plus", true)) 0 else 1 }
                    .firstOrNull()
                    ?.apply { isAccessible = true }
            )
        }.orElse(null) ?: return 0
        return runCatching { (field.get(info) as? Bundle)?.getInt(KEY_FLEXIBLE_STATE, 0) ?: 0 }
            .getOrDefault(0)
    }

    // ==================== reconcile ====================

    /**
     * 单窗口重算：配置（piercesFreeform）× 浮窗形态（AOSP mode=5 或
     * ColorOS flexible state）→ buffer/容器/leash 三 surface 一致置位。
     * 置 false 与置 true 同等一等公民——退出浮窗/重新全屏的 relayout
     * 经此自动清除。
     *
     * 装饰腿（先于 app 腿，ColorOS 15 实证）：SystemUI 为浮窗绘制的伴随
     * 装饰窗口（FlexibleTaskCaptionView 卡片/标题、FlexibleTaskIndicatorView
     * 把手）是独立 WindowState（owner=systemui，不挂被穿透 task 子树），
     * 三 surface 标记覆盖不到——窗口标题含 "FlexibleTask" 且存在配置了
     * 穿透的 flexible task 时一并标记，浮窗装饰从截图中同步消失。
     */
    private fun reconcileWindow(ws: Any?) {
        if (ws == null) return
        runCatching { captureWmsRefs(ws) }
        val title = windowTitleOf(ws)
        val pkg = runCatching { getOwningPackage?.invoke(ws) as? String }.getOrNull()
        // 装饰腿：标题匹配即接管（systemui 包未配置，须在快速路径之前）
        if (title != null && title.contains("FlexibleTask")) {
            val active = anyPiercedFlexibleTask()
            val decorId = "$DECOR_PKG:$title"
            bufferSurfaceOf(ws)?.let { mark(it, decorId, active, active) }
            containerSurfaceOf(ws)?.let { mark(it, decorId, active, active) }
            return
        }
        if (pkg == null) return
        // 快速路径：未配置应用零额外反射开销（其遗留标记由重载监听器清除）
        if (!HookContext.piercesFreeform(pkg)) return
        val task = runCatching { getTask?.invoke(ws) }.getOrNull()
        val mode = windowingModeOf(ws)
        if (mode != null && mode !in KNOWN_MODES && modeDiagLogged.add("$pkg#$mode")) {
            HookContext.log(Log.INFO, "E4 mode diag: $pkg mode=$mode (unknown)")
        }
        val form = mode == WINDOWING_MODE_FREEFORM || oplusFlexibleOf(task)
        // 三目标全打（TSS 实证矩阵）：buffer surface（app 渲染层）优先，
        // 容器 surface 与 task leash 兜底——ColorOS 浮窗即 leash 缩放显示，
        // leash 是首要目标；SF 层 flag 继承语义跨 ROM 不一，冗余覆盖
        bufferSurfaceOf(ws)?.let { mark(it, pkg, form, form) }
        containerSurfaceOf(ws)?.let { mark(it, pkg, form, form) }
        runCatching {
            val leash = task?.let { t -> taskSurface?.invoke(t) } ?: return
            mark(leash, pkg, form, form)
        }
    }

    /** 窗口标题（WindowState.mAttrs.getTitle()，decor 腿判定键） */
    private fun windowTitleOf(ws: Any): String? = runCatching {
        val attrs = wsAttrsField?.get(ws) ?: return null
        (attrsGetTitle?.invoke(attrs) as? CharSequence)?.toString()
    }.getOrNull()

    /**
     * 从窗口实例沿 mSession→mService 捕获 WMS 根容器与全局锁（E1 同款，
     * 一次性缓存）。失败静默（静态字段路径解析失败的窗口走不到这里）。
     */
    private fun captureWmsRefs(ws: Any) {
        if (rootContainer != null) return
        val session = wsSessionField?.get(ws)
        val service = session?.let { sessionServiceField?.get(it) }
        val root = service?.let { wmsRootField?.get(it) }
        val lock = service?.let { wmsLockField?.get(it) }
        if (root != null && lock != null) {
            rootContainer = root
            wmGlobalLock = lock
        }
    }

    /**
     * 全量窗口树重算（flex event 与配置重载的兜底通道）：decor 窗口的
     * surface 创建早于 flex event 时，事件帧按空 gate 落了"不标记"缓存，
     * 此后该窗口可能再无 relayout——只有全量遍历能补标。持 WM 全局锁
     * （binder 线程可重入安全；锁缺失则放弃）。
     */
    private fun reconcileAllWindows() {
        val root = rootContainer
        val forAll = forAllWindowsM
        val lock = wmGlobalLock
        if (root == null || forAll == null || lock == null) {
            if (!fullPassDiagLogged) {
                fullPassDiagLogged = true
                HookContext.log(
                    Log.WARN,
                    "E4 full pass skipped: root=${root != null} forAll=${forAll != null} lock=${lock != null}"
                )
            }
            return
        }
        runCatching {
            var visited = 0
            synchronized(lock) {
                val cbType = forAll.parameterTypes[0]
                val visitor = Proxy.newProxyInstance(
                    cbType.classLoader, arrayOf(cbType)
                ) { _, m, args ->
                    if (m.parameterCount == 1) {
                        visited++
                        runCatching { reconcileWindow(args?.firstOrNull()) }
                    }
                    if (m.returnType == java.lang.Boolean::class.javaPrimitiveType ||
                        m.returnType == java.lang.Boolean::class.java
                    ) java.lang.Boolean.FALSE else null
                }
                forAll.invoke(root, visitor, true)
            }
            HookContext.log(Log.INFO, "E4 full pass: $visited windows visited")
        }.onFailure { HookContext.log(Log.WARN, "E4 full pass failed: ${it.message}") }
    }

    /** 存在「配置了穿透的包」处于 flexible 态（decor 腿全局门控） */
    private fun anyPiercedFlexibleTask(): Boolean = synchronized(flexibleTaskIds) {
        flexibleTaskIds.any { id ->
            flexibleTaskPkgs[id]?.let { HookContext.piercesFreeform(it) } == true
        }
    }

    /** ColorOS 浮窗判定：Path A（拉取实时 state）|| Path B（事件集合成员） */
    private fun oplusFlexibleOf(task: Any?): Boolean =
        oplusFlexiblePull(task) || oplusFlexibleMember(task)

    private fun oplusFlexiblePull(task: Any?): Boolean {
        val m = taskInfoM ?: return false
        if (task == null) return false
        return runCatching { flexibleStateOf(m.invoke(task) ?: return false) != 0 }
            .getOrDefault(false)
    }

    private fun oplusFlexibleMember(task: Any?): Boolean {
        val f = taskMTaskIdField ?: return false
        if (task == null) return false
        val id = runCatching { f.getInt(task) }.getOrDefault(-1)
        return id >= 0 && id in flexibleTaskIds
    }

    /** 配置重载：surface 遗留标记按新配置重算（取消分配/关闭开关即时生效） */
    private fun onConfigReloaded() {
        val snapshot: List<Pair<Any, Applied>> = synchronized(applied) {
            applied.entries.map { it.key to it.value }
        }
        for ((sc, st) in snapshot) {
            if (st.pkg.startsWith(DECOR_PKG)) {
                // 装饰条目：门控是全局 flexible×配置态，非单包配置
                val active = anyPiercedFlexibleTask()
                mark(sc, st.pkg, active, active)
            } else {
                mark(sc, st.pkg, st.form, HookContext.piercesFreeform(st.pkg) && st.form)
            }
        }
        // 裸 SC 装饰层注册表随配置统一重算（新建未入 applied 的也一并覆盖）
        recomputeDecorSurfaces()
    }

    /** 去重后提交标记事务；失败不落缓存（下次事件重试）。状态翻转打日志（低频） */
    private fun mark(sc: Any, pkg: String, form: Boolean, flag: Boolean) {
        val cur = applied[sc]
        if (cur != null && cur.pkg == pkg && cur.form == form && cur.flag == flag) return
        if (!applyTransaction(sc, flag)) return
        val prev = applied.put(sc, Applied(pkg, form, flag))
        if (flag) {
            HookContext.log(Log.INFO, "E4 pierce marked: $pkg (form=$form)")
        } else if (prev?.flag == true) {
            HookContext.log(Log.INFO, "E4 pierce cleared: $pkg")
        }
    }

    // ==================== 反射工具 ====================

    private fun windowingModeOf(ws: Any): Int? {
        val wc = runCatching { getWindowConfiguration?.invoke(ws) }.getOrNull() ?: return null
        return runCatching { getWindowingMode?.invoke(wc) as? Int }.getOrNull()
    }

    /**
     * buffer surface：WindowState.mWinAnimator → WindowStateAnimator
     * .mSurfaceController → WindowSurfaceController.mSurfaceControl
     * （app 实际渲染的 buffer layer；TSS 主目标）
     */
    private fun bufferSurfaceOf(ws: Any): Any? = runCatching {
        wsAnimatorField?.get(ws)?.let { animator ->
            wsaControllerField?.get(animator)?.let { ctrl -> controllerSurfaceField?.get(ctrl) }
        }
    }.getOrNull()

    /** 容器 surface：WindowState.mSurfaceControl（WMS 窗口容器 layer） */
    private fun containerSurfaceOf(ws: Any): Any? =
        runCatching { wsSurfaceField?.get(ws) }.getOrNull()

    /**
     * 提交单标记事务。失败二分（ColorOS 15 实证）：
     * - 退出浮窗时装饰层先行销毁（DragLeash 最典型），对已释放 SC 的
     *   set/apply 抛异常是对象级失败——作废本次事务待下轮事件重试，
     *   并将死句柄逐出装饰层注册表（gate 重算不再重试），不降级；
     * - 仅 setSkipScreenshot「从未成功过」才判定 ROM 方法运行期损坏，
     *   一次性永久降级 setSecure（体验降级为黑块而非穿透）。
     */
    private fun applyTransaction(sc: Any, flag: Boolean): Boolean {
        val ctor = txnCtor ?: return false
        val applyM = txnApply ?: return false
        var txn: Any? = null
        var ok = false
        try {
            txn = ctor.newInstance()
            val skip = txnSetSkip
            if (skip != null) {
                try {
                    skip.invoke(txn, sc, flag)
                    skipEverSucceeded = true
                } catch (t: Throwable) {
                    if (skipEverSucceeded) {
                        // 方法此前成功过 → 本次是该 SC 已释放，非方法损坏
                        HookContext.log(Log.WARN, "E4 setSkipScreenshot sc-fail (released?): ${t.message}")
                        return false
                    }
                    txnSetSkip = null // 从未成功过 → ROM 方法损坏，永久降级
                    HookContext.log(Log.WARN, "E4 setSkipScreenshot broken, degrade to setSecure")
                    runCatching { txnClose?.invoke(txn) }
                    txn = ctor.newInstance()
                }
            }
            if (txnSetSkip == null) {
                val secure = txnSetSecure ?: return false
                secure.invoke(txn, sc, flag)
            }
            applyM.invoke(txn)
            ok = true
            return true
        } catch (t: Throwable) {
            HookContext.log(Log.WARN, "E4 apply failed: ${t.message}")
            return false
        } finally {
            runCatching { txnClose?.invoke(txn) }
            if (!ok) decorSurfaces.remove(sc) // 死句柄逐出（窗口 SC 不在册，remove 无副作用）
        }
    }

    /** 类层级序列（含自身，直至 null） */
    private fun hierarchyOf(cls: Class<*>): Sequence<Class<*>> =
        generateSequence(cls) { it.superclass }

    /** 沿类层级找声明方法（WindowState 的部分方法声明在 WindowContainer 基类） */
    private fun methodInHierarchy(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = runCatching { c!!.getDeclaredMethod(name, *params) }.getOrNull()
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
