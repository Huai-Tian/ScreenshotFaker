package fake.screenshot.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fake.screenshot.R
import fake.screenshot.wrappers.ScreenShareReceiver
import fake.screenshot.wrappers.ScreenShareReceiverConfig
import fake.screenshot.wrappers.ScreenShareReceiverManager

/**
 * 屏幕共享查看器：连接指定接收配置并渲染视频流。
 *
 * Surface 创建后启动接收（start 可重入），Surface 销毁时停止接收。
 * 视频按 session meta 中的尺寸做宽高比适配；启用控制时叠加触摸层与按键工具栏，
 * 把手势与按键转换为 scrcpy 控制消息发回发送端。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenShareViewerCompose(configId: Int) {
    val context = LocalContext.current
    var config by remember { mutableStateOf<ScreenShareReceiverConfig?>(null) }
    var receiver by remember { mutableStateOf<ScreenShareReceiver?>(null) }
    // 滚动模式：视频区单指拖动转为滚动事件（默认触摸模式直接注入触摸）
    var scrollMode by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }

    LaunchedEffect(configId) {
        config = ScreenShareReceiverManager.loadConfig(context, configId)
    }

    config?.let { cfg ->
        DisposableEffect(cfg.id) {
            val r = ScreenShareReceiverManager.getOrCreate(cfg)
            receiver = r
            onDispose { r.stop() }
        }

        receiver?.let { r ->
            val state by r.state.collectAsState()
            val videoSize by r.videoSize.collectAsState()
            val clipboardContent by r.clipboardContent.collectAsState()
            // 视频是否可用由协商结果决定（发送端可能仅共享音频/控制）
            val videoAvailable by r.videoAvailable.collectAsState()
            // 控制可用 = 协商到发送端提供的控制通道（无需本端配置）
            val controlEnabled = r.controlAvailable.collectAsState().value

            // 发送端剪贴板内容到达（自动同步或拉取响应）→ 写入本机剪贴板
            LaunchedEffect(clipboardContent) {
                clipboardContent?.let { text ->
                    if (text.isNotEmpty()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("text", text))
                    }
                }
            }

            // 发送端注入失败上报 → Toast 提示（不再静默无反应）
            val injectError by r.injectError.collectAsState()
            LaunchedEffect(injectError) {
                injectError?.let { err ->
                    Toast.makeText(
                        context,
                        "receiver_inject_failed $err",
                        Toast.LENGTH_LONG
                    ).show()
                    r.injectError.value = null
                }
            }

            Scaffold(
                topBar = { TopAppBar(title = { Text(cfg.name) }) },
                containerColor = Color.Black
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(Color.Black)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        // 视频区域：已知尺寸时按宽高比 letterbox 显示
                        val videoModifier = videoSize?.let { (w, h) ->
                            Modifier.aspectRatio(w.toFloat() / h)
                        } ?: Modifier.fillMaxSize()

                        Box(modifier = videoModifier) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    SurfaceView(ctx).apply {
                                        holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                r.setSurface(holder.surface)
                                                r.start()
                                            }

                                            override fun surfaceChanged(
                                                holder: SurfaceHolder,
                                                format: Int,
                                                width: Int,
                                                height: Int
                                            ) = Unit

                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                r.setSurface(null)
                                                r.stop()
                                            }
                                        })
                                    }
                                }
                            )

                            if (controlEnabled) {
                                if (scrollMode) {
                                    ScrollInputLayer(
                                        receiver = r,
                                        videoWidth = videoSize?.get(0),
                                        videoHeight = videoSize?.get(1)
                                    )
                                } else {
                                    TouchInputLayer(
                                        receiver = r,
                                        videoWidth = videoSize?.get(0),
                                        videoHeight = videoSize?.get(1)
                                    )
                                }
                            }
                        }

                        // 无视频模式（发送端仅共享音频/控制）：显示模式提示
                        if (state is ScreenShareReceiver.State.Running && !videoAvailable) {
                            Text(
                                text = stringResource(R.string.receiver_video_disabled),
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }

                        when (val s = state) {
                            is ScreenShareReceiver.State.Connecting ->
                                StatusOverlay(stringResource(R.string.receiver_connecting))

                            is ScreenShareReceiver.State.Failed ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.receiver_connection_failed) + s.message,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(onClick = { r.start() }) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }

                            is ScreenShareReceiver.State.Stopped ->
                                StatusOverlay(stringResource(R.string.not_running))

                            else -> Unit
                        }
                    }

                    if (controlEnabled) {
                        ControlToolbar(
                            receiver = r,
                            scrollMode = scrollMode,
                            onToggleScrollMode = { scrollMode = !scrollMode },
                            onShowTextInput = { showTextInput = true }
                        )
                    }
                }
            }
        }

        if (showTextInput) {
            TextInputDialog(
                onDismiss = { showTextInput = false },
                onSend = { text ->
                    receiver?.sendText(text)
                    showTextInput = false
                }
            )
        }
    } ?: run {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.receive_stealth_screen_sharing)) })
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(text = stringResource(R.string.loading))
            }
        }
    }
}

/**
 * 触摸转发层：跟踪任意多根手指，每根手指分配独立 pointerId（-2 起递减，
 * -1 为鼠标保留），按下/移动/抬起分别注入发送端，支持双指缩放等多指手势。
 * 视频尺寸未知时不转发（坐标无法映射）。
 *
 * 注意：必须用 change.id（PointerId）作为手指标识——每个指针事件都会产生新的
 * PointerInputChange 实例，不能拿事件对象本身做 key。
 */
@Composable
private fun TouchInputLayer(
    receiver: ScreenShareReceiver,
    videoWidth: Int?,
    videoHeight: Int?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(videoWidth, videoHeight) {
                // videoSize 未收到时触摸层不工作（session meta 包异常）
                val vw = videoWidth ?: return@pointerInput
                val vh = videoHeight ?: return@pointerInput
                fun mapX(x: Float) = (x / size.width * vw).toInt().coerceIn(0, vw - 1)
                fun mapY(y: Float) = (y / size.height * vh).toInt().coerceIn(0, vh - 1)

                var nextPointerId = ScreenShareReceiver.POINTER_ID_FIRST_FINGER
                awaitEachGesture {
                    // 活跃手指：Compose PointerId → 发送到对端的 scrcpy pointerId
                    val active = HashMap<PointerId, Long>()
                    // 各手指最后位置，异常中断时用于补发抬起
                    val lastPosition = HashMap<PointerId, Offset>()

                    fun inject(change: PointerInputChange, action: Int, pressure: Float) {
                        val pid = active[change.id] ?: return
                        receiver.sendTouch(
                            action, pid,
                            mapX(change.position.x), mapY(change.position.y),
                            vw, vh, pressure
                        )
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val newlyPressed = !change.previousPressed && change.pressed
                                val released = change.previousPressed && !change.pressed
                                when {
                                    newlyPressed -> {
                                        active[change.id] = nextPointerId--
                                        lastPosition[change.id] = change.position
                                        inject(change, ScreenShareReceiver.ACTION_DOWN, 1f)
                                        change.consume()
                                    }
                                    released -> {
                                        inject(change, ScreenShareReceiver.ACTION_UP, 0f)
                                        active.remove(change.id)
                                        lastPosition.remove(change.id)
                                        change.consume()
                                    }
                                    change.pressed && change.positionChanged() -> {
                                        lastPosition[change.id] = change.position
                                        inject(change, ScreenShareReceiver.ACTION_MOVE, 1f)
                                        change.consume()
                                    }
                                    else -> {}
                                }
                            }
                            if (active.isEmpty()) break
                        }
                    } catch (e: Exception) {
                        // 手势流中断（尺寸变化/协程取消）：为残余手指补发抬起，
                        // 避免发送端触点悬死；CancellationException 继续向上抛
                        active.forEach { (composeId, pid) ->
                            val pos = lastPosition[composeId] ?: Offset.Zero
                            receiver.sendTouch(
                                ScreenShareReceiver.ACTION_UP, pid,
                                mapX(pos.x), mapY(pos.y),
                                vw, vh, 0f
                            )
                        }
                        if (e is kotlinx.coroutines.CancellationException) throw e
                    }
                }
            }
    )
}

/**
 * 滚动层：视频区单指垂直拖动转为滚动事件注入发送端，
 * 适用于不方便精确滑动的长列表滚动。
 */
@Composable
private fun ScrollInputLayer(
    receiver: ScreenShareReceiver,
    videoWidth: Int?,
    videoHeight: Int?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(videoWidth, videoHeight) {
                // videoSize 未收到时滚动层不工作（session meta 包异常）
                val vw = videoWidth ?: return@pointerInput
                val vh = videoHeight ?: return@pointerInput
                // 拖动像素 → 滚动单位的换算系数（约 40px = 1 次滚轮）
                val SCROLL_PIXEL_UNIT = 40f
                detectVerticalDragGestures { change, dragAmount ->
                    val x = (change.position.x / size.width * vw).toInt().coerceIn(0, vw - 1)
                    val y = (change.position.y / size.height * vh).toInt().coerceIn(0, vh - 1)
                    // 向上拖 = 向下滚动（同触屏列表惯性方向）
                    val vScroll = -dragAmount / SCROLL_PIXEL_UNIT
                    receiver.sendScroll(x, y, vw, vh, 0f, vScroll)
                    change.consume()
                }
            }
    )
}

/**
 * 远程控制工具栏：滚动模式切换 / 输入文字 / 剪贴板推拉 /
 * 返回 / 主页 / 最近任务 / 音量 / 电源 / 通知栏 / 旋转
 */
@Composable
private fun ControlToolbar(
    receiver: ScreenShareReceiver,
    scrollMode: Boolean,
    onToggleScrollMode: () -> Unit,
    onShowTextInput: () -> Unit
) {
    val context = LocalContext.current
    val pulledMessage = stringResource(R.string.receiver_clipboard_pulled)
    val pushedMessage = stringResource(R.string.receiver_clipboard_pushed)

    fun localClipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ToolbarButton(
            icon = Icons.Default.SwapVert,
            description = stringResource(R.string.receiver_scroll_mode),
            highlighted = scrollMode,
            onClick = onToggleScrollMode
        )

        ToolbarButton(
            icon = Icons.Default.Keyboard,
            description = stringResource(R.string.receiver_keyboard),
            onClick = onShowTextInput
        )

        ToolbarButton(
            icon = Icons.Default.ContentPaste,
            description = stringResource(R.string.receiver_clipboard_push),
            onClick = {
                receiver.sendSetClipboard(localClipboardText(), paste = false)
                Toast.makeText(context, pushedMessage, Toast.LENGTH_SHORT).show()
            }
        )

        ToolbarButton(
            icon = Icons.Default.ContentCopy,
            description = stringResource(R.string.receiver_clipboard_pull),
            onClick = {
                receiver.sendGetClipboard()
                Toast.makeText(context, pulledMessage, Toast.LENGTH_SHORT).show()
            }
        )

        ToolbarButton(
            icon = Icons.Default.PowerSettingsNew,
            description = stringResource(R.string.receiver_power)
        ) { receiver.sendKey(ScreenShareReceiver.KEYCODE_POWER) }

        ToolbarButton(
            icon = Icons.Default.Notifications,
            description = stringResource(R.string.receiver_notifications)
        ) { receiver.sendEmptyEvent(ScreenShareReceiver.TYPE_EXPAND_NOTIFICATION_PANEL) }

        ToolbarButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            description = stringResource(R.string.receiver_back)
        ) { receiver.sendKey(ScreenShareReceiver.KEYCODE_BACK) }

        ToolbarButton(
            icon = Icons.Default.Home,
            description = stringResource(R.string.receiver_home)
        ) { receiver.sendKey(ScreenShareReceiver.KEYCODE_HOME) }

        ToolbarButton(
            icon = Icons.Default.Apps,
            description = stringResource(R.string.receiver_recents)
        ) { receiver.sendKey(ScreenShareReceiver.KEYCODE_APP_SWITCH) }

        ToolbarButton(
            icon = Icons.Default.KeyboardArrowDown,
            description = stringResource(R.string.receiver_volume_down)
        ) { receiver.sendKey(ScreenShareReceiver.KEYCODE_VOLUME_DOWN) }

        ToolbarButton(
            icon = Icons.Default.KeyboardArrowUp,
            description = stringResource(R.string.receiver_volume_up)
        ) { receiver.sendKey(ScreenShareReceiver.KEYCODE_VOLUME_UP) }

        ToolbarButton(
            icon = Icons.Default.ScreenRotation,
            description = stringResource(R.string.receiver_rotate)
        ) { receiver.sendEmptyEvent(ScreenShareReceiver.TYPE_ROTATE_DEVICE) }
    }
}

/** 文本注入对话框：输入任意文本（含中文）注入发送端当前焦点输入框 */
@Composable
private fun TextInputDialog(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.receiver_keyboard)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }, enabled = text.isNotEmpty()) {
                Text(stringResource(R.string.receiver_keyboard_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.Cancel))
            }
        }
    )
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            } else {
                Color.White.copy(alpha = 0.15f)
            },
            contentColor = Color.White
        )
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun StatusOverlay(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}