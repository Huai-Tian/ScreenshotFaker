package fake.screenshot.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.runtime.mutableIntStateOf
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
import fake.screenshot.wrappers.ReplaceImageManager
import fake.screenshot.wrappers.ReplaceVideoManager
import fake.screenshot.wrappers.TemplateManager
import fake.screenshot.styles.IconCropDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

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
    // 落地专用 scope（导入落盘 + 配置保存）：与组合生命周期解耦——视频
    // 导入流式加密耗时数秒，导入中用户按返回退出页面会 cancel 掉半途
    // 写入（半截本地/远程密文）。本 scope 无人 cancel，跑完即止
    val persistScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // 截图替换选图（Photo Picker，不可用时框架自动回落系统选择器）。
    // 选图后先解码降采样（原图可能 50MP，直接解码 OOM），弹屏幕比例
    // 裁剪对话框，确认后落盘
    var pendingCrop by remember { mutableStateOf<Bitmap?>(null) }
    // 换图修订号：重选同槽位时 imageId 值不变（全局恒 GLOBAL_ID）→
    // 保存的 config 结构相等 → 状态不失效 → 行不重组 → 缩略图
    // remember(file.length/lastModified) 无从重读磁盘元数据，预览停留
    // 旧图。rev 变更强制行重组刷新预览（"开关一关一开才刷新"即同机理：
    // 开关变更触发重组，彼时元数据已新）
    var imageRev by remember { mutableStateOf(0) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            val bmp = decodeForCrop(context, uri)
            if (bmp != null) {
                pendingCrop = bmp
            } else {
                Toast.makeText(context, R.string.replace_image_read_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 录屏替换选视频（Photo Picker VideoOnly）：无裁剪直接导入（大小/
    // 可播放性校验 + 缩略图 + 远程流式加密投递都在 [ReplaceVideoManager.save]，
    // 100MB 上限内可能耗时数秒——行内进度态反馈）
    var videoImporting by remember { mutableStateOf(false) }
    var videoRev by remember { mutableStateOf(0) }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) persistScope.launch {
            videoImporting = true
            when (ReplaceVideoManager.save(context, ReplaceVideoManager.GLOBAL_ID, uri)) {
                ReplaceVideoManager.ImportResult.Ok -> {
                    TemplateManager.saveConfig(
                        context,
                        config.copy(globalRecordVideoId = ReplaceVideoManager.GLOBAL_ID)
                    )
                    videoRev++
                }
                ReplaceVideoManager.ImportResult.TooLarge ->
                    Toast.makeText(context, R.string.replace_video_too_large, Toast.LENGTH_SHORT).show()
                ReplaceVideoManager.ImportResult.Invalid ->
                    Toast.makeText(context, R.string.replace_video_invalid, Toast.LENGTH_SHORT).show()
                ReplaceVideoManager.ImportResult.ReadFailed ->
                    Toast.makeText(context, R.string.replace_video_read_failed, Toast.LENGTH_SHORT).show()
            }
            videoImporting = false
        }
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
                            rev = imageRev,
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
                        Spacer(Modifier.height(12.dp))
                        RecordReplaceEntryRow(
                            enabled = config.globalRecordVideoEnabled,
                            videoFile = replaceVideoFile(context, config),
                            thumbFile = ReplaceVideoManager.thumbFile(context, ReplaceVideoManager.GLOBAL_ID),
                            importing = videoImporting,
                            rev = videoRev,
                            onToggle = { v ->
                                scope.launch {
                                    TemplateManager.saveConfig(context, config.copy(globalRecordVideoEnabled = v))
                                }
                            },
                            onPickVideo = {
                                videoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
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
                                    if (tpl.imageId != null) {
                                        append(" · ")
                                        append(stringResource(R.string.screenshot_replace))
                                    }
                                    if (tpl.recordVideoId != null) {
                                        append(" · ")
                                        append(stringResource(R.string.record_replace))
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

    // 屏幕比例裁剪（选图后触发）：确认落盘，取消丢弃
    pendingCrop?.let { bmp ->
        IconCropDialog(
            image = bmp,
            aspectRatio = screenAspectRatio(context),
            titleRes = R.string.crop_replace_image,
            onConfirm = { cropped ->
                pendingCrop = null
                persistScope.launch {
                    importReplaceImage(context, config, cropped)
                    imageRev++
                }
            },
            onDismiss = { pendingCrop = null }
        )
    }
}

/**
 * 模板编辑页（新建 route id 为空 / 编辑 route 带模板 id）。
 * 编辑模式下权威配置未到达前（DataStore 冷流首帧为 DEFAULT）显示
 * 加载态、表单不组合——若先以空值组合，数据到达后 key 值不变
 * （editing.id == templateId），remember 不会重置，表单将停留在
 * 空名称假象。模板级替换图仅编辑态可配（新建态模板尚无 id，选图
 * 以模板 id 作为 imageId 命名，保存后再开放——与"已应用于"行同约束）。
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
    // 落地专用 scope（配置保存 + 资源转正/删除）：与组合生命周期解耦——
    // 保存按钮 launch 后立即 popBackStack，rememberCoroutineScope 随页面
    // 离开组合被 cancel，saveConfig 之后的 promoteStaging 会被取消（视频
    // 只转正本地、从未推远程 → hook 侧 "remote file absent"，实测 round 3）。
    // 本 scope 无人 cancel，协程跑完即止（活跃协程持有引用，无泄漏）
    val persistScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // 模板级替换选图（与全局入口同管线：Photo Picker → 降采样解码 →
    // 屏幕比例裁剪 → ReplaceImageManager 落双区，imageId = 模板 id）
    var pendingCrop by remember { mutableStateOf<Bitmap?>(null) }
    // 换图修订号（与 TemplateCompose 同机理：重选同模板槽位时 imageId
    // 状态值不变、config 未保存 → 无重组 → 缩略图停留旧图）
    var imageRev by remember { mutableStateOf(0) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            val bmp = decodeForCrop(context, uri)
            if (bmp != null) {
                pendingCrop = bmp
            } else {
                Toast.makeText(context, R.string.replace_image_read_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 模板级录屏视频：picker 回调只桥接 uri（pendingVideoUri 模式，同
    // pendingCrop——回调在 key 块外，块内 recordVideoId 状态无法直写，
    // 由 key 块内的 LaunchedEffect 消费导入并绑定）
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var videoImporting by remember { mutableStateOf(false) }
    var videoRev by remember { mutableStateOf(0) }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pendingVideoUri = uri
    }

    key(editing?.id ?: "") {
        var name by remember { mutableStateOf(editing?.name ?: "") }
        var policy by remember { mutableIntStateOf(editing?.securePolicy ?: HookConfig.SECURE_FOLLOW) }
        var maskCapture by remember { mutableStateOf(editing?.maskCaptureDetection ?: false) }
        var maskRecord by remember { mutableStateOf(editing?.maskRecordDetection ?: false) }
        var maskOverlay by remember { mutableStateOf(editing?.maskOverlayDetection ?: false) }
        var maskFocus by remember { mutableStateOf(editing?.maskFocusDetection ?: false) }
        var maskPresentation by remember { mutableStateOf(editing?.maskPresentationDetection ?: false) }
        var pierceFreeform by remember { mutableStateOf(editing?.pierceFreeform ?: false) }
        var imageId by remember { mutableStateOf(editing?.imageId) }
        var recordVideoId by remember { mutableStateOf(editing?.recordVideoId) }
        // 清除延迟落地：清除按钮只改内存态（图/视频文件保留），点保存时
        // 才物理删除双区资源——未保存退出即完全恢复原状（文件在 + 配置
        // 残留 id 仍指向有效资源），与"退出丢弃所有更改"语义一致
        var imageCleared by remember { mutableStateOf(false) }
        var videoCleared by remember { mutableStateOf(false) }
        // 导入延迟落地（对称面）：换图/换视频只落暂存文件（正式区与远程
        // 不动），点保存时 promoteStaging 转正——否则换新资源未保存退出
        // 会即时覆盖原资源（不可恢复）且 hook 立即用新资源（更改被生效）
        var imageStaged by remember { mutableStateOf(false) }
        var videoStaged by remember { mutableStateOf(false) }

        // 进入编辑页清上次未保存退出的暂存残留（编辑态才有 id 与暂存）
        LaunchedEffect(Unit) {
            editing?.id?.let { id ->
                ReplaceImageManager.discardStaging(context, id)
                ReplaceVideoManager.discardStaging(context, id)
            }
        }

        // 视频导入消费（key 块内：可写 recordVideoId）：videoId = 模板 id，
        // 落暂存（保存才转正）
        LaunchedEffect(pendingVideoUri) {
            val uri = pendingVideoUri ?: return@LaunchedEffect
            pendingVideoUri = null
            val tplId = templateId.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
            videoImporting = true
            when (ReplaceVideoManager.save(context, tplId, uri, staging = true)) {
                ReplaceVideoManager.ImportResult.Ok -> {
                    recordVideoId = tplId
                    videoCleared = false
                    videoStaged = true
                    videoRev++
                }
                ReplaceVideoManager.ImportResult.TooLarge ->
                    Toast.makeText(context, R.string.replace_video_too_large, Toast.LENGTH_SHORT).show()
                ReplaceVideoManager.ImportResult.Invalid ->
                    Toast.makeText(context, R.string.replace_video_invalid, Toast.LENGTH_SHORT).show()
                ReplaceVideoManager.ImportResult.ReadFailed ->
                    Toast.makeText(context, R.string.replace_video_read_failed, Toast.LENGTH_SHORT).show()
            }
            videoImporting = false
        }

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
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null
                        )
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
                    IconButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val saved = HookTemplate(
                                id = editing?.id ?: Auxiliary.getSecureRandomString(16),
                                name = name.trim(),
                                securePolicy = policy,
                                maskCaptureDetection = maskCapture,
                                maskRecordDetection = maskRecord,
                                maskOverlayDetection = maskOverlay,
                                maskFocusDetection = maskFocus,
                                maskPresentationDetection = maskPresentation,
                                pierceFreeform = pierceFreeform,
                                imageId = imageId,
                                recordVideoId = recordVideoId,
                            )
                            val next = if (editing == null) {
                                config.copy(templates = config.templates + saved)
                            } else {
                                config.copy(templates = config.templates.map {
                                    if (it.id == saved.id) saved else it
                                })
                            }
                            persistScope.launch {
                                TemplateManager.saveConfig(context, next)
                                // 清除落地：保存时才物理删除双区资源（新建态
                                // 无可清资源，cleared 恒 false 天然跳过）
                                if (imageCleared) {
                                    ReplaceImageManager.delete(context, saved.id)
                                }
                                if (videoCleared) {
                                    ReplaceVideoManager.delete(context, saved.id)
                                }
                                // 导入落地：保存时暂存转正（staging → 正式
                                // + 远程投递；未导入 staged 恒 false 跳过）
                                if (imageStaged) {
                                    ReplaceImageManager.promoteStaging(context, saved.id)
                                }
                                if (videoStaged) {
                                    ReplaceVideoManager.promoteStaging(context, saved.id)
                                }
                            }
                            navController.popBackStack()
                        }
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
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
                // 模板级替换图（编辑态：imageId = 模板 id，前台命中即换图，
                // 优先级高于全局图；导入落暂存、清除仅解绑内存态，两者均
                // 延迟到保存落地——未保存退出时文件与配置均未动，完全恢复
                // 原状；暂存期预览源切到 staging 文件）
                if (editing != null) {
                    TemplateReplaceRow(
                        imageFile = if (imageStaged) {
                            ReplaceImageManager.stagingFile(context, editing.id)
                        } else {
                            ReplaceImageManager.localFile(context, editing.id)
                        },
                        rev = imageRev,
                        bound = imageId != null,
                        onPickImage = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onClear = {
                            imageId = null
                            imageCleared = true
                            imageStaged = false
                            ReplaceImageManager.discardStaging(context, editing.id)
                        }
                    )
                }
                // 模板级录屏视频（编辑态：videoId = 模板 id，录屏命中即换
                // 视频，优先级高于全局视频；导入落暂存、清除仅解绑内存态，
                // 两者均延迟到保存落地——未保存退出时完全恢复原状；暂存期
                // 预览源切到 staging 文件）
                if (editing != null) {
                    TemplateRecordReplaceRow(
                        videoFile = if (videoStaged) {
                            ReplaceVideoManager.stagingFile(context, editing.id)
                        } else {
                            ReplaceVideoManager.localFile(context, editing.id)
                        },
                        thumbFile = if (videoStaged) {
                            ReplaceVideoManager.stagingThumbFile(context, editing.id)
                        } else {
                            ReplaceVideoManager.thumbFile(context, editing.id)
                        },
                        rev = videoRev,
                        importing = videoImporting,
                        bound = recordVideoId != null,
                        onPickVideo = {
                            videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onClear = {
                            recordVideoId = null
                            videoCleared = true
                            videoStaged = false
                            ReplaceVideoManager.discardStaging(context, editing.id)
                        }
                    )
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
                SwitchRow(stringResource(R.string.mask_focus_detection), maskFocus) { maskFocus = it }
                SwitchRow(stringResource(R.string.mask_presentation_detection), maskPresentation) { maskPresentation = it }
                SwitchRow(stringResource(R.string.freeform_pierce), pierceFreeform) { pierceFreeform = it }
            }
        }

        // 屏幕比例裁剪（模板级选图后触发）：确认后落暂存并绑定
        // imageId = 模板 id（key 块内，直取块内 imageId 状态；保存才转正）
        pendingCrop?.let { bmp ->
            val tplId = editing?.id
            if (tplId != null) {
                IconCropDialog(
                    image = bmp,
                    aspectRatio = screenAspectRatio(context),
                    titleRes = R.string.crop_replace_image,
                    onConfirm = { cropped ->
                        pendingCrop = null
                        scope.launch {
                            ReplaceImageManager.save(context, tplId, cropped, staging = true)
                            imageId = tplId
                            imageCleared = false
                            imageStaged = true
                            imageRev++
                        }
                    },
                    onDismiss = { pendingCrop = null }
                )
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
                    persistScope.launch {
                        TemplateManager.saveConfig(
                            context,
                            config.copy(
                                templates = config.templates.filter { it.id != editing.id },
                                scope = config.scope.filterValues { it != editing.id }
                            )
                        )
                        // 模板删除同步清替换图/视频双区（imageId/videoId =
                        // 模板 id，孤儿资源在 hook 侧表现为加载失败
                        // fail-open，但本地明文/远程密文必须随模板消亡）
                        ReplaceImageManager.delete(context, editing.id)
                        ReplaceVideoManager.delete(context, editing.id)
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
 * 模板级替换图行（编辑页）：点击选图（已绑定时换图），清除按钮解绑。
 * 与全局 [ReplaceEntryRow] 的差异：无 Switch——模板有图即生效，启停
 * 语义由"是否分配到应用"承担；副标题说明命中语义（模板图优先于全局图）
 */
@Composable
private fun TemplateReplaceRow(
    imageFile: File,
    rev: Int,
    bound: Boolean,
    onPickImage: () -> Unit,
    onClear: () -> Unit,
) {
    val configured = bound && imageFile.exists()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onPickImage),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (configured) {
                    ReplaceThumb(imageFile, rev)
                    Spacer(Modifier.width(12.dp))
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column {
                    Text(
                        stringResource(R.string.screenshot_replace),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        if (configured) stringResource(R.string.replace_state_configured)
                        else stringResource(R.string.replace_state_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (configured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (configured) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
        Text(
            stringResource(R.string.replace_template_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 录屏替换行（全局卡，截图替换下方同款样式）：Switch 是唯一启停入口；
 * 开启时点击行主体弹选视频器，导入中行内进度态。语义（E3b 两级内容源）：
 * 开启且已配置 → 录屏用替换视频；未启用/未配置 → 录屏回落静态替换图。
 * 缩略图复用 [ReplaceThumb]（缩略图是 JPEG，同解码管线）
 */
@Composable
private fun RecordReplaceEntryRow(
    enabled: Boolean,
    videoFile: File?,
    thumbFile: File?,
    importing: Boolean,
    rev: Int,
    onToggle: (Boolean) -> Unit,
    onPickVideo: () -> Unit,
) {
    val configured = videoFile?.exists() == true
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (enabled && !importing) Modifier.clickable(onClick = onPickVideo)
                    else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
            } else if (enabled && configured && thumbFile != null) {
                ReplaceThumb(thumbFile, rev)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    stringResource(R.string.record_replace),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = when {
                        importing -> stringResource(R.string.replace_video_importing)
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

/**
 * 模板级录屏视频行（编辑页，模板替换图下方同款样式）：与全局行的差异
 * 同图——无 Switch，绑定即生效，启停语义由"是否分配到应用"承担。
 * 副标题说明命中语义（模板视频优先于全局视频，未配置录屏沿用替换图）
 */
@Composable
private fun TemplateRecordReplaceRow(
    videoFile: File,
    thumbFile: File,
    rev: Int,
    importing: Boolean,
    bound: Boolean,
    onPickVideo: () -> Unit,
    onClear: () -> Unit,
) {
    val configured = bound && videoFile.exists()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(if (importing) Modifier else Modifier.clickable(onClick = onPickVideo)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                } else if (configured) {
                    ReplaceThumb(thumbFile, rev)
                    Spacer(Modifier.width(12.dp))
                } else {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column {
                    Text(
                        stringResource(R.string.record_replace),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        if (importing) stringResource(R.string.replace_video_importing)
                        else if (configured) stringResource(R.string.replace_state_configured)
                        else stringResource(R.string.replace_state_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (configured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (configured) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
        Text(
            stringResource(R.string.replace_record_template_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 模板语义图标（仿 HMA 按类型区分模板的图标语义；我们无模板类型，
 * 按最高优先级特征取图）：穿透 > 屏蔽 > 强制禁止 > 强制允许 > 空
 */
private fun featureIcon(tpl: HookTemplate): ImageVector = when {
    tpl.pierceFreeform -> Icons.Filled.PictureInPictureAlt
    tpl.maskCaptureDetection || tpl.maskRecordDetection || tpl.maskOverlayDetection ||
            tpl.maskFocusDetection || tpl.maskPresentationDetection ->
        Icons.Filled.VisibilityOff
    tpl.securePolicy == HookConfig.SECURE_DENY -> Icons.Filled.Lock
    tpl.securePolicy == HookConfig.SECURE_ALLOW -> Icons.Filled.LockOpen
    else -> Icons.Filled.Description
}

/**
 * 截图替换行（全局卡第二行）：Switch 是唯一启停入口；关闭时仅显示
 * "未启用"（配置状态静默保留，不显示缩略图与是否配置）；开启时点击
 * 行主体弹选图器，副标题显示 未配置/已配置，已配置时带缩略图预览。
 * hook 侧经 HookContext.replacementImageId 消费（前台者的模板图
 * 优先，未命中模板时回落此全局图）。
 */
@Composable
private fun ReplaceEntryRow(
    enabled: Boolean,
    imageFile: File?,
    rev: Int,
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
                ReplaceThumb(imageFile, rev)
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

/** 已配置缩略图（降采样解码，~256px 预览；rev = 换图修订号，同槽位
 *  重选时强制键失效——file.length/lastModified 键仅在重组时求值，
 *  rev 变更即触发重组） */
@Composable
private fun ReplaceThumb(file: File, rev: Int) {
    val bitmap = remember(file.absolutePath, file.length(), file.lastModified(), rev) {
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

/** 全局替换图本地凭据（files/replace/g.png；存在 = 已配置） */
private fun replaceImageFile(context: Context, config: HookConfig): File? =
    if (config.globalReplaceImage != null)
        ReplaceImageManager.localFile(context, ReplaceImageManager.GLOBAL_ID)
    else null

/** 全局替换视频本地凭据（files/replace_video/g.mp4；存在 = 已配置） */
private fun replaceVideoFile(context: Context, config: HookConfig): File? =
    if (config.globalRecordVideoId != null)
        ReplaceVideoManager.localFile(context, ReplaceVideoManager.GLOBAL_ID)
    else null

/**
 * 选图导入：裁剪后的 Bitmap 经 [ReplaceImageManager] 落双区（本地明文
 * PNG + 托管区 AES 密文），配置存 imageId（全局图固定 [ReplaceImageManager.GLOBAL_ID]）。
 * 本地 PNG 是"已配置"的可靠凭据（相册 Uri 权限会过期、原图会被删）；
 * 远程投递失败（服务未连接）不阻断配置落库——本地已保底，绑定 catch-up 补投
 */
private suspend fun importReplaceImage(context: Context, config: HookConfig, bitmap: Bitmap) {
    ReplaceImageManager.save(context, ReplaceImageManager.GLOBAL_ID, bitmap)
    TemplateManager.saveConfig(context, config.copy(globalReplaceImage = ReplaceImageManager.GLOBAL_ID))
}

/**
 * 裁剪前解码：单次读流 + bounds 探测 + inSampleSize 降采样（长边 ≤ 4096，
 * 防 50MP 原图直接解码 ~200MB OOM；4096 已超任何手机屏分辨率，替换图
 * 场景无质量损失感知）。失败返回 null（调用方 Toast 提示——损坏/无授权/
 * 单读 provider 均可见，不静默）
 *
 * 单次读流的原因：bounds 探测与实际解码不能对同一 URI openInputStream
 * 两次——部分 OEM 相册 provider（ColorOS 回落选择器实测）的流是一次性
 * 的，第二次打开返回 null → 解码失败静默丢弃 → 裁剪页不弹（round 1
 * 实测 bug）。读入 byte[] 后两次 decodeByteArray 共用同一份字节
 */
private suspend fun decodeForCrop(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 4096) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }

/**
 * 屏幕宽高比（currentWindowMetrics.bounds：真实物理分辨率，截图
 * 输出即此分辨率——替换图按此比例裁剪，E3 注入时无黑边/拉伸）
 */
private fun screenAspectRatio(context: Context): Float {
    val bounds = context.getSystemService(WindowManager::class.java)
        .currentWindowMetrics.bounds
    return bounds.width().toFloat() / bounds.height()
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
    listOf(
        tpl.maskCaptureDetection,
        tpl.maskRecordDetection,
        tpl.maskOverlayDetection,
        tpl.maskFocusDetection,
        tpl.maskPresentationDetection
    ).count { it }
