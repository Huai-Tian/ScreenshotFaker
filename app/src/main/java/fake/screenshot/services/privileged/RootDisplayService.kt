package fake.screenshot.services.privileged

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaPlayer
import android.net.LocalServerSocket
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import fake.screenshot.views.CornerHandleView
import rikka.shizuku.ShizukuApiConstants
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行于 Shizuku root 进程（uid=0）的悬浮显示窗口服务。
 *
 * 为什么必须放在 root 进程：
 * - 消除 FLAG_WINDOW_IS_OBSCURED 需要 LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY，
 *   而 WMS 校验其签名权限 INTERNAL_SYSTEM_WINDOW。应用进程（哪怕有 root 授权的
 *   appops）在 checkCallingOrSelfPermission 中按 uid 判定，普通应用 uid 无法通过；
 *   uid=0（root）在 CheckPermissionUtil 中直接 PERMISSION_GRANTED，标记真实生效。
 *   此后本窗口（FLAG_NOT_TOUCHABLE 穿透）遮挡下层应用时，InputDispatcher 的
 *   obscured 检查会跳过 trusted overlay，下层应用触摸事件不再携带遮挡标记。
 * - 窗口归属为 system context 的 "android" 包：无障碍/系统侧看到的即系统窗口，
 *   与真实 SystemUI 窗口表现一致，无需任何伪装与全局设置修改。
 *
 * 进程环境说明（Shizuku UserService v3）：
 * - 本服务由 Shizuku 服务端用 DexClassLoader 从本应用 APK 反射实例化，
 *   运行在 fork 自 Shizuku server 的 root 进程中，无 Android 应用组件环境。
 * - ViewRootImpl 要求创建线程持有 Looper，因此所有窗口操作集中在专用
 *   HandlerThread；AIDL 方法（binder 线程进入）一律转发到该线程执行。
 * - system context 通过反射 ActivityThread.systemMain() 获取：attach(true)
 *   分支不与 AMS 交互，可在任意 ART 进程安全调用；进程内只初始化一次。
 * - 媒体内容以 ParcelFileDescriptor 传入（binder 传递时已 dup），本进程
 *   负责关闭；视频 fd 须保持打开直到 MediaPlayer 释放。
 */
class RootDisplayService : IRootDisplay.Stub() {

    companion object {
        // Shizuku 进程内全局共享（同 APK 的 classloader 只加载一次）
        @Volatile
        private var systemContext: Context? = null

        private fun obtainSystemContext(): Context? {
            systemContext?.let { return it }
            return runCatching {
                val atClass = Class.forName("android.app.ActivityThread")
                val current = runCatching {
                    atClass.getMethod("currentActivityThread").invoke(null)
                }.getOrNull()
                val at = current ?: atClass.getMethod("systemMain").invoke(null)
                (atClass.getMethod("getSystemContext").invoke(at) as Context)
                    .also { systemContext = it }
            }.getOrNull()
        }

        // root 进程按策略不受 hidden API 限制，这里显式豁免以保证各 ROM 行为一致；
        // 失败不影响主流程（后续每步均有回退）
        private fun exemptHiddenApi() {
            runCatching {
                val vm = Class.forName("dalvik.system.VMRuntime")
                vm.getMethod("setHiddenApiExemptions", Array<String>::class.java)
                    .invoke(vm.getMethod("getRuntime").invoke(null), arrayOf("L"))
            }
        }

        /**
         * 在窗口 LayoutParams 上写入 PRIVATE_FLAG_TRUSTED_OVERLAY（hidden 常量 1 shl 28）。
         *
         * 仅在 root（uid=0）进程中调用真实生效：WMS 校验签名权限
         * INTERNAL_SYSTEM_WINDOW，uid=0 的 binder 调用直接 GRANTED；之后本窗口
         * （FLAG_NOT_TOUCHABLE 穿透）遮挡下层应用时，InputDispatcher 的遮挡检查
         * 会跳过 trusted overlay，下层应用触摸事件不再携带 FLAG_WINDOW_IS_OBSCURED。
         *
         * 刻意不做的（会留痕）：修改 Settings.Global（如 block_untrusted_touches）
         * 或 appops——任何应用可读取，本身即特征。
         *
         * 返回是否成功写入字段（失败=false：穿透触摸将携带遮挡标记，仅 logcat 留痕）。
         */
        @Suppress("DiscouragedPrivateApi")
        private fun applyTrustedOverlay(params: WindowManager.LayoutParams): Boolean {
            return try {
                val field = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
                field.isAccessible = true
                field.set(params, (field.get(params) as Int) or (1 shl 28))
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    // ViewRootImpl 绑定该线程的 Looper：Choreographer、View 绘制、Surface 控制
    private val handlerThread = HandlerThread("RootDisplay").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var windowManager: WindowManager? = null
    private var floatingView: FrameLayout? = null
    private var cornerHandleView: CornerHandleView? = null
    private var params: WindowManager.LayoutParams? = null

    // 控制窗口：透明可触摸，与显示窗口同几何；
    // 手势全部在本进程内检测处理（见 attachControl）
    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    // 应用进程反向回调（切换媒体）
    private var callback: IRootDisplayCallback? = null

    private var contentContainer: FrameLayout? = null
    private var imageView: ImageView? = null
    private var surfaceView: SurfaceView? = null
    private var mediaPlayer: MediaPlayer? = null

    // 视频文件 fd：MediaPlayer.setDataSource 后仍需保持打开，直至释放
    private var videoFd: ParcelFileDescriptor? = null

    // 当前媒体是否视频：手势判定（长按 seek/双击分区/单击透传）在本进程内直接读取
    private var currentIsVideo = false

    // ---------- 控制窗口手势状态（与本地 ControlOverlayService 逻辑一致） ----------

    // 手势模式：NONE/移动窗口/移动媒体（图片平移）/四角缩放
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

    // 视频长按快进/快退：左半区快退（-1），右半区快进（+1）
    // 步长 5s 起步逐次翻倍、封顶 30s，每 500ms 一步——按住越久跳得越快
    private var seekDirection = 0
    private var seekStepMs = 0

    private var screenWidth = 0
    private var screenHeight = 0

    private val minSize = 80
    private val touchSlop = 60

    // 在 handler 线程（窗口事件派发线程）创建并使用
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null

    // 图片几何状态（与本地模式手势逻辑一致）
    private var currentScale = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var baseScale = 1.0f
    private var mediaWidth = 0
    private var mediaHeight = 0

    private var isMuted = false

    // ==================== 窗口生命周期 ====================

    override fun attach(x: Int, y: Int, width: Int, height: Int) {
        handler.post { attachInternal(x, y, width, height) }
    }

    private fun attachInternal(x: Int, y: Int, width: Int, height: Int) {
        if (floatingView != null) {
            setGeometryInternal(x, y, width, height)
            return
        }
        try {
            exemptHiddenApi()
            val context = obtainSystemContext() ?: run {
                // 与 Sui 无显示同源的隐患：反射失败绝不能静默吞掉，
                // 必须上报否则应用侧无从感知、悬浮窗消失
                notifyWindowFailed("attach", IllegalStateException("systemContext unavailable"))
                return
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val view = FrameLayout(context).apply {
                setBackgroundColor(Color.RED)
                val handles = CornerHandleView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    isClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
                cornerHandleView = handles
                addView(
                    handles,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val p = WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_SECURE,
                -3 // PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            // uid=0 下签名权限校验直接通过，TRUSTED_OVERLAY 真实生效
            // （消除穿透触摸的 FLAG_WINDOW_IS_OBSCURED）。trusted=false 说明
            // 反射写 privateFlags 失败——穿透触摸将携带 obscured 标志被
            // 下层应用感知，logcat 留痕以便排查（logcat 其他应用不可读）
            val trusted = applyTrustedOverlay(p)

            wm.addView(view, p)
            floatingView = view
            params = p
            windowManager = wm
            android.util.Log.i("RootDisplay", "display attached trusted=$trusted")
        } catch (t: Throwable) {
            // 绝不静默：上报应用进程回落本地窗口（否则悬浮窗"无任何显示"）
            notifyWindowFailed("attach", t)
            cleanupWindow()
        }
    }

    /** 窗口挂载失败上报（handler 线程内调用；须在 cleanupWindow 清空调用前）。 */
    private fun notifyWindowFailed(where: String, t: Throwable) {
        val cb = callback ?: return
        runCatching {
            cb.onWindowFailed("$where: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun detach() {
        handler.post { detachInternal() }
    }

    private fun detachInternal() {
        clearMediaInternal()
        cleanupWindow()
    }

    private fun cleanupWindow() {
        val view = floatingView
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        floatingView = null
        params = null
        cornerHandleView = null
        removeControlWindowInternal()
        windowManager = null
        callback = null
        stopSeekLoopInternal()
    }

    // ==================== Shizuku 保留事务 ====================

    /**
     * Shizuku 服务器销毁本服务（unbindUserService remove）时以保留事务码
     * USER_SERVICE_TRANSACTION_destroy(16777115) 直接 transact——该值超出
     * AIDL 编译器允许的上限，无法在 IRootDisplay.aidl 中声明，
     * 在此拦截并执行清理（移除窗口、释放线程），避免窗口泄漏。
     *
     * RestrictedApi 警告可安全忽略：ShizukuApiConstants 标了
     * @RestrictTo(LIBRARY_GROUP_PREFIX)，但拦截 destroy 恰是该常量
     * 设计给 UserService 实现方的用途；常量为编译期内联，运行时无检查。
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy) {
            handleDestroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    // Shizuku 销毁本服务（unbindUserService remove）时调用
    private fun handleDestroy() {
        handler.post {
            detachInternal()
            handler.removeCallbacksAndMessages(null)
            handlerThread.quitSafely()
        }
    }

    // ==================== 几何 / 外观 ====================

    override fun setGeometry(x: Int, y: Int, width: Int, height: Int) {
        handler.post { setGeometryInternal(x, y, width, height) }
    }

    private fun setGeometryInternal(x: Int, y: Int, width: Int, height: Int) {
        val p = params ?: return
        val view = floatingView ?: return
        p.x = x
        p.y = y
        p.width = width
        p.height = height
        runCatching { windowManager?.updateViewLayout(view, p) }
        // 控制窗口与显示窗口几何始终同步（root 手势 / 应用进程 setGeometry 均生效）
        val cp = controlParams
        val cv = controlView
        if (cp != null && cv != null) {
            cp.x = x
            cp.y = y
            cp.width = width
            cp.height = height
            runCatching { windowManager?.updateViewLayout(cv, cp) }
        }
        cornerHandleView?.invalidate()
        updateImageMatrix()
    }

    override fun setAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        handler.post {
            floatingView?.alpha = clamped
        }
    }

    // ==================== 控制窗口（root 托管手势） ====================

    override fun attachControl(x: Int, y: Int, width: Int, height: Int) {
        handler.post { attachControlInternal(x, y, width, height) }
    }

    private fun attachControlInternal(x: Int, y: Int, width: Int, height: Int) {
        if (controlView != null) {
            // 已挂载：同步几何即可
            setGeometryInternal(x, y, width, height)
            return
        }
        try {
            val context = floatingView?.context ?: obtainSystemContext() ?: run {
                notifyWindowFailed("attachControl", IllegalStateException("systemContext unavailable"))
                return
            }
            val wm = windowManager
                ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 屏幕尺寸：窗口移动/缩放的 clamp 边界
            runCatching {
                val bounds = wm.maximumWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            }

            // GestureDetector 必须与事件派发线程（本 handler 线程）一致
            gestureDetector = GestureDetector(context, ControlGestureListener())
            scaleDetector = ScaleGestureDetector(context, ControlScaleListener())

            val view = View(context).apply {
                setBackgroundColor(0x00000000)
                isClickable = false
                isFocusable = false
            }
            view.setOnTouchListener { _, event -> onControlTouch(event) }

            val p = WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                -3 // PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            // 同显示窗口：uid=0 下 TRUSTED_OVERLAY 真实生效。
            // 控制窗口可触摸、必然遮挡下层应用的触摸事件，正是
            // FLAG_WINDOW_IS_OBSCURED 的来源——root 托管后 InputDispatcher
            // 跳过 trusted overlay 的遮挡标记，下层应用无法感知本窗口存在
            val trusted = applyTrustedOverlay(p)

            wm.addView(view, p)
            controlView = view
            controlParams = p
            windowManager = wm
            android.util.Log.i("RootDisplay", "control attached trusted=$trusted")
        } catch (t: Throwable) {
            // 控制窗口失败同样上报（显示窗口虽在但手势全失效，整体回落本地）
            notifyWindowFailed("attachControl", t)
            removeControlWindowInternal()
        }
    }

    override fun detachControl() {
        handler.post { removeControlWindowInternal() }
    }

    private fun removeControlWindowInternal() {
        val view = controlView
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        controlView = null
        controlParams = null
        gestureDetector = null
        scaleDetector = null
        stopSeekLoopInternal()
    }

    override fun registerCallback(cb: IRootDisplayCallback?) {
        if (cb == null) return
        handler.post { callback = cb }
    }

    // ---------- 触摸事件流（逻辑与本地 ControlOverlayService 一致） ----------

    private fun onControlTouch(event: MotionEvent): Boolean {
        // GestureDetector 优先（长按/双击/单击确认）
        if (gestureDetector?.onTouchEvent(event) == true) {
            return true
        }

        scaleDetector?.onTouchEvent(event)
        if (scaleDetector?.isInProgress == true) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isLongPress = false
                lockedMode = detectModeInternal(event)
                if (lockedMode.name.startsWith("SCALE_")) {
                    isScaling = true
                }
                initialX = controlParams?.x ?: 0
                initialY = controlParams?.y ?: 0
                initialWidth = controlParams?.width ?: 0
                initialHeight = controlParams?.height ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isLongPress) return true
                when (lockedMode) {
                    Mode.MOVE_WINDOW -> handleMoveWindowInternal(event)
                    Mode.MOVE_MEDIA -> handleMoveMediaInternal(event)
                    Mode.SCALE_LEFT_TOP, Mode.SCALE_RIGHT_TOP,
                    Mode.SCALE_LEFT_BOTTOM, Mode.SCALE_RIGHT_BOTTOM -> handleScaleInternal(event)
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPress) {
                    isLongPress = false
                    stopSeekLoopInternal()
                    lockedMode = Mode.NONE
                    isScaling = false
                    return true
                }
                lockedMode = Mode.NONE
                isScaling = false
                return true
            }

            else -> return false
        }
    }

    private fun detectModeInternal(event: MotionEvent): Mode {
        val x = event.x
        val y = event.y
        val w = controlParams?.width ?: 0
        val h = controlParams?.height ?: 0

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
            currentIsVideo -> Mode.NONE
            else -> Mode.MOVE_MEDIA
        }
    }

    private fun handleMoveWindowInternal(event: MotionEvent) {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
        updateOverlayInternal(initialX + dx, initialY + dy, initialWidth, initialHeight)
    }

    private fun handleMoveMediaInternal(event: MotionEvent) {
        val dx = (event.rawX - initialTouchX) * 2f
        val dy = (event.rawY - initialTouchY) * 2f
        panImage(dx, dy)
        initialTouchX = event.rawX
        initialTouchY = event.rawY
    }

    private fun handleScaleInternal(event: MotionEvent) {
        val dx = (event.rawX - initialTouchX).toInt()
        val dy = (event.rawY - initialTouchY).toInt()
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
        updateOverlayInternal(newX, newY, newW, newH)
    }

    /** 与本地 updateOverlay 相同的 clamp 规则，几何同时作用于两窗口。 */
    private fun updateOverlayInternal(x: Int, y: Int, width: Int, height: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val clampedWidth = width.coerceAtMost(screenWidth)
        val clampedHeight = height.coerceAtMost(screenHeight)
        val maxX = screenWidth - clampedWidth
        val maxY = screenHeight - clampedHeight
        val clampedX = x.coerceIn(0, maxX)
        val clampedY = y.coerceIn(0, maxY)
        setGeometryInternal(clampedX, clampedY, clampedWidth, clampedHeight)
    }

    // ---------- 长按 seek 循环 ----------

    private val seekRunnable = object : Runnable {
        override fun run() {
            if (!isLongPress || seekDirection == 0) return
            seekByInternal(seekDirection * seekStepMs)
            seekStepMs = (seekStepMs * 2).coerceAtMost(30_000)
            handler.postDelayed(this, 500)
        }
    }

    private fun startSeekLoopInternal() {
        seekStepMs = 5_000
        seekRunnable.run()
    }

    private fun stopSeekLoopInternal() {
        handler.removeCallbacks(seekRunnable)
        seekDirection = 0
        seekStepMs = 0
    }

    private fun seekByInternal(deltaMs: Int) {
        val mp = mediaPlayer ?: return
        runCatching {
            val duration = mp.duration
            if (duration > 0) {
                val target = (mp.currentPosition + deltaMs)
                    .coerceIn(0, (duration - 250).coerceAtLeast(0))
                mp.seekTo(target)
            }
        }
    }

    // 上次注入时刻：注入的 tap 若再回流到本控制窗口会形成自激励循环
    // （注入 → 命中自己 → onSingleTapConfirmed → 再注入 → …），须拦截
    private var lastInjectAt = 0L

    /**
     * 单击视频非边缘区域：向屏幕注入点击，透传给下层应用（等效本地模式的触摸穿透）。
     *
     * 关键：注入坐标上最顶层的"可触摸"窗口是本控制窗口自己，直接注入只会命中
     * 自己（既到不了下层应用，还会无限自环）。因此注入前瞬时把控制窗口改为
     * FLAG_NOT_TOUCHABLE，让注入事件穿过后命中下层应用；显示窗口虽也覆盖该点
     * 但已是 trusted 的 NOT_TOUCHABLE 穿透窗口，下层收到的 tap 不带 obscured 标志。
     * 注入进程启动有数十毫秒延迟，updateViewLayout 的 binder 传播先行到达即可。
     */
    private fun injectTap(e: MotionEvent) {
        val p = controlParams ?: return
        val view = controlView ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastInjectAt < 800) return
        lastInjectAt = now
        val absX = (p.x + e.x).toInt()
        val absY = (p.y + e.y).toInt()
        val wm = windowManager ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching {
            p.flags = p.flags or notTouchable
            wm.updateViewLayout(view, p)
            ProcessBuilder("/system/bin/input", "tap", absX.toString(), absY.toString())
                .redirectErrorStream(true)
                .start()
        }
        // 恢复控制窗口可触摸（注入事件已经 InputDispatcher 异步派发落地）
        handler.postDelayed({
            runCatching {
                p.flags = p.flags and notTouchable.inv()
                wm.updateViewLayout(view, p)
            }
        }, 300)
    }

    // ---------- 手势监听 ----------

    private inner class ControlScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleImage(detector.scaleFactor)
            return true
        }
    }

    private inner class ControlGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // 仅视频启用长按 seek；缩放/窗口移动/边缘调整模式不触发
            if (isScaling || lockedMode != Mode.NONE) return
            if (!currentIsVideo) return
            val halfWidth = (controlView?.width ?: return) / 2f
            seekDirection = if (e.x < halfWidth) -1 else 1
            isLongPress = true
            startSeekLoopInternal()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isScaling) return false
            val view = controlView ?: return false

            if (currentIsVideo) {
                // 视频：左25%上一张，中间50%播放/暂停，右25%下一张
                val width = view.width.toFloat()
                val delta = when {
                    e.x < width * 0.25f -> -1
                    e.x > width * 0.75f -> 1
                    else -> 0
                }
                if (delta != 0) {
                    runCatching { callback?.onSwitchMedia(delta) }
                } else {
                    togglePlayPause()
                }
            } else {
                // 图片：左半区上一张，右半区下一张
                val halfWidth = view.width / 2f
                val delta = if (e.x < halfWidth) -1 else 1
                runCatching { callback?.onSwitchMedia(delta) }
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 视频非边缘区域的单击：注入点击透传给下层应用（本地模式靠事件穿透实现，
            // root 托管窗口收到事件后无法原样穿透，只能以 input tap 等效注入）
            if (currentIsVideo && lockedMode == Mode.NONE && !isLongPress) {
                injectTap(e)
            }
            return true
        }
    }

    // ==================== 媒体显示 ====================

    override fun showImage(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        handler.post { showImageInternal(fd) }
    }

    private fun showImageInternal(fd: ParcelFileDescriptor) {
        clearMediaInternal()
        currentIsVideo = false
        try {
            val bitmap = fd.use { decodeBitmap(it) }
            if (bitmap == null) {
                floatingView?.setBackgroundColor(Color.RED)
                return
            }
            val context = floatingView?.context ?: return
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.MATRIX
                setImageBitmap(bitmap)
            }
            mediaWidth = bitmap.width
            mediaHeight = bitmap.height
            imageView = iv
            contentContainer = FrameLayout(context).apply {
                addView(
                    iv,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            attachContent()
            currentScale = 1.0f
            panX = 0f
            panY = 0f
            updateImageMatrix()
        } catch (_: Throwable) {
            floatingView?.setBackgroundColor(Color.RED)
        }
    }

    /**
     * 按窗口两倍尺寸降采样解码，控制 root（Shizuku 宿主）进程内存；
     * 边界探测 + 解码需 fd 可 seek，对管道型 fd 失败时返回 null（与本地模式
     * setImageURI 的约束一致，显示红底）。
     */
    private fun decodeBitmap(fd: ParcelFileDescriptor): Bitmap? {
        val fdo = fd.fileDescriptor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fdo, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val p = params
        val reqW = ((p?.width ?: 0) * 2).coerceAtLeast(320)
        val reqH = ((p?.height ?: 0) * 2).coerceAtLeast(320)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFileDescriptor(fdo, null, opts)
    }

    override fun showVideo(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        handler.post { showVideoInternal(fd) }
    }

    private fun showVideoInternal(fd: ParcelFileDescriptor) {
        clearMediaInternal()
        currentIsVideo = true
        videoFd = fd
        val context = floatingView?.context ?: run {
            runCatching { fd.close() }
            videoFd = null
            return
        }
        val sv = SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    // 读字段而非闭包 fd：可能先于本回调发生 clearMedia
                    initPlayer(videoFd, holder.surface)
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
            setZOrderMediaOverlay(true)
        }
        surfaceView = sv
        contentContainer = FrameLayout(context).apply {
            addView(
                sv,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        attachContent()
    }

    private fun attachContent() {
        val view = floatingView ?: return
        val container = contentContainer ?: return
        view.addView(
            container,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        view.bringChildToFront(cornerHandleView)
        view.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun initPlayer(fd: ParcelFileDescriptor?, surface: Surface) {
        val fdo = fd?.fileDescriptor ?: return
        releasePlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(fdo)
                setSurface(surface)
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                isLooping = true
                setOnPreparedListener {
                    applyMuteState()
                    start()
                }
                setOnErrorListener { _, _, _ -> false }
                prepare()
            }
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    override fun clearMedia() {
        handler.post { clearMediaInternal() }
    }

    private fun clearMediaInternal() {
        currentIsVideo = false
        releasePlayer()
        videoFd?.let { runCatching { it.close() } }
        videoFd = null
        contentContainer?.let { c -> floatingView?.removeView(c) }
        contentContainer = null
        imageView = null
        surfaceView = null
        mediaWidth = 0
        mediaHeight = 0
        currentScale = 1.0f
        panX = 0f
        panY = 0f
        baseScale = 1.0f
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun applyMuteState() {
        mediaPlayer?.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
    }

    // ==================== 图片缩放 / 平移 ====================

    override fun scaleImage(factor: Float) {
        handler.post {
            if (imageView == null) return@post
            var newScale = currentScale * factor
            newScale = maxOf(newScale, 1.0f)
            if (newScale > 5.0f) return@post
            currentScale = newScale
            clampPan()
            updateImageMatrix()
        }
    }

    override fun panImage(dx: Float, dy: Float) {
        handler.post {
            if (imageView == null) return@post
            panX += dx
            panY += dy
            clampPan()
            updateImageMatrix()
        }
    }

    private fun updateImageMatrix() {
        val view = imageView ?: return
        val p = params ?: return
        if (mediaWidth <= 0 || mediaHeight <= 0) return
        val viewWidth = p.width
        val viewHeight = p.height
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
        val p = params ?: return
        if (mediaWidth <= 0 || mediaHeight <= 0) return
        val viewWidth = p.width
        val viewHeight = p.height
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

    // ==================== 视频控制 ====================

    override fun togglePlayPause() {
        handler.post {
            val mp = mediaPlayer ?: return@post
            runCatching {
                if (mp.isPlaying) mp.pause() else mp.start()
            }
        }
    }

    override fun seekBy(deltaMs: Int) {
        handler.post {
            val mp = mediaPlayer ?: return@post
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

    override fun setMuted(muted: Boolean) {
        handler.post {
            isMuted = muted
            applyMuteState()
        }
    }
}

/**
 * su 直连模式的 root 宿主进程入口（内联于本文件：su 命令行以本类名为
 * app_process 入口，与 RootDisplayService 同属 root 服务实现，并非独立服务）。
 *
 * 由应用进程执行：
 *   su -c "CLASSPATH='<apk路径>' exec /system/bin/app_process / \
 *          fake.screenshot.services.privileged.SuLauncher <socket名>"
 *
 * 以 CLASSPATH=本应用 APK 启动的 app_process 会用 PathClassLoader 加载
 * 全部应用类（与 Shizuku UserService 从同一 APK 反射加载完全同源），
 * 因此 RootDisplayService 无需任何改动即可在本进程实例化：它自建
 * HandlerThread（ViewRootImpl 的 Looper 要求）、反射 ActivityThread.
 * systemMain() 获取 system context，addView 时以 uid=0 通过
 * INTERNAL_SYSTEM_WINDOW 校验使 TRUSTED_OVERLAY 真实生效。
 *
 * 进程生命周期：
 * - 启动后在抽象命名空间监听（名字由应用进程随机生成传入，不可猜测）；
 * - 看门狗：15 秒内未等到连接则退出（应用进程侧 8 秒已放弃，防止孤儿
 *   root 进程常驻）；
 * - 连接后进入帧派发循环，直接调用 RootDisplayService 方法（其方法与
 *   binder 线程进入时一样内部 post 到窗口线程，线程安全）；
 * - 收到 destroy 帧、对端关闭（应用进程死亡）或协议错误即退出——进程
 *   死亡时 WMS 经 binder 死亡通知自动摘除本进程窗口，不会泄漏。
 */
object SuLauncher {

    /** 无人连接时 root 进程最长存活时间（毫秒）。 */
    private const val ACCEPT_WATCHDOG_MS = 15_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val name = args.getOrNull(0) ?: return

        val server = runCatching { LocalServerSocket(name) }.getOrNull() ?: return

        val connected = AtomicBoolean(false)
        Thread {
            runCatching { Thread.sleep(ACCEPT_WATCHDOG_MS) }
            if (!connected.get()) {
                runCatching { server.close() }
                // 未等到应用连接：直接退出，不留任何进程痕迹
                Runtime.getRuntime().exit(0)
            }
        }.apply {
            isDaemon = true
            this.name = "SuAcceptWatchdog"
        }.start()

        val socket = runCatching { server.accept() }.getOrNull()
        connected.set(true)
        if (socket == null) {
            Runtime.getRuntime().exit(0)
            return
        }

        // 窗口宿主：与 Shizuku UserService 共用同一实现
        val service = RootDisplayService()

        val transport = SuTransport.accepted(socket)

        // 反向回调（root -> app）：手势判定"切换媒体"/窗口挂载失败时经
        // socket 帧通知。binder 对象无法跨 socket 序列化，故以发送代理
        // 对象就地注入（与 Shizuku 路径的 registerCallback binder 代理等效）。
        service.registerCallback(SuCallbackSender(transport))

        // 帧派发循环：本线程即"协议线程"，等价于 Shizuku 路径的 binder 线程
        while (true) {
            val frame = runCatching { transport.readFrame() }.getOrNull() ?: break
            if (frame.code == SuProto.CODE_DESTROY) break
            val ok = runCatching { dispatch(service, frame) }.getOrDefault(false)
            if (!ok) break
        }

        // 进程退出即清理：WMS 摘除窗口、MediaPlayer 释放（进程级资源回收）
        runCatching { transport.close() }
        Runtime.getRuntime().exit(0)
    }

    /** 参数与 SuRootDisplayProxy 各方法的写入顺序一一对应；false 表示协议错误。 */
    private fun dispatch(service: RootDisplayService, frame: SuTransport.Frame): Boolean {
        val di = DataInputStream(ByteArrayInputStream(frame.payload))
        fun i(): Int = di.readInt()
        fun f(): Float = di.readFloat()
        fun b(): Boolean = di.readBoolean()

        when (frame.code) {
            1 -> service.attach(i(), i(), i(), i())
            2 -> service.detach()
            3 -> service.setGeometry(i(), i(), i(), i())
            4 -> service.setAlpha(f())
            5 -> {
                // SCM_RIGHTS 收到的 fd 归本进程所有：dup 成 ParcelFileDescriptor
                // 后关闭原始副本（dup 与 binder 传递语义一致）
                val fd = frame.fds.firstOrNull() ?: return false
                val pfd = ParcelFileDescriptor.dup(fd)
                android.system.Os.close(fd)
                service.showImage(pfd)
            }
            6 -> {
                val fd = frame.fds.firstOrNull() ?: return false
                val pfd = ParcelFileDescriptor.dup(fd)
                android.system.Os.close(fd)
                service.showVideo(pfd)
            }
            7 -> service.clearMedia()
            8 -> service.scaleImage(f())
            9 -> service.panImage(f(), f())
            10 -> service.togglePlayPause()
            11 -> service.seekBy(i())
            12 -> service.setMuted(b())
            13 -> service.attachControl(i(), i(), i(), i())
            14 -> service.detachControl()
            else -> {
                // 未知帧：忽略（向前兼容），不算协议错误
            }
        }
        return true
    }

    private class SuCallbackSender(
        private val transport: SuTransport
    ) : IRootDisplayCallback.Stub() {
        override fun onSwitchMedia(delta: Int) {
            runCatching {
                transport.sendFrame(SuProto.CODE_ON_SWITCH_MEDIA) { it.writeInt(delta) }
            }
        }

        override fun onWindowFailed(reason: String?) {
            runCatching {
                transport.sendFrame(SuProto.CODE_ON_WINDOW_FAILED) {
                    it.writeUTF(reason ?: "unknown")
                }
            }
        }
    }
}
