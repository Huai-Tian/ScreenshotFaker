package fake.screenshot

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ScreenShareManager {
    private const val VERSION = "4.1"
    private lateinit var scrcpyName: String
    private lateinit var scrcpyJob: Job
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    var scrcpyRunning = false

    fun initialize(context: Context): Boolean {
        if (initialized) return true
        appContext = context
        scrcpyName = Auxiliary.getRandomString(8)
        if (Auxiliary.exec("cp ${appContext.applicationInfo.nativeLibraryDir}/libscrcpy-server.so /data/local/tmp/$scrcpyName").first != 0) return false
        initialized = true
        return true
    }

    fun startScreenShare(): Boolean {
        if (!(initialized && Auxiliary.isShellActivated)) return false
        if (scrcpyRunning) return true
        scrcpyJob = scope.launch {
            val localPort = ConfigManager.getDataOnce(appContext,"screenShare_port",2345)
            val enableControl = ConfigManager.getDataOnce(appContext,"screenShare_control",true).let { "control=$it"}
            val syncClipboard = ConfigManager.getDataOnce(appContext,"screenShare_sync_clipboard",true).let { "clipboard_autosync=$it" }
            val enableVideo = ConfigManager.getDataOnce(appContext,"screenShare_video",true).let { "video=$it" }
            val videoDisplay = ConfigManager.getDataOnce(appContext,"screenShare_video_display",true).let { if (it)"video_source=display" else "" }
            val videoDisplayID = ConfigManager.getDataOnce(appContext,"screenShare_video_display_id","").let { if (it.isEmpty()) "" else "display_id=$it" }
            val videoCamera = ConfigManager.getDataOnce(appContext,"screenShare_video_camera",false).let { if (it)"video_source=camera" else "" }
            val videoCameraID = ConfigManager.getDataOnce(appContext,"screenShare_video_camera_id","0").let { "camera_id=$it" }
            val videoCameraZoom = ConfigManager.getDataOnce(appContext,"screenShare_video_camera_zoom","").let { if (it.isEmpty()) "" else "camera_zoom=$it" }
            val videoCameraTorch = ConfigManager.getDataOnce(appContext,"screenShare_video_camera_torch",false).let { "camera_torch=$it" }
            val enableAudio = ConfigManager.getDataOnce(appContext,"screenShare_audio",true).let { "audio=$it" }
            val audioOutput = ConfigManager.getDataOnce(appContext,"screenShare_audio_output",true).let { if (it)"audio_source=output" else "" }
            val audioMic = ConfigManager.getDataOnce(appContext,"screenShare_audio_mic",false).let { if (it)"audio_source=mic" else "" }
            val base = "CLASSPATH=/data/local/tmp/$scrcpyName app_process / fake.screenshot.scrcpy.Server $VERSION tunnel_forward=true tcp_port=$localPort"
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
                audioMic
                ).filter { it.isNotEmpty() }
            Auxiliary.exec(args.joinToString(" "))
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
    }
}