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
            ScreenShareManager.relayRunning -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.screencasting)
                tile.subtitle = null
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.stealth_screencast)
                tile.subtitle = subtitleFor(ScreenShareManager.lastError)
            }
        }
        tile.updateTile()
    }

    /**
     * lastError 内部码 → 本地化副标题。内部状态码绝不上屏："destroyed"
     * 直显等于向持机者宣告自毁机制存在且刚被触发（胁迫演出穿帮），映射
     * 为静默（无副标题——磁贴无响应与普通故障不可区分）。未识别值一律
     * 静默：lastError 未来新增内部码不得经磁贴泄露内部机制；仅入口经
     * getString 写入的本地化文案原样透传
     */
    private fun subtitleFor(error: String?): String? = when (error) {
        null, "destroyed" -> null
        "locked_no_credentials" -> getString(R.string.unlock_app_first)
        "ssh_hostkey_changed" -> getString(R.string.share_error_hostkey)
        "server_exited_repeatedly" -> getString(R.string.share_error_server)
        "ssh_connect_failed", "ssh_forward_failed" -> getString(R.string.share_error_ssh)
        "copy_server_failed" -> getString(R.string.initialize_failed)
        else -> error?.takeIf {
            it == getString(R.string.no_permission) || it == getString(R.string.initialize_failed)
        }
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