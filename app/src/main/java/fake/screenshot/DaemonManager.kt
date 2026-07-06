package fake.screenshot

import android.content.Context
import android.os.Environment
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
    private lateinit var appContext: Context
    private val mutex = Mutex()

    // 缓存密钥（密码不变则复用）
    private var cachedPassword: String? = null
    private var cachedKey: SecretKeySpec? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private suspend fun getPassword(): String {
        return ConfigManager.getDataOnce(
            appContext,
            "daemon_verification_password",
            "ScreenshotFaker"
        )
    }

    private suspend fun getPort(): Int {
        return ConfigManager.getDataOnce(
            appContext,
            "daemon_socket_port",
            1234
        )
    }

    private suspend fun getKey(): SecretKeySpec {
        val password = getPassword()
        return if (cachedPassword == password && cachedKey != null) {
            cachedKey!!
        } else {
            val key = Auxiliary.deriveKey(password)
            cachedPassword = password
            cachedKey = key
            key
        }
    }

    suspend fun startDaemon(): Boolean = mutex.withLock {
        if (isDaemonRunning()) return true

        val port = getPort()
        val password = getPassword()

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
            val (exitCode, _) = Auxiliary.exec("${appContext.applicationInfo.nativeLibraryDir}/daemon.so $port $password")
            if (exitCode != 0) {
                return@withContext false
            }
        }

        // 等待守护进程启动，最多重试20次（每次间隔100ms，共2秒）
        repeat(20) {
            if (isDaemonRunning()) return true
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
                        val (nonce, ciphertext) = Auxiliary.encryptData(key, plaintext)

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
                        val plainResponse = Auxiliary.decryptData(key, respNonce, respCiphertext)

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
                defaultValue = "${Environment.getExternalStorageDirectory().path}/Pictures/ScreenshotFaker/Screenshots"
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
                defaultValue = "${Environment.getExternalStorageDirectory().path}/Pictures/ScreenshotFaker/Records"
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
            ""
        }
        val command =
            "config$screenshot\u001E$screenRecord\u001E$screenShare\u001D${screenshotCommand()}\u001E${screenRecordCommand()}\u001E${screenShareCommand()}"
        return sendCommand(command) == "fine"
    }
}