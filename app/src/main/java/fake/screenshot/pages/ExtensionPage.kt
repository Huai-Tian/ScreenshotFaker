package fake.screenshot.pages

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import fake.screenshot.ConfigManager
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
        "${Environment.getExternalStorageDirectory().path}/DCIM/ScreenshotFaker/Screenshots"
    )
    val screenshotDisplayID by ConfigManager.rememberValue(
        context,
        "screenshot_display_id",
        ""
    )
    var screenshotConfigDialog by remember { mutableStateOf(false) }
    var screenshotConfigDialogSavaPathInputText by remember { mutableStateOf(screenshotSavePath) }
    var screenshotConfigDialogDisplayIDInputText by remember { mutableStateOf(screenshotDisplayID) }
    //ScreenRecord
    val screenRecordSavePath by ConfigManager.rememberValue(
        context,
        "screenRecord_save_path",
        "${Environment.getExternalStorageDirectory().path}/DCIM/ScreenshotFaker/Records"
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
    var screenRecordConfigDialog by remember { mutableStateOf(false) }
    var screenRecordConfigDialogSavaPathInputText by remember { mutableStateOf(screenRecordSavePath) }
    var screenRecordConfigDialogDisplayIDInputText by remember { mutableStateOf(screenRecordDisplayID) }
    var screenRecordConfigDialogDurationInputText by remember {mutableStateOf(screenRecordDuration)}
    var screenRecordConfigDialogBitRateInputText by remember {mutableStateOf(screenRecordBitRate)}
    var screenRecordConfigDialogResolutionInputText by remember {mutableStateOf(screenRecordResolution)}

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
                    PreferenceItem(
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
                            screenshotConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItem(
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
                            screenRecordConfigDialogSavaPathInputText = screenRecordSavePath
                            screenRecordConfigDialogDisplayIDInputText = screenRecordDisplayID
                            screenRecordConfigDialogDurationInputText = screenRecordDuration
                            screenRecordConfigDialogBitRateInputText = screenRecordBitRate
                            screenRecordConfigDialogResolutionInputText = screenRecordResolution
                            screenRecordConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItem(
                        icon = Icons.Default.Cast,
                        title = stringResource(R.string.stealth_screen_sharing),
                        subtitle = stringResource(R.string.click_to_config_stealth_screen_sharing),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = { /*TODO*/ }
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
                    Column{
                        OutlinedTextField(
                            value = screenshotConfigDialogSavaPathInputText,
                            onValueChange = { screenshotConfigDialogSavaPathInputText = it }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenshot_save_path)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = screenshotConfigDialogDisplayIDInputText,
                            onValueChange = {
                                screenshotConfigDialogDisplayIDInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.physical_display_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            ConfigManager.saveData(
                                context,
                                "screenshot_save_path",
                                screenshotConfigDialogSavaPathInputText.removeSuffix("/")
                            )
                            ConfigManager.saveData(
                                context,
                                "screenshot_display_id",
                                screenshotConfigDialogDisplayIDInputText
                            )
                        }
                        screenshotConfigDialog = false
                    }) {
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
                    Column{
                        OutlinedTextField(
                            value = screenRecordConfigDialogSavaPathInputText,
                            onValueChange = {
                                screenRecordConfigDialogSavaPathInputText = it
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
                            value = screenRecordConfigDialogDisplayIDInputText,
                            onValueChange = {
                                screenRecordConfigDialogDisplayIDInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.physical_display_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = screenRecordConfigDialogBitRateInputText,
                            onValueChange = {
                                screenRecordConfigDialogBitRateInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenRecord_bitrate)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = screenRecordConfigDialogResolutionInputText,
                            onValueChange = {
                                screenRecordConfigDialogResolutionInputText = it
                            }, // 可编辑
                            label = { Text(stringResource(R.string.stealth_screenRecord_size)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            ConfigManager.saveData(
                                context,
                                "screenRecord_save_path",
                                screenRecordConfigDialogSavaPathInputText.removeSuffix("/")
                            )
                            ConfigManager.saveData(
                                context,
                                "screenRecord_display_id",
                                screenRecordConfigDialogDisplayIDInputText
                            )
                            ConfigManager.saveData(
                                context,
                                "screenRecord_duration",
                                screenRecordConfigDialogDurationInputText
                            )
                            ConfigManager.saveData(
                                context,
                                "screenRecord_bitrate",
                                screenRecordConfigDialogBitRateInputText
                            )
                            ConfigManager.saveData(
                                context,
                                "screenRecord_resolution",
                                screenRecordConfigDialogResolutionInputText
                            )
                        }
                        screenRecordConfigDialog = false
                    }) {
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
    }
}