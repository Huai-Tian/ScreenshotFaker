package fake.screenshot.hooks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E3 替换图的图片本体通道（App/hook 双侧共享）：
 *
 * App 侧（wrappers.ReplaceImageManager）把裁剪后的 PNG 字节以 AES-GCM
 * 信封写入框架远程文件（openRemoteFile，fd 派发无 binder 1MB 限制）；
 * hook 侧（[Store]）读远程密文 → 解密 → 解码 Bitmap → LRU 缓存。
 *
 * 落盘在 LSPosed 托管区而非模块私有目录，与 RemotePreferences 同存储
 * 语义——密文信封与 HookConfigCodec 同哲学：密钥源码常量派生，属混淆
 * 层而非密码学边界，防御目标是泛化取证扫描（磁盘上的文件不可直接
 * 解码为图像，gallery 类工具无从提取"配置了替换图"的事实）。
 *
 * 密文格式：[12B nonce][密文+16B GCM tag]，Base64 不参与（文件态二进制）。
 */
object ReplaceImageCodec {

    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    // 与 HookConfigCodec 同哲学（见其类注释）：独立种子的混淆层密钥
    private val key by lazy {
        val seed = "sf.replaceimg.v1::8c3e5a71d4b9f206e7a1c8d3b5f40962"
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()), "AES")
    }

    /** 加密为信封字节流（nonce 前置） */
    fun encrypt(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val ct = cipher.doFinal(plain)
        return nonce + ct
    }

    /** 解密信封；格式损坏/篡改（AEAD 校验失败）返回 null */
    fun decrypt(envelope: ByteArray): ByteArray? = runCatching {
        if (envelope.size <= NONCE_LEN) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(TAG_BITS, envelope.copyOfRange(0, NONCE_LEN))
        )
        cipher.doFinal(envelope.copyOfRange(NONCE_LEN, envelope.size))
    }.getOrNull()
}

/**
 * hook 进程侧替换图仓库（E3a 截屏应用进程 / E3b system_server 共用，
 * 单文件自管，不依赖引擎互相引用）。
 *
 * 加载策略 = 惰性 + 预热：配置 reload 时异步预热（daemon 线程一次性
 * 任务，解码完成即退出——不持有常驻线程，热重载卸载旧 classloader 后
 * 线程自然死亡无泄漏）；截屏瞬间未预热命中（冷进程首截）才付同步
 * 解码代价（~200ms 量级，落在截图流程本身的时序预算内）。
 *
 * 缓存：LruCache 4 槽（屏幕级 ARGB_8888 ~10MB/张，上限 ~40MB——
 * 同屏语义只用 1 张，4 槽覆盖前后台快速切换的复用）；失败负缓存到
 * reload 为止（坏图/文件缺失不逐帧重试 IO）。
 */
object ReplaceImageStore {

    /** 远程文件名约定（App 侧 ReplaceImageManager 同步维护） */
    const val REMOTE_PREFIX = "sf_img_"

    fun remoteName(imageId: String): String = "$REMOTE_PREFIX$imageId"

    private const val CACHE_SLOTS = 4

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > CACHE_SLOTS
    }.let { Collections.synchronizedMap(it) as MutableMap<String, Bitmap> }

    /** 负缓存：加载失败的 id（reload 时重置） */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var installed = false

    /** 引擎装配入口（幂等）：订阅配置 reload 做预热与失效清理 */
    fun ensureInstalled() {
        if (installed) return
        installed = true
        HookContext.addConfigReloadListener { reload() }
        reload()
    }

    /** 取图（未命中同步加载）；null = 无图/加载失败（引擎按原生处理） */
    fun bitmapFor(imageId: String): Bitmap? {
        cache[imageId]?.let { return it }
        if (failed.contains(imageId)) return null
        return synchronized(imageId.intern()) {
            cache[imageId]?.let { return it }
            load(imageId)?.also { cache[imageId] = it } ?: run {
                failed.add(imageId)
                null
            }
        }
    }

    /**
     * 配置 reload：清负缓存、逐出失效槽位、后台预热全部配置内 id
     * （模板数级小集合；daemon 一次性线程，空闲即死）
     */
    private fun reload() {
        failed.clear()
        val ids = HookContext.activeImageIds()
        cache.keys.retainAll(ids)
        if (ids.isEmpty()) return
        Thread({
            ids.forEach { runCatching { bitmapFor(it) } }
        }, "sf-img-warm").apply { isDaemon = true }.start()
    }

    /** 单 id 全链路：远程密文 → 解密 → 解码 */
    private fun load(imageId: String): Bitmap? = runCatching {
        val pfd = HookContext.openRemoteImage(remoteName(imageId)) ?: return null
        val envelope = pfd.use { fd ->
            ByteArrayOutputStream().use { out ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.copyTo(out) }
                out.toByteArray()
            }
        }
        val plain = ReplaceImageCodec.decrypt(envelope) ?: return null
        BitmapFactory.decodeByteArray(plain, 0, plain.size)
    }.onFailure {
        HookContext.log(Log.WARN, "E3 image load failed for $imageId: ${it.message}")
    }.getOrNull()
}
