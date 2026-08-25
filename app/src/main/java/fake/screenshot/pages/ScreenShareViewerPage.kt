package fake.screenshot.pages

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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

                            // 触摸层：单指手势映射到视频坐标并注入发送端
                            if (cfg.enableControl) {
                                TouchInputLayer(
                                    receiver = r,
                                    videoWidth = videoSize?.get(0),
                                    videoHeight = videoSize?.get(1)
                                )
                            }
                        }

                        when (val s = state) {
                            is ScreenShareReceiver.State.Connecting ->
                                StatusOverlay(stringResource(R.string.receiver_connecting))

                            is ScreenShareReceiver.State.Failed ->
                                StatusOverlay(
                                    stringResource(R.string.receiver_connection_failed) + s.message
                                )

                            is ScreenShareReceiver.State.Stopped ->
                                StatusOverlay(stringResource(R.string.not_running))

                            else -> Unit
                        }
                    }

                    if (cfg.enableControl) {
                        ControlToolbar(receiver = r)
                    }
                }
            }
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
 * 触摸转发层：把本机单指按下/移动/抬起点位映射到视频坐标后注入发送端。
 * 视频尺寸未知时不转发（坐标无法映射）。
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
                val vw = videoWidth ?: return@pointerInput
                val vh = videoHeight ?: return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun map(pos: androidx.compose.ui.geometry.Offset): Pair<Int, Int> {
                        val x = (pos.x / size.width * vw).toInt().coerceIn(0, vw - 1)
                        val y = (pos.y / size.height * vh).toInt().coerceIn(0, vh - 1)
                        return x to y
                    }

                    val (dx, dy) = map(down.position)
                    receiver.sendTouch(ScreenShareReceiver.ACTION_DOWN, dx, dy, vw, vh, 1f)
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                val (ux, uy) = map(change.position)
                                receiver.sendTouch(ScreenShareReceiver.ACTION_UP, ux, uy, vw, vh, 0f)
                                change.consume()
                                break
                            }
                            if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero) {
                                val (mx, my) = map(change.position)
                                receiver.sendTouch(ScreenShareReceiver.ACTION_MOVE, mx, my, vw, vh, 1f)
                                change.consume()
                            }
                        }
                    } catch (_: Exception) {
                        // 手势检测中断（如尺寸变化），发送抬起避免发送端触点悬死
                        receiver.sendTouch(ScreenShareReceiver.ACTION_UP, 0, 0, vw, vh, 0f)
                    }
                }
            }
    )
}

/** 远程按键工具栏：返回 / 主页 / 最近任务 / 音量 / 旋转 */
@Composable
private fun ControlToolbar(receiver: ScreenShareReceiver) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ToolbarButton(
            icon = Icons.Default.KeyboardArrowLeft,
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

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.White.copy(alpha = 0.15f),
            contentColor = Color.White
        )
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
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
