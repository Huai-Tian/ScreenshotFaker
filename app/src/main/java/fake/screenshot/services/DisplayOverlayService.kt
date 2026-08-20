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
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import fake.screenshot.Auxiliary.enableScreenshotExclusion
import fake.screenshot.OverlayServiceManager
import java.lang.ref.WeakReference

class DisplayOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1001

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
                // 重绘角标
                service.cornerHandleView?.invalidate()
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
        fun seekVideo(offset: Int) {
            instanceRef?.get()?.let { service ->
                service.mediaPlayer?.let { mp ->
                    val newPos = mp.currentPosition + offset
                    if (newPos > 0) {
                        mp.seekTo(newPos.coerceAtMost(mp.duration))
                    }
                }
            }
        }

        @JvmStatic
        fun scaleMedia(factor: Float, focusX: Float, focusY: Float) {
            instanceRef?.get()?.applyScale(factor, focusX, focusY)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var params: WindowManager.LayoutParams

    private var currentIndex = 0
    private var mediaList: List<Uri> = emptyList()
    private var contentContainer: FrameLayout? = null
    private var mediaPlayer: MediaPlayer? = null
    private var imageView: ImageView? = null
    private var textureView: TextureView? = null
    private var mediaView: View? = null

    private var currentScale = 1.0f

    private var cornerHandleView: CornerHandleView? = null

    override fun onCreate() {
        super.onCreate()
        OverlayServiceManager.setDisplayRunning(true)
        instanceRef = WeakReference(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
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
            addView(cornerHandleView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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

    private fun createNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮窗运行中")
            .setContentText("媒体展示中")
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
        resetScale()
        // 确保角标在最上层
        floatingView.bringChildToFront(cornerHandleView)
    }

    private fun resetScale() {
        mediaView?.apply {
            scaleX = 1f
            scaleY = 1f
            pivotX = 0f
            pivotY = 0f
        }
        currentScale = 1f
    }

    private fun showImage(uri: Uri) {
        imageView = ImageView(this).apply {
            // 改为 FIT_CENTER，使缩小能看到更多原始内容
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageURI(uri)
        }
        mediaView = imageView
        contentContainer = FrameLayout(this).apply {
            addView(imageView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        floatingView.addView(contentContainer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        floatingView.bringChildToFront(cornerHandleView)
        floatingView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun showVideo(uri: Uri) {
        textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    initVideoPlayer(uri, Surface(surface))
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releasePlayer()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        mediaView = textureView
        contentContainer = FrameLayout(this).apply {
            addView(textureView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        floatingView.addView(contentContainer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        floatingView.bringChildToFront(cornerHandleView)
        floatingView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun initVideoPlayer(uri: Uri, surface: Surface) {
        releasePlayer()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@DisplayOverlayService, uri)
            setSurface(surface)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun clearMedia() {
        releasePlayer()
        contentContainer?.let { floatingView.removeView(it) }
        contentContainer = null
        imageView = null
        textureView = null
        mediaView = null
        resetScale()
    }

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        val view = mediaView ?: return
        val newScale = currentScale * factor
        val minScale = 0.2f
        val maxScale = 5.0f
        if (newScale !in minScale..maxScale) return

        // 焦点相对于 View 左上角的坐标
        val viewX = focusX - params.x
        val viewY = focusY - params.y
        view.pivotX = viewX
        view.pivotY = viewY
        view.scaleX = newScale
        view.scaleY = newScale
        currentScale = newScale
    }

    // ---------- 自定义角标 View ----------
    private class CornerHandleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f * resources.displayMetrics.density  // 加粗
        }
        private val cornerSize = 30f * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val size = cornerSize

            // 左上角
            canvas.drawLine(0f, 0f, size, 0f, paint)
            canvas.drawLine(0f, 0f, 0f, size, paint)
            // 右上角
            canvas.drawLine(w - size, 0f, w, 0f, paint)
            canvas.drawLine(w, 0f, w, size, paint)
            // 左下角
            canvas.drawLine(0f, h - size, 0f, h, paint)
            canvas.drawLine(0f, h, size, h, paint)
            // 右下角
            canvas.drawLine(w - size, h, w, h, paint)
            canvas.drawLine(w, h - size, w, h, paint)
        }
    }
}