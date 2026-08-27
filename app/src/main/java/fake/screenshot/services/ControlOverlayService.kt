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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import fake.screenshot.Auxiliary
import fake.screenshot.services.privileged.RootDisplayConnection
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.OverlayServiceManager
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
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

    // 视频状态每手势只查询一次（ACTION_DOWN 时刷新），
    // 之后整条手势流复用，避免逐事件触发跨进程逻辑
    private var isVideoGesture = false

    // 视频长按快进/快退：左半区快退（-1），右半区快进（+1）
    // 步长 5s 起步逐次翻倍、封顶 30s，每 500ms 一步——按住越久跳得越快
    private var seekDirection = 0
    private var seekStepMs = 0
    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekRunnable = object : Runnable {
        override fun run() {
            if (!isLongPress || seekDirection == 0) return
            DisplayOverlayService.seekMedia(seekDirection * seekStepMs)
            seekStepMs = (seekStepMs * 2).coerceAtMost(30_000)
            seekHandler.postDelayed(this, 500)
        }
    }

    private fun startSeekLoop() {
        seekStepMs = 5_000
        seekRunnable.run()
    }

    private fun stopSeekLoop() {
        seekHandler.removeCallbacks(seekRunnable)
        seekDirection = 0
        seekStepMs = 0
    }

    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val minSize = 80
    private val touchSlop = 60

    override fun onCreate() {
        super.onCreate()

        // 提前初始化：onDestroy / root 断连回退路径均可能访问
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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

        // root 托管模式：控制窗口同样由 root 进程创建（TRUSTED_OVERLAY，
        // 可触摸窗口遮挡下层触摸时不产生 FLAG_WINDOW_IS_OBSCURED——
        // 应用进程的可触摸窗口做不到这一点），本服务不创建任何本地窗口。
        // 手势由 RootDisplayService 处理；root 断连时经 rootFallbackListener
        // 补挂本地窗口回落。
        if (DisplayOverlayService.isUsingRootWindow()) {
            RootDisplayConnection.addListener(rootFallbackListener)
            // binder 已就绪（控制服务单独启动的场景）则直接挂；
            // 否则等 DisplayOverlayService 的 connected 回调统一补挂
            RootDisplayConnection.get()?.let {
                runCatching {
                    it.attachControl(pos.first, pos.second, size.first, size.second)
                }
            }
            return
        }

        attachLocalControl(pos, size)
    }

    /** 创建并挂载本地控制窗口（无 root 的原方案，或 root 断连后的回退）。 */
    private fun attachLocalControl(pos: Pair<Int, Int>, size: Pair<Int, Int>) {
        if (controlView != null) return

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

        // 无特权模式 = 普通悬浮窗：不做任何伪装（root 可用时控制窗口由
        // root 进程托管为 TRUSTED_OVERLAY，本地控制窗口仅是功能兜底）
        val view = controlView ?: return
        windowManager.addView(view, params)

        view.setOnTouchListener { v, event -> onLocalControlTouch(v, event) }
    }

    /**
     * root 断连回退：DisplayOverlayService 已回落本地显示窗口（其监听先于本监听
     * 回调，useRootWindow 已置 false），这里补挂本地控制窗口保证悬浮窗可继续操作。
     */
    private val rootFallbackListener = RootDisplayConnection.Listener { active ->
        if (!active && controlView == null && !DisplayOverlayService.isUsingRootWindow()) {
            val pos = DisplayOverlayService.getPosition() ?: return@Listener
            val size = DisplayOverlayService.getSize() ?: return@Listener
            runCatching { attachLocalControl(pos, size) }
        }
    }

    private fun onLocalControlTouch(view: View, event: MotionEvent): Boolean {
        // GestureDetector 优先
        if (gestureDetector.onTouchEvent(event)) {
            return true
        }

        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            return true
        }

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                isLongPress = false

                // 每手势一次：长按/双击/模式判定整条事件流复用该值
                isVideoGesture = DisplayOverlayService.isCurrentVideo()

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
                if (isVideoGesture && lockedMode == Mode.NONE) {
                    false
                } else {
                    true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isLongPress) {
                    return true
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
                    stopSeekLoop()
                    lockedMode = Mode.NONE
                    isScaling = false
                    return true
                }

                // 视频模式下且非边缘区域：透传单击给播放器
                if (isVideoGesture && lockedMode == Mode.NONE) {
                    lockedMode = Mode.NONE
                    isScaling = false
                    return false
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

        return when {
            isLeft && isTop -> Mode.SCALE_LEFT_TOP
            isRight && isTop -> Mode.SCALE_RIGHT_TOP
            isLeft && isBottom -> Mode.SCALE_LEFT_BOTTOM
            isRight && isBottom -> Mode.SCALE_RIGHT_BOTTOM
            isTop -> Mode.MOVE_WINDOW
            isVideoGesture -> Mode.NONE
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
        override fun onLongPress(e: MotionEvent) {
            // 仅视频启用长按 seek；图片保持原有行为
            // 缩放/窗口移动/边缘调整模式下不触发（此时手势已有明确含义）
            if (isScaling || lockedMode != Mode.NONE) return
            if (!isVideoGesture) return
            val halfWidth = (controlView?.width ?: return) / 2f
            seekDirection = if (e.x < halfWidth) -1 else 1
            isLongPress = true
            startSeekLoop()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isScaling) {
                return false
            }
            val view = controlView ?: return false

            if (isVideoGesture) {
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
        stopSeekLoop()
        OverlayServiceManager.setControlRunning(false)
        if (controlView != null) {
            runCatching { windowManager.removeView(controlView) }
        } else {
            // root 托管模式（本地窗口从未创建）：撤下 root 端控制窗口并移除回退监听
            RootDisplayConnection.removeListener(rootFallbackListener)
            RootDisplayConnection.get()?.let {
                runCatching { it.detachControl() }
            }
        }
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