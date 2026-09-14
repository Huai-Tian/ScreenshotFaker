package fake.screenshot.hooks

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.io.FileDescriptor
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
 * 机制（虚拟屏假图层，两级内容源）：
 * - hook DMS#createVirtualDisplay*（binder 入口在 DMS 外层类或其内部类，
 *   形参两系：公开 VirtualDisplayConfig / system parcelable
 *   VirtualDisplayConfigInternal——AOSP 13+ 及 ColorOS 15 为后者）：
 *   AUTO_MIRROR 类 VD（MediaProjection 录屏标准形态）记录目标分辨率，
 *   供 mirror layer 创建时定假图层尺寸（buffer = 显示空间分辨率
 *   ——VD 只是时序配对标记 + 解析失败时的兜底尺寸；系统录屏 VD 常按
 *   码率降分辨率，按 VD 尺寸建层只盖左上角，实测 round 3）。系统内部 auto-mirror VD（ColorOS 下拉
 *   控制中心实时屏幕背景等，mirror 树合成回真实屏幕）按 callingUid
 *   排除——误挂会把替换图真实显示在系统 UI 上
 * - hook SurfaceControl#mirrorDisplay/#mirrorSurface（静态 hidden，
 *   auto-mirror VD 内容树的创建必经）：after 拿 mirror SurfaceControl，
 *   前台命中替换策略时创建 buffer layer，reparent 到 mirror 子树并
 *   置顶——SF 合成 VD 输出时假图层盖住 mirror 的真实内容。
 *   内容源按配置两级：仅图（历史行为）→ 静态假图；命中录屏视频 →
 *   同步先 post 视频 0 帧（warm 首帧命中）或黑屏（不画替换图——残影
 *   根除），后台线程流式解密视频信封到 memfd（[ReplaceVideoStore]）
 *   后 MediaPlayer 接管同一 layer（looping / 静音 / CROPPING 缩放铺满
 *   ）——视频 prepare 期间录屏开头即假内容，零真实内容窗口。视频任
 *   一环节失败 → 回落替换图（无图停留黑屏占位）
 * - hook DMS#releaseVirtualDisplay*（录屏停止）：全量清会话——视频
 *   会话持有 MediaPlayer 解码线程与 memfd 不可泄漏（静态图时代文档化
 *   接受的单 layer 泄漏面在视频语义下不可接受）
 * - 录屏会话锁定：内容源在 mirror 创建帧按当时前台解析（换图/换策略
 *   由配置 reload 重挂当前 mirror 生效；会话内前台切换不追踪——
 *   录屏是持续行为，逐帧切换语义反而暴露替换特征）
 * - 方向自适应（2026-09-14 二轮定稿：替换不跟随系统旋转）：假图层
 *   buffer 恒为方形（显示空间长边）——旋转后 mirror 坐标系宽高互换，
 *   方形恒覆盖全部可见区域（旧定向 buffer 旋转后只盖左半 = 真实内容
 *   暴露的根因）；内容统一中心裁剪铺满当前方向可见条（[cropStrip]，
 *   不旋转不变形），方向看护线程 50ms 轮询尺寸变化刷新条带几何（视频
 *   中继帧自动跟随）——全程零真实内容窗口
 *
 * 前台解析：system_server 内 LocalServices → ActivityTaskManagerInternal
 * #getTopApp → WindowProcessController.mName（processName，普通应用
 * = 包名）。解析失败 → 全局回落（replacementImageId/recordVideoId(null)）。
 *
 * 防御性设计（OEM 差异容错）：
 * - mirror 双候选方法名（mirrorDisplay/mirrorSurface）运行时探测，
 *   全部缺失时仅 installed 摘要计数归零，不阻断其余 hook；
 * - pending 分辨率带 5s 衰减：DMS 记录与 mirror 创建跨线程传递，
 *   陈旧 pending 不污染后续无关 mirror 调用；
 * - 视频线程与会话清理竞态：Session.removed 标记三查（解密前 /
 *   prepare 后 / start 前），中途退出就地释放 player 与 memfd；
 * - releaseVirtualDisplay 全清不区分 VD：多并发录屏会话罕见，误清
 *   另一会话（其假图层消失 = 原生内容直至 reload 重挂）的代价小于
 *   MediaPlayer 泄漏代价。
 *
 * fail-open 全链：无策略 / 视频与图均未命中 / 视频加载失败（回落图）
 * / 前台解析失败 → 不建假图层或停留首帧，录屏流程绝不阻断。
 */
object ProjectionReplaceHook {

    /** pending 分辨率有效期（DMS 帧到 mirror 帧的跨线程窗口） */
    private const val PENDING_TTL_MS = 5_000L

    /** 活跃假图层登记上限（防多 VD 并发会话的 map 无界增长） */
    private const val LAYERS_CAP = 8

    /** 方向看护轮询间隔（旋转后条带几何刷新延迟上界；反射读两字段，
     *  20Hz 在 system_server 开销可忽略） */
    private const val ORIENT_POLL_MS = 50L

    // ---- 反射单点缓存（install 解析一次，services.jar 类须经 system_server CL）----

    private var getServiceM: Method? = null
    private var atmInternalClass: Class<*>? = null
    private var getTopAppM: Method? = null
    private var wpcNameField: Field? = null

    /** DisplayManagerInternal（LocalServices 同款解析）：显示空间尺寸 */
    private var dmiClass: Class<*>? = null
    private var getDisplayInfoM: Method? = null
    private var logicalWidthF: Field? = null
    private var logicalHeightF: Field? = null

    /** Transaction#show(SurfaceControl)：hidden API（public SDK 无），反射缓存 */
    private var showM: Method? = null

    /** DMS 帧 → mirror 帧的 VD 分辨率传递（binder 线程与 mirror 线程可能不同） */
    @Volatile
    private var pendingDims: Pair<Pair<Int, Int>, Long>? = null

    /** mirror SC → 录屏会话（假图层 + 可选视频播放器；reload/release 全清） */
    private val layers = java.util.Collections.synchronizedMap(HashMap<Any, Session>())

    /** 最近一次 mirror SC（reload 重挂当前活跃录屏用） */
    @Volatile
    private var lastMirror: Any? = null

    /** 方向看护线程（活跃会话存在期间运行；见 [ensureOrientationWatch]） */
    @Volatile
    private var orientWatch: Thread? = null
    private val orientLock = Any()

    /**
     * 录屏会话资源：假图层（常驻）+ 视频播放器与 memfd（视频会话）。
     * [lock] 序化 player 登记与 removed 判定（[removeLayer] 同锁）——
     * 无锁窗口下 start() 前后登记的 player 可能错过清理（MediaPlayer
     * + memfd 泄漏）
     */
    private class Session(val layer: SurfaceControl) {
        /** 图层 buffer = 方形边长（显示空间长边；旋转不重建——全覆盖关键） */
        @Volatile var bufS = 0
        /** 可见条带尺寸 = 当前方向显示逻辑尺寸（方向看护刷新；中继/
         *  占位/回落绘制实时跟随——横竖屏自适应核心） */
        @Volatile var dstW = 0
        @Volatile var dstH = 0
        @Volatile var player: MediaPlayer? = null
        @Volatile var videoFd: FileDescriptor? = null
        @Volatile var removed = false
        /** 帧中继（round 13）：解码器 → ImageReader（YUV）→ 本进程转
         *  RGB 半分辨率位图 → canvas 中心裁剪铺满 layer。几何完全自控，
         *  规避 OEM 对解码器直挂图层的满幅钳制。释放责任同 player */
        @Volatile var relayReader: ImageReader? = null
        @Volatile var relayThread: HandlerThread? = null
        @Volatile var relaySurface: Surface? = null
        @Volatile var relayDead = false
        /** 图回落位图（视频会话 fail-open 到图，round 17）：overlayMirror
         *  帧解析的最终替换图引用。初始占位不再画图（残影根除）后，图
         *  仅在视频失败路径经 [postFallback] 出现 */
        @Volatile var fallbackImage: Bitmap? = null
        /** 中继工作集（bitmap 供看护线程方向重画共享读，[Session.lock]
         *  外仅读引用；平面拷贝/像素输出中继线程独占）与统计 */
        @Volatile var relayBitmap: Bitmap? = null
        var relayY: ByteArray? = null
        var relayU: ByteArray? = null
        var relayV: ByteArray? = null
        var relayOut: IntArray? = null
        var relayLogged = false
        var relayFrames = 0
        var relayFails = 0
        val lock = Any()
    }

    fun installSystemServer(classLoader: ClassLoader) {
        ReplaceImageStore.ensureInstalled()
        ReplaceVideoStore.ensureInstalled()

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

        // ---- 显示空间尺寸链（LocalServices → DisplayManagerInternal →
        // DisplayInfo#logicalWidth/Height：当前方向的全显示尺寸）----
        // 假图层的坐标系是 mirror 树的显示空间（物理 logical），不是 VD
        // 请求分辨率——系统录屏 VD 常按码率需求降分辨率（实测 720x1608
        // vs 显示 1080x2412），按 VD 尺寸建 buffer 假图只盖左上角
        runCatching {
            dmiClass = classLoader.loadClass("android.hardware.display.DisplayManagerInternal")
            getDisplayInfoM = dmiClass!!.getMethod(
                "getDisplayInfo", Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val diClass = classLoader.loadClass("android.view.DisplayInfo")
            logicalWidthF = diClass.getDeclaredField("logicalWidth").apply { isAccessible = true }
            logicalHeightF = diClass.getDeclaredField("logicalHeight").apply { isAccessible = true }
        }.onFailure { HookContext.log(Log.WARN, "E3b display-info chain unresolved: ${it.message}") }

        // ---- VD config 形参解析（两系） ----
        // DMS binder 形参在 AOSP 13+ 为 VirtualDisplayConfigInternal（system
        // parcelable，public final m* 字段），公开 API 类 VirtualDisplayConfig
        // （getter）仅在旧签名出现——ColorOS 15 实测 dms=0 根因即原匹配只认
        // 公开类。两系全解析，按形参 Class 对号读取
        val cfgAccessors = listOf(
            "android.hardware.display.VirtualDisplayConfig",
            "android.hardware.display.VirtualDisplayConfigInternal"
        ).mapNotNull { name ->
            runCatching {
                val cls = Class.forName(name)
                cls to CfgAccessors(
                    intAccessor(cls, "getWidth", "mWidth", "width"),
                    intAccessor(cls, "getHeight", "mHeight", "height"),
                    intAccessor(cls, "getFlags", "mFlags", "flags")
                )
            }.getOrNull()
        }.toMap()

        var dmsHooked = 0
        // ---- DMS#createVirtualDisplay*：AUTO_MIRROR VD 分辨率登记 ----
        // binder 入口宿主含 DMS 内部类（AOSP 13+ BinderService extends
        // IDisplayManager.Stub，binder 方法不在外层类）；方法名前缀匹配
        // 兼容 createVirtualDisplayInternal 辅助路径——与 binder 方法同会话
        // 双跳时 pendingDims 幂等覆盖，仅登记日志重复一行，无害
        runCatching {
            val dmsClass = classLoader.loadClass("com.android.server.display.DisplayManagerService")
            val hosts = listOf(dmsClass) + dmsClass.declaredClasses
            hosts.forEach { host ->
                host.declaredMethods
                    .filter { it.name.startsWith("createVirtualDisplay") }
                    .forEach { m ->
                        val cfgIdx = m.parameterTypes.indexOfFirst { it in cfgAccessors }
                        if (cfgIdx < 0) return@forEach
                        val acc = cfgAccessors.getValue(m.parameterTypes[cfgIdx])
                        m.isAccessible = true
                        HookContext.hookE("E3b", m).intercept { chain ->
                            val cfg = chain.args[cfgIdx] ?: return@intercept chain.proceed()
                            val flags = acc.flags(cfg) ?: 0
                            if (flags and DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR != 0) {
                                // AUTO_MIRROR VD 不全是录屏：系统内部也建（ColorOS 15 实测
                                // 下拉控制中心的"实时屏幕背景"即每次下拉创建 auto-mirror VD
                                // + mirrorDisplay，其 mirror 树合成回真实屏幕）。若不区分，
                                // 假图层会被挂到该 mirror 上——替换图真实显示在状态栏背景。
                                // 判据：binder 调用方为 system（含 system_server 同进程内
                                // 调用）或 SystemUI → 系统内部用途，不登记
                                if (!isSystemMirrorCaller()) {
                                    val w = acc.width(cfg) ?: 0
                                    val h = acc.height(cfg) ?: 0
                                    if (w > 0 && h > 0) {
                                        pendingDims = (w to h) to System.currentTimeMillis()
                                        // 调用方包名显式打点（E3c 录屏器包名发现路径）：
                                        // uid → 包名解析失败时打 uid（adb `pm list packages --uid`
                                        // 可反查）
                                        val caller = Binder.getCallingUid()
                                        val pkgs = HookContext.packagesForUid(caller)
                                        HookContext.log(
                                            Log.INFO,
                                            "E3b auto-mirror VD registered ${w}x$h (caller uid=$caller pkg=${pkgs?.firstOrNull() ?: "unresolved"})"
                                        )
                                    }
                                }
                            }
                            chain.proceed()
                        }
                        dmsHooked++
                    }
            }
        }.onFailure { HookContext.log(Log.WARN, "E3b dms leg error: ${it.message}") }

        // ---- DMS#releaseVirtualDisplay*：录屏停止的会话回收钩 ----
        // binder 入口与 create 同宿主集（DMS 外层类或内部类）。不区分
        // 具体 VD（callback 与 mirror SC 无公开映射），保守全清——视频
        // 会话的 MediaPlayer/memfd 泄漏代价 > 并发另一会话被误清的代价
        var releaseHooked = 0
        runCatching {
            val dmsClass2 = classLoader.loadClass("com.android.server.display.DisplayManagerService")
            (listOf(dmsClass2) + dmsClass2.declaredClasses).forEach { host ->
                host.declaredMethods
                    .filter { it.name.startsWith("releaseVirtualDisplay") }
                    .forEach { m ->
                        m.isAccessible = true
                        HookContext.hookE("E3b", m).intercept { chain ->
                            val r = chain.proceed()
                            runCatching { onVirtualDisplayReleased() }
                                .onFailure { HookContext.log(Log.WARN, "E3b release sweep failed: ${it.message}") }
                            r
                        }
                        releaseHooked++
                    }
            }
        }.onFailure { HookContext.log(Log.WARN, "E3b release leg error: ${it.message}") }

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
            "E3b installed (dms=$dmsHooked, release=$releaseHooked, mirror=$mirrorHooked, topApp=${getTopAppM != null})"
        )
    }

    // ==================== 会话核心（假图层 + 可选视频） ====================

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

    /**
     * 假图层装配（两级内容源）。同会话前台快照一次解析：图（E3a 同源
     * 策略）与视频（E3b 专属）分属两级配置，仅视频命中 → 黑屏首帧 +
     * 视频接管；仅图命中 → 历史行为（图必须就位才挂，加载失败 = 原生
     * 内容——不引入新行为）；两者命中 → 视频 0 帧（warm 首帧命中）或
     * 黑屏占位 + 视频接管，图降级为视频失败的回落（round 17 残影根除：
     * 此前双命中画替换图，视频接管前可见数十至数百 ms 即录屏开头残影）
     */
    private fun overlayMirror(mirrorSc: Any, w: Int, h: Int) {
        if (!HookContext.hasReplacePolicy()) return
        val fg = topPackage()
        val videoId = HookContext.recordVideoId(fg)
        val imageId = HookContext.replacementImageId(fg)
        if (videoId == null && imageId == null) return
        var fake = imageId?.let { ReplaceImageStore.bitmapFor(it) }
        if (fake == null && imageId != null) {
            // 模板图加载失败（远程缺失/损坏——如历史 promote 被取消留下的
            // 死配置）回落全局图：黑屏首帧虽同样 fail-open（无真实内容
            // 泄漏），但可用替换显著优于黑屏；加载失败细节由图片仓库
            // 分级 WARN（absent/decrypt/decode/负缓存）
            val gid = HookContext.globalImageId()
            if (gid != null && gid != imageId) {
                fake = ReplaceImageStore.bitmapFor(gid)
                if (fake != null) {
                    HookContext.log(Log.WARN, "E3b image $imageId unavailable, fell back to global image")
                }
            }
        }
        // 无视频会话维持原语义：图未就位不挂（fail-open 原生内容）
        if (videoId == null && fake == null) return

        // 已有登记（reload 重挂路径）：先摘旧会话
        removeLayer(mirrorSc)

        // 可见条带尺寸 = mirror 树的显示空间（物理 logical，含旋转），VD
        // 请求分辨率仅作解析失败时的兜底（logical 链解析失败时兜底尺寸
        // 偏小 → blanket 外暴露，与历史一致——hook 侧日志可见以便定位）
        val (bw, bh) = displayLogicalSize() ?: run {
            HookContext.log(Log.WARN, "E3b display logical size unresolved, fallback to VD ${w}x$h")
            w to h
        }
        // 方形 blanket（2026-09-14 二轮）：buffer 取显示空间长边为方形——
        // 旋转时 mirror 坐标系宽高互换，定向 buffer 只盖左半（实测暴露
        // 根因）；方形恒覆盖两个方向的可见区域（条带外区域被 mirror 裁剪
        // 不可见），旋转零重建零空窗
        val bs = maxOf(bw, bh)
        val session = Session(
            SurfaceControl.Builder()
                .setName("sf-e3b")
                .setBufferSize(bs, bs)
                .setFormat(PixelFormat.RGBA_8888)
                .build()
        )
        // 会话几何登记（bufS 供看护线程判定显示尺寸是否超出 blanket；
        // dst 供中继/占位/回落帧实时读取——看护刷新后所有绘制自动跟随）
        session.bufS = bs
        session.dstW = bw
        session.dstH = bh
        // 同步首帧（buffer 先 post——layer 从 show 起即有内容，录屏开头
        // 零真实内容窗口）：全方形黑底 + 当前方向条带内容（中心裁剪铺
        // 满，与中继帧/回落图同几何）。无视频会话：图命中 → 替换图条带；
        // 视频会话：peek 命中 → 视频 0 帧条带（与中继帧无缝），未命中 →
        // 纯黑底；图记入 session 作失败回落（[postFallback]）
        // Surface 非 Closeable（无 use 扩展），手动 release——已 post 的
        // buffer 仍挂在 layer 上，Surface 释放不影响显示
        session.fallbackImage = fake
        val surface = Surface(session.layer)
        try {
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawColor(Color.BLACK)
                val lead: Bitmap? =
                    if (videoId != null) ReplaceVideoStore.peekFirstFrame(videoId) else fake
                if (lead != null) {
                    canvas.drawBitmap(
                        lead, cropStrip(lead.width, lead.height, bw, bh),
                        Rect(0, 0, bw, bh), relayPaint
                    )
                }
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } finally {
            surface.release()
        }
        SurfaceControl.Transaction().use { t ->
            t.reparent(session.layer, mirrorSc as SurfaceControl)
            t.setLayer(session.layer, Int.MAX_VALUE)
            // show 为 hidden API（AOSP 29+ 稳定存在），反射调用
            showM?.let { m -> runCatching { m.invoke(t, session.layer) } }
            t.apply()
        }
        if (layers.size >= LAYERS_CAP) {
            // 容量兜底：清最旧一条（LinkedHashMap 保序）
            synchronized(layers) {
                layers.keys.firstOrNull()?.let { removeLayer(it) }
            }
        }
        layers[mirrorSc] = session
        // 方向看护（横竖屏切换的条带几何刷新，见 [redrawStrip]）
        ensureOrientationWatch()
        if (videoId != null) {
            // 视频接管走后台线程：缓存命中（配置同步预热）零解密等待，
            // 未命中才流式解密（打点 MB/s 供回归观测）；不占 binder 调用
            // 线程（mirror 创建路径可能持 DMS 锁）
            Thread({ startVideo(session, videoId) }, "sf-e3b-video")
                .apply { isDaemon = true }.start()
        }
        HookContext.log(
            Log.INFO,
            "E3b session overlaid on mirror buffer ${bs}x${bs} (view ${bw}x${bh}, vd=${w}x$h, image=${imageId ?: "none"}, video=${videoId ?: "none"})"
        )
    }

    /**
     * 视频接管（后台线程）：memfd 数据源 → MediaPlayer 挂会话 layer。
     * 全程 runCatching（system_server 铁律：任何异常不可上抛）；removed
     * 多查（解密前 / prepare 后）——中途会话被清（reload / VD release
     * 并发）就就地释放；失败回落替换图（[postFallback]——无图停留黑屏
     * 占位），fail-open。
     * player 登记与 removed 判定经 [Session.lock] 互斥：登记成功后
     * start 途中被清理，释放责任归 [removeLayer]；登记前被清理则就地
     * 释放（removeLayer 侧当时读到 null player 未动作，无双释放）
     */
    private fun startVideo(session: Session, videoId: String) {
        var player: MediaPlayer? = null
        var fd: FileDescriptor? = null
        var relayThread: HandlerThread? = null
        var relayReader: ImageReader? = null
        var started = false
        runCatching {
            if (session.removed) {
                HookContext.log(Log.INFO, "E3b video aborted before load (session removed) for $videoId")
                return@runCatching
            }
            val taken = ReplaceVideoStore.takePlayable(videoId) ?: run {
                // 失败细节（远程缺失/解密失败/memfd 不可用）已在
                // ReplaceVideoStore.load 内分级 WARN，此处回落替换图
                HookContext.log(Log.WARN, "E3b video source unavailable, fallback image for $videoId")
                postFallback(session)
                return@runCatching
            }
            if (session.removed) {
                HookContext.log(Log.INFO, "E3b video aborted after take (session removed) for $videoId")
                return@runCatching
            }
            val p: MediaPlayer
            if (taken.player != null) {
                // ---- 快路径：warm 预装配的 prepared player（round 7——
                // 会话侧 setDataSource→prepare→解码首帧的 ~0.5s 暴露窗口
                // 是录屏开头静态图残影的根因，提前到空闲窗口装配后此处
                // 只剩 setSurface + start，残影窗口压缩到解码首帧级别）----
                p = taken.player
                player = p
                HookContext.log(Log.INFO, "E3b fast path: prepared player taken for $videoId")
            } else {
                // ---- 慢路径：warm 未命中（首次录屏/缓存被取走/指纹
                // 逐出后的惰性重建），会话线程内全装配。fd 所有权：
                // MediaDataSource 形式成功 → fd 转交 dataSource（fd 置
                // null，release 时关）；其余形式/失败 → fd 留会话层兜底关----
                p = MediaPlayer()
                player = p
                val srcFd = taken.fd!!
                fd = srcFd
                var sourceSet = false
                try {
                    p.setDataSource(MemfdDataSource(srcFd, taken.length))
                    sourceSet = true
                    fd = null
                } catch (e: Exception) {
                    HookContext.log(
                        Log.WARN,
                        "E3b setDataSource(MediaDataSource) failed: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                if (!sourceSet) {
                    // 回退：fd 直传形式（读取偏移复位；fd 形式不接管 fd，
                    // 会话层兜底关闭语义不变）
                    runCatching { Os.lseek(srcFd, 0, OsConstants.SEEK_SET) }
                    p.setDataSource(srcFd, 0L, taken.length)
                    HookContext.log(Log.INFO, "E3b setDataSource(fd) fallback for $videoId")
                }
                // ---- setter 全部在 prepare 前（round 7 拉伸根因：OEM
                // NuPlayer 在 prepare 时锁定 scaling mode，之后设置无效
                // → 视频帧 stretch 到 buffer。round 5 把它挪到 prepare 后
                // 是误判真凶后的错误顺序——真凶是 SELinux，已由
                // MediaDataSource 形式修复）。fail-soft：循环/静音/缩放
                // 失败不阻断播放----
                try {
                    p.setLooping(true)
                } catch (e: Exception) {
                    HookContext.log(Log.WARN, "E3b setLooping failed: ${e.javaClass.simpleName}: ${e.message} (continue without loop)")
                }
                try {
                    p.setVolume(0f, 0f)
                } catch (e: Exception) {
                    HookContext.log(Log.WARN, "E3b setVolume failed: ${e.javaClass.simpleName}: ${e.message} (continue, audio risk)")
                }
                try {
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                } catch (e: Exception) {
                    HookContext.log(Log.WARN, "E3b setVideoScalingMode failed: ${e.javaClass.simpleName}: ${e.message} (continue, default fit)")
                }
                // ---- prepare（解复用 + 解码器装配）----
                try {
                    p.prepare()
                } catch (e: Exception) {
                    HookContext.log(Log.WARN, "E3b prepare failed: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
            // ---- 帧中继装配（round 13 拉伸终解）：round 8-12 五轮实测
            // 证伪了一切 layer 几何手段（builder 尺寸 / 事后事务 / 帧确立
            //  post / 边界内信箱均无效，视频 buffer 恒被 OEM 缩放到显示
            //  空间满幅）。改由本进程全权接管渲染：解码器输出到
            //  ImageReader（YUV_420_888，不参与 SF 合成，OEM 钳制无从
            //  施加），中继线程逐帧取最新帧 → CPU 半分辨率 YUV→RGB（含
            //  旋转元数据换算）→ canvas 中心裁剪铺满 layer 全屏 buffer
            //  （buffer == frame == 显示空间，SF 缩放退化为恒等——占位图
            //  canvas post 六轮实测恒正确，同一渲染面）。CPU 半分辨率
            //  （540x960 ≈ 52 万像素/帧）换取 30fps 余量，画质略软可接受
            //  （替换内容语义下流畅优先于锐度）。中继失败 fail-soft：停
            //  绘冻结末帧（好于真实内容）；装配失败回落解码器直挂 layer
            //  （round 8 语义：拉伸但可用）----
            val vw = p.videoWidth
            val vh = p.videoHeight
            runCatching {
                if (vw <= 0 || vh <= 0) throw IllegalStateException("stream dims $vw x $vh")
                val rot = taken.rotation
                // 解码器输出为旋转前 buffer（竖拍视频 sensor 横置）：按旋
                // 转元数据定 reader 初始尺寸（codec 侧 setBuffersGeometry
                // 失配时 BufferQueue 自愈重分配，转换侧按实际帧尺寸自适应）
                val dbw = if (rot == 90 || rot == 270) vh else vw
                val dbh = if (rot == 90 || rot == 270) vw else vh
                val th = HandlerThread("sf-e3b-relay").apply { start() }
                relayThread = th
                val rd = ImageReader.newInstance(dbw, dbh, ImageFormat.YUV_420_888, 4)
                rd.setOnImageAvailableListener(
                    { r -> onRelayFrame(session, r, vw, vh, rot) },
                    Handler(th.looper)
                )
                relayReader = rd
                p.setSurface(rd.surface)
                HookContext.log(Log.INFO, "E3b relay reader ${dbw}x${dbh} rot=$rot for $videoId")
                // 接管前导帧：firstFrame（快路径）→ 与视频首帧内容几何
                // 一致，无缝；null（慢路径）不绘制，占位保持黑屏或
                // peek 0 帧（残影语义由 overlayMirror 初始占位承担）
                postLead(session, taken.firstFrame)
            }.onFailure {
                HookContext.log(
                    Log.WARN,
                    "E3b relay setup failed (direct surface, stretched fallback): ${it.message}"
                )
                relayThread?.let { t -> runCatching { t.quitSafely() } }
                relayThread = null
                runCatching { relayReader?.close() }
                relayReader = null
                runCatching { p.setSurface(Surface(session.layer)) }
            }
            // ---- 共同尾段：登记 + start ----
            var aborted = false
            synchronized(session.lock) {
                if (session.removed) aborted = true
                else {
                    session.player = p
                    session.videoFd = fd
                    session.relayReader = relayReader
                    session.relayThread = relayThread
                }
            }
            if (aborted) {
                HookContext.log(Log.INFO, "E3b video aborted after prepare (session removed) for $videoId")
                return@runCatching
            }
            try {
                p.start()
            } catch (e: Exception) {
                HookContext.log(Log.WARN, "E3b start failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            started = true
            HookContext.log(
                Log.INFO,
                "E3b video started on mirror buffer ${session.dstW}x${session.dstH} (video=$videoId, stream=${p.videoWidth}x${p.videoHeight})"
            )
        }.onFailure {
            HookContext.log(Log.WARN, "E3b video start failed for $videoId: ${it.message}")
            // 异常路径回滚登记（若已登记）：就地释放，不待会话清理
            synchronized(session.lock) {
                if (session.player === player) {
                    session.player = null
                    session.videoFd = null
                    session.relayReader = null
                    session.relayThread = null
                }
            }
            runCatching { player?.release() }
            fd?.let { runCatching { Os.close(it) } }
            relayReader?.let { r -> runCatching { r.close() } }
            relayThread?.let { t -> runCatching { t.quitSafely() } }
            started = false
            // 回落替换图（fail-open 到图；无图保持黑屏占位）
            postFallback(session)
        }
        // 早退路径（removed 中断/数据源失败）：player 未登记，就地释放
        if (!started) {
            synchronized(session.lock) {
                if (session.player == null) {
                    runCatching { player?.release() }
                    fd?.let { runCatching { Os.close(it) } }
                    relayReader?.let { r -> runCatching { r.close() } }
                    relayThread?.let { t -> runCatching { t.quitSafely() } }
                }
            }
        }
    }

    /** 中继绘制双线性插值（半分辨率位图放大） */
    private val relayPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * 帧中继核心（round 16，中继线程）：取最新帧 → 实际几何解析
     * （[copyPlaneSafe] + limit 联立解，不信任 image 报告几何）→ 半分辨
     * 率 YUV→RGB（含旋转换算）→ 中心裁剪铺满 layer 全屏 canvas。单帧
     * 失败可恢复（过渡帧跳帧），连续 3 次失败判死（[failRelay]，冻结
     * 末帧 fail-soft），绝不外抛（listener 线程死亡 = 后续帧全部丢弃，
     * 录制内容停留在末帧——好于真实内容）
     */
    private fun onRelayFrame(
        session: Session,
        reader: ImageReader,
        vw: Int,
        vh: Int,
        rot: Int
    ) {
        if (session.removed || session.relayDead) return
        // 目标尺寸实时读会话（方向看护刷新后随之翻转——横竖屏自适应核心）
        val w = session.dstW
        val h = session.dstH
        if (w <= 0 || h <= 0) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            session.relayFrames++
            val bw = image.width
            val bh = image.height
            if (bw <= 0 || bh <= 0) return
            // 过渡帧防御：codec setBuffersGeometry 重分配瞬间 planes 可能
            // 为 null 元素 / buffer 未就绪——跳帧可恢复（连败计数保护）
            val planes = image.planes
            if (planes.size < 3 || planes[0] == null || planes[1] == null || planes[2] == null) {
                failRelay(session, "planes invalid (${planes.size})")
                return
            }
            if (planes[0].buffer == null || planes[1].buffer == null || planes[2].buffer == null) {
                failRelay(session, "plane buffer null")
                return
            }
            // ---- 实际几何解析（round 16）：OEM plane limit/stride 布局与
            // image 报告几何错配（round 15 实测 3 连败 BufferUnderflow，
            // reader 重建条件未触发——U rowStride 与报告宽一致，炸点在
            // 行距×行数越 limit，错配不在宽度维度）。本进程不再信任
            // image 报告几何：先按标准布局 limit 公式（limit = stride*
            // (rows-1)+行有效宽）预检报告几何可读性，越界则由 Y/U 两
            // 平面 limit 与 stride 联立解出实际 buffer 几何（NV12/NV21
            // semi-planar 与 I420 planar 两系公式），解出即真相——两种
            // codec 旋转行为（预旋转/非预旋转输出）均自适。解不出（OEM
            // limit 无公式）回落报告几何 + [copyPlaneSafe] 防御拷贝
            // （limit 收缩 + 末行复制），任何布局下中继不判死 ----
            val yPlane = planes[0]
            val uPlane = planes[1]
            val yR = yPlane.rowStride
            val uR = uPlane.rowStride
            val vR = planes[2].rowStride
            val uS = uPlane.pixelStride
            val vS = planes[2].pixelStride
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val yLim = yBuf.limit()
            val uLim = uBuf.limit()
            // 可完整读取行数（limit 语义内）
            fun rowsReadable(lim: Int, stride: Int, cw: Int): Int =
                if (stride > 0 && lim >= cw) (lim - cw) / stride + 1 else 0
            var gw = bw
            var gh = bh
            if (rowsReadable(yLim, yR, bw) < bh ||
                rowsReadable(uLim, uR, ((bw + 1) / 2) * uS) < (bh + 1) / 2
            ) {
                val semi = uS >= 2
                val denom = if (semi) yR - uR / 2 else yR - uR
                // hSol 先行范围校验（防 yR*(hSol-1) 中间溢出——异常解可
                // 达千万级）
                val hSol = if (denom != 0) {
                    if (semi) {
                        (yLim - uLim - uR + yR) / denom
                    } else {
                        (yLim + yR - 2 * uLim - 2 * uR) / denom
                    }
                } else -1
                if (hSol in 16..4320) {
                    val wSol = if (semi) {
                        yLim - yR * (hSol - 1)
                    } else {
                        2 * (uLim - uR * (hSol / 2 - 1))
                    }
                    if (wSol in 16..4320) {
                        gw = wSol
                        gh = hSol
                    }
                }
            }
            // 旋转有效性自检：元数据称 90/270 但 buffer 已是后置方向（个别
            // codec 预旋转输出）→ 按无旋转处理，防双重旋转（实际几何下判定）
            val rotEff = if ((rot == 90 || rot == 270) && gw == vw && gh == vh && vw != vh) 0 else rot
            val evw = if (rotEff == 90 || rotEff == 270) gh else gw
            val evh = if (rotEff == 90 || rotEff == 270) gw else gh
            val hw = (evw + 1) / 2
            val hh = (evh + 1) / 2
            val yW = gw
            val cW = (gw + 1) / 2
            val cH = (gh + 1) / 2
            // 平面整块拷出（防御拷贝：limit 语义内收缩，不足行复制末可用
            // 行——OEM 非标布局下画面局部异常但中继存活）
            val yNeed = yW * gh
            val uNeed = cW * uS * cH
            val vNeed = cW * vS * cH
            val yArr = session.relayY.ensure(yNeed).also { copyPlaneSafe(yPlane, it, yR, yW, gh) }
            val uArr = session.relayU.ensure(uNeed).also { copyPlaneSafe(uPlane, it, uR, cW * uS, cH) }
            val vArr = session.relayV.ensure(vNeed).also { copyPlaneSafe(planes[2], it, vR, cW * vS, cH) }
            val uRow = cW * uS
            val vRow = cW * vS
            val out = session.relayOut.ensure(hw * hh)
            var idx = 0
            for (oy in 0 until hh) {
                val ry = (oy * 2).coerceAtMost(evh - 1)
                for (ox in 0 until hw) {
                    val rx = (ox * 2).coerceAtMost(evw - 1)
                    val sx: Int
                    val sy: Int
                    when (rotEff) {
                        90 -> { sx = ry; sy = gh - 1 - rx }
                        180 -> { sx = gw - 1 - rx; sy = gh - 1 - ry }
                        270 -> { sx = gw - 1 - ry; sy = rx }
                        else -> { sx = rx; sy = ry }
                    }
                    val y0 = yArr[sy * yW + sx].toInt() and 0xFF
                    val su = sx shr 1
                    val sv = sy shr 1
                    val u0 = uArr[sv * uRow + su * uS].toInt() and 0xFF
                    val v0 = vArr[sv * vRow + su * vS].toInt() and 0xFF
                    // BT.601 有限范围近似（替换内容语义下精度足够）
                    val r = y0 + ((1436 * (v0 - 128)) shr 10)
                    val g = y0 - (((351 * (u0 - 128)) + (728 * (v0 - 128))) shr 10)
                    val b = y0 + ((1815 * (u0 - 128)) shr 10)
                    out[idx++] =
                        (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
                }
            }
            var bmp = session.relayBitmap
            if (bmp == null || bmp.width != hw || bmp.height != hh) {
                bmp = Bitmap.createBitmap(hw, hh, Bitmap.Config.ARGB_8888)
                session.relayBitmap = bmp
            }
            bmp.setPixels(out, 0, hw, 0, 0, hw, hh)
            // 中心裁剪铺满当前方向可见条（方形 blanket 左上 [0,w]×[0,h]；
            // 条带外区域本方向不可见，旋转由看护线程刷新条带几何）→
            // 半分辨率位图坐标
            val strip = cropStrip(evw, evh, w, h)
            val src = Rect(
                (strip.left + 1) / 2, (strip.top + 1) / 2,
                (strip.right + 1) / 2, (strip.bottom + 1) / 2
            )
            val surface = session.relaySurface
                ?: Surface(session.layer).also { session.relaySurface = it }
            synchronized(session.lock) {
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawBitmap(bmp, src, Rect(0, 0, w, h), relayPaint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            }
            // 成功帧归零连败计数（真实"连续 3 次"语义）
            session.relayFails = 0
            if (!session.relayLogged) {
                session.relayLogged = true
                HookContext.log(Log.INFO, "E3b relay frame #${session.relayFrames} ok (${gw}x$gh rotEff=$rotEff)")
            }
        } catch (t: Throwable) {
            failRelay(session, "${t.javaClass.simpleName}: ${t.message}", t)
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * 中继单帧失败处理：可恢复语义（round 14）——过渡帧（codec 几何
     * 重分配瞬间的 null plane/buffer）跳帧即愈，连续 3 次失败才判死
     * （冻结末帧 fail-soft）。日志带异常类名与堆栈首行（round 13 只打
     * message 为 null 无法取证）
     */
    private fun failRelay(session: Session, why: String, t: Throwable? = null) {
        session.relayFails++
        val at = t?.stackTrace?.firstOrNull()?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""
        HookContext.log(Log.WARN, "E3b relay fail #${session.relayFails} (frame #${session.relayFrames}): $why$at")
        if (session.relayFails >= 3) {
            session.relayDead = true
            HookContext.log(Log.WARN, "E3b relay dead after 3 consecutive failures (frozen last frame)")
        }
    }

    /** 接管前导帧：firstFrame 与中继帧同几何（中心裁剪铺满，快路径
     *  无缝）；null 不绘制——慢路径占位已是黑屏或 peek 命中的视频 0 帧
     *  （round 17 初始占位不画图后，此处重画黑屏反而会把 0 帧盖回黑屏，
     *  如 rewarm 未完成的下一会话）。与中继共用 relaySurface（同一
     *  BufferQueue 生产者，避免多实例） */
    private fun postLead(session: Session, lead: Bitmap?) {
        val bmp = lead ?: return
        val w = session.dstW
        val h = session.dstH
        if (w <= 0 || h <= 0) return
        runCatching {
            val sf = session.relaySurface
                ?: Surface(session.layer).also { session.relaySurface = it }
            synchronized(session.lock) {
                val c = sf.lockHardwareCanvas()
                try {
                    c.drawBitmap(bmp, cropStrip(bmp.width, bmp.height, w, h), Rect(0, 0, w, h), relayPaint)
                } finally {
                    sf.unlockCanvasAndPost(c)
                }
            }
        }.onFailure {
            HookContext.log(Log.WARN, "E3b lead post failed: ${it.message}")
        }
    }

    /**
     * 视频失败回落替换图（fail-open 到图，round 17 残影根除的语义补全：
     * 初始占位不再画图后，图只在视频失败时出现）。中心裁剪铺满（不旋转
     * 不变形）；无图（仅视频配置）不绘制——保持黑屏占位。统一走
     * relaySurface（单生产者实例，postLead 前调用时按需创建）
     */
    private fun postFallback(session: Session) {
        val img = session.fallbackImage ?: return
        if (session.removed) return
        val w = session.dstW
        val h = session.dstH
        if (w <= 0 || h <= 0) return
        runCatching {
            val sf = session.relaySurface
                ?: Surface(session.layer).also { session.relaySurface = it }
            synchronized(session.lock) {
                val c = sf.lockHardwareCanvas()
                try {
                    c.drawBitmap(img, cropStrip(img.width, img.height, w, h), Rect(0, 0, w, h), relayPaint)
                } finally {
                    sf.unlockCanvasAndPost(c)
                }
            }
        }.onFailure {
            HookContext.log(Log.WARN, "E3b fallback post failed: ${it.message}")
        }
    }

    /** 中心裁剪条（源后置方向坐标）：源比目标相对更宽 → 裁列；更窄 → 裁行 */
    private fun cropStrip(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
        val a = sw.toFloat() / sh
        val t = dw.toFloat() / dh
        return when {
            a > t + 0.001f -> {
                val cw = (sh * t).toInt().coerceIn(1, sw)
                Rect((sw - cw) / 2, 0, (sw + cw) / 2, sh)
            }
            a < t - 0.001f -> {
                val ch = (sw / t).toInt().coerceIn(1, sh)
                Rect(0, (sh - ch) / 2, sw, (sh + ch) / 2)
            }
            else -> Rect(0, 0, sw, sh)
        }
    }

    /**
     * 防御性平面拷贝（round 16）：按 plane buffer 的 limit 语义计算可完整
     * 读取的行数——读取量不足时先尝试扩 limit 到 capacity（OEM 保守
     * limit 不含末行 stride 偏移的常见变体；capacity 为 gralloc 分配上界，
     * 读入最多含 padding 无害），仍不足则截断行数并以末可用行复制填充
     * （画面底部重复纹理，好于中继判死）。绝不抛 BufferUnderflow。
     * 返回实际读取行数（0 = 一行都读不了，arr 保持零值）
     */
    private fun copyPlaneSafe(
        plane: android.media.Image.Plane,
        arr: ByteArray,
        rowStride: Int,
        cw: Int,
        h: Int
    ): Int {
        val buf = plane.buffer
        val lim = buf.limit()
        var rows = if (rowStride > 0 && lim >= cw) (lim - cw) / rowStride + 1 else 0
        if (rows < h && rowStride > 0) {
            val cap = buf.capacity()
            if (cap > lim && cap >= (h - 1).toLong() * rowStride + cw) {
                buf.limit(cap)
                rows = h
            }
        }
        rows = rows.coerceIn(0, h)
        if (rows <= 0) return 0
        var pos = 0
        for (row in 0 until rows) {
            buf.position(row * rowStride)
            buf.get(arr, pos, cw)
            pos += cw
        }
        if (rows < h) {
            val last = (rows - 1) * cw
            for (row in rows until h) {
                System.arraycopy(arr, last, arr, pos, cw)
                pos += cw
            }
        }
        return rows
    }

    private fun clamp8(c: Int): Int = if (c < 0) 0 else if (c > 255) 255 else c

    /** ByteArray 容量确保（尺寸变化时重分配；中继线程独占无竞态） */
    private fun ByteArray?.ensure(n: Int): ByteArray =
        if (this != null && size >= n) this else ByteArray(n)

    /** IntArray 容量确保（同上） */
    private fun IntArray?.ensure(n: Int): IntArray =
        if (this != null && size >= n) this else IntArray(n)

    /** 摘除并释放单会话（player + memfd + 中继资源 + layer），异步重建预热缓存 */
    private fun removeLayer(mirrorSc: Any) {
        val session = layers.remove(mirrorSc) ?: return
        synchronized(session.lock) { session.removed = true }
        runCatching {
            session.player?.release()
            session.videoFd?.let { runCatching { Os.close(it) } }
            session.relayReader?.let { r -> runCatching { r.close() } }
            session.relayThread?.let { t -> runCatching { t.quitSafely() } }
            session.relaySurface?.let { s -> runCatching { s.release() } }
            session.relayBitmap?.let { b -> runCatching { b.recycle() } }
            SurfaceControl.Transaction().use { t ->
                t.reparent(session.layer, null)
                t.apply()
            }
            session.layer.release()
        }
        // re-warm：快路径取走的 prepared player 已随会话销毁，后台重建
        // 供下次录屏（连续录屏间零装配等待）
        ReplaceVideoStore.rewarm()
    }

    // ==================== 方向自适应（横竖屏切换） ====================

    /**
     * 方向看护（旋转事件源，2026-09-14 二轮定稿：替换不跟随系统旋转）：
     * 会话存在期间 50ms 轮询显示逻辑尺寸，变化即刷新会话条带几何并重画
     * （[redrawStrip]）——方形 blanket 保证覆盖零空窗，此处只负责内容
     * 几何跟上。轮询而非 hook 显示遍历：事件驱动路径在 DMS 持锁遍历内
     * 触发回调，锁序风险高于 50ms 检测延迟。无会话时线程自灭
     * （system_server 不养常驻轮询），下次会话重建——经 [orientLock]
     * 双检无丢会话窗口
     */
    private fun ensureOrientationWatch() {
        synchronized(orientLock) {
            if (orientWatch?.isAlive == true) return
            orientWatch = Thread({ watchOrientation() }, "sf-e3b-orient")
                .apply { isDaemon = true }
                .also { it.start() }
        }
    }

    private fun watchOrientation() {
        while (true) {
            if (runCatching { Thread.sleep(ORIENT_POLL_MS) }.isFailure) return
            if (layers.isEmpty()) {
                synchronized(orientLock) {
                    if (layers.isEmpty()) {
                        orientWatch = null
                        return
                    }
                }
            }
            val dm = displayLogicalSize() ?: continue
            val snapshot = synchronized(layers) { layers.entries.map { it.key to it.value } }
            for ((mirrorSc, s) in snapshot) {
                if (s.removed || s.dstW <= 0 || s.dstH <= 0) continue
                if (dm.first == s.dstW && dm.second == s.dstH) continue
                if (maxOf(dm.first, dm.second) > s.bufS) {
                    // 显示尺寸超出方形 blanket（折叠展开/外接屏等旋转之外
                    // 的显示变化）：条带盖不住，整会话重挂（策略与内容源
                    // 按当前前台重解析）
                    runCatching {
                        removeLayer(mirrorSc)
                        overlayMirror(mirrorSc, dm.first, dm.second)
                    }.onFailure {
                        HookContext.log(Log.WARN, "E3b remount on display change failed: ${it.message}")
                    }
                } else {
                    // 旋转（长边不变）：方形 blanket 仍全覆盖零空窗——刷新
                    // 条带尺寸并同步重画；视频会话随后的中继帧自动接续
                    s.dstW = dm.first
                    s.dstH = dm.second
                    runCatching { redrawStrip(s) }
                }
            }
        }
    }

    /**
     * 方向变化条带重画（看护线程）：relayBitmap（中继末帧，半分辨率）/
     * 回落图，中心裁剪铺满新方向条带；两者皆无（prepare 中的视频会话）
     * 画黑。与中继/视频线程的绘制经 [Session.lock] 互斥（同一
     * relaySurface 的 canvas 非线程安全）；relayBitmap 与会话清理的
     * recycle 竞态由 runCatching 兜底（失败仅丢一帧重画，图层保持
     * 陈旧假内容——无暴露）
     */
    private fun redrawStrip(session: Session) {
        if (session.removed) return
        val w = session.dstW
        val h = session.dstH
        if (w <= 0 || h <= 0) return
        runCatching {
            val lead = session.relayBitmap ?: session.fallbackImage
            val sf = session.relaySurface
                ?: Surface(session.layer).also { session.relaySurface = it }
            synchronized(session.lock) {
                val c = sf.lockHardwareCanvas()
                try {
                    if (lead != null) {
                        c.drawBitmap(lead, cropStrip(lead.width, lead.height, w, h), Rect(0, 0, w, h), relayPaint)
                    } else {
                        c.drawColor(Color.BLACK)
                    }
                } finally {
                    sf.unlockCanvasAndPost(c)
                }
            }
            HookContext.log(Log.INFO, "E3b session strip geometry rotated to ${w}x$h")
        }.onFailure {
            HookContext.log(Log.WARN, "E3b strip redraw failed: ${it.message}")
        }
    }

    /**
     * VD 释放（录屏停止）：全量清会话。全清不区分具体 VD（callback 与
     * mirror SC 无公开映射），多并发录屏会话罕见，误清代价（另一会话
     * 假图消失 = 原生内容直至其 reload 重挂）小于 MediaPlayer 泄漏代价
     */
    private fun onVirtualDisplayReleased() {
        if (layers.isEmpty()) return
        synchronized(layers) {
            layers.keys.toList().forEach { removeLayer(it) }
        }
        HookContext.log(Log.INFO, "E3b sessions released on virtual display release")
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
                // 兜底尺寸传入（overlayMirror 内部优先解析显示空间 logical
                // ——含旋转；此值仅在 logical 链解析失败时生效）
                val dm = displaySize()
                if (dm != null) runCatching { overlayMirror(mirror, dm.first, dm.second) }
            }
        }
    }

    // ==================== 前台解析 ====================

    /** VD config 形参的 int 读取器组（width/height/flags） */
    private class CfgAccessors(
        val width: (Any) -> Int?,
        val height: (Any) -> Int?,
        val flags: (Any) -> Int?,
    )

    /** 单 int 读取器：getter 优先（公开 VirtualDisplayConfig），字段兜底
     *  （internal parcelable 为 public final m* 字段，兼容裸字段名） */
    private fun intAccessor(cls: Class<*>, getter: String, vararg fields: String): (Any) -> Int? {
        val g = runCatching { cls.getMethod(getter) }.getOrNull()
        val fs = fields.mapNotNull { f ->
            runCatching { cls.getDeclaredField(f).apply { isAccessible = true } }.getOrNull()
        }
        return { o ->
            g?.let { m -> runCatching { m.invoke(o) as? Int }.getOrNull() }
                ?: fs.firstNotNullOfOrNull { f -> runCatching { f.getInt(o) }.getOrNull() }
        }
    }

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
        val statsClass = Class.forName("android.view.SurfaceControl\$DisplayStatistics")
        val getStats = scClass.getDeclaredMethod("getDisplayStatistics", token::class.java)
            .apply { isAccessible = true }
        val stats = getStats.invoke(null, token) ?: return null
        val widthField = statsClass.getDeclaredField("width").apply { isAccessible = true }
        val heightField = statsClass.getDeclaredField("height").apply { isAccessible = true }
        (widthField.getInt(stats) to heightField.getInt(stats))
    }.getOrNull()

    /**
     * 主屏显示空间尺寸（logicalWidth/Height：当前方向的全显示尺寸，旋转
     * 已应用——横屏时宽高互换）。假图层 reparent 到 mirror 后的坐标系
     * 即此空间，buffer 按此尺寸建才能铺满。解析失败 null（调用方回落）
     */
    private fun displayLogicalSize(): Pair<Int, Int>? = runCatching {
        val dmi = getServiceM?.invoke(null, dmiClass) ?: return null
        // 0 = Display.DEFAULT_DISPLAY（录屏 mirror 的源显示）
        val info = getDisplayInfoM?.invoke(dmi, 0) ?: return null
        val w = logicalWidthF?.getInt(info) ?: return null
        val h = logicalHeightF?.getInt(info) ?: return null
        if (w > 0 && h > 0) w to h else null
    }.getOrNull()
}
