package fake.screenshot.services

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fake.screenshot.R
import fake.screenshot.wrappers.ScreenShareManager

class ScreenShareTileService : TileService() {

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
                ScreenShareManager.lastErrorResId?.let { resId ->
                    tile.subtitle = getString(resId)
                } ?: run {
                    tile.subtitle = null
                }
            }
        }
        tile.updateTile()
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