package fake.screenshot.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import fake.screenshot.R
import fake.screenshot.hooks.HookConfig
import fake.screenshot.hooks.HookTemplate
import fake.screenshot.styles.CommonCard
import fake.screenshot.styles.PreferenceItemEx
import fake.screenshot.styles.TwoStatePreference
import fake.screenshot.wrappers.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用详情页（HMA 式单应用配置）：模板分配（单选，含移除）+ 激进的
 * 检测过滤（per-app 独立开关，不依赖模板——未分配模板也可单独开启）。
 * 写回 scope 映射 / aggressiveFilter 包名集，保存即经 TemplateManager
 * 导出（RemotePreferences 热同步 system_server）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailCompose(navController: NavController, pkg: String) {
    val context = LocalContext.current
    val config by TemplateManager.configFlow(context)
        .collectAsStateWithLifecycle(initialValue = HookConfig.DEFAULT)
    val scope = rememberCoroutineScope()

    // 单包元数据（label/icon；IO 线程，枚举失败容忍为 null）
    var meta by remember { mutableStateOf<Pair<CharSequence, android.graphics.drawable.Drawable>?>(null) }
    LaunchedEffect(pkg) {
        meta = withContext(Dispatchers.IO) {
            runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                info.loadLabel(context.packageManager) to info.loadIcon(context.packageManager)
            }.getOrNull()
        }
    }

    val assignedId = config.scope[pkg]
    val aggressive = config.aggressiveFilter.contains(pkg)
    var showTemplatePicker by remember { mutableStateOf(false) }

    fun save(next: HookConfig) {
        scope.launch { TemplateManager.saveConfig(context, next) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_settings)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 应用身份卡（样式同应用列表项：图标 + 名称 + 包名）
            CommonCard {
                meta?.let { (label, icon) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(icon, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                label.toString(),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } ?: Text(
                    pkg,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            // ---- 模板分配（HMA 式：单行项，点击弹单选对话框）----
            CommonCard {
                Column {
                    PreferenceItemEx(
                        icon = Icons.Default.Layers,
                        title = stringResource(R.string.template),
                        subtitle = config.templateFor(pkg)?.name
                            ?: stringResource(R.string.not_assigned),
                        onClick = { showTemplatePicker = true },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    // ---- 激进的检测过滤（per-app 独立开关）----
                    TwoStatePreference(
                        icon = Icons.Default.Gavel,
                        title = stringResource(R.string.aggressive_detection_filtering),
                        subtitle = stringResource(R.string.filter_content_observer),
                        checked = aggressive,
                        onCheckedChange = { on ->
                            save(
                                config.copy(
                                    aggressiveFilter = if (on) config.aggressiveFilter + pkg
                                    else config.aggressiveFilter - pkg
                                )
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }

    // 模板单选对话框（HMA 式：模板名 + 三态摘要单选，含取消分配）
    if (showTemplatePicker) {
        AlertDialog(
            onDismissRequest = { showTemplatePicker = false },
            title = { Text(stringResource(R.string.template)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    if (config.templates.isEmpty()) {
                        Text(
                            stringResource(R.string.template_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    config.templates.forEach { tpl ->
                        SelectableRow(
                            label = tpl.name,
                            subtitle = policySummary(tpl),
                            selected = assignedId == tpl.id,
                            onClick = {
                                save(config.copy(scope = config.scope + (pkg to tpl.id)))
                                showTemplatePicker = false
                            }
                        )
                    }
                    if (config.templates.isNotEmpty()) {
                        SelectableRow(
                            label = stringResource(R.string.remove_mapping),
                            subtitle = stringResource(R.string.not_assigned),
                            selected = assignedId == null,
                            onClick = {
                                save(config.copy(scope = config.scope - pkg))
                                showTemplatePicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplatePicker = false }) {
                    Text(stringResource(R.string.Cancel))
                }
            }
        )
    }
}

/** 单选行（模板选择对话框内）：Radio + 主文本 + 摘要副文本 */
@Composable
private fun SelectableRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 模板摘要副标题：截屏限制三态（与 TemplatePage 的 policyLabel 同源） */
@Composable
private fun policySummary(tpl: HookTemplate): String = when (tpl.securePolicy) {
    HookConfig.SECURE_ALLOW -> stringResource(R.string.secure_policy_allow)
    HookConfig.SECURE_DENY -> stringResource(R.string.secure_policy_deny)
    else -> stringResource(R.string.secure_policy_follow)
}
