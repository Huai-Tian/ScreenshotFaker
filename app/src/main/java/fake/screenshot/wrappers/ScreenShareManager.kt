package fake.screenshot.wrappers

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import fake.screenshot.Auxiliary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object ScreenShareManager {
    private const val VERSION = "4.1"
    private lateinit var scrcpyName: String
    private lateinit var scrcpyJob: Job
    private var sshSession: Session? = null
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    var scrcpyRunning = false

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        appContext = context
        if (runBlocking {
                if (!ConfigManager.getDataOnce(
                        appContext,
                        "ssh_tunnel_enabled",
                        false
                    )
                ) return@runBlocking true
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
                    true
                } catch (_: Exception) {
                    false
                }
            }) return false
        scrcpyName = Auxiliary.getRandomStringEx((1..12).random())
        if (Auxiliary.exec("cp ${appContext.applicationInfo.nativeLibraryDir}/libscrcpy-server.so /data/local/tmp/$scrcpyName").first != 0) return false
        initialized = true
        return true
    }

    fun startScreenShare(): Boolean {
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
            val enableAudio =
                ConfigManager.getDataOnce(appContext, "screenShare_audio", true).let { "audio=$it" }
            val audioOutput =
                ConfigManager.getDataOnce(appContext, "screenShare_audio_output", true)
                    .let { if (it) "audio_source=output" else "" }
            val audioMic = ConfigManager.getDataOnce(appContext, "screenShare_audio_mic", false)
                .let { if (it) "audio_source=mic" else "" }
            val tcpLocalOnly =
                if (sshSession != null) "tcp_local_only=true" else ""
            // 共享密码：启用后接收端连接时需先完成密码握手（直连模式的访问控制）
            val authPassword =
                ConfigManager.getDataOnce(appContext, "screenShare_password", "")
                    .let { if (it.isEmpty()) "" else "auth_password=$it" }
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
                runCatching { session.setPortForwardingR(remotePort, "127.0.0.1", localPort) }
            }

            Auxiliary.exec(args.joinToString(" "))
            scrcpyRunning = false
        }
        initialized = false
        scrcpyRunning = true
        return true
    }

    fun stopScreenShare() {
        if (!scrcpyRunning) return
        Auxiliary.exec("pkill -f $scrcpyName")
        scrcpyJob.cancel()
        scrcpyRunning = false
        sshSession?.disconnect()
        sshSession = null
    }
}