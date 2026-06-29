package fake.screenshot.services

import android.os.Environment
import android.service.quicksettings.TileService
import fake.screenshot.Auxiliary
import fake.screenshot.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ScreenshotTileService : TileService() {
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
                with(File(savePath)) {
                    if (!(exists() && isDirectory)) mkdirs()
                }
                val args = listOf(
                    "screencap",
                    "-p",
                    displayID,
                    "${savePath}/${Auxiliary.getCurrentDateString()}_${Auxiliary.getRandomString(4)}$suffix"
                ).filter { it.isNotEmpty() }
                Auxiliary.exec(args.joinToString(" "))
            }
        }
        clicked = false
    }
}