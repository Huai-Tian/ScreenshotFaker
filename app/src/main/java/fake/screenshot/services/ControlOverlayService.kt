package fake.screenshot.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import fake.screenshot.OverlayServiceManager
import kotlin.math.abs

class ControlOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "control_overlay_channel"
        private const val NOTIFICATION_ID = 1002

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
        NONE, MOVE, SCALE_LEFT_TOP, SCALE_RIGHT_TOP, SCALE_LEFT_BOTTOM, SCALE_RIGHT_BOTTOM
    }

    private lateinit var windowManager: WindowManager
    private var controlView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var mode = Mode.NONE
    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // 单指手势相关
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isSwiping = false
    private var isLongPress = false

    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0

    // 缩放检测器
    private lateinit var scaleDetector: ScaleGestureDetector

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

        // 获取屏幕尺寸
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()

        // 初始化缩放检测器
        scaleDetector = ScaleGestureDetector(this, ScaleListener())

        controlView = View(this).apply {
            setBackgroundColor(0x00000000)
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
            // 先让缩放检测器处理
            scaleDetector.onTouchEvent(event)

            // 如果正在缩放，跳过单指逻辑
            if (scaleDetector.isInProgress) {
                return@setOnTouchListener true
            }

            // 如果触摸点数大于1，跳过单指逻辑（双指缩放由 ScaleGestureDetector 处理）
            if (event.pointerCount > 1) {
                return@setOnTouchListener true
            }

            // 单指手势处理
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    isSwiping = false
                    isLongPress = false

                    mode = detectMode(event)
                    initialX = params.x
                    initialY = params.y
                    initialWidth = params.width
                    initialHeight = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isSwiping || isLongPress) {
                        return@setOnTouchListener true
                    }

                    val dx = event.x - downX
                    if (abs(dx) > 100) {
                        val delta = if (dx > 0) 1 else -1
                        DisplayOverlayService.switchMedia(delta)
                        isSwiping = true
                        return@setOnTouchListener true
                    }

                    when (mode) {
                        Mode.MOVE -> handleMove(event)
                        Mode.SCALE_LEFT_TOP -> handleScaleLeftTop(event)
                        Mode.SCALE_RIGHT_TOP -> handleScaleRightTop(event)
                        Mode.SCALE_LEFT_BOTTOM -> handleScaleLeftBottom(event)
                        Mode.SCALE_RIGHT_BOTTOM -> handleScaleRightBottom(event)
                        else -> {}
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isSwiping) {
                        isSwiping = false
                        mode = Mode.NONE
                        return@setOnTouchListener true
                    }

                    if (isLongPress) {
                        isLongPress = false
                        mode = Mode.NONE
                        return@setOnTouchListener true
                    }

                    val duration = System.currentTimeMillis() - downTime
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (duration > 500 && dx < 20 && dy < 20) {
                        val offset = if (downX < view.width / 2) -5000 else 5000
                        DisplayOverlayService.seekVideo(offset)
                        isLongPress = true
                        mode = Mode.NONE
                        return@setOnTouchListener true
                    }

                    if (abs(event.rawX - initialTouchX) < 5 && abs(event.rawY - initialTouchY) < 5) {
                        view.performClick()
                    }
                    mode = Mode.NONE
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

    // 检测触摸点位于哪个角区域或移动区域
    private fun detectMode(event: MotionEvent): Mode {
        val x = event.x
        val y = event.y
        val w = params.width
        val h = params.height

        val isLeft = x <= touchSlop
        val isRight = x >= w - touchSlop
        val isTop = y <= touchSlop
        val isBottom = y >= h - touchSlop

        return when {
            isLeft && isTop -> Mode.SCALE_LEFT_TOP
            isRight && isTop -> Mode.SCALE_RIGHT_TOP
            isLeft && isBottom -> Mode.SCALE_LEFT_BOTTOM
            isRight && isBottom -> Mode.SCALE_RIGHT_BOTTOM
            else -> Mode.MOVE
        }
    }

    // ---------- 单指手势处理方法 ----------
    private fun handleMove(event: MotionEvent): Boolean {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
        val newX = initialX + dx
        val newY = initialY + dy
        updateOverlay(newX, newY, params.width, params.height)
        return true
    }

    // 四角缩放（已限制宽度不超过屏幕宽度，高度不超过屏幕高度）
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

    // 缩放监听器（双指缩放媒体内容，不改变窗口大小）
    private class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = detector.focusX
            val focusY = detector.focusY
            val scaleFactor = detector.scaleFactor
            DisplayOverlayService.scaleMedia(scaleFactor, focusX, focusY)
            return true
        }
    }

    // 更新控制层和显示层，并限制尺寸和边界
    private fun updateOverlay(x: Int, y: Int, width: Int, height: Int) {
        // 限制宽度和高度不超过屏幕尺寸
        val clampedWidth = width.coerceAtMost(screenWidth)
        val clampedHeight = height.coerceAtMost(screenHeight)

        // 计算位置，确保窗口不超出屏幕边界
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Control Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("控制层运行中")
            .setContentText("透明触摸层")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}