package fake.screenshot.wrappers

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕共享接收端配置。
 *
 * [address]/[port] 的含义取决于 [useSsh]：
 * - 直连：目标设备的地址与 scrcpy TCP proxy 端口（发送端的"屏幕共享本地端口"）；
 * - SSH 隧道：SSH 服务器地址与 SSH 服务器上的远程端口。
 *   接收端登录 SSH 后建立本地端口转发（localhost:lport → SSH 服务器 127.0.0.1:port），
 *   适用于发送端通过远程转发把共享端口暴露在 SSH 服务器上的场景。
 *
 * 音频与控制无需配置：接收端按发送端实际提供的通道自动适配
 * （发送端关闭 video/audio/control 任意通道时，本端自动进入对应模式，
 * 与 scrcpy 官方 `--no-video`/`--no-audio`/`--no-control` 行为一致）。
 */
data class ScreenShareReceiverConfig(
    val id: Int,
    val name: String,
    val address: String,
    val port: Int,
    val useSsh: Boolean = false,
    val sshPort: Int = 22,
    val sshUserName: String = "",
    val sshPassword: String = "",
    /** 共享密码：与发送端 auth_password 一致，留空表示发送端未启用认证 */
    val password: String = ""
)

/**
 * 屏幕共享接收端。设计为普通类而非单例：每个实例接收一路共享，
 * 支持同时存在多个实例（对应多部发送设备）。
 *
 * 协议（tunnel_forward 模式，与 [fake.screenshot.scrcpy.Server] 的 TCP proxy 对接）：
 * - 逐条建立 TCP 连接：连一条、读到 server 在该通道上写入的 1 字节通道标识
 *   （video=0 兼容旧 dummy byte，audio=1，control=2），确认配对成功后再连下一条。
 *   严格定序消除了 TCP proxy 多线程转发导致的通道错位配对竞态。
 * - 通道标识同时用于自适应：发送端关闭 audio/control 时 server 不再 accept
 *   对应通道（连接立即 EOF），接收端按实际存在的通道工作，收发两端开关不再要求一致。
 *   连接后等不到标识字节（旧版 server）则按 video → audio → control 的规范顺序回退。
 * - 通道可缺失：发送端支持仅视频/仅音频/仅控制（对应 video=/audio=/control= 参数），
 *   video 缺失时设备名改由 audio（或 control）socket 携带，音频/控制照常工作。
 * - video socket：64 字节设备名 + 4 字节 codec id + 帧流（标识字节已在协商时消费）；
 * - audio socket：4 字节 codec id + 帧流（video 缺失时最前面还有 64 字节设备名）；
 * - control socket：client → server 的控制消息注入（触摸/滚动/按键/文本/剪贴板）；
 *   server → client 的 DeviceMessage（剪贴板同步等）由 [controlLoop] 解析。
 * - 帧格式：12 字节 header（8 字节 ptsAndFlags + 4 字节 packetSize）+ 载荷。
 *   bit63 = session meta（宽高变化），bit62 = config/CSD 包，bit61 = 关键帧。
 *
 * 连接失败或会话中途断开时自动重试（发送端会自动重启 server），
 * 重试耗尽才进入 [State.Failed]。
 *
 * 生命周期：[start] 可重入（[stop] 后可再次 start），解码器在数据线程内创建与释放。
 */
class ScreenShareReceiver(val config: ScreenShareReceiverConfig) {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Running(val deviceName: String) : State
        data class Failed(val message: String) : State
        data object Stopped : State
    }

    companion object {
        /**
         * 单帧/单包长度上限：恶意或损坏的发送端可宣告超大 size，
         * ByteArray(size) 抛 OutOfMemoryError（Error 不被 catch(Exception)
         * 捕获，直接崩溃 app）——超限按流损坏走 IOException 重试路径
         */
        const val MAX_PACKET_BYTES = 8 * 1024 * 1024

        /** scrcpy Codec id（名称的 4 字节 ASCII 大端表示） */
        const val CODEC_H264 = 0x68323634
        const val CODEC_H265 = 0x68323635
        const val CODEC_AV1 = 0x00617631
        const val CODEC_VP8 = 0x00767038
        const val CODEC_VP9 = 0x00767039
        const val CODEC_OPUS = 0x6f707573
        const val CODEC_RAW = 0x00726177

        const val PACKET_FLAG_SESSION = 1L shl 63
        const val PACKET_FLAG_CONFIG = 1L shl 62
        const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = (1L shl 61) - 1

        const val DEVICE_NAME_LENGTH = 64
        const val CONNECT_TIMEOUT_MS = 8000
        const val SSH_TIMEOUT_MS = 8000

        // ControlMessage 类型（与服务端 ControlMessage 常量一致）
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_GET_CLIPBOARD = 8
        const val TYPE_SET_CLIPBOARD = 9
        const val TYPE_ROTATE_DEVICE = 11
        const val TYPE_RESET_VIDEO = 17

        // 通道标识（server 在每个 accept 的通道上写入 1 字节）
        const val CHANNEL_VIDEO = 0
        const val CHANNEL_AUDIO = 1
        const val CHANNEL_CONTROL = 2

        /** 等待通道标识字节的超时；超时视为旧版 server（无标识字节）按规范顺序回退 */
        const val CHANNEL_ID_TIMEOUT_MS = 3000

        /**
         * 通道协商完成后等待设备元数据（设备名 + codec id）的超时。
         * server 正常时元数据紧随其后到达；超时说明通道配对异常或 server 无响应，
         * 快速失败交给重试循环，避免永久阻塞在"连接中"
         */
        const val NEGOTIATION_TIMEOUT_MS = 10_000

        /**
         * 连接/会话失败自动重试次数与间隔。
         * 发送端 server 重启涉及进程退出检测（最长约 1s）+ 守护循环唤醒 + app_process
         * 冷启动（可能 2-4s），且发送端 app 后台时调度可能进一步延迟，重试窗口需足够长
         */
        const val RETRY_COUNT = 12
        const val RETRY_DELAY_MS = 1000L

        /**
         * 手指 pointerId 基数（-1 为 POINTER_ID_MOUSE 保留，服务端按鼠标注入），
         * 多指时依次递减分配：-2、-3、-4…
         */
        const val POINTER_ID_FIRST_FINGER = -2L

        /** 服务端 ControlMessageReader 对单条 INJECT_TEXT 的长度上限 */
        const val INJECT_TEXT_MAX_LENGTH = 300

        // MotionEvent.ACTION_*（避免依赖 android.view.MotionEvent 也可读性更好）
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        // 常用 KeyEvent.KEYCODE_*
        const val KEYCODE_BACK = 4
        const val KEYCODE_HOME = 3
        const val KEYCODE_APP_SWITCH = 187
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_POWER = 26
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /**
     * 单帧/单包长度上限：恶意或损坏的发送端可宣告超大 size，
     * ByteArray(size) 抛 OutOfMemoryError（Error 不被 catch(Exception) 捕获，
     * 直接崩溃 app）——超限按流损坏走 IOException 重试路径
     */
    private fun checkPacketSize(size: Int) {
        if (size < 0 || size > MAX_PACKET_BYTES) {
            throw IOException("implausible packet size: $size")
        }
    }

    /**
     * 会话代数：stop() 递增使旧 runLoop 的 finally 失效，
     * 防止旧会话清理时误关新会话的 socket / 置空新会话的 controlOut
     * （快速退出再进入 viewer、旋转屏幕触发 surface 重建时会发生）。
     */
    private val generation = AtomicLong(0)

    /** 当前会话的 socket，stop 时统一关闭以中断阻塞读 */
    private val sockets = CopyOnWriteArrayList<Socket>()

    /** SSH 模式下创建的本地转发端口，stop/重连时删除，避免随重试累积泄漏 */
    private val localForwardPorts = CopyOnWriteArrayList<Int>()
    private var sshSession: Session? = null

    /** control socket 的输出流，用于向发送端注入控制消息 */
    @Volatile
    private var controlOut: java.io.OutputStream? = null

    /**
     * 控制消息发送线程：所有 sendXXX 都可能从 UI（主线程）调用，
     * 直接写 socket 会抛 NetworkOnMainThreadException（控制失效的根因）。
     * 单线程执行器既完成线程切换，又天然保证消息顺序（如 DOWN 先于 UP）。
     *
     * 生命周期：stop() 时 shutdown 并重建——实例被 Manager 替换/丢弃后
     * 线程不再永久驻留（每次保存配置重建实例都会产生新线程）。
     * execute 包 runCatching：重建竞态窗口内旧 executor 已 shutdown，
     * 拒绝任务若不吞会在 UI 线程抛 RejectedExecutionException
     */
    @Volatile
    private var controlExecutor = createControlExecutor()

    private fun createControlExecutor() =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "net-worker").apply { isDaemon = true }
        }

    /**
     * 把一次控制消息写入投递到 [controlExecutor]（见其文档：主线程禁网）。
     * 写失败（socket 已断等）记录日志便于定位控制链路断点
     */
    private fun postControl(what: String, write: (java.io.DataOutputStream) -> Unit) {
        val out = controlOut ?: return
        runCatching {
            controlExecutor.execute {
                try {
                    val buffer = java.io.DataOutputStream(out)
                    write(buffer)
                    buffer.flush()
                } catch (_: Exception) {
                    // 写失败（socket 已断）：静默丢弃
                }
            }
        }
    }

    /** viewer 提供的渲染 Surface（null 表示暂无可渲染目标） */
    @Volatile
    private var surface: Surface? = null

    val state = MutableStateFlow<State>(State.Idle)

    /** 视频尺寸（来自 session meta），viewer 用于宽高比适配与触摸坐标映射 */
    val videoSize = MutableStateFlow<IntArray?>(null)

    /**
     * 发送端是否提供视频通道（协商结果）。
     * 缺失时 viewer 隐藏画面区域，显示音频/控制模式提示。
     */
    val videoAvailable = MutableStateFlow(false)

    /**
     * 发送端是否提供控制通道（协商结果）。
     * viewer 据此决定是否显示控制工具栏与触摸层。
     */
    val controlAvailable = MutableStateFlow(false)

    /**
     * 发送端剪贴板内容（来自 DeviceMessage type 0，含 GET_CLIPBOARD 响应与
     * 发送端 clipboard_autosync 自动同步），viewer 监听后写入本机剪贴板。
     */
    val clipboardContent = MutableStateFlow<String?>(null)

    /**
     * 发送端注入失败标记（DeviceMessage type 3）：发送端无法注入输入事件
     * （如 INJECT_EVENTS 权限被拒）时上报，viewer 提示用户而非静默无反应。
     * 每次 Toast 后由 viewer 置回 null。
     */
    val injectError = MutableStateFlow<String?>(null)

    fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    fun start() {
        if (job?.isActive == true) return
        running.set(true)
        state.value = State.Connecting
        videoSize.value = null
        videoAvailable.value = false
        job = scope.launch { runLoop() }
    }

    fun stop() {
        generation.incrementAndGet() // 使旧 runLoop 的 finally 不再触碰共享状态
        running.set(false)
        job?.cancel()
        closeSockets()
        runCatching { sshSession?.disconnect() }
        sshSession = null
        videoAvailable.value = false
        controlAvailable.value = false
        state.value = State.Stopped
        // 关停并重建控制线程：实例被 Manager 丢弃后旧线程不再驻留，
        // 重建保证后续 start() 的 postControl 可用
        controlExecutor.shutdownNow()
        controlExecutor = createControlExecutor()
    }

    // ---------------------------------------------------------------- 连接

    /**
     * 建立一条到发送端的 TCP 连接。SSH 模式下通过 JSch 本地端口转发接入。
     */
    private fun openSocket(): Socket {
        if (!config.useSsh) {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(config.address, config.port), CONNECT_TIMEOUT_MS)
            authenticate(socket)
            sockets.add(socket)
            return socket
        }

        val session = sshSession ?: createSshSession()
        // local_port = 0：由 JSch 自动分配空闲本地端口
        val localPort = session.setPortForwardingL(0, "127.0.0.1", config.port)
        localForwardPorts.add(localPort)
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
        authenticate(socket)
        sockets.add(socket)
        return socket
    }

    /**
     * 读取 server 在本条连接配对成功后写回的 1 字节通道标识。
     * 返回标识值；-1 表示对端关闭（服务器不再提供更多通道）；
     * -2 表示超时（旧版 server 不发送标识字节）。
     */
    private fun readChannelId(socket: Socket): Int {
        socket.soTimeout = CHANNEL_ID_TIMEOUT_MS
        try {
            return socket.getInputStream().read()
        } catch (e: java.net.SocketTimeoutException) {
            return -2
        } finally {
            socket.soTimeout = 0
        }
    }

    /** 协商结果：server 实际提供的各通道 socket（video 可缺失 = 发送端关闭了视频） */
    private class Channels(
        val videoSocket: Socket?,
        val audioSocket: Socket?,
        val controlSocket: Socket?
    )

    /**
     * 逐条连接并确认通道标识，直到 server 不再提供更多通道（EOF）或读满 3 条。
     * 每条连接确认配对后才发起下一条，保证与 server 端 accept 顺序一致。
     */
    private fun negotiateChannels(): Channels {
        val channels = HashMap<Int, Socket>()
        val canonicalOrder = intArrayOf(CHANNEL_VIDEO, CHANNEL_AUDIO, CHANNEL_CONTROL)
        try {
            while (channels.size < 3) {
                val socket = openSocket()
                val id = readChannelId(socket)
                when {
                    id in 0..2 && !channels.containsKey(id) -> channels[id] = socket
                    // 旧版 server 兼容：旧版在 audio/control 通道也发 dummy byte 0，
                    // video 已确认后再读到 0 说明对端是旧版（发送端 APK 未更新），
                    // 该字节实际是 dummy byte，按规范顺序回退配对下一通道。
                    // 新版 server 各通道标识唯一，不会重复发 0，此分支不触发
                    id == 0 && channels.containsKey(CHANNEL_VIDEO) -> {
                        val fallback = canonicalOrder.first { it !in channels }
                        channels[fallback] = socket
                    }
                    // 旧版 server 无标识字节（超时）：按规范顺序回退（连接顺序即配对顺序）
                    id == -2 -> {
                        val fallback = canonicalOrder.first { it !in channels }
                        channels[fallback] = socket
                    }
                    else -> {
                        // EOF 或重复/非法标识：server 不再提供更多通道
                        runCatching { socket.close() }
                        sockets.remove(socket)
                        break
                    }
                }
                if (channels.containsKey(CHANNEL_CONTROL)) break // control 是最后一个通道
            }
        } catch (e: Exception) {
            channels.values.forEach { runCatching { it.close() } }
            sockets.removeAll(channels.values)
            throw e
        }
        // 发送端可关闭任意通道（仅视频/仅音频/仅控制），但至少要有一个
        if (channels.isEmpty()) {
            throw IOException("sender did not provide any channel")
        }
        return Channels(channels[CHANNEL_VIDEO], channels[CHANNEL_AUDIO], channels[CHANNEL_CONTROL])
    }

    /**
     * 密码握手：发送端启用 auth_password 时，每条 TCP 连接（video/audio/control）
     * 建立后必须先发送一行密码，proxy 校验通过后才会接入 abstract socket。
     */
    private fun authenticate(socket: Socket) {
        val pwd = config.password
        if (pwd.isEmpty()) return
        socket.getOutputStream().apply {
            write((pwd + "\n").toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    private fun createSshSession(): Session {
        val jsch = JSch()
        val session = jsch.getSession(config.sshUserName, config.address, config.sshPort)
        session.setPassword(config.sshPassword.toByteArray(Charsets.UTF_8))
        session.setConfig("StrictHostKeyChecking", "no")
        // SSH 保活：NAT 静默丢弃连接（无 RST/EOF）时读线程一直阻塞、
        // isConnected 长期为 true——12 次重试全部复用死 session 白白耗尽，
        // 且 Failed 后页面"重试"不经 stop() 同样无法自愈（sshSession 只在
        // stop 里清）。保活探测使 isConnected 反映真实链路状态，
        // runLoop 的"仅重置已断开 session"逻辑才能命中
        session.setServerAliveInterval(15)
        session.setServerAliveCountMax(4)
        session.connect(SSH_TIMEOUT_MS)
        sshSession = session
        return session
    }

    private fun closeSockets() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        // 删除本次会话创建的 SSH 本地转发，避免随重试/重连累积泄漏
        sshSession?.let { session ->
            localForwardPorts.forEach { runCatching { session.delPortForwardingL(it) } }
        }
        localForwardPorts.clear()
    }

    // ---------------------------------------------------------------- 主循环

    /**
     * 主循环：协商 + 会话运行，失败（连接拒绝、server 重启窗口、网络断开等）
     * 自动重试 [RETRY_COUNT] 次，重试期间保持 Connecting 状态。
     */
    private suspend fun runLoop() {
        val gen = generation.incrementAndGet()
        var attempts = 0
        try {
            while (running.get() && gen == generation.get()) {
                try {
                    state.value = State.Connecting
                    runSession(gen)
                    // 正常返回 = 已停止
                    return
                } catch (e: Exception) {
                    // 清理本次失败的尝试（gen 已被新会话取代时不触碰共享状态）
                    if (gen == generation.get()) {
                        controlOut = null
                        videoAvailable.value = false
                        controlAvailable.value = false
                        closeSockets()
                        // SSH 隧道中途死亡时重置 session：openSocket 复用
                        // sshSession 引用，不重置则剩余重试全部在死 session 上
                        // 抛异常白白耗尽（Sender 宕机但 SSH 存活时 session 仍
                        // 连接、正常复用——只重置已断开的）
                        if (sshSession?.isConnected != true) {
                            runCatching { sshSession?.disconnect() }
                            sshSession = null
                        }
                    }
                    if (!running.get() || gen != generation.get()) return
                    attempts++
                    if (attempts >= RETRY_COUNT || e is kotlinx.coroutines.CancellationException) {
                        state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                        return
                    }
                    delay(RETRY_DELAY_MS)
                }
            }
        } finally {
            if (gen == generation.get()) {
                // 仍是当前会话：正常清理全部共享状态
                running.set(false)
                controlOut = null
                videoAvailable.value = false
                controlAvailable.value = false
                closeSockets()
                runCatching { sshSession?.disconnect() }
                sshSession = null
            }
        }
    }

    /**
     * 单次会话：协商通道 → 读取设备元数据 → 按可用通道运行主循环。
     *
     * 设备名由第一条可用通道（video → audio → control）携带，video 缺失时
     * audio/control socket 最前面是 64 字节设备名，其后才是各自的流数据。
     * 主循环选择：video 存在时为 videoLoop（其余通道为子协程）；video 缺失时
     * 依次回退 audioLoop / controlLoop，主循环异常上抛触发重试，
     * 子协程异常被 runCatching 吞掉仅记录（排水职责）。
     */
    private suspend fun runSession(gen: Long) {
        val channels = negotiateChannels()

        // 设备名在第一条可用通道上（video 缺失时为 audio/control）
        val firstSocket = channels.videoSocket ?: channels.audioSocket ?: channels.controlSocket!!
        val firstIn = DataInputStream(
            BufferedInputStream(firstSocket.getInputStream(), 64 * 1024)
        )
        // 协商后元数据读取加超时：server 异常/通道错位时快速失败走重试，
        // 否则 readFully 无超时会永久阻塞在"连接中"
        firstSocket.soTimeout = NEGOTIATION_TIMEOUT_MS
        val deviceName = readDeviceName(firstIn)
        var videoMime: String? = null
        if (channels.videoSocket != null) {
            val videoCodecId = firstIn.readInt()
            videoMime = videoMimeFor(videoCodecId)
                ?: throw IOException("unsupported video codec: 0x${videoCodecId.toString(16)}")
        }
        firstSocket.soTimeout = 0

        // 僵尸会话守卫：stop() 与本会话的 negotiateChannels/元数据读取之间
        // 存在不可中断窗口（socket.connect 阻塞），旧会话可能在此后才走到
        // 这里——若无守卫，它会覆盖新会话的 state/controlOut（触摸注入到
        // 已死 socket）。守卫命中时自行关闭本次协商的 socket 后退出
        if (gen != generation.get() || !running.get()) {
            listOfNotNull(
                channels.videoSocket, channels.audioSocket, channels.controlSocket
            ).forEach {
                runCatching { it.close() }
                sockets.remove(it)
            }
            return
        }

        state.value = State.Running(deviceName)
        videoAvailable.value = channels.videoSocket != null

        // 视频模式需等待 viewer 提供渲染 Surface（此前 server 推送的数据暂存于 TCP 缓冲）；
        // 纯音频/纯控制模式不渲染，无需等待
        if (channels.videoSocket != null) {
            while (running.get() && surface == null) delay(100)
            if (!running.get()) return
        }

        // controlOut 赋值同样带 gen 守卫（与上方守卫之间的微小窗口）
        channels.controlSocket?.let {
            if (gen == generation.get()) controlOut = it.getOutputStream()
        }
        controlAvailable.value = channels.controlSocket != null && gen == generation.get()

        var audioJob: Job? = null
        var controlJob: Job? = null
        try {
            when {
                channels.videoSocket != null -> {
                    // 接收端未启用的通道也要保持排水（读丢弃），否则 TCP 背压会阻塞发送端
                    audioJob = channels.audioSocket?.let {
                        scope.launch {
                            runCatching {
                                audioLoop(
                                    DataInputStream(BufferedInputStream(it.getInputStream(), 64 * 1024))
                                )
                            }
                        }
                    }
                    controlJob = channels.controlSocket?.let {
                        scope.launch {
                            runCatching {
                                controlLoop(DataInputStream(BufferedInputStream(it.getInputStream())))
                            }
                        }
                    }
                    videoLoop(channels.videoSocket, firstIn, videoMime!!)
                }

                channels.audioSocket != null -> {
                    // 仅音频（+可选控制）：audioLoop 为主循环
                    controlJob = channels.controlSocket?.let {
                        scope.launch {
                            runCatching {
                                controlLoop(DataInputStream(BufferedInputStream(it.getInputStream())))
                            }
                        }
                    }
                    audioLoop(firstIn)
                }

                else -> {
                    // 仅控制：controlLoop 为主循环
                    controlLoop(firstIn)
                }
            }
        } finally {
            audioJob?.cancel()
            controlJob?.cancel()
            if (gen == generation.get()) {
                controlOut = null
                controlAvailable.value = false
            }
        }
    }

    private fun readDeviceName(input: DataInputStream): String {
        val buffer = ByteArray(DEVICE_NAME_LENGTH)
        input.readFully(buffer)
        // 设备名恰好占满 64 字节时无 0 终止符
        val end = buffer.indexOf(0).let { if (it == -1) buffer.size else it }
        return String(buffer, 0, end).trim()
    }

    private fun videoMimeFor(codecId: Int): String? = when (codecId) {
        CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        CODEC_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        CODEC_VP8 -> MediaFormat.MIMETYPE_VIDEO_VP8
        CODEC_VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
        else -> null
    }

    // ---------------------------------------------------------------- 视频

    private fun videoLoop(socket: Socket, input: DataInputStream, mime: String) {
        val header = ByteArray(12)
        var frameBuffer = ByteArray(64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        var codec: MediaCodec? = null
        // 解码拥塞处理：连续拿不到输入 buffer 说明解码跟不上，帧在堆积。
        // 此时请求发送端 RESET_VIDEO（产生新 config+关键帧），并丢弃积压帧
        // 直到新 config 到达（重建解码器），把延迟拉回来。
        var congestedFrames = 0
        var waitingForReset = false
        var droppedSinceReset = 0
        try {
            while (running.get()) {
                input.readFully(header)
                val ptsAndFlags = readLongBE(header, 0)
                // session meta（分辨率变化）：记录尺寸供 UI 宽高比适配与触摸坐标映射，
                // 其余交给解码器自行从码流获取
                if (ptsAndFlags and PACKET_FLAG_SESSION != 0L) {
                    // 布局：flags(4) + width(4) + height(4)，width 位于 header[4..7]
                    val w = readIntBE(header, 4)
                    val h = readIntBE(header, 8)
                    if (w > 0 && h > 0) videoSize.value = intArrayOf(w, h)
                    continue
                }
                val isConfig = ptsAndFlags and PACKET_FLAG_CONFIG != 0L
                val size = readIntBE(header, 8)
                checkPacketSize(size)
                if (frameBuffer.size < size) frameBuffer = ByteArray(size)
                input.readFully(frameBuffer, 0, size)

                if (isConfig) {
                    // CSD 包（H264 为 SPS+PPS）：（重新）配置解码器；
                    // 拥塞恢复点：新 config 到达，重建解码器后积压清零
                    waitingForReset = false
                    congestedFrames = 0
                    droppedSinceReset = 0
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
                    format.setByteBuffer(
                        // MediaFormat.KEY_CSD_0 已从新 SDK 中移除，值为 "csd-0"
                        "csd-0", ByteBufferWrap(frameBuffer, size)
                    )
                    // 先创建局部变量再 configure：configure 抛异常（CSD 损坏/
                    // surface 失效）时已创建的实例逃逸——每次重试泄漏一个解码器
                    val created = MediaCodec.createDecoderByType(mime)
                    runCatching {
                        created.configure(format, surface, null, 0)
                        created.start()
                    }.onFailure {
                        runCatching { created.release() }
                        throw it
                    }
                    codec = created
                } else if (waitingForReset) {
                    // 等待 RESET_VIDEO 产生的新 config：丢弃旧帧快速排水。
                    // 保险阀：reset 请求失败（无控制通道等）时避免无限丢帧
                    droppedSinceReset++
                    if (droppedSinceReset > 300) {
                        waitingForReset = false
                        droppedSinceReset = 0
                    }
                } else {
                    val c = codec ?: continue
                    val pts = ptsAndFlags and PTS_MASK
                    val index = c.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        congestedFrames = 0
                        val inputBuffer = c.getInputBuffer(index)!!
                        inputBuffer.clear()
                        inputBuffer.put(frameBuffer, 0, size)
                        c.queueInputBuffer(index, 0, size, pts, 0)
                        drainAndRender(c, bufferInfo)
                    } else {
                        congestedFrames++
                        if (congestedFrames >= 30) {
                            sendEmptyEvent(TYPE_RESET_VIDEO)
                            waitingForReset = true
                            droppedSinceReset = 0
                        }
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun drainAndRender(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                codec.releaseOutputBuffer(index, true)
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
            // INFO_OUTPUT_FORMAT_CHANGED：继续取下一帧
        }
    }

    // ---------------------------------------------------------------- 音频

    /** 解码器连续重建计数（drainAudio 产出输出时清零） */
    private var audioRebuildCount = 0

    private fun audioLoop(input: DataInputStream) {
        val codecId = input.readInt()
        if (codecId == CODEC_RAW) {
            // raw 流：发送端直传 PCM（48kHz 立体声 16bit），无需解码，
            // 直接写 AudioTrack。实测部分设备 Opus 解码器组件故障
            // （queueInputBuffer 抛 ISE），raw 彻底绕开解码器。
            rawAudioLoop(input)
            return
        }
        if (codecId != CODEC_OPUS) {
            // 非 OPUS/RAW（flac 等）暂不支持播放：
            // 持续读取并丢弃，防止 TCP 背压阻塞发送端
            discardLoop(input)
            return
        }

        val header = ByteArray(12)
        var packetBuffer = ByteArray(16 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        // config 包缓存：解码器中途崩溃（IllegalStateException 等）时，
        // 发送端不会重发 config，必须用缓存重建解码器，否则音频永久中断
        var configBytes: ByteArray? = null

        try {
            while (running.get()) {
                input.readFully(header)
                val ptsAndFlags = readLongBE(header, 0)
                if (ptsAndFlags and PACKET_FLAG_SESSION != 0L) continue
                val isConfig = ptsAndFlags and PACKET_FLAG_CONFIG != 0L
                val size = readIntBE(header, 8)
                checkPacketSize(size)
                if (packetBuffer.size < size) packetBuffer = ByteArray(size)
                input.readFully(packetBuffer, 0, size)

                if (isConfig) {
                    // config 包为 OpusHead：magic(8) version(1) channels(1) preskip(2)…
                    configBytes = packetBuffer.copyOf(size)
                    audioRebuildCount = 0
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    runCatching { track?.release() }
                    val created = createOpusDecoder(configBytes)
                    codec = created?.first
                    track = created?.second?.also { it.play() }
                    if (codec == null || track == null) {
                        discardLoop(input)
                        return
                    }
                } else {
                    val pts = ptsAndFlags and PTS_MASK
                    try {
                        val c = codec ?: continue
                        val t = track ?: continue
                        val index = c.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val inputBuffer = c.getInputBuffer(index) ?: continue
                            inputBuffer.clear()
                            inputBuffer.put(packetBuffer, 0, size)
                            c.queueInputBuffer(index, 0, size, pts, 0)
                        }
                        // 无论是否成功入队都排水：输入满时输出也需及时取走
                        drainAudio(c, t, bufferInfo)
                    } catch (e: IllegalStateException) {
                        // 自愈：部分设备解码器组件启动后进入错误态，所有调用抛无消息 ISE。
                        // 丢弃当前包，用缓存 config 重建（优先软件解码器）后继续解码
                        audioRebuildCount++
                        if (audioRebuildCount > 50) {
                            runCatching { track?.release() }
                            runCatching { codec?.release() }
                            discardLoop(input)
                            return
                        }
                        runCatching { codec?.stop() }
                        runCatching { codec?.release() }
                        runCatching { track?.release() }
                        codec = null
                        track = null
                        val cb = configBytes
                        if (cb != null) {
                            val created = createOpusDecoder(cb)
                            codec = created?.first
                            track = created?.second?.also { it.play() }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { track?.release() }
        }
    }

    /**
     * raw 音频流处理：发送端直传 PCM（48kHz 立体声 16bit，包格式与其他流一致：
     * 12 字节 header + 载荷），无需解码，直接写 AudioTrack。
     */
    private fun rawAudioLoop(input: DataInputStream) {
        val header = ByteArray(12)
        var packetBuffer = ByteArray(16 * 1024)
        var track: AudioTrack? = null
        try {
            while (running.get()) {
                input.readFully(header)
                val ptsAndFlags = readLongBE(header, 0)
                if (ptsAndFlags and PACKET_FLAG_SESSION != 0L) continue
                val isConfig = ptsAndFlags and PACKET_FLAG_CONFIG != 0L
                val size = readIntBE(header, 8)
                checkPacketSize(size)
                if (packetBuffer.size < size) packetBuffer = ByteArray(size)
                input.readFully(packetBuffer, 0, size)
                if (isConfig) continue

                if (track == null) {
                    // raw 流无 config 包，采样格式由协议固定（48kHz 立体声 16bit）
                    track = buildAudioTrack(2).also { it.play() }
                }
                val buffer = ByteBuffer.wrap(packetBuffer, 0, size)
                track.write(buffer, size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            runCatching { track?.release() }
        }
    }

    /**
     * 创建 Opus 解码器 + AudioTrack。
     *
     * 优先使用 Google 软件解码器 "c2.android.opus.decoder"：实测部分设备的
     * 默认解码器（createDecoderByType 按优先级选中厂商组件）启动后立即进入
     * 错误态，queueInputBuffer/dequeueInputBuffer 全部抛无消息 IllegalStateException，
     * 且无任何输出。软件解码器存在于所有 Android 8+ 设备，无厂商魔改，最稳。
     * [configBytes] 为缓存的 OpusHead（发送端 config 包，19 字节）。
     */
    private fun createOpusDecoder(configBytes: ByteArray): Pair<MediaCodec, AudioTrack>? {
        val channels = configBytes[9].toInt() and 0xFF
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(configBytes))

        // 候选依次尝试：软件解码器 → 系统默认选择。
        // 延迟创建（前一个失败释放后才创建下一个）：同时创建两个候选时，
        // 第一个成功即 return，第二个永不 release——每次重建（分辨率变化/
        // ISE 自愈最多 50 次）泄漏一个 codec 实例，长会话耗尽系统解码器
        val factories = listOf<(MediaFormat) -> MediaCodec>(
            { MediaCodec.createByCodecName("c2.android.opus.decoder") },
            { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) }
        )
        for (factory in factories) {
            val c = runCatching { factory(format) }.getOrNull() ?: continue
            val ok = runCatching {
                c.configure(format, null, null, 0)
                c.start()
            }.isSuccess
            if (ok) {
                return c to buildAudioTrack(channels)
            }
            runCatching { c.release() }
        }
        return null
    }

    private fun buildAudioTrack(channels: Int): AudioTrack {
        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(
            48000, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(channelMask)
                .build(),
            (minBuffer * 4).coerceAtLeast(16 * 1024),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    private fun drainAudio(codec: MediaCodec, track: AudioTrack, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                val outputBuffer = codec.getOutputBuffer(index) ?: break
                // 解码器正常产出：连续重建计数清零
                audioRebuildCount = 0
                track.write(outputBuffer, info.size, AudioTrack.WRITE_BLOCKING)
                codec.releaseOutputBuffer(index, false)
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 继续取下一帧
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }
    }

    /** 持续读取并丢弃流数据，防止发送端因 TCP 背压而阻塞 */
    private fun discardLoop(input: DataInputStream) {
        val header = ByteArray(12)
        val skipBuffer = ByteArray(16 * 1024)
        while (running.get()) {
            input.readFully(header)
            val size = readIntBE(header, 8)
            // 与其余三个读循环一致：负值/超限 size 不校验会使跳过计数为负
            //（内层 while 直接跳过），流错位后无限读垃圾直到 EOF——
            // 排水循环失去"快速失败走重试"的语义
            checkPacketSize(size)
            var remaining = size
            while (remaining > 0) {
                val n = input.read(skipBuffer, 0, minOf(remaining, skipBuffer.size))
                if (n < 0) return
                remaining -= n
            }
        }
    }

    // ---------------------------------------------------------------- 控制通道

    /**
     * 读取 server 下发的 DeviceMessage（与 DeviceMessageReader 的线格式一致）：
     * type 0 剪贴板文本（length + UTF-8）→ 写入 [clipboardContent]；
     * type 1 ack clipboard（sequence）与 type 2 uhid output → 读取丢弃。
     * 不读取会导致 server 端写阻塞。
     */
    private fun controlLoop(input: DataInputStream) {
        val skipBuffer = ByteArray(8 * 1024)
        fun skipFully(count: Int) {
            var remaining = count
            while (remaining > 0) {
                val n = input.read(skipBuffer, 0, minOf(remaining, skipBuffer.size))
                if (n < 0) throw IOException("control socket closed")
                remaining -= n
            }
        }

        while (running.get()) {
            when (val type = input.read()) {
                -1 -> return
                0 -> {
                    val length = input.readInt()
                    // 发送端协议上限 256K；超限/负值 = 恶意或损坏流，
                    // 巨大值 ByteArray 直接 OOM 崩溃（Error 不被 catch 捕获）
                    if (length < 0 || length > 256 * 1024) {
                        throw IOException("implausible clipboard length: $length")
                    }
                    val data = ByteArray(length)
                    input.readFully(data)
                    clipboardContent.value = String(data, Charsets.UTF_8)
                }
                1 -> skipFully(8)                     // ack clipboard
                2 -> { skipFully(2); skipFully(input.readUnsignedShort()) } // uhid output
                3 -> {
                    // 注入失败上报：发送端无法注入输入事件（权限被拒等）。
                    // 上限与 type-0 一致（256K）：服务端 DeviceMessageWriter
                    // 对 type-3 同样按 CLIPBOARD_TEXT_MAX_LENGTH（256K-5）截断，
                    // 接收端 1024 会把超长错误文案误判为流损坏断连重连
                    val length = input.readInt()
                    if (length < 0 || length > 256 * 1024) {
                        throw IOException("implausible error length: $length")
                    }
                    val data = ByteArray(length)
                    input.readFully(data)
                    injectError.value = String(data, Charsets.UTF_8)
                }
                else -> throw IOException("unknown device message type: $type")
            }
        }
    }

    // ---------------------------------------------------------------- 控制消息发送

    /**
     * 注入触摸事件（与 ControlMessageReader.parseInjectTouchEvent 的线格式一致）：
     * type(1) + action(1) + pointerId(8) + x(4) + y(4)
     * + screenWidth(2) + screenHeight(2) + pressure(2) + actionButton(4) + buttons(4)
     *
     * 坐标为视频坐标系；[screenWidth]/[screenHeight] 传视频尺寸，
     * server 端 PositionMapper 会映射到真实屏幕。
     * 多指时每根手指传入不同的 [pointerId]（负数递减分配，-1 为鼠标保留）。
     *
     * @param action MotionEvent.ACTION_DOWN=0 / ACTION_UP=1 / ACTION_MOVE=2
     */
    fun sendTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float
    ) {
        postControl("touch") { buffer ->
            buffer.writeByte(TYPE_INJECT_TOUCH_EVENT)
            buffer.writeByte(action)
            buffer.writeLong(pointerId)
            buffer.writeInt(x)
            buffer.writeInt(y)
            buffer.writeShort(screenWidth)
            buffer.writeShort(screenHeight)
            // u16 定点压力：0xffff ↔ 1f
            buffer.writeShort((pressure.coerceIn(0f, 1f) * 0xffff).toInt())
            buffer.writeInt(0) // actionButton
            buffer.writeInt(0) // buttons（手指事件下 server 强制清零）
        }
    }

    /**
     * 注入滚动事件（与 parseInjectScrollEvent 的线格式一致）：
     * type(1) + x(4) + y(4) + screenWidth(2) + screenHeight(2)
     * + hScroll(2) + vScroll(2) + buttons(4)
     */
    fun sendScroll(x: Int, y: Int, screenWidth: Int, screenHeight: Int, hScroll: Float, vScroll: Float) {
        postControl("scroll") { buffer ->
            buffer.writeByte(TYPE_INJECT_SCROLL_EVENT)
            buffer.writeInt(x)
            buffer.writeInt(y)
            buffer.writeShort(screenWidth)
            buffer.writeShort(screenHeight)
            // i16 定点，实际范围 [-16, 16]，编码前除以 16
            buffer.writeShort(toI16FixedPoint(hScroll / 16))
            buffer.writeShort(toI16FixedPoint(vScroll / 16))
            buffer.writeInt(0) // buttons
        }
    }

    /**
     * 注入按键事件（与 parseInjectKeycode 的线格式一致）：
     * type(1) + action(1) + keycode(4) + repeat(4) + metaState(4)
     */
    fun sendKeycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0) {
        postControl("keycode") { buffer ->
            buffer.writeByte(TYPE_INJECT_KEYCODE)
            buffer.writeByte(action)
            buffer.writeInt(keycode)
            buffer.writeInt(repeat)
            buffer.writeInt(metaState)
        }
    }

    /** 便捷方法：完整的按键按下-抬起 */
    fun sendKey(keycode: Int) {
        sendKeycode(ACTION_DOWN, keycode)
        sendKeycode(ACTION_UP, keycode)
    }

    /** 无载荷控制消息（展开通知栏、收起面板、旋转设备等） */
    fun sendEmptyEvent(type: Int) {
        postControl("emptyEvent") { buffer ->
            buffer.writeByte(type)
        }
    }

    /**
     * 注入文本（与 parseInjectText 的线格式一致）：type(1) + length(4) + UTF-8 字节。
     * 超过 [INJECT_TEXT_MAX_LENGTH] 时自动分段发送。
     */
    fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return
        postControl("text") { buffer ->
            var offset = 0
            while (offset < bytes.size) {
                // 从上限处回退到 UTF-8 字符边界，避免拆出非法序列
                var end = minOf(offset + INJECT_TEXT_MAX_LENGTH, bytes.size)
                while (end > offset && end < bytes.size &&
                    (bytes[end].toInt() and 0xC0) == 0x80
                ) end--

                buffer.writeByte(TYPE_INJECT_TEXT)
                buffer.writeInt(end - offset)
                buffer.write(bytes, offset, end - offset)
                offset = end
            }
        }
    }

    /**
     * 把文本设置到发送端剪贴板（与 parseSetClipboard 的线格式一致）：
     * type(1) + sequence(8) + paste(1) + length(4) + UTF-8 字节。
     * sequence 传 0（无效）则发送端不会回 ack；[paste] 为 true 时发送端立即触发粘贴。
     */
    fun sendSetClipboard(text: String, paste: Boolean) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        postControl("setClipboard") { buffer ->
            buffer.writeByte(TYPE_SET_CLIPBOARD)
            // sequence 无效，不请求 ack
            buffer.writeLong(0)
            buffer.writeByte(if (paste) 1 else 0)
            buffer.writeInt(bytes.size)
            buffer.write(bytes)
        }
    }

    /**
     * 请求发送端剪贴板内容（与 parseGetClipboard 的线格式一致）：type(1) + copyKey(1)。
     * 内容通过 DeviceMessage(type 0) 异步返回，见 [clipboardContent]。
     */
    fun sendGetClipboard() {
        postControl("getClipboard") { buffer ->
            buffer.writeByte(TYPE_GET_CLIPBOARD)
            buffer.writeByte(0) // COPY_KEY_NONE
        }
    }

    private fun toI16FixedPoint(value: Float): Int =
        (value.coerceIn(-1f, 1f) * 0x7fff).toInt()

    // ---------------------------------------------------------------- 工具

    private fun readLongBE(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readIntBE(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF shl 24) or
                (buffer[offset + 1].toInt() and 0xFF shl 16) or
                (buffer[offset + 2].toInt() and 0xFF shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
    }

    private fun ByteBufferWrap(buffer: ByteArray, size: Int): ByteBuffer =
        ByteBuffer.wrap(buffer.copyOf(size))
}