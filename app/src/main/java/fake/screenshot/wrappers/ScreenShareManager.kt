package fake.screenshot.wrappers

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import fake.screenshot.Auxiliary
import fake.screenshot.R
import fake.screenshot.services.ScreenShareTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

object ScreenShareManager {
    private const val VERSION = "4.1"
    private lateinit var scrcpyName: String
    private lateinit var scrcpyJob: Job
    private var sshSession: Session? = null
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    var scrcpyRunning = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val tileListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addTileListener(listener: () -> Unit) {
        tileListeners.add(listener)
        listener()
    }

    fun removeTileListener(listener: () -> Unit) {
        tileListeners.remove(listener)
    }

    private fun notifyStateChanged() {
        tileListeners.forEach { runCatching(it) }
        if (::appContext.isInitialized) {
            runCatching {
                TileService.requestListeningState(
                    appContext,
                    ComponentName(appContext, ScreenShareTileService::class.java)
                )
            }
        }
    }

    private sealed interface InitResult {
        data object Ok : InitResult
        data class SshFailed(val reason: String) : InitResult
        data class CopyFailed(val reason: String) : InitResult
    }


    private suspend fun initializeInternal(): InitResult {
        if (initialized) return InitResult.Ok
        if (ConfigManager.getDataOnce(appContext, "ssh_tunnel_enabled", false)) {
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
            try {
                val jsch = JSch()
                val session = jsch.getSession(name, address, port)
                session.setPassword(password.toByteArray(Charsets.UTF_8))
                session.setConfig("StrictHostKeyChecking", "no")
                session.connect(8000)
                sshSession = session
            } catch (e: Exception) {
                sshSession = null
                return InitResult.SshFailed(
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
        scrcpyName = Auxiliary.getRandomStringEx((20..35).random())
        val src = "${appContext.applicationInfo.nativeLibraryDir}/libscrcpy-server.so"
        val (exitCode, output) = Auxiliary.exec("cp $src /data/local/tmp/$scrcpyName")
        if (exitCode != 0) {
            return InitResult.CopyFailed(output.take(80))
        }
        initialized = true
        return InitResult.Ok
    }

    private fun startScreenShareInternal(): Boolean {
        if (!(initialized && Auxiliary.isShellActivated)) return false
        if (scrcpyRunning) return true
        scrcpyJob = scope.launch {
            val localPort = ConfigManager.getDataOnce(appContext, "screenShare_port", 2345)
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
            // 视频比特率：过高会加大编码与传输延迟，0 表示使用 server 默认值（8Mbps）
            val videoBitRate = ConfigManager.getDataOnce(appContext, "screenShare_video_bit_rate", 4000000)
                .let { if (it > 0) "video_bit_rate=$it" else "" }
            val enableAudio =
                ConfigManager.getDataOnce(appContext, "screenShare_audio", true).let { "audio=$it" }
            val audioOutput =
                ConfigManager.getDataOnce(appContext, "screenShare_audio_output", true)
                    .let { if (it) "audio_source=output" else "" }
            val audioMic = ConfigManager.getDataOnce(appContext, "screenShare_audio_mic", false)
                .let { if (it) "audio_source=mic" else "" }
            val tcpLocalOnly =
                if (sshSession != null) "tcp_local_only=true" else ""
            val authPassword =
                ConfigManager.getDataOnce(appContext, "screenShare_password", "")
                    .let { if (it.isEmpty()) "" else "auth_password=${shellQuote(it)}" }
            val base =
                "CLASSPATH=/data/local/tmp/$scrcpyName app_process / fake.screenshot.scrcpy.Server $VERSION tunnel_forward=true tcp_port=$localPort"
            val args = listOf(
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
                audioOutput,
                audioMic,
                tcpLocalOnly,
                authPassword
            ).filter { it.isNotEmpty() }

            sshSession?.let { session ->
                val configuredRemotePort =
                    ConfigManager.getDataOnce(appContext, "ssh_tunnel_remote_port", 0)
                val remotePort =
                    if (configuredRemotePort in 1024..65535) configuredRemotePort else localPort
                // 远程转发指向本机端口，server 重启后重新监听同一端口，转发持续有效
                runCatching { session.setPortForwardingR(remotePort, "127.0.0.1", localPort) }
            }

            // 自动重启循环：接收端断开（锁屏/切后台/网络波动）会使 scrcpy server
            // 进程退出，只要共享未被停止就重新拉起，让接收端总能重新连上
            val command = args.joinToString(" ")
            var fastExits = 0
            while (scrcpyRunning) {
                val startTime = System.currentTimeMillis()
                Auxiliary.exec(command)
                if (!scrcpyRunning) break // 用户主动停止
                // 会话持续超过 30s 视为正常结束，重置快速退出计数
                if (System.currentTimeMillis() - startTime > 30_000) {
                    fastExits = 0
                } else {
                    fastExits++
                }
                // 连续快速退出说明 server 无法正常启动（端口占用等），放弃重启
                if (fastExits >= 3) {
                    lastError = "server_exited_repeatedly"
                    break
                }
                delay(1000.milliseconds)
            }
            scrcpyRunning = false
            initialized = false
            notifyStateChanged()
        }
        scrcpyRunning = true
        return true
    }

    /**
     * 磁贴/页面统一入口：异步初始化并启动/停止共享。
     * 可安全地在主线程调用；失败原因写入 [lastError] 并刷新磁贴副标题。
     */
    fun toggleScreenShare(context: Context) {
        appContext = context.applicationContext
        Auxiliary.refreshShellState()
        if (scrcpyRunning) {
            scope.launch {
                stopScreenShare()
                notifyStateChanged()
            }
            return
        }
        scope.launch {
            lastError = if (!Auxiliary.isShellActivated) {
                context.getString(R.string.no_permission)
            } else {
                when (initializeInternal()) {
                    is InitResult.SshFailed -> "ssh_connect_failed"
                    is InitResult.CopyFailed -> "copy_server_failed"
                    InitResult.Ok -> {
                        if (startScreenShareInternal()) null
                        else context.getString(R.string.initialize_failed)
                    }
                }
            }
            notifyStateChanged()
        }
    }

    fun stopScreenShare() {
        if (!scrcpyRunning) return
        scrcpyRunning = false
        Auxiliary.exec(
            "pkill -INT -f fake.screenshot.scrcpy.Server; sleep 1; pkill -KILL -f fake.screenshot.scrcpy.Server"
        )
        scrcpyJob.cancel()
        sshSession?.disconnect()
        sshSession = null
        initialized = false
    }

    /** sh 安全引用：单引号包裹，内部单引号转义为 '\'' */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}