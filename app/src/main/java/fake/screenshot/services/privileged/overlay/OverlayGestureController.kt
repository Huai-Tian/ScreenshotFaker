package fake.screenshot.services.privileged.overlay

import android.content.Context
import android.os.Handler
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * 纯 Surface 方案的手势状态机：逻辑与旧 RootOverlayService /
 * 本地 ControlOverlayService 完全一致，仅输入源由 View 触摸事件
 * 换为 [GestureInputMonitor] 解析出的 MotionEvent（屏幕绝对坐标）。
 *
 * ==================== 纯观察者模式（零输入暴露面） ====================
 *
 * 本类只消费 spy monitor 的**事件副本**，从不调用 pilferPointers、
 * 从不注入任何事件：
 * - 下层应用收到的指针流永远是调度器原生输出（DOWN→MOVE…→UP 完整
 *   合法收尾）——不存在孤儿 DOWN、不存在无成因 CANCEL、不存在指针流
 *   截断，输入层无从区分"悬浮窗存在"与"悬浮窗不存在"；
 * - 悬浮窗的手势（移动/缩放/双击/长按）从副本旁观判定——"跟随手指"
 *   变为"旁观手指"；
 * - 代价（有意接受）：同一次触摸被下层应用与悬浮窗同时消费——拖动
 *   悬浮窗时下层也在滚动，悬浮窗盖住按钮时按钮也会被点中。交互意图
 *   混流是纯观察架构的本质属性，换取的是输入层绝对零残留。
 *
 * 模式：移动窗口 / 移动媒体（图片平移）/ 四角缩放 / 长按 seek /
 * 双击分区（切换媒体、播放暂停）。视频单击/双击的未命中区域无需
 * 任何处理——事件本来就在向下派发（原生透传）。
 */
internal class OverlayGestureController(
    context: Context,
    private val handler: Handler,
    private val backend: OverlaySurfaceBackend,
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
     * 本轮事件流始于悬浮窗上的 DOWN（命中矩形，从副本旁观响应）。MOVE/UP/CANCEL 的
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

    // ==================== 注入副本识别（已废弃：纯观察者模式无注入） ====================
    //
    // 历史实现为视频单击透传注入 pilferPointers + injectTap，注入副本
    // 回流 monitor 后以签名过滤。纯观察者模式下既不注入也不 pilfer，
    // 回流过滤不再必要——monitor 副本与派发流同源同形，无需区分。

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
                // 命中悬浮窗 → 仅标记本轮流的手势归属（从副本旁观响应）。
                // 不 pilfer：真实事件流继续原生派发下层应用，悬浮窗与
                // 下层同时消费同一次触摸（纯观察者模式，见类文档）
                if (event.x >= windowX && event.x <= windowX + windowWidth &&
                    event.y >= windowY && event.y <= windowY + windowHeight
                ) {
                    gestureActive = true
                    handleDown(event)
                }
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                // 消费归属：DOWN 是否命中悬浮窗（见 gestureActive 文档）。
                // 未命中的事件流只有下层应用消费，一概忽略
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
            // 纯观察者模式：单击透传无需处理——事件本来就在原生向下
            // 派发（历史实现经 pilfer + injectTap 合成透传，现已整体
            // 移除，见类文档"注入副本识别"一节）
            return false
        }
    }
}
