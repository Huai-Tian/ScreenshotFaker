package fake.screenshot.services.privileged.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceControl

/**
 * 纯 Surface 渲染后端：双层结构（内容层 + 手柄层），全部经 SurfaceControl
 * 直挂 SurfaceFlinger，完全不经过 WindowManager / ViewRootImpl——
 * Android 15 的 WMS session 加固（"Unknown pid" 报错）被整体绕开。
 *
 * ==================== 层结构 ====================
 *
 * root layer（随机名，无 buffer，仅作定位容器）
 * ├── content layer：图片 / 视频。图片用 Canvas 软绘；视频把 Surface
 * │   直接交给 MediaPlayer（buffer producer 独占——这正是必须分层的
 * │   原因：同一 layer 上无法再叠加绘制）。
 * └── handle layer：四角缩放手柄 + 顶部中线指示（Canvas 软绘，顶层 z）。
 *
 * root layer setLayer(0x7FFFFFFF) 置顶；每层 setSkipScreenshot
 * （root 进程反射隐藏 API）使截屏/录屏完全跳过——FLAG_SECURE 在无窗口
 * 场景不可用，这是唯一的截图排除手段。
 *
 * Surface(SurfaceControl) 为公开构造（API 29+）；Builder 的
 * setBufferSize/setFormat/setParent 为 @hide，经 [OverlayHiddenApi]
 * 反射调用（root 进程 + hidden API 豁免后反射稳定可用）。
 *
 * 所有方法必须在同一线程（服务 handler 线程）调用。
 *
 * @param onFatal 渲染后端不可恢复错误（须触发回落）
 */
internal class OverlaySurfaceBackend(private val onFatal: (Throwable) -> Unit) {

    // ==================== 状态字段 ====================

    private var root: SurfaceControl? = null
    private var contentLayer: SurfaceControl? = null
    private var handleLayer: SurfaceControl? = null

    private var contentSurface: Surface? = null
    private var handleSurface: Surface? = null

    private var width = 0
    private var height = 0
    private var alpha = 1f

    // 图片状态（与原 RootOverlayService 手势逻辑一致）
    private var bitmap: Bitmap? = null
    private var mediaWidth = 0
    private var mediaHeight = 0
    private var currentScale = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var baseScale = 1.0f

    // 视频状态
    private var mediaPlayer: MediaPlayer? = null
    private var videoFd: ParcelFileDescriptor? = null
    private var isMuted = false
    var isVideo = false
        private set

    // 手柄绘制参数（与旧 CornerHandleView 一致）
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        // root 进程无 Resources：以 2.5f 近似 density（主流设备 2~3）
        strokeWidth = 6f * 2.5f
    }
    private val cornerSize = 30f * 2.5f

    // ==================== 生命周期 ====================

    /** 创建三层并显示。任何一步失败即上报回落（调用方 cleanup）。 */
    fun attach(x: Int, y: Int, w: Int, h: Int) {
        if (root != null) {
            setGeometry(x, y, w, h)
            return
        }
        width = w
        height = h
        try {
            val rootSc = OverlayHiddenApi.newLayerBuilder(OverlayHiddenApi.randomName()).build()
            val tx = SurfaceControl.Transaction()
            OverlayHiddenApi.txSetLayer(tx, rootSc, 0x7FFFFFFF)
            OverlayHiddenApi.txSetPosition(tx, rootSc, x, y)
            OverlayHiddenApi.txShow(tx, rootSc)
            tx.apply()

            val content = buildChildLayer(rootSc)
            val handle = buildChildLayer(rootSc)
            val tx2 = SurfaceControl.Transaction()
            OverlayHiddenApi.txSetBufferSize(tx2, content, w, h)
            OverlayHiddenApi.txSetBufferSize(tx2, handle, w, h)
            OverlayHiddenApi.txSetLayer(tx2, content, 1)
            OverlayHiddenApi.txSetLayer(tx2, handle, 2)
            OverlayHiddenApi.txShow(tx2, content)
            OverlayHiddenApi.txShow(tx2, handle)
            tx2.apply()

            root = rootSc
            contentLayer = content
            handleLayer = handle
            contentSurface = Surface(content)
            handleSurface = Surface(handle)

            // 截图排除（非致命：失败仅记录，悬浮照常显示）
            listOf(rootSc, content, handle).forEach {
                OverlayHiddenApi.applySkipScreenshot(it)
            }
            drawHandles()
        } catch (t: Throwable) {
            onFatal(t)
            detach()
        }
    }

    private fun buildChildLayer(parent: SurfaceControl): SurfaceControl {
        val builder = OverlayHiddenApi.newLayerBuilder(OverlayHiddenApi.randomName())
        OverlayHiddenApi.builderSetFormat(builder, PixelFormat.TRANSLUCENT)
        OverlayHiddenApi.builderSetParent(builder, parent)
        return builder.build()
    }

    fun detach() {
        releasePlayer()
        videoFd?.let { runCatching { it.close() } }
        videoFd = null
        bitmap = null
        mediaWidth = 0
        mediaHeight = 0
        runCatching { contentSurface?.release() }
        runCatching { handleSurface?.release() }
        contentSurface = null
        handleSurface = null
        contentLayer?.let { runCatching { it.release() } }
        handleLayer?.let { runCatching { it.release() } }
        root?.let { runCatching { it.release() } }
        contentLayer = null
        handleLayer = null
        root = null
        isVideo = false
    }

    val isAttached: Boolean get() = root != null

    // ==================== 几何 / 外观 ====================

    fun setGeometry(x: Int, y: Int, w: Int, h: Int) {
        val r = root ?: return
        width = w
        height = h
        val tx = SurfaceControl.Transaction()
        OverlayHiddenApi.txSetPosition(tx, r, x, y)
        contentLayer?.let { OverlayHiddenApi.txSetBufferSize(tx, it, w, h) }
        handleLayer?.let { OverlayHiddenApi.txSetBufferSize(tx, it, w, h) }
        tx.apply()
        // 视频生产者按 layer bounds 重新适配；图片重绘
        if (isVideo) {
            mediaPlayer?.let { mp -> runCatching { mp.setSurface(contentSurface) } }
        } else {
            drawImage()
        }
        drawHandles()
    }

    fun setAlpha(a: Float) {
        alpha = a.coerceIn(0f, 1f)
        val r = root ?: return
        val tx = SurfaceControl.Transaction()
        OverlayHiddenApi.txSetAlpha(tx, r, alpha)
        tx.apply()
    }

    // ==================== 图片渲染 ====================

    fun showImage(bm: Bitmap?) {
        releasePlayer()
        videoFd = null
        isVideo = false
        bitmap = bm
        mediaWidth = bm?.width ?: 0
        mediaHeight = bm?.height ?: 0
        currentScale = 1.0f
        panX = 0f
        panY = 0f
        baseScale = 1.0f
        drawImage()
    }

    fun scaleImage(factor: Float) {
        if (bitmap == null) return
        var newScale = currentScale * factor
        newScale = maxOf(newScale, 1.0f)
        if (newScale > 5.0f) return
        currentScale = newScale
        clampPan()
        drawImage()
    }

    fun panImage(dx: Float, dy: Float) {
        if (bitmap == null) return
        panX += dx
        panY += dy
        clampPan()
        drawImage()
    }

    private fun drawImage() {
        val bm = bitmap ?: run {
            // 无媒体：红底提示（与原实现一致）
            contentSurface?.let { s ->
                runCatching {
                    val canvas = s.lockHardwareCanvas()
                    canvas.drawColor(Color.RED)
                    s.unlockCanvasAndPost(canvas)
                }
            }
            return
        }
        val surface = contentSurface ?: return
        if (mediaWidth <= 0 || mediaHeight <= 0 || width <= 0 || height <= 0) return
        runCatching {
            val scaleX = width.toFloat() / mediaWidth
            val scaleY = height.toFloat() / mediaHeight
            baseScale = maxOf(scaleX, scaleY)
            val finalScale = baseScale * currentScale
            clampPan()
            val centerX = (width - mediaWidth * finalScale) / 2
            val centerY = (height - mediaHeight * finalScale) / 2
            val matrix = Matrix().apply {
                setScale(finalScale, finalScale)
                postTranslate(centerX + panX, centerY + panY)
            }
            val canvas = surface.lockHardwareCanvas()
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(bm, matrix, null)
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun clampPan() {
        if (mediaWidth <= 0 || mediaHeight <= 0 || width <= 0 || height <= 0) return
        val finalScale = baseScale * currentScale
        val scaledW = mediaWidth * finalScale
        val scaledH = mediaHeight * finalScale
        panX = if (scaledW > width) panX.coerceIn(-(scaledW - width) / 2, (scaledW - width) / 2) else 0f
        panY = if (scaledH > height) panY.coerceIn(-(scaledH - height) / 2, (scaledH - height) / 2) else 0f
    }

    // ==================== 手柄绘制（与旧 CornerHandleView 一致） ====================

    private fun drawHandles() {
        val surface = handleSurface ?: return
        runCatching {
            val canvas: Canvas = surface.lockHardwareCanvas()
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val w = width.toFloat()
            val h = height.toFloat()
            val size = cornerSize
            canvas.drawLine(0f, 0f, size, 0f, handlePaint)
            canvas.drawLine(0f, 0f, 0f, size, handlePaint)
            canvas.drawLine(w - size, 0f, w, 0f, handlePaint)
            canvas.drawLine(w, 0f, w, size, handlePaint)
            canvas.drawLine(0f, h - size, 0f, h, handlePaint)
            canvas.drawLine(0f, h, size, h, handlePaint)
            canvas.drawLine(w - size, h, w, h, handlePaint)
            canvas.drawLine(w, h - size, w, h, handlePaint)
            val centerX = w / 2
            canvas.drawLine(centerX - size, 0f, centerX + size, 0f, handlePaint)
            surface.unlockCanvasAndPost(canvas)
        }
    }

    // ==================== 视频渲染 ====================

    fun showVideo(fd: ParcelFileDescriptor) {
        clearMedia()
        isVideo = true
        videoFd = fd
        val surface = contentSurface ?: run {
            runCatching { fd.close() }
            videoFd = null
            return
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(fd.fileDescriptor)
                setSurface(surface)
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                isLooping = true
                setOnPreparedListener {
                    applyMuteState()
                    start()
                }
                setOnErrorListener { _, _, _ -> false }
                prepare()
            }
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    fun clearMedia() {
        releasePlayer()
        videoFd?.let { runCatching { it.close() } }
        videoFd = null
        isVideo = false
        bitmap = null
        mediaWidth = 0
        mediaHeight = 0
        currentScale = 1.0f
        panX = 0f
        panY = 0f
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun applyMuteState() {
        mediaPlayer?.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        applyMuteState()
    }

    // ==================== 视频控制 ====================

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        runCatching {
            if (mp.isPlaying) mp.pause() else mp.start()
        }
    }

    fun seekBy(deltaMs: Int) {
        val mp = mediaPlayer ?: return
        runCatching {
            val duration = mp.duration
            if (duration > 0) {
                val target = (mp.currentPosition + deltaMs)
                    .coerceIn(0, (duration - 250).coerceAtLeast(0))
                mp.seekTo(target)
            }
        }
    }
}
