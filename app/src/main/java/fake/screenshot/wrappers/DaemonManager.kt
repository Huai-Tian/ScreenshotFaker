package fake.screenshot.wrappers

import android.content.Context
import android.os.Environment
import fake.screenshot.Auxiliary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.milliseconds

object DaemonManager {
    private const val VERSION = "4.1"
    private lateinit var appContext: Context
    private val mutex = Mutex()

    /** sh 安全引用：单引号包裹，内部单引号转义为 '\''（与 ScreenShareManager 一致） */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // 缓存密钥（DK 由 EncryptManager 经 Keystore 包裹管理，进程内复用）
    private var cachedKey: SecretKeySpec? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private suspend fun getPort(): Int {
        return ConfigManager.getDataOnce(
            appContext,
            "daemon_socket_port",
            1234
        )
    }

    private fun getKey(): SecretKeySpec =
        cachedKey ?: EncryptManager.getOrCreateDaemonKey().also { cachedKey = it }

    suspend fun startDaemon(): Boolean = mutex.withLock {
        if (isDaemonRunning()) return true

        val port = getPort()

        // 检查端口是否被其他进程占用（仅连接测试，不发送数据）
        try {
            Socket("127.0.0.1", port).use {
                // 能连接说明端口被占用，且不是我们的守护进程（因为 isDaemonRunning 已返回 false）
                return false
            }
        } catch (_: Exception) {
            // 连接失败，端口空闲，继续启动
        }

        withContext(Dispatchers.IO) {
            // 密钥经 stdin 递交（不经 argv，避免 cmdline 泄露），
            // 命令行仅含二进制路径与端口
            val key = getKey()
            val daemonPath = "${appContext.applicationInfo.nativeLibraryDir}/libdaemon.so"
            val (exitCode, _) = Auxiliary.execWithStdin("$daemonPath $port", key.encoded)
            if (exitCode != 0) {
                return@withContext false
            }
        }

        // 等待守护进程启动，最多重试20次（每次间隔100ms，共2秒）
        repeat(20) {
            if (isDaemonRunning()) {
                syncConfig()
                return true
            }
            delay(100.milliseconds)
        }
        return false
    }

    suspend fun stopDaemon(): Boolean = mutex.withLock {
        sendCommand("stop") ?: return !isDaemonRunning()
        // 发送成功，等待进程退出
        repeat(20) {
            if (!isDaemonRunning()) return true
            delay(100.milliseconds)
        }
        return false
    }

    suspend fun detachDaemon(): Boolean = mutex.withLock {
        sendCommand("detach") ?: return !isDaemonRunning()
        // 发送成功，等待进程退出
        repeat(20) {
            if (!isDaemonRunning()) return true
            delay(100.milliseconds)
        }
        return false
    }

    suspend fun isDaemonRunning() = sendCommand("status")?.startsWith("Working") ?: false

    suspend fun sendCommand(command: String, retries: Int = 3): String? {
        var attempt = 0
        while (attempt < retries) {
            val result = withContext(Dispatchers.IO) context@{
                try {
                    val port = getPort()
                    val key = getKey()
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 3000
                        // 1. 构造并发送加密命令
                        val timestamp = Auxiliary.getCurrentTimestampSeconds()
                        val plaintext = "$command\u001C$timestamp"
                        val (nonce, ciphertext) = EncryptManager.encryptByPassword(key, plaintext)

                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeInt(ciphertext.size + nonce.size)
                        out.write(nonce)
                        out.write(ciphertext)
                        out.flush()

                        // 2. 读取响应
                        val `in` = DataInputStream(socket.getInputStream())
                        val respLen = `in`.readInt()
                        if (respLen <= 0) return@context null
                        val respData = ByteArray(respLen)
                        `in`.readFully(respData)

                        // 3. 解密响应
                        val respNonce = respData.sliceArray(0 until 12)
                        val respCiphertext = respData.sliceArray(12 until respData.size)
                        val plainResponse =
                            EncryptManager.decryptByPassword(key, respNonce, respCiphertext)

                        // 4. 如果是错误响应，返回 null 以便重试
                        if (plainResponse == "Decryption failed") {
                            return@context null
                        }

                        // 5. 验证格式和时间戳
                        val parts = plainResponse.split('\u001C')
                        if (parts.size != 2) return@context null
                        val responseCommand = parts[0]
                        val responseTimestamp = parts[1].toLongOrNull()
                        if (responseTimestamp == null || !Auxiliary.isTimestampValid(
                                responseTimestamp
                            )
                        ) {
                            return@context null
                        }
                        return@context responseCommand
                    }
                } catch (_: Exception) {
                    return@context null
                }
            }

            if (result != null) {
                return result
            }
            attempt++
            if (attempt < retries) {
                delay(200.milliseconds) // 增加延迟到 200ms
            }
        }
        return null
    }

    suspend fun syncConfig(): Boolean {
        if (!isDaemonRunning()) return false
        val separator = ConfigManager.getDataOnce(appContext, "daemon_config_separator", "#")
        val screenshot =
            ConfigManager.getDataOnce(appContext, "daemon_screenshot_config", "").split(separator)
                .joinToString("\u001F")
        val screenRecord =
            ConfigManager.getDataOnce(appContext, "daemon_screenRecord_config", "").split(separator)
                .joinToString("\u001F")
        val screenShare =
            ConfigManager.getDataOnce(appContext, "daemon_screenshare_config", "").split(separator)
                .joinToString("\u001F")
        val screenshotCommand = suspend {
            val savePath = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenshot_save_path",
                defaultValue = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_SCREENSHOTS
                ).path
            )
            val suffix = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenshot_suffix",
                defaultValue = ".png"
            )
            val displayID = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenshot_display_id",
                defaultValue = ""
            ).let { if (it.isEmpty()) "" else "-d $it" }
            listOf(
                "screencap",
                "-p",
                displayID,
                savePath,
                suffix
            ).filter { it.isNotEmpty() }.joinToString("\u001F")
        }
        val screenRecordCommand = suspend {
            val savePath = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_save_path",
                defaultValue = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).path
            )
            val duration = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_duration",
                defaultValue = "180"
            )
            val suffix = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_suffix",
                defaultValue = ".mp4"
            )
            val displayID = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_display_id",
                defaultValue = ""
            ).let { if (it.isEmpty()) "" else "--display-id $it" }
            val bitrate = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_bitrate",
                defaultValue = ""
            ).let { if (it.isEmpty()) "" else "--bit-rate $it" }
            val resolution = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_resolution",
                defaultValue = ""
            ).let { if (it.isEmpty()) "" else "--size $it" }
            val bugreport = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_bugreport",
                defaultValue = false
            ).let { if (it) "--bugreport" else "" }
            listOf(
                "screenrecord",
                "--time-limit", duration,
                displayID,
                bitrate,
                resolution,
                bugreport,
                savePath,
                suffix
            ).filter { it.isNotEmpty() }.joinToString("\u001F")
        }
        val screenShareCommand = suspend {
            val localPort = ConfigManager.getDataOnce(appContext, "screenShare_port", 2345)
            val sshEnabled = ConfigManager.getDataOnce(appContext, "ssh_tunnel_enabled", false)
            val enableControl = ConfigManager.getDataOnce(appContext, "screenShare_control", true)
                .let { "control=$it" }
            val syncClipboard =
                ConfigManager.getDataOnce(appContext, "screenShare_sync_clipboard", true)
                    .let { "clipboard_autosync=$it" }
            val enableVideo =
                ConfigManager.getDataOnce(appContext, "screenShare_video", true).let { "video=$it" }
            val videoDisplay =
                ConfigManager.getDataOnce(appContext, "screenShare_video_display", true)
                    .let { if (it) "video_source=display" else "" }
            val videoDisplayID =
                ConfigManager.getDataOnce(appContext, "screenShare_video_display_id", "")
                    .let { if (it.isEmpty()) "" else "display_id=$it" }
            val videoCamera =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera", false)
                    .let { if (it) "video_source=camera" else "" }
            val videoCameraID =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera_id", "0")
                    .let { "camera_id=$it" }
            val videoCameraZoom =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera_zoom", "")
                    .let { if (it.isEmpty()) "" else "camera_zoom=$it" }
            val videoCameraTorch =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera_torch", false)
                    .let { "camera_torch=$it" }
            // 限制分辨率/帧率，降低编码与传输延迟（0 表示不限制）
            val maxSize = ConfigManager.getDataOnce(appContext, "screenShare_max_size", 1280)
                .let { if (it > 0) "max_size=$it" else "" }
            val maxFps = ConfigManager.getDataOnce(appContext, "screenShare_max_fps", 60)
                .let { if (it > 0) "max_fps=$it" else "" }
            // 视频比特率：过高会加大编码与传输延迟，0 表示使用 server 默认值
            val videoBitRate =
                ConfigManager.getDataOnce(appContext, "screenShare_video_bit_rate", 4000000)
                    .let { if (it > 0) "video_bit_rate=$it" else "" }
            val enableAudio =
                ConfigManager.getDataOnce(appContext, "screenShare_audio", true).let { "audio=$it" }
            // 与 ScreenShareManager 保持一致：
            // - playback + audio_dup=true：设备继续外放的同时复制一份音频流到捕获，音量不受影响
            // - raw（PCM 直传）：绕开部分接收设备 Opus 解码器的兼容性问题
            val audioDup = "audio_dup=true"
            val audioCodec = "audio_codec=raw"
            val audioOutput =
                ConfigManager.getDataOnce(appContext, "screenShare_audio_output", true)
                    .let { if (it) "audio_source=playback" else "" }
            val audioMic = ConfigManager.getDataOnce(appContext, "screenShare_audio_mic", false)
                .let { if (it) "audio_source=mic" else "" }
            // SSH 隧道模式下 server 只监听回环，防止局域网直连绕过隧道
            val tcpLocalOnly = if (sshEnabled) "tcp_local_only=true" else ""
            val authPassword =
                ConfigManager.getDataOnce(appContext, "screenShare_password", "")
                    .let { if (it.isEmpty()) "" else "auth_password=${shellQuote(it)}" }
            val base =
                "CLASSPATH=/data/local/tmp/FullRandomName app_process / fake.screenshot.core.Relay $VERSION tunnel_forward=true tcp_port=$localPort"

            listOf(
                base,
                enableControl,
                syncClipboard,
                enableVideo,
                videoDisplay,
                videoDisplayID,
                videoCamera,
                videoCameraID,
                videoCameraZoom,
                videoCameraTorch,
                maxSize,
                maxFps,
                videoBitRate,
                enableAudio,
                audioDup,
                audioCodec,
                audioOutput,
                audioMic,
                tcpLocalOnly,
                authPassword
            ).filter { it.isNotEmpty() }.joinToString("\u001F")
        }
        val sshOptions = suspend {
            val enabled = ConfigManager.getDataOnce(
                appContext,
                "ssh_tunnel_enabled",
                false
            )
            val address =
                ConfigManager.getDataOnce(appContext, "ssh_tunnel_server_address", "127.0.0.1")
            val port = ConfigManager.getDataOnce(appContext, "ssh_tunnel_server_port", 22)
            val name = ConfigManager.getDataOnce(
                appContext, "ssh_tunnel_user_name",
                "ScreenshotFaker"
            )
            val password = ConfigManager.getDataOnce(
                appContext, "ssh_tunnel_user_password",
                "ScreenshotFaker"
            )
            val remotePort = ConfigManager.getDataOnce(appContext, "ssh_tunnel_remote_port", 0)
            listOf(enabled, address, port, name, password, remotePort).joinToString("\u001F")
        }
        val otherOptions = suspend {
            val relayPath =
                "${appContext.applicationInfo.nativeLibraryDir}/libscrcpy-server.so"
            val autoEncrypt =
                "${ConfigManager.getDataOnce(appContext, "encrypt_outputs", false)}"
            val definedTimestamp =
                ConfigManager.getDataOnce(appContext, "defined_timestamp", "").trim()
            listOf(relayPath, autoEncrypt, definedTimestamp).joinToString("\u001F")
        }
        val command =
            "config$screenshot\u001E$screenRecord\u001E$screenShare\u001D${screenshotCommand()}\u001E${screenRecordCommand()}\u001E${screenShareCommand()}\u001D${sshOptions()}\u001D${otherOptions()}"
        return sendCommand(command) == "fine"
    }
}