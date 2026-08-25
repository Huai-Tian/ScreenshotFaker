package fake.screenshot.services

import android.os.Environment
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fake.screenshot.Auxiliary
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.EncryptManager
import fake.screenshot.R
import kotlinx.coroutines.*
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class ScreenRecordTileService : TileService() {
    private val tempPath = "/data/local/tmp/"

    private var isRecording = false
    private var recordPid: Int? = null
    private var clicked = false
    private var showingNoPermission = false

    private var lastEncryptOutputs = false
    private var lastSavePath = ""
    private var lastFileName = ""
    private var lastTempName = ""

    @Volatile
    private var isEncrypting = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        checkAndResetIfProcessDead()
        updateTileUI()
    }

    override fun onClick() {
        super.onClick()
        Auxiliary.refreshShellState()

        if (!Auxiliary.isShellActivated) {
            showingNoPermission = !showingNoPermission
            if (showingNoPermission) {
                clicked = false
                if (isRecording) {
                    showingNoPermission = false
                    return
                }
            }
            updateTileUI()
            return
        }

        showingNoPermission = false
        checkAndResetIfProcessDead()

        if (isRecording) {
            stopRecording()
            return
        }

        if (clicked) {
            clicked = false
            updateTileUI()
        } else {
            clicked = true
            updateTileUI()
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Auxiliary.refreshShellState()
        showingNoPermission = false
        checkAndResetIfProcessDead()

        if (clicked && Auxiliary.isShellActivated) {
            clicked = false
            serviceScope.launch {
                startRecording()
            }
            return
        }
        clicked = false
        updateTileUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun startRecording() {
        if (isRecording) {
            return
        }
        var waitCount = 0
        while (isEncrypting && waitCount < 10) {
            delay(500.milliseconds)
            waitCount++
        }
        if (isEncrypting) {
            return
        }

        val savePath = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_save_path",
            defaultValue = "${Environment.getExternalStorageDirectory().path}/Pictures/ScreenshotFaker/Records"
        )
        val duration = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_duration",
            defaultValue = "180"
        ).toIntOrNull() ?: 180
        val prefix = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_prefix",
            defaultValue = ""
        )
        val suffix = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_suffix",
            defaultValue = ".mp4"
        )
        val displayID = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_display_id",
            defaultValue = ""
        ).let { if (it.isEmpty()) "" else "--display-id $it" }
        val bitrate = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_bitrate",
            defaultValue = ""
        ).let { if (it.isEmpty()) "" else "--bit-rate $it" }
        val resolution = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_resolution",
            defaultValue = ""
        ).let { if (it.isEmpty()) "" else "--size $it" }
        val customPrefix = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_custom_prefix",
            defaultValue = false
        )
        val fullRandom = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_full_random",
            defaultValue = false
        )
        val bugreport = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_bugreport",
            defaultValue = false
        ).let { if (it) "--bugreport" else "" }
        val encryptOutputs = ConfigManager.getDataOnce(
            context = this,
            key = "encrypt_outputs",
            defaultValue = false
        )

        File(savePath).apply { if (!exists()) mkdirs() }
        val fileName = when {
            fullRandom -> Auxiliary.getRandomStringEx((16..24).random())
            customPrefix -> "${prefix}_${Auxiliary.getRandomString(4)}$suffix"
            else -> "${Auxiliary.getCurrentDateString()}_${Auxiliary.getRandomString(4)}$suffix"
        }
        val tempName = Auxiliary.getRandomStringEx((20..35).random())
        val outputPath = if (encryptOutputs) tempPath + tempName else "$savePath/$fileName"

        lastEncryptOutputs = encryptOutputs
        lastSavePath = savePath
        lastFileName = fileName
        lastTempName = tempName

        val args = listOf(
            "/system/bin/screenrecord",
            "--time-limit", duration.toString(),
            displayID,
            bitrate,
            resolution,
            bugreport,
            outputPath
        ).filter { it.isNotEmpty() }

        val cmd = args.joinToString(" ") + " & echo $!"

        val pid = Auxiliary.execGetPid(cmd)
        if (pid == null) {
            withContext(Dispatchers.Main) {
                updateTileUI()
            }
            return
        }

        withContext(Dispatchers.Main) {
            isRecording = true
            recordPid = pid
            updateTileUI()
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            return
        }
        val pid = recordPid
        if (pid != null) {
            Auxiliary.killProcess(pid)
        }
        isRecording = false
        recordPid = null
        clicked = false
        updateTileUI()

        if (lastEncryptOutputs) {
            val temp = lastTempName
            val save = lastSavePath
            val file = lastFileName
            serviceScope.launch {
                if (isEncrypting) return@launch
                isEncrypting = true
                try {
                    var waited = 0
                    while (waited < 5) {
                        if (pid == null || !isProcessAlive(pid)) break
                        delay(500.milliseconds)
                        waited++
                    }
                    val tempFile = File(tempPath + temp)
                    if (tempFile.exists()) {
                        Auxiliary.exec("chmod 444 ${tempPath + temp}")
                        val encrypted = File("$save/$file")
                        EncryptManager.encryptFileByKeystore(tempFile, encrypted)
                    }
                } catch (_: Exception) {
                } finally {
                    Auxiliary.exec("rm -f ${tempPath + temp}")
                    isEncrypting = false
                }
            }
        }
    }

    private fun checkAndResetIfProcessDead() {
        val pid = recordPid
        if (isRecording && pid != null && !isProcessAlive(pid)) {
            isRecording = false
            recordPid = null
            if (lastEncryptOutputs && File(tempPath + lastTempName).exists()) {
                val temp = lastTempName
                val save = lastSavePath
                val file = lastFileName
                serviceScope.launch {
                    if (isEncrypting) return@launch
                    isEncrypting = true
                    try {
                        Auxiliary.exec("chmod 444 ${tempPath + temp}")
                        File(tempPath + temp).apply {
                            val encrypted = File("$save/$file")
                            EncryptManager.encryptFileByKeystore(this, encrypted)
                        }
                    } catch (_: Exception) {
                    } finally {
                        Auxiliary.exec("rm -f ${tempPath + temp}")
                        isEncrypting = false
                    }
                }
            }
            updateTileUI()
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        val (exitCode, _) = Auxiliary.exec("kill -0 $pid 2>/dev/null")
        return exitCode == 0
    }

    private fun updateTileUI() {
        qsTile?.apply {
            state = when {
                isRecording -> Tile.STATE_ACTIVE
                clicked -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            label = when {
                showingNoPermission -> getString(R.string.no_permission)
                isRecording -> getString(R.string.recording)
                clicked -> getString(R.string.collapse_to_start)
                else -> getString(R.string.stealth_screen_recording)
            }
            updateTile()
        }
    }
}