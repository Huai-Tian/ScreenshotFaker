package fake.screenshot.hooks

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E3b 替换视频的信封编解码（App/hook 双侧共享）。
 *
 * 与 [ReplaceImageCodec] 同格式（[12B nonce][密文+16B GCM tag]）同哲学
 * （密钥源码常量派生的混淆层，防泛化取证扫描），但实现为**流式**——
 * 视频几十 MB，全量字节数组加解密在 system_server 有 OOM 风险
 * （整机软重启），分块处理使内存占用仅为缓冲区。tag 在 doFinal 校验
 * （GCM AEAD 语义）。
 */
object VideoEnvelope {

    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    /** 分块缓冲 1MB（CipherInputStream 路径实测 0.2 MB/s 吞吐、35s/6.5MB
     *  ——Conscrypt 流实现对底层流的读取粒度不受控；手动分块使 read/
     *  update/write 三侧调用数与缓冲对齐。吞吐回归由 [DecryptStats] 的
     *  读侧/总耗时分离打点观测） */
    private const val IO_BUFFER = 1024 * 1024

    // 独立种子的混淆层密钥（与 ReplaceImageCodec/HookConfigCodec 同哲学）
    private val key by lazy {
        val seed = "sf.replacevid.v1::d4a1f8c6b0e35927a8f14c6d0b9e2a73"
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()), "AES")
    }

    /**
     * 流式加密（App 侧投递用）：nonce 头 + 密文流。写完显式 close 内层
     * CipherOutputStream（输出尾部 GCM tag）；外层 [out] 生命周期归调用方
     */
    fun encryptTo(plain: InputStream, out: OutputStream) {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        out.write(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val cos = javax.crypto.CipherOutputStream(out, cipher)
        plain.copyTo(cos, IO_BUFFER)
        cos.close()
    }

    /**
     * 流式解密（hook 侧播放用）：读 nonce 头 → 分块 cipher.update →
     * doFinal（GCM tag 在此校验，不符抛 AEADBadTagException → false）。
     * 返回 [DecryptStats]（读侧耗时分离——读 MB/s 异常低 = LSPosed
     * 远程 fd 读路径瓶颈，与密码学无关）
     */
    fun decryptTo(envelope: InputStream, out: OutputStream): DecryptStats {
        val nonce = ByteArray(NONCE_LEN)
        if (!readFully(envelope, nonce)) return DecryptStats(false, 0L, 0L)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val buf = ByteArray(IO_BUFFER)
        var bytes = 0L
        var readNanos = 0L
        return try {
            while (true) {
                val t0 = System.nanoTime()
                val n = envelope.read(buf)
                readNanos += System.nanoTime() - t0
                if (n < 0) break
                if (n == 0) continue
                cipher.update(buf, 0, n)?.takeIf { it.isNotEmpty() }?.let {
                    out.write(it)
                    bytes += it.size
                }
            }
            cipher.doFinal()?.takeIf { it.isNotEmpty() }?.let {
                out.write(it)
                bytes += it.size
            }
            DecryptStats(true, bytes, readNanos)
        } catch (_: IOException) {
            DecryptStats(false, bytes, readNanos)
        } catch (_: GeneralSecurityException) {
            // AEADBadTagException（tag 不符——历史截断残留/写半途中断）
            DecryptStats(false, bytes, readNanos)
        }
    }


    /** InputStream.readNBytes 的手写版（API 33 前无此方法） */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** 解密统计：ok + 明文字节数 + 底层读累计纳秒（吞吐分离观测） */
    class DecryptStats(val ok: Boolean, val bytes: Long, val readNanos: Long)
}

/**
 * hook 进程侧替换视频仓库（E3b system_server 消费）。
 *
 * 视频的"解码"是 MediaPlayer 的事，会话期由播放器持有解码线程；本仓库
 * 负责把远程密文信封流式解密到 **memfd**（匿名内存文件：可 seek、不落
 * 盘、内存页由内核 tmpfs 管理可回收），以**单槽正缓存**持有（实测解密
 * 吞吐受远程读路径制约——6.5MB 耗时 35s，逐会话重解密不可接受）：
 *
 * - 预热：配置同步后台线程解密全局视频入缓存（冷启动/配置推送与录屏
 *   间的空闲窗口通常以分钟计，35s 级解密完全被吸收）
 * - 会话：[playableFd] dup 交付（零拷贝零解密），模板视频首用/缓存逐出
 *   后走惰性解密
 * - 失效：指纹感知（同图片口径）——远程文件变化（App 侧换视频/自愈重
 *   投）即逐出重解，不依赖配置 reload；负缓存同图片（坏视频/文件缺失
 *   不逐会话重试 IO）
 */
object ReplaceVideoStore {

    /** 远程文件名约定（App 侧 ReplaceVideoManager 同步维护） */
    const val REMOTE_PREFIX = "sf_vid_"

    fun remoteName(videoId: String): String = "$REMOTE_PREFIX$videoId"

    /** 正缓存条目：已解密 memfd（fd 归缓存所有，会话取 dup） */
    private class Entry(val fd: FileDescriptor, val length: Long, val fingerprint: Long)

    /** 负缓存：id → 失败时的远程文件指纹（reload 重置；指纹感知同图片） */
    private val failed = ConcurrentHashMap<String, Long>()

    /**
     * 单槽正缓存（[lock] 全局互斥）。单槽 = 内存上限锁定单视频明文
     * （App 侧 100MB 约束），不随模板数放大；逐出关闭原 fd——会话经
     * [dupOf] 持有的 dup 是独立 fd，不受影响（dup 共享文件描述与偏移：
     * 顺序会话安全；并发同视频双会话的 seek 竞态不支持——单活动录屏
     * 的现实约束）
     */
    private val lock = Any()
    private var cached: Entry? = null
    private var cachedId: String? = null

    @Volatile
    private var installed = false

    /** 引擎装配入口（幂等）：订阅配置 reload（失效清理 + 预热） */
    fun ensureInstalled() {
        if (installed) return
        installed = true
        HookContext.addConfigReloadListener { reload() }
        reload()
    }

    /**
     * 播放就绪数据源：正缓存命中（指纹一致）→ dup 交付；未命中 → 流式
     * 解密入缓存后 dup。返回 (fd, 明文字节长度)，fd 的 close 责任归调用
     * 方（会话生命周期，E3b 在 MediaPlayer.release 后 Os.close）。
     * null = 任一环节失败（负缓存登记 + 分级 WARN），调用方 fail-open
     * 回落静态图
     */
    fun playableFd(videoId: String): Pair<FileDescriptor, Long>? {
        if (isNegativelyCached(videoId)) {
            HookContext.log(Log.WARN, "E3b video negatively cached for $videoId (remote fingerprint unchanged)")
            return null
        }
        synchronized(lock) {
            if (isNegativelyCached(videoId)) return null
            val fp = fingerprintOf(videoId)
            cached?.takeIf { cachedId == videoId && it.fingerprint == fp }?.let { hit ->
                dupOf(hit)?.let { return it to hit.length }
                // dup 失败（fd 耗尽极端态）：落重解路径（保守但可用）
            }
            val entry = loadEntry(videoId) ?: run {
                failed[videoId] = fp
                return null
            }
            runCatching { cached?.let { Os.close(it.fd) } }
            cached = entry
            cachedId = videoId
            dupOf(entry)?.let { return it to entry.length }
            // dup 失败：丢弃条目按失败处理（下次重试）
            runCatching { Os.close(entry.fd) }
            cached = null
            cachedId = null
            failed[videoId] = fp
            return null
        }
    }

    /** 缓存条目 → 会话 fd：dup（独立 fd，缓存逐出不受影响）+ 归零偏移
     *  （上次会话/player 的 seek 可能移动过共享偏移） */
    private fun dupOf(e: Entry): FileDescriptor? = runCatching {
        Os.dup(e.fd).also { Os.lseek(it, 0, OsConstants.SEEK_SET) }
    }.getOrNull()

    /**
     * 负缓存命中判定（同 [ReplaceImageStore] 口径）：远程文件指纹仍等于
     * 失败时指纹才命中。已变（App 侧绑定自愈重投 / 换视频覆盖）即逐出
     * 重试——恢复不依赖配置 reload
     */
    private fun isNegativelyCached(videoId: String): Boolean {
        val failedFp = failed[videoId] ?: return false
        if (fingerprintOf(videoId) == failedFp) return true
        failed.remove(videoId)
        return false
    }

    /** 远程文件指纹（size 高 32 位 + mtime 秒低 32 位；stat 失败/缺失 0） */
    private fun fingerprintOf(videoId: String): Long = runCatching {
        HookContext.openRemoteVideo(remoteName(videoId))?.use { pfd ->
            val st: StructStat = Os.fstat(pfd.fileDescriptor)
            (st.st_size shl 32) or (st.st_mtime and 0xFFFFFFFFL)
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * 配置 reload：清负缓存、逐出已移除的缓存条目、后台预热全局视频
     * （最常见的录屏替换源；daemon 一次性线程，空闲即死）
     */
    private fun reload() {
        failed.clear()
        val ids = HookContext.activeVideoIds()
        synchronized(lock) {
            if (cachedId != null && ids.isNotEmpty() && cachedId !in ids) {
                runCatching { cached?.let { Os.close(it.fd) } }
                cached = null
                cachedId = null
            }
        }
        if (ids.isEmpty()) return
        Thread({
            val gid = HookContext.globalVideoId() ?: ids.firstOrNull() ?: return@Thread
            runCatching { playableFd(gid)?.first?.let { Os.close(it) } }
        }, "sf-vid-warm").apply { isDaemon = true }.start()
    }

    /**
     * 单 id 全链路：远程密文 → memfd。指纹在解密完成后取（文件在解密
     * 中途被覆写的极端竞态：存新指纹，下次访问指纹不符自然逐出重解，
     * 方向安全）。吞吐打点分读侧/总耗时（见 [VideoEnvelope.decryptTo]）
     */
    private fun loadEntry(videoId: String): Entry? = runCatching {
        val pfd = HookContext.openRemoteVideo(remoteName(videoId)) ?: run {
            HookContext.log(Log.WARN, "E3b video remote file absent for $videoId")
            return null
        }
        val fd = memfdCreateFn?.invoke("sf_e3b_vid") ?: run {
            HookContext.log(Log.WARN, "E3b memfd_create unavailable for $videoId")
            return null
        }
        val out = CountingFileOutputStream(fd)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val stats = pfd.use { raw ->
            VideoEnvelope.decryptTo(ParcelFileDescriptor.AutoCloseInputStream(raw), out)
        }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        if (!stats.ok) {
            runCatching { Os.close(fd) }
            HookContext.log(Log.WARN, "E3b video decrypt failed for $videoId")
            return null
        }
        val total = stats.bytes * 1000.0 / ms.coerceAtLeast(1) / 1024 / 1024
        val readMs = stats.readNanos / 1_000_000.0
        val read = if (readMs >= 1.0) stats.bytes * 1000.0 / readMs / 1024 / 1024 else -1.0
        HookContext.log(
            Log.INFO,
            "E3b video decrypted ${stats.bytes} bytes in ${ms}ms (total %.1f MB/s, read %.1f MB/s) for $videoId"
                .format(total, read)
        )
        Entry(fd, stats.bytes, fingerprintOf(videoId))
    }.onFailure {
        HookContext.log(Log.WARN, "E3b video load failed for $videoId: ${it.message}")
    }.getOrNull()

    // ==================== memfd（双候选反射，版本容错） ====================

    /** MFD_CLOEXEC（内核 UAPI 稳常量，OsConstants.MFD_CLOEXEC API 30 未必可见） */
    private const val MFD_CLOEXEC = 1

    /**
     * memfd_create 解析（首次调用解析一次）：
     * - 候选 1：android.system.Os.memfd_create（新平台公开化；运行时
     *   API 35 及以下多缺失 → 反射 NoSuchMethod 失败落候选 2）
     * - 候选 2：libcore.io.Linux 静态 os/INSTANCE 实例（boot classpath，
     *   Android 10+ 存在；system_server 反射无 hidden API 限制）
     * 全缺失 → null（视频替换不可用，E3b fail-open 回落静态图）
     */
    private val memfdCreateFn: ((String) -> FileDescriptor)? by lazy {
        // 绑定器：避免裸 lambda 紧随反射调用表达式被解析为尾随实参
        // （Kotlin 语法陷阱：call(args)\n{...} 合法但语义错位）
        fun bind(target: Any?, m: Method): (String) -> FileDescriptor =
            { name -> m.invoke(target, name, MFD_CLOEXEC) as FileDescriptor }
        val direct = runCatching {
            bind(
                null,
                Os::class.java.getMethod(
                    "memfd_create", String::class.java, Int::class.javaPrimitiveType
                )
            )
        }.getOrNull()
        direct ?: runCatching {
            val linux = Class.forName("libcore.io.Linux")
            val os = listOf("os", "INSTANCE").asSequence()
                .mapNotNull { f -> runCatching { linux.getDeclaredField(f).get(null) }.getOrNull() }
                .firstOrNull()
            val m = os?.javaClass?.getMethod(
                "memfd_create", String::class.java, Int::class.javaPrimitiveType
            )
            if (os == null || m == null) null else bind(os, m)
        }.getOrNull()
    }

    /** FileOutputStream + 长度计数（诊断辅助；会话长度以 stats 为准） */
    private class CountingFileOutputStream(fd: FileDescriptor) : OutputStream() {
        private val out = FileOutputStream(fd)
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        // 不实现 close：FileOutputStream.close 会关底层 memfd fd——fd 归
        // 缓存生命周期管理（Android FileDescriptor 无 finalizer 自动回收，
        // 丢弃包装对象不泄漏）
    }
}
