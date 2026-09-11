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
 * 同屏语义只用 1 张，4 槽覆盖前后台快速切换的复用）；命中须经指纹
 * 校验（同 id 换图——重选槽位——远程文件被覆写而 imageId 不变、配置
 * JSON 亦相同（RemotePreferences 值级去重不推送），纯 id 键控会持续
 * 供旧图直到进程重启；指纹 = 远程文件 size+mtime，一次 open+fstat，
 * 落在本就重量级的截图保存路径时序预算内）；失败负缓存指纹感知
 * （[isNegativelyCached]——坏图/文件缺失不逐帧重试 IO，但远程文件
 * 变化（App 侧自愈重投/换图）即失效重试，无需等 reload）。
 */
object ReplaceImageStore {

    /** 远程文件名约定（App 侧 ReplaceImageManager 同步维护） */
    const val REMOTE_PREFIX = "sf_img_"

    fun remoteName(imageId: String): String = "$REMOTE_PREFIX$imageId"

    private const val CACHE_SLOTS = 4

    /** 缓存槽：指纹（[fingerprintOf]）+ 位图 */
    private class Entry(val fingerprint: Long, val bitmap: Bitmap)

    private val cache = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > CACHE_SLOTS
    }.let { Collections.synchronizedMap(it) as MutableMap<String, Entry> }

    /** 负缓存：id → 失败时的远程文件指纹（reload 重置；指纹感知见
     *  [isNegativelyCached]——App 侧自愈重投/换图后无需 reload 即失效重试） */
    private val failed = ConcurrentHashMap<String, Long>()

    @Volatile
    private var installed = false

    /** 引擎装配入口（幂等）：订阅配置 reload 做预热与失效清理 */
    fun ensureInstalled() {
        if (installed) return
        installed = true
        HookContext.addConfigReloadListener { reload() }
        reload()
    }

    /** 取图（未命中/指纹不符同步加载；null = 无图/加载失败，引擎按原生
     *  处理）。命中先过指纹校验，不符即逐出重载——同槽位换图即时生效 */
    fun bitmapFor(imageId: String): Bitmap? {
        val cached = cache[imageId]
        if (cached != null && fingerprintOf(imageId) == cached.fingerprint) return cached.bitmap
        if (isNegativelyCached(imageId)) return null
        return synchronized(imageId.intern()) {
            val resynced = cache[imageId]
            if (resynced != null && fingerprintOf(imageId) == resynced.fingerprint) return resynced.bitmap
            if (isNegativelyCached(imageId)) return null
            load(imageId)?.let { (fp, bmp) ->
                cache[imageId] = Entry(fp, bmp)
                bmp
            } ?: run {
                failed[imageId] = fingerprintOf(imageId)
                null
            }
        }
    }

    /**
     * 负缓存命中判定：远程文件指纹仍等于失败时指纹才命中。已变
     * （App 侧绑定自愈重投了历史损坏残留 / 换图覆盖）即逐出重试——
     * 修复后首个恢复路径不再依赖配置 reload（开关循环），App 侧重投
     * 完成后的下一次取图即生效
     */
    private fun isNegativelyCached(imageId: String): Boolean {
        val failedFp = failed[imageId] ?: return false
        if (fingerprintOf(imageId) == failedFp) return true
        failed.remove(imageId)
        return false
    }

    /** 远程文件指纹（size 高 32 位 + mtime 秒低 32 位；只 stat 不读字节。
     *  stat 失败/文件缺失返回 0——退化校验恒等价于旧实现的纯 id 键控，
     *  不劣化）。开销一次 openRemoteImage IPC + fstat */
    private fun fingerprintOf(imageId: String): Long = runCatching {
        HookContext.openRemoteImage(remoteName(imageId))?.use { pfd ->
            val st = android.system.Os.fstat(pfd.fileDescriptor)
            (st.st_size shl 32) or (st.st_mtime and 0xFFFFFFFFL)
        } ?: 0L
    }.getOrDefault(0L)

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

    /** 单 id 全链路：远程密文 → 解密 → 解码；附带文件指纹（同一 pfd
     *  先 stat 再读，供缓存槽校验） */
    private fun load(imageId: String): Pair<Long, Bitmap>? = runCatching {
        val pfd = HookContext.openRemoteImage(remoteName(imageId)) ?: run {
            HookContext.log(Log.WARN, "E3 remote file absent for $imageId")
            return null
        }
        var fingerprint = 0L
        val envelope = pfd.use { fd ->
            runCatching {
                val st = android.system.Os.fstat(fd.fileDescriptor)
                fingerprint = (st.st_size shl 32) or (st.st_mtime and 0xFFFFFFFFL)
            }
            ByteArrayOutputStream().use { out ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.copyTo(out) }
                out.toByteArray()
            }
        }
        val plain = ReplaceImageCodec.decrypt(envelope) ?: run {
            HookContext.log(Log.WARN, "E3 decrypt failed for $imageId")
            return null
        }
        BitmapFactory.decodeByteArray(plain, 0, plain.size)?.let { fingerprint to it } ?: run {
            HookContext.log(Log.WARN, "E3 decode failed for $imageId")
            null
        }
    }.onFailure {
        HookContext.log(Log.WARN, "E3 image load failed for $imageId: ${it.message}")
    }.getOrNull()
}
