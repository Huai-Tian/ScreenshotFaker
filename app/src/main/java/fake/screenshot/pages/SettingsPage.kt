package fake.screenshot.pages

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.material3.RadioButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.NavController
import fake.screenshot.Auxiliary
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.DaemonManager
import fake.screenshot.styles.*
import fake.screenshot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import fake.screenshot.wrappers.RepackIdentity
import androidx.core.graphics.scale
import fake.screenshot.wrappers.RepackManager
import fake.screenshot.defense.GateManager
import fake.screenshot.defense.IdleWatchdog
import androidx.compose.ui.text.input.PasswordVisualTransformation

/** 密码熵估算（位）：长度 × log2(字符池大小)。粗估但足够指导用户 */
private fun estimatePasswordBits(pw: String): Int {
    if (pw.isEmpty()) return 0
    var pool = 0
    if (pw.any { it.isDigit() }) pool += 10
    if (pw.any { it.isLowerCase() }) pool += 26
    if (pw.any { it.isUpperCase() }) pool += 26
    if (pw.any { !it.isLetterOrDigit() }) pool += 33
    if (pool == 0) pool = 1
    return (pw.length * kotlin.math.log2(pool.toDouble())).toInt()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCompose(navController: NavController) {
    // 状态管理
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkUpdate by ConfigManager.rememberValue(context, "check_update", true)
    val enableFlagSecure by ConfigManager.rememberValue(context, "enable_flag_secure", true)
    val encryptOutputs by ConfigManager.rememberValue(context, "encrypt_outputs", false)
    val hideIcon by ConfigManager.rememberValue(context, "hide_icon", false)
    val hideFromRecent by ConfigManager.rememberValue(context, "hide_from_recent", true)
    val attemptFilter by ConfigManager.rememberValue(context, "attempt_filter", false)
    val definedTimestamp by ConfigManager.rememberValue(context, "defined_timestamp", "")
    var definedTimestampInputText by remember { mutableStateOf(definedTimestamp) }
    val daemonSocketPort by ConfigManager.rememberValue(
        context,
        "daemon_socket_port",
        1234
    )
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
            )

        }
    }
    var daemonConfigDialog by remember { mutableStateOf(false) }
    var timestampConfigDialog by remember { mutableStateOf(false) }
    var externalStorageRequireDialog by remember { mutableStateOf(false) }
    var fileEncryptionWarnings by remember { mutableStateOf(false) }
    var hideIconWarnings by remember { mutableStateOf(false) }
    var installPackageRequireDialog by remember { mutableStateOf(false) }
    var isDaemonRunning by remember { mutableStateOf(false) }
    //Gate
    var gateEnabled by remember { mutableStateOf(GateManager.isGateEnabled()) }
    var passwordConfigDialog by remember { mutableStateOf(false) }
    var currentPasswordInputText by remember { mutableStateOf("") }
    var newPasswordInputText by remember { mutableStateOf("") }
    var confirmPasswordInputText by remember { mutableStateOf("") }
    var coercionPasswordInputText by remember { mutableStateOf("") }
    var currentPasswordWrong by remember { mutableStateOf(false) }
    var passwordWorking by remember { mutableStateOf(false) }
    //Idle timeout destroy
    var idleTimeoutDialog by remember { mutableStateOf(false) }
    var idleCurrentLimit by remember { mutableStateOf<Long?>(null) }
    var idleSelectedLimit by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        idleCurrentLimit = IdleWatchdog.getCurrentIdleTimeout()
        idleSelectedLimit = idleCurrentLimit
    }
    val isPasswordConfigValid by remember {
        derivedStateOf {
            val currentOk = !gateEnabled || currentPasswordInputText.isNotEmpty()
            val matchOk = newPasswordInputText == confirmPasswordInputText
            // 两级密码相同会使胁迫密码永不命中（verifyGate 先查安全密码），
            // 必须禁止——否则用户误以为有胁迫保护，实际是死密码
            val coercionDistinct = coercionPasswordInputText.isEmpty() ||
                    coercionPasswordInputText != newPasswordInputText
            // 新密码非空 = 设置/修改；已启用且三项全空 = 移除保护
            val actionOk = newPasswordInputText.isNotEmpty() ||
                    (gateEnabled && confirmPasswordInputText.isEmpty() && coercionPasswordInputText.isEmpty())
            currentOk && matchOk && coercionDistinct && actionOk
        }
    }
    val isTimestampValid by remember {
        derivedStateOf {
            definedTimestampInputText.let {
                it.isEmpty() || try {
                    java.time.LocalDateTime.parse(
                        it,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d H:m")
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
    //Repack
    var repackConfigDialog by remember { mutableStateOf(false) }
    var repackPackageNameInputText by remember { mutableStateOf("") }
    var repackAppNameEnInputText by remember { mutableStateOf("") }
    var repackAppNameZhInputText by remember { mutableStateOf("") }
    var repackDescriptionEnInputText by remember { mutableStateOf("") }
    var repackDescriptionZhInputText by remember { mutableStateOf("") }
    var repackIcon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var repackIconCropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var repackRepacking by remember { mutableStateOf(false) }
    var repackMessage by remember { mutableStateOf<String?>(null) }
    val repackIconPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)?.let { bitmap ->
                        val max = 512
                        val scale = max.toFloat() / maxOf(bitmap.width, bitmap.height)
                        repackIconCropSource = if (scale < 1f) {
                            bitmap.let {
                                it.scale(
                                    (it.width * scale).toInt().coerceAtLeast(1),
                                    (it.height * scale).toInt().coerceAtLeast(1)
                                )
                            }
                        } else bitmap
                    }
                }
            }
        }
    }
    val isRepackInputValid by remember {
        derivedStateOf {
            RepackIdentity(
                packageName = repackPackageNameInputText,
                appNameEn = repackAppNameEnInputText,
                appNameZh = repackAppNameZhInputText,
                descriptionEn = repackDescriptionEnInputText,
                descriptionZh = repackDescriptionZhInputText
            ).validate() == null
        }
    }
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
                                val activity = context as? Activity
                                if (it) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                ConfigManager.saveData(context, "enable_flag_secure", it)
                            }
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.Password,
                        title = stringResource(R.string.app_lock),
                        subtitle = if (gateEnabled) stringResource(R.string.app_lock_enabled)
                        else stringResource(R.string.app_lock_description),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            currentPasswordInputText = ""
                            newPasswordInputText = ""
                            confirmPasswordInputText = ""
                            coercionPasswordInputText = ""
                            currentPasswordWrong = false
                            passwordConfigDialog = true
                        }
                    )
                    if (!gateEnabled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.no_gate_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.Timer,
                        title = stringResource(R.string.idle_timeout_destroy),
                        subtitle = idleCurrentLimit?.let {
                            formatIdleTimeoutLabel(it)
                        } ?: stringResource(R.string.idle_timeout_destroy_description),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            idleSelectedLimit = idleCurrentLimit
                            idleTimeoutDialog = true
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
                                    !it -> {
                                        ConfigManager.saveData(context, "encrypt_outputs", false)
                                        DaemonManager.syncConfig()
                                    }

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
                            if (newValue && !(Auxiliary.isShellActivated || Auxiliary.isRootActivated)) {
                                //权限不足
                                return@TwoStatePreference
                            }
                            scope.launch {
                                // IO 线程执行：startDaemon 的端口占用探测是
                                // Socket 连接（主线程抛 NetworkOnMainThreadException
                                // 被 catch 吞掉 → 探测恒失效）；stopDaemon 兜底
                                // 路径的 pkill/pgrep 会阻塞主线程数秒
                                isDaemonRunning = if (newValue) {
                                    withContext(Dispatchers.IO) { DaemonManager.startDaemon() }
                                } else {
                                    !withContext(Dispatchers.IO) { DaemonManager.stopDaemon() }
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
                        icon = Icons.Default.AutoFixHigh,
                        title = stringResource(R.string.customize_file_timestamp),
                        subtitle = stringResource(R.string.customize_file_timestamp_description),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            definedTimestampInputText = definedTimestamp
                            timestampConfigDialog = true
                        }
                    )
                }
            }
            item {
                CommonCard {
                    TwoStatePreference(
                        icon = Icons.Default.VisibilityOff,
                        title = stringResource(R.string.hide_application_icon),
                        subtitle = stringResource(R.string.hide_application_icon_description),
                        checked = hideIcon,
                        onCheckedChange = {
                            scope.launch {
                                if (it) {
                                    hideIconWarnings = true
                                } else {
                                    context.apply {
                                        packageManager.setComponentEnabledSetting(
                                            ComponentName(
                                                packageName,
                                                "$packageName.MainActivityAlias"
                                            ),
                                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                            PackageManager.DONT_KILL_APP
                                        )
                                    }
                                    ConfigManager.saveData(context, "hide_icon", false)
                                }
                            }
                        }
                    )
                }
            }
            item {
                CommonCard {
                    TwoStatePreference(
                        icon = Icons.Default.LayersClear,
                        title = stringResource(R.string.hide_from_recent),
                        subtitle = stringResource(R.string.hide_this_application_from_recent_tasks),
                        checked = hideFromRecent,
                        onCheckedChange = {
                            scope.launch {
                                context.getSystemService(ActivityManager::class.java)
                                    .appTasks.forEach { task -> task.setExcludeFromRecents(it) }
                                ConfigManager.saveData(context, "hide_from_recent", it)
                            }
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
            item {
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.DesignServices,
                        title = stringResource(R.string.custom_application_features),
                        subtitle = stringResource(R.string.custom_application_features_description),
                        trailingContent = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            if (!context.packageManager.canRequestPackageInstalls()) installPackageRequireDialog =
                                true
                            else repackConfigDialog = true
                        }
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
                                if (portChanged) {
                                    // 停旧 daemon 失败时中止端口变更：旧实例仍占着
                                    // 旧端口，改端口后 startDaemon 在新端口探测不到它
                                    // → 拉起第二个实例，旧 daemon 从此无法经信道停止
                                    val wasRunning = isDaemonRunning
                                    val stopped = withContext(Dispatchers.IO) {
                                        DaemonManager.stopDaemon()
                                    }
                                    if (wasRunning && !stopped) return@launch
                                    isDaemonRunning = !stopped
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_socket_port",
                                        newPort
                                    )
                                    if (wasRunning) isDaemonRunning =
                                        withContext(Dispatchers.IO) { DaemonManager.startDaemon() }
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
                                DaemonManager.syncConfig()
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
        if (timestampConfigDialog) {
            CenteredAlertDialog(
                onDismissRequest = { timestampConfigDialog = false },
                title = { Text(stringResource(R.string.customize_file_timestamp)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = definedTimestampInputText,
                            onValueChange = { definedTimestampInputText = it },
                            label = { Text(stringResource(R.string.disable_if_empty)) },
                            placeholder = {
                                Text(
                                    "e.g. ${
                                        java.time.LocalDateTime.now()
                                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d H:m"))
                                    }"
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.DateTime),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (definedTimestamp != definedTimestampInputText) {
                                    ConfigManager.saveData(
                                        context,
                                        "defined_timestamp",
                                        definedTimestampInputText
                                    )
                                    DaemonManager.syncConfig()
                                }
                            }
                            timestampConfigDialog = false
                        },
                        enabled = isTimestampValid
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { timestampConfigDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (passwordConfigDialog) {
            CenteredAlertDialog(
                onDismissRequest = { if (!passwordWorking) passwordConfigDialog = false },
                title = {
                    Text(
                        stringResource(
                            if (gateEnabled) R.string.change_password else R.string.set_password
                        )
                    )
                },
                text = {
                    Column {
                        if (gateEnabled) {
                            OutlinedTextField(
                                value = currentPasswordInputText,
                                onValueChange = {
                                    currentPasswordInputText = it
                                    currentPasswordWrong = false
                                },
                                label = { Text(stringResource(R.string.current_password)) },
                                isError = currentPasswordWrong,
                                supportingText = if (currentPasswordWrong) {
                                    { Text(stringResource(R.string.incorrect_password)) }
                                } else null,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = newPasswordInputText,
                            onValueChange = { newPasswordInputText = it },
                            label = { Text(stringResource(R.string.new_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (newPasswordInputText.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val bits = estimatePasswordBits(newPasswordInputText)
                            Text(
                                text = stringResource(R.string.password_strength_hint, bits) +
                                        " · " + when {
                                    bits < 40 -> stringResource(R.string.password_strength_weak)
                                    bits < 60 -> stringResource(R.string.password_strength_medium)
                                    else -> stringResource(R.string.password_strength_strong)
                                },
                                fontSize = 9.sp,
                                color = when {
                                    bits < 40 -> MaterialTheme.colorScheme.error
                                    bits < 60 -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmPasswordInputText,
                            onValueChange = { confirmPasswordInputText = it },
                            label = { Text(stringResource(R.string.confirm_new_password)) },
                            isError = confirmPasswordInputText.isNotEmpty() &&
                                    confirmPasswordInputText != newPasswordInputText,
                            supportingText = if (confirmPasswordInputText.isNotEmpty() &&
                                confirmPasswordInputText != newPasswordInputText
                            ) {
                                { Text(stringResource(R.string.password_mismatch)) }
                            } else null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = coercionPasswordInputText,
                            onValueChange = { coercionPasswordInputText = it },
                            label = { Text(stringResource(R.string.coercion_password)) },
                            isError = coercionPasswordInputText.isNotEmpty() &&
                                    coercionPasswordInputText == newPasswordInputText,
                            supportingText = {
                                Text(
                                    text = when {
                                        coercionPasswordInputText.isNotEmpty() &&
                                                coercionPasswordInputText == newPasswordInputText ->
                                            stringResource(R.string.coercion_password_same)

                                        gateEnabled && coercionPasswordInputText.isEmpty() ->
                                            stringResource(R.string.coercion_password_clear)

                                        else -> stringResource(R.string.coercion_password_hint)
                                    },
                                    fontSize = 9.sp,
                                    color = if (coercionPasswordInputText.isNotEmpty() &&
                                        coercionPasswordInputText == newPasswordInputText
                                    ) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (gateEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.password_empty_hint),
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            passwordWorking = true
                            scope.launch {
                                val currentOk = !gateEnabled ||
                                        GateManager.verifyGatePassword(currentPasswordInputText)
                                when {
                                    !currentOk -> currentPasswordWrong = true
                                    newPasswordInputText.isEmpty() -> {
                                        // 当前密码已验证，三项全空 = 移除保护
                                        GateManager.removeGate(currentPasswordInputText)
                                        gateEnabled = false
                                        passwordConfigDialog = false
                                    }

                                    else -> {
                                        GateManager.setPasswords(
                                            currentPasswordInputText,
                                            newPasswordInputText,
                                            coercionPasswordInputText
                                        )
                                        gateEnabled = true
                                        passwordConfigDialog = false
                                    }
                                }
                                passwordWorking = false
                            }
                        },
                        enabled = isPasswordConfigValid && !passwordWorking
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { passwordConfigDialog = false },
                        enabled = !passwordWorking
                    ) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (idleTimeoutDialog) {
            CenteredAlertDialog(
                onDismissRequest = { idleTimeoutDialog = false },
                title = { Text(stringResource(R.string.idle_timeout_destroy)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.idle_timeout_destroy_warning),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // 无禁用项：只有时长档位，一旦启用不可关闭
                        IdleWatchdog.idleTimeoutOptions.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { idleSelectedLimit = option },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = idleSelectedLimit == option,
                                    onClick = { idleSelectedLimit = option }
                                )
                                Text(text = formatIdleTimeoutLabel(option))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            idleSelectedLimit?.let { selected ->
                                scope.launch {
                                    IdleWatchdog.setIdleTimeout(selected)
                                    idleCurrentLimit = selected
                                }
                            }
                            idleTimeoutDialog = false
                        },
                        enabled = idleSelectedLimit != null
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { idleTimeoutDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (hideIconWarnings) {
            CenteredAlertDialog(
                onDismissRequest = { },
                title = {
                    Text(text = stringResource(R.string.warning))
                },
                text = {
                    Text(stringResource(R.string.hide_application_icon_warnings))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                context.apply {
                                    packageManager.setComponentEnabledSetting(
                                        ComponentName(
                                            packageName,
                                            "$packageName.MainActivityAlias"
                                        ),
                                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                        PackageManager.DONT_KILL_APP
                                    )
                                }
                                ConfigManager.saveData(context, "hide_icon", true)
                            }
                            hideIconWarnings = false
                        },
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { hideIconWarnings = false }) {
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
        if (installPackageRequireDialog) {
            CenteredAlertDialog(
                onDismissRequest = { installPackageRequireDialog = false },
                title = {
                    Text(text = stringResource(R.string.tips))
                },
                text = {
                    Text(stringResource(R.string.install_package_permission_required))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val intent =
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                            context.startActivity(intent)
                            installPackageRequireDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.go_to_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { installPackageRequireDialog = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        if (repackConfigDialog) {
            val cannotInstall = stringResource(R.string.cannot_install)
            val packagingFailed = stringResource(R.string.packaging_failed)
            val packagingSuccess = stringResource(R.string.packaging_success)
            // 按系统语言排序输入框：简体中文环境中文字段在前，
            // 其他语言英文字段在前（用户最可能填写的放最显眼位置）
            val zhFirst = remember { RepackIdentity.detectSimplifiedChinese() }
            CenteredAlertDialog(
                onDismissRequest = {
                    if (!repackRepacking) repackConfigDialog = false
                },
                title = { Text(stringResource(R.string.custom_application_features)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = repackPackageNameInputText,
                            onValueChange = { repackPackageNameInputText = it.trim() },
                            label = { Text(stringResource(R.string.new_package_name)) },
                            placeholder = { Text("com.example.notes") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !repackRepacking
                        )
                        if (zhFirst) {
                            OutlinedTextField(
                                value = repackAppNameZhInputText,
                                onValueChange = { repackAppNameZhInputText = it },
                                label = { Text(stringResource(R.string.application_name_chinese_simplified)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !repackRepacking
                            )
                            OutlinedTextField(
                                value = repackDescriptionZhInputText,
                                onValueChange = { repackDescriptionZhInputText = it },
                                label = { Text(stringResource(R.string.application_description_chinese_simplified)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !repackRepacking
                            )
                            OutlinedTextField(
                                value = repackAppNameEnInputText,
                                onValueChange = { repackAppNameEnInputText = it },
                                label = { Text(stringResource(R.string.application_name_english)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !repackRepacking
                            )
                            OutlinedTextField(
                                value = repackDescriptionEnInputText,
                                onValueChange = { repackDescriptionEnInputText = it },
                                label = { Text(stringResource(R.string.application_description_english)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !repackRepacking
                            )
                        } else {
                            OutlinedTextField(
                                value = repackAppNameEnInputText,
                                onValueChange = { repackAppNameEnInputText = it },
                                label = { Text(stringResource(R.string.application_name_english)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !repackRepacking
                            )
                            OutlinedTextField(
                                value = repackAppNameZhInputText,
                                onValueChange = { repackAppNameZhInputText = it },
                                label = { Text(stringResource(R.string.application_name_chinese_simplified)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !repackRepacking
                            )
                            OutlinedTextField(
                                value = repackDescriptionEnInputText,
                                onValueChange = { repackDescriptionEnInputText = it },
                                label = { Text(stringResource(R.string.application_description_english)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !repackRepacking
                            )
                            OutlinedTextField(
                                value = repackDescriptionZhInputText,
                                onValueChange = { repackDescriptionZhInputText = it },
                                label = { Text(stringResource(R.string.application_description_chinese_simplified)) },
                                placeholder = { Text(stringResource(R.string.keep_current_if_blank)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !repackRepacking
                            )
                        }
                        PreferenceItemEx(
                            icon = Icons.Default.Image,
                            title = if (repackIcon == null) stringResource(R.string.choose_new_icon) else stringResource(
                                R.string.icon_selected
                            ),
                            subtitle = stringResource(R.string.retain_if_not_selected),
                            trailingContent = {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                if (!repackRepacking) repackIconPicker.launch("image/*")
                            }
                        )
                        if (repackIcon != null) {
                            repackIcon?.let {
                                androidx.compose.foundation.Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .size(72.dp)
                                )
                            }
                        }
                        if (repackRepacking) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        repackMessage?.let {
                            Text(
                                text = it,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val identity = RepackIdentity(
                                packageName = repackPackageNameInputText,
                                appNameEn = repackAppNameEnInputText,
                                appNameZh = repackAppNameZhInputText,
                                descriptionEn = repackDescriptionEnInputText,
                                descriptionZh = repackDescriptionZhInputText
                            )
                            val invalid = identity.validate()
                            if (invalid != null) {
                                repackMessage = invalid
                                return@TextButton
                            }
                            repackRepacking = true
                            repackMessage = null
                            scope.launch {
                                RepackManager.repack(context, identity, repackIcon).fold(
                                    onSuccess = { apk ->
                                        repackRepacking = false
                                        runCatching {
                                            RepackManager.install(
                                                context,
                                                apk,
                                                identity.packageName
                                            )
                                        }.onFailure {
                                            repackMessage =
                                                "$cannotInstall${it.message ?: it.javaClass.simpleName}"
                                        }.onSuccess {
                                            repackMessage = packagingSuccess
                                        }
                                    },
                                    onFailure = {
                                        repackMessage =
                                            "$packagingFailed${it.message ?: it.javaClass.simpleName}"
                                        repackRepacking = false
                                    }
                                )
                            }
                        },
                        enabled = !repackRepacking && isRepackInputValid
                    ) {
                        Text(stringResource(R.string.package_and_install))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { repackConfigDialog = false },
                        enabled = !repackRepacking
                    ) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
        repackIconCropSource?.let { source ->
            IconCropDialog(
                image = source,
                onConfirm = { cropped ->
                    repackIcon = cropped
                    repackIconCropSource = null
                },
                onDismiss = { repackIconCropSource = null }
            )
        }
    }
}

/** 档位分钟数 → 本地化标签（与 IdleWatchdog.idleTimeoutOptions 一一对应） */
@Composable
fun formatIdleTimeoutLabel(minutes: Long): String = when (minutes) {
    5L -> stringResource(R.string.idle_option_5_minutes)
    30L -> stringResource(R.string.idle_option_30_minutes)
    60L -> stringResource(R.string.idle_option_1_hour)
    360L -> stringResource(R.string.idle_option_6_hours)
    1440L -> stringResource(R.string.idle_option_1_day)
    10080L -> stringResource(R.string.idle_option_1_week)
    43200L -> stringResource(R.string.idle_option_1_month)
    129600L -> stringResource(R.string.idle_option_3_months)
    259200L -> stringResource(R.string.idle_option_6_months)
    525600L -> stringResource(R.string.idle_option_12_months)
    else -> stringResource(R.string.unknown)
}