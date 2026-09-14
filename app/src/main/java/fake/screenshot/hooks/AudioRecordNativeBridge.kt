package fake.screenshot.hooks

import android.util.Log
import java.io.File

/**
 * E3c-N Kotlin 桥：native 层（[libmediafx.so]）与策略层（[HookContext]）的
 * 接缝。真机实证（2026-09-13，ColorOS 15）：OEM 录屏器走 C++ AudioRecord
 * （libaudioclient）TRANSFER_CALLBACK 模式，Java AudioRecord 类从未
 * 实例化——Java 层 hook 全天零触发。本桥把替换下沉到 native：主腿
 * processAudioBuffer C 回调拦截（EVENT_MORE_DATA pre-call 三态填充），
 * 汇聚腿私有 obtainBuffer post-call 覆写 [raw, raw+mSize)——真实管线
 * 原样运转、产出节奏完全原生（Java 层阻断式替换的虚拟时钟 pacing
 * 问题在 native 模型下天然消失）。
 *
 * 职责分工：
 * - native（audio_replace.cpp）：hook 装配（幂等，库迟到由加载监听重试）、
 *   会话表（懒登记 + TTL 60s 重判 + stop 清理）、Buffer 合理性防御、
 *   三态覆写热路径（REPLACE=假 PCM 覆写 / MIX=假 PCM 与原数据相加，
 *   PCM 数据经 [fillPcm] 上调拉取——CamSwap pcm_bridge 蓝本）
 * - 本桥（低频上调，会话懒登记/PCM 拉取）：
 *   - [queryPolicy]：三态策略解析（[HookContext.recordAudioPolicy]，录屏
 *     替换的声音部分——仅录屏视频替换命中时生效；前台锚定经
 *     [HookContext.screenshotForegroundPackage]）
 *   - [fillPcm]：native 按位拉取假音频 PCM（[ReplaceAudioStore] 供给，
 *     重采样/声道映射/循环在 Kotlin 侧完成；每 20ms 一次 JNI 上调）
 *   - [onLog]：日志经 [HookContext.log] 落 LSPosed 模块日志
 *
 * 加载时序：libaudioclient.so 可能晚于模块装配——install 由两处驱动：
 * installRecorderApp 装配尾 + libaudioclient 加载监听见其映射时。
 *
 * 热重载：nativeInstall 每次刷新桥实例全局引用。
 */
object AudioRecordNativeBridge {

    /** 本实例已成功加载过 native 库（幂等重入与热重载守卫的区分标记） */
    @Volatile
    private var loadedHere = false

    /**
     * 装配 native hook（幂等）。失败不抛出——库未加载/符号缺失均记录
     * 日志后静默返回（加载监听重试兜底）。
     *
     * **热重载双实例守卫**（iilmsg 实证 2026-09-13：模块 APK 更新后 LSPosed
     * 把新实例热注入运行中的录屏器进程——旧实例的 libmediafx.so（==deleted==
     * 路径）仍在位且其 hook 未摘除，新实例再 hook 同符号 = 两条独立
     * ShadowHook 链叠加（实证：stop caller 解析到 libmediafx.so 自身），
     * 数据路径被打断 → 产物静音）。检测到异路径 libmediafx.so 映射时
     * 本实例放弃安装并清除宿主进程（[evictStaleInstance]——杀进程，
     * 下次启动即为纯新实例单例态）。
     *
     * so 加载双腿（真机实证 2026-09-13 gzvwnh：LspModuleClassLoader 于
     * base.apk!/lib/arm64-v8a 找不到压缩存储的 libmediafx.so，单腿必败）：
     * - 腿1 [System.loadLibrary]：APK 内嵌直载路径（仅未压缩存储可用）
     * - 腿2 [System.load]（nativeLibraryDir）：安装时解压的 so 目录，主路径
     *
     * @return true = 主 hook（私有 obtainBuffer）已在位
     */
    fun install(): Boolean {
        if (!loadedHere && foreignMediaFxMapped()) {
            HookContext.log(
                Log.WARN,
                "E3c-N skip install: another libmediafx.so instance holds hooks (module hot-reloaded into live process)"
            )
            evictStaleInstance()
            return false
        }
        val direct = runCatching {
            System.loadLibrary("mediafx")
            nativeInstall()
        }
        if (direct.getOrDefault(false)) {
            loadedHere = true
            return true
        }

        val fallback = runCatching {
            val dir = HookContext.moduleApplicationInfo()?.nativeLibraryDir
            val so = dir?.let { File(it, "libmediafx.so") }
            if (so == null || !so.exists()) {
                throw UnsatisfiedLinkError("libmediafx.so not found under nativeLibraryDir=$dir")
            }
            System.load(so.absolutePath)
            nativeInstall()
        }
        return fallback.onFailure {
            HookContext.log(
                Log.WARN,
                "E3c-N bridge unavailable (direct: ${direct.exceptionOrNull()?.message}; " +
                        "fallback: ${it.message})"
            )
        }.map {
            if (it) loadedHere = true
            it
        }.getOrDefault(false)
    }

    private external fun nativeInstall(): Boolean

    /**
     * 陈旧实例清除（cgj1um 轮定案 2026-09-14）：模块热更新后旧实例的
     * native hook（ShadowHook 链）与 Java hook 无法从新实例摘除，旧实例
     * 继续以冻结/陈旧的配置干预数据路径——连续五轮真机测试（13:40 →
     * 15:13）录屏器进程从未重启，新 native 代码从未运行，排障被陈旧
     * 实例噪声污染。用户侧"更新后强停录屏器"的纪律实际不可依赖，
     * 改为模块自动执行等价操作：检测到异路径 libmediafx.so 即在短延迟
     * （日志落盘 + LSPosed 装配收尾）后杀死宿主进程，下次启动即为
     * 纯新实例单例态。
     *
     * 仅 RECORDER_APP 进程会走到 [install]，system_server 无此路径；
     * 模块更新瞬间若正在录制，录制会被中断（该场景下行为本已被
     * 热重载破坏，可接受）。杀进程失败 fallback exitProcess。
     */
    @Volatile
    private var evictScheduled = false

    private fun evictStaleInstance() {
        if (evictScheduled) return
        synchronized(this) {
            if (evictScheduled) return
            evictScheduled = true
        }
        Thread(
            {
                runCatching { Thread.sleep(2000) }
                HookContext.log(
                    Log.WARN,
                    "E3c-N killing recorder process to evict stale instance (auto force-stop; next launch runs current code only)"
                )
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
                runCatching { Runtime.getRuntime().exit(0) }
                kotlin.system.exitProcess(0)
            },
            "E3cStaleEvict",
        ).apply { isDaemon = true }.start()
    }

    /**
     * 异路径 libmediafx.so 映射检测（热重载双实例守卫）：maps 中存在
     * 非**本 APK nativeLibraryDir** 路径的 libmediafx.so（旧 APK 残留，
     * 含 ==deleted== 形态）= 旧实例仍持有 hook。本实例 own 路径的映射
     * 不算（本实例重复 install 的幂等重入）
     */
    private fun foreignMediaFxMapped(): Boolean = runCatching {
        val ownDir = HookContext.moduleApplicationInfo()?.nativeLibraryDir
        java.io.File("/proc/self/maps").readLines().any { l ->
            val so = SO_PATH.find(l)?.value ?: return@any false
            so.endsWith("/libmediafx.so") && (ownDir == null || !so.startsWith("$ownDir/"))
        }
    }.getOrDefault(false)

    /** maps 行中的 so 绝对路径 */
    private val SO_PATH = Regex("/\\S+\\.so")

    // ==================== native 上调（AudioRecord 采集线程）====================

    /**
     * 三态策略解析（会话懒登记时上调）。冷启动护栏对齐 Java 层 E3c。
     * 返回值契约（audio_replace.cpp POLICY_*）：0=原声 1=替换 2=叠加；
     * 异常 fail-open（0）
     */
    private fun queryPolicy(): Int {
        HookContext.awaitConfigSynced(2000L)
        val fg = HookContext.screenshotForegroundPackage()
        return when (HookContext.recordAudioPolicy(fg)) {
            HookConfig.AUDIO_REPLACE -> 1
            HookConfig.AUDIO_MIX -> 2
            else -> 0
        }
    }

    /**
     * 替换视频 id（native 会话懒登记时上调，随策略一并快照）——声音源
     * = 该视频自带音轨。null = 未配置（REPLACE 回落静音 / MIX 回落原声；
     * 策略非 OFF 时视频必已命中，null 仅防御）
     */
    private fun queryVideoId(): String? {
        val fg = HookContext.screenshotForegroundPackage()
        return HookContext.recordVideoId(fg)
    }

    /**
     * 假音频 PCM 拉取（native 每次 fill 时上调，~每 20ms 一次）：
     * [positionFrames] = 会话内目标流帧位（native 累计，跟随真实管线
     * 节奏）；[out] = I16 交错小端输出缓冲。返回写入帧数；-1 = 数据
     * 未就绪（调用方按策略回落）
     */
    private fun fillPcm(positionFrames: Long, frames: Int, sampleRate: Int, channels: Int, out: ByteArray): Int {
        if (frames <= 0 || out.size < frames * channels * 2) return -1
        val videoId = nativeVideoId ?: return -1
        return runCatching {
            ReplaceAudioStore.fill(videoId, positionFrames, frames, sampleRate, channels, out)
        }.onFailure {
            HookContext.log(Log.WARN, "E3c-N fillPcm failed: ${it.message}")
        }.getOrDefault(-1)
    }

    /** native 侧当前会话的视频 id 快照（nativeInstall 时由 native 注册回调刷新；
     *  简化路径：queryPolicy 同期经 [noteVideoId] 登记） */
    @Volatile
    private var nativeVideoId: String? = null

    /** native 会话懒登记时的视频 id 快照登记（与 queryPolicy 同一时刻调用） */
    private fun noteVideoId() {
        nativeVideoId = queryVideoId()
    }

    /** native 日志落 LSPosed 模块日志（priority 为 android.util.Log 常量） */
    private fun onLog(priority: Int, msg: String) {
        HookContext.log(priority, msg)
    }
}
