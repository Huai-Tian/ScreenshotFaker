package fake.screenshot.pages

import android.app.Activity
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import fake.screenshot.Auxiliary
import fake.screenshot.ConfigManager
import fake.screenshot.DaemonManager
import fake.screenshot.R
import kotlinx.coroutines.launch
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCompose(navController: NavController) {
    // 状态管理
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkUpdate by ConfigManager.rememberValue(context, "check_update", true)
    val enableFlagSecure by ConfigManager.rememberValue(context, "enable_flag_secure", true)
    val encryptOutputs by ConfigManager.rememberValue(context, "encrypt_outputs", false)
    val attemptFilter by ConfigManager.rememberValue(context, "attempt_filter", false)
    val daemonSocketPort by ConfigManager.rememberValue(
        context,
        "daemon_socket_port",
        1234
    )
    val daemonVerificationPassword by ConfigManager.rememberValue(
        context,
        "daemon_verification_password",
        "ScreenshotFaker"
    )
    var daemonVerificationPasswordInputText by remember { mutableStateOf(daemonVerificationPassword) }
    var daemonSocketPortInputText by remember { mutableStateOf(daemonSocketPort.toString()) }
    val daemonConfigSeparator by ConfigManager.rememberValue(
        context,
        "daemon_config_separator",
        "#"
    )
    val daemonScreenshotConfig by ConfigManager.rememberValue(
        context,
        "daemon_screenshot_config",
        ""
    )
    val daemonScreenRecordConfig by ConfigManager.rememberValue(
        context,
        "daemon_screenRecord_config",
        ""
    )
    val daemonScreenShareConfig by ConfigManager.rememberValue(
        context,
        "daemon_screenshare_config",
        ""
    )
    var daemonConfigSeparatorInputText by remember { mutableStateOf(daemonConfigSeparator) }
    var daemonScreenshotConfigInputText by remember { mutableStateOf(daemonScreenshotConfig) }
    var daemonScreenRecordConfigInputText by remember { mutableStateOf(daemonScreenRecordConfig) }
    var daemonScreenShareConfigInputText by remember { mutableStateOf(daemonScreenShareConfig) }
    val isDaemonConfigValid by remember {
        derivedStateOf {
            val validPriorityLetters = setOf('V', 'D', 'I', 'W', 'E', 'F')
            fun checkConfig(vararg inputs: String): Boolean = inputs.all { input ->
                val parts = input.split(daemonConfigSeparatorInputText)
                input.isEmpty() || (
                        parts.size == 3
                                && ((parts[0].length == 1 && parts[0][0] in validPriorityLetters) || parts[0].isEmpty())
                                && parts[1].isNotEmpty()
                                && parts[2].isNotEmpty()
                                && Auxiliary.isConfigValid(parts[1])
                                && Auxiliary.isRegexValid(parts[2]))
            }

            val portValid =
                daemonSocketPortInputText.toIntOrNull().let { it != null && it in 1024..65535 }
            val separatorValid = daemonConfigSeparatorInputText.isNotBlank()
            portValid && separatorValid && checkConfig(
                daemonScreenshotConfigInputText,
                daemonScreenRecordConfigInputText,
                daemonScreenShareConfigInputText
            ) && daemonVerificationPasswordInputText.isNotBlank()

        }
    }
    var daemonConfigDialog by remember { mutableStateOf(false) }
    var externalStorageRequireDialog by remember { mutableStateOf(false) }
    var fileEncryptionWarnings by remember { mutableStateOf(false) }
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
            contentPadding = PaddingValues(bottom = 20.dp),
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
                        icon = Icons.Default.AppBlocking,
                        title = stringResource(R.string.enable_page_protection),
                        subtitle = stringResource(R.string.protect_pages_from_screenshotting),
                        checked = enableFlagSecure,
                        onCheckedChange = {
                            scope.launch {
                                ConfigManager.saveData(context, "enable_flag_secure", it)
                                val activity = context as? Activity
                                if (it) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                        }
                    )
                }
            }
            item {
                CommonCard {
                    TwoStatePreference(
                        icon = Icons.Default.Lock,
                        title = stringResource(R.string.encrypt_outputs),
                        subtitle = stringResource(R.string.auto_encrypt_outputs),
                        checked = encryptOutputs,
                        onCheckedChange = {
                            scope.launch {
                                when {
                                    !it -> ConfigManager.saveData(context, "encrypt_outputs", false)
                                    Environment.isExternalStorageManager() -> fileEncryptionWarnings =
                                        true

                                    else -> externalStorageRequireDialog = true
                                }
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
                            onClick = { navController.navigate("daemon_status") }
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
                            daemonVerificationPasswordInputText = daemonVerificationPassword
                            daemonSocketPortInputText = daemonSocketPort.toString()
                            daemonConfigSeparatorInputText = daemonConfigSeparator
                            daemonScreenshotConfigInputText = daemonScreenshotConfig
                            daemonScreenRecordConfigInputText = daemonScreenRecordConfig
                            daemonScreenShareConfigInputText = daemonScreenShareConfig
                            daemonConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.CastConnected,
                        title = stringResource(R.string.receive_stealth_screen_sharing),
                        subtitle = stringResource(R.string.receive_screen_sharing_from_ScreenshotFaker),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = { navController.navigate("receive_screen_sharing") }
                    )
                }
            }
            if (Auxiliary.isModuleActivated) {
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
                        onClick = { navController.navigate("about") }
                    )
                }
            }
        }
        if (daemonConfigDialog) {
            CenteredAlertDialog(
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
                            value = daemonVerificationPasswordInputText,
                            onValueChange = { daemonVerificationPasswordInputText = it }, // 可编辑
                            label = { Text(stringResource(R.string.verification_password)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = daemonConfigSeparatorInputText,
                            onValueChange = { daemonConfigSeparatorInputText = it },
                            label = { Text(stringResource(R.string.config_separator)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = daemonScreenshotConfigInputText,
                            onValueChange = { daemonScreenshotConfigInputText = it },
                            label = { Text(stringResource(R.string.screenshot_condition)) },
                            placeholder = {
                                Text(
                                    "LV" + daemonConfigSeparatorInputText
                                            + "TAG" + daemonConfigSeparatorInputText + "MSG"
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = daemonScreenRecordConfigInputText,
                            onValueChange = { daemonScreenRecordConfigInputText = it },
                            label = { Text(stringResource(R.string.screenRecord_condition)) },
                            placeholder = {
                                Text(
                                    "LV" + daemonConfigSeparatorInputText
                                            + "TAG" + daemonConfigSeparatorInputText + "MSG"
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = daemonScreenShareConfigInputText,
                            onValueChange = { daemonScreenShareConfigInputText = it },
                            label = { Text(stringResource(R.string.screenShare_condition)) },
                            placeholder = {
                                Text(
                                    "LV" + daemonConfigSeparatorInputText
                                            + "TAG" + daemonConfigSeparatorInputText + "MSG"
                                )
                            },
                            supportingText = {
                                Text(
                                    text = "LV <- (V/D/I/W/E/F/S)\n" +
                                            stringResource(R.string.disable_if_empty),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
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
                                if (daemonConfigSeparator != daemonConfigSeparatorInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_config_separator",
                                        daemonConfigSeparatorInputText
                                    )
                                    configChanged = true
                                }
                                if (daemonScreenshotConfig != daemonScreenshotConfigInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_screenshot_config",
                                        daemonScreenshotConfigInputText
                                    )
                                    configChanged = true
                                }
                                if (daemonScreenRecordConfig != daemonScreenRecordConfigInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_screenRecord_config",
                                        daemonScreenRecordConfigInputText
                                    )
                                    configChanged = true
                                }
                                if (daemonScreenShareConfig != daemonScreenShareConfigInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_screenshare_config",
                                        daemonScreenShareConfigInputText
                                    )
                                    configChanged = true
                                }
                                if (configChanged) {
                                    DaemonManager.syncConfig()
                                }
                                val newPort = daemonSocketPortInputText.toInt()
                                val portChanged = daemonSocketPort != newPort
                                val passwordChanged =
                                    daemonVerificationPassword != daemonVerificationPasswordInputText
                                if (portChanged || passwordChanged) {
                                    val wasRunning = isDaemonRunning
                                    isDaemonRunning = !DaemonManager.stopDaemon()
                                    if (portChanged) {
                                        ConfigManager.saveData(
                                            context,
                                            "daemon_socket_port",
                                            newPort
                                        )
                                    }
                                    if (passwordChanged) {
                                        ConfigManager.saveData(
                                            context,
                                            "daemon_verification_password",
                                            daemonVerificationPasswordInputText
                                        )
                                    }
                                    if (wasRunning) isDaemonRunning = DaemonManager.startDaemon()
                                }
                            }
                            daemonConfigDialog = false
                        },
                        enabled = isDaemonConfigValid
                    ) {
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
        if (externalStorageRequireDialog) {
            CenteredAlertDialog(
                onDismissRequest = { externalStorageRequireDialog = false },
                title = {
                    Text(text = stringResource(R.string.tips))
                },
                text = {
                    Text(stringResource(R.string.full_storage_access_required))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val intent =
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                            context.startActivity(intent)
                            externalStorageRequireDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.go_to_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { externalStorageRequireDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (fileEncryptionWarnings) {
            CenteredAlertDialog(
                onDismissRequest = { },
                title = {
                    Text(text = stringResource(R.string.warning))
                },
                text = {
                    Text(stringResource(R.string.hardware_encryption_warnings))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                ConfigManager.saveData(context, "encrypt_outputs", true)
                            }
                            fileEncryptionWarnings = false
                        },
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileEncryptionWarnings = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
    }
}

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

@Composable
fun CenteredAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = shape,
            color = containerColor,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                if (title != null) {
                    ProvideTextStyle(value = MaterialTheme.typography.titleLarge) {
                        title()
                    }
                    Spacer(modifier = Modifier.height(if (text != null) 16.dp else 24.dp))
                }
                if (text != null) {
                    ProvideTextStyle(value = MaterialTheme.typography.bodyMedium) {
                        text()
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (dismissButton != null) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            dismissButton()
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        confirmButton()
                    }
                }
            }
        }
    }
}