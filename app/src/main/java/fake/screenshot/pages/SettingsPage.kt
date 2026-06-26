package fake.screenshot.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.setValue
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
fun SettingsCompose() {
    // 状态管理
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkUpdate by ConfigManager.rememberValue(context, "check_update", true)
    val attemptFilter by ConfigManager.rememberValue(context, "attempt_filter", false)
    val daemonSocketPort by ConfigManager.rememberValue(
        context,
        "daemon_socket_port",
        1234
    )
    var daemonSocketPortInputText by remember { mutableStateOf(daemonSocketPort.toString()) }
    var daemonConfigDialog by remember { mutableStateOf(false) }
    var isDaemonRunning by remember { mutableStateOf(false) }
    LaunchedEffect(daemonSocketPort) {
        isDaemonRunning = DaemonManager.isDaemonRunning()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
                    TwoStatePreference(
                        icon = Icons.Default.Update,
                        title = stringResource(R.string.check_update),
                        subtitle = stringResource(R.string.auto_check_update),
                        checked = checkUpdate,
                        onCheckedChange = {
                            scope.launch {
                                ConfigManager.saveData(context, "check_update", it)
                            }
                        }
                    )
                }
            }
            item {
                CommonCard {
                    TwoStatePreference(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.start_daemon),
                        subtitle = stringResource(R.string.start_daemon_to_work_background),
                        checked = isDaemonRunning,
                        onCheckedChange = { newValue ->
                            if (newValue && !(Auxiliary.isShellActivated || Auxiliary.isRootActivated())) {
                                //权限不足
                                return@TwoStatePreference
                            }
                            scope.launch {
                                isDaemonRunning = if (newValue) {
                                    DaemonManager.startDaemon()
                                } else {
                                    !DaemonManager.stopDaemon()
                                }
                            }
                        }
                    )
                }
            }
            if (isDaemonRunning) {
                item {
                    CommonCard {
                        PreferenceItemEx(
                            icon = Icons.Default.Dashboard,
                            title = stringResource(R.string.view_daemon_status),
                            subtitle = stringResource(R.string.click_to_view_daemon_status),
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
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.DataObject,
                        title = stringResource(R.string.config_daemon),
                        subtitle = stringResource(R.string.config_daemon_working_options),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            daemonSocketPortInputText = daemonSocketPort.toString()
                            daemonConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.CastConnected,
                        title = stringResource(R.string.receive_stealth_screen_casting),
                        subtitle = stringResource(R.string.receive_screen_casting_from_ScreenshotFaker),
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
            if (Auxiliary.isModuleActivated()) {
                item {
                    CommonCard {
                        TwoStatePreference(
                            icon = Icons.Default.Gavel,
                            title = stringResource(R.string.aggressive_detection_filtering),
                            subtitle = stringResource(R.string.filter_content_observer),
                            checked = attemptFilter,
                            onCheckedChange = {
                                scope.launch {
                                    ConfigManager.saveData(context, "attempt_filter", it)
                                }
                            }
                        )
                    }
                }
            }
            item {
                CommonCard {
                    PreferenceItem(
                        icon = Icons.Default.CloudUpload,
                        title = stringResource(R.string.backup_config),
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
                        icon = Icons.Default.Restore,
                        title = stringResource(R.string.restore_config),
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
                        icon = Icons.Default.ContactPage,
                        title = stringResource(R.string.about),
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
        if (daemonConfigDialog) {
            AlertDialog(
                onDismissRequest = { daemonConfigDialog = false },
                title = {
                    Text(text = stringResource(R.string.config_daemon)) // 标题
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = daemonSocketPortInputText,
                            onValueChange = { daemonSocketPortInputText = it }, // 可编辑
                            label = { Text(stringResource(R.string.socket_port)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            if (daemonSocketPortInputText.isDigitsOnly() && daemonSocketPort.toString() != daemonSocketPortInputText && daemonSocketPortInputText.toInt() in 1024..65535) {
                                if (isDaemonRunning) {
                                    isDaemonRunning = !DaemonManager.stopDaemon()
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_socket_port",
                                        daemonSocketPortInputText.toInt()
                                    )
                                    isDaemonRunning = DaemonManager.startDaemon()
                                } else {
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_socket_port",
                                        daemonSocketPortInputText.toInt()
                                    )
                                }
                            }
                        }
                        daemonConfigDialog = false
                    }) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { daemonConfigDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
    }
}

// 通用卡片容器：包裹单个设置项
@Composable
fun CommonCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = 0.3f
            )
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

// 列表项（带箭头或文本尾部）
@Composable
fun PreferenceItemEx(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),   // ← 关键：启用波纹
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 13.sp, color = Color.Gray)
        }
        trailingContent()
    }
}

@Composable
fun PreferenceItem(
    icon: ImageVector,
    title: String,
    trailingContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),   // ← 关键：启用波纹
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        trailingContent()
    }
}

// 开关项
@Composable
fun TwoStatePreference(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 13.sp, color = Color.Gray)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,        // 禁用 Switch 自身的点击
            enabled = true,               // 视觉上不可交互，避免叠加波纹
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color.Blue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
            )
        )
    }
}