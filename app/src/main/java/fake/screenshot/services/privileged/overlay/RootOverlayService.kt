package fake.screenshot.services.privileged.overlay

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import fake.screenshot.Auxiliary
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ROOT 权限下的无痕悬浮窗（单文件实现：服务类即接口）。
 *
 * ==================== 架构总览 ====================
 *
 * 本文件是服务类文件，同时承载两半（分别运行于不同进程）：
 *
 * - 【root 进程侧】[RootOverlayService] 本体（继承 Binder，手写 onTransact
 *   协议，不依赖 AIDL）：实现委托至本包（services.privileged.overlay）
 *   其余模块——纯 Surface 双层渲染 + InputMonitor 输入通道，完全不经
 *   WindowManager / ViewRootImpl / View 体系；
 * - 【应用进程侧】[RootOverlayService] 的 companion object 即对外接口：
 *   bind/unbind 管理双后端（Shizuku UserService 优先 / su app_process 兜底），
 *   并暴露 attach/detach/showImage 等指令方法与 Listener 通知。
 *
 * ==================== 为什么是"纯 Surface 方案" ====================
 *
 * 前代方案（WindowManager + 反射私有标志）在 Android 15 上因 WMS session
 * 加固失败：uid=0 但未经 AMS 注册的进程调用 openSession 抛
 * "Unknown pid"（Session 构造要求进程在 AMS 有记录）。
 *
 * 现方案完全绕开 WMS：
 * - 渲染：SurfaceControl 直挂 SurfaceFlinger（root layer + 内容层 +
 *   手柄层，随机命名，setSkipScreenshot 截图排除）——见
 *   [OverlaySurfaceBackend]；
 * - 输入：IInputManager.monitorGestureInput（uid=0 过 MONITOR_INPUT）建立
 *   手势监视通道，自解析 InputMessage 二进制协议（Android 11-16 四代
 *   布局）并即时回发 FINISHED ACK；命中悬浮窗时 pilferPointers 抢占
 *   指针流——见 [GestureInputMonitor] / InputMessageCodec；
 * - 手势：与普通悬浮窗完全一致的状态机（移动/四角缩放/图片平移缩放/
 *   长按 seek/双击分区/视频单击透传注入）——见 [OverlayGestureController]。
 *
 * ==================== 检测面对照 ====================
 *
 * | 第三方检测手段                          | 对策                        |
 * |----------------------------------------|-----------------------------|
 * | 触摸事件 FLAG_WINDOW_IS_OBSCURED(_PART) | spy monitor 不计遮挡判定    |
 * | setFilterTouchesWhenObscured 拦截       | 同上（标记不产生）          |
 * | Android 12+ block_untrusted_touches    | 不拦截 monitor 副本派发     |
 * | setHideOverlayWindows() 隐藏            | 无窗口可隐藏                |
 * | 截图/录屏中出现悬浮窗                    | setSkipScreenshot（每层）   |
 * | 无障碍/窗口列表枚举窗口                  | 无窗口（纯 layer + monitor）|
 * | SYSTEM_ALERT_WINDOW AppOps 追溯         | 不使用 overlay 通道         |
 * | WMS session 加固（Android 15）           | 不经 WMS                    |
 * | 输入 ANR / 派发超时日志                  | 即时 FINISHED ACK           |
 * | monitor/layer/线程名                     | 全部随机字符串              |
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
 *   102 onDebug(msg)——root 进程零日志，诊断信息经回调送达 app 进程，
 *       由 app 进程决定落 logcat（见 handleDebug）
 *
 *   su 路径回调 binder 对象无法跨 socket 序列化，root 端启动时就地注入
 *   帧发送代理；999 为 su 路径 destroy 帧。
 *
 * ==================== 进程环境说明（Shizuku UserService v5）====================
 *
 * - 本服务由 Shizuku 服务端用 DexClassLoader 从本应用 APK 反射实例化，
 *   运行在 fork 自 Shizuku server 的 root 进程中，无 Android 应用组件环境；
 *   su 路径由 [SuLauncher] 以 CLASSPATH=本 APK 的 app_process 加载，同源。
 * - 全部操作集中在专用 HandlerThread（SuLauncher 的协议线程 / binder 线程
 *   一律转发到该线程执行）。
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

        /**
         * app 进程侧诊断日志 tag（= 本文件开头的 package 声明，编译期
         * 常量）。root 进程保持零日志（无痕承诺），所有 root 侧诊断经
         * 回调（code 101/102）或建连 diag 送达 app 进程后才落 logcat——
         * logcat 记录归属于 app 进程自身，与普通应用行为无异。
         */
        internal const val LOG_TAG = "fake.screenshot.services.privileged.overlay"

        /**
         * 最近一次 root 路线失败原因（app 进程侧记录）：
         * - su 建连各环节失败（spawn/握手/连接/认证/就绪 + stderr 摘录）；
         * - root 端窗口挂载失败（code 101 透传的原因）；
         * - Shizuku 服务断连。
         * 供 OverlayServiceManager 降级 Toast / 排查使用；成功连接时清空。
         */
        @Volatile
        var lastFailureReason: String? = null
            private set

        private val args by lazy {
            Shizuku.UserServiceArgs(
                ComponentName(APPLICATION_ID, RootOverlayService::class.java.name)
            )
                // 进程名后缀是对外可见标识：SecureRandom（可预测的后缀可被
                // 用于跨会话关联同一应用）
                .processNameSuffix(Auxiliary.getSecureRandomString(Auxiliary.getSecureRandomInt(6..14)))
                .version(23)
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
                    android.util.Log.i(LOG_TAG, "bind: shizuku-root backend")
                    Shizuku.bindUserService(args, connection)
                    return true
                } catch (t: Throwable) {
                    // binder 竞态/未授权/版本不支持：落到 su 兜底
                    android.util.Log.w(LOG_TAG, "bind: shizuku failed -> su fallback", t)
                }
            }

            // 后端 2：root 管理器直接授权（su），无需 Shizuku/Sui 存在
            if (suBackend != null) return true
            val gen = generation.incrementAndGet()
            android.util.Log.i(LOG_TAG, "bind: su direct backend")
            val pending = SuOverlayConnection.connectAsync(context) { conn, reason ->
                mainHandler.post { handleSuResult(conn, gen, reason) }
            }
            pendingSu = pending
            if (!pending) {
                lastFailureReason = "su: 无可用 su 二进制（常见路径均不存在）"
                android.util.Log.w(LOG_TAG, "bind: $lastFailureReason")
            }
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
                lastFailureReason = null
                android.util.Log.i(LOG_TAG, "shizuku backend connected")
                notifyChanged()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (suBackend != null) return
                api = null
                isActive = false
                lastFailureReason = "shizuku: 服务断连"
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
         * reason 仅在失败时非空：su 建连各环节的失败诊断（见
         * [SuOverlayConnection.connectAsync]），记录后由降级路径透出。
         */
        private fun handleSuResult(conn: SuOverlayConnection?, gen: Int, reason: String?) {
            if (gen != generation.get()) {
                conn?.shutdown()
                return
            }
            pendingSu = false
            if (conn != null) {
                suBackend = conn
                api = conn.api
                isActive = true
                lastFailureReason = null
                android.util.Log.i(LOG_TAG, "su backend connected (peer verified)")
                notifyChanged()
            } else {
                // 建立失败（无授权/超时/认证失败）或进程死亡：走 su 后端失败通知。
                // su 是唯一在途后端（Shizuku 可用就不会走到 su），置空安全。
                lastFailureReason = "su: ${reason?.ifBlank { "unknown" } ?: "unknown"}"
                android.util.Log.w(LOG_TAG, "su backend failed: $lastFailureReason")
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

        /** root 端诊断（code 102）：app 进程侧落 logcat（root 进程零日志）。 */
        internal fun handleDebug(msg: String) {
            mainHandler.post { android.util.Log.d(LOG_TAG, "root: $msg") }
        }

        /** root 端窗口失败（code 101）：记录原因并触发回落。 */
        internal fun handleWindowFailed(reason: String) {
            mainHandler.post {
                lastFailureReason = "window: $reason"
                android.util.Log.w(LOG_TAG, "root window failed: $reason")
                reportBackendFailed()
            }
        }
    }

    // ==================== root 进程侧：纯 Surface 悬浮窗宿主 ====================

    private val handlerThread = HandlerThread(OverlayHiddenApi.randomName()).apply { start() }
    private val handler = Handler(handlerThread.looper)

    // 渲染后端（handler 线程独占；节流重绘 postDelayed 同线程无并发）。
    // 诊断经 code 102 回调送达 app 进程（root 进程自身零日志）
    private val backend = OverlaySurfaceBackend(
        handler,
        onFatal = { t -> notifyWindowFailed("backend", t) },
        onDebug = { msg -> notifyDebug(msg) }
    )

    // 输入监视与手势状态机（attachControl 后可用）
    private var monitor: GestureInputMonitor? = null
    private var controller: OverlayGestureController? = null

    // 应用进程反向回调（binder：切换媒体 / 窗口失败上报）
    private var callback: IBinder? = null

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
     * 在此拦截并执行清理（销毁 layer / monitor，避免资源泄漏）。
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

    // ==================== 生命周期 ====================

    fun attach(x: Int, y: Int, width: Int, height: Int) {
        handler.post { attachInternal(x, y, width, height) }
    }

    private fun attachInternal(x: Int, y: Int, width: Int, height: Int) {
        // 记录最近一次几何：attachControl 时 controller 以此为初值
        // （原实现只声明从未赋值，恒 (0,0,0,0) → 命中判断恒 false →
        // 所有触摸事件被丢弃，"点击无响应"的直接根因）
        lastX = x
        lastY = y
        lastW = width
        lastH = height
        if (backend.isAttached) {
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

            // 屏幕尺寸：窗口移动/缩放的 clamp 边界。
            // maximumWindowMetrics 在 root 进程（无 display context）可能
            // 抛异常且被静默吞掉 → screenWidth=0 → updateOverlay 早退 →
            // 移动/缩放全部失效（图片平移不经 clamp 故正常，正是该症状）。
            // 多路径解析。
            resolveScreenSize(context)

            controller?.syncGeometry(x, y, width, height)
            backend.attach(x, y, width, height)
        } catch (t: Throwable) {
            // 失败必须上报应用进程回落本地窗口（否则悬浮窗"无任何显示"）；
            // 无日志：原因经回调链传达
            notifyWindowFailed("attach", t)
            detachInternal()
        }
    }

    fun detach() {
        handler.post { detachInternal() }
    }

    private fun detachInternal() {
        removeControlInternal()
        backend.detach()
        callback = null
    }

    // ==================== 几何 / 外观 ====================

    private var screenWidth = 0
    private var screenHeight = 0

    fun setGeometry(x: Int, y: Int, width: Int, height: Int) {
        handler.post { setGeometryInternal(x, y, width, height) }
    }

    private fun setGeometryInternal(x: Int, y: Int, width: Int, height: Int) {
        if (!backend.isAttached) return
        lastX = x
        lastY = y
        lastW = width
        lastH = height
        controller?.syncGeometry(x, y, width, height)
        backend.setGeometry(x, y, width, height)
    }

    fun setAlpha(alpha: Float) {
        handler.post { backend.setAlpha(alpha) }
    }

    /**
     * 屏幕尺寸多路径解析（顺序：成功即停，静默失败轮询下一路径）：
     * 1. DisplayManager + Display.getRealMetrics（不依赖 display context）
     * 2. WindowManager.maximumWindowMetrics（应用进程路径）
     * 3. resources.displayMetrics（兜底，可能不含导航栏）
     */
    private fun resolveScreenSize(context: Context) {
        // 路径 1：DisplayManager（root 进程最可靠）
        runCatching {
            val dm = context.getSystemService("display")
                    as android.hardware.display.DisplayManager
            val disp = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val m = android.util.DisplayMetrics()
            disp.getRealMetrics(m)
            if (m.widthPixels > 0 && m.heightPixels > 0) {
                applyScreenSize(m.widthPixels, m.heightPixels)
                return
            }
        }
        // 路径 2：WindowManager metrics
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val b = wm.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) {
                applyScreenSize(b.width(), b.height())
                return
            }
        }
        // 路径 3：resources 兜底
        runCatching {
            val m = context.resources.displayMetrics
            if (m.widthPixels > 0 && m.heightPixels > 0) {
                applyScreenSize(m.widthPixels, m.heightPixels)
                return
            }
        }
    }

    private fun applyScreenSize(w: Int, h: Int) {
        screenWidth = w
        screenHeight = h
        controller?.let {
            it.screenWidth = w
            it.screenHeight = h
        }
    }

    // ==================== 控制通道（root 托管手势） ====================

    /** 建立输入监视：monitorGestureInput + 自解析读线程 + 手势状态机。 */
    fun attachControl() {
        handler.post {
            if (monitor != null) return@post
            if (!backend.isAttached) {
                notifyWindowFailed(
                    "attachControl",
                    IllegalStateException("display layer not attached")
                )
                return@post
            }
            val context = obtainSystemContext() ?: run {
                notifyWindowFailed("attachControl", IllegalStateException("systemContext unavailable"))
                return@post
            }

            val mon = GestureInputMonitor(
                displayId = android.view.Display.DEFAULT_DISPLAY,
                // 读线程会在回调返回后立即 recycle 原事件：post 前先同步
                // obtain() 拷贝，切到 handler 线程消费完毕后再回收拷贝
                onEvent = { ev ->
                    val copy = android.view.MotionEvent.obtain(ev)
                    handler.post {
                        try {
                            controller?.onTouch(copy)
                        } finally {
                            copy.recycle()
                        }
                    }
                },
                onFatal = { t -> notifyWindowFailed("inputMonitor", t) }
            )
            val ctrl = OverlayGestureController(
                context = context,
                handler = handler,
                backend = backend,
                input = mon,
                onSwitchMedia = { delta -> notifySwitchMedia(delta) }
            ).apply {
                screenWidth = this@RootOverlayService.screenWidth
                screenHeight = this@RootOverlayService.screenHeight
                syncGeometry(
                    controllerWindowX(), controllerWindowY(),
                    controllerWindowW(), controllerWindowH()
                )
            }
            if (!mon.start()) {
                // start 失败已通过 onFatal 上报（回落由应用侧驱动）
                return@post
            }
            // controller 先于事件流到达就位（start 前赋值，杜绝早期事件丢弃）
            controller = ctrl
            monitor = mon
        }
    }

    // backend 建立时几何由应用进程下发；controller 创建于其后，
    // 以最近一次 attach/setGeometry 的值为初值（缺省 0）
    private var lastX = 0
    private var lastY = 0
    private var lastW = 0
    private var lastH = 0

    private fun controllerWindowX() = lastX
    private fun controllerWindowY() = lastY
    private fun controllerWindowW() = lastW
    private fun controllerWindowH() = lastH

    fun detachControl() {
        handler.post { removeControlInternal() }
    }

    private fun removeControlInternal() {
        monitor?.stop()
        monitor = null
        controller = null
    }

    fun registerCallback(cb: IBinder?) {
        if (cb == null) return
        handler.post { callback = cb }
    }

    // ==================== root -> app 回调 ====================

    /** 挂载失败上报（handler 线程内调用）。无日志：原因仅经回调链通知应用侧回落。 */
    private fun notifyWindowFailed(where: String, t: Throwable) {
        invokeCallback(101) {
            it.writeString("$where: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun notifySwitchMedia(delta: Int) {
        invokeCallback(100) { it.writeInt(delta) }
    }

    /** root 端诊断上报（handler 线程内调用）：经 code 102 送达 app 进程。 */
    private fun notifyDebug(msg: String) {
        invokeCallback(102) { it.writeString(msg) }
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

    // ==================== 媒体显示 ====================

    fun showImage(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        handler.post { showImageInternal(fd) }
    }

    private fun showImageInternal(fd: ParcelFileDescriptor) {
        try {
            val bitmap = fd.use { decodeBitmap(it) }
            backend.showImage(bitmap) // null 时 backend 绘制红底（与原实现一致）
        } catch (_: Throwable) {
            backend.showImage(null)
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
        val reqW = (lastW * 2).coerceAtLeast(320)
        val reqH = (lastH * 2).coerceAtLeast(320)
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
        if (!backend.isAttached) {
            runCatching { fd.close() }
            return
        }
        backend.showVideo(fd)
    }

    fun clearMedia() {
        handler.post { backend.clearMedia() }
    }

    fun scaleImage(factor: Float) {
        handler.post { backend.scaleImage(factor) }
    }

    fun panImage(dx: Float, dy: Float) {
        handler.post { backend.panImage(dx, dy) }
    }

    // ==================== 视频控制 ====================

    fun togglePlayPause() {
        handler.post { backend.togglePlayPause() }
    }

    fun seekBy(deltaMs: Int) {
        handler.post { backend.seekBy(deltaMs) }
    }

    fun setMuted(muted: Boolean) {
        handler.post { backend.setMuted(muted) }
    }

    // ==================== root 进程基础设施 ====================

    // Shizuku 进程内全局共享（同 APK 的 classloader 只加载一次）
    @Volatile
    private var systemContext: Context? = null

    @SuppressLint("PrivateApi")
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

            102 -> {
                RootOverlayService.handleDebug(data.readString() ?: "")
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}

private object SuProto {
    /** root -> app：onSwitchMedia(int delta) */
    const val CODE_ON_SWITCH_MEDIA = 100

    /** root -> app：onWindowFailed(String reason) */
    const val CODE_ON_WINDOW_FAILED = 101

    /** root -> app：onDebug(String msg)——root 进程诊断经帧送达 app 进程 */
    const val CODE_ON_DEBUG = 102

    /** root -> app：SO_PEERCRED 对端校验通过、帧派发循环就绪 */
    const val CODE_READY = 997

    /** app -> root：销毁 root 端服务并退出进程 */
    const val CODE_DESTROY = 999

    /** 握手行前缀（root 进程经 stdout 管道回传："SF1 <socketName>"） */
    const val HANDSHAKE_PREFIX = "SF1"
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

    /**
     * 内核级对端校验（SO_PEERCRED）：对端进程 uid 必须为 0（root）。
     * 防御 socket 名泄露后的伪造服务端（抢先绑定同名抽象 socket 诱使
     * 本进程连接并交出媒体 fd）：伪造者非 root 即被内核凭据戳穿，
     * 无法伪造 uid。
     */
    fun verifyPeerRoot(): Boolean = runCatching {
        socket.peerCredentials.uid == 0
    }.getOrDefault(false)

    companion object {
        private const val MAX_FRAME = 1 shl 20

        /**
         * 应用端连接 root 端监听的抽象命名空间 socket；失败返回 null。
         *
         * 必须用单参数 connect(endpoint)——带 timeout 的重载经
         * LocalSocketImpl.connect(addr, timeout) 恒抛
         * UnsupportedOperationException（AF_UNIX 不支持连接超时，
         * AOSP 未实现该路径）。抽象 socket 连接是即时的（成功或
         * ECONNREFUSED 立即返回），无需超时参数；外层 awaitTransport
         * 的轮询 deadline 兜底。
         *
         * [lastError] 非空时写入本次异常摘要（每次失败覆盖，保留最后
         * 一次）：EACCES → SELinux 拒绝；ECONNREFUSED → socket 不存在
         * （root 端尚未绑定或已退出）。
         */
        fun connect(name: String, timeoutMs: Int, lastError: StringBuilder? = null): SuTransport? {
            return try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                SuTransport(s)
            } catch (t: Throwable) {
                lastError?.apply {
                    setLength(0)
                    append("${t.javaClass.simpleName}: ${t.message}")
                }
                null
            }
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
 * ==================== 建连与信任引导（无痕导向） ====================
 *
 * 旧方案 `su -c "<完整命令>"` 的 su 客户端进程存活期间（即整个悬浮窗
 * 生命周期）cmdline 常驻 APK 路径 + 入口类名 + socket 名（/proc/<pid>/cmdline
 * 传统上全局可读）。现行方案彻底翻转机密流向：
 *
 * 1. 应用以无参数 su 启动（命令写入 su 进程 stdin，Magisk/KernelSU/AOSP
 *    均支持）——su 客户端进程 cmdline 仅剩 "su"，argv 零信息；
 * 2. root 宿主进程（SuLauncher）自选 SecureRandom socket 名，经 stdout
 *    管道回传——socket 名不出现在任何进程的 argv / 全局可读 /proc 接口，
 *    第三方无从知晓更无从抢注；
 * 3. 双向内核级认证（SO_PEERCRED，uid 由内核提供不可伪造）：
 *    - root 端校验连接者 uid == 应用 uid（期望值经 su 命令的环境前缀
 *      传入，仅本应用可控；伪造连接一律关闭并继续 accept）；
 *    - 应用端校验对端 uid == 0（防伪造服务端骗取媒体 fd）。
 *
 * 失败兜底：无参数模式不可用的个别 ROM 回退 `-c` 模式（此时命令已不含
 * 任何机密，仅暴露可归因信息）；su 完全不可用则回落普通悬浮窗路线。
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
                SuProto.CODE_ON_DEBUG ->
                    frame.readStringFromParcel()?.let { RootOverlayService.handleDebug(it) }
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
        reader.apply { name = OverlayHiddenApi.randomName() }.start()
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

        /** 等待 root 端就绪帧的上限（root 端认证即时就绪，此为看门狗） */
        private const val READY_TIMEOUT_MS = 3000L

        /**
         * 异步建立 su 直连。返回 false 表示立即判定不可行（无 su 二进制），
         * 此时调用方应同步走普通路线。
         *
         * 依次尝试 stdin 模式与 `-c` 兜底模式（见类文档），任一模式建连
         * 成功即返回。失败时 onResult 第二参数携带逐环节诊断
         * （spawn/握手/连接/认证/就绪 + stderr 摘录），由 app 进程落日志。
         */
        fun connectAsync(
            context: Context,
            onResult: (SuOverlayConnection?, String?) -> Unit
        ): Boolean {
            if (!Auxiliary.hasSuBinary()) return false
            val apkPath = context.applicationInfo.sourceDir

            Thread {
                val diag = StringBuilder()
                var conn: SuOverlayConnection? = null
                for (useStdin in booleanArrayOf(true, false)) {
                    diag.append(if (useStdin) "[stdin模式] " else "[-c模式] ")
                    conn = connectOnce(apkPath, useStdin, diag) {
                        onResult(null, "root 进程死亡/断连")
                    }
                    if (conn != null) break
                }
                if (conn == null) {
                    // 兜底取证：main 入口之前的失败（VM 创建/类加载）仍只进
                    // logcat，管道零输出——以 root 身份 dump AndroidRuntime/
                    // appproc 错误与 crash 缓冲，按本次启动的随机进程名
                    // （--nice-name，已记录在 diag 的 cmd 串中）精确归因，
                    // 排除其他应用的崩溃噪声
                    runCatching {
                        val names = Regex("nice-name='([^']+)'")
                            .findAll(diag).map { it.groupValues[1] }.toList()
                        val p = ProcessBuilder(
                            "su", "-c",
                            "logcat -d -b main -b crash -s AndroidRuntime:E appproc:E -t 200"
                        ).start()
                        runCatching { Thread.sleep(PROBE_MS * 2) }
                        if (runCatching { p.exitValue() }.getOrNull() == null) {
                            runCatching { p.destroy() }
                        } else {
                            val logs = runCatching {
                                p.inputStream.bufferedReader().use { it.readText() }
                            }.getOrDefault("")
                            if (logs.isNotBlank()) {
                                val lines = logs.lineSequence().toList()
                                // 崩溃头行含 "Process: <nice-name>"；据此定位
                                // 本进程崩溃块，截取头行上下文若干行
                                val idx = lines.indexOfFirst { l ->
                                    names.any { l.contains(it) }
                                }
                                if (idx >= 0) {
                                    val from = maxOf(0, idx - 2)
                                    val to = minOf(lines.size, idx + 25)
                                    diag.append(
                                        "crash缓冲[本次进程]: ${
                                            lines.subList(from, to).joinToString(" | ").take(500)
                                        }; "
                                    )
                                } else if (logs.contains("could not find class") ||
                                    logs.contains("JNI_CreateJavaVM")
                                ) {
                                    // 无进程名归因时的次选：这两类错误只会在
                                    // app_process 启动时产生，基本必属本进程
                                    diag.append(
                                        "logcat[app_process错误]: ${
                                            logs.trim().replace('\n', ' ').take(400)
                                        }; "
                                    )
                                }
                            }
                        }
                    }
                    // SELinux 拒绝取证：若 connect 卡在 EACCES（untrusted_app
                    // → root 域 unix_stream_socket connect 未放行），内核
                    // avc 拒绝记录落在 logcat 环形缓冲，进程死后仍在——
                    // 过滤 unix_socket 相关行，与本次 socket 名交叉印证
                    runCatching {
                        val sockNames = Regex("socket\\(name=([^,)]+)")
                            .findAll(diag).map { it.groupValues[1] }.toSet()
                        val p = ProcessBuilder(
                            "su", "-c", "logcat -d -s avc -t 300"
                        ).start()
                        runCatching { Thread.sleep(PROBE_MS * 2) }
                        if (runCatching { p.exitValue() }.getOrNull() == null) {
                            runCatching { p.destroy() }
                        } else {
                            val avc = runCatching {
                                p.inputStream.bufferedReader().use { it.readText() }
                            }.getOrDefault("")
                            val hits = avc.lineSequence()
                                .filter { line ->
                                    (line.contains("unix_stream_socket") ||
                                            line.contains("unix_stream") ||
                                            line.contains("unix_dgram")) &&
                                            (line.contains("connect") ||
                                                    sockNames.any { line.contains(it) })
                                }
                                .toList()
                            diag.append(
                                if (hits.isEmpty()) {
                                    "AVC[unix]: 无相关拒绝记录; "
                                } else {
                                    "AVC[unix拒绝]: ${hits.joinToString(" | ").take(400)}; "
                                }
                            )
                        }
                    }
                }
                onResult(
                    conn,
                    if (conn == null) diag.toString().trim().ifBlank { "unknown" } else null
                )
            }.apply {
                isDaemon = true
                this.name = OverlayHiddenApi.randomName()
            }.start()
            return true
        }

        /**
         * 以指定模式完成一次完整建连（启动 root 进程 → 读握手行 → 连接
         * → 双向认证 → 等就绪帧），任一环节失败向 [diag] 追加该环节的
         * 诊断描述并返回 null（调用方据此定位失败点）。
         */
        private fun connectOnce(
            apkPath: String,
            useStdin: Boolean,
            diag: StringBuilder,
            onDead: () -> Unit
        ): SuOverlayConnection? {
            val proc = startRootProcess(apkPath, useStdin, diag) ?: return null
            val socketName = readHandshakeLine(proc, CONNECT_TIMEOUT_MS, diag)
            if (socketName == null) {
                runCatching { proc.destroy() }
                return null
            }
            val transport = awaitTransport(socketName, proc, diag)
            if (transport == null) {
                runCatching { proc.destroy() }
                return null
            }
            if (!transport.verifyPeerRoot()) {
                diag.append("auth: SO_PEERCRED 对端非 root; ")
                runCatching { transport.close() }
                runCatching { proc.destroy() }
                return null
            }
            if (!waitReady(transport, diag)) {
                runCatching { transport.close() }
                runCatching { proc.destroy() }
                return null
            }
            val proxy = SuOverlayProxy(transport)
            return SuOverlayConnection(proxy, transport, proc, onDead).also { it.start() }
        }

        /**
         * 仅收集 stderr（handshake / connect 阶段使用：stdout 可能携带
         * 握手行，绝不可在此消费）。失败路径调用，不阻塞。
         */
        private fun drainStderr(proc: Process): String = runCatching {
            val es = proc.errorStream
            val avail = es.available()
            if (avail <= 0) "" else {
                val buf = ByteArray(avail)
                es.read(buf)
                // 600：容纳 SuLauncher 顶层 catch 写入的 FATAL + 栈帧摘要
                String(buf).trim().replace('\n', ' ').take(600)
            }
        }.getOrDefault("")

        /**
         * 非阻塞收集 su / root 进程 stdout + stderr（root 管理器拒绝授权、
         * shell 语法错误等信息写这里——部分实现（如 KernelSU 未授权）
         * 静默 exit(1) 两流皆空；部分写 stderr；也有写 stdout 的）。
         * 仅在 spawn 失败路径调用（进程已死，读 stdout 不会吞掉握手行），
         * available() 探测保证不阻塞。
         */
        private fun drainOutput(proc: Process): String {
            fun drain(stream: java.io.InputStream): String = runCatching {
                val avail = stream.available()
                if (avail <= 0) "" else {
                    val buf = ByteArray(avail)
                    stream.read(buf)
                    String(buf).trim().replace('\n', ' ').take(200)
                }
            }.getOrDefault("")

            val err = drain(proc.errorStream)
            val out = drain(proc.inputStream)
            return listOf(err, out).filter { it.isNotBlank() }
                .joinToString(" | ") { it }
                .ifBlank { "" }
        }

        /**
         * 启动 root 宿主进程。
         *
         * stdin 模式（useStdin=true，优先）：su 不带参数，命令写入 su 进程
         * stdin——su 客户端进程存活期间 cmdline 仅剩 "su"（-c 模式下完整
         * 命令含 APK 路径/入口类名常驻 cmdline，全局可读）。写完即关写端：
         * 管道缓冲数据保留可被对端完整读取；若 exec 失败，shell 因 EOF
         * 退出，被存活探测过滤。
         *
         * `-c` 模式（useStdin=false，兜底）：个别 ROM 的 su 无参数模式
         * 不可用时保功能。命令已不含任何机密（socket 名由 root 进程自选
         * 经 stdout 回传），-c 暴露的只有可归因信息（APK 路径与入口类名，
         * su 客户端的父进程归属应用本身，归因本就无法隐藏）。
         *
         * 命令体（两种模式共用）：
         * - SF_UID：期望对端 uid（root 端 SO_PEERCRED 校验用），经环境
         *   前缀传递，root 进程的 /proc/<pid>/environ 仅 root 可读；
         * - --nice-name（AOSP 11-16 均支持）：app_process 启动即以
         *   AndroidRuntime.setArgv0 重写 argv 区，/proc/cmdline 与 comm
         *   只剩随机名（随机字符+随机长度）。argv 全程不含机密。
         *
         * stderr 保持管道（不重定向 /dev/null）：root 管理器拒绝授权等
         * 错误写这里，失败时经 drainOutput 收进诊断；root 端握手完成后
         * 自行将 fd 2 重定向 /dev/null（见 SuLauncher），运行期噪声不会
         * 填满管道阻塞 root 进程。
         *
         * 逐 su 候选尝试（PATH 优先，再常见绝对路径）；短暂探测存活以
         * 过滤"无授权被直接拒绝"（拒绝时 su 立即退出；授权弹窗期间进程
         * 存活，继续等待）。全失败返回 null（各候选死因记入 diag）。
         */
        private fun startRootProcess(
            apkPath: String,
            useStdin: Boolean,
            diag: StringBuilder
        ): Process? {
            val niceName = Auxiliary.getSecureRandomString(Auxiliary.getSecureRandomInt(6..12))
            // app_process 参数序（AOSP 11-16 一致）：[vm-options] cmd-dir [内部参数] 类名 [args]
            // —— --nice-name 必须位于 cmd-dir（"/"）之后。放在它之前会被 VM option
            // 解析循环吞掉传给 JNI_CreateJavaVM，而 AndroidRuntime 以
            // ignoreUnrecognized=FALSE 初始化 VM，未知 option 直接令 VM 创建失败：
            // 进程毫秒级静默退出（错误仅写 logcat 的 AndroidRuntime tag，
            // stdout/stderr 零输出）——即此前"su 正常 / env 前缀正常 / 命令体
            // exit=1 无输出"探针组合的根因
            val cmd = "SF_UID=${android.os.Process.myUid()} CLASSPATH='$apkPath' " +
                    "exec /system/bin/app_process / --nice-name='$niceName' " +
                    SuLauncher::class.java.name
            // 诊断：完整命令串原样入 diag（失败时随 Toast/logcat 输出），
            // 供直接比对 shell 语义/引号/长度问题
            diag.append("cmd=[$cmd]; ")
            // 混淆自检：app_process 经 JNI 按 "main" 方法名定位入口（见
            // SuLauncher 文档与 proguard-rules.pro 的 keepclassmembers 规则）。
            // 规则缺失时方法名被 R8 混淆 → root 进程静默 exit=1（错误仅进
            // logcat 的 AndroidRuntime tag，stderr 零输出）——提前拦截，
            // 给出可读原因而非逐 su 候选盲试
            runCatching {
                SuLauncher::class.java.getMethod("main", Array<String>::class.java)
            }.onFailure {
                diag.append(
                    "SuLauncher.main 无法反射定位（混淆 keep 规则缺失，" +
                            "方法名已被 R8 改写）: ${it.message}; "
                )
                return null
            }
            val candidates = listOf("su") + Auxiliary.suBinaryPaths
            for (bin in candidates) {
                val proc = runCatching {
                    ProcessBuilder(if (useStdin) listOf(bin) else listOf(bin, "-c", cmd))
                        .start()
                }.getOrNull() ?: run {
                    diag.append("spawn[$bin]: 启动失败; ")
                    continue
                }
                if (useStdin) {
                    val wrote = runCatching {
                        proc.outputStream.use {
                            it.write((cmd + "\n").toByteArray())
                            it.flush()
                        }
                        true
                    }.getOrDefault(false)
                    if (!wrote) {
                        diag.append("spawn[$bin]: stdin 写入失败; ")
                        runCatching { proc.destroy() }
                        continue
                    }
                }
                runCatching { Thread.sleep(PROBE_MS) }
                if (proc.isAlive) return proc
                diag.append(
                    "spawn[$bin]: ${PROBE_MS}ms 内退出" +
                            runCatching { "(exit=${proc.exitValue()})" }.getOrDefault("") +
                            " ${drainOutput(proc)}; "
                )
                runCatching { proc.destroy() }
            }
            // 最小探针：与命令体无关的 su -c id。区分两类失败：
            // - exit=0 且输出 uid=0 → su 链路完全正常，问题在命令体
            //   （环境变量前缀 + exec app_process 组合在该 su 的 shell
            //   下解析/执行失败）；
            // - 非 0 / 无输出 → su 对本应用整体拒绝（授权未生效/uid 判定
            //   不匹配/管理器策略），与我们的命令写法无关
            var suNormal = false
            for (bin in listOf("su", "/system/bin/su")) {
                val probe = runCatching {
                    ProcessBuilder(bin, "-c", "id").start()
                }.getOrNull() ?: continue
                // id 毫秒级完成；给 su 授权判定留余量
                runCatching { Thread.sleep(PROBE_MS) }
                val exited = runCatching { probe.exitValue() }.getOrNull()
                val out = drainOutput(probe)
                runCatching { probe.destroy() }
                if (exited == null) {
                    // 仍存活：授权弹窗挂起或命令未归（罕见）
                    diag.append("probe[$bin -c id]: 仍存活(可能弹窗等待); ")
                    continue
                }
                diag.append("probe[$bin -c id]: exit=$exited ${out.ifBlank { "(无输出)" }}; ")
                if (exited == 0 && out.contains("uid=0")) {
                    suNormal = true
                    diag.append("(su 正常→命令体问题); ")
                }
                break
            }
            if (suNormal) {
                // 分段探针：su 正常但命令体失败时，进一步区分失败层级。
                // 探针2（环境前缀语法）：`SF_UID=1 id` 若失败 → 该 su 的
                //   shell 不支持环境变量前缀写法，需改 export/env 形式；
                // 探针3（完整命令体 + stderr 合流）：若 app_process 崩溃，
                //   Java 栈会进入 stdout 被 diag 捕获（根因直读）；
                //   若进程存活 → 命令体可正常运行，矛盾于 spawn 存活判定；
                // 探针4（root 侧 logcat）：app_process 的 VM 创建失败
                //   （JNI_CreateJavaVM failed）与类加载失败（could not
                //   find class）只经 ALOGE 写 logcat（AndroidRuntime /
                //   appproc tag），stderr 零输出——探针3 "exit!=0 无输出"
                //   的根因只躺在这里，需以 root 身份 dump 才能拿到
                runCatching {
                    val p2 = ProcessBuilder("su", "-c", "SF_UID=1 id").start()
                    runCatching { Thread.sleep(PROBE_MS) }
                    val p2Exit = runCatching { p2.exitValue() }.getOrNull()
                    diag.append(
                        "probe2[env前缀]: exit=$p2Exit ${drainOutput(p2).ifBlank { "(无输出)" }}; "
                    )
                    runCatching { p2.destroy() }
                }
                runCatching {
                    val p3 = ProcessBuilder("su", "-c", "$cmd 2>&1").start()
                    runCatching { Thread.sleep(PROBE_MS * 3) }
                    val p3Exit = runCatching { p3.exitValue() }.getOrNull()
                    if (p3Exit == null) {
                        diag.append("probe3[命令体]: 进程存活(命令可运行); ")
                        // 真实 SuLauncher 进程：握手行已写 stdout（被本探针读走
                        // 属预期），看门狗 15s 自动回收；主动 destroy 免等
                        runCatching { p3.destroy() }
                    } else {
                        // 崩溃输出（Java 栈）截断防 diag 超长
                        val trace = runCatching {
                            p3.inputStream.bufferedReader().use { it.readText() }
                        }.getOrDefault("")
                        diag.append(
                            "probe3[命令体]: exit=$p3Exit ${
                                trace.trim().replace('\n', ' ').take(400).ifBlank { "(无输出)" }
                            }; "
                        )
                    }
                }
                runCatching {
                    // 进程已退出（exit 已知）后完整读流不会阻塞；
                    // logcat -d 落盘即退，仍存活属异常（个别 ROM 的 su 会话
                    // 挂起），销毁跳过
                    val p4 = ProcessBuilder(
                        "su", "-c",
                        "logcat -d -b main -b crash -s AndroidRuntime:E appproc:E -t 60"
                    ).start()
                    runCatching { Thread.sleep(PROBE_MS) }
                    val p4Exit = runCatching { p4.exitValue() }.getOrNull()
                    if (p4Exit == null) {
                        diag.append("probe4[logcat]: 仍存活(异常); ")
                        runCatching { p4.destroy() }
                    } else {
                        val logs = runCatching {
                            p4.inputStream.bufferedReader().use { it.readText() }
                        }.getOrDefault("")
                        // 关键词过滤：logcat 的 AndroidRuntime tag 混有其他
                        // 应用崩溃栈，只保留 app_process 启动链相关行
                        val hits = logs.lineSequence()
                            .filter { line ->
                                listOf(
                                    "JNI_CreateJavaVM", "find class", "FATAL",
                                    "Exception", "app_process", "RuntimeInit",
                                    "Caused by", "SuLauncher"
                                ).any { line.contains(it, ignoreCase = true) }
                            }
                            .toList()
                        diag.append(
                            if (hits.isEmpty()) {
                                "probe4[logcat]: exit=$p4Exit (无相关条目); "
                            } else {
                                "probe4[logcat]: exit=$p4Exit ${hits.joinToString(" | ").take(500)}; "
                            }
                        )
                    }
                }
            }
            return null
        }

        /**
         * 读取 root 进程经 stdout 管道回传的握手行（"SF1 <socketName>"）。
         * 以前缀扫描而非按行号解析：容忍 ART 类加载注记等先行杂散输出。
         * root 进程写入该行后立即将 stdout 重定向 /dev/null（随后 EOF），
         * 管道不会因无人读取而阻塞 root 进程。
         *
         * 失败时向 [diag] 追加具体环节（超时 / 进程退出 / EOF）+ stderr 摘录。
         */
        private fun readHandshakeLine(proc: Process, timeoutMs: Long, diag: StringBuilder): String? {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val input = proc.inputStream
            val pending = StringBuilder()
            val chunk = ByteArray(128)
            while (SystemClock.elapsedRealtime() < deadline) {
                val n = runCatching {
                    if (input.available() > 0) input.read(chunk) else 0
                }.getOrDefault(-1)
                if (n < 0) {
                    diag.append("handshake: 管道 EOF（进程退出）${drainStderr(proc)}; ")
                    return null // root 进程死亡（EOF / 流损坏）
                }
                if (n > 0) {
                    pending.append(String(chunk, 0, n))
                    while (true) {
                        val idx = pending.indexOf('\n')
                        if (idx < 0) break
                        val line = pending.substring(0, idx).trim()
                        pending.delete(0, idx + 1)
                        val parts = line.split(' ')
                        if (parts.size == 2 && parts[0] == SuProto.HANDSHAKE_PREFIX) {
                            return parts[1]
                        }
                    }
                } else if (!proc.isAlive) {
                    diag.append("handshake: 进程提前退出${drainStderr(proc)}; ")
                    return null
                } else {
                    runCatching { Thread.sleep(50) }
                }
            }
            diag.append("handshake: ${timeoutMs}ms 内未收到握手行${drainStderr(proc)}; ")
            return null
        }

        /**
         * 等待 root 端就绪帧（SO_PEERCRED 校验通过、帧派发循环启动）。
         * 独立看门狗线程兜底：超时强制关闭 socket 解除阻塞读（root 端
         * 认证即时就绪，正常路径毫秒级到达）。失败细节记入 [diag]。
         */
        private fun waitReady(transport: SuTransport, diag: StringBuilder): Boolean {
            val done = AtomicBoolean(false)
            val watchdog = Thread {
                runCatching { Thread.sleep(READY_TIMEOUT_MS) }
                if (!done.get()) transport.close()
            }.apply {
                isDaemon = true
                start()
            }
            val frame = runCatching { transport.readFrame() }.getOrNull()
            done.set(true)
            if (frame == null) {
                diag.append("ready: ${READY_TIMEOUT_MS}ms 内未收到就绪帧; ")
                return false
            }
            if (frame.code != SuProto.CODE_READY) {
                diag.append("ready: 异常帧 code=${frame.code}; ")
                return false
            }
            return true
        }

        private fun awaitTransport(name: String, proc: Process, diag: StringBuilder): SuTransport? {
            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            val lastErr = StringBuilder()
            while (SystemClock.elapsedRealtime() < deadline) {
                SuTransport.connect(name, 1500, lastErr)?.let { return it }
                // su client 随宿主存活；退出即失败（拒绝/崩溃）
                if (!proc.isAlive) {
                    diag.append("connect: su 进程在连接重试期间退出${drainStderr(proc)}; ")
                    return null
                }
                runCatching { Thread.sleep(CONNECT_POLL_MS) }
            }
            diag.append(
                "connect: ${CONNECT_TIMEOUT_MS}ms 内无法连接 socket(name=$name, " +
                        "进程存活, lastErr=${lastErr.toString().take(120).ifBlank { "(无异常)" }}); "
            )
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
 * 由应用进程执行（无参数 su + stdin 递交，个别 ROM 兜底 `-c`）：
 *   SF_UID=<应用uid> CLASSPATH='<apk路径>' exec /system/bin/app_process \
 *          / --nice-name='<随机名>' <入口类名>
 * （--nice-name 必须位于 cmd-dir（"/"）之后：放在其前会被 app_process 的
 *   VM option 解析循环吞掉传给 JNI_CreateJavaVM，AndroidRuntime 以
 *   ignoreUnrecognized=FALSE 初始化 VM，未知 option 直接令 VM 创建失败，
 *   进程毫秒级静默退出——错误仅写 logcat 的 AndroidRuntime tag）
 *
 * ==================== 机密流向（与旧方案相反） ====================
 *
 * - argv 全程不含机密：入口类名是 APK 内公开字符串；--nice-name 使
 *   app_process 启动即重写 argv（AndroidRuntime.setArgv0），存活期间
 *   /proc/cmdline 与 comm 仅剩随机名（随机字符+随机长度）；
 * - socket 名由本进程 SecureRandom 自选，经 stdout 管道回传应用进程：
 *   不出现在任何进程 argv / 环境 / 全局可读 /proc 接口 → 无从知晓、
 *   无从抢注、无从伪造（另配合应用端 SO_PEERCRED uid=0 校验）；
 * - SF_UID（SO_PEERCRED 期望对端 uid）经 su 命令的环境前缀传入：
 *   本进程 environ 仅 root 可读，其他进程不可控不可读。
 *
 * 以 CLASSPATH=本应用 APK 启动的 app_process 会用 PathClassLoader 加载
 * 全部应用类（与 Shizuku UserService 从同一 APK 反射加载完全同源），
 * 因此 RootOverlayService 无需任何改动即可在本进程实例化：它自建
 * HandlerThread、反射 ActivityThread.systemMain() 获取 system context，
 * 纯 Surface 渲染与 InputMonitor 输入通道均以 uid=0 直达 SF / IMS。
 *
 * 进程生命周期：
 * - 启动即绑定抽象 socket 并经 stdout 回传名字，随后将 stdout 重定向
 *   /dev/null（写端关闭使应用侧读循环自然结束，管道不会缓冲填满）；
 * - 连接准入循环：SO_PEERCRED 内核级校验对端 uid == SF_UID——内核
 *   真相不可伪造，非本应用的连接（即使得知 socket 名）立即关闭并继续
 *   accept（防连接抢占 DoS），无数据依赖故零等待开销；
 * - 看门狗：15 秒内未等到合法连接则退出（应用进程侧 8 秒已放弃，防止
 *   孤儿 root 进程常驻）；
 * - 合法连接后发送就绪帧（997）并进入帧派发循环：payload 反序列化为
 *   Parcel 后直接进入 RootOverlayService.dispatch（其内部一律 post 到
 *   窗口线程，线程安全）；
 * - 收到 destroy 帧、对端关闭（应用进程死亡）或协议错误即退出——进程
 *   死亡时 layer / monitor 由系统侧 binder 死亡通知自动回收，不会泄漏。
 */
object SuLauncher {

    /** 无人连接时 root 进程最长存活时间（毫秒）。 */
    private const val ACCEPT_WATCHDOG_MS = 15_000L

    /** 期望对端 uid 的环境变量名（应用进程经 su 命令的环境前缀传入）。 */
    private const val ENV_PEER_UID = "SF_UID"

    /**
     * 启动期诊断直写 fd 2（stderr 管道，应用侧失败路径经 drainStderr 收取）。
     * 仅在 dup2(/dev/null) 之前的启动阶段使用；经 Os.write 绕开
     * PrintStream（其吞掉 IOException）。写失败（管道已断）静默忽略。
     */
    private fun diagErr(msg: String) {
        runCatching {
            val b = (msg + "\n").toByteArray()
            android.system.Os.write(java.io.FileDescriptor.err, b, 0, b.size)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 顶层兜底：app_process 下未捕获异常只进 logcat crash 缓冲，
        // 应用侧管道零输出——写 stderr 让应用侧直读根因，再非零退出
        try {
            runMain(args)
        } catch (t: Throwable) {
            // 逐层展开 cause 链：ExceptionInInitializerError 的真实根因在
            // 被包裹的 cause 里（wrapper 自身 message 为 null）
            var depth = 0
            var cur: Throwable? = t
            while (cur != null && depth < 5) {
                diagErr(
                    if (depth == 0) "FATAL: ${cur.javaClass.name}: ${cur.message}"
                    else "Caused by(${depth}): ${cur.javaClass.name}: ${cur.message}"
                )
                if (depth == 0) {
                    // 完整栈只在最外层打（cause 栈是其子集的前缀）
                    cur.stackTrace.take(12).forEach { diagErr("  at $it") }
                } else {
                    cur.stackTrace.take(3).forEach { diagErr("  at $it") }
                }
                cur = cur.cause
                depth++
            }
            runCatching { Runtime.getRuntime().exit(1) }
        }
    }

    private fun runMain(args: Array<String>) {
        val expectedUid = System.getenv(ENV_PEER_UID)?.toIntOrNull() ?: run {
            diagErr("SF_UID 环境变量缺失（su 命令的环境前缀未生效）")
            return
        }

        // socket 名：SecureRandom 自选（62^16+ 空间，猜测不可行），
        // 抽象命名空间 socket 在 /proc/net/unix 对 root/系统侧可见，
        // 但名字不出现在任何全局可读接口，第三方无从抢注
        val socketName = OverlayHiddenApi.randomName(16..32)
        val server = runCatching { LocalServerSocket(socketName) }.getOrNull() ?: run {
            diagErr("LocalServerSocket 绑定失败（SELinux/命名空间限制）")
            return
        }

        // 握手行经 stdout 管道回传（仅应用进程可读）。经 Os.write 直写
        // fd 1 而非 System.out（PrintStream 吞掉 IOException，对端已死
        // EPIPE 时无法感知），也非 FileOutputStream.use{}（close 会关闭
        // fd 1 本身——后续 dup2 前的窗口期内该 fd 号可能被并发 open
        // 复用，dup2 将覆盖无关文件）
        val wrote = runCatching {
            val line = "${SuProto.HANDSHAKE_PREFIX} $socketName\n".toByteArray()
            android.system.Os.write(java.io.FileDescriptor.out, line, 0, line.size)
            true
        }.getOrDefault(false)
        if (!wrote) {
            // 应用进程已死：退出，不留任何进程痕迹
            runCatching { server.close() }
            Runtime.getRuntime().exit(0)
        }
        // stdout 与 stderr 均重定向 /dev/null：stdout 管道写端关闭（应用侧
        // 随后 EOF，读循环自然结束）；stderr 同步关闭——启动期错误（ART
        // init 注记等）已留在管道供应用侧失败诊断读取，握手完成后本进程
        // 不再向两管道写入任何字节（防填满阻塞）
        runCatching {
            val devNull = android.system.Os.open(
                "/dev/null", android.system.OsConstants.O_WRONLY, 0
            )
            android.system.Os.dup2(devNull, 1)
            android.system.Os.dup2(devNull, 2)
            android.system.Os.close(devNull)
        }

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
            this.name = OverlayHiddenApi.randomName()
        }.start()

        // 连接准入循环：SO_PEERCRED（内核真相，uid 不可伪造）。校验无
        // 数据依赖，非法连接零等待关闭；看门狗保证循环不会无限耗资源
        var socket: LocalSocket? = null
        while (true) {
            val accepted = runCatching { server.accept() }.getOrNull() ?: break
            val uidOk = runCatching {
                accepted.peerCredentials.uid == expectedUid
            }.getOrDefault(false)
            if (uidOk) {
                socket = accepted
                break
            }
            runCatching { accepted.close() }
        }
        connected.set(true)
        if (socket == null) {
            runCatching { server.close() }
            Runtime.getRuntime().exit(0)
            return
        }
        // 唯一会话已建立：停止接受新连接
        runCatching { server.close() }

        // 窗口宿主：与 Shizuku UserService 共用同一实现
        val service = RootOverlayService()

        val transport = SuTransport.accepted(socket)

        // 反向回调（root -> app）：手势判定"切换媒体"/窗口挂载失败时经
        // socket 帧通知。binder 对象无法跨 socket 序列化，故以发送代理
        // 对象就地注入（与 Shizuku 路径的 registerCallback binder 等效）。
        service.registerCallback(SuCallbackSender(transport))

        // 就绪帧：应用侧据此确认认证通过、派发循环即将启动
        runCatching { transport.sendFrame(SuProto.CODE_READY, ByteArray(0), null) }

        // 帧派发循环：本线程即"协议线程"，等价于 Shizuku 路径的 binder 线程
        while (true) {
            val frame = runCatching { transport.readFrame() }.getOrNull() ?: break
            if (frame.code == SuProto.CODE_DESTROY) break
            val ok = runCatching { dispatchFrame(service, frame) }.getOrDefault(false)
            if (!ok) break
        }

        // 进程退出即清理：SF 回收 layer、IMS 回收 monitor（进程级资源回收）
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

            102 -> {
                val msg = data.readString() ?: ""
                transport.sendParcelFrame(102) { it.writeString(msg) }
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}
