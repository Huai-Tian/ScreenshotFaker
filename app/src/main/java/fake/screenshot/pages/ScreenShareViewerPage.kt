package fake.screenshot.pages

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import fake.screenshot.R
import fake.screenshot.wrappers.ScreenShareReceiver
import fake.screenshot.wrappers.ScreenShareReceiverConfig
import fake.screenshot.wrappers.ScreenShareReceiverManager

/**
 * 屏幕共享查看器：连接指定接收配置并渲染视频流。
 *
 * Surface 创建后启动接收（start 可重入），Surface 销毁时停止接收。
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

            Scaffold(
                topBar = {
                    TopAppBar(title = { Text(cfg.name) })
                },
                containerColor = Color.Black
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(Color.Black)
                ) {
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
