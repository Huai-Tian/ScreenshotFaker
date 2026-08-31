package fake.screenshot.services

import android.os.Environment
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fake.screenshot.R
import fake.screenshot.Auxiliary
import fake.screenshot.defense.DefenseProtocol
import fake.screenshot.defense.KeyVault
import fake.screenshot.wrappers.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScreenshotTileService : TileService() {
    private val tempPath = "/data/local/tmp/"
    private var clicked = false
    private var showingNoPermission = false

    private fun screenshot() {
        if ((Auxiliary.isShellActivated || Auxiliary.isRootActivated) && clicked) {
            CoroutineScope(Dispatchers.IO).launch {
                val savePath = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "screenshot_save_path",
                    defaultValue = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_SCREENSHOTS
                    ).path
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
                // DK 拆分激活且本会话未解锁组装：直接放弃（fail-closed）。
                // 此时若继续，明文会先落 /data/local/tmp 再删——绝不给这个窗口
                if (encryptOutputs && !KeyVault.isDaemonKeyReady()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@ScreenshotTileService,
                            getString(R.string.unlock_app_first),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                val definedTimestamp = ConfigManager.getDataOnce(
                    context = this@ScreenshotTileService,
                    key = "defined_timestamp",
                    defaultValue = ""
                )
                File(savePath).apply {
                    if (!exists()) mkdirs()
                }
                val fileName = when {
                    fullRandom -> Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(16..24))
                    customPrefix -> "${prefix}_${Auxiliary.getRandomString(4)}$suffix"
                    else -> "${Auxiliary.getCurrentDateString()}_${
                        Auxiliary.getRandomString(
                            4
                        )
                    }$suffix"
                }
                val tempName = Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(20..35))
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
                            KeyVault.encryptFileByKeystore(this, encrypted)
                        }
                    } catch (_: Exception) {
                    } finally {
                        Auxiliary.exec("rm -f ${tempPath + tempName}")
                    }
                }
                Auxiliary.applyDefinedTimestamp(definedTimestamp, "$savePath/$fileName")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴可能先于 MainActivity 运行（如重启后直接点磁贴），确保 DK 上下文就绪
        DefenseProtocol.init(applicationContext)
        Auxiliary.refreshShellState()
        clicked = false
        showingNoPermission = false
        updateTileUI()
    }

    private fun updateTileUI() {
        qsTile?.apply {
            state = if (clicked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = when {
                showingNoPermission -> getString(R.string.no_permission)
                clicked -> getString(R.string.collapse_to_start)
                else -> getString(R.string.stealth_screenshot)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Auxiliary.refreshShellState()

        if (!Auxiliary.isShellActivated && !Auxiliary.isRootActivated) {
            clicked = false
            showingNoPermission = !showingNoPermission
            updateTileUI()
            return
        }

        clicked = when (qsTile?.state) {
            Tile.STATE_INACTIVE -> true
            Tile.STATE_ACTIVE -> false
            else -> false
        }
        updateTileUI()
    }

    override fun onStopListening() {
        super.onStopListening()
        Auxiliary.refreshShellState()
        if ((Auxiliary.isShellActivated || Auxiliary.isRootActivated) && clicked) {
            screenshot()
        } else {
            showingNoPermission = false
        }
        clicked = false
        updateTileUI()
    }
}