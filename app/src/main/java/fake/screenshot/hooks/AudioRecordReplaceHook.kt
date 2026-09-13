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
 * 采集本身可能是敏感通道（环境声/通话声落录屏），也可能是录屏可信度
 * 的佐证（真实环境声 + 假画面反而更难被识破）——取舍交给用户：音频
 * 策略与画面替换配置完全解耦，独立三态选择（见下）。
 *
 * 架构定位（scope 约束下的两腿分工）：
 * - 本引擎（进程内腿）：OEM 录屏器是系统应用，可被 LSPosed scope——
 *   在其进程内拦截 AudioRecord 的数据出口。第三方录屏 app 无法 scope
 *   （模块代码不在其进程内运行），其音频兜底走 system_server 策略腿
 *   （后续阶段：MediaProjection 音频捕获授权降级 → 原生语义静音）
 *
 * 机制（登记 + 数据出口替换）：
 * - **⚠ 真机终局实证（2026-09-13 xsvh5o，ColorOS 15）：Java 层全灭**。
 *   16:12 会话带 AAC 音轨（产物 A:1 V:1）、audioserver AudioBoost 全程
 *   SCHED boost 录屏器 pid 的 3 条采集线程（tid 14083/14084/14141，
 *   kWhatRemoveActiveAudioRecord 收尾）——但 12 个 Java 钩子（build/
 *   ctor×5/start×2/read×4）全会话零触发。native read 腿不可内联
 *   （触发即必然），零触发只有一个解释：**Java AudioRecord 类从未
 *   实例化——录屏器走 C++ AudioRecord（libaudioclient）。Java 层
 *   机制保留（系统内其他 Java 采集路径 + 第三方 MediaProjection app
 *   的未来覆盖）+ native 路径探针（startNativePathProbe，41zaym 轮已
 *   实锤 libaudioclient.so 加载与 AudioRecord 采集线程）→ 替换主路径
 *   下沉至 E3c-N native 腿（[AudioRecordNativeBridge] → audio_replace.cpp，
 *   ShadowHook inline hook 私有 obtainBuffer 三路汇聚点，post-call
 *   内容替换）
 * - read 腿懒登记设计仍然正确（对"Java 实例存在但构造/启动腿被 OEM
 *   AOT 内联绕过"的场景兜底；native 方法不可内联，JNI 调用必经
 *   entry point）：native_read 首次见到未登记实例 → lazyRegister 判定
 *   （激进度/策略快照）。真机实证（2026-09-13 ColorOS 15）：deoptimize
 *   后 startRecording hook 仍零触发（ART deoptimize 只保证被 hook 方法
 *   走解释器，调用方 odex 内联副本不受影响）
 * - startRecording 腿（提前登记 + 清缓存重判）：能触发则策略快照更贴
 *   会话边界；被内联绕过无妨（read 兜底）。stop 腿清状态（复用实例
 *   的时钟重置与策略刷新）。均配套 deoptimize
 * - 构造器/Builder hook 仅诊断（构造路径打点，限流）——实证录屏器构造
 *   路径不落 REMOTE_SUBMIX int 构造器（AudioAttributes 形态），枚举
 *   构造路径做登记不可靠
 * - 数据出口 hook 4 个 private native `native_read_in_*`（byte/short/float
 *   数组 + direct buffer）：Java read() 全部重载的最终汇聚点——一处
 *   拦截覆盖所有读取形态（含 MediaCodec 输入 ByteBuffer 直喂）。readMode
 *   形参为 boolean isBlocking（真机 dump 实证）。native 方法无字节码、
 *   不可被 JIT/AOT 内联——唯一无需去优化的腿
 * - **去优化铁律**：Java 层 hook（构造器/build/startRecording/stop）
 *   必须配套 deoptimize——OEM 应用 AOT 编译会把小方法内联进自身代码，
 *   内联调用点绕过 hook trampoline（真机实证 2026-09-13：12 句柄装配
 *   成功但全天零回调，方法体日志照常出现；代码库 E1/E2c/E2d/E4 同样
 *   全部去优化）。注意 deoptimize 不能消除调用方已内联副本——所以才
 *   有 read 懒登记兜底这条主路径
 *
 * 替换语义（登记时快照——read 懒登记或 startRecording 提前登记，对齐
 * E3b 会话锁定；会话内配置变更不追踪，复用实例下一会话 stop→read 重判）：
 * - 策略独立解析（recordAudioPolicy 三态），**不从画面替换配置派生**
 *   ——"音频替换"与"仅视频图像替换"是用户的独立选择，任意组合合法
 *   （画面替换 + 真实音频原样保留同为合法组合：环境声反而增强录屏
 *   可信度）：
 *   - OFF：不登记 = 原生放行（fail-open）
 *   - MUTE：登记静音
 *   - REPLACE：登记假音频（本阶段静音占位，数据源管线 Phase 1 落地；
 *     id 落空回落静音——显式选择替换后放行真实音频 = 泄漏）
 *
 * 虚拟时钟 pacing：真实 AudioRecord 按采样率产数据（录屏器 read 循环
 * 的节奏被数据生产速率约束）；替换层若即时返回全量，循环空转（CPU
 * 飙升 / 虚拟时长超速 = 替换特征）。模型：登记时刻起虚拟产出线性增长
 * （bytesPerSecond = 采样率×帧字节），read 消费不得超前——BLOCKING
 * 模式 sleep 差值补齐，NON_BLOCKING 模式按可用量返回（可为 0，原生
 * 允许部分读取）
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
     * 已打过"首次 native read"决定性诊断的实例（弱引用，随实例回收）。
     * 未登记实例首次 native_read 无条件打点（区分"read hook 没触发"与
     * "触发但被策略忽略"）——read 懒登记 miss 时每实例仅一条
     */
    private val readSeen: MutableSet<Any> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))

    /**
     * 采集实例的替换会话。虚拟时钟模型：clockStart 起产出线性增长，
     * consumedBytes 记累计消费——可用量 = produced - consumed（负值
     * 钳 0，消费永不超前）
     */
    private class RecState(
        val clockStart: Long,
        val bytesPerSecond: Long,
        val audioId: String?,
        @Volatile var consumedBytes: Long = 0L,
        var logged: Boolean = false,
    )

    /** 构造诊断日志限流（每进程每腿最多 N 条——录屏器冷启动 + 每次录屏各一条足够） */
    private val buildDiagCount = java.util.concurrent.atomic.AtomicInteger()
    private val ctorDiagCount = java.util.concurrent.atomic.AtomicInteger()

    private fun buildDiag(msg: String) {
        if (buildDiagCount.incrementAndGet() <= 4) HookContext.log(Log.INFO, "E3c $msg")
    }

    private fun ctorDiag(msg: String) {
        if (ctorDiagCount.incrementAndGet() <= 6) HookContext.log(Log.INFO, "E3c $msg")
    }

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

        var buildHooked = 0
        var ctorHooked = 0
        var startHooked = 0
        var readHooked = 0

        // ---- 腿 1：Builder#build（纯诊断：playback capture 构造路径打点）----
        runCatching {
            val builderClass = Class.forName("android.media.AudioRecord\$Builder")
            val buildM = builderClass.getDeclaredMethod("build").apply { isAccessible = true }
            val capField = runCatching {
                builderClass.getDeclaredField("mAudioPlaybackCaptureConfig")
                    .apply { isAccessible = true }
            }.getOrNull()
            HookContext.hookE("E3c", buildM).intercept { chain ->
                val rec = chain.proceed()
                runCatching {
                    val cfgSet = capField != null && capField.get(chain.thisObject) != null
                    buildDiag(
                        "build() called (capField=${if (capField != null) "resolved" else "missing"}, captureConfig=${if (cfgSet) "set" else "null"})"
                    )
                }
                rec
            }
            // 去优化：build() 是小方法，OEM AOT 易内联进调用方——内联调用
            // 点绕过 hook trampoline（真机实证全天零回调根因）
            HookContext.deoptimize(buildM)
            buildHooked++
        }.onFailure { HookContext.log(Log.WARN, "E3c builder leg error: ${it.message}") }

        // ---- 腿 2：构造器族（纯诊断：构造路径打点，限流）----
        // 实证录屏器构造路径不落 int 构造器（AudioAttributes 形态）——
        // 登记已移至 startRecording（会话边界），此处仅观测构造路径。
        // 构造器去优化（内联绕过根因，见腿 1 注释）
        runCatching {
            AudioRecord::class.java.declaredConstructors
                .forEach { c ->
                    c.isAccessible = true
                    val firstIsInt = c.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
                    HookContext.hookE("E3c", c).intercept { chain ->
                        val r = chain.proceed()
                        runCatching {
                            val a0 = chain.args.getOrNull(0)
                            ctorDiag(
                                "ctor(${c.parameterTypes.joinToString { t -> t.simpleName }}) first=${if (firstIsInt && a0 is Int) "audioSource=$a0" else a0?.javaClass?.simpleName ?: "null"}"
                            )
                        }
                        r
                    }
                    HookContext.deoptimize(c)
                    ctorHooked++
                }
        }.onFailure { HookContext.log(Log.WARN, "E3c ctor leg error: ${it.message}") }

        // ---- 腿 3：startRecording（登记点——会话边界）----
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

        // ---- 腿 3b：stop（会话边界收尾——复用实例状态清理）----
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

        // ---- 腿 4：native_read_in_*（数据出口，Java read 全重载汇聚点）----
        // 失败路径必须可诊断：未识别布局 WARN（不再静默跳过）+ read=0 时
        // 全量签名 dump（OEM 改名/改签名一次定位）
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
                            // 决定性诊断：每实例首次 native_read 无条件打点（不做
                            // 任何过滤）——区分"read hook 根本没触发"（OEM 走
                            // C++ 采集路径，Java AudioRecord 只是状态壳或不
                            // 存在）与"触发但 bps=0 被静默忽略"。下一轮日志
                            // 靠这一行定生死
                            if (readSeen.add(rec)) {
                                runCatching {
                                    val r = rec as? AudioRecord
                                    HookContext.log(
                                        Log.INFO,
                                        "E3c first native read on ${m.name} " +
                                                "(src=${r?.audioSource}, rate=${r?.sampleRate}, ch=${r?.channelCount}, " +
                                                "fmt=${r?.audioFormat}, state=${r?.recordingState})"
                                    )
                                }
                            }
                            // 懒登记兜底：native 方法不可内联（JNI 调用必经
                            // entry point），read 必然触发——Java 登记腿
                            // （构造器/startRecording）被 OEM AOT 内联绕过时
                            // 由此补位。miss 才走判定，热路径 O(1)
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
                        val fill = availableBytes(st, mode, requestBytes)
                        if (fill <= 0L) return@intercept 0
                        val off = if (layout.offsetIdx >= 0) {
                            (chain.args.getOrNull(layout.offsetIdx) as? Int) ?: 0
                        } else -1
                        if (!fillSilence(chain.args.getOrNull(layout.dataIdx), off, fill)) {
                            // 填充失败（read-only buffer 等极端态）：静默 0——
                            // 绝不 fail-open 到原生 read（真实音频泄漏）
                            HookContext.log(Log.WARN, "E3c silence fill failed, zero returned")
                            return@intercept 0
                        }
                        st.consumedBytes += fill
                        if (!st.logged) {
                            st.logged = true
                            HookContext.log(
                                Log.INFO,
                                "E3c first read replaced (${fill}B, audio=${st.audioId ?: "mute"}, ${st.bytesPerSecond}B/s)"
                            )
                        }
                        (fill / layout.elemBytes).toInt()
                    }
                    readHooked++
                }.onFailure {
                    HookContext.log(Log.WARN, "E3c read leg ${m.name} error: ${it.message}")
                }
            }

        // 诊断（read=0 时必触发）：AudioRecord 全部声明方法签名 dump——
        // OEM 改名/改签名一次定位。方法多但一次性、进程冷启动时打一次
        if (readHooked == 0) {
            runCatching {
                val dump = AudioRecord::class.java.declaredMethods
                    .sortedBy { it.name }
                    .joinToString("; ") {
                        "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})"
                    }
                HookContext.log(Log.WARN, "E3c read leg empty; AudioRecord methods: $dump")
            }
        }

        // native 采集路径探针：Java 层已实证零触发（C++ 采集路径，见
        // startNativePathProbe 文档）——采样音频 so 加载与采集线程名，
        // 为 native 层 hook 点设计（libaudioclient AudioRecord::read /
        // libaaudio AAudioStream_read / 录屏器自研 so）提供一轮实证
        startNativePathProbe()

        // ---- E3c-N native 腿（C++ AudioRecord 路径，41zaym 实锤）----
        // ShadowHook inline hook libaudioclient 私有 obtainBuffer（read/
        // 回调/OBTAIN 三路数据出口汇聚点），post-call 内容替换。装配即
        // 尝试一次；libaudioclient 尚未加载则探针见其映射后重试（native
        // 侧按符号幂等）。仅录屏器专用进程——systemui 等宿主进程的普通
        // 录音不进入 native 腿
        if (HookContext.kind == HookContext.ProcessKind.RECORDER_APP) {
            AudioRecordNativeBridge.install()
        }

        HookContext.log(
            Log.INFO,
            "E3c installed (build=$buildHooked, ctor=$ctorHooked, start=$startHooked, read=$readHooked, aggressive=$aggressive)"
        )
    }

    // ==================== native 采集路径探针 ====================

    /** 探针单例守卫（模块热重载会重复进入 installRecorderApp） */
    @Volatile private var probeStarted = false

    /**
     * native 采集路径采样。真机实证（2026-09-13 xsvh5o）：录屏会话带
     * AAC 音轨、audioserver AudioBoost 全程 SCHED boost 录屏器进程的
     * 采集线程、native read 腿（不可内联，触发即必然）零触发——Java
     * AudioRecord 类从未实例化，录屏器走 C++ AudioRecord（libaudioclient）。
     * 转 native 层拦截前，3s 轮询记录两类增量（会话期间才加载的 so /
     * 才出现的采集线程，一次性冷启动 dump 拿不到）：
     * - /proc/self/maps 新增 so：录屏器自有 lib（/data/app/ 路径，可能
     *   自研采集引擎）+ 系统音频 lib（audioclient/aaudio/opensles/media）
     * - /proc/self/task/<tid>/comm 新增线程：音频关键词命名（AudioRecord/
     *   AAudio/采集循环）——揭示采集引擎与承载 so 的对应关系
     */
    private fun startNativePathProbe() {
        if (HookContext.kind != HookContext.ProcessKind.RECORDER_APP) return
        if (probeStarted) return
        synchronized(this) {
            if (probeStarted) return
            probeStarted = true
        }
        val seen = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        Thread(
            {
                while (true) {
                    runCatching {
                        java.io.File("/proc/self/maps").readLines().forEach { l ->
                            val so = SO_PATH.find(l)?.value ?: return@forEach
                            val name = so.substringAfterLast('/')
                            val interesting = so.contains("/data/app/") ||
                                    AUDIO_SO_HINTS.any { name.contains(it, true) }
                            if (interesting && seen.add(so)) {
                                HookContext.log(Log.INFO, "E3c native lib loaded: $so")
                                // E3c-N 装配重试：ShadowHook 解析符号需库在位——
                                // 装配时 libaudioclient/libaaudio 未加载的场景由
                                // 此补位（native 侧按符号幂等，重复调用无副作用）
                                if (name == "libaudioclient.so" || name == "libaaudio.so") {
                                    AudioRecordNativeBridge.install()
                                }
                            }
                        }
                        java.io.File("/proc/self/task").listFiles()?.forEach { t ->
                            runCatching {
                                val comm = java.io.File(t, "comm").readText().trim()
                                if (AUDIO_THREAD_HINTS.any { comm.contains(it, true) } &&
                                    seen.add("t:$comm")
                                ) {
                                    HookContext.log(Log.INFO, "E3c capture thread appeared: $comm")
                                }
                            }
                        }
                    }
                    runCatching { Thread.sleep(3000) }
                }
            },
            "E3cNativeProbe",
        ).apply { isDaemon = true }.start()
    }

    /** maps 行中的 so 绝对路径（版本后缀如 .so.1 不匹配——Android so 无此形态） */
    private val SO_PATH = Regex("/\\S+\\.so")

    /** 系统音频相关 so 名关键词（native hook 候选承载库） */
    private val AUDIO_SO_HINTS = listOf("audioclient", "aaudio", "opensles", "libmedia", "mediandk")

    /** 采集线程名关键词（C++ AudioRecord/AAudio/OpenSLES 及 OEM 命名习惯） */
    private val AUDIO_THREAD_HINTS = listOf(
        "audio", "aaudio", "sles", "pcm", "sound", "mic", "voice", "capture", "submix", "record"
    )

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
     * 策略快照一次（会话锁定语义），独立解析 [HookContext.recordAudioPolicy]
     * 三态——不从画面替换配置派生。REPLACE 落空（id 未配/悬空）回落静音。
     * 冷启动护栏对齐 E3a：录屏器进程可能被 OEM 后台清理杀死，首录时配置
     * 未同步即判定 = 策略漏判，先有界等待。
     * 探测日志与策略判定分离（真机验收可观测性）：OFF 也记录"拦到采集
     * 实例"——否则录到真音频时无法区分"腿没拦到实例"（换采集路径/
     * 字段名不符）与"策略未生效"（配置未同步），排查树断层。
     * @return true=已登记（MUTE/REPLACE）；false=OFF 放行（调用方可缓存）
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
        val audioId = if (policy == HookConfig.AUDIO_REPLACE) HookContext.recordAudioId(fg) else null
        records[rec] = RecState(
            clockStart = SystemClock.elapsedRealtime(),
            bytesPerSecond = bytesPerSecond,
            audioId = audioId,
        )
        HookContext.log(
            Log.INFO,
            "E3c registered capture (fg=${fg ?: "?"}, policy=${if (audioId != null) "replace:$audioId" else "mute"}, $bytesPerSecond B/s)"
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
