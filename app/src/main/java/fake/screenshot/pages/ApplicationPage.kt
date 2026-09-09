package fake.screenshot.pages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fake.screenshot.R
import fake.screenshot.hooks.HookConfig
import fake.screenshot.wrappers.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用映射页（HMA 三级结构第二级）：包名 → 模板 的分配管理。
 * 全量枚举已安装应用（QUERY_ALL_PACKAGES，含无启动器入口的后台检测
 * 组件、无 UI 截屏工具等；IO 线程加载图标与标签，列表展示 icon/
 * label/包名/分配状态），点击弹模板单选（标题带应用图标）；未分配 =
 * 原生行为（E1 回落全局三态）。写回 scope 映射，保存即导出。
 */
internal data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

/**
 * 全量已安装应用（IO 线程枚举 + 排序；QUERY_ALL_PACKAGES，含无启动器
 * 入口的后台组件）。null = 枚举中（数百包的 loadLabel/loadIcon 是逐包
 * binder + 资源装载，主线程同步做会冻结 UI 数秒）。应用映射页与模板
 * 已应用多选页共用
 */
@Composable
internal fun rememberInstalledApps(): List<AppEntry>? {
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .map { info -> AppEntry(info.packageName, info.loadLabel(pm).toString(), info.loadIcon(pm)) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }
    }
    return apps
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationCompose() {
    val context = LocalContext.current
    val config by TemplateManager.configFlow(context)
        .collectAsStateWithLifecycle(initialValue = HookConfig.DEFAULT)
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<AppEntry?>(null) }
    val scope = rememberCoroutineScope()

    val apps = rememberInstalledApps()
    val filtered = apps?.let { list ->
        if (query.isBlank()) list
        else list.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    } ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.application)) })
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
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(filtered, key = { it.pkg }) { app ->
                    val templateName = config.templateFor(app.pkg)?.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picking = app }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.icon, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                templateName?.let {
                                    stringResource(R.string.assigned_to, it)
                                } ?: stringResource(R.string.not_assigned),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (templateName != null) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.primary.takeIf { templateName != null }
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    picking?.let { app ->
        val currentId = config.scope[app.pkg]
        AlertDialog(
            onDismissRequest = { picking = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.icon, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(app.label)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (config.templates.isEmpty()) {
                        Text(
                            stringResource(R.string.template_empty),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    config.templates.forEach { tpl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        TemplateManager.saveConfig(
                                            context,
                                            config.copy(scope = config.scope + (app.pkg to tpl.id))
                                        )
                                    }
                                    picking = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentId == tpl.id, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(tpl.name)
                        }
                    }
                    if (config.templates.isNotEmpty()) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        TemplateManager.saveConfig(
                                            context,
                                            config.copy(scope = config.scope - app.pkg)
                                        )
                                    }
                                    picking = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentId == null, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.remove_mapping))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = null }) { Text(stringResource(R.string.Cancel)) }
            }
        )
    }
}

@Composable
internal fun AppIcon(icon: Drawable, modifier: Modifier = Modifier) {
    // 固定 48dp 画布 + setBounds：自适应图标等 Drawable 的 intrinsic
    // 尺寸不可靠（-1 或异值），按 intrinsic 建位图会得到 1px 或错比例图
    val density = LocalDensity.current
    val bitmap = remember(icon, density.density) {
        val size = with(density) { 48.dp.roundToPx() }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))
        bmp.asImageBitmap()
    }
    Image(bitmap, contentDescription = null, modifier = modifier)
}
