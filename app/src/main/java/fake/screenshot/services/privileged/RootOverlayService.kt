package fake.screenshot.services.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import fake.screenshot.Auxiliary
import fake.screenshot.Auxiliary.enableScreenshotExclusion
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ROOT 权限下的无痕悬浮窗（单文件实现：服务类即接口）。
 *
 * ==================== 架构总览 ====================
 *
 * 本文件是唯一的服务类文件，同时承载两半（分别运行于不同进程）：
 *
 * - 【root 进程侧】[RootOverlayService] 本体（继承 Binder，手写 onTransact
 *   协议，不依赖 AIDL）：反射 ActivityThread.systemMain() 取 system context，
 *   在 uid=0 进程内创建"显示窗口 + 控制窗口"双窗口并承载全部手势与媒体
 *   渲染；
 * - 【应用进程侧】[RootOverlayService] 的 companion object 即对外接口：
 *   bind/unbind 管理双后端（Shizuku UserService 优先 / su app_process 兜底），
 *   并暴露 attach/detach/showImage 等指令方法与 Listener 通知。
 *
 * ==================== 为什么必须运行在 root（uid=0）进程 ====================
 *
 * 消除触摸遮挡标记需要 LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY：
 * WMS 注册输入窗口句柄时的判定为
 *
 *   mInputWindowHandle.setTrustedOverlay(
 *       ((mAttrs.privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0
 *           && mOwnerCanAddInternalSystemWindow)
 *       || InputMonitor.isTrustedOverlay(mAttrs.type));
 *
 * 该私有标志要求签名权限 INTERNAL_SYSTEM_WINDOW——应用进程（哪怕持有
 * root 授权的 appops）按 uid 判定无法通过；uid=0 在 CheckPermissionUtil
 * 中直接 PERMISSION_GRANTED，标志真实生效。此后本窗口遮挡下层应用时，
 * InputDispatcher 的遮挡检查（FLAG_WINDOW_IS_OBSCURED /
 * FLAG_WINDOW_IS_PARTIALLY_OBSCURED）跳过 trusted overlay，下层应用触摸
 * 事件不再携带任何遮挡标记——setFilterTouchesWhenObscured 防护失效，
 * 第三方应用无法经触摸事件感知本窗口存在。
 *
 * 同时写入 PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY（同为签名权限，
 * uid=0 反射写入，经反射读常量值避免版本差异）：Android 12+ 下层应用可
 * 调用 Window.setHideOverlayWindows(true) 隐藏其他应用的 overlay 窗口，
 * 该标志使本窗口对此免疫——"不被屏蔽"。
 *
 * 窗口归属为 system context 的 "android" 包：无障碍/系统侧看到的即系统
 * 窗口，与真实 SystemUI 窗口表现一致，无需任何包名伪装与全局设置修改
 * （修改 Settings.Global / appops 本身即是可检测特征，刻意不做）。
 *
 * ==================== 检测面对照 ====================
 *
 * | 第三方检测手段                          | 对策                        |
 * |----------------------------------------|-----------------------------|
 * | 触摸事件 FLAG_WINDOW_IS_OBSCURED(_PART) | TRUSTED_OVERLAY（uid=0）    |
 * | setFilterTouchesWhenObscured 拦截       | 同上（标记不产生）          |
 * | Android 12+ block_untrusted_touches    | 可信层豁免，穿透不被拦      |
 * | setHideOverlayWindows() 隐藏            | SYSTEM_APPLICATION_OVERLAY  |
 * | 截图/录屏中出现悬浮窗                    | FLAG_SECURE + setSkipScreenshot |
 * | 无障碍枚举窗口                          | 归属 "android" 系统包       |
 * | SYSTEM_ALERT_WINDOW AppOps 追溯         | 窗口不经普通 overlay 通道   |
 *
 * ==================== binder 协议（手写，无 AIDL）====================
 *
 * 事务码与 su 帧协议共用一套（payload 一律为 Parcel 编组字节）：
 *
 *   1 attach(x,y,w,h)    2 detach            3 setGeometry(x,y,w,h)
 *   4 setAlpha(f)        5 showImage(fd)     6 showVideo(fd)
 *   7 clearMedia         8 scaleImage(f)     9 panImage(f,f)
 *   10 togglePlayPause   11 seekBy(ms)      12 setMuted(b)
 *   13 attachControl()   14 detachControl()  15 registerCallback(binder)
 *
 * root -> app 回调码：
 *
 *   100 onSwitchMedia(delta)    101 onWindowFailed(reason)
 *
 *   su 路径回调 binder 对象无法跨 socket 序列化，root 端启动时就地注入
 *   帧发送代理；999 为 su 路径 destroy 帧。
 *
 * ==================== 进程环境说明（Shizuku UserService v4）====================
 *
 * - 本服务由 Shizuku 服务端用 DexClassLoader 从本应用 APK 反射实例化，
 *   运行在 fork 自 Shizuku server 的 root 进程中，无 Android 应用组件环境；
 *   su 路径由 [SuLauncher] 以 CLASSPATH=本 APK 的 app_process 加载，同源。
 * - ViewRootImpl 要求创建线程持有 Looper，因此所有窗口操作集中在专用
 *   HandlerThread；协议入口（binder 线程 / su 帧线程）一律转发到该线程执行。
 * - 媒体内容以 ParcelFileDescriptor 传入（binder / SCM_RIGHTS 传递时内核
 *   已 dup），root 进程负责关闭；视频 fd 须保持打开直到 MediaPlayer 释放。
 */
class RootOverlayService : Binder() {

    /** 连接状态通知（回调发生在主线程）。 */
    fun interface Listener {
        fun onConnectionChanged(active: Boolean)
    }

    /** root 端手势判定"切换媒体"时回调应用进程（delta 为 -1/+1）。 */
    fun interface MediaSwitchHandler {
        fun onSwitchMedia(delta: Int)
    }

    // ==================== 应用进程侧：对外接口与连接管理 ====================

    companion object {

        private const val APPLICATION_ID = "fake.screenshot"

        // version 用于让 Shizuku 服务端区分实现版本：修改本类行为/结构后
        // 必须递增，否则服务端可能沿用旧版本缓存的类。
        // v4：脱离旧 AIDL 接口，手写 binder 协议单文件实现
        // （su 直连路径不经过 Shizuku，与该版本号无关）
        private val args by lazy {
            Shizuku.UserServiceArgs(
                ComponentName(APPLICATION_ID, RootOverlayService::class.java.name)
            )
                .processNameSuffix("overlay")
                .version(4)
        }

        @Volatile
        private var api: Api? = null

        @Volatile
        var isActive: Boolean = false
            private set

        @Volatile
        private var suBackend: SuOverlayConnection? = null

        @Volatile
        private var pendingSu = false

        /** unbind/rebind 时递增：作废仍在途的 su 连接请求 */
        private val generation = AtomicInteger(0)

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        @Volatile
        private var mediaSwitchHandler: MediaSwitchHandler? = null

        private val listeners = CopyOnWriteArraySet<Listener>()

        fun addListener(listener: Listener) {
            listeners.add(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun setMediaSwitchHandler(handler: MediaSwitchHandler?) {
            mediaSwitchHandler = handler
        }

        // ---------- 指令面：应用进程调用，转发当前活跃后端 ----------

        fun attach(x: Int, y: Int, width: Int, height: Int) {
            api?.attach(x, y, width, height)
        }

        fun detach() {
            api?.detach()
        }

        fun setAlpha(alpha: Float) {
            api?.setAlpha(alpha)
        }

        fun showImage(fd: ParcelFileDescriptor?) {
            api?.showImage(fd)
        }

        fun showVideo(fd: ParcelFileDescriptor?) {
            api?.showVideo(fd)
        }

        fun clearMedia() {
            api?.clearMedia()
        }

        fun setMuted(muted: Boolean) {
            api?.setMuted(muted)
        }

        fun attachControl() {
            api?.attachControl()
        }

        fun detachControl() {
            api?.detachControl()
        }

        /**
         * 绑定 root 托管服务（双后端自动选择）：
         * - 后端 1（优先）：Shizuku UserService——Sui 或以 root 启动的 Shizuku；
         * - 后端 2（兜底）：su 直连（[SuOverlayConnection]）——仅凭 root 管理器
         *   （Magisk/KernelSU 等）对本应用的授权即可拉起 root 宿主进程。
         *
         * 返回 false 表示立即判定不可行（无 root 且无 su），调用方应同步走
         * 普通路线；返回 true 表示已绑定或正在建立（su 异步路径，上限 8 秒，
         * 覆盖 root 管理器授权弹窗），结果经 [Listener] 通知。
         */
        fun bind(context: Context): Boolean {
            if (isActive || pendingSu) return true

            // 后端 1：Sui / 以 root 运行的 Shizuku
            if (isShizukuRoot()) {
                try {
                    Shizuku.bindUserService(args, connection)
                    return true
                } catch (_: Throwable) {
                    // binder 竞态/未授权/版本不支持：落到 su 兜底
                }
            }

            // 后端 2：root 管理器直接授权（su），无需 Shizuku/Sui 存在
            if (suBackend != null) return true
            val gen = generation.incrementAndGet()
            val pending = SuOverlayConnection.connectAsync(context) { conn ->
                mainHandler.post { handleSuResult(conn, gen) }
            }
            pendingSu = pending
            return pending
        }

        /** 解除绑定并销毁 root 端服务（双后端各自清理）。 */
        fun unbind() {
            generation.incrementAndGet() // 作废在途的 su 连接请求
            pendingSu = false
            suBackend?.let {
                suBackend = null
                it.shutdown()
            }
            runCatching {
                Shizuku.unbindUserService(args, connection, true)
            }
            api = null
            isActive = false
        }

        /**
         * root 端失败上报入口：作废当前后端并广播断连，调用方
         * （OverlayServiceManager）经 Listener 回落普通悬浮窗路线。
         */
        fun reportBackendFailed() {
            mainHandler.post {
                generation.incrementAndGet()
                pendingSu = false
                suBackend?.let {
                    suBackend = null
                    it.shutdown()
                }
                runCatching {
                    Shizuku.unbindUserService(args, connection, true)
                }
                api = null
                isActive = false
                notifyChanged()
            }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (suBackend != null) {
                    // 当前实际走 su 后端（Shizuku 晚到的回调）：忽略，防止覆盖
                    return
                }
                val proxy = ShizukuBinderProxy(binder)
                // 先注册回调（code 15），再置可用——保证 attach 前失败可上报
                runCatching {
                    val data = Parcel.obtain()
                    try {
                        data.writeStrongBinder(CallbackBinder())
                        binder.transact(15, data, null, IBinder.FLAG_ONEWAY)
                    } finally {
                        data.recycle()
                    }
                }
                api = proxy
                isActive = true
                notifyChanged()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (suBackend != null) return
                api = null
                isActive = false
                notifyChanged()
            }
        }

        private fun isShizukuRoot(): Boolean = runCatching {
            Shizuku.getBinder() != null &&
                    Auxiliary.isShellActivated &&
                    Shizuku.getUid() == 0
        }.getOrDefault(false)

        /**
         * su 后端结果（成功连接 / 失败 / 进程死亡）统一入口，主线程执行。
         * gen 不匹配说明该请求已被 unbind/rebind 作废：直接销毁连接。
         */
        private fun handleSuResult(conn: SuOverlayConnection?, gen: Int) {
            if (gen != generation.get()) {
                conn?.shutdown()
                return
            }
            pendingSu = false
            if (conn != null) {
                suBackend = conn
                api = conn.api
                isActive = true
                notifyChanged()
            } else {
                // 建立失败（无授权/超时）或进程死亡：走 su 后端失败通知。
                // su 是唯一在途后端（Shizuku 可用就不会走到 su），置空安全。
                suBackend = null
                api = null
                isActive = false
                notifyChanged()
            }
        }

        private fun notifyChanged() {
            val active = isActive
            listeners.forEach { listener ->
                runCatching { listener.onConnectionChanged(active) }
            }
        }

        // ---------- root -> app 回调内部派发（主线程化） ----------

        internal fun handleMediaSwitch(delta: Int) {
            mainHandler.post { mediaSwitchHandler?.onSwitchMedia(delta) }
        }

        internal fun handleWindowFailed(reason: String) {
            mainHandler.post { reportBackendFailed() }
        }
    }

    // ==================== root 进程侧：窗口宿主实现 ====================

    private class RootContextHolder {
        companion object
    }

    // ViewRootImpl 绑定该线程的 Looper：Choreographer、View 绘制、Surface 控制
    private val handlerThread = HandlerThread("RootOverlay").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var windowManager: WindowManager? = null
    private var floatingView: FrameLayout? = null
    private var cornerHandleView: CornerHandleView? = null
    private var params: WindowManager.LayoutParams? = null

    // 控制窗口：透明可触摸，与显示窗口同几何；
    // 手势全部在本进程内检测处理（见 attachControl）
    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    // 应用进程反向回调（binder：切换媒体 / 窗口失败上报）
    private var callback: IBinder? = null

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

    // ==================== 协议入口（binder 线程 / su 帧线程共用） ====================

    /**
     * 手写协议分发。返回 false 表示未知事务码。
     * binder 路径由 [onTransact] 进入（suFds = null，fd 自 Parcel 读取）；
     * su 路径由 SuLauncher 将帧 payload 反序列化为 Parcel 进入（fd 随帧
     * 经 SCM_RIGHTS 到达，自 suFds 读取）。
     */
    internal fun dispatch(code: Int, data: Parcel, suFds: Array<FileDescriptor>?): Boolean {
        when (code) {
            1 -> attach(data.readInt(), data.readInt(), data.readInt(), data.readInt())
            2 -> detach()
            3 -> setGeometry(data.readInt(), data.readInt(), data.readInt(), data.readInt())
            4 -> setAlpha(data.readFloat())
            5 -> showImage(readMediaFd(data, suFds))
            6 -> showVideo(readMediaFd(data, suFds))
            7 -> clearMedia()
            8 -> scaleImage(data.readFloat())
            9 -> panImage(data.readFloat(), data.readFloat())
            10 -> togglePlayPause()
            11 -> seekBy(data.readInt())
            12 -> setMuted(data.readBoolean())
            13 -> attachControl()
            14 -> detachControl()
            15 -> registerCallback(data.readStrongBinder())
            else -> return false
        }
        return true
    }

    /** su 路径的 fd 来自 SCM_RIGHTS（归本进程所有，dup 后关原副本）；binder 路径自 Parcel 读取。 */
    private fun readMediaFd(data: Parcel, suFds: Array<FileDescriptor>?): ParcelFileDescriptor? {
        if (suFds != null) {
            val fd = suFds.firstOrNull() ?: return null
            val pfd = ParcelFileDescriptor.dup(fd)
            runCatching { android.system.Os.close(fd) }
            return pfd
        }
        @Suppress("DEPRECATION")
        return data.readParcelable(ParcelFileDescriptor::class.java.classLoader)
    }

    /**
     * Shizuku 服务器销毁本服务（unbindUserService remove）时以保留事务码
     * USER_SERVICE_TRANSACTION_destroy(16777115) 直接 transact——该值超出
     * AIDL 编译器允许的上限，故本实现不使用 AIDL 而在 onTransact 手写，
     * 在此拦截并执行清理（移除窗口、释放线程），避免窗口泄漏。
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy) {
            handleDestroy()
            return true
        }
        val handled = runCatching { dispatch(code, data, null) }.getOrDefault(false)
        return handled || super.onTransact(code, data, reply, flags)
    }

    // Shizuku 销毁本服务（unbindUserService remove）时调用
    private fun handleDestroy() {
        handler.post {
            detachInternal()
            handler.removeCallbacksAndMessages(null)
            handlerThread.quitSafely()
        }
    }

    // ==================== 窗口生命周期 ====================

    fun attach(x: Int, y: Int, width: Int, height: Int) {
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
                // 反射失败绝不能静默吞掉：必须上报否则应用侧无从感知
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
            // uid=0 下签名权限校验直接通过，两个私有标志真实生效：
            // - TRUSTED_OVERLAY：穿透触摸不携带 FLAG_WINDOW_IS_OBSCURED
            // - SYSTEM_APPLICATION_OVERLAY：免疫 setHideOverlayWindows 屏蔽
            // trusted=false 说明反射写 privateFlags 失败——穿透触摸将携带
            // obscured 标志被下层应用感知，logcat 留痕以便排查（其他应用不可读）
            val trusted = applyStealthPrivateFlags(p)

            wm.addView(view, p)
            floatingView = view
            params = p
            windowManager = wm
            android.util.Log.i("RootOverlay", "display attached trusted=$trusted")
            scheduleScreenshotExclusion(view, 0)
            scheduleTrustedDiagnostics()
        } catch (t: Throwable) {
            // 绝不静默：上报应用进程回落本地窗口（否则悬浮窗"无任何显示"）
            android.util.Log.e("RootOverlay", "display attach FAILED", t)
            notifyWindowFailed("attach", t)
            cleanupWindow()
        }
    }

    /**
     * 诊断：attach 后 3 秒（两窗口均已挂载）自动在 root 进程内执行
     * dumpsys input，抓取本应用两个窗口在 InputDispatcher 侧的真实状态，
     * 全部打到 logcat（tag=RootOverlay，diag 前缀），无需 adb 手动排查：
     * - Uid：本进程 uid——非 0 则 WMS 签名权限判定失败，TRUSTED_OVERLAY
     *   不生效（FLAG_WINDOW_IS_OBSCURED 重新出现的根本原因）；
     * - inputConfig 含 TRUSTED_OVERLAY：WMS 已认可本窗口为可信覆盖层；
     *   缺失则说明权限判定失败（常量位写入成功也无效）；
     * - ownerUid：InputDispatcher 记录的窗口属主，须与进程 uid 一致。
     */
    private fun scheduleTrustedDiagnostics() {
        handler.postDelayed({
            runCatching { runTrustedDiagnostics() }
        }, 3000)
    }

    private fun runTrustedDiagnostics() {
        val uidLine = runCatching {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Uid:") }?.trim()
        }.getOrNull()
        android.util.Log.i("RootOverlay", "diag proc $uidLine")

        val dump = runCatching {
            val pb = ProcessBuilder("dumpsys", "input")
            pb.environment()["PATH"] = "/system/bin:/vendor/bin:/system/xbin"
            val proc = pb.start()
            proc.inputStream.bufferedReader().readText().also { proc.waitFor() }
        }.getOrNull()
        if (dump == null) {
            android.util.Log.i("RootOverlay", "diag dumpsys input failed")
            return
        }
        // dumpsys input 每个窗口两行：name=... 行 + inputConfig=... 行
        val lines = dump.lines()
        for (i in lines.indices) {
            if (lines[i].contains("fake.screenshot")) {
                val cfg = lines.getOrNull(i + 1)?.trim() ?: ""
                android.util.Log.i(
                    "RootOverlay",
                    "diag win ${lines[i].trim().take(160)} | $cfg"
                )
            }
        }
        val hasTrusted = lines.any { it.contains("TRUSTED_OVERLAY") }
        android.util.Log.i("RootOverlay", "diag summary anyTrustedOverlay=$hasTrusted")
    }

    /**
     * 截图/录屏排除：FLAG_SECURE 使本窗口内容不出现在系统截屏与
     * MediaProjection 捕获中；再尽力补 SurfaceControl.setSkipScreenshot(true)
     * （隐藏 API，root 进程内无限制）使窗口被完全跳过而非黑块。
     * SurfaceControl 在首次遍历后才 valid，故带重试。
     */
    private fun scheduleScreenshotExclusion(view: View, attempt: Int) {
        handler.postDelayed({
            if (floatingView !== view) return@postDelayed
            if (view.enableScreenshotExclusion() || attempt >= 5) return@postDelayed
            scheduleScreenshotExclusion(view, attempt + 1)
        }, 200)
    }

    /** 窗口挂载失败上报（handler 线程内调用；须在 cleanupWindow 清空调用前）。 */
    private fun notifyWindowFailed(where: String, t: Throwable) {
        invokeCallback(101) {
            it.writeString("$where: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun notifySwitchMedia(delta: Int) {
        invokeCallback(100) { it.writeInt(delta) }
    }

    /** root -> app 回调统一出口：Shizuku 路径为 binder transact；su 路径为帧发送代理。 */
    private fun invokeCallback(code: Int, write: (Parcel) -> Unit) {
        val cb = callback ?: return
        val p = Parcel.obtain()
        try {
            write(p)
            cb.transact(code, p, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
        } finally {
            p.recycle()
        }
    }

    fun detach() {
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

    // ==================== 几何 / 外观 ====================

    fun setGeometry(x: Int, y: Int, width: Int, height: Int) {
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

    fun setAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        handler.post {
            floatingView?.alpha = clamped
        }
    }

    // ==================== 控制窗口（root 托管手势） ====================

    /** 控制窗口几何复用显示窗口当前值（应用进程不再单独维护几何）。 */
    fun attachControl() {
        handler.post {
            val p = params ?: run {
                notifyWindowFailed(
                    "attachControl",
                    IllegalStateException("display window not attached")
                )
                return@post
            }
            attachControlInternal(p.x, p.y, p.width, p.height)
        }
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
            // 同显示窗口：uid=0 下两个私有标志真实生效。
            // 控制窗口可触摸、必然遮挡下层应用的触摸事件，正是
            // FLAG_WINDOW_IS_OBSCURED 的来源——root 托管后 InputDispatcher
            // 跳过 trusted overlay 的遮挡标记，下层应用无法感知本窗口存在
            val trusted = applyStealthPrivateFlags(p)

            wm.addView(view, p)
            controlView = view
            controlParams = p
            windowManager = wm
            android.util.Log.i("RootOverlay", "control attached trusted=$trusted")
        } catch (t: Throwable) {
            // 控制窗口失败同样上报（显示窗口虽在但手势全失效，整体回落本地）
            android.util.Log.e("RootOverlay", "control attach FAILED", t)
            notifyWindowFailed("attachControl", t)
            removeControlWindowInternal()
        }
    }

    fun detachControl() {
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

    fun registerCallback(cb: IBinder?) {
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
        val now = SystemClock.elapsedRealtime()
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
                    notifySwitchMedia(delta)
                } else {
                    togglePlayPause()
                }
            } else {
                // 图片：左半区上一张，右半区下一张
                val halfWidth = view.width / 2f
                val delta = if (e.x < halfWidth) -1 else 1
                notifySwitchMedia(delta)
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

    fun showImage(fd: ParcelFileDescriptor?) {
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

    fun showVideo(fd: ParcelFileDescriptor?) {
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

    fun clearMedia() {
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

    fun scaleImage(factor: Float) {
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

    fun panImage(dx: Float, dy: Float) {
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

    fun togglePlayPause() {
        handler.post {
            val mp = mediaPlayer ?: return@post
            runCatching {
                if (mp.isPlaying) mp.pause() else mp.start()
            }
        }
    }

    fun seekBy(deltaMs: Int) {
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

    fun setMuted(muted: Boolean) {
        handler.post {
            isMuted = muted
            applyMuteState()
        }
    }

    // ==================== root 进程基础设施 ====================

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
     * 在窗口 LayoutParams 上写入两个隐藏私有标志：
     * - PRIVATE_FLAG_TRUSTED_OVERLAY：消除穿透触摸的 FLAG_WINDOW_IS_OBSCURED /
     *   PARTIALLY_OBSCURED。常量值跨版本有变化（AOSP 实测）：
     *   API ≤33 为 1 shl 28，API ≥34 为 1 shl 29；
     * - PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY（API ≥31 才存在，恒为 1 shl 3）：
     *   免疫下层应用 setHideOverlayWindows 屏蔽。
     *
     * 反射读取常量失败时按 SDK_INT 分版本回退，保证任何环境下都写入正确的位
     * （写错位更危险：如 API ≥34 上 1 shl 28 是 FIT_INSETS_CONTROLLED）。
     *
     * 仅在 root（uid=0）进程中调用真实生效：WMS 在
     * WindowState.isWindowTrustedOverlay() 校验 TRUSTED_OVERLAY 位 +
     * Session 权限（INTERNAL_SYSTEM_WINDOW），uid=0 的 binder 调用直接 GRANTED。
     * 返回是否成功写入 TRUSTED_OVERLAY（含回读校验）。
     */
    @Suppress("DiscouragedPrivateApi")
    private fun applyStealthPrivateFlags(params: WindowManager.LayoutParams): Boolean {
        return try {
            val field = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
            field.isAccessible = true
            val trustedFallback = if (Build.VERSION.SDK_INT >= 34) 1 shl 29 else 1 shl 28
            val systemAppFallback = if (Build.VERSION.SDK_INT >= 31) 1 shl 3 else null
            val trusted = hiddenStaticFlag("PRIVATE_FLAG_TRUSTED_OVERLAY", trustedFallback)
            val systemApp = hiddenStaticFlag("PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY", systemAppFallback)
            var flags = field.get(params) as Int
            if (trusted != null) {
                flags = flags or trusted
            }
            if (systemApp != null) {
                flags = flags or systemApp
            }
            field.set(params, flags)
            // 回读校验：确认位真实落盘（反射 set 异常不会走到这里，防止静默失败）
            val verify = (field.get(params) as Int)
            val ok = trusted != null && (verify and trusted) == trusted
            android.util.Log.i(
                "RootOverlay",
                "stealth flags sdk=${Build.VERSION.SDK_INT} trusted=0x${trusted?.toString(16)} " +
                        "systemApp=0x${systemApp?.toString(16)} result=0x${verify.toString(16)} ok=$ok"
            )
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /** 反射读 WindowManager.LayoutParams 隐藏静态常量；读不到返回 fallback。 */
    @Suppress("DiscouragedPrivateApi")
    private fun hiddenStaticFlag(name: String, fallback: Int?): Int? {
        return runCatching {
            val f = WindowManager.LayoutParams::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.getInt(null)
        }.getOrDefault(fallback)
    }

    // ==================== 四角缩放手柄（内嵌实现，不建独立 View 文件） ====================

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

// ==================== 应用进程侧后端代理与协议辅助（仅本文件使用） ====================

/** 应用进程侧对 root 端的指令接口（binder / su 双后端各自实现）。 */
internal interface Api {
    fun attach(x: Int, y: Int, width: Int, height: Int)
    fun detach()
    fun setAlpha(alpha: Float)
    fun showImage(fd: ParcelFileDescriptor?)
    fun showVideo(fd: ParcelFileDescriptor?)
    fun clearMedia()
    fun setMuted(muted: Boolean)
    fun attachControl()
    fun detachControl()
}

/**
 * Shizuku 后端代理：手写 binder 事务（FLAG_ONEWAY 单向不阻塞），
 * payload 为 Parcel 编组字节，与 root 端 RootOverlayService.dispatch 对应。
 */
private class ShizukuBinderProxy(private val binder: IBinder) : Api {

    private fun call(code: Int, write: (Parcel) -> Unit = {}) {
        val data = Parcel.obtain()
        try {
            write(data)
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
        } finally {
            data.recycle()
        }
    }

    override fun attach(x: Int, y: Int, width: Int, height: Int) =
        call(1) {
            it.writeInt(x)
            it.writeInt(y)
            it.writeInt(width)
            it.writeInt(height)
        }

    override fun detach() = call(2)

    override fun setAlpha(alpha: Float) = call(4) { it.writeFloat(alpha) }

    override fun showImage(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        call(5) { it.writeParcelable(fd, 0) }
        // binder 事务发出时内核已 dup，本地副本即可关闭
        runCatching { fd.close() }
    }

    override fun showVideo(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        call(6) { it.writeParcelable(fd, 0) }
        runCatching { fd.close() }
    }

    override fun clearMedia() = call(7)

    override fun setMuted(muted: Boolean) = call(12) { it.writeBoolean(muted) }

    override fun attachControl() = call(13)

    override fun detachControl() = call(14)
}

/**
 * 应用进程侧回调接收器（Shizuku 路径）：root 端经 binder 反向 transact
 * 100/101 进入；su 路径不用本类（帧经 socket 到达后由读线程直接派发）。
 */
private class CallbackBinder : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            100 -> {
                RootOverlayService.handleMediaSwitch(data.readInt())
                return true
            }

            101 -> {
                RootOverlayService.handleWindowFailed(data.readString() ?: "unknown")
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}

/** su 直连模式的帧协议常量。帧格式（两个方向对称）：[int32 code][int32 payloadLen][payload] */
private object SuProto {
    /** root -> app：onSwitchMedia(int delta) */
    const val CODE_ON_SWITCH_MEDIA = 100

    /** root -> app：onWindowFailed(String reason) */
    const val CODE_ON_WINDOW_FAILED = 101

    /** app -> root：销毁 root 端服务并退出进程 */
    const val CODE_DESTROY = 999
}

/**
 * su 直连模式下应用进程 <-> root（`su -c app_process`）进程的帧传输层。
 *
 * - 应用端 [connect]、root 端由 SuLauncher 用 LocalServerSocket.accept 得到
 *   LocalSocket 后经 [accepted] 包装，两端共用同一套读写实现。
 * - 双工单 socket：任意线程发送（写锁互斥），专用读线程接收。
 * - 媒体 fd：发送端在帧字节的一次 write 前调 setFileDescriptorsForSend
 *   挂上 fd（内核在 SCM_RIGHTS 传递时自动 dup，与 binder 语义一致，
 *   调用方继续拥有并关闭自己的副本）；接收端在逐段 read 后经
 *   getAncillaryFileDescriptors 收集，归入当前完成的帧。
 * - 任一方向 EOF / 协议错误：readFrame 返回 null，调用方据此判定对端死亡。
 */
internal class SuTransport private constructor(private val socket: LocalSocket) {

    class Frame(val code: Int, val payload: ByteArray, val fds: List<FileDescriptor>)

    private val writeLock = Any()

    fun sendFrame(code: Int, payload: ByteArray, fds: Array<FileDescriptor>?) {
        val frame = ByteArrayOutputStream(8 + payload.size).let { bos ->
            val dos = DataOutputStream(bos)
            dos.writeInt(code)
            dos.writeInt(payload.size)
            dos.write(payload)
            bos.toByteArray()
        }
        synchronized(writeLock) {
            val hasFds = !fds.isNullOrEmpty()
            if (hasFds) {
                socket.setFileDescriptorsForSend(fds)
            }
            try {
                val out = socket.outputStream
                out.write(frame)
                out.flush()
            } finally {
                if (hasFds) {
                    // 清除发送队列，避免后续普通帧误带 ancillary
                    socket.setFileDescriptorsForSend(null)
                }
            }
        }
    }

    /** payload 为 Parcel 编组字节（与 binder 路径共用同一编组格式）。 */
    fun sendParcelFrame(
        code: Int,
        fds: Array<FileDescriptor>? = null,
        write: (Parcel) -> Unit = {}
    ) {
        val p = Parcel.obtain()
        val payload = try {
            write(p)
            p.marshall()
        } finally {
            p.recycle()
        }
        sendFrame(code, payload, fds)
    }

    /**
     * 读一帧；对端关闭或协议异常（负长度/超大长度/参数截断）返回 null。
     * 无缓冲：逐段 read 保证 ancillary fd 与其所属帧的对应关系。
     */
    fun readFrame(): Frame? {
        var pendingFds: Array<FileDescriptor>? = null

        fun readN(n: Int): ByteArray? {
            if (n <= 0) return ByteArray(0)
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = socket.inputStream.read(buf, off, n - off)
                if (r < 0) return null
                off += r
                // fd 伴随包含其帧字节的某一次底层 read 到达；取最近一次结果
                socket.ancillaryFileDescriptors?.let { pendingFds = it }
            }
            return buf
        }

        val header = readN(8) ?: return null
        val dis = DataInputStream(ByteArrayInputStream(header))
        val code = dis.readInt()
        val len = dis.readInt()
        if (len < 0 || len > MAX_FRAME) return null
        val payload = readN(len) ?: return null
        return Frame(code, payload, pendingFds?.toList() ?: emptyList())
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val MAX_FRAME = 1 shl 20

        /** 应用端连接 root 端监听的抽象命名空间 socket；失败返回 null。 */
        fun connect(name: String, timeoutMs: Int): SuTransport? {
            return runCatching {
                val s = LocalSocket()
                s.connect(
                    LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT),
                    timeoutMs
                )
                SuTransport(s)
            }.getOrNull()
        }

        /** root 端包装 accept() 得到的已连接 socket。 */
        fun accepted(socket: LocalSocket): SuTransport = SuTransport(socket)
    }
}

private fun SuTransport.Frame.readIntFromParcel(): Int? = runCatching {
    val p = Parcel.obtain()
    try {
        p.unmarshall(payload, 0, payload.size)
        p.setDataPosition(0)
        p.readInt()
    } finally {
        p.recycle()
    }
}.getOrNull()

private fun SuTransport.Frame.readStringFromParcel(): String? = runCatching {
    val p = Parcel.obtain()
    try {
        p.unmarshall(payload, 0, payload.size)
        p.setDataPosition(0)
        p.readString()
    } finally {
        p.recycle()
    }
}.getOrNull()

/**
 * 应用进程侧的 su 直连后端：经 root 管理器（Magisk/KernelSU 等）的 su
 * 直接拉起 root 宿主进程（SuLauncher），不依赖 Shizuku/Sui 存在。
 *
 * 建连流程（异步，上限 8 秒覆盖 root 管理器授权弹窗）：
 * 1. 生成不可猜测的抽象 socket 名，`su -c "CLASSPATH=<apk> app_process …"`
 *    启动 SuLauncher（stdout/stderr 重定向 /dev/null，无任何输出特征）；
 * 2. 轮询连接该 socket；
 * 3. 成功后 proxy（Api 的 socket 代理）交给 RootOverlayService companion
 *    对外暴露；读线程循环接收 root->app 帧（100/101）。
 *
 * 生命周期：连接成功/死亡/失败均经 onResult 回调；shutdown() 主动关闭
 * 不触发死亡回调（区别于意外断连：后者必须通知上层回落本地窗口）。
 */
internal class SuOverlayConnection private constructor(
    private val proxy: SuOverlayProxy,
    private val transport: SuTransport,
    private val process: Process,
    private val onDead: () -> Unit
) {
    val api: Api get() = proxy

    @Volatile
    private var closed = false

    @Volatile
    private var started = false

    private val reader = Thread {
        while (!closed) {
            val frame = runCatching { transport.readFrame() }.getOrNull() ?: break
            when (frame.code) {
                SuProto.CODE_ON_SWITCH_MEDIA ->
                    frame.readIntFromParcel()?.let { RootOverlayService.handleMediaSwitch(it) }
                SuProto.CODE_ON_WINDOW_FAILED ->
                    RootOverlayService.handleWindowFailed(
                        frame.readStringFromParcel() ?: "unknown"
                    )
                else -> {
                    // 未知帧忽略（向前兼容）
                }
            }
        }
        // root 进程死亡 / su 被杀：与 Shizuku 服务死亡同样处理
        if (started && !closed) onDead()
    }

    fun start() {
        started = true
        reader.apply { name = "SuOverlayReader" }.start()
    }

    /** 主动断开（应用侧销毁悬浮窗）：通知 root 端退出并清理，不触发死亡回调。 */
    fun shutdown() {
        closed = true
        runCatching { transport.sendFrame(SuProto.CODE_DESTROY, ByteArray(0), null) }
        runCatching { transport.close() }
        runCatching { process.destroy() }
        // reader 因 EOF 退出；closed 已置位故不再回调 onDead
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val CONNECT_POLL_MS = 100L
        private const val PROBE_MS = 300L

        /**
         * 异步建立 su 直连。返回 false 表示立即判定不可行（无 su 二进制/
         * su 进程被立即拒绝），此时调用方应同步走普通路线。
         */
        fun connectAsync(
            context: Context,
            onResult: (SuOverlayConnection?) -> Unit
        ): Boolean {
            if (!Auxiliary.hasSuBinary()) return false
            val name = "sf.r.${Auxiliary.getRandomString(24)}"
            val apkPath = context.applicationInfo.sourceDir

            Thread {
                val proc = startRootProcess(apkPath, name)
                if (proc == null) {
                    onResult(null)
                    return@Thread
                }
                val transport = awaitTransport(name, proc)
                if (transport == null) {
                    runCatching { proc.destroy() }
                    onResult(null)
                    return@Thread
                }
                val proxy = SuOverlayProxy(transport)
                val conn = SuOverlayConnection(proxy, transport, proc) { onResult(null) }
                conn.start()
                onResult(conn)
            }.apply {
                isDaemon = true
                this.name = "SuOverlayConnect"
            }.start()
            return true
        }

        /**
         * 启动 root 宿主进程。先试 PATH 中的 su，再试常见绝对路径；
         * 短暂探测存活以过滤"无授权被直接拒绝"（拒绝时 su 立即退出；
         * 授权弹窗期间进程存活，继续等待）。全失败返回 null。
         */
        private fun startRootProcess(apkPath: String, socketName: String): Process? {
            val cmd = "CLASSPATH='$apkPath' exec /system/bin/app_process / " +
                    "${SuLauncher::class.java.name} $socketName"
            val candidates = listOf("su") + Auxiliary.suBinaryPaths
            for (bin in candidates) {
                val proc = runCatching {
                    ProcessBuilder(bin, "-c", cmd)
                        // 静默运行：无输出、不写任何可检测痕迹
                        .redirectOutput(File("/dev/null"))
                        .redirectError(File("/dev/null"))
                        .start()
                }.getOrNull() ?: continue
                runCatching { Thread.sleep(PROBE_MS) }
                if (proc.isAlive) return proc
                runCatching { proc.destroy() }
            }
            return null
        }

        private fun awaitTransport(name: String, proc: Process): SuTransport? {
            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                SuTransport.connect(name, 1500)?.let { return it }
                // su client 随宿主存活；退出即失败（拒绝/崩溃）
                if (!proc.isAlive) return null
                runCatching { Thread.sleep(CONNECT_POLL_MS) }
            }
            return null
        }
    }
}

/**
 * Api 的 socket 代理（应用进程侧，su 路径）。
 *
 * payload 为 Parcel 编组字节（与 binder 路径共用同一编组格式）；
 * showImage/showVideo 的 fd 经 SCM_RIGHTS 传递（内核 dup，与 binder
 * 语义一致——调用方继续拥有并负责关闭自己的副本）。
 */
private class SuOverlayProxy(private val transport: SuTransport) : Api {

    override fun attach(x: Int, y: Int, width: Int, height: Int) =
        transport.sendParcelFrame(1) {
            it.writeInt(x)
            it.writeInt(y)
            it.writeInt(width)
            it.writeInt(height)
        }

    override fun detach() = transport.sendParcelFrame(2)

    override fun setAlpha(alpha: Float) = transport.sendParcelFrame(4) { it.writeFloat(alpha) }

    override fun showImage(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        transport.sendParcelFrame(5, arrayOf(fd.fileDescriptor))
        runCatching { fd.close() }
    }

    override fun showVideo(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        transport.sendParcelFrame(6, arrayOf(fd.fileDescriptor))
        runCatching { fd.close() }
    }

    override fun clearMedia() = transport.sendParcelFrame(7)

    override fun setMuted(muted: Boolean) =
        transport.sendParcelFrame(12) { it.writeBoolean(muted) }

    override fun attachControl() = transport.sendParcelFrame(13)

    override fun detachControl() = transport.sendParcelFrame(14)
}

// ==================== su 直连 root 宿主进程入口 ====================

/**
 * su 直连模式的 root 宿主进程入口（内联于本文件：su 命令行以本类名为
 * app_process 入口，与 RootOverlayService 同属 root 服务实现，并非独立服务）。
 *
 * 由应用进程执行：
 *   su -c "CLASSPATH='<apk路径>' exec /system/bin/app_process / \
 *          fake.screenshot.services.privileged.SuLauncher <socket名>"
 *
 * 以 CLASSPATH=本应用 APK 启动的 app_process 会用 PathClassLoader 加载
 * 全部应用类（与 Shizuku UserService 从同一 APK 反射加载完全同源），
 * 因此 RootOverlayService 无需任何改动即可在本进程实例化：它自建
 * HandlerThread（ViewRootImpl 的 Looper 要求）、反射 ActivityThread.
 * systemMain() 获取 system context，addView 时以 uid=0 通过
 * INTERNAL_SYSTEM_WINDOW 校验使 TRUSTED_OVERLAY 真实生效。
 *
 * 进程生命周期：
 * - 启动后在抽象命名空间监听（名字由应用进程随机生成传入，不可猜测）；
 * - 看门狗：15 秒内未等到连接则退出（应用进程侧 8 秒已放弃，防止孤儿
 *   root 进程常驻）；
 * - 连接后进入帧派发循环：payload 反序列化为 Parcel 后直接进入
 *   RootOverlayService.dispatch（其内部一律 post 到窗口线程，线程安全）；
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
        val service = RootOverlayService()

        val transport = SuTransport.accepted(socket)

        // 反向回调（root -> app）：手势判定"切换媒体"/窗口挂载失败时经
        // socket 帧通知。binder 对象无法跨 socket 序列化，故以发送代理
        // 对象就地注入（与 Shizuku 路径的 registerCallback binder 等效）。
        service.registerCallback(SuCallbackSender(transport))

        // 帧派发循环：本线程即"协议线程"，等价于 Shizuku 路径的 binder 线程
        while (true) {
            val frame = runCatching { transport.readFrame() }.getOrNull() ?: break
            if (frame.code == SuProto.CODE_DESTROY) break
            val ok = runCatching { dispatchFrame(service, frame) }.getOrDefault(false)
            if (!ok) break
        }

        // 进程退出即清理：WMS 摘除窗口、MediaPlayer 释放（进程级资源回收）
        runCatching { transport.close() }
        Runtime.getRuntime().exit(0)
    }

    /** payload 反序列化为 Parcel 后共用 RootOverlayService.dispatch；false 表示协议错误。 */
    private fun dispatchFrame(service: RootOverlayService, frame: SuTransport.Frame): Boolean {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(frame.payload, 0, frame.payload.size)
            parcel.setDataPosition(0)
            return service.dispatch(
                frame.code,
                parcel,
                frame.fds.takeIf { it.isNotEmpty() }?.toTypedArray()
            )
        } finally {
            parcel.recycle()
        }
    }
}

/**
 * su 路径的 root->app 回调发送代理：将 root 端 binder 风格的
 * invokeCallback（transact 100/101）转译为 socket 帧。
 */
private class SuCallbackSender(private val transport: SuTransport) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            100 -> {
                val delta = data.readInt()
                transport.sendParcelFrame(100) { it.writeInt(delta) }
                return true
            }

            101 -> {
                val reason = data.readString() ?: "unknown"
                transport.sendParcelFrame(101) { it.writeString(reason) }
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}
