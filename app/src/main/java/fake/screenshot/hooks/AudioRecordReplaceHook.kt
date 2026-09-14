package fake.screenshot.hooks

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Collections
import java.util.WeakHashMap

/**
 * E3c 录屏音频替换引擎（OEM 录屏器进程内，AudioRecord 数据出口拦截）。
 *
 * 威胁模型：录屏会话的画面替换（E3b）与音频采集是两条独立管线。音频
 * 是录屏替换的声音部分（无独立"音频替换"功能）——仅录屏视频替换
 * 命中时按三态处理（见下）。
 *
 * 架构定位（scope 约束下的两腿分工）：
 * - 本引擎（进程内腿）：OEM 录屏器是系统应用，可被 LSPosed scope——
 *   在其进程内拦截 AudioRecord 的数据出口。第三方录屏 app 无法 scope
 *   （模块代码不在其进程内运行），其音频兜底走 system_server 策略腿
 *   （后续阶段：MediaProjection 音频捕获授权降级 → 原生语义静音）
 *
 * 机制（登记 + 数据出口替换）：
 * - 真机实证（2026-09-13 ColorOS 15）：ColorOS 录屏器走 C++ AudioRecord
 *   （libaudioclient），Java AudioRecord 类从未实例化——Java 层钩子全天
 *   零触发，替换主路径在 native 腿（[AudioRecordNativeBridge] →
 *   audio_replace.cpp：processAudioBuffer C 回调拦截 + 私有 obtainBuffer
 *   汇聚点，post-call 内容替换）。Java 层机制保留，覆盖系统内其他
 *   Java 采集路径（AOSP SystemUI 录屏等）
 * - read 腿懒登记设计（对"Java 实例存在但构造/启动腿被 OEM AOT 内联
 *   绕过"的场景兜底；native 方法不可内联，JNI 调用必经 entry point）：
 *   native_read 首次见到未登记实例 → lazyRegister 判定（激进度/策略快照）
 * - startRecording 腿（提前登记 + 清缓存重判）：能触发则策略快照更贴
 *   会话边界；被内联绕过无妨（read 兜底）。stop 腿清状态（复用实例
 *   的时钟重置与策略刷新）。均配套 deoptimize
 * - 数据出口 hook 4 个 private native `native_read_in_*`（byte/short/float
 *   数组 + direct buffer）：Java read() 全部重载的最终汇聚点——一处
 *   拦截覆盖所有读取形态（含 MediaCodec 输入 ByteBuffer 直喂）。readMode
 *   形参为 boolean isBlocking（真机 dump 实证）。native 方法无字节码、
 *   不可被 JIT/AOT 内联——唯一无需去优化的腿
 * - **去优化铁律**：Java 层 hook（startRecording/stop）必须配套
 *   deoptimize——OEM 应用 AOT 编译会把小方法内联进自身代码，内联调用
 *   点绕过 hook trampoline。注意 deoptimize 不能消除调用方已内联副本
 *   ——所以才有 read 懒登记兜底这条主路径
 *
 * 替换语义（登记时快照——read 懒登记或 startRecording 提前登记，对齐
 * E3b 会话锁定；会话内配置变更不追踪，复用实例下一会话 stop→read 重判）：
 * - 策略解析（recordAudioPolicy 三态）从属于录屏替换：仅录屏视频替换
 *   命中（前台者模板视频 / 全局视频开启且已配置）时生效，否则一律原声：
 *   - OFF（原声）：不登记 = 原生放行（fail-open）
 *   - REPLACE（替换）：登记假音频替换（[ReplaceAudioStore] PCM 供给；
 *     id 落空回落静音——显式选择替换后放行真实音频 = 泄漏）
 *   - MIX（叠加）：登记混合——真实数据 + 假音频相加（id 落空回落
 *     原声：叠加语义本就保留原声）
 *
 * 虚拟时钟 pacing（仅 REPLACE）：真实 AudioRecord 按采样率产数据（录屏
 * 器 read 循环的节奏被数据生产速率约束）；替换层若即时返回全量，循环
 * 空转（CPU 飙升 / 虚拟时长超速 = 替换特征）。模型：登记时刻起虚拟产出
 * 线性增长（bytesPerSecond = 采样率×帧字节），read 消费不得超前——
 * BLOCKING 模式 sleep 差值补齐，NON_BLOCKING 模式按可用量返回（可为 0，
 * 原生允许部分读取）。MIX 无需 pacing——数据即真实的（chain.proceed
 * 原生节奏），混合不改变量与时序
 *
 * fail-safe 全链：填充失败（read-only buffer 等极端态）返回 0 静默——
 * 绝不 fail-open 到原生 read（真实音频泄漏）；未登记实例原生放行。
 */
object AudioRecordReplaceHook {

    /** 冷启动配置竞态的有界等待上限（对齐 E3a 实测口径 ~1.25s 推送 + 余量） */
    private const val COLD_CONFIG_WAIT_MS = 2000L

    /** AudioRecord.READ_BLOCKING（AOSP 稳定值） */
    private const val READ_BLOCKING = 0

    /** AudioRecord.READ_NONBLOCKING（AOSP 稳定值） */
    private const val READ_NONBLOCKING = 1

    /** BLOCKING 等待的单次 sleep 上限（虚拟时钟追平等待的节拍粒度） */
    private const val PACE_SLEEP_MAX_MS = 120L

    /** BLOCKING 等待的总松弛量（生产模型线性，全额可得时刻 + 此余量即封顶） */
    private const val PACE_SLACK_MS = 250L

    /** 已登记采集实例：实例 → 替换会话（WeakHashMap 随实例回收自愈） */
    private val records = Collections.synchronizedMap(WeakHashMap<Any, RecState>())

    /**
     * 采集实例的替换会话。REPLACE 的虚拟时钟模型：clockStart 起产出线性
     * 增长，consumedBytes 记累计消费——可用量 = produced - consumed（负值
     * 钳 0，消费永不超前）。MIX 无时钟（真实数据原生节奏，只改内容）
     */
    private class RecState(
        val policy: Int,           // HookConfig.AUDIO_REPLACE / AUDIO_MIX
        val videoId: String?,      // 声音源 = 替换视频音轨；null = 数据落空（REPLACE→静音 / MIX→原声）
        val sampleRate: Int,
        val channels: Int,
        val clockStart: Long,
        val bytesPerSecond: Long,
        @Volatile var consumedBytes: Long = 0L,
        @Volatile var positionFrames: Long = 0L,  // 假音频帧位（跟随已产帧数）
        var logged: Boolean = false,
    )

    fun installRecorderApp(packageName: String, classLoader: ClassLoader) {
        // ---- 进程过滤（对齐 E3a 模式：scope 按包授权，子进程全量进入）----
        // 录屏管线进程：进程名含 record（com.oplus.screenrecorder 主进程/
        // :record 子进程、com.android.systemui:screenrecord）或宿主包主进程
        // （AOSP SystemUI 录屏在主进程）
        val processName = Application.getProcessName()
        val isRecordProcess = processName.contains("record", ignoreCase = true) ||
                processName == packageName
        if (!isRecordProcess) {
            HookContext.log(Log.INFO, "E3c skipped for non-record process $processName")
            return
        }

        // 登记激进度（真机实证 2026-09-13：录屏器 AudioRecord 实例跨会话
        // 复用 + 构造路径不落枚举。专用录屏进程内的任何 AudioRecord 都
        // 属于录屏音频管线，startRecording 即登记；systemui 等宿主进程
        // 仅 REMOTE_SUBMIX——语音助手热词等录音不可误伤）
        val aggressive = HookContext.kind == HookContext.ProcessKind.RECORDER_APP

        var startHooked = 0
        var readHooked = 0

        // ---- 腿 1：startRecording（登记点——会话边界）----
        // 复用实例每次会话必调；新构造实例同样必经。登记 = 策略快照 +
        // 虚拟时钟重置，天然逐会话刷新。strict 宿主用公开 getter
        // getAudioSource() 过滤 REMOTE_SUBMIX，无反射依赖
        runCatching {
            AudioRecord::class.java.declaredMethods
                .filter { it.name == "startRecording" }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E3c", m).intercept { chain ->
                        val r = chain.proceed()
                        runCatching {
                            val rec = chain.thisObject as? AudioRecord ?: return@runCatching
                            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                                (aggressive || rec.audioSource == MediaRecorder.AudioSource.REMOTE_SUBMIX)
                            ) {
                                // 新会话：清懒登记缓存重判（策略可能已变更）
                                ignored.remove(rec)
                                register(rec, instanceBps(rec))
                            }
                        }
                        r
                    }
                    // 去优化：startRecording 是小方法（inline 热门）——不去
                    // 优化则 OEM AOT 内联调用点绕过 hook（真机实证根因）
                    HookContext.deoptimize(m)
                    startHooked++
                }
        }.onFailure { HookContext.log(Log.WARN, "E3c start leg error: ${it.message}") }

        // ---- 腿 1b：stop（会话边界收尾——复用实例状态清理）----
        // 实证录屏器 AudioRecord 实例跨会话复用：旧 RecState 残留会使
        // 虚拟时钟跨会话累计（静音轨长于实际录制）且策略不刷新。stop
        // 时清 records/ignored → 下一会话 read 懒登记重判（时钟重置）
        runCatching {
            AudioRecord::class.java.declaredMethods
                .filter { it.name == "stop" && it.parameterTypes.isEmpty() }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E3c", m).intercept { chain ->
                        val r = chain.proceed()
                        runCatching {
                            records.remove(chain.thisObject)
                            ignored.remove(chain.thisObject)
                        }
                        r
                    }
                    HookContext.deoptimize(m)
                }
        }.onFailure { HookContext.log(Log.WARN, "E3c stop leg error: ${it.message}") }

        // ---- 腿 2：native_read_in_*（数据出口，Java read 全重载汇聚点）----
        // 失败路径必须可诊断：未识别布局 WARN（OEM 改名/改签名一次定位）
        AudioRecord::class.java.declaredMethods
            .filter { it.name.startsWith("native_read_in_") }
            .forEach { m ->
                runCatching {
                    val layout = readLayout(m.name, m.parameterTypes)
                    if (layout == null) {
                        HookContext.log(
                            Log.WARN,
                            "E3c unrecognized read layout: ${m.name}(${m.parameterTypes.joinToString { t -> t.simpleName }})"
                        )
                        return@runCatching
                    }
                    m.isAccessible = true
                    HookContext.hookE("E3c", m).intercept { chain ->
                        val rec = chain.thisObject
                        var st = records[rec]
                        if (st == null) {
                            // 懒登记兜底：native 方法不可内联（JNI 调用必经
                            // entry point），read 必然触发——Java 登记腿
                            // （startRecording）被 OEM AOT 内联绕过时由此
                            // 补位。miss 才走判定，热路径 O(1)
                            lazyRegister(rec)
                            st = records[rec] ?: return@intercept chain.proceed()
                        }
                        val requestUnits = chain.args.getOrNull(layout.sizeIdx) as? Int ?: 0
                        if (requestUnits <= 0 || st.bytesPerSecond <= 0L) {
                            return@intercept chain.proceed()
                        }
                        val mode = if (layout.modeIdx >= 0) {
                            // readMode 形参两种形态：Boolean isBlocking（AOSP/
                            // ColorOS 实测）或 Int readMode（防御其它 OEM）。
                            // 未知类型按阻塞语义（对 pacing 正确且保守）
                            when (val mv = chain.args.getOrNull(layout.modeIdx)) {
                                is Boolean -> if (mv) READ_BLOCKING else READ_NONBLOCKING
                                is Int -> mv
                                else -> READ_BLOCKING
                            }
                        } else READ_BLOCKING
                        val requestBytes = requestUnits.toLong() * layout.elemBytes

                        if (st.policy == HookConfig.AUDIO_MIX) {
                            // MIX：真实数据原生节奏（proceed），post-call 相加
                            // 假音频（落空回落原声）
                            val real = chain.proceed()
                            if (real is Int && real > 0) {
                                mixInto(chain.args.getOrNull(layout.dataIdx), st, real.toLong() * layout.elemBytes)
                            }
                            return@intercept real
                        }

                        // REPLACE：虚拟时钟 pacing + 假 PCM 覆写（落空静音）
                        val fill = availableBytes(st, mode, requestBytes)
                        if (fill <= 0L) return@intercept 0
                        val off = if (layout.offsetIdx >= 0) {
                            (chain.args.getOrNull(layout.offsetIdx) as? Int) ?: 0
                        } else -1
                        if (!fillReplace(chain.args.getOrNull(layout.dataIdx), off, fill, st)) {
                            // 填充失败（read-only buffer 等极端态）：静默 0——
                            // 绝不 fail-open 到原生 read（真实音频泄漏）
                            HookContext.log(Log.WARN, "E3c replace fill failed, zero returned")
                            return@intercept 0
                        }
                        st.consumedBytes += fill
                        if (!st.logged) {
                            st.logged = true
                            HookContext.log(
                                Log.INFO,
                                "E3c first read replaced (${fill}B, video=${st.videoId ?: "silence"}, ${st.bytesPerSecond}B/s)"
                            )
                        }
                        (fill / layout.elemBytes).toInt()
                    }
                    readHooked++
                }.onFailure {
                    HookContext.log(Log.WARN, "E3c read leg ${m.name} error: ${it.message}")
                }
            }

        // libaudioclient 加载监听：native 腿装配重试（见 startLibAudioClientWatch）
        startLibAudioClientWatch()

        // ---- E3c-N native 腿（C++ AudioRecord 回调路径，真机实证主路径）----
        // ShadowHook inline hook libaudioclient 私有 obtainBuffer（汇聚腿）
        // + processAudioBuffer C 回调拦截（主腿）。装配即尝试一次；
        // libaudioclient 尚未加载则加载监听见其映射后重试（native 侧按
        // 符号幂等）。仅录屏器专用进程——systemui 等宿主进程的普通
        // 录音不进入 native 腿
        if (HookContext.kind == HookContext.ProcessKind.RECORDER_APP) {
            // E3c 音频数据源仓库（REPLACE/MIX 的 PCM 供给）——配置 reload
            // 失效订阅 + 单槽缓存
            ReplaceAudioStore.ensureInstalled()
            AudioRecordNativeBridge.install()
        }

        HookContext.log(
            Log.INFO,
            "E3c installed (start=$startHooked, read=$readHooked, aggressive=$aggressive)"
        )
    }

    // ==================== libaudioclient 加载监听 ====================

    /** 监听单例守卫（模块热重载会重复进入 installRecorderApp） */
    @Volatile private var watchStarted = false

    /**
     * libaudioclient.so 加载监听 + native 腿装配重试。模块注入早于录屏器
     * 首次音频使用，libaudioclient 可能尚未加载——ShadowHook 的 pending
     * task 不会随库加载自动完成（dl 回调已随 linker init 失效），3s 轮询
     * /proc/self/maps，见其映射即重试 [AudioRecordNativeBridge.install]
     * （native 侧按符号幂等）并退出
     */
    private fun startLibAudioClientWatch() {
        if (HookContext.kind != HookContext.ProcessKind.RECORDER_APP) return
        if (watchStarted) return
        synchronized(this) {
            if (watchStarted) return
            watchStarted = true
        }
        Thread(
            {
                while (true) {
                    val loaded = runCatching {
                        java.io.File("/proc/self/maps").readLines()
                            .any { it.contains("libaudioclient.so") }
                    }.getOrDefault(false)
                    if (loaded) {
                        AudioRecordNativeBridge.install()
                        return@Thread
                    }
                    runCatching { Thread.sleep(3000) }
                }
            },
            "E3cLibWatch",
        ).apply { isDaemon = true }.start()
    }

    // ==================== 登记与策略快照 ====================

    /**
     * 懒登记缓存：已判定"不登记"的实例 → 判定时刻。TTL 后重判（策略
     * 变更后复用实例也能刷新——startRecording 腿被内联绕过时唯一刷新
     * 时机）。read 热路径只做 map 查询 + 时间比较
     */
    private val ignored = java.util.concurrent.ConcurrentHashMap<Any, Long>()

    /** 懒判定 TTL：OFF→MUTE/REPLACE 切换后最长 60s 生效（复用实例） */
    private val IGNORE_TTL_MS = 60_000L

    private val lazyLock = Any()

    /**
     * read 腿兜底登记（[records] miss 时）。strict 宿主的普通录音
     * （语音助手热词等）记入 ignored 放行；aggressive 进程全登记；
     * OFF 策略也记 ignored（TTL 重判），避免 read 每 20ms 重复判定
     */
    private fun lazyRegister(rec: Any) {
        val now = SystemClock.elapsedRealtime()
        ignored[rec]?.let { if (now - it < IGNORE_TTL_MS) return }
        if (records.containsKey(rec)) return
        synchronized(lazyLock) {
            if (records.containsKey(rec)) return
            // 双检 TTL（synchronized 期间可能已判）
            ignored[rec]?.let { if (now - it < IGNORE_TTL_MS) return }
            val r = rec as? AudioRecord ?: return
            val aggressive = HookContext.kind == HookContext.ProcessKind.RECORDER_APP
            if (!aggressive &&
                r.audioSource != MediaRecorder.AudioSource.REMOTE_SUBMIX
            ) {
                ignored[rec] = now
                return
            }
            val registered = register(r, instanceBps(r))
            if (!registered) ignored[rec] = now
        }
    }

    /**
     * 采集实例登记（startRecording 成功或 read 兜底触发，per-会话）。
     * 策略快照一次（会话锁定语义），[HookContext.recordAudioPolicy]
     * 三态——录屏替换的声音部分（仅录屏视频替换命中时非 OFF）。
     * REPLACE/MIX 数据源 = [ReplaceAudioStore]
     * （id 落空：REPLACE 回落静音 / MIX 回落原声）。
     * 冷启动护栏对齐 E3a：录屏器进程可能被 OEM 后台清理杀死，首录时配置
     * 未同步即判定 = 策略漏判，先有界等待。
     * @return true=已登记（REPLACE/MIX）；false=OFF 放行（调用方可缓存）
     */
    private fun register(rec: Any, bytesPerSecond: Long): Boolean {
        if (bytesPerSecond <= 0L) return false
        HookContext.awaitConfigSynced(COLD_CONFIG_WAIT_MS)
        val fg = HookContext.screenshotForegroundPackage()
        val policy = HookContext.recordAudioPolicy(fg)
        if (policy == HookConfig.AUDIO_OFF) {
            HookContext.log(
                Log.INFO,
                "E3c capture detected (fg=${fg ?: "?"}, policy=off, $bytesPerSecond B/s, passthrough)"
            )
            return false
        }
        val r = rec as? AudioRecord ?: return false
        val videoId = HookContext.recordVideoId(fg)
        records[rec] = RecState(
            policy = policy,
            videoId = videoId,
            sampleRate = r.sampleRate,
            channels = r.channelCount,
            clockStart = SystemClock.elapsedRealtime(),
            bytesPerSecond = bytesPerSecond,
        )
        HookContext.log(
            Log.INFO,
            "E3c registered capture (fg=${fg ?: "?"}, policy=${if (policy == HookConfig.AUDIO_REPLACE) "replace" else "mix"}${videoId?.let { ", video:$it" } ?: "(no video)"}, $bytesPerSecond B/s)"
        )
        return true
    }

    /**
     * 采集实例的产出速率（构造完成后从实例 getter 读请求配置——适用
     * 全部构造路径：Builder / int 老构造 / AudioAttributes+AudioFormat，
     * 免去按构造器形态分别解析参数）。异常（构造失败残留态等）返回 0
     * → register 拒绝登记，原生放行（fail-open 仅此一处，且实例此时
     * 无有效采集配置，read 不会产出数据）
     */
    private fun instanceBps(rec: Any): Long = runCatching {
        val r = rec as? AudioRecord ?: return 0L
        val bytes = when (r.audioFormat) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        r.sampleRate.toLong() * r.channelCount * bytes
    }.getOrDefault(0L)

    // ==================== 虚拟时钟与静音填充 ====================

    /**
     * 本次可填充字节数（虚拟时钟 pacing 核心）：
     * - NON_BLOCKING / 已足量：直接返回 min(可用, 请求)
     * - BLOCKING 不足额：sleep 差值循环至足额或超时上限（产出模型线性，
     *   足额绝对时刻可预知；上限 = 该时刻 + 松弛量——防御 bytesPerSecond
     *   解析异常导致的死等）。sleep 被中断（录屏器停止线程）即返回当前
     *   可用量
     */
    private fun availableBytes(st: RecState, mode: Int, requestBytes: Long): Long {
        fun produced(): Long =
            (SystemClock.elapsedRealtime() - st.clockStart) * st.bytesPerSecond / 1000

        var avail = (produced() - st.consumedBytes).coerceAtLeast(0L)
        if (mode != READ_BLOCKING || avail >= requestBytes) {
            return avail.coerceAtMost(requestBytes)
        }
        val deadline = SystemClock.elapsedRealtime() +
                (requestBytes - avail) * 1000 / st.bytesPerSecond + PACE_SLACK_MS
        while (avail < requestBytes) {
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) break
            val waitMs = ((requestBytes - avail) * 1000 / st.bytesPerSecond + 5)
                .coerceIn(1L, PACE_SLEEP_MAX_MS)
                .coerceAtMost(deadline - now)
            if (runCatching { Thread.sleep(waitMs) }.isFailure) break
            avail = (produced() - st.consumedBytes).coerceAtLeast(0L)
        }
        return avail.coerceAtMost(requestBytes)
    }

    // ==================== 假音频供给与填充（REPLACE 覆写 / MIX 相加） ====================

    /** PCM 拉取复用缓冲（[ReplaceAudioStore.fill] 输出；按需扩容） */
    private var pcmBuf: ByteArray = ByteArray(8192)

    /**
     * 拉取假音频 PCM（I16 交错小端，[bytesRequested] 字节对齐帧边界）。
     * 数据落空返回 null；成功更新 [RecState.positionFrames]
     */
    private fun pullPcm(st: RecState, bytesRequested: Long): ByteArray? {
        val videoId = st.videoId ?: return null
        val frameBytes = st.channels * 2
        if (frameBytes <= 0) return null
        val frames = (bytesRequested / frameBytes).toInt()
        if (frames <= 0) return null
        if (pcmBuf.size < frames * frameBytes) {
            pcmBuf = ByteArray(frames * frameBytes)
        }
        val n = runCatching {
            ReplaceAudioStore.fill(videoId, st.positionFrames, frames, st.sampleRate, st.channels, pcmBuf)
        }.getOrDefault(-1)
        if (n <= 0) return null
        st.positionFrames += frames
        return pcmBuf
    }

    /**
     * REPLACE 填充：假 PCM 覆写（数据落空 → 静音零值——显式选择替换后
     * 放行真实音频 = 泄漏）。容器形态分支（byte/short/float 数组 +
     * direct ByteBuffer；ByteBuffer 绝对索引写，不动 position——Java
     * 包装层 read(ByteBuffer) 在 native 返回后自行前移，此处前移 =
     * 双倍偏移）
     */
    private fun fillReplace(data: Any?, offset: Int, bytes: Long, st: RecState): Boolean {
        val pcm = pullPcm(st, bytes)
        if (pcm == null) return fillSilence(data, offset, bytes)
        return when (data) {
            is ByteArray -> {
                val units = (bytes / 1).toInt().coerceAtMost(pcm.size)
                if (offset >= 0 && offset + units <= data.size) {
                    System.arraycopy(pcm, 0, data, offset, units); true
                } else false
            }
            is ShortArray -> {
                val units = (bytes / 2).toInt().coerceAtMost(pcm.size / 2)
                if (offset >= 0 && offset + units <= data.size) {
                    for (i in 0 until units) {
                        data[offset + i] = ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
                    }
                    true
                } else false
            }
            is FloatArray -> {
                // 假音频 I16 → float（÷32768 归一）
                val units = (bytes / 4).toInt().coerceAtMost(pcm.size / 2)
                if (offset >= 0 && offset + units <= data.size) {
                    for (i in 0 until units) {
                        val v = ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
                        data[offset + i] = v / 32768f
                    }
                    true
                } else false
            }
            is ByteBuffer -> runCatching {
                if (data.isReadOnly || bytes <= 0L || data.remaining() < bytes) {
                    return@runCatching false
                }
                val units = bytes.toInt().coerceAtMost(pcm.size)
                val pos = data.position()
                for (i in 0 until units) data.put(pos + i, pcm[i])
                true
            }.getOrDefault(false)
            else -> false
        }
    }

    /**
     * MIX 混合：真实数据 + 假音频相加（clamp 防削波；数据落空不动 =
     * 原声）。I16 相加按 short；float 相加后 clamp ±1
     */
    private fun mixInto(data: Any?, st: RecState, bytes: Long) {
        val pcm = pullPcm(st, bytes) ?: return
        when (data) {
            is ByteArray -> {
                val units = (bytes / 1).toInt().coerceAtMost(pcm.size)
                val samples = units / 2 * 2  // 偶数字节化（I16 样本边界）
                for (i in 0 until samples step 2) {
                    val a = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
                    val b = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
                    val v = (a + b).toInt().coerceIn(-32768, 32767)
                    data[i] = (v and 0xFF).toByte()
                    data[i + 1] = ((v shr 8) and 0xFF).toByte()
                }
            }
            is ShortArray -> {
                val units = (bytes / 2).toInt().coerceAtMost(pcm.size / 2)
                for (i in 0 until units) {
                    val b = ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
                    data[i] = (data[i] + b).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            is FloatArray -> {
                val units = (bytes / 4).toInt().coerceAtMost(pcm.size / 2)
                for (i in 0 until units) {
                    val b = (((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()) / 32768f
                    data[i] = (data[i] + b).coerceIn(-1f, 1f)
                }
            }
            is ByteBuffer -> runCatching {
                if (data.isReadOnly || bytes <= 0L) return@runCatching
                val units = bytes.toInt().coerceAtMost(pcm.size)
                val samples = units / 2 * 2  // 偶数字节化（I16 样本边界）
                val pos = data.position()
                for (i in 0 until samples step 2) {
                    val a = ((data.get(pos + i).toInt() and 0xFF) or (data.get(pos + i + 1).toInt() shl 8)).toShort()
                    val b = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
                    val v = (a + b).toInt().coerceIn(-32768, 32767)
                    data.put(pos + i, (v and 0xFF).toByte())
                    data.put(pos + i + 1, ((v shr 8) and 0xFF).toByte())
                }
            }.getOrDefault(Unit)
            else -> Unit
        }
    }

    /**
     * 静音填充（PCM 静音 = 全零：16bit 0x0000 / float 0.0 / 8bit 0x80
     * ——8bit 为无符号偏置编码，静音中点是 128，此处填 0 是极端负值，
     * 但 8bit 采集罕见且偏置差异不可闻，统一零值实现）。
     * ByteBuffer 腿：绝对索引写（position 起写 units 字节，不动 position
     * ——Java 包装层 read(ByteBuffer) 在 native 返回后自行前移 position，
     * 此处前移 = 双倍偏移）
     */
    private fun fillSilence(data: Any?, offset: Int, bytes: Long): Boolean {
        val elemBytes = when (data) {
            is ShortArray -> 2L
            is FloatArray -> 4L
            else -> 1L
        }
        val units = (bytes / elemBytes).toInt()
        return when (data) {
            is ByteArray ->
                if (offset >= 0 && units >= 0 && offset + units <= data.size) {
                    Arrays.fill(data, offset, offset + units, 0.toByte()); true
                } else false
            is ShortArray ->
                if (offset >= 0 && units >= 0 && offset + units <= data.size) {
                    Arrays.fill(data, offset, offset + units, 0.toShort()); true
                } else false
            is FloatArray ->
                if (offset >= 0 && units >= 0 && offset + units <= data.size) {
                    Arrays.fill(data, offset, offset + units, 0f); true
                } else false
            is ByteBuffer -> runCatching {
                if (data.isReadOnly || units <= 0 || data.remaining() < units) {
                    return@runCatching false
                }
                val pos = data.position()
                for (i in 0 until units) data.put(pos + i, 0.toByte())
                true
            }.getOrDefault(false)
            else -> false
        }
    }

    // ==================== native_read 参数布局解析 ====================

    /** native_read_* 参数布局（AOSP 稳定签名，运行时按名/类型解析） */
    private class ReadLayout(
        val dataIdx: Int,
        val offsetIdx: Int,  // -1 = 无（direct buffer 腿）
        val sizeIdx: Int,
        val modeIdx: Int,    // -1 = 无（防御：无 readMode 按阻塞语义）
        val elemBytes: Int,  // size 参数的单位字节（数组腿元素宽，buffer 腿 1）
    )

    /**
     * 按方法名 + 形参类型解析布局。真机实测签名（2026-09-13 ColorOS 15
     * dump，与 AOSP 一致）：
     * - 数组腿 (byte[]/short[]/float[] audioData, int offsetInBytes,
     *   int sizeInBytes, boolean isBlocking)
     * - buffer 腿 (Object audioBuffer, int sizeInBytes, boolean isBlocking)
     * readMode 形参是 **boolean**（true=BLOCKING）而非 int——初版按 int
     * 布局解析导致 read=0（真机实证根因）。防御其它 OEM 的 int 形态。
     * 结构不符返回 null → 该方法不 hook（其余腿不受影响）
     */
    private fun readLayout(name: String, types: Array<Class<*>>): ReadLayout? {
        // 首参必须是数据容器（数组/Object），排除 int/boolean 形参误判
        val first = types.firstOrNull() ?: return null
        if (first == Int::class.javaPrimitiveType ||
            first == Boolean::class.javaPrimitiveType
        ) return null
        val intIdx = (1 until types.size)
            .filter { types[it] == Int::class.javaPrimitiveType }
        val boolIdx = (1 until types.size)
            .filter { types[it] == Boolean::class.javaPrimitiveType }
        val elemBytes = when {
            name.contains("byte") -> 1
            name.contains("short") -> 2
            name.contains("float") -> 4
            name.contains("direct") -> 1
            else -> return null
        }
        // readMode 位置：优先 boolean isBlocking；无 boolean 时退 int 末位
        // （防御非标形态）；都无 → -1（hook 侧按阻塞语义）
        val modeIdx = when {
            boolIdx.isNotEmpty() -> boolIdx[0]
            name.contains("direct") -> intIdx.getOrElse(1) { -1 }
            else -> intIdx.getOrElse(2) { -1 }
        }
        return if (name.contains("direct")) {
            // (buffer, sizeInBytes, isBlocking)
            if (intIdx.size < 1) null
            else ReadLayout(0, -1, intIdx[0], modeIdx, elemBytes)
        } else {
            // (array, offset, size, isBlocking)
            if (intIdx.size < 2) null
            else ReadLayout(0, intIdx[0], intIdx[1], modeIdx, elemBytes)
        }
    }
}
