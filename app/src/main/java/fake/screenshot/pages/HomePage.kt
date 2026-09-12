package fake.screenshot.pages

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fake.screenshot.Auxiliary
import fake.screenshot.wrappers.DaemonManager
import fake.screenshot.R
import rikka.shizuku.Shizuku

/** 配套检测工具（ScreenshotDetector）包名与发布页 */
private const val DETECTOR_PACKAGE = "detect.screenshot"
private const val DETECTOR_RELEASES_URL = "https://github.com/Huai-Tian/ScreenshotDetector/releases"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCompose() {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
        } catch (_: Exception) {
            null
        }
    }
    // 屏蔽测试入口状态：Detector 已装 → 直接拉起；未装 → 下载确认弹窗
    var showDetectorDialog by remember { mutableStateOf(false) }
    val detectorInstalled = remember {
        runCatching { context.packageManager.getPackageInfo(DETECTOR_PACKAGE, 0) }.isSuccess
    }
    Column {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        // 内容区支持垂直滚动，防止屏幕不够高时底部卡片被压缩变形
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            if (Auxiliary.isModuleActivated || Auxiliary.isRootActivated || Auxiliary.isShellActivated) {
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
                                .size(36.dp) // 设置图标容器的大小
                                .clip(CircleShape) // 将容器裁剪为圆形
                                .background(Color.Blue), // 设置背景色为卡片背景色
                            contentAlignment = Alignment.Center // 让图标在容器中居中
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = null,
                                tint = Color.Black, // 设置对勾的颜色，这里用黑色
                                modifier = Modifier.size(48.dp) // 设置对勾图标的大小
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
                                    packageInfo?.versionName
                                        ?: stringResource(R.string.unknown)

                                }（${
                                    packageInfo?.longVersionCode?.toString() ?: stringResource(R.string.unknown)
                                }）",
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
                    colors = CardDefaults.cardColors(containerColor = Color.LightGray),
                    onClick = {
                        // 先刷新权限状态（root 态是派生属性，随 shell 态即时生效）：
                        // - su 可用 / Shizuku root 模式 → isRootActivated 即刻为 true，
                        //   状态自动翻转为已激活，无需弹 Shizuku（exec 的 su 直连分支已可用）
                        // - 仍无任何权限 → 弹 Shizuku 授权引导
                        Auxiliary.refreshShellState()
                        if (!Auxiliary.isShellActivated && !Auxiliary.isRootActivated) {
                            try {
                                Shizuku.requestPermission(1)
                            } catch (_: Exception) {
                                //request permission failed
                            }
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
                                .size(36.dp) // 设置图标容器的大小
                                .clip(CircleShape) // 将容器裁剪为圆形
                                .background(Color.LightGray), // 设置背景色为卡片背景色
                            contentAlignment = Alignment.Center // 让图标在容器中居中
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color.Black, // 设置对勾的颜色，这里用黑色
                                modifier = Modifier.size(48.dp) // 设置对勾图标的大小
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

            // 屏蔽测试卡片（页面最下方独立卡，无副标题）：已装 Detector →
            // 拉起验证；未装 → 确认弹窗后跳发布页下载
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(vertical = 16.dp),
                // 对齐 SukiSU-Ultra 底部卡片（miuix Card）：16dp 圆角 + 纯色 surfaceContainer
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                onClick = {
                    if (detectorInstalled) {
                        runCatching {
                            context.packageManager.getLaunchIntentForPackage(DETECTOR_PACKAGE)
                        }.getOrNull()?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(it)
                        } ?: Toast.makeText(
                            context, R.string.detector_open_failed, Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showDetectorDialog = true
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            stringResource(R.string.block_test),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        // 摘要：miuix BasicComponent summary 规格（body2 14sp + 弱化色）
                        Text(
                            stringResource(R.string.block_test_description),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 报告问题卡片（纯 UI 占位，逻辑后期补充）：与屏蔽测试卡同款 miuix 规格
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                onClick = {}
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            stringResource(R.string.report_issue),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.report_issue_description),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (showDetectorDialog) {
                AlertDialog(
                    onDismissRequest = { showDetectorDialog = false },
                    title = { Text(stringResource(R.string.block_test)) },
                    text = { Text(stringResource(R.string.detector_download_prompt)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showDetectorDialog = false
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(DETECTOR_RELEASES_URL))
                                )
                            }
                        }) { Text(stringResource(R.string.detector_download)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDetectorDialog = false }) {
                            Text(stringResource(R.string.Cancel))
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun WorkingInformation() {
    val deviceInfo = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}"
    val systemVersion = "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
    val fingerprint = Build.FINGERPRINT
    var isDaemonRunning by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        isLoading = true
        isDaemonRunning = DaemonManager.isDaemonRunning()
        isLoading = false
    }

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
                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                InfoItem(stringResource(R.string.system_version), systemVersion)
                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                InfoItem(
                    stringResource(R.string.privilege), when {
                        Auxiliary.isModuleActivated && Auxiliary.isRootActivated -> "LSPosed + Root"
                        Auxiliary.isModuleActivated && Auxiliary.isShellActivated -> "LSPosed + Shell"
                        Auxiliary.isModuleActivated -> "LSPosed"
                        Auxiliary.isRootActivated -> "Root"
                        Auxiliary.isShellActivated -> "Shell"
                        else -> "None"
                    }
                )

                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                InfoItem(
                    stringResource(R.string.daemon),
                    when {
                        isLoading -> stringResource(R.string.loading)
                        isDaemonRunning -> stringResource(R.string.running)
                        else -> stringResource(R.string.not_running)
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