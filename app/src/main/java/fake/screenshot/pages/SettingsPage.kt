package fake.screenshot.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import fake.screenshot.Auxiliary
import fake.screenshot.ConfigManager
import fake.screenshot.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCompose() {
    // 状态管理
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checkUpdate by ConfigManager.rememberValue(context, "check_update", true)
    val attemptFilter by ConfigManager.rememberValue(context, "attempt_filter", false)
    val hideIcon by ConfigManager.rememberValue(context, "hide_icon", false)

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
            contentPadding = PaddingValues(vertical = 20.dp),
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
                                ConfigManager.saveData(context,"check_update",it)
                            }
                        }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItem(
                        icon = Icons.Default.CastConnected,
                        title = stringResource(R.string.receive_stealth_screen_casting),
                        subtitle = stringResource(R.string.receive_screen_casting_from_ScreenshotFaker),
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
            if (Auxiliary.isModuleActivated()) {
                item {
                    CommonCard {
                        TwoStatePreference(
                            icon = Icons.Default.Gavel,
                            title = stringResource(R.string.aggressive_detection_filtering),
                            subtitle = stringResource(R.string.filter_content_observer),
                            checked = attemptFilter,
                            onCheckedChange = { scope.launch {
                                ConfigManager.saveData(context,"attempt_filter",it)
                            } }
                        )
                    }
                }
            }
            item {
                CommonCard {
                    TwoStatePreference(
                        icon = Icons.Default.ImageNotSupported,
                        title = stringResource(R.string.hide_desktop_icon),
                        subtitle = stringResource(R.string.use_secret_code_to_open_application),
                        checked = hideIcon,
                        onCheckedChange = { scope.launch {
                            ConfigManager.saveData(context,"hide_icon",it)
                        } }
                    )
                }
            }
            item {
                CommonCard {
                    PreferenceItem(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.hide_app_attributes),
                        subtitle = stringResource(R.string.config_to_bypass_detection),
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
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.about_this_app),
                        subtitle = stringResource(R.string.to_know_about_ScreenshotFaker),
                        trailingContent = {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null
                            )
                        },
                        onClick = { /*TODO*/ }
                    )
                }
            }
        }
    }
}

// 通用卡片容器：包裹单个设置项
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

// 列表项（带箭头或文本尾部）
@Composable
fun PreferenceItem(
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