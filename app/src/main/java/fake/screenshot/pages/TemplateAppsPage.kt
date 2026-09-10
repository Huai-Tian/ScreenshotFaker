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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import fake.screenshot.R
import fake.screenshot.hooks.HookConfig
import fake.screenshot.wrappers.TemplateManager
import kotlinx.coroutines.launch

/**
 * 模板已应用页（HMA TemplateSettingsFragment 的 appliedApps 列表）：
 * 仅显示已配置该模板的应用（模板视角），勾选保留 / 取消移除——
 * 新增分配走应用详情页正向单选。复用全量枚举与图标。
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
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
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
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
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
                // 仅显示已配置该模板的应用（勾选保留 / 取消移除）
                val applied = apps.filter { config.scope[it.pkg] == templateId }
                val filtered = if (query.isBlank()) applied
                else applied.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.template_apps_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
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
}
