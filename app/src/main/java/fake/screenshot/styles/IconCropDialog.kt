package fake.screenshot.styles

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import fake.screenshot.R
import kotlin.math.max
import kotlin.math.min

@Composable
fun IconCropDialog(
    image: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // fitScale：等比适配裁剪框的初始缩放；minScale：覆盖裁剪框所需最小用户缩放
    var fitScale by remember { mutableFloatStateOf(1f) }
    var minScale by remember { mutableFloatStateOf(1f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    var initialized by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crop_icon)) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .onSizeChanged { size ->
                            boxSize = size
                            if (!initialized && size.width > 0) {
                                // 初始：等比适配后放大到恰好覆盖正方形裁剪框，居中
                                fitScale = min(
                                    size.width.toFloat() / image.width,
                                    size.height.toFloat() / image.height
                                )
                                val drawW = image.width * fitScale
                                val drawH = image.height * fitScale
                                minScale = max(
                                    size.width / drawW,
                                    size.height / drawH
                                )
                                scale = max(1f, minScale)
                                topLeft = Offset(
                                    (size.width - drawW * scale) / 2f,
                                    (size.height - drawH * scale) / 2f
                                )
                                initialized = true
                            }
                        }
                        .pointerInput(image) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val box = boxSize
                                if (box.width <= 0) return@detectTransformGestures
                                val drawW = image.width * fitScale
                                val drawH = image.height * fitScale
                                val minS = max(box.width / drawW, box.height / drawH)

                                val newScale = (scale * zoom).coerceIn(minS, 12f)
                                // 缩放围绕手势中心，再叠加平移
                                var newTopLeft = centroid -
                                        (centroid - topLeft) * (newScale / scale) + pan
                                // 约束：图片必须完整覆盖正方形裁剪框
                                val w = drawW * newScale
                                val h = drawH * newScale
                                newTopLeft = Offset(
                                    newTopLeft.x.coerceIn(box.width - w, 0f),
                                    newTopLeft.y.coerceIn(box.height - h, 0f)
                                )
                                scale = newScale
                                topLeft = newTopLeft
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        if (boxSize.width <= 0) return@Canvas
                        // 图片（fit + 用户变换）
                        withTransform({
                            translate(topLeft.x, topLeft.y)
                            scale(scale, scale, pivot = Offset.Zero)
                            scale(fitScale, fitScale, pivot = Offset.Zero)
                        }) {
                            drawImage(image.asImageBitmap())
                        }
                        // 裁剪框为整个正方形区域：白色描边 + 四角 L 形标记
                        drawRect(
                            color = Color.White,
                            topLeft = Offset.Zero,
                            size = size,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        val corner = 18.dp.toPx()
                        val stroke = 4.dp.toPx()
                        val corners = listOf(
                            Triple(Offset.Zero, Offset(corner, 0f), Offset(0f, corner)),
                            Triple(Offset(size.width, 0f), Offset(size.width - corner, 0f), Offset(size.width, corner)),
                            Triple(Offset(0f, size.height), Offset(corner, size.height), Offset(0f, size.height - corner)),
                            Triple(Offset(size.width, size.height), Offset(size.width - corner, size.height), Offset(size.width, size.height - corner))
                        )
                        for ((apex, hEnd, vEnd) in corners) {
                            drawLine(Color.White, apex, hEnd, strokeWidth = stroke)
                            drawLine(Color.White, apex, vEnd, strokeWidth = stroke)
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.crop_icon_hint),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val box = boxSize
                    if (box.width <= 0) return@TextButton
                    // 裁剪框（整个正方形）映射回源图坐标
                    val totalScale = scale * fitScale
                    val left = (-topLeft.x / totalScale).toInt()
                    val top = (-topLeft.y / totalScale).toInt()
                    val cropSize = (box.width / totalScale).toInt()
                        .coerceAtMost(min(image.width, image.height))
                    val safeLeft = left.coerceIn(0, image.width - cropSize)
                    val safeTop = top.coerceIn(0, image.height - cropSize)
                    val cropped = Bitmap.createBitmap(
                        image, safeLeft, safeTop, cropSize, cropSize
                    )
                    onConfirm(cropped)
                }
            ) { Text(stringResource(R.string.Confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.Cancel)) }
        }
    )
}
