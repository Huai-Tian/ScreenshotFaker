package fake.screenshot.pages

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import fake.screenshot.R
import fake.screenshot.hooks.HookConfig
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用映射页（HMA 三级结构第二级）：全量枚举已安装应用
 * （QUERY_ALL_PACKAGES，含无启动器入口的后台检测组件、无 UI 截屏
 * 工具等；IO 线程加载图标与标签，列表展示 icon/label/包名/分配状态），
 * 点击进入应用详情页（模板分配 + 激进的检测过滤，HMA 式单应用配置）。
 * 未分配 = 原生行为（E1 回落全局三态）。
 */
internal data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable,
    /** PackageInfo.firstInstallTime：安装顺序排序键 */
    val firstInstallTime: Long,
    /** FLAG_SYSTEM（含被用户更新的系统应用）：筛选系统应用用 */
    val isSystem: Boolean
)

/**
 * 全量已安装应用（IO 线程枚举 + 按安装时间降序排列——新安装的在前；
 * QUERY_ALL_PACKAGES，含无启动器入口的后台组件）。null = 枚举中
 * （数百包的 loadLabel/loadIcon 是逐包 binder + 资源装载，主线程同步
 * 做会冻结 UI 数秒）。应用映射页与模板已应用多选页共用
 */
@Composable
internal fun rememberInstalledApps(): List<AppEntry>? {
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledPackages(0)
                .mapNotNull { pi ->
                    val info = pi.applicationInfo ?: return@mapNotNull null
                    AppEntry(
                        pkg = pi.packageName,
                        label = info.loadLabel(pm).toString(),
                        icon = info.loadIcon(pm),
                        firstInstallTime = pi.firstInstallTime,
                        isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    )
                }
                .sortedByDescending { it.firstInstallTime }
        }
    }
    return apps
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationCompose(navController: NavController) {
    val context = LocalContext.current
    val config by TemplateManager.configFlow(context)
        .collectAsStateWithLifecycle(initialValue = HookConfig.DEFAULT)
    // 筛选状态（加密 DataStore 持久化，HMA 式）：默认不显示系统应用
    val showSystem by ConfigManager.rememberValue(context, "show_system_apps", false)
    val scope = rememberCoroutineScope()
    var filterMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val apps = rememberInstalledApps()
    val filtered = apps?.let { list ->
        val base = if (showSystem) list else list.filter { !it.isSystem }
        val matched = if (query.isBlank()) base
        else base.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
        // 已配置模板的应用置顶，组内保持安装时间降序（新安装的在前）
        matched.sortedWith(
            compareBy<AppEntry> { config.scope[it.pkg] == null }
                .thenByDescending { it.firstInstallTime }
        )
    } ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.application)) },
            actions = {
                IconButton(onClick = { filterMenu = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = null)
                }
                DropdownMenu(
                    expanded = filterMenu,
                    onDismissRequest = { filterMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_system_apps)) },
                        onClick = {
                            scope.launch {
                                ConfigManager.saveData(context, "show_system_apps", !showSystem)
                            }
                        },
                        trailingIcon = {
                            Checkbox(checked = showSystem, onCheckedChange = null)
                        }
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
                    val aggressive = config.aggressiveFilter.contains(app.pkg)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate("app_detail/${app.pkg}") }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.icon, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            // 配置徽章行：模板名（有分配时）+ 激进的检测过滤（独立
                            // 开关，可单独出现）；两者皆无则整行不渲染
                            if (templateName != null || aggressive) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    templateName?.let { StatusBadge(it) }
                                    if (aggressive) {
                                        StatusBadge(
                                            stringResource(R.string.aggressive_detection_filtering)
                                        )
                                    }
                                }
                            }
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 配置状态徽章：蓝色矩形方块 + 白字（模板名 / 激进的检测过滤） */
@Composable
private fun StatusBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(badgeBlue, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 徽章底色：固定蓝（主题未定义品牌色，避免动态颜色漂移出"蓝底白字"） */
private val badgeBlue = Color(0xFF1E88E5)

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
