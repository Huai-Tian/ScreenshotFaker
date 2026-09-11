package fake.screenshot.wrappers

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import fake.screenshot.LSPosedServiceManager
import fake.screenshot.hooks.ReplaceImageCodec
import fake.screenshot.hooks.ReplaceImageStore
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

    /**
     * 服务绑定 catch-up（onServiceBind 调用，与 TemplateManager 同一
     * 时序闭环）：对配置内全部替换图补投远程缺失（保存时服务未连接的
     * 空档在下一次绑定必然补齐）；同时删除配置外孤儿（模板已删/销毁后
     * 残留的 sf_img_*，销毁-未连接场景的中和在此兜底闭环——active 为
     * 空集时全删）
     */
    fun onServiceBound(context: Context) {
        scope.launch {
            runCatching {
                val service = LSPosedServiceManager.mService ?: return@launch
                val config = TemplateManager.configFlow(context).first()
                val active = buildSet {
                    config.templates.forEach { it.imageId?.let { id -> add(id) } }
                    if (config.globalReplaceEnabled && config.globalReplaceImage != null) {
                        add(config.globalReplaceImage)
                    }
                }
                val existing = service.listRemoteFiles()
                    .filter { it.startsWith(ReplaceImageStore.REMOTE_PREFIX) }
                // 孤儿清理：远程有、配置无（含销毁后残留——active 空集全删）
                existing.forEach { name ->
                    val id = name.removePrefix(ReplaceImageStore.REMOTE_PREFIX)
                    if (id !in active) runCatching { service.deleteRemoteFile(name) }
                }
                // 缺失补投：配置有、远程无（本地明文是保存保底）
                active.forEach { id ->
                    val name = ReplaceImageStore.remoteName(id)
                    if (name !in existing) {
                        val local = localFile(context, id)
                        if (local.exists()) runCatching { pushRemote(id, local) }
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
     */
    suspend fun save(context: Context, imageId: String, bitmap: Bitmap): Boolean =
        withContext(Dispatchers.IO) {
            // 1) 本地明文 PNG（压缩失败即整体失败——UI 凭据缺失）
            val local = localFile(context, imageId)
            runCatching {
                local.parentFile?.mkdirs()
                local.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.getOrElse { return@withContext false }

            // 2) 远程密文信封（服务未连接/写失败 → 本地已保底，false 提示补投）
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

    /** 本地 PNG 加密推送到托管区；返回远程文件名（失败 null） */
    private fun pushRemote(imageId: String, local: File): String? = runCatching {
        val service = LSPosedServiceManager.mService ?: return null
        val envelope = ReplaceImageCodec.encrypt(local.readBytes())
        val name = ReplaceImageStore.remoteName(imageId)
        val pfd: ParcelFileDescriptor = service.openRemoteFile(name)
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(envelope) }
        name
    }.getOrNull()
}