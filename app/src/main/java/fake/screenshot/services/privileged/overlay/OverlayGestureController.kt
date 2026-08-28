package fake.screenshot.services.privileged.overlay

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * 纯 Surface 方案的手势状态机：逻辑与旧 RootOverlayService /
 * 本地 ControlOverlayService 完全一致，仅输入源由 View 触摸事件
 * 换为 [GestureInputMonitor] 解析出的 MotionEvent（屏幕绝对坐标）。
 *
 * - DOWN 命中悬浮窗矩形 → 立即 pilferPointers 抢占指针流（下层应用
 *   收不到该手势后续事件，也从未收到任何遮挡标记）；
 * - 模式：移动窗口 / 移动媒体（图片平移）/ 四角缩放 / 长按 seek /
 *   双击分区（切换媒体、播放暂停）/ 视频非边缘单击透传（注入）；
 * - 透传注入经 InputManager.injectInputEvent（uid=0 直过 INJECT_EVENTS，
 *   事件正常派发至下层——spy monitor 不拦截派发，无需旧方案的
 *   "瞬时 NOT_TOUCHABLE" 补丁）；注入事件会被 monitor 副本收到，
 *   以时间窗过滤防自激励循环。
 */
internal class OverlayGestureController(
    context: Context,
    private val handler: Handler,
    private val backend: OverlaySurfaceBackend,
    private val input: GestureInputMonitor,
    private val onSwitchMedia: (Int) -> Unit
) {

    // ==================== 几何状态（controller 为唯一事实源） ====================

    var windowX = 0
        private set
    var windowY = 0
        private set
    var windowWidth = 0
        private set
    var windowHeight = 0
        private set

    var screenWidth = 0
    var screenHeight = 0

    fun syncGeometry(x: Int, y: Int, w: Int, h: Int) {
        windowX = x
        windowY = y
        windowWidth = w
        windowHeight = h
    }

    private val minSize = 80
    private val touchSlop = 60

    // ==================== 手势模式 ====================

    private enum class Mode {
        NONE,
        MOVE_WINDOW,
        MOVE_MEDIA,
        SCALE_LEFT_TOP, SCALE_RIGHT_TOP, SCALE_LEFT_BOTTOM, SCALE_RIGHT_BOTTOM
    }

    private var lockedMode = Mode.NONE
    private var isScaling = false

    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var isLongPress = false

    // 视频长按快进/快退：5s 起步逐次翻倍、封顶 30s，每 500ms 一步
    private var seekDirection = 0
    private var seekStepMs = 0

    // 注入防自激励：注入的 tap 会回流到 monitor（spy 收到事件副本）
    private var lastInjectAt = 0L

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    init {
        gestureDetector = GestureDetector(context, GestureListener())
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
    }

    // ==================== 事件入口（handler 线程调用） ====================

    fun onTouch(event: MotionEvent) {
        if (!backend.isAttached) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 注入时间窗内的事件是自身注入的副本：整串忽略
                if (SystemClock.elapsedRealtime() - lastInjectAt < 800) {
                    suppressCurrentGesture()
                    return
                }
                // 命中悬浮窗 → 抢占指针流，此后下层应用收不到本手势
                if (event.x >= windowX && event.x <= windowX + windowWidth &&
                    event.y >= windowY && event.y <= windowY + windowHeight
                ) {
                    input.pilferPointers()
                    handleDown(event)
                    android.util.Log.i(
                        "RootOverlay",
                        "down HIT (${"%.0f".format(event.x)},${"%.0f".format(event.y)}) " +
                                "window=($windowX,$windowY ${windowWidth}x${windowHeight}) " +
                                "screen=(${screenWidth}x${screenHeight}) mode=$lockedMode"
                    )
                }
                // 未命中：不 pilfer，事件自然穿透给下层应用
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (lockedMode == Mode.NONE && !isLongPress) return
                if (gestureDetector.onTouchEvent(event)) return
                scaleDetector.onTouchEvent(event)
                if (scaleDetector.isInProgress) return
                dispatchAction(event)
            }
            else -> {
                gestureDetector.onTouchEvent(event)
                scaleDetector.onTouchEvent(event)
            }
        }
    }

    private fun suppressCurrentGesture() {
        lockedMode = Mode.NONE
        isScaling = false
        isLongPress = false
        stopSeekLoop()
    }

    private fun handleDown(event: MotionEvent) {
        isLongPress = false
        lockedMode = detectMode(event.x - windowX, event.y - windowY)
        if (lockedMode.name.startsWith("SCALE_")) isScaling = true
        initialX = windowX
        initialY = windowY
        initialWidth = windowWidth
        initialHeight = windowHeight
        initialTouchX = event.x
        initialTouchY = event.y
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
    }

    private fun dispatchAction(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isLongPress) return
                when (lockedMode) {
                    Mode.MOVE_WINDOW -> handleMoveWindow(event)
                    Mode.MOVE_MEDIA -> handleMoveMedia(event)
                    Mode.SCALE_LEFT_TOP, Mode.SCALE_RIGHT_TOP,
                    Mode.SCALE_LEFT_BOTTOM, Mode.SCALE_RIGHT_BOTTOM -> handleScale(event)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPress) {
                    isLongPress = false
                    stopSeekLoop()
                }
                val wasGesture = lockedMode != Mode.NONE
                lockedMode = Mode.NONE
                isScaling = false
                // 手势结束：live 合成器变换 → 精确几何 + 最终帧全量重绘
                if (wasGesture) {
                    backend.settleGeometry(windowX, windowY, windowWidth, windowHeight)
                }
            }
        }
    }

    private fun detectMode(localX: Float, localY: Float): Mode {
        val isLeft = localX <= touchSlop
        val isRight = localX >= windowWidth - touchSlop
        val isTop = localY <= touchSlop
        val isBottom = localY >= windowHeight - touchSlop
        return when {
            isLeft && isTop -> Mode.SCALE_LEFT_TOP
            isRight && isTop -> Mode.SCALE_RIGHT_TOP
            isLeft && isBottom -> Mode.SCALE_LEFT_BOTTOM
            isRight && isBottom -> Mode.SCALE_RIGHT_BOTTOM
            isTop -> Mode.MOVE_WINDOW
            backend.isVideo -> Mode.NONE
            else -> Mode.MOVE_MEDIA
        }
    }

    private fun handleMoveWindow(event: MotionEvent) {
        val dx = (event.x - initialTouchX).toInt()
        val dy = (event.y - initialTouchY).toInt()
        updateOverlay(initialX + dx, initialY + dy, initialWidth, initialHeight)
    }

    private fun handleMoveMedia(event: MotionEvent) {
        val dx = (event.x - initialTouchX) * 2f
        val dy = (event.y - initialTouchY) * 2f
        backend.panImage(dx, dy)
        initialTouchX = event.x
        initialTouchY = event.y
    }

    private fun handleScale(event: MotionEvent) {
        val dx = (event.x - initialTouchX).toInt()
        val dy = (event.y - initialTouchY).toInt()
        var newW = initialWidth
        var newH = initialHeight
        var newX = initialX
        var newY = initialY
        when (lockedMode) {
            Mode.SCALE_LEFT_TOP -> {
                newW = initialWidth - dx
                newH = initialHeight - dy
                newX = initialX + (initialWidth - newW)
                newY = initialY + (initialHeight - newH)
            }
            Mode.SCALE_RIGHT_TOP -> {
                newW = initialWidth + dx
                newH = initialHeight - dy
                newY = initialY + (initialHeight - newH)
            }
            Mode.SCALE_LEFT_BOTTOM -> {
                newW = initialWidth - dx
                newH = initialHeight + dy
                newX = initialX + (initialWidth - newW)
            }
            Mode.SCALE_RIGHT_BOTTOM -> {
                newW = initialWidth + dx
                newH = initialHeight + dy
            }
            else -> return
        }
        updateOverlay(newX, newY, newW, newH)
    }

    /** 与本地模式相同的 clamp 规则。 */
    private fun updateOverlay(x: Int, y: Int, w: Int, h: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            // 屏幕尺寸未知：clamp 边界缺失，静默丢弃正是"边框无法操作"的
            // 隐蔽根因。一次性留 ERROR，之后避免刷屏。
            if (!loggedScreenSizeFailure) {
                loggedScreenSizeFailure = true
                android.util.Log.e(
                    "RootOverlay",
                    "updateOverlay skipped: screen size unknown (${screenWidth}x${screenHeight})"
                )
            }
            return
        }
        val clampedW = w.coerceAtLeast(minSize).coerceAtMost(screenWidth)
        val clampedH = h.coerceAtLeast(minSize).coerceAtMost(screenHeight)
        // coerceAtLeast(0)：窗口大于屏幕（横竖屏切换等）时避免空区间异常
        val maxX = (screenWidth - clampedW).coerceAtLeast(0)
        val maxY = (screenHeight - clampedH).coerceAtLeast(0)
        val clampedX = x.coerceIn(0, maxX)
        val clampedY = y.coerceIn(0, maxY)
        syncGeometry(clampedX, clampedY, clampedW, clampedH)
        // 手势进行中：合成器变换路径（GPU 缩放/移动，零 canvas 成本）
        backend.setGeometry(clampedX, clampedY, clampedW, clampedH, live = true)
    }

    private var loggedScreenSizeFailure = false

    // ==================== 长按 seek 循环 ====================

    private val seekRunnable = object : Runnable {
        override fun run() {
            if (!isLongPress || seekDirection == 0) return
            backend.seekBy(seekDirection * seekStepMs)
            seekStepMs = (seekStepMs * 2).coerceAtMost(30_000)
            scheduleSeek()
        }
    }

    private fun scheduleSeek() {
        handler.postDelayed(seekRunnable, 500)
    }

    private fun startSeekLoop() {
        seekStepMs = 5_000
        scheduleSeek()
    }

    private fun stopSeekLoop() {
        handler.removeCallbacks(seekRunnable)
        seekDirection = 0
        seekStepMs = 0
    }

    // ==================== 手势监听 ====================

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            backend.scaleImage(detector.scaleFactor)
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (isScaling || lockedMode != Mode.NONE) return
            if (!backend.isVideo) return
            val halfWidth = windowWidth / 2f
            seekDirection = if (e.x - windowX < halfWidth) -1 else 1
            isLongPress = true
            startSeekLoop()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isScaling) return false
            val localX = e.x - windowX
            if (backend.isVideo) {
                // 视频：左25%上一张，中间50%播放/暂停，右25%下一张
                val delta = when {
                    localX < windowWidth * 0.25f -> -1
                    localX > windowWidth * 0.75f -> 1
                    else -> 0
                }
                if (delta != 0) onSwitchMedia(delta) else backend.togglePlayPause()
            } else {
                // 图片：左半区上一张，右半区下一张
                onSwitchMedia(if (localX < windowWidth / 2f) -1 else 1)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 视频非边缘区域的单击：注入点击透传给下层应用
            if (backend.isVideo && lockedMode == Mode.NONE && !isLongPress) {
                injectTap(e.x.toInt(), e.y.toInt())
            }
            return true
        }
    }

    // ==================== 单击透传注入 ====================

    /**
     * 直接构造 down+up 注入（uid=0 过 INJECT_EVENTS）。注入事件不经本
     * monitor 拦截（spy 只观察），直接派发到下层窗口。事件源设为
     * touchscreen 且坐标为屏幕绝对坐标——与真实触摸无差异，无注入特征。
     */
    private fun injectTap(x: Int, y: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInjectAt < 800) return
        lastInjectAt = now
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        fun event(action: Int, eventTime: Long) = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x.toFloat()
                    this.y = y.toFloat()
                    pressure = 1f
                    size = 1f
                }
            ),
            0, 0, 1f, 1f, // metaState, buttonState, xPrecision, yPrecision
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0 // deviceId, edgeFlags, source, flags
        )

        val injected = runCatching {
            val im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
            // 全版本（11-16，AOSP 逐版本核对）声明为
            // injectInputEvent(InputEvent, int)——参数类型必须是
            // InputEvent；用 MotionEvent 查找必抛 NoSuchMethodException，
            // 导致永远走 shell 兜底（v20 修复）
            val inject = im.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java, Int::class.javaPrimitiveType
            )
            val down = event(MotionEvent.ACTION_DOWN, downTime)
            val up = event(MotionEvent.ACTION_UP, downTime + 16)
            val okDown = inject.invoke(im, down, 0) as Boolean
            val okUp = inject.invoke(im, up, 0) as Boolean
            down.recycle()
            up.recycle()
            okDown && okUp
        }.getOrDefault(false)

        if (!injected) {
            // 反射不可用（理论不会发生）时兜底命令行注入
            runCatching {
                ProcessBuilder("/system/bin/input", "tap", x.toString(), y.toString())
                    .redirectErrorStream(true).start()
            }
        }
    }
}
