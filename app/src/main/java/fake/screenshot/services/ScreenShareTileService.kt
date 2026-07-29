package fake.screenshot.services

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fake.screenshot.Auxiliary
import fake.screenshot.R

class ScreenShareTileService : TileService() {

    private var screencasting = false
    private var initializing = false
    private val scrcpyName = Auxiliary.getRandomString(8)

    private fun initialize(): Boolean {
        if (!Auxiliary.isShellActivated) return false
        //TODO Generate Random Port Number and Prepare SSH
        if (Auxiliary.exec("cp ${this@ScreenShareTileService.applicationInfo.nativeLibraryDir}/libscrcpy-server.so /data/local/tmp/$scrcpyName").first != 0) return false
        return true
    }

    private fun stopScreencast(): Boolean {
        return true
    }

    private fun startScreencast(): Boolean {
        return true
    }

    private fun updateUI() {
        val tile = qsTile ?: return

        when {
            screencasting -> {
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

        if (screencasting) {
            screencasting = !stopScreencast()
        } else {
            initializing = true
            updateUI()
            initialize()
            initializing = false
            screencasting = startScreencast()
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