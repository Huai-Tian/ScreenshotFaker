package fake.screenshot.wrappers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import fake.screenshot.LSPosedServiceManager
import fake.screenshot.hooks.ReplaceVideoStore
import fake.screenshot.hooks.VideoEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * App 侧替换视频管理：本地 MP4 持久化（UI 预览凭据）+ LSPosed 托管区
 * 流式密文投递（hook 进程运行态，经 XposedService.openRemoteFile——
 * fd 派发无 binder 1MB 限制）。
 *
 * 双存储职责（与 [ReplaceImageManager] 同构）：
 * - 本地 files/replace_video/&lt;videoId&gt;.mp4：明文，模块私有目录，UI
 *   缩略图渲染源与重投权威源；胁迫销毁随 [neutralizeForCoercion] 清除
 * - 远程 sf_vid_&lt;videoId&gt;：流式 AES-GCM 信封密文（[VideoEnvelope]），
 *   hook 进程经 [ReplaceVideoStore] 流式解密到 memfd 供 MediaPlayer
 *
 * 与图片通道的量化差异：视频几十 MB——投递与自愈校验均为**流式**
 * （绑定 catch-up 只补投缺失、不做全量损坏校验，尾部损坏由 hook 侧
 * 指纹负缓存 + 解密失败日志暴露，日志驱动人工重选自愈）。
 *
 * videoId 命名与图片同一 id 空间（文件名前缀区分）：全局固定 "g"，
 * 模板级 = 模板 id（模板删除时同步清视频）。
 */
object ReplaceVideoManager {

    private const val TAG = "SF-Video"

    private const val LOCAL_DIR = "replace_video"

    /** 全局替换视频的固定 videoId（与 ReplaceImageManager.GLOBAL_ID 同值同义） */
    const val GLOBAL_ID = "g"

    /** 单视频大小上限（导入校验；100MB 覆盖 1080p 约数分钟循环素材） */
    const val MAX_VIDEO_BYTES = 100L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun localFile(context: Context, videoId: String): File =
        File(File(context.filesDir, LOCAL_DIR), "$videoId.mp4")

    /** 首帧缩略图（UI 预览凭据；生成失败不阻断导入，UI 侧图标兜底） */
    fun thumbFile(context: Context, videoId: String): File =
        File(File(context.filesDir, LOCAL_DIR), "${videoId}_thumb.jpg")

    // ==================== 编辑页暂存区 ====================
    // 模板编辑页"保存才生效"语义与全局入口"即时生效"不同：导入先落
    // 暂存（不碰正式文件、不推远程——hook 侧零感知），保存时 promote
    // 转正；未保存退出 = 暂存作废（下次进入编辑页时 discard 清残留），
    // 正式区与远程保持旧状态，完全恢复原状

    fun stagingFile(context: Context, videoId: String): File =
        File(File(context.filesDir, LOCAL_DIR), "${videoId}_staging.mp4")

    fun stagingThumbFile(context: Context, videoId: String): File =
        File(File(context.filesDir, LOCAL_DIR), "${videoId}_staging_thumb.jpg")

    /** 暂存转正（编辑页保存时）：staging → 正式 + 远程投递；无暂存 no-op */
    suspend fun promoteStaging(context: Context, videoId: String) {
        withContext(Dispatchers.IO) {
            val staging = stagingFile(context, videoId)
            if (!staging.exists()) {
                Log.w(TAG, "promoteStaging: no staging file for $videoId (nothing to promote)")
                return@withContext
            }
            val local = localFile(context, videoId)
            // 同目录 rename 常规必成；失败（OEM 文件系统怪癖）回退 copy
            if (!staging.renameTo(local)) {
                staging.copyTo(local, overwrite = true)
                staging.delete()
            }
            val stagingThumb = stagingThumbFile(context, videoId)
            if (stagingThumb.exists()) {
                val thumb = thumbFile(context, videoId)
                if (!stagingThumb.renameTo(thumb)) {
                    stagingThumb.copyTo(thumb, overwrite = true)
                    stagingThumb.delete()
                }
            }
            Log.i(TAG, "promoteStaging: promoted ${local.length()} bytes for $videoId, pushing remote")
            val pushed = pushRemote(videoId, local)
            if (pushed == null) {
                // 不回滚（本地已保底，服务重绑 catch-up 补投），但必须
                // 可见——此前静默失败 = hook 侧 "remote file absent" 无从
                // 定位（实测 round 3）
                Log.w(TAG, "promoteStaging: remote push failed for $videoId (local kept, catch-up will retry)")
            }
        }
    }

    /** 丢弃暂存（编辑页清除按钮 / 进入页面清上次退出残留） */
    fun discardStaging(context: Context, videoId: String) {
        stagingFile(context, videoId).delete()
        stagingThumbFile(context, videoId).delete()
    }

    /**
     * 导入结果：失败原因分级（UI Toast 对应文案）
     */
    sealed class ImportResult {
        object Ok : ImportResult()
        object TooLarge : ImportResult()
        object Invalid : ImportResult()
        object ReadFailed : ImportResult()
    }

    /**
     * 选视频落双区：校验（大小上限 + 可解析视频轨）→ 本地 MP4 拷贝 →
     * 首帧缩略图 → 流式密文投递远程。服务未连接时仅落本地（下次绑定
     * catch-up 补投，与图片通道语义一致）；调用方以配置落库为准，
     * 远程缺视频在 hook 侧表现为"加载失败 = 回落静态图"，fail-open
     *
     * [staging] = true 时仅落暂存文件（编辑页"保存才生效"语义：不碰
     * 正式文件、不推远程，promoteStaging 转正）
     */
    suspend fun save(
        context: Context,
        videoId: String,
        uri: Uri,
        staging: Boolean = false
    ): ImportResult =
        withContext(Dispatchers.IO) {
            // 1) 源校验与本地拷贝（一次性流式；Uri 只开一次——OEM provider
            //    流可能是一次性的，同图片 decodeForCrop 的教训）
            val local = if (staging) stagingFile(context, videoId) else localFile(context, videoId)
            local.parentFile?.mkdirs()
            val copied = runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@runCatching false
                input.use { local.outputStream().use { output -> it.copyTo(output) } }
                true
            }.getOrDefault(false)
            if (!copied) {
                local.delete()
                return@withContext ImportResult.ReadFailed
            }

            // 2) 大小上限
            if (local.length() > MAX_VIDEO_BYTES) {
                local.delete()
                return@withContext ImportResult.TooLarge
            }

            // 3) 可播放性校验（有视频轨 + 时长 > 0；MediaMetadataRetriever
            //    对损坏文件抛 RuntimeException 或返回空元数据）
            val valid = runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(local.absolutePath)
                    val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    (duration?.toLongOrNull() ?: 0L) > 0L &&
                            (width?.toIntOrNull() ?: 0) > 0 &&
                            (height?.toIntOrNull() ?: 0) > 0
                }
            }.getOrDefault(false)
            if (!valid) {
                local.delete()
                return@withContext ImportResult.Invalid
            }

            // 4) 首帧缩略图（失败不阻断——UI 图标兜底）
            val thumb = if (staging) stagingThumbFile(context, videoId)
            else thumbFile(context, videoId)
            runCatching { generateThumb(local, thumb) }

            // 5) 远程流式密文（服务未连接/写失败 → 本地已保底，绑定补投；
            //    staging 不推——转正时统一投递）
            if (!staging) pushRemote(videoId, local)
            ImportResult.Ok
        }

    /** 删除双区视频 + 缩略图（模板删除/清除视频时） */
    suspend fun delete(context: Context, videoId: String) {
        withContext(Dispatchers.IO) {
            localFile(context, videoId).delete()
            thumbFile(context, videoId).delete()
            runCatching {
                LSPosedServiceManager.mService?.deleteRemoteFile(
                    ReplaceVideoStore.remoteName(videoId)
                )
            }
        }
    }

    /**
     * 服务绑定 catch-up（与 [ReplaceImageManager.onServiceBound] 同一时序
     * 闭环，差异见类注释）：补投配置内缺失视频 + 清理配置外孤儿。
     *
     * active 口径 = 已配置的视频，不含开关态（"关闭时静默保留"语义，
     * 同图片通道的教训：按开关态计算会把合法暂存视频当孤儿误删）
     */
    fun onServiceBound(context: Context) {
        scope.launch {
            runCatching {
                val service = LSPosedServiceManager.mService ?: return@launch
                val config = TemplateManager.configFlow(context).first()
                val active = buildSet {
                    config.templates.forEach { it.recordVideoId?.let { id -> add(id) } }
                    config.globalRecordVideoId?.let { add(it) }
                }
                val existing = service.listRemoteFiles()
                    .filter { it.startsWith(ReplaceVideoStore.REMOTE_PREFIX) }
                // 孤儿清理：远程有、配置无（含销毁后残留——active 空集全删）
                existing.forEach { name ->
                    val id = name.removePrefix(ReplaceVideoStore.REMOTE_PREFIX)
                    if (id !in active) runCatching { service.deleteRemoteFile(name) }
                }
                // 缺失补投：配置有、远程无（不做损坏校验——流式全量解密
                // 太重，尾部损坏由 hook 侧负缓存 + WARN 日志暴露）
                active.forEach { id ->
                    val name = ReplaceVideoStore.remoteName(id)
                    val local = localFile(context, id)
                    if (name !in existing && local.exists()) {
                        runCatching { pushRemote(id, local) }
                    }
                }
            }
        }
    }

    /**
     * 胁迫销毁中和（ConfigManager.resetForCoercion 序列调用）：本地明文
     * 目录全清（视频 + 缩略图——明文替换素材本身即取证信号）+ 托管区
     * 全部 sf_vid_* 远程文件删除（同步语义）
     */
    fun neutralizeForCoercion(context: Context) {
        File(context.filesDir, LOCAL_DIR).deleteRecursively()
        val service = LSPosedServiceManager.mService ?: return
        runCatching {
            service.listRemoteFiles()
                .filter { it.startsWith(ReplaceVideoStore.REMOTE_PREFIX) }
                .forEach { service.deleteRemoteFile(it) }
        }
    }

    // ==================== 私有实现 ====================

    /** 首帧缩略图：长边压到 512px（48dp 预览足够）+ JPEG 90 */
    private fun generateThumb(video: File, thumb: File) {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(video.absolutePath)
            @Suppress("DEPRECATION") // getFrameAtIndex 需 API 30+ 且部分 OEM 实现不稳
            val frame = r.getFrameAtTime(0) ?: return
            val scale = 512f / maxOf(frame.width, frame.height).coerceAtLeast(1)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame, (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1), true
                ).also { if (it != frame) frame.recycle() }
            } else frame
            thumb.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
        }
    }

    /**
     * 本地 MP4 流式加密推送到托管区；返回远程文件名（失败 null）。
     * 截断铁律与图片通道同源（openRemoteFile RW|CREATE 不截断，残留
     * 尾部令 GCM tag 错位恒败）：写前 ftruncate 归零 + 写后截到信封
     * 精确长度（计数输出流累计，fd 关闭前执行）。
     * 各失败出口分级日志（App 侧此前完全静默——hook 侧只见 "remote
     * file absent" 无从定位投递断点）
     */
    private fun pushRemote(videoId: String, local: File): String? = runCatching {
        val service = LSPosedServiceManager.mService ?: run {
            Log.w(TAG, "pushRemote: service not bound for $videoId (local kept)")
            return null
        }
        val name = ReplaceVideoStore.remoteName(videoId)
        val pfd: ParcelFileDescriptor = try {
            service.openRemoteFile(name)
        } catch (e: Exception) {
            Log.w(TAG, "pushRemote: openRemoteFile failed for $videoId: ${e.message}")
            return null
        }
        val counting = CountingOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pfd))
        counting.use {
            runCatching { android.system.Os.ftruncate(pfd.fileDescriptor, 0L) }
                .onFailure { Log.w(TAG, "pushRemote: pre-truncate failed for $videoId: ${it.message}") }
            VideoEnvelope.encryptTo(local.inputStream(), it)
            runCatching { android.system.Os.ftruncate(pfd.fileDescriptor, counting.count) }
                .onFailure { Log.w(TAG, "pushRemote: post-truncate failed for $videoId: ${it.message}") }
        }
        Log.i(TAG, "pushRemote: pushed ${counting.count} cipher bytes for $videoId")
        name
    }.onFailure {
        Log.w(TAG, "pushRemote: stream failed for $videoId: ${it.message}")
    }.getOrNull()

    /** OutputStream + 字节数计数（ftruncate 精确长度用）；
     *  close 委托底层（pfd 释放） */
    private class CountingOutputStream(out: OutputStream) : OutputStream() {
        var count = 0L
            private set
        private val o = out

        override fun write(b: Int) {
            o.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            o.write(b, off, len)
            count += len
        }

        override fun close() {
            o.close()
        }
    }
}
