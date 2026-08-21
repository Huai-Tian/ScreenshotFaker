package fake.screenshot.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
import fake.screenshot.ConfigManager
import fake.screenshot.OverlayServiceManager
import kotlinx.coroutines.runBlocking
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
                service.windowManager.updateViewLayout(service.floatingView, service.params)
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
            instanceRef?.get()?.let { service ->
                service.mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.pause()
                    } else {
                        mp.start()
                    }
                }
            }
        }

        @JvmStatic
        fun scaleMedia(factor: Float) {
            instanceRef?.get()?.applyScale(factor)
        }

        @JvmStatic
        fun panMedia(dx: Float, dy: Float) {
            // 仅图片支持平移
            instanceRef?.get()?.let { service ->
                if (service.mediaView is ImageView) {
                    service.panX += dx
                    service.panY += dy
                    service.clampPan()
                    service.updateImageMatrix()
                }
            }
        }

        @JvmStatic
        fun isCurrentVideo(): Boolean {
            return instanceRef?.get()?.mediaView is SurfaceView
        }

        @JvmStatic
        fun setDisplayAlpha(alpha: Float) {
            instanceRef?.get()?.let { service ->
                val clamped = alpha.coerceIn(0.0f, 1.0f)
                service.currentAlpha = clamped
                service.floatingView.alpha = clamped
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
                }
            }
        }

        @JvmStatic
        fun setMuted(muted: Boolean) {
            instanceRef?.get()?.let { service ->
                service.isMuted = muted
                service.applyMuteState()
                runBlocking {
                    ConfigManager.saveData(service.applicationContext, "overlay_video_muted", muted)
                }
            }
        }

        @JvmStatic
        fun isMuted(): Boolean {
            return instanceRef?.get()?.isMuted ?: false
        }

    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var params: WindowManager.LayoutParams

    private var currentIndex = 0
    private var mediaList: List<Uri> = emptyList()
    private var contentContainer: FrameLayout? = null

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
            200, 200,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            -3
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        windowManager.addView(floatingView, params)

        val savedAlpha = runBlocking {
            ConfigManager.getDataOnce(applicationContext, "overlay_display_alpha", 1.0f)
        }
        currentAlpha = savedAlpha
        floatingView.alpha = savedAlpha

        val savedMuted = runBlocking {
            ConfigManager.getDataOnce(applicationContext, "overlay_video_muted", false)
        }
        isMuted = savedMuted


        floatingView.post {
            floatingView.enableScreenshotExclusion()
        }

        mediaList = OverlayServiceManager.mediaList.value
        if (mediaList.isNotEmpty()) {
            currentIndex = 0
            showMedia(0)
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
        releasePlayer()
        clearMedia()
        windowManager.removeView(floatingView)
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        when {
            mimeType?.startsWith("image/") == true -> showImage(uri)
            mimeType?.startsWith("video/") == true -> showVideo(uri)
            else -> {
                floatingView.setBackgroundColor(Color.RED)
                currentIndex = index
                return
            }
        }
        currentIndex = index
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

    // ---------- 角标 ----------
    private class CornerHandleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f * resources.displayMetrics.density
        }
        private val cornerSize = 30f * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val size = cornerSize

            canvas.drawLine(0f, 0f, size, 0f, paint)
            canvas.drawLine(0f, 0f, 0f, size, paint)
            canvas.drawLine(w - size, 0f, w, 0f, paint)
            canvas.drawLine(w, 0f, w, size, paint)
            canvas.drawLine(0f, h - size, 0f, h, paint)
            canvas.drawLine(0f, h, size, h, paint)
            canvas.drawLine(w - size, h, w, h, paint)
            canvas.drawLine(w, h - size, w, h, paint)

            val centerX = w / 2
            canvas.drawLine(centerX - size, 0f, centerX + size, 0f, paint)
        }
    }
}