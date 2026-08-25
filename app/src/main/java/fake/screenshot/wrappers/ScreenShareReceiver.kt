package fake.screenshot.wrappers

import android.content.Context
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕共享接收端配置。
 *
 * [address]/[port] 的含义取决于 [useSsh]：
 * - 直连：目标设备的地址与 scrcpy TCP proxy 端口（发送端的"屏幕共享本地端口"）；
 * - SSH 隧道：SSH 服务器地址与 SSH 服务器上的远程端口。
 *   接收端登录 SSH 后建立本地端口转发（localhost:lport → SSH 服务器 127.0.0.1:port），
 *   适用于发送端通过远程转发把共享端口暴露在 SSH 服务器上的场景。
 *
 * [enableAudio]/[enableControl] 必须与发送端的"启用音频/允许控制"一致：
 * scrcpy server 按 video→audio→control 的顺序依次 accept，若开关不匹配，
 * server 会永远阻塞在 accept 上（或把 socket 错位配对），导致连接卡住或功能缺失。
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
    val enableAudio: Boolean = true,
    val enableControl: Boolean = true,
    /** 共享密码：与发送端 auth_password 一致，留空表示发送端未启用认证 */
    val password: String = ""
)

/**
 * 屏幕共享接收端。设计为普通类而非单例：每个实例接收一路共享，
 * 支持同时存在多个实例（对应多部发送设备）。
 *
 * 协议（tunnel_forward 模式，与 [fake.screenshot.scrcpy.Server] 的 TCP proxy 对接）：
 * - 按顺序建立三个 TCP 连接：video → audio → control（与 server 端
 *   DesktopConnection.open 的 accept 顺序一致）；
 * - video socket：1 字节 dummy byte + 64 字节设备名 + 4 字节 codec id + 帧流；
 * - audio socket：4 字节 codec id + 帧流；
 * - control socket：server → client 的 DeviceMessage（此处仅读取丢弃）。
 * - 帧格式：12 字节 header（8 字节 ptsAndFlags + 4 字节 packetSize）+ 载荷。
 *   bit63 = session meta（宽高变化），bit62 = config/CSD 包，bit61 = 关键帧。
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

    private companion object {
        /** scrcpy Codec id（名称的 4 字节 ASCII 大端表示） */
        const val CODEC_H264 = 0x68323634
        const val CODEC_H265 = 0x68323635
        const val CODEC_AV1 = 0x00617631
        const val CODEC_VP8 = 0x00767038
        const val CODEC_VP9 = 0x00767039
        const val CODEC_OPUS = 0x6f707573

        const val PACKET_FLAG_SESSION = 1L shl 63
        const val PACKET_FLAG_CONFIG = 1L shl 62
        const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = (1L shl 61) - 1

        const val DEVICE_NAME_LENGTH = 64
        const val CONNECT_TIMEOUT_MS = 8000
        const val SSH_TIMEOUT_MS = 8000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /** 当前会话的 socket，stop 时统一关闭以中断阻塞读 */
    private val sockets = CopyOnWriteArrayList<Socket>()
    private var sshSession: Session? = null

    /** viewer 提供的渲染 Surface（null 表示暂无可渲染目标） */
    @Volatile
    private var surface: Surface? = null

    val state = MutableStateFlow<State>(State.Idle)

    fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    fun start() {
        if (job?.isActive == true) return
        running.set(true)
        state.value = State.Connecting
        job = scope.launch { runLoop() }
    }

    fun stop() {
        running.set(false)
        closeSockets()
        runCatching { sshSession?.disconnect() }
        sshSession = null
        state.value = State.Stopped
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
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
        authenticate(socket)
        sockets.add(socket)
        return socket
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
        session.connect(SSH_TIMEOUT_MS)
        sshSession = session
        return session
    }

    private fun closeSockets() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    // ---------------------------------------------------------------- 主循环

    private suspend fun runLoop() {
        var videoSocket: Socket? = null
        var audioSocket: Socket? = null
        var controlSocket: Socket? = null
        try {
            // 连接顺序必须与 server 端 accept 顺序一致：video → audio → control
            videoSocket = openSocket()
            val videoIn = DataInputStream(
                BufferedInputStream(videoSocket.getInputStream(), 64 * 1024)
            )

            // dummy byte：server 在第一个 accept 的 socket 上写入 1 字节 0，
            // 用于让客户端检测连接错误
            videoIn.read()

            val deviceName = readDeviceName(videoIn)
            val videoCodecId = videoIn.readInt()
            val videoMime = videoMimeFor(videoCodecId)
                ?: throw IOException("unsupported video codec: 0x${videoCodecId.toString(16)}")

            if (config.enableAudio) {
                audioSocket = openSocket()
            }
            if (config.enableControl) {
                controlSocket = openSocket()
            }

            state.value = State.Running(deviceName)

            // 等待 viewer 提供渲染 Surface（此前 server 推送的数据暂存于 TCP 缓冲）
            while (running.get() && surface == null) delay(100)
            if (!running.get()) return

            val audioJob = audioSocket?.let {
                scope.launch { runCatching { audioLoop(it) } }
            }
            val controlJob = controlSocket?.let {
                scope.launch { runCatching { controlLoop(it) } }
            }

            videoLoop(videoSocket, videoIn, videoMime)

            audioJob?.cancel()
            controlJob?.cancel()
        } catch (e: Exception) {
            if (running.get()) {
                state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            running.set(false)
            closeSockets()
            runCatching { sshSession?.disconnect() }
            sshSession = null
        }
    }

    private fun readDeviceName(input: DataInputStream): String {
        val buffer = ByteArray(DEVICE_NAME_LENGTH)
        input.readFully(buffer)
        val end = buffer.indexOf(0)
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
        try {
            while (running.get()) {
                input.readFully(header)
                val ptsAndFlags = readLongBE(header, 0)
                // session meta（分辨率变化）：跳过，解码器自行从码流获取尺寸
                if (ptsAndFlags and PACKET_FLAG_SESSION != 0L) continue
                val isConfig = ptsAndFlags and PACKET_FLAG_CONFIG != 0L
                val size = readIntBE(header, 8)
                if (frameBuffer.size < size) frameBuffer = ByteArray(size)
                input.readFully(frameBuffer, 0, size)

                if (isConfig) {
                    // CSD 包（H264 为 SPS+PPS）：（重新）配置解码器
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
                    format.setByteBuffer(
                        // MediaFormat.KEY_CSD_0 已从新 SDK 中移除，值为 "csd-0"
                        "csd-0", ByteBufferWrap(frameBuffer, size)
                    )
                    codec = MediaCodec.createDecoderByType(mime).apply {
                        configure(format, surface, null, 0)
                        start()
                    }
                } else {
                    val c = codec ?: continue
                    val pts = ptsAndFlags and PTS_MASK
                    val index = c.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val inputBuffer = c.getInputBuffer(index)!!
                        inputBuffer.clear()
                        inputBuffer.put(frameBuffer, 0, size)
                        c.queueInputBuffer(index, 0, size, pts, 0)
                        drainAndRender(c, bufferInfo)
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

    private fun audioLoop(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        val codecId = input.readInt()
        if (codecId != CODEC_OPUS) {
            // 非 OPUS（raw/flac 等）暂不支持播放，但必须持续读取防止 TCP 背压阻塞发送端
            discardLoop(input)
            return
        }

        val header = ByteArray(12)
        var packetBuffer = ByteArray(16 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            while (running.get()) {
                input.readFully(header)
                val ptsAndFlags = readLongBE(header, 0)
                if (ptsAndFlags and PACKET_FLAG_SESSION != 0L) continue
                val isConfig = ptsAndFlags and PACKET_FLAG_CONFIG != 0L
                val size = readIntBE(header, 8)
                if (packetBuffer.size < size) packetBuffer = ByteArray(size)
                input.readFully(packetBuffer, 0, size)

                if (isConfig) {
                    // config 包为 OpusHead：magic(8) version(1) channels(1) preskip(2)…
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    runCatching { track?.release() }
                    val channels = packetBuffer[9].toInt() and 0xFF
                    val format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, channels
                    )
                    format.setByteBuffer(
                        // MediaFormat.KEY_CSD_0 已从新 SDK 中移除，值为 "csd-0"
                        "csd-0", ByteBufferWrap(packetBuffer, size)
                    )
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                        configure(format, null, null, 0)
                        start()
                    }
                    track = buildAudioTrack(channels).also { it.play() }
                } else {
                    val c = codec ?: continue
                    val t = track ?: continue
                    val pts = ptsAndFlags and PTS_MASK
                    val index = c.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val inputBuffer = c.getInputBuffer(index)!!
                        inputBuffer.clear()
                        inputBuffer.put(packetBuffer, 0, size)
                        c.queueInputBuffer(index, 0, size, pts, 0)
                        drainAudio(c, t, bufferInfo)
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { track?.release() }
        }
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
                val outputBuffer = codec.getOutputBuffer(index)!!
                track.write(outputBuffer, info.size, AudioTrack.WRITE_BLOCKING)
                codec.releaseOutputBuffer(index, false)
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
     * 读取并丢弃 server 下发的 DeviceMessage（剪贴板同步等）。
     * 仅当发送端启用"允许控制"时该 socket 才存在；不读取会导致 server 端写阻塞。
     */
    private fun controlLoop(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
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
                0 -> skipFully(input.readInt())       // clipboard 文本
                1 -> skipFully(8)                     // ack clipboard
                2 -> { skipFully(2); skipFully(input.readUnsignedShort()) } // uhid output
                else -> throw IOException("unknown device message type: $type")
            }
        }
    }

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

/**
 * 接收端实例与配置的管理器。
 * 配置以分隔符序列化存储于加密 DataStore（ConfigManager），
 * 支持保存任意多个接收配置（对应多部发送设备）。
 */
object ScreenShareReceiverManager {

    private const val IDS_KEY = "receive_screen_share_config_ids"
    private const val CONFIG_PREFIX = "receive_screen_share_config_"
    private const val SEPARATOR = "\u001F"

    private val receivers = ConcurrentHashMap<Int, ScreenShareReceiver>()

    suspend fun loadConfigs(context: Context): List<ScreenShareReceiverConfig> {
        return loadIds(context).mapNotNull { loadConfig(context, it) }
    }

    suspend fun loadConfig(
        context: Context,
        id: Int
    ): ScreenShareReceiverConfig? {
        val raw = ConfigManager.getDataOnce(context, CONFIG_PREFIX + id, "")
        if (raw.isEmpty()) return null
        val parts = raw.split(SEPARATOR)
        if (parts.size < 9) return null
        return runCatching {
            ScreenShareReceiverConfig(
                id = id,
                name = parts[0],
                address = parts[1],
                port = parts[2].toInt(),
                useSsh = parts[3].toBoolean(),
                sshPort = parts[4].toInt(),
                sshUserName = parts[5],
                sshPassword = parts[6],
                enableAudio = parts[7].toBoolean(),
                enableControl = parts[8].toBoolean(),
                password = parts.getOrElse(9) { "" }
            )
        }.getOrNull()
    }

    suspend fun saveConfig(
        context: Context,
        config: ScreenShareReceiverConfig
    ) {
        val raw = listOf(
            config.name, config.address, config.port.toString(), config.useSsh.toString(),
            config.sshPort.toString(), config.sshUserName, config.sshPassword,
            config.enableAudio.toString(), config.enableControl.toString(),
            config.password
        ).joinToString(SEPARATOR)
        ConfigManager.saveData(context, CONFIG_PREFIX + config.id, raw)
        val ids = loadIds(context).toMutableSet()
        ids.add(config.id)
        ConfigManager.saveData(context, IDS_KEY, ids.joinToString(","))
    }

    suspend fun deleteConfig(context: Context, id: Int) {
        ConfigManager.saveData(context, CONFIG_PREFIX + id, "")
        val ids = loadIds(context).toMutableSet()
        ids.remove(id)
        ConfigManager.saveData(context, IDS_KEY, ids.joinToString(","))
        receivers.remove(id)?.stop()
    }

    suspend fun nextId(context: Context): Int {
        return (loadIds(context).maxOrNull() ?: 0) + 1
    }

    private suspend fun loadIds(context: Context): List<Int> {
        return ConfigManager.getDataOnce(context, IDS_KEY, "")
            .split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
    }

    fun getOrCreate(config: ScreenShareReceiverConfig): ScreenShareReceiver =
        receivers.getOrPut(config.id) { ScreenShareReceiver(config) }

    fun get(id: Int): ScreenShareReceiver? = receivers[id]

    fun stopAll() {
        receivers.values.forEach { it.stop() }
    }
}
