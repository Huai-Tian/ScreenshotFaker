package fake.screenshot.hooks

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.StructStat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * E3c 录屏替换声音仓库（hook 进程侧）。
 *
 * **声音源 = 替换视频自带的音轨**（录屏替换三态：原声 / 替换 / 叠加——
 * "替换"即用用户导入的替换视频的声音顶替录屏声音，无独立音频导入）。
 * 数据源与 [ReplaceVideoStore] 同一远程密文（sf_vid_\<id\>），本仓库
 * 把它解密 + MediaExtractor 选音轨 + MediaCodec 解码为 **PCM I16 交错
 * 全量内存**（音频需要可随机访问的 PCM——每 20ms 按位取一段，不是
 * 播放器流），单槽缓存 + 指纹感知失效 + 负缓存（口径同图/视频）。
 *
 * 落空语义（视频无音轨 / 超过 PCM 上限 / 解码失败）：负缓存 → 调用方
 * 按策略回落（REPLACE 静音，MIX 原声）。
 *
 * 数据供给 [fill]：目标流（dstRate/dstCh）按 positionFrames 取帧——线性
 * 插值重采样 + 声道映射 + 循环（音轨短于录屏时长时循环，与画面侧视频
 * 循环同语义）。positionFrames 语义为**目标流的帧位**（消费方累计已填
 * 帧数，天然跟随真实管线节奏，无独立虚拟时钟）。
 */
object ReplaceAudioStore {

    /** 解码 PCM 上限（内存护栏；超限 = 落空，调用方按策略回落） */
    private const val MAX_PCM_BYTES = 64L * 1024 * 1024

    /** 已解码条目：PCM I16 交错 + 源流参数（键 = 替换视频 id） */
    private class Entry(
        val pcm: ShortArray,
        val srcRate: Int,
        val srcCh: Int,
        val fingerprint: Long,
    )

    /** 负缓存：视频 id → 失败指纹（reload 重置） */
    private val failed = ConcurrentHashMap<String, Long>()

    /** 单槽正缓存 */
    private val lock = Any()
    private var cached: Entry? = null
    private var cachedId: String? = null

    @Volatile
    private var installed = false

    /** 引擎装配入口（幂等）：订阅配置 reload（失效清理） */
    fun ensureInstalled() {
        if (installed) return
        installed = true
        HookContext.addConfigReloadListener {
            failed.clear()
            val ids = HookContext.activeAudioIds()
            synchronized(lock) {
                if (cachedId != null && ids.isNotEmpty() && cachedId !in ids) {
                    cached = null
                    cachedId = null
                }
            }
        }
    }

    /**
     * 数据就绪查询（REPLACE/MIX 落空判定）：未加载则同步加载（首录
     * 一次性；成功驻缓存）。失败（含视频无音轨）负缓存返回 false
     */
    fun ensureAvailable(videoId: String): Boolean {
        synchronized(lock) {
            if (cachedId == videoId && cached != null) return true
        }
        if (isNegativelyCached(videoId)) return false
        return loadEntry(videoId) != null
    }

    /**
     * PCM 供给：目标流帧位 [positionFrames] 起 [frames] 帧，写入 [out]
     * （I16 交错小端，容量 ≥ frames×dstCh×2，调用方保证）。返回写入帧数；
     * 数据未就绪 -1（调用方按策略回落）。
     *
     * 重采样：源帧位定点计算（低精度线性插值，20ms 粒度误差不可闻）；
     * 声道映射：等数直拷 / 少→多复制 / 多→少取前 N 声道；循环：取模
     */
    fun fill(videoId: String, positionFrames: Long, frames: Int, dstRate: Int, dstCh: Int, out: ByteArray): Int {
        var e = synchronized(lock) { if (cachedId == videoId) cached else null }
        if (e == null) {
            if (!ensureAvailable(videoId)) return -1
            e = synchronized(lock) { if (cachedId == videoId) cached else null } ?: return -1
        }
        if (frames <= 0 || dstRate <= 0 || dstCh <= 0 || e.pcm.isEmpty()) return -1

        val srcTotal = e.pcm.size / e.srcCh
        var outOff = 0
        for (i in 0 until frames) {
            // 源帧位 = (positionFrames + i) × srcRate / dstRate，定点插值
            val srcPosRaw = (positionFrames + i) * e.srcRate
            val srcFrame = Math.floorDiv(srcPosRaw, dstRate.toLong()).toInt()
            val fracNum = (srcPosRaw % dstRate).toInt()  // 小数分子（÷dstRate）
            val f0 = Math.floorMod(srcFrame, srcTotal)
            val f1 = (f0 + 1) % srcTotal
            val f0Base = f0 * e.srcCh
            val f1Base = f1 * e.srcCh
            for (c in 0 until dstCh) {
                val sc = if (e.srcCh >= dstCh) c else 0
                val s0 = e.pcm[f0Base + sc]
                val s1 = e.pcm[f1Base + sc]
                val v = s0 + ((s1 - s0) * fracNum / dstRate)
                out[outOff++] = (v.toInt() and 0xFF).toByte()
                out[outOff++] = ((v.toInt() shr 8) and 0xFF).toByte()
            }
        }
        return frames
    }

    // ==================== 加载与解码 ====================

    private fun fingerprintOf(videoId: String): Long = runCatching {
        HookContext.openRemoteVideo(ReplaceVideoStore.remoteName(videoId))?.use { pfd ->
            val st: StructStat = Os.fstat(pfd.fileDescriptor)
            (st.st_size shl 32) or (st.st_mtime and 0xFFFFFFFFL)
        } ?: 0L
    }.getOrDefault(0L)

    private fun isNegativelyCached(videoId: String): Boolean {
        val failedFp = failed[videoId] ?: return false
        if (fingerprintOf(videoId) == failedFp) return true
        failed.remove(videoId)
        return false
    }

    private fun loadEntry(videoId: String): Entry? {
        synchronized(lock) {
            if (cachedId == videoId && cached != null) return cached
        }
        val fp = fingerprintOf(videoId)
        val entry = runCatching { decodeEntry(videoId) }
            .onFailure { HookContext.log(Log.WARN, "E3c audio load failed for video $videoId: ${it.message}") }
            .getOrNull()
        if (entry == null) {
            failed[videoId] = fp
            return null
        }
        synchronized(lock) {
            cached = entry
            cachedId = videoId
        }
        HookContext.log(
            Log.INFO,
            "E3c video audio track decoded ${entry.pcm.size * 2} bytes (${entry.srcRate}Hz x${entry.srcCh}ch) for $videoId"
        )
        return entry
    }

    /** 解密（密文 → 容器字节）+ 解码（MediaExtractor 选音轨/MediaCodec → I16） */
    private fun decodeEntry(videoId: String): Entry {
        val pfd = HookContext.openRemoteVideo(ReplaceVideoStore.remoteName(videoId))
            ?: run {
                HookContext.log(Log.WARN, "E3c video remote file absent for $videoId")
                error("remote absent")
            }
        val container = pfd.use { raw ->
            val out = ByteArrayOutputStream()
            val stats = VideoEnvelope.decryptTo(ParcelFileDescriptor.AutoCloseInputStream(raw), out)
            if (!stats.ok) error("decrypt failed")
            out.toByteArray()
        }
        // MediaExtractor 需可寻址数据源——落进程内临时文件（系统应用
        // filesDir/cache 可写），解码完即删
        val tmp = File.createTempFile("sf_aud", null)
        try {
            tmp.writeBytes(container)
            return decodePcm(tmp, videoId)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** MediaExtractor + MediaCodec 全量解码视频音轨为 I16 交错 short[]（无音轨 = 落空） */
    private fun decodePcm(src: File, videoId: String): Entry {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(src.absolutePath)
            val trackIdx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                // 替换视频无音轨：常态而非异常（录屏素材常无声）——
                // INFO 级，回落语义由调用方按策略处理
                HookContext.log(Log.INFO, "E3c replacement video $videoId has no audio track")
                error("no audio track")
            }
            extractor.selectTrack(trackIdx)
            val fmt = extractor.getTrackFormat(trackIdx)
            val srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: error("no mime")

            val codec = MediaCodec.createDecoderByType(mime)
            val pcm = java.util.ArrayList<Short>(1024 * 1024)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = codec.getInputBuffer(inIdx)!!
                            val sz = extractor.readSampleData(ib, 0)
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* codec.outputFormat 即时读 */ }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> {
                            if (outIdx >= 0) {
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                                val ob = codec.getOutputBuffer(outIdx)
                                if (ob != null && info.size > 0) {
                                    val encoding = runCatching {
                                        codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                                    appendPcm(ob, info, encoding, pcm)
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                            }
                        }
                    }
                    if (inputDone && pcm.size * 2L > MAX_PCM_BYTES) error("pcm overflow cap")
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            if (pcm.isEmpty()) error("empty decode")
            val arr = ShortArray(pcm.size)
            for (i in arr.indices) arr[i] = pcm[i]
            HookContext.log(Log.INFO, "E3c audio track: $mime ${srcRate}Hz x$srcCh, ${arr.size} samples")
            return Entry(arr, srcRate, srcCh, fingerprintOf(videoId))
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** 解码输出（按 encoding）→ 追加 I16 样本（float ÷32768 归一） */
    private fun appendPcm(buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, encoding: Int, out: java.util.ArrayList<Short>) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                while (fb.hasRemaining()) {
                    val v = fb.get().coerceIn(-1f, 1f)
                    out.add((v * 32767f).toInt().toShort())
                }
            }
            else -> {  // I16（audio/raw 标准；8/24/32bit 罕见，按 16bit 保守读
                val sb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (sb.hasRemaining()) out.add(sb.get())
            }
        }
    }
}
