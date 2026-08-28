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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import fake.screenshot.wrappers.RepackIdentity
import androidx.core.graphics.scale
import fake.screenshot.wrappers.RepackManager

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
    val hideFromRecent by ConfigManager.rememberValue(context, "hide_from_recent", false)
    val attemptFilter by ConfigManager.rememberValue(context, "attempt_filter", false)
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
    var externalStorageRequireDialog by remember { mutableStateOf(false) }
    var fileEncryptionWarnings by remember { mutableStateOf(false) }
    var hideIconWarnings by remember { mutableStateOf(false) }
    var installPackageRequireDialog by remember { mutableStateOf(false) }
    var isDaemonRunning by remember { mutableStateOf(false) }
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
                                    Environment.isExternalStorageManager() -> fileEncryptionWarnings = true

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
                                    val wasRunning = isDaemonRunning
                                    isDaemonRunning = !DaemonManager.stopDaemon()
                                    ConfigManager.saveData(
                                        context,
                                        "daemon_socket_port",
                                        newPort
                                    )
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
        if (fileEncryptionWarnings) {
            CenteredAlertDialog(
                onDismissRequest = { },
                title = {
                    Text(text = stringResource(R.string.warning))
                },
                text = {
                    Text(stringResource(R.string.software_hardware_encryption_warnings))
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