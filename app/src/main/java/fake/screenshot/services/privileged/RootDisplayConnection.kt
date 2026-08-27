package fake.screenshot.services.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import fake.screenshot.Auxiliary
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用进程侧对 RootDisplayService（root 进程）的连接管理，双后端自动选择：
 *
 * - 后端 1（优先）：Shizuku UserService——Sui 或以 root 启动的 Shizuku。
 *   成熟稳定，bindUserService 即用。
 * - 后端 2（兜底）：su 直连（[SuDisplayConnection]）——仅凭 root 管理器
 *   （Magisk/KernelSU 等）对本应用的授权即可拉起 root 宿主进程，
 *   不要求设备上存在 Shizuku/Sui。有 root 而无 Shizuku 时同样获得
 *   绝对无痕窗口（uid=0 的 TRUSTED_OVERLAY）。
 *
 * 两个后端对外统一呈现 IRootDisplay 与 [Listener]：上层
 * （DisplayOverlayService / ControlOverlayService）完全不感知后端差异，
 * 连接失败/进程死亡一律经 onConnectionChanged(false) 回落本地窗口。
 *
 * - Shizuku 路径：bindUserService 同步创建、onServiceConnected 经 Shizuku
 *   主线程派发，binder 会"稍后"到达。
 * - su 路径：异步建立（含 root 管理器授权弹窗等待，上限 8 秒），
 *   结果经 handleSuResult 转主线程；generation 序号使 unbind/rebind
 *   能作废仍在途的连接请求，避免孤儿 root 进程。
 */
object RootDisplayConnection {

    private const val APPLICATION_ID = "fake.screenshot"

    // version 用于让 Shizuku 服务端区分实现版本：修改 RootDisplayService 的
    // 行为/结构后必须递增，否则服务端可能沿用旧版本缓存的类。
    // v2：新增控制窗口（attachControl/detachControl）与 registerCallback
    // v3：IRootDisplayCallback 新增 onWindowFailed（root 端窗口挂载失败上报）
    // （su 直连路径不经过 Shizuku，与该版本号无关）
    private val args = Shizuku.UserServiceArgs(
        ComponentName(APPLICATION_ID, RootDisplayService::class.java.name)
    )
        .processNameSuffix("display")
        .version(3)

    @Volatile
    private var service: IRootDisplay? = null

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    private var suBackend: SuDisplayConnection? = null

    @Volatile
    private var pendingSu = false

    /** unbind/rebind 时递增：作废仍在途的 su 连接请求 */
    private val generation = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun interface Listener {
        /** 回调发生在主线程。 */
        fun onConnectionChanged(active: Boolean)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (suBackend != null) {
                // 当前实际走 su 后端（Shizuku 晚到的回调）：忽略，防止覆盖
                return
            }
            service = IRootDisplay.Stub.asInterface(binder)
            isActive = service != null
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (suBackend != null) return
            service = null
            isActive = false
            notifyChanged()
        }
    }

    /** 当前可用的 root 端接口；连接不可用时返回 null（调用方走本地路径）。 */
    fun get(): IRootDisplay? = if (isActive) service else null

    /**
     * 绑定 root 托管服务（双后端）。返回 false 表示立即判定不可行
     * （Shizuku 非 root 且无 su 二进制），调用方应同步回退本地窗口；
     * 返回 true 表示已绑定或正在建立（su 异步路径），
     * 结果经 [Listener] 通知。
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
        val pending = SuDisplayConnection.connectAsync(context) { conn ->
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
        service = null
        isActive = false
    }

    /**
     * root 端失败上报入口（DisplayOverlayService 收到 onWindowFailed 回调、
     * 或连接看门狗超时后调用）：作废当前后端并广播断连，上层两个悬浮窗
     * 服务经各自 Listener 回落本地窗口，悬浮窗不中断。
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
            service = null
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
    private fun handleSuResult(conn: SuDisplayConnection?, gen: Int) {
        if (gen != generation.get()) {
            conn?.shutdown()
            return
        }
        pendingSu = false
        if (conn != null) {
            suBackend = conn
            service = conn.rootDisplay
            isActive = true
            notifyChanged()
        } else {
            // 建立失败（无授权/超时）或进程死亡：走 su 后端失败通知。
            // su 是唯一在途后端（Shizuku 可用就不会走到 su），置空安全。
            suBackend = null
            service = null
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
}

// ==================== su 直连后端（内联实现，仅本文件使用） ====================

/**
 * su 直连模式的帧协议常量。
 *
 * 帧格式（两个方向对称）：
 *   [int32 code][int32 payloadLen][payload bytes]
 *
 * code 与 IRootDisplay 的事务码保持一致（1~14），便于对照；
 * 15（registerCallback）不走协议——su 模式下回调对象在 root 进程
 * 启动时就地注入（binder 对象无法跨 socket 序列化）。
 * 媒体帧（code 5/6）的 fd 经 SCM_RIGHTS 随帧头同一次 write 传递。
 */
internal object SuProto {
    /** root -> app：onSwitchMedia(int delta)，payload 为 1 个 int32 */
    const val CODE_ON_SWITCH_MEDIA = 100

    /** root -> app：onWindowFailed(String reason)，payload 为 UTF 字符串 */
    const val CODE_ON_WINDOW_FAILED = 101

    /** app -> root：销毁 root 端服务并退出进程 */
    const val CODE_DESTROY = 999
}

/**
 * Su 直连模式下应用进程 <-> root（`su -c app_process`）进程的帧传输层。
 *
 * - 应用端 [connect]、root 端由 SuLauncher（见 RootDisplayService.kt）用
 *   LocalServerSocket.accept 得到 LocalSocket 后经 [accepted] 包装，
 *   两端共用同一套读写实现。
 * - 双工单 socket：主线程/手势线程发送（写锁互斥），专用读线程接收。
 * - 媒体 fd：发送端在帧字节的一次 write 前调 setFileDescriptorsForSend
 *   挂上 fd（内核在 SCM_RIGHTS 传递时自动 dup，与 binder 语义一致，
 *   调用方继续拥有并关闭自己的副本）；接收端在逐段 read 后经
 *   getAncillaryFileDescriptors 收集，归入当前完成的帧。
 * - 任一方向 EOF / 协议错误：readFrame 返回 null，调用方据此判定对端死亡。
 *
 * 不模仿 binder Parcel 格式（接口令牌/对象编组）：参数以 DataOutputStream
 * 原语按方法签名逐个写入，两端手工对照，避免脆弱的 Parcel 布局依赖。
 */
internal class SuTransport private constructor(private val socket: LocalSocket) {

    class Frame(val code: Int, val payload: ByteArray, val fds: List<FileDescriptor>)

    private val writeLock = Any()

    fun sendFrame(
        code: Int,
        fds: Array<FileDescriptor>? = null,
        write: (DataOutputStream) -> Unit = {}
    ) {
        val payload = ByteArrayOutputStream().let { bos ->
            DataOutputStream(bos).use(write)
            bos.toByteArray()
        }
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

/**
 * 应用进程侧的 su 直连后端：经 root 管理器（Magisk/KernelSU 等）的 su
 * 直接拉起 root 宿主进程（SuLauncher，见 RootDisplayService.kt），
 * 不依赖 Shizuku/Sui 存在。
 *
 * 建连流程（异步）：
 * 1. 生成不可猜测的抽象 socket 名，`su -c "CLASSPATH=<apk> app_process …"`
 *    启动 SuLauncher（stdout/stderr 重定向 /dev/null，无任何输出特征）；
 * 2. 轮询连接该 socket（上限 8 秒，覆盖 root 管理器授权弹窗的等待）；
 * 3. 成功后 rootDisplay（IRootDisplay 的 socket 代理）交给
 *    RootDisplayConnection 对外暴露；读线程循环接收 root->app 帧。
 *
 * 生命周期：
 * - 连接成功/死亡/失败均经 onResult 回调（SuDisplayConnection 或 null）；
 * - shutdown()：发送 destroy 帧后关闭——主动关闭不触发死亡回调
 *   （区别于意外断连：后者必须通知上层回落本地窗口）。
 */
internal class SuDisplayConnection private constructor(
    val proxy: SuRootDisplayProxy,
    private val transport: SuTransport,
    private val process: Process,
    private val onDead: () -> Unit
) {

    val rootDisplay: IRootDisplay get() = proxy

    @Volatile private var closed = false
    @Volatile private var started = false

    private val reader = Thread {
        while (!closed) {
            val frame = runCatching { transport.readFrame() }.getOrNull() ?: break
            when {
                frame.code == SuProto.CODE_ON_SWITCH_MEDIA && frame.payload.size >= 4 -> {
                    val delta = DataInputStream(
                        ByteArrayInputStream(frame.payload)
                    ).readInt()
                    proxy.callback?.let { runCatching { it.onSwitchMedia(delta) } }
                }
                frame.code == SuProto.CODE_ON_WINDOW_FAILED && frame.payload.isNotEmpty() -> {
                    // root 端窗口挂载失败：转发回调，上层回落本地窗口
                    val reason = DataInputStream(
                        ByteArrayInputStream(frame.payload)
                    ).readUTF()
                    proxy.callback?.let { runCatching { it.onWindowFailed(reason) } }
                }
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
        reader.apply { name = "SuDisplayReader" }.start()
    }

    /** 主动断开（应用侧销毁悬浮窗）：通知 root 端退出并清理，不触发死亡回调。 */
    fun shutdown() {
        closed = true
        runCatching { transport.sendFrame(SuProto.CODE_DESTROY) }
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
         * su 进程被立即拒绝），此时调用方应同步回退本地窗口；返回 true
         * 表示正在建立（含等待 root 授权弹窗），最终结果经 [onResult]，
         * 回调线程为内部工作线程（由 RootDisplayConnection 转主线程）。
         */
        fun connectAsync(
            context: Context,
            onResult: (SuDisplayConnection?) -> Unit
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
                val proxy = SuRootDisplayProxy(transport)
                val conn = SuDisplayConnection(proxy, transport, proc) { onResult(null) }
                conn.start()
                onResult(conn)
            }.apply {
                isDaemon = true
                this.name = "SuDisplayConnect"
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
 * IRootDisplay 的 socket 代理（应用进程侧）。
 *
 * 方法与 SuLauncher.dispatch 的参数顺序一一对应；
 * showImage/showVideo 的 fd 经 SCM_RIGHTS 传递（内核 dup，与 binder
 * 语义一致——调用方继续拥有并负责关闭自己的副本）；
 * registerCallback 不走协议：回调仅保存在本地，供读线程派发
 * root->app 帧时使用（root 端启动时已就地注入发送代理）。
 */
internal class SuRootDisplayProxy(
    private val transport: SuTransport
) : IRootDisplay {

    @Volatile
    var callback: IRootDisplayCallback? = null

    override fun attach(x: Int, y: Int, width: Int, height: Int) =
        transport.sendFrame(1) {
            it.writeInt(x); it.writeInt(y); it.writeInt(width); it.writeInt(height)
        }

    override fun detach() = transport.sendFrame(2)

    override fun setGeometry(x: Int, y: Int, width: Int, height: Int) =
        transport.sendFrame(3) {
            it.writeInt(x); it.writeInt(y); it.writeInt(width); it.writeInt(height)
        }

    override fun setAlpha(alpha: Float) =
        transport.sendFrame(4) { it.writeFloat(alpha) }

    override fun showImage(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        transport.sendFrame(5, fds = arrayOf(fd.fileDescriptor))
    }

    override fun showVideo(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        transport.sendFrame(6, fds = arrayOf(fd.fileDescriptor))
    }

    override fun clearMedia() = transport.sendFrame(7)

    override fun scaleImage(factor: Float) =
        transport.sendFrame(8) { it.writeFloat(factor) }

    override fun panImage(dx: Float, dy: Float) =
        transport.sendFrame(9) { it.writeFloat(dx); it.writeFloat(dy) }

    override fun togglePlayPause() = transport.sendFrame(10)

    override fun seekBy(deltaMs: Int) =
        transport.sendFrame(11) { it.writeInt(deltaMs) }

    override fun setMuted(muted: Boolean) =
        transport.sendFrame(12) { it.writeBoolean(muted) }

    override fun attachControl(x: Int, y: Int, width: Int, height: Int) =
        transport.sendFrame(13) {
            it.writeInt(x); it.writeInt(y); it.writeInt(width); it.writeInt(height)
        }

    override fun detachControl() = transport.sendFrame(14)

    override fun registerCallback(cb: IRootDisplayCallback?) {
        callback = cb
    }

    override fun asBinder(): IBinder? = null
}
