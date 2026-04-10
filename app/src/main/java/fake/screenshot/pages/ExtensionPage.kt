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
    val screenshotSavePath by ConfigManager.rememberValue(
        context,
        "screenshot_save_path",
        "${Environment.getExternalStorageDirectory().path}/DCIM/ScreenshotFaker/Screenshots"
    )
    var screenshotConfigDialog by remember { mutableStateOf(false) }
    var screenshotConfigDialogInputText by remember { mutableStateOf(screenshotSavePath) }
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
                            screenshotConfigDialogInputText = screenshotSavePath
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
                        onClick = { /*TODO*/ }
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
                    Text(text = stringResource(R.string.config_stealth_screenshot)) // 标题 A
                },
                text = {
                    OutlinedTextField(
                        value = screenshotConfigDialogInputText,
                        onValueChange = { screenshotConfigDialogInputText = it }, // 可编辑
                        label = { Text(stringResource(R.string.stealth_screenshot_save_path)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            ConfigManager.saveData(
                                context,
                                "screenshot_save_path",
                                screenshotConfigDialogInputText.removeSuffix("/")
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
    }
}