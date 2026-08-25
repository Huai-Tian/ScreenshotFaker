package fake.screenshot.services

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fake.screenshot.R
import fake.screenshot.wrappers.ScreenShareManager

class ScreenShareTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val stateListener: () -> Unit = {
        mainHandler.post { updateUI() }
    }

    override fun onClick() {
        super.onClick()
        ScreenShareManager.toggleScreenShare(this)
        updateUI()
    }

    private fun updateUI() {
        val tile = qsTile ?: return

        when {
            ScreenShareManager.scrcpyRunning -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.screencasting)
                tile.subtitle = null
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.stealth_screencast)
                tile.subtitle = ScreenShareManager.lastError
            }
        }
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        ScreenShareManager.addTileListener(stateListener)
    }

    override fun onStopListening() {
        ScreenShareManager.removeTileListener(stateListener)
        super.onStopListening()
    }
}