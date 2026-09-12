package fake.screenshot.hooks

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.os.Binder
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
 *   同步先 post 替换图或黑屏首帧（图加载失败兜底），后台线程流式
 *   解密视频信封到 memfd（[ReplaceVideoStore]）后 MediaPlayer 接管
 *   同一 layer（looping / 静音 / CROPPING 缩放铺满）——视频 prepare
 *   期间录屏开头即假内容，零真实内容窗口。视频任一环节失败 → 停留
 *   首帧（fail-open 到图/黑屏）
 * - hook DMS#releaseVirtualDisplay*（录屏停止）：全量清会话——视频
 *   会话持有 MediaPlayer 解码线程与 memfd 不可泄漏（静态图时代文档化
 *   接受的单 layer 泄漏面在视频语义下不可接受）
 * - 录屏会话锁定：内容源在 mirror 创建帧按当时前台解析（换图/换策略
 *   由配置 reload 重挂当前 mirror 生效；会话内前台切换不追踪——
 *   录屏是持续行为，逐帧切换语义反而暴露替换特征）
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

    /**
     * 录屏会话资源：假图层（常驻）+ 视频播放器与 memfd（视频会话）。
     * [lock] 序化 player 登记与 removed 判定（[removeLayer] 同锁）——
     * 无锁窗口下 start() 前后登记的 player 可能错过清理（MediaPlayer
     * + memfd 泄漏）
     */
    private class Session(val layer: SurfaceControl) {
        @Volatile var player: MediaPlayer? = null
        @Volatile var videoFd: FileDescriptor? = null
        @Volatile var removed = false
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
                                        HookContext.log(Log.INFO, "E3b auto-mirror VD registered ${w}x$h")
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
     * 内容——不引入新行为）；两者命中 → 图为首帧、视频接管
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

        // 假图层 buffer 尺寸 = mirror 树的显示空间（物理 logical，含旋转），
        // VD 请求分辨率仅作解析失败时的兜底（等比缩放的录屏降分辨率场景
        // 退化为左上角覆盖，好于无图——hook 侧日志可见以便定位）
        val (bw, bh) = displayLogicalSize() ?: run {
            HookContext.log(Log.WARN, "E3b display logical size unresolved, fallback to VD ${w}x$h")
            w to h
        }
        val session = Session(
            SurfaceControl.Builder()
                .setName("sf-e3b")
                .setBufferSize(bw, bh)
                .setFormat(PixelFormat.RGBA_8888)
                .build()
        )
        // 同步首帧（buffer 先 post——layer 从 show 起即有内容，录屏开头
        // 零真实内容窗口）：图命中 → 替换图拉伸铺满（无缺角）；未命中
        // （仅视频配置）→ 黑屏。视频线程 prepared 后首帧到达，同一
        // layer 的 buffer 自然被接管。
        // Surface 非 Closeable（无 use 扩展），手动 release——已 post 的
        // buffer 仍挂在 layer 上，Surface 释放不影响显示
        val surface = Surface(session.layer)
        try {
            val canvas = surface.lockHardwareCanvas()
            if (fake != null) canvas.drawBitmap(fake, null, Rect(0, 0, bw, bh), null)
            else canvas.drawColor(Color.BLACK)
            surface.unlockCanvasAndPost(canvas)
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
        if (videoId != null) {
            // 视频接管走后台线程：缓存命中（配置同步预热）零解密等待，
            // 未命中才流式解密（打点 MB/s 供回归观测）；不占 binder 调用
            // 线程（mirror 创建路径可能持 DMS 锁）
            Thread({ startVideo(session, videoId, bw, bh) }, "sf-e3b-video")
                .apply { isDaemon = true }.start()
        }
        HookContext.log(
            Log.INFO,
            "E3b session overlaid on mirror buffer ${bw}x$bh (vd=${w}x$h, image=${imageId ?: "none"}, video=${videoId ?: "none"})"
        )
    }

    /**
     * 视频接管（后台线程）：memfd 数据源 → MediaPlayer 挂会话 layer。
     * 全程 runCatching（system_server 铁律：任何异常不可上抛）；removed
     * 多查（解密前 / prepare 后）——中途会话被清（reload / VD release
     * 并发）就就地释放；失败停留首帧（图/黑屏），fail-open。
     * player 登记与 removed 判定经 [Session.lock] 互斥：登记成功后
     * start 途中被清理，释放责任归 [removeLayer]；登记前被清理则就地
     * 释放（removeLayer 侧当时读到 null player 未动作，无双释放）
     */
    private fun startVideo(session: Session, videoId: String, w: Int, h: Int) {
        var player: MediaPlayer? = null
        var fd: FileDescriptor? = null
        var started = false
        runCatching {
            if (session.removed) {
                HookContext.log(Log.INFO, "E3b video aborted before load (session removed) for $videoId")
                return@runCatching
            }
            val loaded = ReplaceVideoStore.playableFd(videoId) ?: run {
                // 失败细节（远程缺失/解密失败/memfd 不可用）已在
                // ReplaceVideoStore.load 内分级 WARN，此处只标记接管中止
                HookContext.log(Log.WARN, "E3b video source unavailable, stay on first frame for $videoId")
                return@runCatching
            }
            fd = loaded.first
            if (session.removed) {
                HookContext.log(Log.INFO, "E3b video aborted after load (session removed) for $videoId")
                return@runCatching
            }
            val p = MediaPlayer()
            player = p
            // ---- Stage 1: 数据源（三形式回退链；OEM 的 fd 形式失败根因
            // 未明——system_server 进程环境与 App 差异，逐形式试错并
            // 留分级日志供下轮定位）----
            var sourceSet = false
            try {
                p.setDataSource(loaded.first, 0L, loaded.second)
                sourceSet = true
            } catch (e: Exception) {
                HookContext.log(
                    Log.WARN,
                    "E3b setDataSource(fd,0,${loaded.second}) failed: ${e.javaClass.simpleName}: ${e.message}"
                )
                // 回退 1：单参 fd 形式（长度语义不同——读到 EOF）
                runCatching { Os.lseek(loaded.first, 0, OsConstants.SEEK_SET) }
                try {
                    p.setDataSource(loaded.first)
                    sourceSet = true
                    HookContext.log(Log.INFO, "E3b setDataSource(fd) fallback succeeded for $videoId")
                } catch (e2: Exception) {
                    HookContext.log(
                        Log.WARN,
                        "E3b setDataSource(fd) failed: ${e2.javaClass.simpleName}: ${e2.message}"
                    )
                    // 回退 2：/proc/self/fd/N 路径形式（native open(2) 本地
                    // 打开，绕开 fd 直传路径的任何 OEM 限制）
                    fdIntOf(loaded.first)?.let { intFd ->
                        try {
                            p.setDataSource("/proc/self/fd/$intFd")
                            sourceSet = true
                            HookContext.log(Log.INFO, "E3b setDataSource(/proc/self/fd) fallback succeeded for $videoId")
                        } catch (e3: Exception) {
                            HookContext.log(
                                Log.WARN,
                                "E3b setDataSource(/proc/self/fd/$intFd) failed: ${e3.javaClass.simpleName}: ${e3.message}"
                            )
                        }
                    }
                    // 三形式皆败：memfd 内容取证（前 64 字节 hex——验证
                    // 解密产物确为 MP4 头 'ftyp'，区分内容坏 vs 环境坏）
                    if (!sourceSet) {
                        HookContext.log(Log.WARN, "E3b all setDataSource forms failed for $videoId, dumping memfd head")
                        dumpMemfdHead(loaded.first, videoId)
                        throw e2
                    }
                }
            }
            // ---- Stage 2: 渲染面（同一 layer 双 producer 接力：首帧 Surface
            // 已 release，此处新建 Surface 挂 player）----
            try {
                p.setSurface(Surface(session.layer))
            } catch (e: Exception) {
                HookContext.log(Log.WARN, "E3b setSurface failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            p.setLooping(true)
            // 静音铁律：替换视频音频外放 = 替换行为即刻暴露
            p.setVolume(0f, 0f)
            // 裁剪铺满 layer buffer 尺寸（显示空间），无黑边
            p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            // ---- Stage 3: prepare（解复用 + 解码器装配）----
            try {
                p.prepare()
            } catch (e: Exception) {
                HookContext.log(Log.WARN, "E3b prepare failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            var aborted = false
            synchronized(session.lock) {
                if (session.removed) aborted = true
                else {
                    session.player = p
                    session.videoFd = loaded.first
                }
            }
            if (aborted) {
                HookContext.log(Log.INFO, "E3b video aborted after prepare (session removed) for $videoId")
                return@runCatching
            }
            // ---- Stage 4: start ----
            try {
                p.start()
            } catch (e: Exception) {
                HookContext.log(Log.WARN, "E3b start failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            started = true
            HookContext.log(Log.INFO, "E3b video started on mirror buffer ${w}x$h (video=$videoId)")
        }.onFailure {
            HookContext.log(Log.WARN, "E3b video start failed for $videoId: ${it.message}")
            // 异常路径回滚登记（若已登记）：就地释放，不待会话清理
            synchronized(session.lock) {
                if (session.player === player) {
                    session.player = null
                    session.videoFd = null
                }
            }
            runCatching { player?.release() }
            fd?.let { runCatching { Os.close(it) } }
            started = false
        }
        // 早退路径（removed 中断/数据源失败）：player 未登记，就地释放
        if (!started) {
            synchronized(session.lock) {
                if (session.player == null) {
                    runCatching { player?.release() }
                    fd?.let { runCatching { Os.close(it) } }
                }
            }
        }
    }

    /** FileDescriptor → 原始 int fd（libcore getInt$，system_server 反射无限制） */
    private fun fdIntOf(fd: FileDescriptor): Int? = runCatching {
        FileDescriptor::class.java.getMethod("getInt$").let { m ->
            m.isAccessible = true
            m.invoke(fd) as Int
        }
    }.getOrNull()

    /** memfd 头 64 字节 hex 取证（Os.read 不经流封装不关 fd） */
    private fun dumpMemfdHead(fd: FileDescriptor, videoId: String) {
        runCatching {
            val probe = ByteArray(64)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            var off = 0
            while (off < probe.size) {
                val n = Os.read(fd, probe, off, probe.size - off)
                if (n <= 0) break
                off += n
            }
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            HookContext.log(
                Log.WARN,
                "E3b memfd head for $videoId: ${probe.joinToString(" ") { String.format("%02x", it) }}"
            )
        }
    }

    /** 摘除并释放单会话（player + memfd + layer） */
    private fun removeLayer(mirrorSc: Any) {
        val session = layers.remove(mirrorSc) ?: return
        synchronized(session.lock) { session.removed = true }
        runCatching {
            session.player?.release()
            session.videoFd?.let { runCatching { Os.close(it) } }
            SurfaceControl.Transaction().use { t ->
                t.reparent(session.layer, null)
                t.apply()
            }
            session.layer.release()
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
