package fake.screenshot.services

import android.os.Environment
import android.service.quicksettings.TileService
import fake.screenshot.Auxiliary
import fake.screenshot.ConfigManager
import fake.screenshot.EncryptManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ScreenshotTileService : TileService() {
    private val tempPath = "/data/local/tmp/"
    private var clicked = false
    override fun onClick() {
        super.onClick()
        clicked = true
    }

    override fun onStopListening() {
        super.onStopListening()
        if (Auxiliary.isShellActivated && clicked) {
            CoroutineScope(Dispatchers.IO).launch {
                val savePath = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "screenshot_save_path",
                    defaultValue = "${Environment.getExternalStorageDirectory().path}/Pictures/ScreenshotFaker/Screenshots"
                )
                val prefix = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "screenshot_prefix",
                    defaultValue = ""
                )
                val suffix = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "screenshot_suffix",
                    defaultValue = ".png"
                )
                val displayID = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "screenshot_display_id",
                    defaultValue = ""
                ).let { if (it.isEmpty()) "" else "-d $it" }
                val customPrefix = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "screenshot_custom_prefix",
                    defaultValue = false
                )
                val fullRandom = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "screenshot_full_random",
                    defaultValue = false
                )
                val encryptOutputs = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "encrypt_outputs",
                    defaultValue = false
                )
                File(savePath).apply {
                    if (!exists()) mkdirs()
                }
                val fileName = when {
                    fullRandom -> Auxiliary.getRandomStringEx((16..24).random())
                    customPrefix -> "${prefix}_${Auxiliary.getRandomString(4)}$suffix"
                    else -> "${Auxiliary.getCurrentDateString()}_${
                        Auxiliary.getRandomString(
                            4
                        )
                    }$suffix"
                }
                val tempName = Auxiliary.getRandomStringEx((20..35).random())
                val args = listOf(
                    "screencap",
                    "-p",
                    displayID,
                    if (encryptOutputs) tempPath + tempName else "$savePath/$fileName"
                ).filter { it.isNotEmpty() }
                Auxiliary.exec(args.joinToString(" "))
                if (encryptOutputs) {
                    Auxiliary.exec("chmod 444 ${tempPath + tempName}")
                    try {
                        File(tempPath + tempName).apply {
                            val encrypted = File("$savePath/$fileName")
                            EncryptManager.encryptFileByKeystore(this, encrypted)
                        }
                    } catch (_: Exception) {
                    } finally {
                        Auxiliary.exec("rm -f ${tempPath + tempName}")
                    }
                }
            }
        }
        clicked = false
    }
}