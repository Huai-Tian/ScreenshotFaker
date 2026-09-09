package fake.screenshot.pages

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import fake.screenshot.Auxiliary
import fake.screenshot.R
import fake.screenshot.hooks.HookConfig
import fake.screenshot.hooks.HookTemplate
import fake.screenshot.wrappers.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模板页（HMA 三级结构第一级，布局仿 HMA TemplateManageFragment）：
 * 全局设置卡 + 新建入口行 + 模板列表（语义图标 + 配置摘要 + 已应用数，
 * 删除入口在编辑页 Toolbar 菜单，HMA 式）。TopAppBar info 菜单弹使用
 * 说明。配置唯一权威源经 [TemplateManager] 读写（加密 DataStore → 自动
 * 导出 RemotePreferences），UI 无独立状态——列表渲染 config 流，编辑页
 * 保存整份新配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateCompose(navController: NavController) {
    val context = LocalContext.current
    val config by TemplateManager.configFlow(context)
        .collectAsStateWithLifecycle(initialValue = HookConfig.DEFAULT)
    var showHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 截图替换选图（Photo Picker，不可用时框架自动回落系统选择器）
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch { importReplaceImage(context, config, uri) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.template)) },
            actions = {
                IconButton(onClick = { showHint = true }) {
                    Icon(Icons.Default.Info, contentDescription = null)
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.global_settings),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.global_secure_policy),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        SecurePolicySelector(config.globalSecurePolicy, { v ->
                            scope.launch {
                                TemplateManager.saveConfig(context, config.copy(globalSecurePolicy = v))
                            }
                        })
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        ReplaceEntryRow(
                            enabled = config.globalReplaceEnabled,
                            imageFile = replaceImageFile(context, config),
                            onToggle = { v ->
                                scope.launch {
                                    TemplateManager.saveConfig(context, config.copy(globalReplaceEnabled = v))
                                }
                            },
                            onPickImage = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.template_apps_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // 新建入口行（HMA 式：列表顶部 add 图标入口，非 TopAppBar 按钮）
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate("template_edit/") },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.template_new),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (config.templates.isNotEmpty()) {
                item { HorizontalDivider() }
            }
            if (config.templates.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.template_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(config.templates, key = { it.id }) { tpl ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { navController.navigate("template_edit/${tpl.id}") }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            featureIcon(tpl),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tpl.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(policyLabel(tpl.securePolicy))
                                    append(" · ")
                                    append(stringResource(R.string.template_masks_count, masksCount(tpl)))
                                    if (tpl.pierceFreeform) {
                                        append(" · ")
                                        append(stringResource(R.string.freeform_pierce))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(
                                    R.string.template_applied_count,
                                    config.scope.count { it.value == tpl.id }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHint) {
        AlertDialog(
            onDismissRequest = { showHint = false },
            title = { Text(stringResource(R.string.template)) },
            text = { Text(stringResource(R.string.template_usage_hint)) },
            confirmButton = {
                TextButton(onClick = { showHint = false }) {
                    Text(stringResource(R.string.Cancel))
                }
            }
        )
    }
}

/**
 * 模板编辑页（新建 route id 为空 / 编辑 route 带模板 id）。
 * 编辑模式下权威配置未到达前（DataStore 冷流首帧为 DEFAULT）显示
 * 加载态、表单不组合——若先以空值组合，数据到达后 key 值不变
 * （editing.id == templateId），remember 不会重置，表单将停留在
 * 空名称假象。imageId 字段暂不暴露（E3 替换引擎落地后随选图功能开放）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditCompose(navController: NavController, templateId: String) {
    val context = LocalContext.current
    val config by TemplateManager.configFlow(context)
        .collectAsStateWithLifecycle(initialValue = HookConfig.DEFAULT)
    val editing = if (templateId.isEmpty()) null else config.templates.firstOrNull { it.id == templateId }

    // 编辑模式三态分流：配置已到达且 id 存在 → 下方组合表单；
    // 已到达但 id 不存在（模板已被删）→ 退出；未到达（templates 仍为
    // DEFAULT 空）→ 加载态。新建模式（templateId 空）无此阶段
    if (templateId.isNotEmpty() && editing == null) {
        if (config.templates.isNotEmpty()) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    var confirmingDelete by remember { mutableStateOf(false) }
    key(editing?.id ?: "") {
        var name by remember { mutableStateOf(editing?.name ?: "") }
        var policy by remember { mutableStateOf(editing?.securePolicy ?: HookConfig.SECURE_FOLLOW) }
        var maskCapture by remember { mutableStateOf(editing?.maskCaptureDetection ?: false) }
        var maskRecord by remember { mutableStateOf(editing?.maskRecordDetection ?: false) }
        var maskOverlay by remember { mutableStateOf(editing?.maskOverlayDetection ?: false) }
        var pierceFreeform by remember { mutableStateOf(editing?.pierceFreeform ?: false) }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing == null && templateId.isEmpty()) R.string.template_new
                            else R.string.template_settings_title
                        )
                    )
                },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text(stringResource(R.string.Cancel))
                    }
                },
                actions = {
                    // 删除入口（HMA 式：编辑页 Toolbar 菜单 + 确认对话框；
                    // 仅编辑态可见——新建态无模板可删）
                    if (editing != null) {
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val saved = HookTemplate(
                                id = editing?.id ?: Auxiliary.getSecureRandomString(16),
                                name = name.trim(),
                                securePolicy = policy,
                                maskCaptureDetection = maskCapture,
                                maskRecordDetection = maskRecord,
                                maskOverlayDetection = maskOverlay,
                                pierceFreeform = pierceFreeform,
                                imageId = editing?.imageId, // 未暴露字段原样保留
                            )
                            val next = if (editing == null) {
                                config.copy(templates = config.templates + saved)
                            } else {
                                config.copy(templates = config.templates.map {
                                    if (it.id == saved.id) saved else it
                                })
                            }
                            scope.launch { TemplateManager.saveConfig(context, next) }
                            navController.popBackStack()
                        }
                    ) { Text(stringResource(R.string.save)) }
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.template_name)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                // 已应用于导航行（HMA 式：图标 + 单行计数主文本 + 编辑按钮 →
                // 应用多选页，模板视角反向管理 scope 分配；仅编辑态——
                // 新建态模板尚无 id，保存后再分配）
                if (editing != null) {
                    val appliedCount = config.scope.count { it.value == editing.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate("template_apps/${editing.id}") },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.template_applied_count, appliedCount),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { navController.navigate("template_apps/${editing.id}") }) {
                            Text(stringResource(R.string.template_apps_edit))
                        }
                    }
                }
                // 截屏限制：标签占左余宽，紧凑三态 chips 靠右同一水平线（无滚动）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.secure_policy),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    SecurePolicySelector(policy, { policy = it }, compact = true)
                }
                SwitchRow(stringResource(R.string.mask_capture_detection), maskCapture) { maskCapture = it }
                SwitchRow(stringResource(R.string.mask_record_detection), maskRecord) { maskRecord = it }
                SwitchRow(stringResource(R.string.mask_overlay_detection), maskOverlay) { maskOverlay = it }
                SwitchRow(stringResource(R.string.freeform_pierce), pierceFreeform) { pierceFreeform = it }
            }
        }
    }

    // 删除确认（HMA 式：编辑页触发；同时清除引用它的 scope 映射——
    // 悬空引用模型层容忍，UI 层主动清理避免"幽灵映射"困惑）
    if (confirmingDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.warning)) },
            text = { Text(stringResource(R.string.template_delete_confirm, editing.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        TemplateManager.saveConfig(
                            context,
                            config.copy(
                                templates = config.templates.filter { it.id != editing.id },
                                scope = config.scope.filterValues { it != editing.id }
                            )
                        )
                    }
                    confirmingDelete = false
                    navController.popBackStack()
                }) { Text(stringResource(R.string.Confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.Cancel))
                }
            }
        )
    }
}

// ==================== 复用小组件 ====================

/**
 * 模板语义图标（仿 HMA 按类型区分模板的图标语义；我们无模板类型，
 * 按最高优先级特征取图）：穿透 > 屏蔽 > 强制禁止 > 强制允许 > 空
 */
private fun featureIcon(tpl: HookTemplate): ImageVector = when {
    tpl.pierceFreeform -> Icons.Filled.PictureInPictureAlt
    tpl.maskCaptureDetection || tpl.maskRecordDetection || tpl.maskOverlayDetection ->
        Icons.Filled.VisibilityOff
    tpl.securePolicy == HookConfig.SECURE_DENY -> Icons.Filled.Lock
    tpl.securePolicy == HookConfig.SECURE_ALLOW -> Icons.Filled.LockOpen
    else -> Icons.Filled.Description
}

/**
 * 截图替换行（全局卡第二行）：Switch 是唯一启停入口；关闭时仅显示
 * "未启用"（配置状态静默保留，不显示缩略图与是否配置）；开启时点击
 * 行主体弹选图器，副标题显示 未配置/已配置，已配置时带缩略图预览。
 * 纯 UI 阶段：hook 侧暂不消费（E3 落地接入）。
 */
@Composable
private fun ReplaceEntryRow(
    enabled: Boolean,
    imageFile: File?,
    onToggle: (Boolean) -> Unit,
    onPickImage: () -> Unit,
) {
    val configured = imageFile?.exists() == true
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (enabled) Modifier.clickable(onClick = onPickImage) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enabled && configured && imageFile != null) {
                ReplaceThumb(imageFile)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    stringResource(R.string.screenshot_replace),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = when {
                        !enabled -> stringResource(R.string.replace_state_disabled)
                        configured -> stringResource(R.string.replace_state_configured)
                        else -> stringResource(R.string.replace_state_not_configured)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled && configured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** 已配置缩略图（降采样解码，~256px 预览） */
@Composable
private fun ReplaceThumb(file: File) {
    val bitmap = remember(file.absolutePath, file.length(), file.lastModified()) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

/** 替换图落盘位置（files/replace/ + 配置中的中性文件名） */
private fun replaceImageFile(context: Context, config: HookConfig): File? =
    config.globalReplaceImage?.let { File(File(context.filesDir, "replace"), it) }

/**
 * 选图导入：拷贝进私有 files/replace/（固定名 global.<ext>，换图自动
 * 清理旧扩展名文件，不堆积）。配置只存中性文件名——相册 Uri 权限会
 * 过期、原图会被删，私有拷贝才是"已配置"的可靠凭据。bounds 解码校验
 * 失败（损坏/非图片）则丢弃拷贝、不落配置。
 */
private suspend fun importReplaceImage(context: Context, config: HookConfig, uri: Uri) =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "replace").apply { mkdirs() }
            val ext = when (context.contentResolver.getType(uri)?.substringAfter('/')) {
                "png" -> "png"
                "webp" -> "webp"
                "gif" -> "gif"
                "heic", "heif" -> "heic"
                else -> "jpg"
            }
            val target = File(dir, "global.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("empty stream")
            // 可解码性校验（bounds 探测，不解码全图）
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, probe)
            check(probe.outWidth > 0) { "undecodable image" }
            // 清理旧扩展名残留（global.png → global.jpg 切换场景）
            dir.listFiles()?.forEach { old ->
                if (old != target && old.name.startsWith("global.")) old.delete()
            }
            target.name
        }.getOrNull()?.let { name ->
            TemplateManager.saveConfig(context, config.copy(globalReplaceImage = name))
        }
    }

/**
 * E1 三态选择器（全局卡与编辑页共用）。
 * compact = 编辑页同排紧凑态：小字号 + 28dp 矮 chip + 4dp 间距，
 * 三 chip 靠右不换行；默认态保持全局卡的原生观感
 */
@Composable
private fun SecurePolicySelector(
    policy: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
    ) {
        listOf(
            HookConfig.SECURE_FOLLOW to R.string.secure_policy_follow,
            HookConfig.SECURE_ALLOW to R.string.secure_policy_allow,
            HookConfig.SECURE_DENY to R.string.secure_policy_deny,
        ).forEach { (value, label) ->
            FilterChip(
                selected = policy == value,
                onClick = { onChange(value) },
                label = {
                    Text(
                        stringResource(label),
                        style = if (compact) MaterialTheme.typography.labelMedium
                        else MaterialTheme.typography.labelLarge
                    )
                },
                modifier = if (compact) Modifier.height(28.dp) else Modifier
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun policyLabel(policy: Int): String = when (policy) {
    HookConfig.SECURE_ALLOW -> stringResource(R.string.secure_policy_allow)
    HookConfig.SECURE_DENY -> stringResource(R.string.secure_policy_deny)
    else -> stringResource(R.string.secure_policy_follow)
}

private fun masksCount(tpl: HookTemplate): Int =
    listOf(tpl.maskCaptureDetection, tpl.maskRecordDetection, tpl.maskOverlayDetection).count { it }
