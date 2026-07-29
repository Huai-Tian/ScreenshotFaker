package fake.screenshot.pages

import java.math.BigDecimal
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    var screenshotConfigDialog by remember { mutableStateOf(false) }
    var screenshotConfigDialogSavaPathInputText by remember { mutableStateOf(screenshotSavePath) }
    var screenshotConfigDialogSuffixInputText by remember { mutableStateOf(screenshotSuffix) }
    var screenshotConfigDialogDisplayIDInputText by remember { mutableStateOf(screenshotDisplayID) }
    val isScreenshotConfigValid by remember {
        derivedStateOf {
            Auxiliary.isConfigValid(
                screenshotConfigDialogSuffixInputText,
                screenshotConfigDialogSavaPathInputText
            ) && screenshotConfigDialogDisplayIDInputText.isDigitsOnly()
        }
    }
    //ScreenRecord
    val screenRecordSavePath by ConfigManager.rememberValue(
        context,
        "screenRecord_save_path",
        "${Environment.getExternalStorageDirectory().path}/Pictures/ScreenshotFaker/Records"
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
    val screenRecordBugreport by ConfigManager.rememberValue(
        context,
        "screenRecord_bugreport",
        false
    )
    var screenRecordConfigDialog by remember { mutableStateOf(false) }
    var screenRecordConfigDialogSavePathInputText by remember { mutableStateOf(screenRecordSavePath) }
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
    var screenRecordConfigDialogEnableBugreport by remember { mutableStateOf(screenRecordBugreport) }
    val isScreenRecordConfigValid by remember {
        derivedStateOf {
            Auxiliary.isConfigValid(
                screenRecordConfigDialogSavePathInputText,
                screenRecordConfigDialogSuffixInputText,
                screenRecordConfigDialogResolutionInputText
            ) && screenRecordConfigDialogDisplayIDInputText.isDigitsOnly()
                    && screenRecordConfigDialogDurationInputText.isDigitsOnly()
                    && screenRecordConfigDialogDurationInputText.isNotEmpty()
                    && screenRecordConfigDialogBitRateInputText.isDigitsOnly()
        }
    }
    //ScreenShare
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
        ""
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
            (!screenShareConfigDialogEnableAudio || screenShareConfigDialogAudioOutput || screenShareConfigDialogAudioMic) &&
                    Auxiliary.isConfigValid(screenShareConfigDialogVideoCameraID) &&
                    (screenShareConfigDialogVideoCameraZoom.toBigDecimalOrNull()
                        ?.let { it >= BigDecimal.ONE }
                        ?: screenShareConfigDialogVideoCameraZoom.isEmpty()) &&
                    (screenShareConfigDialogEnableAudio || screenShareConfigDialogEnableVideo) &&
                    (!screenShareConfigDialogVideoCamera || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
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
            contentPadding = PaddingValues(vertical = 20.dp),
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
                            screenshotConfigDialogDisplayIDInputText = screenshotDisplayID
                            screenshotConfigDialogSuffixInputText = screenshotSuffix
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
                            screenRecordConfigDialogSuffixInputText = screenRecordSuffix
                            screenRecordConfigDialogDisplayIDInputText = screenRecordDisplayID
                            screenRecordConfigDialogDurationInputText = screenRecordDuration
                            screenRecordConfigDialogBitRateInputText = screenRecordBitRate
                            screenRecordConfigDialogResolutionInputText = screenRecordResolution
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
        }
        if (screenshotConfigDialog) {
            AlertDialog(
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
                        OutlinedTextField(
                            value = screenshotConfigDialogSuffixInputText,
                            onValueChange = { screenshotConfigDialogSuffixInputText = it },
                            label = { Text(stringResource(R.string.stealth_file_suffix)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
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
            AlertDialog(
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
                        OutlinedTextField(
                            value = screenRecordConfigDialogSuffixInputText,
                            onValueChange = {
                                screenRecordConfigDialogSuffixInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_file_suffix)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
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
            AlertDialog(
                onDismissRequest = { screenShareConfigDialog = false },
                title = {
                    Text(text = stringResource(R.string.config_stealth_screenShare))
                },
                text = {
                    Column {
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
                                    }
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
                                    }
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
                                    onCheckedChange = { screenShareConfigDialogAudioOutput = it }
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
                                    onCheckedChange = { screenShareConfigDialogAudioMic = it }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            var configChanged = false
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
    }
}