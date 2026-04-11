package fake.screenshot.services

import android.os.Environment
import android.service.quicksettings.TileService
import fake.screenshot.Auxiliary
import fake.screenshot.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ScreenRecordTileService : TileService() {
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
                    context = this@ScreenRecordTileService,
                    key = "screenRecord_save_path",
                    defaultValue = "${Environment.getExternalStorageDirectory().path}/DCIM/ScreenshotFaker/Records"
                )
                val duration = ConfigManager.getDataOnce(
                    context = this@ScreenRecordTileService,
                    key = "screenRecord_duration",
                    defaultValue = "180"
                )
                val displayID = ConfigManager.getDataOnce(
                    context = this@ScreenRecordTileService,
                    key = "screenRecord_display_id",
                    defaultValue = ""
                ).let { if (it.isEmpty()) "" else "--display-id $it" }
                val bitrate = ConfigManager.getDataOnce(
                    context = this@ScreenRecordTileService,
                    key = "screenRecord_bitrate",
                    defaultValue = ""
                ).let { if (it.isEmpty()) "" else "--bit-rate $it" }
                val resolution = ConfigManager.getDataOnce(
                    context = this@ScreenRecordTileService,
                    key = "screenRecord_resolution",
                    defaultValue = ""
                ).let { if (it.isEmpty()) "" else "--size $it" }


                with(File(savePath)) {
                    if (!(exists() && isDirectory)) mkdirs()
                }
                Auxiliary.exec("screenrecord --time-limit $duration $displayID $bitrate $resolution ${savePath}/record.mp4")
            }
        }
        clicked = false
    }
}