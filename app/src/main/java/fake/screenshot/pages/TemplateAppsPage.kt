package fake.screenshot.pages

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import fake.screenshot.R
import fake.screenshot.hooks.HookConfig
import fake.screenshot.wrappers.TemplateManager
import kotlinx.coroutines.launch

/**
 * 模板已应用多选页（HMA TemplateSettingsFragment 的 appliedApps +
 * ScopeFragment 组合）：模板视角反向管理 scope 分配——应用映射页是
 * "应用 → 模板"正向单选，本页是 "模板 → 应用"反向多选，双入口写同一
 * 份 scope 映射。勾选 = 指向本模板（覆盖原指向——scope 单值语义，
 * 后写胜出），取消 = 移除（仅当原指向本模板）。复用全量枚举与图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateAppsCompose(navController: NavController, templateId: String) {
    val context = LocalContext.current
    val config by TemplateManager.configFlow(context)
        .collectAsStateWithLifecycle(initialValue = HookConfig.DEFAULT)
    val template = config.templates.firstOrNull { it.id == templateId }
    val scope = rememberCoroutineScope()

    // 配置未到达（DataStore 冷流首帧 DEFAULT）→ 加载态；模板已被删 → 退出
    if (template == null) {
        if (config.templates.isNotEmpty()) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
        return
    }

    val apps = rememberInstalledApps()
    // 勾选集：初始 = scope 中指向本模板的包；key(templateId) 使模板切换时重置
    key(templateId) {
        var checked by remember {
            mutableStateOf(config.scope.filterValues { it == templateId }.keys.toSet())
        }
        var query by remember { mutableStateOf("") }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.template_applied_title)) },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text(stringResource(R.string.Cancel))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val next = config.copy(
                                scope = buildMap {
                                    // 保留指向其他模板的映射
                                    config.scope.forEach { (pkg, tid) -> if (tid != templateId) put(pkg, tid) }
                                    // 勾选应用指向本模板
                                    checked.forEach { put(it, templateId) }
                                }
                            )
                            scope.launch { TemplateManager.saveConfig(context, next) }
                            navController.popBackStack()
                        }
                    ) { Text(stringResource(R.string.save)) }
                }
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_apps)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (apps == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                val filtered = if (query.isBlank()) apps
                else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(filtered, key = { it.pkg }) { app ->
                        // 其他模板已占用的应用：勾选会改判归属（scope 单值语义），
                        // 副文本提示归属让改判可见
                        val owner = config.scope[app.pkg]?.let { tid ->
                            config.templates.firstOrNull { it.id == tid }?.name
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app.icon)
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (owner != null && app.pkg !in checked) {
                                    Text(
                                        stringResource(R.string.assigned_to, owner),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Checkbox(
                                checked = app.pkg in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + app.pkg else checked - app.pkg
                                }
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
