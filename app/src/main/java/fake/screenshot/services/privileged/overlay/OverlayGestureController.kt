package fake.screenshot.services.privileged.overlay

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import fake.screenshot.Auxiliary
import android.view.GestureDetector
import android.view.InputDevice
import android.view.InputEvent
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
 *   以注入签名（downTime/deviceId/坐标）精确识别副本并整串忽略，
 *   防自激励循环。
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

    /**
     * 本轮事件流始于悬浮窗上的 DOWN（已 pilfer）。MOVE/UP/CANCEL 的
     * 消费归属以此判定：视频非边缘触摸的 lockedMode 为 NONE（无锁定
     * 模式），此前以 lockedMode/isLongPress 早退会把整串 UP/MOVE 挡在
     * 门外，GestureDetector 收不到完整事件流 → 视频 单击/双击/长按
     * 全部失效（既有 bug，本次修复）。
     */
    private var gestureActive = false

    /** 本轮手势 DOWN 时判定的模式（供 UP 之后的异步回调核对语义）。 */
    private var downMode = Mode.NONE

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

    // ==================== 注入副本识别（签名精确过滤） ====================
    //
    // 注入的 tap 会回流到 monitor（spy 收到事件副本）。以注入时记录的
    // 事件签名精确识别自身副本并整串忽略——替代旧的时间窗过滤
    // （800ms 整窗会误杀用户紧随其后的真实触摸，快速连续操作时表现为
    // "点了没反应"）。签名为注入事件的固有属性（我们构造事件时自选的
    // downTime 毫秒值 + 伪装的 deviceId + 抖动后坐标），真实触摸不可能
    // 逐项吻合：downTime 精确到毫秒的重合概率可忽略，坐标含随机抖动
    // 更无从预知。
    private var injectDownTime = 0L
    private var injectDeviceId = 0
    private var injectX = 0f
    private var injectY = 0f

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    init {
        gestureDetector = GestureDetector(context, GestureListener())
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
    }

    // ==================== 事件入口（handler 线程调用） ====================

    fun onTouch(event: MotionEvent) {
        if (!backend.isAttached) return

        // 注入副本（downTime/deviceId/坐标与注入记录吻合）：逐事件直接
        // 忽略，零状态干扰——不 pilfer（副本本就要透传给下层应用）、
        // 不喂 detector（防自激励）、不改任何在途手势状态。不能只过滤
        // DOWN：onSingleTapConfirmed 可能在另一真实手势进行中触发
        // （双击确认窗口内的快速异地触摸），若此时按 DOWN 抑制整串，
        // 会误杀在途真实手势
        if (isSelfInjected(event)) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 命中悬浮窗 → 抢占指针流，此后下层应用收不到本手势
                if (event.x >= windowX && event.x <= windowX + windowWidth &&
                    event.y >= windowY && event.y <= windowY + windowHeight
                ) {
                    gestureActive = true
                    input.pilferPointers()
                    handleDown(event)
                }
                // 未命中：不 pilfer，事件自然穿透给下层应用
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                // 消费归属：DOWN 是否命中悬浮窗（见 gestureActive 文档）。
                // 未命中的事件流属于下层应用，一概忽略
                if (!gestureActive) return
                val terminal = event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                try {
                    if (gestureDetector.onTouchEvent(event)) return
                    scaleDetector.onTouchEvent(event)
                    if (scaleDetector.isInProgress) return
                    dispatchAction(event)
                } finally {
                    // 终态事件无论被哪一层消费（detector 早退 / scale 进行中），
                    // 都必须复位本轮手势归属，防状态悬挂
                    if (terminal) gestureActive = false
                }
            }
            else -> {
                gestureDetector.onTouchEvent(event)
                scaleDetector.onTouchEvent(event)
            }
        }
    }

    private fun handleDown(event: MotionEvent) {
        isLongPress = false
        lockedMode = detectMode(event.x - windowX, event.y - windowY)
        downMode = lockedMode
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
        var newW: Int
        var newH: Int
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
        // 屏幕尺寸未知：clamp 边界缺失，静默丢弃（几何信息由 attach 路径
        // 多路径解析获得，此为极端失败路径）
        if (screenWidth <= 0 || screenHeight <= 0) return
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
            // 视频非边缘区域的单击：注入点击透传给下层应用。
            // 以 downMode（DOWN 时的模式判定）核对——回调触发时
            // lockedMode 已被 UP 复位为 NONE，旧条件恒真，边缘区域
            // （移动条/缩放手柄）的单击会被误注入透传
            if (backend.isVideo && downMode == Mode.NONE && !isLongPress) {
                injectTap(e.x.toInt(), e.y.toInt())
            }
            return true
        }
    }

    // ==================== 单击透传注入 ====================

    /**
     * 直接构造 down+up 注入（uid=0 过 INJECT_EVENTS）。注入事件不经本
     * monitor 拦截（spy 只观察），直接派发到下层窗口。
     *
     * 全参数真实设备伪装——注入事件无任何"虚拟设备"指纹，下层应用
     * （含反作弊自检）无从区分真实手指：
     * - deviceId / xPrecision / yPrecision 取自真实 touchscreen 设备
     *   （虚拟设备指纹 deviceId=0 / 精度 0 是旧实现的破绽）；
     * - 坐标 ±1.5px 抖动（真实手指不可能落在精确整数/原样目标点）；
     * - 压力、接触面积在真实区间内随机（恒 1.0 是注入特征）；
     * - DOWN→UP 时长 45~140ms 随机（恒定 16ms 是注入特征），UP 压力
     *   归零与真实抬指一致。
     *
     * 注入前记录事件签名（downTime/deviceId/抖动后坐标），回流副本由
     * [isSelfInjected] 精确识别后整串忽略。
     *
     * 无 shell 兜底：`/system/bin/input` 的 fork+exec 是无法掩盖的强特征
     * （进程创建审计、/proc cmdline、input 工具自身 logcat 输出）。
     * uid=0 下反射注入全版本可用；极端失败路径选择静默丢弃本次 tap
     * （用户重试即可），绝不以暴露进程特征为代价。
     */
    private fun injectTap(x: Int, y: Int) {
        val device = pickRealTouchscreen()
        val deviceId = device?.id ?: 0
        val xPrecision = device
            ?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHSCREEN)
            ?.resolution?.takeIf { it > 0f } ?: 1f
        val yPrecision = device
            ?.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHSCREEN)
            ?.resolution?.takeIf { it > 0f } ?: 1f

        val downTime = SystemClock.uptimeMillis()
        val jx = x + (Auxiliary.getSecureRandomFloat() * 3f - 1.5f)
        val jy = y + (Auxiliary.getSecureRandomFloat() * 3f - 1.5f)
        val duration = 45L + Auxiliary.getSecureRandomLong(95L)
        val downPressure = 0.45f + Auxiliary.getSecureRandomFloat() * 0.45f
        val downSize = 0.08f + Auxiliary.getSecureRandomFloat() * 0.17f

        injectDownTime = downTime
        injectDeviceId = deviceId
        injectX = jx
        injectY = jy

        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        fun event(action: Int, eventTime: Long, pressure: Float, size: Float) =
            MotionEvent.obtain(
                downTime, eventTime, action, 1, props, arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = jx
                        this.y = jy
                        this.pressure = pressure
                        this.size = size
                    }
                ),
                0, 0, xPrecision, yPrecision, // metaState, buttonState, xPrecision, yPrecision
                deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0 // deviceId, edgeFlags, source, flags
            )

        runCatching {
            val im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
            val inject = im.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java, Int::class.javaPrimitiveType
            )
            val down = event(MotionEvent.ACTION_DOWN, downTime, downPressure, downSize)
            // 真实抬指：UP 压力/面积归零
            val up = event(MotionEvent.ACTION_UP, downTime + duration, 0f, 0f)
            try {
                inject.invoke(im, down, 0)
                inject.invoke(im, up, 0)
            } finally {
                down.recycle()
                up.recycle()
            }
        }
    }

    /**
     * 选取真实 touchscreen 设备（注入伪装用）：id 非 0（非虚拟）、
     * 非虚拟设备、source 含 touchscreen。无触摸屏的极端环境返回 null
     * （注入退回 deviceId=0，仅保功能——此时设备本身无真实触摸可言）。
     */
    private fun pickRealTouchscreen(): InputDevice? = runCatching {
        InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull {
                it.id != 0 && !it.isVirtual &&
                        (it.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0
            }
    }.getOrNull()

    /**
     * 精确判定事件是否为自身注入的副本：与最近一次注入的 downTime
     * （毫秒级精确匹配，DOWN/UP 副本同源同值）、deviceId（注入时记录的
     * 伪装设备 id）、单指针、坐标（抖动后，±2f 容差）逐项吻合。downTime
     * 为注入时自选的 uptimeMillis、坐标含随机抖动，真实触摸逐项吻合的
     * 概率可忽略。
     */
    private fun isSelfInjected(e: MotionEvent): Boolean =
        injectDownTime != 0L &&
                e.downTime == injectDownTime &&
                e.deviceId == injectDeviceId &&
                e.pointerCount == 1 &&
                kotlin.math.abs(e.x - injectX) <= 2f &&
                kotlin.math.abs(e.y - injectY) <= 2f
}
