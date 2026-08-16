package fake.screenshot.pages

import java.math.BigDecimal
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.text.isDigitsOnly
import fake.screenshot.Auxiliary
import fake.screenshot.ConfigManager
import fake.screenshot.DaemonManager
import fake.screenshot.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionCompose() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    //Screenshot
    val screenshotSavePath by ConfigManager.rememberValue(
        context,
        "screenshot_save_path",
        "${Environment.getExternalStorageDirectory().path}/Pictures/ScreenshotFaker/Screenshots"
    )
    val screenshotPrefix by ConfigManager.rememberValue(
        context,
        "screenshot_prefix",
        ""
    )
    val screenshotSuffix by ConfigManager.rememberValue(
        context,
        "screenshot_suffix",
        ".png"
    )
    val screenshotDisplayID by ConfigManager.rememberValue(
        context,
        "screenshot_display_id",
        ""
    )
    val screenshotCustomPrefix by ConfigManager.rememberValue(
        context,
        "screenshot_custom_prefix",
        false
    )
    val screenshotFullRandom by ConfigManager.rememberValue(
        context,
        "screenshot_full_random",
        false
    )
    var screenshotConfigDialog by remember { mutableStateOf(false) }
    var screenshotConfigDialogSavaPathInputText by remember { mutableStateOf(screenshotSavePath) }
    var screenshotConfigDialogPrefixInputText by remember { mutableStateOf(screenshotPrefix) }
    var screenshotConfigDialogSuffixInputText by remember { mutableStateOf(screenshotSuffix) }
    var screenshotConfigDialogDisplayIDInputText by remember { mutableStateOf(screenshotDisplayID) }
    var screenshotConfigDialogCustomPrefixInputText by remember {
        mutableStateOf(
            screenshotCustomPrefix
        )
    }
    var screenshotConfigDialogFullRandomInputText by remember { mutableStateOf(screenshotFullRandom) }
    val isScreenshotConfigValid by remember {
        derivedStateOf {
            screenshotConfigDialogDisplayIDInputText.isDigitsOnly()
                    && screenshotConfigDialogSavaPathInputText.isNotEmpty()
                    && Auxiliary.isConfigValid(
                screenshotConfigDialogSavaPathInputText,
                screenshotConfigDialogPrefixInputText,
                screenshotConfigDialogSuffixInputText
            )
        }
    }
    //ScreenRecord
    val screenRecordSavePath by ConfigManager.rememberValue(
        context,
        "screenRecord_save_path",
        "${Environment.getExternalStorageDirectory().path}/Pictures/ScreenshotFaker/Records"
    )
    val screenRecordPrefix by ConfigManager.rememberValue(
        context,
        "screenRecord_prefix",
        ""
    )
    val screenRecordSuffix by ConfigManager.rememberValue(
        context,
        "screenRecord_suffix",
        ".mp4"
    )
    val screenRecordDisplayID by ConfigManager.rememberValue(
        context,
        "screenRecord_display_id",
        ""
    )
    val screenRecordDuration by ConfigManager.rememberValue(
        context,
        "screenRecord_duration",
        "180"
    )
    val screenRecordBitRate by ConfigManager.rememberValue(
        context,
        "screenRecord_bitrate",
        ""
    )
    val screenRecordResolution by ConfigManager.rememberValue(
        context,
        "screenRecord_resolution",
        ""
    )
    val screenRecordCustomPrefix by ConfigManager.rememberValue(
        context,
        "screenRecord_custom_prefix",
        false
    )
    val screenRecordFullRandom by ConfigManager.rememberValue(
        context,
        "screenRecord_full_random",
        false
    )
    val screenRecordBugreport by ConfigManager.rememberValue(
        context,
        "screenRecord_bugreport",
        false
    )
    var screenRecordConfigDialog by remember { mutableStateOf(false) }
    var screenRecordConfigDialogSavePathInputText by remember { mutableStateOf(screenRecordSavePath) }
    var screenRecordConfigDialogPrefixInputText by remember { mutableStateOf(screenRecordPrefix) }
    var screenRecordConfigDialogSuffixInputText by remember { mutableStateOf(screenRecordSuffix) }
    var screenRecordConfigDialogDisplayIDInputText by remember {
        mutableStateOf(
            screenRecordDisplayID
        )
    }
    var screenRecordConfigDialogDurationInputText by remember { mutableStateOf(screenRecordDuration) }
    var screenRecordConfigDialogBitRateInputText by remember { mutableStateOf(screenRecordBitRate) }
    var screenRecordConfigDialogResolutionInputText by remember {
        mutableStateOf(
            screenRecordResolution
        )
    }
    var screenRecordConfigDialogCustomPrefixInputText by remember {
        mutableStateOf(
            screenRecordCustomPrefix
        )
    }
    var screenRecordConfigDialogFullRandomInputText by remember {
        mutableStateOf(
            screenRecordFullRandom
        )
    }
    var screenRecordConfigDialogEnableBugreport by remember { mutableStateOf(screenRecordBugreport) }
    val isScreenRecordConfigValid by remember {
        derivedStateOf {
            screenRecordConfigDialogDisplayIDInputText.isDigitsOnly()
                    && screenRecordConfigDialogDurationInputText.let { it.isNotEmpty() && it.isDigitsOnly() }
                    && screenRecordConfigDialogSavePathInputText.isNotEmpty()
                    && screenRecordConfigDialogBitRateInputText.isDigitsOnly()
                    && Auxiliary.isConfigValid(
                screenRecordConfigDialogSavePathInputText,
                screenRecordConfigDialogPrefixInputText,
                screenRecordConfigDialogSuffixInputText,
                screenRecordConfigDialogResolutionInputText
            )
        }
    }
    //ScreenShare
    val screenShareLocalPort by ConfigManager.rememberValue(context, "screenShare_port", 2345)
    val screenShareControl by ConfigManager.rememberValue(context, "screenShare_control", true)
    val screenShareSyncClipboard by ConfigManager.rememberValue(
        context,
        "screenShare_sync_clipboard",
        true
    )
    val screenShareVideo by ConfigManager.rememberValue(context, "screenShare_video", true)
    val screenShareVideoDisplay by ConfigManager.rememberValue(
        context,
        "screenShare_video_display",
        true
    )
    val screenShareVideoDisplayID by ConfigManager.rememberValue(
        context,
        "screenShare_video_display_id",
        ""
    )
    val screenShareVideoCamera by ConfigManager.rememberValue(
        context,
        "screenShare_video_camera",
        false
    )
    val screenShareVideoCameraID by ConfigManager.rememberValue(
        context,
        "screenShare_video_camera_id",
        "0"
    )
    val screenShareVideoCameraZoom by ConfigManager.rememberValue(
        context,
        "screenShare_video_camera_zoom",
        ""
    )
    val screenShareVideoCameraTorch by ConfigManager.rememberValue(
        context,
        "screenShare_video_camera_torch",
        false
    )
    val screenShareAudio by ConfigManager.rememberValue(context, "screenShare_audio", true)
    val screenShareAudioOutput by ConfigManager.rememberValue(
        context,
        "screenShare_audio_output",
        true
    )
    val screenShareAudioMic by ConfigManager.rememberValue(
        context,
        "screenShare_audio_mic",
        false
    )
    var screenShareConfigDialog by remember { mutableStateOf(false) }
    var screenShareConfigDialogLocalPort by remember { mutableStateOf(screenShareLocalPort.toString()) }
    var screenShareConfigDialogAllowControl by remember { mutableStateOf(screenShareControl) }
    var screenShareConfigDialogSyncClipboard by remember { mutableStateOf(screenShareSyncClipboard) }
    var screenShareConfigDialogEnableVideo by remember { mutableStateOf(screenShareVideo) }
    var screenShareConfigDialogVideoDisplayID by remember { mutableStateOf(screenShareVideoDisplayID) }
    var screenShareConfigDialogVideoDisplay by remember { mutableStateOf(screenShareVideoDisplay) }
    var screenShareConfigDialogVideoCamera by remember { mutableStateOf(screenShareVideoCamera) }
    var screenShareConfigDialogVideoCameraID by remember { mutableStateOf(screenShareVideoCameraID) }
    var screenShareConfigDialogVideoCameraZoom by remember {
        mutableStateOf(
            screenShareVideoCameraZoom
        )
    }
    var screenShareConfigDialogVideoCameraTorch by remember {
        mutableStateOf(
            screenShareVideoCameraTorch
        )
    }
    var screenShareConfigDialogEnableAudio by remember { mutableStateOf(screenShareAudio) }
    var screenShareConfigDialogAudioOutput by remember { mutableStateOf(screenShareAudioOutput) }
    var screenShareConfigDialogAudioMic by remember { mutableStateOf(screenShareAudioMic) }
    val isScreenShareConfigValid by remember {
        derivedStateOf {
            val portValid = screenShareConfigDialogLocalPort.toIntOrNull()
                .let { it != null && it in 1024..65535 }
            val cameraIdValid =
                !screenShareConfigDialogVideoCamera || screenShareConfigDialogVideoCameraID.let { it.isNotEmpty() && it.isDigitsOnly() }
            val displayIdValid =
                !screenShareConfigDialogVideoDisplay || screenShareConfigDialogVideoDisplayID.isDigitsOnly()
            val cameraZoomValid = screenShareConfigDialogVideoCameraZoom.toBigDecimalOrNull()
                ?.let { it >= BigDecimal.ONE }
                ?: screenShareConfigDialogVideoCameraZoom.isEmpty()
            portValid && cameraIdValid && cameraZoomValid && displayIdValid
        }
    }
    //SSH Tunnel
    val sshTunnelEnabled by ConfigManager.rememberValue(context, "ssh_tunnel_enabled", false)
    val sshTunnelServerAddress by ConfigManager.rememberValue(
        context,
        "ssh_tunnel_server_address",
        "127.0.0.1"
    )
    val sshTunnelServerPort by ConfigManager.rememberValue(context, "ssh_tunnel_server_port", 22)
    val sshTunnelUserName by ConfigManager.rememberValue(
        context,
        "ssh_tunnel_user_name",
        "ScreenshotFaker"
    )
    val sshTunnelUserPassword by ConfigManager.rememberValue(
        context,
        "ssh_tunnel_user_password",
        "ScreenshotFaker"
    )
    var sshTunnelConfigDialog by remember { mutableStateOf(false) }
    var sshTunnelConfigDialogEnabled by remember { mutableStateOf(sshTunnelEnabled) }
    var sshTunnelConfigDialogServerAddress by remember { mutableStateOf(sshTunnelServerAddress) }
    var sshTunnelConfigDialogServerPort by remember { mutableStateOf(sshTunnelServerPort.toString()) }
    var sshTunnelConfigDialogUserName by remember { mutableStateOf(sshTunnelUserName) }
    var sshTunnelConfigDialogUserPassword by remember { mutableStateOf(sshTunnelUserPassword) }
    val isSshTunnelConfigValid by remember {
        derivedStateOf {
            val addressValid = sshTunnelConfigDialogServerAddress.let {
                it.isNotEmpty() && it.all { char ->
                    char.isLetterOrDigit() || char in listOf(
                        '.',
                        '-'
                    )
                }
            }
            val portValid = sshTunnelConfigDialogServerPort.toIntOrNull()
                .let { it != null && it in 1..65535 }
            val nameValid = sshTunnelConfigDialogUserName.let {
                it.isNotEmpty() && it.matches(Regex("^[a-zA-Z0-9_][a-zA-Z0-9_-]*$"))
            }
            addressValid && portValid && nameValid && sshTunnelConfigDialogUserPassword.isNotBlank()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extension)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            // 关键：通过 spacedBy 控制卡片之间的垂直间距
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.Screenshot,
                        title = stringResource(R.string.stealth_screenshot),
                        subtitle = stringResource(R.string.click_to_config_stealth_screenshot),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            screenshotConfigDialogSavaPathInputText = screenshotSavePath
                            screenshotConfigDialogPrefixInputText = screenshotPrefix
                            screenshotConfigDialogSuffixInputText = screenshotSuffix
                            screenshotConfigDialogDisplayIDInputText = screenshotDisplayID
                            screenshotConfigDialogCustomPrefixInputText = screenshotCustomPrefix
                            screenshotConfigDialogFullRandomInputText = screenshotFullRandom
                            screenshotConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.Videocam,
                        title = stringResource(R.string.stealth_screen_recording),
                        subtitle = stringResource(R.string.click_to_config_stealth_screen_recording),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            screenRecordConfigDialogSavePathInputText = screenRecordSavePath
                            screenRecordConfigDialogPrefixInputText = screenRecordPrefix
                            screenRecordConfigDialogSuffixInputText = screenRecordSuffix
                            screenRecordConfigDialogDisplayIDInputText = screenRecordDisplayID
                            screenRecordConfigDialogDurationInputText = screenRecordDuration
                            screenRecordConfigDialogBitRateInputText = screenRecordBitRate
                            screenRecordConfigDialogResolutionInputText = screenRecordResolution
                            screenRecordConfigDialogCustomPrefixInputText = screenRecordCustomPrefix
                            screenRecordConfigDialogFullRandomInputText = screenRecordFullRandom
                            screenRecordConfigDialogEnableBugreport = screenRecordBugreport
                            screenRecordConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.Cast,
                        title = stringResource(R.string.stealth_screen_sharing),
                        subtitle = stringResource(R.string.click_to_config_stealth_screen_sharing),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            screenShareConfigDialogLocalPort = screenShareLocalPort.toString()
                            screenShareConfigDialogAllowControl = screenShareControl
                            screenShareConfigDialogSyncClipboard = screenShareSyncClipboard
                            screenShareConfigDialogEnableVideo = screenShareVideo
                            screenShareConfigDialogVideoDisplay = screenShareVideoDisplay
                            screenShareConfigDialogVideoDisplayID = screenShareVideoDisplayID
                            screenShareConfigDialogVideoCamera = screenShareVideoCamera
                            screenShareConfigDialogVideoCameraID = screenShareVideoCameraID
                            screenShareConfigDialogVideoCameraZoom = screenShareVideoCameraZoom
                            screenShareConfigDialogVideoCameraTorch = screenShareVideoCameraTorch
                            screenShareConfigDialogEnableAudio = screenShareAudio
                            screenShareConfigDialogAudioOutput = screenShareAudioOutput
                            screenShareConfigDialogAudioMic = screenShareAudioMic
                            screenShareConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.CellTower,
                        title = stringResource(R.string.ssh_tunnel),
                        subtitle = stringResource(R.string.click_to_config_ssh_tunnel),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            sshTunnelConfigDialogEnabled = sshTunnelEnabled
                            sshTunnelConfigDialogServerAddress = sshTunnelServerAddress
                            sshTunnelConfigDialogServerPort = sshTunnelServerPort.toString()
                            sshTunnelConfigDialogUserName = sshTunnelUserName
                            sshTunnelConfigDialogUserPassword = sshTunnelUserPassword
                            sshTunnelConfigDialog = true
                        }
                    )
                }
            }
        }
        if (screenshotConfigDialog) {
            CenteredAlertDialog(
                onDismissRequest = { screenshotConfigDialog = false },
                title = {
                    Text(text = stringResource(R.string.config_stealth_screenshot)) // 标题
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = screenshotConfigDialogSavaPathInputText,
                            onValueChange = { screenshotConfigDialogSavaPathInputText = it }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenshot_save_path)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (!screenshotConfigDialogFullRandomInputText) {
                            if (screenshotConfigDialogCustomPrefixInputText) {
                                OutlinedTextField(
                                    value = screenshotConfigDialogPrefixInputText,
                                    onValueChange = { screenshotConfigDialogPrefixInputText = it },
                                    label = { Text(stringResource(R.string.stealth_file_prefix)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                            }
                            OutlinedTextField(
                                value = screenshotConfigDialogSuffixInputText,
                                onValueChange = { screenshotConfigDialogSuffixInputText = it },
                                label = { Text(stringResource(R.string.stealth_file_suffix)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = screenshotConfigDialogDisplayIDInputText,
                            onValueChange = {
                                screenshotConfigDialogDisplayIDInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.physical_display_id)) },
                            placeholder = { Text(stringResource(R.string.default_if_empty)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (!screenshotConfigDialogFullRandomInputText) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = stringResource(R.string.custom_file_prefix))
                                Switch(
                                    checked = screenshotConfigDialogCustomPrefixInputText,
                                    onCheckedChange = {
                                        screenshotConfigDialogCustomPrefixInputText = it
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.full_random_file_name))
                            Switch(
                                checked = screenshotConfigDialogFullRandomInputText,
                                onCheckedChange = { screenshotConfigDialogFullRandomInputText = it }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                var configChanged = false
                                if (screenshotSavePath != screenshotConfigDialogSavaPathInputText.removeSuffix(
                                        "/"
                                    )
                                ) {
                                    ConfigManager.saveData(
                                        context,
                                        "screenshot_save_path",
                                        screenshotConfigDialogSavaPathInputText.removeSuffix("/")
                                    )
                                    configChanged = true
                                }
                                if (screenshotPrefix != screenshotConfigDialogPrefixInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "screenshot_prefix",
                                        screenshotConfigDialogPrefixInputText
                                    )
                                    configChanged = true
                                }
                                if (screenshotSuffix != screenshotConfigDialogSuffixInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "screenshot_suffix",
                                        screenshotConfigDialogSuffixInputText
                                    )
                                    configChanged = true
                                }
                                if (screenshotDisplayID != screenshotConfigDialogDisplayIDInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "screenshot_display_id",
                                        screenshotConfigDialogDisplayIDInputText
                                    )
                                    configChanged = true
                                }
                                if (screenshotCustomPrefix != screenshotConfigDialogCustomPrefixInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "screenshot_custom_prefix",
                                        screenshotConfigDialogCustomPrefixInputText
                                    )
                                    configChanged = true
                                }
                                if (screenshotFullRandom != screenshotConfigDialogFullRandomInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "screenshot_full_random",
                                        screenshotConfigDialogFullRandomInputText
                                    )
                                    configChanged = true
                                }
                                if (configChanged) {
                                    DaemonManager.syncConfig()
                                }
                            }
                            screenshotConfigDialog = false
                        },
                        enabled = isScreenshotConfigValid
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { screenshotConfigDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (screenRecordConfigDialog) {
            CenteredAlertDialog(
                onDismissRequest = { screenRecordConfigDialog = false },
                title = {
                    Text(text = stringResource(R.string.config_stealth_screenRecord)) // 标题
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = screenRecordConfigDialogSavePathInputText,
                            onValueChange = {
                                screenRecordConfigDialogSavePathInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenRecord_save_path)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = screenRecordConfigDialogDurationInputText,
                            onValueChange = {
                                screenRecordConfigDialogDurationInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenRecord_duration)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (!screenRecordConfigDialogFullRandomInputText) {
                            if (screenRecordConfigDialogCustomPrefixInputText) {
                                OutlinedTextField(
                                    value = screenRecordConfigDialogPrefixInputText,
                                    onValueChange = {
                                        screenRecordConfigDialogPrefixInputText = it
                                    },
                                    label = { Text(stringResource(R.string.stealth_file_prefix)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                            }
                            OutlinedTextField(
                                value = screenRecordConfigDialogSuffixInputText,
                                onValueChange = {
                                    screenRecordConfigDialogSuffixInputText = it
                                }, // 可编辑
                                label = { Text(stringResource(R.string.stealth_file_suffix)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = screenRecordConfigDialogDisplayIDInputText,
                            onValueChange = {
                                screenRecordConfigDialogDisplayIDInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.physical_display_id)) },
                            placeholder = { Text(stringResource(R.string.default_if_empty)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = screenRecordConfigDialogBitRateInputText,
                            onValueChange = {
                                screenRecordConfigDialogBitRateInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenRecord_bitrate)) },
                            placeholder = { Text(stringResource(R.string.default_if_empty)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = screenRecordConfigDialogResolutionInputText,
                            onValueChange = {
                                screenRecordConfigDialogResolutionInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenRecord_size)) },
                            placeholder = { Text(stringResource(R.string.default_if_empty)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (!screenRecordConfigDialogFullRandomInputText) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = stringResource(R.string.custom_file_prefix))
                                Switch(
                                    checked = screenRecordConfigDialogCustomPrefixInputText,
                                    onCheckedChange = {
                                        screenRecordConfigDialogCustomPrefixInputText = it
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.full_random_file_name))
                            Switch(
                                checked = screenRecordConfigDialogFullRandomInputText,
                                onCheckedChange = {
                                    screenRecordConfigDialogFullRandomInputText = it
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.stealth_screenRecord_bugreport))
                            Switch(
                                checked = screenRecordConfigDialogEnableBugreport,
                                onCheckedChange = { screenRecordConfigDialogEnableBugreport = it }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            var configChanged = false
                            if (screenRecordSavePath != screenRecordConfigDialogSavePathInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_save_path",
                                    screenRecordConfigDialogSavePathInputText.removeSuffix("/")
                                )
                                configChanged = true
                            }
                            if (screenRecordPrefix != screenRecordConfigDialogPrefixInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_prefix",
                                    screenRecordConfigDialogPrefixInputText
                                )
                                configChanged = true
                            }
                            if (screenRecordSuffix != screenRecordConfigDialogSuffixInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_suffix",
                                    screenRecordConfigDialogSuffixInputText
                                )
                                configChanged = true
                            }
                            if (screenRecordDisplayID != screenRecordConfigDialogDisplayIDInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_display_id",
                                    screenRecordConfigDialogDisplayIDInputText
                                )
                                configChanged = true
                            }
                            if (screenRecordCustomPrefix != screenRecordConfigDialogCustomPrefixInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_custom_prefix",
                                    screenRecordConfigDialogCustomPrefixInputText
                                )
                                configChanged = true
                            }
                            if (screenRecordFullRandom != screenRecordConfigDialogFullRandomInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_full_random",
                                    screenRecordConfigDialogFullRandomInputText
                                )
                                configChanged = true
                            }
                            if (screenRecordBugreport != screenRecordConfigDialogEnableBugreport) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_bugreport",
                                    screenRecordConfigDialogEnableBugreport
                                )
                                configChanged = true
                            }
                            if (screenRecordDuration != screenRecordConfigDialogDurationInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_duration",
                                    screenRecordConfigDialogDurationInputText
                                )
                                configChanged = true
                            }
                            if (screenRecordBitRate != screenRecordConfigDialogBitRateInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_bitrate",
                                    screenRecordConfigDialogBitRateInputText
                                )
                                configChanged = true
                            }
                            if (screenRecordResolution != screenRecordConfigDialogResolutionInputText) {
                                ConfigManager.saveData(
                                    context,
                                    "screenRecord_resolution",
                                    screenRecordConfigDialogResolutionInputText
                                )
                                configChanged = true
                            }
                            if (configChanged) {
                                DaemonManager.syncConfig()
                            }
                        }
                        screenRecordConfigDialog = false
                    }, enabled = isScreenRecordConfigValid) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { screenRecordConfigDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (screenShareConfigDialog) {
            CenteredAlertDialog(
                onDismissRequest = { screenShareConfigDialog = false },
                title = {
                    Text(text = stringResource(R.string.config_stealth_screenShare))
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = screenShareConfigDialogLocalPort,
                            onValueChange = { screenShareConfigDialogLocalPort = it }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenShare_port)) },
                            placeholder = {
                                Text(
                                    "1024…65535"
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.stealth_screenShare_control))
                            Switch(
                                checked = screenShareConfigDialogAllowControl,
                                onCheckedChange = { screenShareConfigDialogAllowControl = it }
                            )
                        }
                        if (screenShareConfigDialogAllowControl) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = stringResource(R.string.stealth_screenShare_sync_clipboard))
                                Switch(
                                    checked = screenShareConfigDialogSyncClipboard,
                                    onCheckedChange = { screenShareConfigDialogSyncClipboard = it }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.stealth_screenShare_video))
                            Switch(
                                checked = screenShareConfigDialogEnableVideo,
                                onCheckedChange = { screenShareConfigDialogEnableVideo = it }
                            )
                        }
                        if (screenShareConfigDialogEnableVideo) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = stringResource(R.string.stealth_screenShare_video_display))
                                Switch(
                                    checked = screenShareConfigDialogVideoDisplay,
                                    onCheckedChange = {
                                        screenShareConfigDialogVideoDisplay = it
                                        screenShareConfigDialogVideoCamera = !it
                                    },
                                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                )
                            }
                            if (screenShareConfigDialogVideoDisplay) {
                                OutlinedTextField(
                                    value = screenShareConfigDialogVideoDisplayID,
                                    onValueChange = { screenShareConfigDialogVideoDisplayID = it },
                                    label = { Text(stringResource(R.string.physical_display_id)) },
                                    placeholder = { Text(stringResource(R.string.default_if_empty)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = stringResource(R.string.stealth_screenShare_video_camera))
                                Switch(
                                    checked = screenShareConfigDialogVideoCamera,
                                    onCheckedChange = {
                                        screenShareConfigDialogVideoCamera = it
                                        screenShareConfigDialogVideoDisplay = !it
                                    },
                                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                )
                            }
                            if (screenShareConfigDialogVideoCamera) {
                                OutlinedTextField(
                                    value = screenShareConfigDialogVideoCameraID,
                                    onValueChange = { screenShareConfigDialogVideoCameraID = it },
                                    label = { Text(stringResource(R.string.stealth_screenShare_video_camera_id)) },
                                    placeholder = { Text(stringResource(R.string.default_if_empty)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = screenShareConfigDialogVideoCameraZoom,
                                    onValueChange = { screenShareConfigDialogVideoCameraZoom = it },
                                    label = { Text(stringResource(R.string.stealth_screenShare_video_camera_zoom)) },
                                    placeholder = { Text(stringResource(R.string.default_if_empty)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = stringResource(R.string.stealth_screenShare_video_camera_torch))
                                    Switch(
                                        checked = screenShareConfigDialogVideoCameraTorch,
                                        onCheckedChange = {
                                            screenShareConfigDialogVideoCameraTorch = it
                                        }
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.stealth_screenShare_audio))
                            Switch(
                                checked = screenShareConfigDialogEnableAudio,
                                onCheckedChange = { screenShareConfigDialogEnableAudio = it }
                            )
                        }
                        if (screenShareConfigDialogEnableAudio) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = stringResource(R.string.stealth_screenShare_audio_output))
                                Switch(
                                    checked = screenShareConfigDialogAudioOutput,
                                    onCheckedChange = {
                                        screenShareConfigDialogAudioOutput = it
                                        screenShareConfigDialogAudioMic = !it
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = stringResource(R.string.stealth_screenShare_audio_mic))
                                Switch(
                                    checked = screenShareConfigDialogAudioMic,
                                    onCheckedChange = {
                                        screenShareConfigDialogAudioMic = it
                                        screenShareConfigDialogAudioOutput = !it
                                    }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            var configChanged = false
                            if (screenShareLocalPort != screenShareConfigDialogLocalPort.toInt()) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_port",
                                    screenShareConfigDialogLocalPort.toInt()
                                )
                                configChanged = true
                            }
                            if (screenShareControl != screenShareConfigDialogAllowControl) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_control",
                                    screenShareConfigDialogAllowControl
                                )
                                configChanged = true
                            }
                            if (screenShareSyncClipboard != screenShareConfigDialogSyncClipboard) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_sync_clipboard",
                                    screenShareConfigDialogSyncClipboard
                                )
                                configChanged = true
                            }
                            if (screenShareVideo != screenShareConfigDialogEnableVideo) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_video",
                                    screenShareConfigDialogEnableVideo
                                )
                                configChanged = true
                            }
                            if (screenShareVideoDisplay != screenShareConfigDialogVideoDisplay) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_video_display",
                                    screenShareConfigDialogVideoDisplay
                                )
                                configChanged = true
                            }
                            if (screenShareVideoDisplayID != screenShareConfigDialogVideoDisplayID) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_video_display_id",
                                    screenShareConfigDialogVideoDisplayID
                                )
                                configChanged = true
                            }
                            if (screenShareVideoCamera != screenShareConfigDialogVideoCamera) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_video_camera",
                                    screenShareConfigDialogVideoCamera
                                )
                                configChanged = true
                            }
                            if (screenShareVideoCameraID != screenShareConfigDialogVideoCameraID) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_video_camera_id",
                                    screenShareConfigDialogVideoCameraID
                                )
                                configChanged = true
                            }
                            if (screenShareVideoCameraZoom != screenShareConfigDialogVideoCameraZoom) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_video_camera_zoom",
                                    screenShareConfigDialogVideoCameraZoom
                                )
                                configChanged = true
                            }
                            if (screenShareVideoCameraTorch != screenShareConfigDialogVideoCameraTorch) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_video_camera_torch",
                                    screenShareConfigDialogVideoCameraTorch
                                )
                                configChanged = true
                            }
                            if (screenShareAudio != screenShareConfigDialogEnableAudio) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_audio",
                                    screenShareConfigDialogEnableAudio
                                )
                                configChanged = true
                            }
                            if (screenShareAudioOutput != screenShareConfigDialogAudioOutput) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_audio_output",
                                    screenShareConfigDialogAudioOutput
                                )
                                configChanged = true
                            }
                            if (screenShareAudioMic != screenShareConfigDialogAudioMic) {
                                ConfigManager.saveData(
                                    context,
                                    "screenShare_audio_mic",
                                    screenShareConfigDialogAudioMic
                                )
                                configChanged = true
                            }
                            if (configChanged) {
                                DaemonManager.syncConfig()
                            }
                        }
                        screenShareConfigDialog = false
                    }, enabled = isScreenShareConfigValid) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { screenShareConfigDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (sshTunnelConfigDialog) {
            CenteredAlertDialog(
                onDismissRequest = { sshTunnelConfigDialog = false },
                title = {
                    Text(text = stringResource(R.string.config_ssh_tunnel))
                },
                text = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.ssh_server_enabled))
                            Switch(
                                checked = sshTunnelConfigDialogEnabled,
                                onCheckedChange = { sshTunnelConfigDialogEnabled = it }
                            )
                        }
                        OutlinedTextField(
                            value = sshTunnelConfigDialogServerAddress,
                            onValueChange = { sshTunnelConfigDialogServerAddress = it },
                            label = { Text(stringResource(R.string.ssh_server_address)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sshTunnelConfigDialogServerPort,
                            onValueChange = { sshTunnelConfigDialogServerPort = it },
                            label = { Text(stringResource(R.string.ssh_server_port)) },
                            placeholder = {
                                Text(
                                    "1024…65535"
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sshTunnelConfigDialogUserName,
                            onValueChange = { sshTunnelConfigDialogUserName = it },
                            label = { Text(stringResource(R.string.ssh_server_user_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sshTunnelConfigDialogUserPassword,
                            onValueChange = { sshTunnelConfigDialogUserPassword = it },
                            label = { Text(stringResource(R.string.ssh_server_user_password)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                var configChanged = false
                                if (sshTunnelEnabled != sshTunnelConfigDialogEnabled) {
                                    ConfigManager.saveData(
                                        context,
                                        "ssh_tunnel_enabled",
                                        sshTunnelConfigDialogEnabled
                                    )
                                    configChanged = true
                                }
                                if (sshTunnelServerAddress != sshTunnelConfigDialogServerAddress) {
                                    ConfigManager.saveData(
                                        context,
                                        "ssh_tunnel_server_address",
                                        sshTunnelConfigDialogServerAddress
                                    )
                                    configChanged = true
                                }
                                if (sshTunnelServerPort != sshTunnelConfigDialogServerPort.toInt()) {
                                    ConfigManager.saveData(
                                        context,
                                        "ssh_tunnel_server_port",
                                        sshTunnelConfigDialogServerPort.toInt()
                                    )
                                    configChanged = true
                                }
                                if (sshTunnelUserName != sshTunnelConfigDialogUserName) {
                                    ConfigManager.saveData(
                                        context,
                                        "ssh_tunnel_user_name",
                                        sshTunnelConfigDialogUserName
                                    )
                                    configChanged = true
                                }
                                if (sshTunnelUserPassword != sshTunnelConfigDialogUserPassword) {
                                    ConfigManager.saveData(
                                        context,
                                        "ssh_tunnel_user_password",
                                        sshTunnelConfigDialogUserPassword
                                    )
                                    configChanged = true
                                }
                                if (configChanged) {
                                    DaemonManager.syncConfig()
                                }
                            }
                            sshTunnelConfigDialog = false
                        },
                        enabled = isSshTunnelConfigValid
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sshTunnelConfigDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                },
            )
        }
    }
}