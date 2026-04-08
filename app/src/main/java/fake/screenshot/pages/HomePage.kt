package fake.screenshot.pages

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fake.screenshot.MainActivity
import fake.screenshot.R
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCompose() {
    Column {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        if (MainActivity.isModuleActivated() || MainActivity.isRootActivated() || MainActivity.isShellActivated) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(vertical = 16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = Color.Blue),
                onClick = {}
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // 关键：这里不需要 SpaceBetween，让内容自然排列
                    horizontalArrangement = Arrangement.Start
                ) {
                    // 左侧图标

                    Box(
                        modifier = Modifier
                            .size(24.dp) // 设置图标容器的大小
                            .clip(CircleShape) // 将容器裁剪为圆形
                            .background(Color.Blue), // 设置背景色为卡片背景色
                        contentAlignment = Alignment.Center // 让图标在容器中居中
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = Color.Black, // 设置对勾的颜色，这里用黑色
                            modifier = Modifier.size(36.dp) // 设置对勾图标的大小
                        )
                    }


                    Spacer(modifier = Modifier.width(16.dp))

                    // 右侧文字区域
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp) // 控制两行文字的间距
                    ) {
                        // 第一行：标题 + 标签
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp) // 关键：控制“工作中”和“LSPosed”的间距
                        ) {
                            Text(
                                text = stringResource(R.string.working),
                                color = Color.White,
                                fontSize = 18.sp
                            )
                        }
                        // 第二行：版本号
                        Text(
                            text = "${stringResource(R.string.version)} ${
                                MainActivity.getVersionName(
                                    LocalContext.current
                                )
                            }（${MainActivity.getVersionCode(LocalContext.current)}）",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(vertical = 16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = Color.Red),
                onClick = {
                    try {
                        Shizuku.requestPermission(1)
                    } catch (_: Exception) {
                        //request permission failed
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // 关键：这里不需要 SpaceBetween，让内容自然排列
                    horizontalArrangement = Arrangement.Start
                ) {
                    // 左侧图标

                    Box(
                        modifier = Modifier
                            .size(24.dp) // 设置图标容器的大小
                            .clip(CircleShape) // 将容器裁剪为圆形
                            .background(Color.Red), // 设置背景色为卡片背景色
                        contentAlignment = Alignment.Center // 让图标在容器中居中
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color.Black, // 设置对勾的颜色，这里用黑色
                            modifier = Modifier.size(36.dp) // 设置对勾图标的大小
                        )
                    }


                    Spacer(modifier = Modifier.width(16.dp))

                    // 右侧文字区域
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp) // 控制两行文字的间距
                    ) {
                        // 第一行：标题 + 标签
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp) // 关键：控制“工作中”和“LSPosed”的间距
                        ) {
                            Text(
                                text = stringResource(R.string.unactivated),
                                color = Color.White,
                                fontSize = 18.sp
                            )
                        }
                        Text(
                            text = stringResource(R.string.click_to_activate),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        WorkingInformation()
    }
}

@Composable
fun WorkingInformation() {
    val deviceInfo = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}"
    val systemVersion = "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
    val fingerprint = Build.FINGERPRINT

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = 0.3f
                )
            ),
            onClick = {}
        ) {
            // 外层 Column 放置所有条目
            Column(modifier = Modifier.padding(16.dp)) {
                InfoItem(stringResource(R.string.device_info), deviceInfo)
                // 分隔线或间距
                Spacer(modifier = Modifier.padding(vertical = 8.dp))

                InfoItem(stringResource(R.string.system_version), systemVersion)
                Spacer(modifier = Modifier.padding(vertical = 8.dp))

                InfoItem(
                    stringResource(R.string.privilege), when {
                        MainActivity.isModuleActivated() && MainActivity.isRootActivated() -> "LSPosed + Root"
                        MainActivity.isModuleActivated() && MainActivity.isShellActivated -> "LSPosed + Shell"
                        MainActivity.isModuleActivated() -> "LSPosed"
                        MainActivity.isRootActivated() -> "Root"
                        MainActivity.isShellActivated -> "Shell"
                        else -> "None"
                    }
                )
                Spacer(modifier = Modifier.padding(vertical = 8.dp))

                InfoItem(stringResource(R.string.fingerprint), fingerprint)

            }
        }
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 1. 标题 (Label)
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth() // <--- 关键代码：强制占满宽度
                .padding(top = 4.dp) // 与标题保持一点间距
        )

        // 2. 内容 (Value)
        // 关键：使用 fillMaxWidth() 确保文本宽度占满剩余空间，从而正确换行
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontWeight = FontWeight.Normal
        )
    }
}