package fake.screenshot.services.privileged

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
import fake.screenshot.services.privileged.overlay.GestureInputMonitor
import fake.screenshot.services.privileged.overlay.OverlayGestureController
import fake.screenshot.services.privileged.overlay.OverlaySurfaceBackend
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
 * 本文件是服务类文件，同时承载两半（分别运行于不同进程）：
 *
 * - 【root 进程侧】[RootOverlayService] 本体（继承 Binder，手写 onTransact
 *   协议，不依赖 AIDL）：实现委托至 [fake.screenshot.services.privileged.overlay]
 *   模块——纯 Surface 双层渲染 + InputMonitor 输入通道，完全不经
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

        // version 用于让 Shizuku 服务端区分实现版本：修改本类行为/结构后
        // 必须递增，否则服务端可能沿用旧版本缓存的类。
        // v4：脱离旧 AIDL 接口，手写 binder 协议单文件实现
        // v5：放弃 WindowManager 悬浮窗，改纯 Surface 双层渲染 +
        //     InputMonitor 输入通道（Android 15 WMS session 加固绕开）
        // v6：输入通道反射加固（monitorGestureInput 双签名回退、
        //     InputChannel fd 双形态）+ 失败路径日志
        // v7：修复 IInputManager 包名错误（android.hardware.input，
        //     11-16 全版本；原误写 android.view 导致 ClassNotFoundException）
        // v8：InputChannel fd 提取改三路策略（13+ native-ptr 形态经
        //     writeToParcel + readFileDescriptor + dup 取 fd）
        // v9：修复 writeToParcel 布局解析：头部 int32 initialized 标志
        //     未跳过导致 fd 读取错位；13-16 与 11-12 两代布局按
        //     正确字段顺序解析（InputChannelCore.aidl 核对）
        // v10：channelPfd 全诊断版（每决策点 I/E 日志 + parcel hex dump
        //     + 三布局解析），定位真机实际 parcel 布局
        // v11：修复 AIDL parcelable 布局解析：pos 4 是尺寸信封
        //     （v10 诊断确认 envelope=156=total-4），此后才是 name/
        //     fd 标志/fd；11-12 旧格式 fd 在末尾 24 字节直取
        // v12：修复 v11 回归——invoke 对 void 方法返回 null，被
        //     getOrNull()?:return null 误判为失败（v10 的 isFailure 判定
        //     在 v11 重写时丢失）
        // v13：fd 提取改全 parcel 扫描（字段顺序跨版本/OEM 不可靠），
        //     int map 日志 + BINDER_TYPE_FD(0x66642a85) 定位
        // v14：读循环修复：InputChannel 为 O_NONBLOCK，改 Os.poll 等
        //     POLLIN（原阻塞式 read 会抛 EAGAIN 杀死读线程）；
        //     增加事件流诊断日志（type/size/action/坐标）
        // v15：fd 校验（fstat 必须 S_ISSOCK，否则继续扫描）；读循环
        //     显式捕获 ErrnoException（原 runCatching 静默吞掉 EBADF
        //     导致死循环无日志）；检查 revents 的 POLLERR/HUP/NVAL
        // v16：修复 controller 初始几何恒 (0,0,0,0)（lastX/Y/W/H 声明
        //     后从未赋值）：命中判断恒 false → 所有触摸事件被丢弃
        //     （"点击无响应"根因）。attach/setGeometry 均记录几何。
        // v17：修复屏幕尺寸恒 0（maximumWindowMetrics 在 root 进程静默
        //     失败被 runCatching 吞掉）→ updateOverlay 早退 → 移动/缩放
        //     全部失效（图片平移不经 clamp 故正常）。改多路径解析
        //     （DisplayManager/WM/resources）+ 失败留日志；DOWN 命中
        //     日志含 mode + 几何；coerceIn 空区间保护。
        // v18：缩放/平移卡顿修复：MOVE(~100Hz) 逐事件全量重绘远超
        //     vsync 60Hz 造成积压。几何 Transaction 逐事件立即（SF
        //     合成器侧，廉价），canvas 重绘节流 16ms 合并 + 尾随帧
        //     保证最终帧；panImage/scaleImage/setGeometry 统一走节流。
        //     实测更糟：节流推迟内容帧但 setBufferSize 仍逐事件触发
        //     buffer 重分配 + SF resize 等待新 buffer，积压更严重。
        // v19：正确方案（WMS 窗口动画同款）：resize 手势期间 buffer
        //     冻结，SF setMatrix 合成器 GPU 缩放现有 buffer（零 canvas
        //     零重分配，绝对跟手）；MOVE_WINDOW 纯移动只挪 position；
        //     ACTION_UP settle：matrix 归一 + 精确 bufferSize + 一次
        //     全量重绘。panImage/scaleImage 保留节流。手柄 live 期间
        //     隐藏（避免非等比拉伸变形），settle 恢复。
        // v20：跨版本审计修复：injectTap 反射参数类型 MotionEvent →
        //     InputEvent（全版本声明为 injectInputEvent(InputEvent, int)，
        //     原写法必抛 NoSuchMethodException → 视频单击透传一直走
        //     /system/bin/input shell 兜底，fork 进程 + 注入特征）。
        // （su 直连路径不经过 Shizuku，与该版本号无关）
        private val args by lazy {
            Shizuku.UserServiceArgs(
                ComponentName(APPLICATION_ID, RootOverlayService::class.java.name)
            )
                .processNameSuffix("overlay")
                .version(20)
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
            // 应用进程侧记录回落原因（与 root 端 TAG 一致，便于同过滤器抓取）
            android.util.Log.w("RootOverlay", "falling back to normal route: $reason")
            mainHandler.post { reportBackendFailed() }
        }
    }

    // ==================== root 进程侧：纯 Surface 悬浮窗宿主 ====================

    private val handlerThread = HandlerThread("RootOverlay").apply { start() }
    private val handler = Handler(handlerThread.looper)

    // 渲染后端（handler 线程独占；节流重绘 postDelayed 同线程无并发）
    private val backend = OverlaySurfaceBackend(handler) { t -> notifyWindowFailed("backend", t) }

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
            // 多路径解析 + 失败必留日志。
            resolveScreenSize(context)

            controller?.syncGeometry(x, y, width, height)
            backend.attach(x, y, width, height)
            android.util.Log.i("RootOverlay", "surface backend attached")
        } catch (t: Throwable) {
            // 绝不静默：上报应用进程回落本地窗口（否则悬浮窗"无任何显示"）
            android.util.Log.e("RootOverlay", "display attach FAILED", t)
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
     * 屏幕尺寸多路径解析（顺序：成功即停，全部失败留 ERROR 日志）：
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
                applyScreenSize(m.widthPixels, m.heightPixels, "DisplayManager")
                return
            }
        }.onFailure {
            android.util.Log.w("RootOverlay", "screen size: DisplayManager failed: $it")
        }
        // 路径 2：WindowManager metrics
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val b = wm.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) {
                applyScreenSize(b.width(), b.height(), "WindowManager")
                return
            }
        }.onFailure {
            android.util.Log.w("RootOverlay", "screen size: WindowManager failed: $it")
        }
        // 路径 3：resources 兜底
        runCatching {
            val m = context.resources.displayMetrics
            if (m.widthPixels > 0 && m.heightPixels > 0) {
                applyScreenSize(m.widthPixels, m.heightPixels, "resources")
                return
            }
        }
        android.util.Log.e(
            "RootOverlay",
            "screen size resolution FAILED: move/scale will not work"
        )
    }

    private fun applyScreenSize(w: Int, h: Int, via: String) {
        screenWidth = w
        screenHeight = h
        controller?.let {
            it.screenWidth = w
            it.screenHeight = h
        }
        android.util.Log.i("RootOverlay", "screen size via $via: ${w}x$h")
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
            android.util.Log.i("RootOverlay", "input monitor attached")
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

    /** 挂载失败上报（handler 线程内调用）。同步打日志：失败原因必须可在 logcat 直接观察。 */
    private fun notifyWindowFailed(where: String, t: Throwable) {
        android.util.Log.e(
            "RootOverlay",
            "window failed @ $where: ${t.javaClass.simpleName}: ${t.message}"
        )
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
 * HandlerThread、反射 ActivityThread.systemMain() 获取 system context，
 * 纯 Surface 渲染与 InputMonitor 输入通道均以 uid=0 直达 SF / IMS。
 *
 * 进程生命周期：
 * - 启动后在抽象命名空间监听（名字由应用进程随机生成传入，不可猜测）；
 * - 看门狗：15 秒内未等到连接则退出（应用进程侧 8 秒已放弃，防止孤儿
 *   root 进程常驻）；
 * - 连接后进入帧派发循环：payload 反序列化为 Parcel 后直接进入
 *   RootOverlayService.dispatch（其内部一律 post 到窗口线程，线程安全）；
 * - 收到 destroy 帧、对端关闭（应用进程死亡）或协议错误即退出——进程
 *   死亡时 layer / monitor 由系统侧 binder 死亡通知自动回收，不会泄漏。
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
        }
        return super.onTransact(code, data, reply, flags)
    }
}
