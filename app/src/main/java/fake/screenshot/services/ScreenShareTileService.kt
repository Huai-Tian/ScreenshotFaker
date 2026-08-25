package fake.screenshot.services

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fake.screenshot.R
import fake.screenshot.wrappers.ScreenShareManager

class ScreenShareTileService : TileService() {

    private var initializing = false

    private fun updateUI() {
        val tile = qsTile ?: return

        when {
            ScreenShareManager.scrcpyRunning -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.screencasting)
            }

            initializing -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.initializing)
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.stealth_screencast)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (ScreenShareManager.scrcpyRunning) {
            ScreenShareManager.stopScreenShare()
        } else {
            initializing = true
            updateUI()
            ScreenShareManager.initialize(this@ScreenShareTileService)
            initializing = false
            ScreenShareManager.startScreenShare()
        }
        updateUI()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateUI()
    }

    override fun onStopListening() {
        super.onStopListening()
        updateUI()
    }
}