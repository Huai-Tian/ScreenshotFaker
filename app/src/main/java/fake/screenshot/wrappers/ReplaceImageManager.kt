package fake.screenshot.wrappers

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import fake.screenshot.LSPosedServiceManager
import fake.screenshot.hooks.ReplaceImageCodec
import fake.screenshot.hooks.ReplaceImageStore
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App 侧替换图管理：本地 PNG 持久化（UI 预览凭据）+ LSPosed 托管区
 * 密文投递（hook 进程运行态，经 XposedService.openRemoteFile——fd
 * 派发无 binder 1MB 限制）。
 *
 * 双存储职责（与 TemplateManager 的配置双区同构）：
 * - 本地 files/replace/&lt;imageId&gt;.png：明文，模块私有目录，UI 缩略图
 *   渲染源；胁迫销毁随 [neutralizeForCoercion] 一并清除（明文假图
 *   本身即取证信号）
 * - 远程 sf_img_&lt;imageId&gt;：AES-GCM 信封密文（[fake.screenshot.hooks.ReplaceImageCodec]），
 *   hook 进程经 [fake.screenshot.hooks.ReplaceImageStore] 读取解密
 *
 * imageId 命名：全局图固定 "g"，模板图 = 模板 id（模板删除时同步清图）。
 */
object ReplaceImageManager {

    private const val LOCAL_DIR = "replace"

    /** 全局替换图的固定 imageId */
    const val GLOBAL_ID = "g"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun localFile(context: Context, imageId: String): File =
        File(File(context.filesDir, LOCAL_DIR), "$imageId.png")

    // ==================== 编辑页暂存区 ====================
    // 与 [ReplaceVideoManager] 暂存区同构：编辑页"保存才生效"语义下导入
    // 先落暂存（不碰正式文件、不推远程），保存时 promoteStaging 转正；
    // 未保存退出 = 暂存作废（编辑页进入时 discard 清残留），原状保留

    fun stagingFile(context: Context, imageId: String): File =
        File(File(context.filesDir, LOCAL_DIR), "${imageId}_staging.png")

    /** 暂存转正（编辑页保存时）：staging → 正式 + 远程投递；无暂存 no-op */
    suspend fun promoteStaging(context: Context, imageId: String) {
        withContext(Dispatchers.IO) {
            val staging = stagingFile(context, imageId)
            if (!staging.exists()) return@withContext
            val local = localFile(context, imageId)
            if (!staging.renameTo(local)) {
                staging.copyTo(local, overwrite = true)
                staging.delete()
            }
            pushRemote(imageId, local)
        }
    }

    /** 丢弃暂存（编辑页清除按钮 / 进入页面清上次退出残留） */
    fun discardStaging(context: Context, imageId: String) {
        stagingFile(context, imageId).delete()
    }

    /**
     * 服务绑定 catch-up（onServiceBind 调用，与 TemplateManager 同一
     * 时序闭环）：对配置内全部替换图补投远程缺失（保存时服务未连接的
     * 空档在下一次绑定必然补齐）；同时删除配置外孤儿（模板已删/销毁后
     * 残留的 sf_img_*，销毁-未连接场景的中和在此兜底闭环——active 为
     * 空集时全删）
     *
     * active 口径 = 已配置的图，不含开关态：HookConfig 语义为"关闭时
     * 静默保留（globalReplaceImage 不清除）"——若按开关态计算，任何
     * 一次"开关=关"时的 App 重启（装 APK/后台被杀后重开）都会把全局
     * 图当孤儿删除；而补投仅在绑定时机发生（开关循环不触发），删除后
     * 重开开关无法自愈，E3a 在 hook 侧静默 miss（远程文件缺失 →
     * ReplaceImageStore 负缓存固化），表现为"替换偶发失效、装 APK
     * 后恢复"（重启 App → 绑定 → 缺失补投恰好是唯一恢复路径）
     */
    fun onServiceBound(context: Context) {
        scope.launch {
            runCatching {
                val service = LSPosedServiceManager.mService ?: return@launch
                val config = TemplateManager.configFlow(context).first()
                val active = buildSet {
                    config.templates.forEach { it.imageId?.let { id -> add(id) } }
                    config.globalReplaceImage?.let { add(it) }
                }
                val existing = service.listRemoteFiles()
                    .filter { it.startsWith(ReplaceImageStore.REMOTE_PREFIX) }
                // 孤儿清理：远程有、配置无（含销毁后残留——active 空集全删）
                existing.forEach { name ->
                    val id = name.removePrefix(ReplaceImageStore.REMOTE_PREFIX)
                    if (id !in active) runCatching { service.deleteRemoteFile(name) }
                }
                // 缺失/损坏补投：配置有、远程无，或远程信封解密失败（历史
                // 截断缺陷损坏的残留 / 写半途中断）——本地明文是保存保底
                // 与权威源，重投即愈（见 pushRemote 截断注释）
                active.forEach { id ->
                    val name = ReplaceImageStore.remoteName(id)
                    val local = localFile(context, id)
                    if (!local.exists()) return@forEach
                    val corrupt = name in existing && !remoteEnvelopeOk(service, name)
                    if (name !in existing || corrupt) {
                        runCatching { pushRemote(id, local) }
                    }
                }
            }
        }
    }

    /**
     * 裁剪后的 Bitmap 落双区：本地 PNG（UI 凭据）+ 远程密文（hook 运行态）。
     * 服务未连接时仅落本地并返回 false（下次保存/绑定时补投——与
     * TemplateManager 的 catch-up 语义一致；调用方以配置落库为准，
     * 远程缺图在 hook 侧表现为"该 id 加载失败 = 原生截图"，fail-open）
     *
     * [staging] = true 时仅落暂存文件（编辑页"保存才生效"语义：不碰
     * 正式文件、不推远程，promoteStaging 转正）
     */
    suspend fun save(
        context: Context,
        imageId: String,
        bitmap: Bitmap,
        staging: Boolean = false
    ): Boolean =
        withContext(Dispatchers.IO) {
            // 1) 本地明文 PNG（压缩失败即整体失败——UI 凭据缺失）
            val local = if (staging) stagingFile(context, imageId) else localFile(context, imageId)
            runCatching {
                local.parentFile?.mkdirs()
                local.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.getOrElse { return@withContext false }

            // 2) 远程密文信封（服务未连接/写失败 → 本地已保底，false 提示补投；
            //    staging 不推——转正时统一投递）
            if (staging) return@withContext true
            pushRemote(imageId, local) ?: return@withContext false
            true
        }

    /** 删除双区图（模板删除/清除图时） */
    suspend fun delete(context: Context, imageId: String) {
        withContext(Dispatchers.IO) {
            localFile(context, imageId).delete()
            runCatching {
                LSPosedServiceManager.mService?.deleteRemoteFile(
                    ReplaceImageStore.remoteName(imageId)
                )
            }
        }
    }

    /**
     * 胁迫销毁中和（ConfigManager.resetForCoercion 序列调用）：本地
     * 明文目录全清 + 托管区全部 sf_img_* 远程文件删除。同步语义
     * （与 TemplateManager.neutralizeRemoteForCoercion 同一序列）
     */
    fun neutralizeForCoercion(context: Context) {
        File(context.filesDir, LOCAL_DIR).deleteRecursively()
        val service = LSPosedServiceManager.mService ?: return
        runCatching {
            service.listRemoteFiles()
                .filter { it.startsWith(ReplaceImageStore.REMOTE_PREFIX) }
                .forEach { service.deleteRemoteFile(it) }
        }
    }

    /**
     * 本地 PNG 加密推送到托管区；返回远程文件名（失败 null）。
     *
     * 截断铁律：openRemoteFile 为 RW|CREATE 语义不截断——重存更短信封
     * 时旧文件内容残留尾巴，而信封格式 [nonce][密文+GCM tag] 的 tag 在
     * 文件尾，解密把整个尾部当 tag → 恒败（"重选小图后替换永久失效"
     * 的根因，真机实证：g 槽位重选后 decrypt failed 持续）。双重保险：
     * 写前 ftruncate 归零 + 写后截到精确长度（fd 关闭前执行），任一生效
     * 即保证文件 = 信封精确字节
     */
    private fun pushRemote(imageId: String, local: File): String? = runCatching {
        val service = LSPosedServiceManager.mService ?: return null
        val envelope = ReplaceImageCodec.encrypt(local.readBytes())
        val name = ReplaceImageStore.remoteName(imageId)
        val pfd: ParcelFileDescriptor = service.openRemoteFile(name)
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
            runCatching { android.system.Os.ftruncate(pfd.fileDescriptor, 0L) }
            it.write(envelope)
            runCatching { android.system.Os.ftruncate(pfd.fileDescriptor, envelope.size.toLong()) }
        }
        name
    }.getOrNull()

    /** 远程信封完整性校验（绑定自愈用）：读全文解密，通过 = 非损坏 */
    private fun remoteEnvelopeOk(service: XposedService, name: String): Boolean = runCatching {
        val envelope = java.io.ByteArrayOutputStream().use { out ->
            ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name)).use { it.copyTo(out) }
            out.toByteArray()
        }
        ReplaceImageCodec.decrypt(envelope) != null
    }.getOrDefault(false)
}