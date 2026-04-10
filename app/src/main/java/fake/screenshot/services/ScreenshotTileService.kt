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
    private var onScreen = false
    override fun onClick() {
        super.onClick()
        clicked = !clicked
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        onScreen = false
    }

    override fun onTileAdded() {
        super.onTileAdded()
        onScreen = true
    }

    override fun onStartListening() {
        super.onStartListening()
        onScreen = true
    }

    override fun onStopListening() {
        super.onStopListening()
        if (onScreen && clicked) {
            clicked = false

            if (Auxiliary.isShellActivated) {
                CoroutineScope(Dispatchers.IO).launch {
                    val savePath = ConfigManager.getDataOnce(
                        context = this@ScreenshotTileService,
                        key = "screenshot_save_path",
                        defaultValue = "${Environment.getExternalStorageDirectory().path}/DCIM/ScreenshotFaker/Screenshots"
                    )
                    with(File(savePath)) {
                        if (!(exists() && isDirectory))mkdirs()
                    }
                    Auxiliary.exec("screencap -p ${savePath}/shot.png")
                }

            } else {
                Runtime.getRuntime()
                    .exec("whoami > /sdcard/1.txt")
            }
        }
    }
}