package fake.screenshot.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import fake.screenshot.Auxiliary
import fake.screenshot.Auxiliary.enableScreenshotExclusion
import fake.screenshot.services.privileged.IRootDisplayCallback
import fake.screenshot.services.privileged.RootDisplayConnection
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.OverlayServiceManager
import fake.screenshot.views.CornerHandleView
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
import java.lang.ref.WeakReference

class DisplayOverlayService : Service() {

    companion object {
        private var instanceRef: WeakReference<DisplayOverlayService>? = null

        @JvmStatic
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DisplayOverlayService::class.java))
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, DisplayOverlayService::class.java))
        }

        @JvmStatic
        fun getSize(): Pair<Int, Int>? {
            return instanceRef?.get()?.let { service ->
                service.params.width to service.params.height
            }
        }

        @JvmStatic
        fun getPosition(): Pair<Int, Int>? {
            return instanceRef?.get()?.let { service ->
                service.params.x to service.params.y
            }
        }

        @JvmStatic
        fun setSizePosition(x: Int, y: Int, width: Int, height: Int) {
            instanceRef?.get()?.let { service ->
                service.params.x = x
                service.params.y = y
                service.params.width = width
                service.params.height = height
                if (service.useRootWindow) {
                    // 几何真相源始终是本服务持有的 params；root 端同步更新
                    RootDisplayConnection.get()?.setGeometry(x, y, width, height)
                } else {
                    runCatching {
                        service.windowManager.updateViewLayout(service.floatingView, service.params)
                    }
                }
                service.cornerHandleView?.invalidate()
                service.refreshContent()
            }
        }

        @JvmStatic
        fun switchMedia(delta: Int) {
            instanceRef?.get()?.let { service ->
                val newIndex = service.currentIndex + delta
                if (newIndex in service.mediaList.indices) {
                    service.showMedia(newIndex)
                }
            }
        }

        @JvmStatic
        fun togglePlayPause() {
            // root 托管优先；连接不可用时自动走本地路径
            RootDisplayConnection.get()?.let {
                runCatching { it.togglePlayPause() }
                return
            }
            instanceRef?.get()?.let { service ->
                service.mediaPlayer?.let { mp ->
                    runCatching {
                        if (mp.isPlaying) {
                            mp.pause()
                        } else {
                            mp.start()
                        }
                    }
                }
            }
        }

        // 视频快进/快退：deltaMs 正为快进，负为快退
        @JvmStatic
        fun seekMedia(deltaMs: Int) {
            RootDisplayConnection.get()?.let {
                runCatching { it.seekBy(deltaMs) }
                return
            }
            instanceRef?.get()?.let { service ->
                val mp = service.mediaPlayer ?: return
                runCatching {
                    val duration = mp.duration
                    if (duration > 0) {
                        val target = (mp.currentPosition + deltaMs)
                            .coerceIn(0, (duration - 250).coerceAtLeast(0))
                        mp.seekTo(target)
                    }
                }
            }
        }

        @JvmStatic
        fun scaleMedia(factor: Float) {
            val service = instanceRef?.get() ?: return
            if (service.useRootWindow) {
                RootDisplayConnection.get()?.let {
                    runCatching { it.scaleImage(factor) }
                }
                return
            }
            service.applyScale(factor)
        }

        @JvmStatic
        fun panMedia(dx: Float, dy: Float) {
            // 仅图片支持平移
            val service = instanceRef?.get() ?: return
            if (service.useRootWindow) {
                RootDisplayConnection.get()?.let {
                    runCatching { it.panImage(dx, dy) }
                }
                return
            }
            if (service.mediaView is ImageView) {
                service.panX += dx
                service.panY += dy
                service.clampPan()
                service.updateImageMatrix()
            }
        }

        // 视频状态由本服务在 showMedia 时按 MIME 记录，
        // root 托管与本地模式统一读取，避免逐事件跨进程查询
        @JvmStatic
        fun isCurrentVideo(): Boolean {
            return instanceRef?.get()?.currentIsVideo ?: false
        }

        @JvmStatic
        fun setDisplayAlpha(alpha: Float) {
            instanceRef?.get()?.let { service ->
                val clamped = alpha.coerceIn(0.0f, 1.0f)
                service.currentAlpha = clamped
                service.floatingView.alpha = clamped
                if (service.useRootWindow) {
                    RootDisplayConnection.get()?.let {
                        runCatching { it.setAlpha(clamped) }
                    }
                }
            }
        }

        @JvmStatic
        fun getDisplayAlpha(): Float {
            return instanceRef?.get()?.currentAlpha ?: 1.0f
        }

        @JvmStatic
        fun reloadMediaList() {
            instanceRef?.get()?.let { service ->
                service.mediaList = OverlayServiceManager.mediaList.value
                if (service.mediaList.isNotEmpty()) {
                    service.currentIndex = 0
                    service.showMedia(0)
                } else {
                    service.floatingView.setBackgroundColor(Color.RED)
                    service.clearMedia()
                    RootDisplayConnection.get()?.let { runCatching { it.clearMedia() } }
                }
            }
        }

        @JvmStatic
        fun setMuted(muted: Boolean) {
            instanceRef?.get()?.let { service ->
                service.isMuted = muted
                service.applyMuteState()
                RootDisplayConnection.get()?.let { runCatching { it.setMuted(muted) } }
                runBlocking {
                    ConfigManager.saveData(service.applicationContext, "overlay_video_muted", muted)
                }
            }
        }

        @JvmStatic
        fun isMuted(): Boolean {
            return instanceRef?.get()?.isMuted ?: false
        }

        /**
         * 显示窗口当前是否由 root 进程托管。
         * ControlOverlayService 据此决定自己是否创建本地控制窗口：
         * root 模式下控制窗口同样由 root 托管（绝对无痕），本地不挂任何窗口。
         */
        @JvmStatic
        fun isUsingRootWindow(): Boolean {
            return instanceRef?.get()?.useRootWindow ?: false
        }

    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var params: WindowManager.LayoutParams

    // root 托管模式：显示窗口由 root 进程（TRUSTED_OVERLAY）托管，
    // 本地 floatingView 仅构造不 addView，作为回退与几何真相源
    private var useRootWindow = false
    private var localWindowAttached = false

    private val rootConnectionListener = RootDisplayConnection.Listener { active ->
        // Shizuku 在主线程派发（MAIN_HANDLER）
        onRootConnectionChanged(active)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // root 端手势"切换媒体"/窗口挂载失败的反向回调（binder 线程进入 → 转主线程处理）
    private val rootCallback = object : IRootDisplayCallback.Stub() {
        override fun onSwitchMedia(delta: Int) {
            mainHandler.post { switchMedia(delta) }
        }

        override fun onWindowFailed(reason: String?) {
            // root 端 addView 失败（WMS 拒绝等）：reason 仅留本地 logcat 定位，
            // 作废后端并广播断连——onRootConnectionChanged(false) 回落本地窗口
            android.util.Log.w("DisplayOverlay", "root window failed: $reason")
            mainHandler.post { RootDisplayConnection.reportBackendFailed() }
        }
    }

    private var currentIndex = 0
    private var mediaList: List<Uri> = emptyList()
    private var contentContainer: FrameLayout? = null
    private var currentIsVideo = false

    // 图片相关
    private var imageView: ImageView? = null

    // 视频相关
    private var surfaceView: SurfaceView? = null
    private var mediaPlayer: MediaPlayer? = null

    // 当前显示的媒体 View
    private var mediaView: View? = null

    // 图片专用：缩放和平移状态
    private var currentScale = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var baseScale = 1.0f
    private var mediaWidth = 0
    private var mediaHeight = 0

    //透明度
    private var currentAlpha = 1.0f

    private var isMuted = false

    private var cornerHandleView: CornerHandleView? = null

    override fun onCreate() {
        super.onCreate()
        OverlayServiceManager.setDisplayRunning(true)
        instanceRef = WeakReference(this)
        val id = runBlocking {
            ConfigManager.getDataOnce(
                applicationContext,
                "overlay_service_display_channel_id",
                1001
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                id,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(id, createNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val initWidth = (screenWidth * 0.6).toInt().coerceIn(300, 1000)
        val initHeight = (screenHeight * 0.4).toInt().coerceIn(200, 800)

        // 本地视图始终构造（回退即用、几何真相源）；root 托管模式下不 addView
        floatingView = FrameLayout(this).apply {
            setBackgroundColor(Color.RED)
            cornerHandleView = CornerHandleView(this@DisplayOverlayService).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            addView(
                cornerHandleView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        params = WindowManager.LayoutParams(
            initWidth, initHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            -3
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        useRootWindow = tryEnableRootDisplay()
        if (useRootWindow) {
            // binder 经主线程回调稍后到达，在回调中 attach 并显示首个媒体
            RootDisplayConnection.get()?.let { root ->
                runCatching { root.attach(params.x, params.y, params.width, params.height) }
            }
        } else {
            addLocalWindow()
        }

        val savedAlpha = runBlocking {
            ConfigManager.getDataOnce(applicationContext, "overlay_display_alpha", 1.0f)
        }
        currentAlpha = savedAlpha
        floatingView.alpha = savedAlpha

        val savedMuted = runBlocking {
            ConfigManager.getDataOnce(applicationContext, "overlay_video_muted", false)
        }
        isMuted = savedMuted

        if (localWindowAttached) {
            floatingView.post {
                floatingView.enableScreenshotExclusion()
            }
        }

        mediaList = OverlayServiceManager.mediaList.value
        if (mediaList.isNotEmpty()) {
            currentIndex = 0
            // root 模式：等 connected 回调（其中会显示首个媒体）；
            // binder 已就绪（重绑场景）则直接显示
            if (!useRootWindow || RootDisplayConnection.isActive) {
                showMedia(0)
            }
        } else {
            floatingView.setBackgroundColor(Color.RED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        OverlayServiceManager.setDisplayRunning(false)
        instanceRef?.clear()
        instanceRef = null
        if (useRootWindow) {
            RootDisplayConnection.removeListener(rootConnectionListener)
            RootDisplayConnection.get()?.let { root ->
                runCatching {
                    // 控制窗口可能仍由 ControlOverlayService 生命周期管理，
                    // 显示服务销毁时一并撤下（unbind 的 destroy 也会兜底清理）
                    root.detachControl()
                    root.detach()
                }
            }
            RootDisplayConnection.unbind()
        }
        releasePlayer()
        clearMedia()
        if (localWindowAttached) {
            runCatching { windowManager.removeView(floatingView) }
            localWindowAttached = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- root 托管模式 ----------

    /**
     * 自动启用 root 托管窗口（绝对隐藏，无额外开关）：
     * RootDisplayConnection 双后端自动选择——Shizuku 以 root（uid=0）
     * 运行时优先用之；否则只要有 root 管理器对应用的 su 授权，就直接
     * 拉起 root 宿主进程（不要求 Shizuku/Sui 存在）。窗口归属 "android"
     * 包 + TRUSTED_OVERLAY 生效，穿透触摸不携带 FLAG_WINDOW_IS_OBSCURED；
     * 无 root 时才走本地窗口方案（隐身伪装开关）。
     * su 后端为异步建立（含授权弹窗等待）：失败经 Listener 通知，
     * onRootConnectionChanged(false) 同样回落本地，悬浮窗不中断。
     */
    private fun tryEnableRootDisplay(): Boolean {
        RootDisplayConnection.addListener(rootConnectionListener)
        if (!RootDisplayConnection.bind(this)) {
            RootDisplayConnection.removeListener(rootConnectionListener)
            return false
        }
        return true
    }

    private fun onRootConnectionChanged(active: Boolean) {
        if (useRootWindow && active) {
            // 连接到达（Shizuku binder 或 su socket）：挂窗口并显示首个媒体
            val root = RootDisplayConnection.get() ?: return
            runCatching {
                // 先注册回调再挂窗口：attach 失败时 onWindowFailed 才不至于
                // 因回调未就绪被丢弃（两条 oneway 事务按序到达，顺序有保证）
                root.registerCallback(rootCallback)
                root.attach(params.x, params.y, params.width, params.height)
                root.setAlpha(currentAlpha)
                root.setMuted(isMuted)
                // 控制服务已在运行则同时挂 root 控制窗口（ControlOverlayService
                // root 模式下不创建本地窗口，由这里统一补挂）
                if (OverlayServiceManager.isControlRunning.value) {
                    root.attachControl(params.x, params.y, params.width, params.height)
                }
                if (mediaList.isNotEmpty()) {
                    showMedia(currentIndex.coerceIn(0, mediaList.size - 1))
                }
            }
        } else if (useRootWindow && !active) {
            // root 进程死亡：立即回落本地窗口，悬浮窗不中断。
            // ControlOverlayService 的同名监听也会收到断连通知并补挂本地控制窗口
            useRootWindow = false
            RootDisplayConnection.removeListener(rootConnectionListener)
            addLocalWindow()
            floatingView.alpha = currentAlpha
            if (mediaList.isNotEmpty()) {
                showMedia(currentIndex.coerceIn(0, mediaList.size - 1))
            } else {
                floatingView.setBackgroundColor(Color.RED)
            }
        }
    }

    /** 以现有 floatingView/params 补挂本地普通窗口（初始即本地模式，或 root 断连回退）。 */
    private fun addLocalWindow() {
        if (localWindowAttached) return

        // 无特权模式 = 普通悬浮窗：不做任何伪装（root 可用时窗口由 root 进程
        // 托管为 TRUSTED_OVERLAY，回落本地仅是功能兜底，保持普通身份即可）
        windowManager.addView(floatingView, params)
        localWindowAttached = true

        floatingView.post {
            floatingView.enableScreenshotExclusion()
        }
    }

    private fun applyMuteState() {
        mediaPlayer?.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
    }

    private fun createNotification(): Notification {
        val channelId = runBlocking {
            ConfigManager.getDataOnce(
                applicationContext,
                "overlay_service_display_channel_name",
                "Display"
            )
        }
        val channel =
            NotificationChannel(
                channelId,
                Auxiliary.getRandomString((20..30).random()),
                NotificationManager.IMPORTANCE_LOW
            )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showMedia(index: Int) {
        if (index < 0 || index >= mediaList.size) return
        clearMedia()
        val uri = mediaList[index]
        val mimeType = contentResolver.getType(uri)
        val isImage = mimeType?.startsWith("image/") == true
        val isVideo = mimeType?.startsWith("video/") == true
        currentIndex = index
        currentIsVideo = isVideo

        if (useRootWindow) {
            val root = RootDisplayConnection.get()
            if (root == null) {
                // 转发中连接断开：回落本地并重试一次
                useRootWindow = false
                RootDisplayConnection.removeListener(rootConnectionListener)
                addLocalWindow()
                floatingView.alpha = currentAlpha
                showMedia(index)
                return
            }
            val delivered = runCatching {
                if (isImage || isVideo) {
                    // app 进程持 Uri 授权，打开 fd 后跨进程传递（binder 自动 dup）
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        if (isImage) root.showImage(pfd) else root.showVideo(pfd)
                        true
                    } ?: false
                } else {
                    false
                }
            }.getOrDefault(false)
            if (!delivered) {
                if (!RootDisplayConnection.isActive) {
                    // 连接已断：回落本地并重试一次
                    useRootWindow = false
                    RootDisplayConnection.removeListener(rootConnectionListener)
                    addLocalWindow()
                    floatingView.alpha = currentAlpha
                    showMedia(index)
                } else {
                    // 连接仍在但内容不可读（授权丢失等）：root 端清空旧内容显示红底
                    runCatching { root.clearMedia() }
                }
            }
            return
        }

        when {
            isImage -> showImage(uri)
            isVideo -> showVideo(uri)
            else -> {
                floatingView.setBackgroundColor(Color.RED)
                return
            }
        }
        if (mediaView is ImageView) {
            currentScale = 1.0f
            panX = 0f
            panY = 0f
            updateImageMatrix()
        }
        floatingView.bringChildToFront(cornerHandleView)
        floatingView.setBackgroundColor(Color.TRANSPARENT)
    }

    // ---------- 图片 ----------
    private fun showImage(uri: Uri) {
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setImageURI(uri)
            drawable?.let {
                mediaWidth = it.intrinsicWidth
                mediaHeight = it.intrinsicHeight
            }
        }
        mediaView = imageView
        contentContainer = FrameLayout(this).apply {
            addView(
                imageView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        floatingView.addView(
            contentContainer,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        floatingView.bringChildToFront(cornerHandleView)
        updateImageMatrix()
    }

    private fun updateImageMatrix() {
        val view = mediaView
        if (view !is ImageView) return
        if (mediaWidth <= 0 || mediaHeight <= 0) return
        val viewWidth = params.width
        val viewHeight = params.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val scaleX = viewWidth.toFloat() / mediaWidth
        val scaleY = viewHeight.toFloat() / mediaHeight
        baseScale = maxOf(scaleX, scaleY)
        val finalScale = baseScale * currentScale

        clampPan()

        val centerX = (viewWidth - mediaWidth * finalScale) / 2
        val centerY = (viewHeight - mediaHeight * finalScale) / 2

        val matrix = Matrix()
        matrix.setScale(finalScale, finalScale)
        matrix.postTranslate(centerX + panX, centerY + panY)
        view.imageMatrix = matrix
    }

    private fun clampPan() {
        if (mediaWidth <= 0 || mediaHeight <= 0) return
        val viewWidth = params.width
        val viewHeight = params.height
        val finalScale = baseScale * currentScale
        val scaledW = mediaWidth * finalScale
        val scaledH = mediaHeight * finalScale
        if (scaledW > viewWidth) {
            val maxPanX = (scaledW - viewWidth) / 2
            panX = panX.coerceIn(-maxPanX, maxPanX)
        } else {
            panX = 0f
        }
        if (scaledH > viewHeight) {
            val maxPanY = (scaledH - viewHeight) / 2
            panY = panY.coerceIn(-maxPanY, maxPanY)
        } else {
            panY = 0f
        }
    }

    private fun applyScale(factor: Float) {
        if (mediaView !is ImageView) return
        var newScale = currentScale * factor
        newScale = maxOf(newScale, 1.0f)
        if (newScale > 5.0f) return
        currentScale = newScale
        clampPan()
        updateImageMatrix()
    }

    // ---------- 视频 ----------
    private fun showVideo(uri: Uri) {
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    initPlayer(uri, holder.surface)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    releasePlayer()
                }
            })
            // 确保 SurfaceView 在悬浮窗中正常显示
            setZOrderMediaOverlay(true)
        }
        mediaView = surfaceView
        contentContainer = FrameLayout(this).apply {
            addView(
                surfaceView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        floatingView.addView(
            contentContainer,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        floatingView.bringChildToFront(cornerHandleView)
        floatingView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun initPlayer(uri: Uri, surface: android.view.Surface) {
        releasePlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@DisplayOverlayService, uri)
                setSurface(surface)
                // 关键：设置系统级裁剪填充
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                isLooping = true
                setOnPreparedListener {
                    applyMuteState()
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    floatingView.setBackgroundColor(Color.RED)
                    false
                }
                prepare()
            }
        } catch (_: Exception) {
            floatingView.setBackgroundColor(Color.RED)
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun refreshContent() {
        if (mediaView is ImageView) {
            updateImageMatrix()
        }
    }

    // ---------- 清理 ----------
    private fun clearMedia() {
        releasePlayer()
        contentContainer?.let { floatingView.removeView(it) }
        contentContainer = null
        imageView = null
        surfaceView = null
        mediaView = null
        mediaWidth = 0
        mediaHeight = 0
        currentScale = 1.0f
        panX = 0f
        panY = 0f
        baseScale = 1.0f
    }
}
