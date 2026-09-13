package fake.screenshot.hooks

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
     *  update/write 三侧调用数与缓冲对齐） */
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
     * doFinal（GCM tag 在此校验，不符抛 AEADBadTagException → false）
     */
    fun decryptTo(envelope: InputStream, out: OutputStream): DecryptStats {
        val nonce = ByteArray(NONCE_LEN)
        if (!readFully(envelope, nonce)) return DecryptStats(false, 0L)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val buf = ByteArray(IO_BUFFER)
        var bytes = 0L
        return try {
            while (true) {
                val n = envelope.read(buf)
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
            DecryptStats(true, bytes)
        } catch (_: IOException) {
            DecryptStats(false, bytes)
        } catch (_: GeneralSecurityException) {
            // AEADBadTagException（tag 不符——历史截断残留/写半途中断）
            DecryptStats(false, bytes)
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

    /** 解密统计：ok + 明文字节数 */
    class DecryptStats(val ok: Boolean, val bytes: Long)
}

/**
 * hook 进程侧替换视频仓库（E3b system_server 消费）。
 *
 * 视频的"解码"是 MediaPlayer 的事，会话期由播放器持有解码线程；本仓库
 * 负责把远程密文信封流式解密到 **memfd**（匿名内存文件：可 seek、不落
 * 盘、内存页由内核 tmpfs 管理可回收），以**单槽正缓存**持有（实测解密
 * 吞吐受远程读路径制约——6.5MB 耗时 35s，逐会话重解密不可接受）：
 *
 * - 预热：配置同步/会话结束后台线程解密全局视频入缓存，并完成 player
 *   全装配（setDataSource + setters + prepareAsync——round 7 实测会话
 *   侧装配+解码首帧延迟使录屏开头暴露静态图约 0.5s，提前到空闲窗口
 *   消除）；scaling/loop/volume setter 必须在 prepare 前调用（OEM
 *   NuPlayer 在 prepare 时锁定 scaling mode，之后设置无效 = 视频拉伸）
 * - 会话：[takePlayable] 优先交付 prepared player（转移所有权，缓存
 *   清空——会话结束 re-warm），无 player 条目回退 dup 交付（会话侧
 *   自行装配，兼容 warm 未完成即开录的窗口）
 * - 失效：指纹感知（同图片口径）——远程文件变化（App 侧换视频/自愈重
 *   投）即逐出重解，不依赖配置 reload；负缓存同图片（坏视频/文件缺失
 *   /prepare 失败不逐会话重试 IO）
 */
object ReplaceVideoStore {

    /** 远程文件名约定（App 侧 ReplaceVideoManager 同步维护） */
    const val REMOTE_PREFIX = "sf_vid_"

    fun remoteName(videoId: String): String = "$REMOTE_PREFIX$videoId"

    /** 会话取用结果：player（prepared 快路径）与 fd（慢路径）二选一非空；
     *  firstFrame = warm 期 MMR 提取的视频第 0 帧（快路径独有，占位无缝）；
     *  rotation = 容器旋转元数据（中继 reader 定尺寸与 YUV 旋转换算用） */
    class TakeResult(
        val player: MediaPlayer?,
        val fd: FileDescriptor?,
        val length: Long,
        val firstFrame: Bitmap?,
        val rotation: Int
    )

    /**
     * 正缓存条目：已解密 memfd（fd 归缓存所有）+ 可选 prepared player
     * （其 MemfdDataSource 持独立 dup fd，release 即关）。[dispose] 统一
     * 释放（幂等性靠调用方单次调用约定）
     */
    private class Entry(
        val fd: FileDescriptor,
        val length: Long,
        val fingerprint: Long,
        val player: MediaPlayer?,
        val firstFrame: Bitmap? = null,
        val rotation: Int = 0
    ) {
        fun dispose() {
            runCatching { player?.release() }
            runCatching { Os.close(fd) }
        }
    }

    /** 负缓存：id → 失败时的远程文件指纹（reload 重置；指纹感知同图片） */
    private val failed = ConcurrentHashMap<String, Long>()

    /**
     * 单槽正缓存（[lock] 全局互斥）。单槽 = 内存上限锁定单视频明文
     * （App 侧 100MB 约束），不随模板数放大；prepared player 条目被
     * 会话取走即清空（所有权转移），会话结束由引擎触发 re-warm
     */
    private val lock = Any()
    private var cached: Entry? = null
    private var cachedId: String? = null

    @Volatile
    private var installed = false

    /** 首帧只读镜像（binder 线程零阻塞 peek 专用，round 17）：warm 成功
     *  写入（id → 视频 0 帧），reload 全清。与 [cached] 弱一致——不追踪
     *  消费（消费后同 id peek 显示的仍是该视频 0 帧，内容正确仅多持一
     *  张位图；换视频必经配置变更 → reload 清空，新 warm 完成前 peek
     *  落空 → 调用方画黑屏，无错内容风险）。读侧无锁——mirror 创建帧
     *  在 binder 线程（可能持 DMS 锁），不可阻塞等 warm 解密 */
    @Volatile
    private var peekFrame: Pair<String, Bitmap>? = null

    /** 非消费式首帧 peek：warm 命中同 id → 视频 0 帧（mirror 创建帧同步
     *  占位用，与中继帧 0 同内容无缝）；未命中 → null（调用方画黑屏） */
    fun peekFirstFrame(videoId: String): Bitmap? =
        peekFrame?.takeIf { it.first == videoId }?.second

    /** 引擎装配入口（幂等）：订阅配置 reload（失效清理 + 预热） */
    fun ensureInstalled() {
        if (installed) return
        installed = true
        HookContext.addConfigReloadListener { reload() }
        reload()
    }

    /**
     * 会话取用（所有权转移语义）：prepared player 命中 → 转移（缓存清
     * 空，re-warm 由会话结束触发）；无 player 条目 → dup 交付（缓存保
     * 留）。null = 任一环节失败（负缓存登记 + 分级 WARN），调用方
     * fail-open 回落静态图。fd/player 的 close 责任归调用方（player 的
     * release 触发其 MemfdDataSource.close 关自有 dup）
     */
    fun takePlayable(videoId: String): TakeResult? {
        if (isNegativelyCached(videoId)) {
            HookContext.log(Log.WARN, "E3b video negatively cached for $videoId (remote fingerprint unchanged)")
            return null
        }
        synchronized(lock) {
            if (isNegativelyCached(videoId)) return null
            val fp = fingerprintOf(videoId)
            cached?.takeIf { cachedId == videoId && it.fingerprint == fp }?.let { hit ->
                hit.player?.let { p ->
                    cached = null
                    cachedId = null
                    return TakeResult(p, null, hit.length, hit.firstFrame, hit.rotation)
                }
                dupOf(hit)?.let { return TakeResult(null, it, hit.length, null, hit.rotation) }
                // dup 失败（fd 耗尽极端态）：落重解路径（保守但可用）
            }
            val entry = loadEntry(videoId, withPlayer = false) ?: run {
                failed[videoId] = fp
                return null
            }
            runCatching { cached?.let { it.dispose() } }
            cached = entry
            cachedId = videoId
            dupOf(entry)?.let { return TakeResult(null, it, entry.length, null, entry.rotation) }
            // dup 失败：丢弃条目按失败处理（下次重试）
            entry.dispose()
            cached = null
            cachedId = null
            failed[videoId] = fp
            return null
        }
    }

    /** 会话结束后 re-warm（配置 reload 亦触发）：重建 prepared player 缓存 */
    fun rewarm() {
        warmAsync()
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
        peekFrame = null
        val ids = HookContext.activeVideoIds()
        synchronized(lock) {
            if (cachedId != null && ids.isNotEmpty() && cachedId !in ids) {
                runCatching { cached?.let { it.dispose() } }
                cached = null
                cachedId = null
            }
        }
        if (ids.isEmpty()) return
        warmAsync()
    }

    /**
     * 后台预热：全局视频解密 + player 全装配入缓存（已命中同 id 条目则
     * 跳过——re-warm 与 reload 的并发去重）。负缓存指纹感知（App 侧
     * 换视频自愈后自动重试）
     */
    private fun warmAsync() {
        val gid = HookContext.globalVideoId() ?: return
        Thread({
            if (isNegativelyCached(gid)) return@Thread
            synchronized(lock) {
                if (cachedId == gid && cached != null) return@Thread
                val fp = fingerprintOf(gid)
                val entry = loadEntry(gid, withPlayer = true) ?: run {
                    failed[gid] = fp
                    return@Thread
                }
                runCatching { cached?.let { it.dispose() } }
                cached = entry
                cachedId = gid
                entry.firstFrame?.let { peekFrame = gid to it }
                HookContext.log(Log.INFO, "E3b video warmed (prepared=${entry.player != null}) for $gid")
            }
        }, "sf-vid-warm").apply { isDaemon = true }.start()
    }

    /**
     * 单 id 全链路：远程密文 → memfd（+ 可选 prepared player）。指纹在
     * 解密完成后取（文件在解密中途被覆写的极端竞态：存新指纹，下次访问
     * 指纹不符自然逐出重解，方向安全）。withPlayer=true 时 prepare 失败
     * → 整条作废返回 null（MediaDataSource 形式下 prepare 失败基本 =
     * 内容坏，负缓存不逐会话重试）
     */
    private fun loadEntry(videoId: String, withPlayer: Boolean): Entry? = runCatching {
        val pfd = HookContext.openRemoteVideo(remoteName(videoId)) ?: run {
            HookContext.log(Log.WARN, "E3b video remote file absent for $videoId")
            return null
        }
        val fd = memfdCreateFn?.invoke("sf_e3b_vid") ?: run {
            HookContext.log(Log.WARN, "E3b memfd_create unavailable for $videoId")
            return null
        }
        val out = FileOutputStream(fd)
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
        HookContext.log(Log.INFO, "E3b video decrypted ${stats.bytes} bytes in ${ms}ms for $videoId")
        val player = if (withPlayer) buildPlayer(fd, stats.bytes, videoId) else null
        if (withPlayer && player == null) {
            runCatching { Os.close(fd) }
            return null
        }
        // 第 0 帧（warm 独有，无缝接管前导帧）+ 旋转元数据（中继 reader
        // 定尺寸与 YUV 旋转换算）。提取失败 fail-soft（帧 null / rot 0）
        val meta = extractMeta(fd, stats.bytes, videoId, wantFrame = withPlayer)
        Entry(fd, stats.bytes, fingerprintOf(videoId), player, meta.firstFrame, meta.rotation)
    }.onFailure {
        HookContext.log(Log.WARN, "E3b video load failed for $videoId: ${it.message}")
    }.getOrNull()

    /**
     * prepared player 装配（warm 阶段，会话建立时零装配零等待）：
     * setDataSource(MemfdDataSource, 独立 dup) → 全 setter（**必须在
     * prepare 前**——OEM NuPlayer prepare 时锁定 scaling mode，后设无
     * 效即视频拉伸）→ prepareAsync + latch。失败 release（dup fd 随
     * dataSource.close 关闭）
     */
    private fun buildPlayer(fd: FileDescriptor, length: Long, videoId: String): MediaPlayer? {
        return try {
            val dup = Os.dup(fd)
            val p = MediaPlayer()
            p.setDataSource(MemfdDataSource(dup, length))
            p.setLooping(true)
            // 静音铁律：替换视频音频外放 = 替换行为即刻暴露
            p.setVolume(0f, 0f)
            // 裁剪铺满 layer buffer（显示空间），无黑边；prepare 前调用
            p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            val latch = CountDownLatch(1)
            var ok = false
            p.setOnPreparedListener { _ ->
                ok = true
                latch.countDown()
            }
            p.setOnErrorListener { _, what, extra ->
                HookContext.log(Log.WARN, "E3b warm prepare error what=$what extra=$extra for $videoId")
                latch.countDown()
                true
            }
            p.prepareAsync()
            if (!latch.await(3, TimeUnit.SECONDS) || !ok) {
                runCatching { p.release() }
                HookContext.log(Log.WARN, "E3b warm prepare not ready in 3s for $videoId")
                null
            } else {
                HookContext.log(Log.INFO, "E3b warm player prepared ${p.videoWidth}x${p.videoHeight} for $videoId")
                p
            }
        } catch (e: Exception) {
            HookContext.log(Log.WARN, "E3b warm build failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 元数据提取结果：第 0 帧（可空）+ 容器旋转 */
    private class Meta(val firstFrame: Bitmap?, val rotation: Int)

    /**
     * 视频元数据提取（MMR，warm/加载期）：第 0 帧（占位无缝用）+ 旋转
     * 元数据（中继 reader 定尺寸与 YUV 旋转换算用）。MMR +
     * MemfdDataSource——与播放器同架构（读取经 binder 回调在本进程，
     * media.extractor 不触 fd，无 SELinux 跨域问题）。独立 dup fd，
     * MMR.release 后显式 dataSource.close（幂等——MMR 是否代关不确定，
     * 双保险）。失败返 (null, 0)（fail-soft）
     */
    private fun extractMeta(fd: FileDescriptor, length: Long, videoId: String, wantFrame: Boolean): Meta {
        var ds: MemfdDataSource? = null
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                ds = MemfdDataSource(Os.dup(fd), length)
                mmr.setDataSource(ds)
                val rot = runCatching {
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.trim()?.toIntOrNull() ?: 0
                }.getOrNull() ?: 0
                val frame = if (wantFrame) {
                    runCatching {
                        mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull()
                } else null
                HookContext.log(
                    Log.INFO,
                    "E3b meta for $videoId: first frame ${frame?.let { "${it.width}x${it.height}" } ?: "null"}, rotation=$rot"
                )
                Meta(frame, rot)
            } finally {
                runCatching { mmr.release() }
                ds?.close()
            }
        } catch (e: Exception) {
            HookContext.log(Log.WARN, "E3b meta extract failed: ${e.javaClass.simpleName}: ${e.message}")
            ds?.close()
            Meta(null, 0)
        }
    }

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
}

/**
 * memfd 数据源（E3b 数据源主形式，round 6 根因修复）：所有读取经 binder
 * 回调发生在持有 memfd 的 system_server 本进程（Os.pread 不动共享偏移，
 * 天然线程安全），mediaserver / media.extractor 从不直接触碰 fd——绕开
 * 跨域 memfd 读取的 SELinux 限制（创建域标签的文件不容 mediaserver
 * pread，fd 直传形式 setDataSource 通过但 prepare 秒败 0x80000000 且
 * 无 AVC 痕迹）。close 关闭自有 dup（player 释放数据源时回调；
 * setDataSource 失败路径由调用方关闭），幂等
 */
internal class MemfdDataSource(
    private val fd: FileDescriptor,
    private val length: Long
) : MediaDataSource() {
    @Volatile
    private var closed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (closed || position >= length) return -1
        val want = minOf(size.toLong(), length - position).toInt()
        return try {
            when (val n = Os.pread(fd, buffer, offset, want, position)) {
                0 -> -1
                else -> n
            }
        } catch (e: ErrnoException) {
            throw IOException(e.message ?: "pread errno ${e.errno}")
        }
    }

    override fun getSize(): Long = length

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { Os.close(fd) }
        }
    }
}
