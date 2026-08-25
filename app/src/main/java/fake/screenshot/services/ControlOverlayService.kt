package fake.screenshot.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import fake.screenshot.Auxiliary
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.OverlayServiceManager
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

class ControlOverlayService : Service() {

    companion object {
        @JvmStatic
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ControlOverlayService::class.java))
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, ControlOverlayService::class.java))
        }
    }

    private enum class Mode {
        NONE,
        MOVE_WINDOW,
        MOVE_MEDIA,
        SCALE_LEFT_TOP, SCALE_RIGHT_TOP, SCALE_LEFT_BOTTOM, SCALE_RIGHT_BOTTOM
    }

    private lateinit var windowManager: WindowManager
    private var controlView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var lockedMode: Mode = Mode.NONE
    private var isScaling = false

    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isLongPress = false

    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val minSize = 80
    private val touchSlop = 60

    override fun onCreate() {
        super.onCreate()

        val pos = DisplayOverlayService.getPosition()
        val size = DisplayOverlayService.getSize()
        if (pos == null || size == null) {
            stopSelf()
            return
        }

        OverlayServiceManager.setControlRunning(true)
        val id = runBlocking {
            ConfigManager.getDataOnce(
                applicationContext,
                "overlay_service_control_channel_id",
                1002
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
        screenWidth = bounds.width()
        screenHeight = bounds.height()

        scaleDetector = ScaleGestureDetector(this, ScaleListener())
        gestureDetector = GestureDetector(this, GestureListener())

        controlView = View(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = false
            isFocusable = false
        }

        params = WindowManager.LayoutParams(
            size.first, size.second,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pos.first
            y = pos.second
        }

        windowManager.addView(controlView, params)

        controlView?.setOnTouchListener { view, event ->
            // GestureDetector 优先
            if (gestureDetector.onTouchEvent(event)) {
                return@setOnTouchListener true
            }

            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                return@setOnTouchListener true
            }

            val isVideo = DisplayOverlayService.isCurrentVideo()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    isLongPress = false

                    lockedMode = detectMode(event)
                    if (lockedMode.name.startsWith("SCALE_")) {
                        isScaling = true
                    }
                    initialX = params.x
                    initialY = params.y
                    initialWidth = params.width
                    initialHeight = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    // 只有在非边缘区域（Mode.NONE）且是视频模式时才透传
                    if (isVideo && lockedMode == Mode.NONE) {
                        return@setOnTouchListener false
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isLongPress) {
                        return@setOnTouchListener true
                    }

                    when (lockedMode) {
                        Mode.MOVE_WINDOW -> handleMoveWindow(event)
                        Mode.MOVE_MEDIA -> handleMoveMedia(event)
                        Mode.SCALE_LEFT_TOP, Mode.SCALE_RIGHT_TOP,
                        Mode.SCALE_LEFT_BOTTOM, Mode.SCALE_RIGHT_BOTTOM -> {
                            handleScale(event)
                        }

                        else -> {}
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPress) {
                        isLongPress = false
                        lockedMode = Mode.NONE
                        isScaling = false
                        return@setOnTouchListener true
                    }

                    // 视频模式下且非边缘区域：透传单击给播放器
                    if (isVideo && lockedMode == Mode.NONE) {
                        lockedMode = Mode.NONE
                        isScaling = false
                        return@setOnTouchListener false
                    }

                    // 点击检测（图片模式）
                    if (abs(event.rawX - initialTouchX) < 5 && abs(event.rawY - initialTouchY) < 5) {
                        view.performClick()
                    }

                    lockedMode = Mode.NONE
                    isScaling = false
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    true
                }

                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
    }

    private fun detectMode(event: MotionEvent): Mode {
        val x = event.x
        val y = event.y
        val w = params.width
        val h = params.height

        val isLeft = x <= touchSlop
        val isRight = x >= w - touchSlop
        val isTop = y <= touchSlop
        val isBottom = y >= h - touchSlop

        val isVideo = DisplayOverlayService.isCurrentVideo()

        return when {
            isLeft && isTop -> Mode.SCALE_LEFT_TOP
            isRight && isTop -> Mode.SCALE_RIGHT_TOP
            isLeft && isBottom -> Mode.SCALE_LEFT_BOTTOM
            isRight && isBottom -> Mode.SCALE_RIGHT_BOTTOM
            isTop -> Mode.MOVE_WINDOW
            isVideo -> Mode.NONE
            else -> Mode.MOVE_MEDIA
        }
    }

    private fun handleMoveWindow(event: MotionEvent): Boolean {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
        val newX = initialX + dx
        val newY = initialY + dy
        updateOverlay(newX, newY, params.width, params.height)
        return true
    }

    private fun handleMoveMedia(event: MotionEvent): Boolean {
        val dx = (event.rawX - initialTouchX) * 2f
        val dy = (event.rawY - initialTouchY) * 2f
        DisplayOverlayService.panMedia(dx, dy)
        initialTouchX = event.rawX
        initialTouchY = event.rawY
        return true
    }

    private fun handleScale(event: MotionEvent) {
        when (lockedMode) {
            Mode.SCALE_LEFT_TOP -> handleScaleLeftTop(event)
            Mode.SCALE_RIGHT_TOP -> handleScaleRightTop(event)
            Mode.SCALE_LEFT_BOTTOM -> handleScaleLeftBottom(event)
            Mode.SCALE_RIGHT_BOTTOM -> handleScaleRightBottom(event)
            else -> {}
        }
    }

    private fun handleScaleLeftTop(event: MotionEvent): Boolean {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
        var newW = initialWidth - dx
        var newH = initialHeight - dy
        newW = newW.coerceIn(minSize, screenWidth)
        newH = newH.coerceIn(minSize, screenHeight)
        val newX = initialX + (initialWidth - newW)
        val newY = initialY + (initialHeight - newH)
        updateOverlay(newX, newY, newW, newH)
        return true
    }

    private fun handleScaleRightTop(event: MotionEvent): Boolean {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
        var newW = initialWidth + dx
        var newH = initialHeight - dy
        newW = newW.coerceIn(minSize, screenWidth)
        newH = newH.coerceIn(minSize, screenHeight)
        val newX = initialX
        val newY = initialY + (initialHeight - newH)
        updateOverlay(newX, newY, newW, newH)
        return true
    }

    private fun handleScaleLeftBottom(event: MotionEvent): Boolean {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
        var newW = initialWidth - dx
        var newH = initialHeight + dy
        newW = newW.coerceIn(minSize, screenWidth)
        newH = newH.coerceIn(minSize, screenHeight)
        val newX = initialX + (initialWidth - newW)
        val newY = initialY
        updateOverlay(newX, newY, newW, newH)
        return true
    }

    private fun handleScaleRightBottom(event: MotionEvent): Boolean {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
        var newW = initialWidth + dx
        var newH = initialHeight + dy
        newW = newW.coerceIn(minSize, screenWidth)
        newH = newH.coerceIn(minSize, screenHeight)
        val newX = initialX
        val newY = initialY
        updateOverlay(newX, newY, newW, newH)
        return true
    }

    private class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            DisplayOverlayService.scaleMedia(scaleFactor)
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isScaling) {
                return false
            }
            val view = controlView ?: return false
            val isVideo = DisplayOverlayService.isCurrentVideo()

            if (isVideo) {
                // 视频：左25%上一张，中间50%播放/暂停，右25%下一张
                val width = view.width.toFloat()
                val x = e.x
                val delta = when {
                    x < width * 0.25f -> -1
                    x > width * 0.75f -> 1
                    else -> 0
                }
                if (delta != 0) {
                    DisplayOverlayService.switchMedia(delta)
                } else {
                    DisplayOverlayService.togglePlayPause()
                }
            } else {
                // 图片：左半区上一张，右半区下一张
                val halfWidth = view.width / 2f
                val delta = if (e.x < halfWidth) -1 else 1
                DisplayOverlayService.switchMedia(delta)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return true
        }
    }

    private fun updateOverlay(x: Int, y: Int, width: Int, height: Int) {
        val clampedWidth = width.coerceAtMost(screenWidth)
        val clampedHeight = height.coerceAtMost(screenHeight)

        val maxX = screenWidth - clampedWidth
        val maxY = screenHeight - clampedHeight
        val clampedX = x.coerceIn(0, maxX)
        val clampedY = y.coerceIn(0, maxY)

        params.x = clampedX
        params.y = clampedY
        params.width = clampedWidth
        params.height = clampedHeight
        windowManager.updateViewLayout(controlView, params)
        DisplayOverlayService.setSizePosition(clampedX, clampedY, clampedWidth, clampedHeight)
    }

    override fun onDestroy() {
        super.onDestroy()
        OverlayServiceManager.setControlRunning(false)
        controlView?.let { windowManager.removeView(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = runBlocking {
            ConfigManager.getDataOnce(
                applicationContext,
                "overlay_service_control_channel_name",
                "Control"
            )
        }
        val channel = NotificationChannel(
            channelId,
            Auxiliary.getRandomString((20..30).random()),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}