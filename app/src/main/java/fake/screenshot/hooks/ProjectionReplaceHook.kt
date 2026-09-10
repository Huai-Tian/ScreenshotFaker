package fake.screenshot.hooks

import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Process
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * E3b 录屏内容替换引擎（纯 system_server，MediaProjection 假图层）。
 *
 * 威胁模型：MediaProjection 系录屏（三方录屏 app / 投屏）持续拉取
 * 真实屏幕内容。截屏替换（E3a）只覆盖"单帧保存"路径，录屏流必须
 * 在内容合成层替换——虚拟屏（auto-mirror VD）的内容树在
 * SurfaceFlinger 侧合成，system_server 是唯一可注入 layer 的进程。
 *
 * 机制（虚拟屏假图层）：
 * - hook DMS#createVirtualDisplay（binder 入口，参数含 VirtualDisplayConfig）：
 *   AUTO_MIRROR 类 VD（MediaProjection 录屏标准形态）记录目标分辨率，
 *   供 mirror layer 创建时定假图层尺寸（buffer = VD 分辨率，假图拉伸
 *   铺满，无缺角露出真实内容）。系统内部 auto-mirror VD（ColorOS 下拉
 *   控制中心实时屏幕背景等，mirror 树合成回真实屏幕）按 callingUid
 *   排除——误挂会把替换图真实显示在系统 UI 上
 * - hook SurfaceControl#mirrorDisplay/#mirrorSurface（静态 hidden，
 *   auto-mirror VD 内容树的创建必经）：after 拿 mirror SurfaceControl，
 *   前台命中替换策略时创建 buffer layer 绘制假图，reparent 到
 *   mirror 子树并置顶——SF 合成 VD 输出时假图层盖住 mirror 的真实
 *   内容，录屏流全程为假图
 * - 录屏会话锁定：假图在 mirror 创建帧按当时前台解析（换图/换策略
 *   由配置 reload 重挂当前 mirror 生效；会话内前台切换不追踪——
 *   录屏是持续行为，逐帧切换语义反而暴露替换特征）
 *
 * 前台解析：system_server 内 LocalServices → ActivityTaskManagerInternal
 * #getTopApp → WindowProcessController.mName（processName，普通应用
 * = 包名）。解析失败 → 全局图回落（replacementImageId(null)）。
 *
 * 防御性设计（无真机先验的 OEM 差异，E4 探针模式）：
 * - mirror 双候选方法名（mirrorDisplay/mirrorSurface）运行时探测，
 *   全部缺失时探针日志，不阻断其余 hook；
 * - pending 分辨率带 5s 衰减：DMS 记录与 mirror 创建跨线程传递，
 *   陈旧 pending 不污染后续无关 mirror 调用；
 * - 已知边界（文档化接受）：fake layer 的 native 资源在录屏停止后
 *   依赖 release 清理（map 容量上限兜底，配置 reload 全量释放），
 *   单会话级泄漏面为一 layer + 一 buffer，配置变更必清。
 *
 * fail-open 全链：无策略 / 无图 / 前台解析失败 → 不建假图层，
 * 录屏内容原生（绝不阻断录屏流程）。
 */
object ProjectionReplaceHook {

    /** pending 分辨率有效期（DMS 帧到 mirror 帧的跨线程窗口） */
    private const val PENDING_TTL_MS = 5_000L

    /** 活跃假图层登记上限（防多 VD 并发会话的 map 无界增长） */
    private const val LAYERS_CAP = 8

    // ---- 反射单点缓存（install 解析一次，services.jar 类须经 system_server CL）----

    private var getServiceM: Method? = null
    private var atmInternalClass: Class<*>? = null
    private var getTopAppM: Method? = null
    private var wpcNameField: Field? = null
    private var cfgGetWidthM: Method? = null
    private var cfgGetHeightM: Method? = null
    private var cfgGetFlagsM: Method? = null

    /** Transaction#show(SurfaceControl)：hidden API（public SDK 无），反射缓存 */
    private var showM: Method? = null

    /** DMS 帧 → mirror 帧的 VD 分辨率传递（binder 线程与 mirror 线程可能不同） */
    @Volatile
    private var pendingDims: Pair<Pair<Int, Int>, Long>? = null

    /** mirror SC → fake layer SC（录屏会话登记；reload 全清） */
    private val layers = java.util.Collections.synchronizedMap(HashMap<Any, Any>())

    /** 最近一次 mirror SC（reload 重挂当前活跃录屏用） */
    @Volatile
    private var lastMirror: Any? = null

    private var mirrorHookedLogged = false

    fun installSystemServer(classLoader: ClassLoader) {
        ReplaceImageStore.ensureInstalled()

        // ---- Transaction#show（hidden API，反射；system_server 无 hidden API 限制）----
        showM = runCatching {
            SurfaceControl.Transaction::class.java
                .getMethod("show", SurfaceControl::class.java)
                .apply { isAccessible = true }
        }.getOrNull()

        // ---- 前台解析链（services.jar：ColorOS 15 实测须 system_server CL）----
        runCatching {
            val lsClass = classLoader.loadClass("com.android.server.LocalServices")
            getServiceM = lsClass.getMethod("getService", Class::class.java).apply { isAccessible = true }
            atmInternalClass = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerInternal")
            getTopAppM = atmInternalClass!!.getMethod("getTopApp").apply { isAccessible = true }
            wpcNameField = classLoader.loadClass("com.android.server.wm.WindowProcessController")
                .getDeclaredField("mName").apply { isAccessible = true }
        }.onFailure { HookContext.log(Log.WARN, "E3b top-app chain unresolved: ${it.message}") }

        // ---- VirtualDisplayConfig 尺寸读取 ----
        // 类为 API 34+（minSdk 30，编译期引用触发 NewApi lint）：反射解析，
        // < 34 ROM 上 CNFE → null，下方 DMS 腿整体跳过（该签名不存在于旧 ROM）
        val vdCfgClass = runCatching {
            Class.forName("android.hardware.display.VirtualDisplayConfig")
        }.getOrNull()
        runCatching {
            val cfgClass = vdCfgClass ?: return@runCatching
            cfgGetWidthM = cfgClass.getMethod("getWidth").apply { isAccessible = true }
            cfgGetHeightM = cfgClass.getMethod("getHeight").apply { isAccessible = true }
            cfgGetFlagsM = cfgClass.getMethod("getFlags").apply { isAccessible = true }
        }

        var dmsHooked = 0
        // ---- DMS#createVirtualDisplay：AUTO_MIRROR VD 分辨率登记 ----
        runCatching {
            val vdCfg = vdCfgClass ?: return@runCatching
            val dmsClass = classLoader.loadClass("com.android.server.display.DisplayManagerService")
            dmsClass.declaredMethods.filter { it.name == "createVirtualDisplay" }.forEach { m ->
                val cfgIdx = m.parameterTypes.indexOfFirst { it == vdCfg }
                if (cfgIdx < 0) return@forEach
                m.isAccessible = true
                HookContext.hookE("E3b", m).intercept { chain ->
                    val cfg = chain.args[cfgIdx] ?: return@intercept chain.proceed()
                    val flags = runCatching { cfgGetFlagsM?.invoke(cfg) as? Int }.getOrNull() ?: 0
                    if (flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR != 0) {
                        // AUTO_MIRROR VD 不全是录屏：系统内部也建（ColorOS 15 实测
                        // 下拉控制中心的"实时屏幕背景"即每次下拉创建 auto-mirror VD
                        // + mirrorDisplay，其 mirror 树合成回真实屏幕）。若不区分，
                        // 假图层会被挂到该 mirror 上——替换图真实显示在状态栏背景。
                        // 判据：binder 调用方为 system（含 system_server 同进程内
                        // 调用）或 SystemUI → 系统内部用途，不登记
                        if (!isSystemMirrorCaller()) {
                            val w = runCatching { cfgGetWidthM?.invoke(cfg) as? Int }.getOrNull() ?: 0
                            val h = runCatching { cfgGetHeightM?.invoke(cfg) as? Int }.getOrNull() ?: 0
                            if (w > 0 && h > 0) {
                                pendingDims = (w to h) to System.currentTimeMillis()
                                HookContext.log(Log.INFO, "E3b auto-mirror VD registered ${w}x$h")
                            }
                        }
                    }
                    chain.proceed()
                }
                dmsHooked++
            }
        }.onFailure { HookContext.log(Log.WARN, "E3b dms leg error: ${it.message}") }

        // ---- SurfaceControl mirror 静态族：mirror layer 创建拦截 ----
        var mirrorHooked = 0
        runCatching {
            val scClass = Class.forName("android.view.SurfaceControl")
            for (name in listOf("mirrorDisplay", "mirrorSurface")) {
                scClass.declaredMethods
                    .filter { it.name == name && Modifier.isStatic(it.modifiers) && it.returnType == scClass }
                    .forEach { m ->
                        m.isAccessible = true
                        HookContext.hookE("E3b", m).intercept { chain ->
                            val mirror = chain.proceed() ?: return@intercept null
                            runCatching { onMirrorCreated(mirror) }
                                .onFailure { HookContext.log(Log.WARN, "E3b overlay failed: ${it.message}") }
                            mirror
                        }
                        mirrorHooked++
                    }
            }
        }.onFailure { HookContext.log(Log.WARN, "E3b mirror leg error: ${it.message}") }

        HookContext.addConfigReloadListener(::reload)
        HookContext.log(
            Log.INFO,
            "E3b installed (dms=$dmsHooked, mirror=$mirrorHooked, topApp=${getTopAppM != null})"
        )
        if (mirrorHooked == 0) {
            HookContext.log(Log.WARN, "E3b no mirror hook point on this ROM (probe for calibration)")
        }
    }

    // ==================== 假图层核心 ====================

    /** mirror layer 就绪：消费 pending 尺寸，命中策略则挂假图层。
     *  pending 为空 = 系统内部 mirror（未登记的 VD 或纯 mirrorDisplay 调用）
     *  → 不挂也不记 lastMirror（防 reload 重挂污染系统 mirror） */
    private fun onMirrorCreated(mirrorSc: Any) {
        val pending = pendingDims ?: return
        if (System.currentTimeMillis() - pending.second > PENDING_TTL_MS) {
            pendingDims = null
            return
        }
        pendingDims = null
        lastMirror = mirrorSc
        overlayMirror(mirrorSc, pending.first.first, pending.first.second)
    }

    private fun overlayMirror(mirrorSc: Any, w: Int, h: Int) {
        if (!HookContext.hasReplacePolicy()) return
        val imageId = HookContext.replacementImageId(topPackage()) ?: return
        val fake = ReplaceImageStore.bitmapFor(imageId) ?: return

        // 已有登记（reload 重挂路径）：先摘旧图层
        removeLayer(mirrorSc)

        val fakeSc = SurfaceControl.Builder()
            .setName("sf-e3b")
            .setBufferSize(w, h)
            .setFormat(PixelFormat.RGBA_8888)
            .build()
        // 假图拉伸铺满 VD 分辨率（无缺角露出真实内容）。
        // Surface 非 Closeable（无 use 扩展），手动 release——已 post 的
        // buffer 仍挂在 layer 上，Surface 释放不影响显示
        val surface = Surface(fakeSc)
        try {
            val canvas = surface.lockHardwareCanvas()
            canvas.drawBitmap(fake, null, Rect(0, 0, w, h), null)
            surface.unlockCanvasAndPost(canvas)
        } finally {
            surface.release()
        }
        SurfaceControl.Transaction().use { t ->
            t.reparent(fakeSc, mirrorSc as SurfaceControl)
            t.setLayer(fakeSc, Int.MAX_VALUE)
            // show 为 hidden API（AOSP 29+ 稳定存在），反射调用
            showM?.let { m -> runCatching { m.invoke(t, fakeSc) } }
            t.apply()
        }
        if (layers.size >= LAYERS_CAP) {
            // 容量兜底：清最旧一条（LinkedHashMap 保序）
            synchronized(layers) {
                layers.keys.firstOrNull()?.let { removeLayer(it) }
            }
        }
        layers[mirrorSc] = fakeSc
        HookContext.log(Log.INFO, "E3b fake layer overlaid on mirror ${w}x$h (image=$imageId)")
    }

    /** 摘除并释放单会话假图层（reparent null 出树 + release native） */
    private fun removeLayer(mirrorSc: Any) {
        val sc = layers.remove(mirrorSc) ?: return
        runCatching {
            SurfaceControl.Transaction().use { t ->
                t.reparent(sc as SurfaceControl, null)
                t.apply()
            }
            (sc as SurfaceControl).release()
        }
    }

    /**
     * 配置 reload：全量摘除假图层；策略仍存在时对最近活跃 mirror 重挂
     * （换图/换前台策略即时生效）；策略消失则净空（录屏恢复原生内容）
     */
    private fun reload() {
        synchronized(layers) {
            layers.keys.toList().forEach { removeLayer(it) }
        }
        lastMirror?.let { mirror ->
            if (HookContext.hasReplacePolicy()) {
                // 重挂用 mirror 创建时的 VD 分辨率——从假图层 buffer 无法
                // 反查（已摘），用主屏分辨率兜底（绝大多数录屏即屏比）
                val dm = displaySize()
                if (dm != null) runCatching { overlayMirror(mirror, dm.first, dm.second) }
            }
        }
    }

    // ==================== 前台解析 ====================

    /**
     * 系统内部 mirror VD 判定：callingUid 为 system（binder 远端 system_server
     * / system 共享 uid 应用，或 system_server 同进程内直调——此时 callingUid
     * 恒为本进程 1000）或 SystemUI → true。其余（三方/OEM 录屏 app，
     * uid 为普通应用段）→ false，按录屏会话登记。
     * uid ≠ 1000 时即使包名解析失败也视为 app——auto-mirror VD 的 binder
     * 调用方本身就是"应用请求录屏"语义，漏登记比误登记（假图上系统
     * UI）的代价小
     */
    private fun isSystemMirrorCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID) return true
        return HookContext.anyPkgForUid(uid) { it == "com.android.systemui" }
    }

    /** system_server 前台包名（getTopApp → processName）；失败 null → 全局图回落 */
    private fun topPackage(): String? = runCatching {
        val atm = getServiceM?.invoke(null, atmInternalClass) ?: return null
        val top = getTopAppM?.invoke(atm) ?: return null
        wpcNameField?.get(top) as? String
    }.getOrNull()

    /** 主屏物理分辨率（reload 重挂的尺寸兜底；解析失败放弃重挂）。
     *  getPhysicalDisplayIds/Token 在 blocklist（targetSdk 36 起 lint 拦截）——
     *  本引擎仅运行于 system_server（Xposed 注入），隐藏 API 限制不适用，
     *  与 Auxiliary.kt 同款豁免 */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun displaySize(): Pair<Int, Int>? = runCatching {
        val scClass = Class.forName("android.view.SurfaceControl")
        val getM = scClass.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }
        val ids = getM.invoke(null) as? LongArray ?: return null
        val tokenM = scClass.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val token = tokenM.invoke(null, ids.firstOrNull() ?: return null) ?: return null
        val statsCtor = scClass.getDeclaredConstructor().let { }
        val statsClass = Class.forName("android.view.SurfaceControl\$DisplayStatistics")
        val getStats = scClass.getDeclaredMethod("getDisplayStatistics", token::class.java)
            .apply { isAccessible = true }
        val stats = getStats.invoke(null, token) ?: return null
        val widthField = statsClass.getDeclaredField("width").apply { isAccessible = true }
        val heightField = statsClass.getDeclaredField("height").apply { isAccessible = true }
        (widthField.getInt(stats) to heightField.getInt(stats))
    }.getOrNull()
}
