package fake.screenshot.hooks

import android.util.Log
import java.io.File

/**
 * E3c-N Kotlin 桥：native 层（[libmediafx.so]）与策略层（[HookContext]）的
 * 接缝。真机实证（2026-09-13 xsvh5o/41zaym，ColorOS 15）：OEM 录屏器走
 * C++ AudioRecord（libaudioclient），Java AudioRecord 类从未实例化——
 * Java 层 hook 全天零触发。本桥把替换下沉到 native：ShadowHook inline
 * hook `android::AudioRecord::obtainBuffer`（read/回调/OBTAIN 三路数据
 * 出口汇聚点，AOSP android15-release 源码验证），post-call 覆写
 * [raw, raw+mSize) —— 真实管线原样运转、产出节奏完全原生（Java 层阻断式
 * 替换的虚拟时钟 pacing 问题在 native post-call 模型下天然消失）。
 *
 * 职责分工：
 * - native（audio_replace.cpp）：hook 装配（幂等，库迟到由探针重试驱动）、
 *   会话表（懒登记 + TTL 60s 重判 + stop 清理）、Buffer 合理性防御、
 *   静音覆写热路径（无 JNI）
 * - 本桥（低频上调，仅在会话懒登记/TTL 时发生）：
 *   - [queryPolicy]：三态策略解析（[HookContext.recordAudioPolicy]，独立
 *     于画面替换配置——音频与视频替换解耦铁律；前台锚定经
 *     [HookContext.screenshotForegroundPackage]，依赖 E1 getTasks 白名单
 *     对录屏器包的放行）
 *   - [onLog]：日志经 [HookContext.log] 落 LSPosed 模块日志（root 可见）
 *
 * 加载时序：libaudioclient.so 可能晚于模块装配（应用启动早期）——
 * install 由两处驱动：installRecorderApp 装配尾 + native 路径探针见到
 * 目标 so 首次映射时（[AudioRecordReplaceHook.startNativePathProbe]）。
 * native 侧按符号幂等（已在位的 hook 跳过），重复调用无副作用。
 *
 * 热重载：nativeInstall 每次刷新桥实例全局引用（旧 classloader 的
 * HookContext 已注销 listener——若沿用旧实例，策略读取将停留在旧配置）。
 */
object AudioRecordNativeBridge {

    /**
     * 装配 native hook（幂等）。失败不抛出——库未加载/符号缺失均记录
     * 日志后静默返回（探针重试兜底；符号缺失则 read 诊断腿打点定位）。
     *
     * so 加载双腿（真机实证 2026-09-13 gzvwnh：LspModuleClassLoader 于
     * base.apk!/lib/arm64-v8a 找不到压缩存储的 libmediafx.so，单腿必败）：
     * - 腿1 [System.loadLibrary]：模块 classloader 的 APK 内嵌直载路径，
     *   仅支持未压缩（page-aligned）存储的 so——so 打包方式改为
     *   useLegacyPackaging=false 时此腿直通
     * - 腿2 [System.load]（nativeLibraryDir）：安装时解压的 so 目录
     *   （useLegacyPackaging=true，/data/app/.../lib/<abi>，跨进程可读），
     *   当前打包形态下的主路径
     *
     * 重复调用安全：同 classloader 内 Runtime 对已加载库路径幂等（探针
     * 重试与装配尾双驱动无半加载状态）。
     *
     * @return true = 主 hook（私有 obtainBuffer）已在位
     */
    fun install(): Boolean {
        val direct = runCatching {
            System.loadLibrary("mediafx")
            nativeInstall()
        }
        if (direct.getOrDefault(false)) return true

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
        }.getOrDefault(false)
    }

    private external fun nativeInstall(): Boolean

    // ==================== native 上调（AudioRecord 采集线程，低频）====================

    /**
     * 三态策略解析（会话懒登记时上调）。冷启动护栏对齐 Java 层 E3c：
     * 录屏器进程被 OEM 后台清理后首录，配置可能未同步——先有界等待。
     * 返回值契约见 audio_replace.cpp（0=OFF 1=MUTE 2=REPLACE；异常 fail-open）
     */
    private fun queryPolicy(): Int {
        HookContext.awaitConfigSynced(2000L)
        val fg = HookContext.screenshotForegroundPackage()
        return when (HookContext.recordAudioPolicy(fg)) {
            HookConfig.AUDIO_MUTE -> 1
            HookConfig.AUDIO_REPLACE -> 2
            else -> 0
        }
    }

    /** native 日志落 LSPosed 模块日志（priority 为 android.util.Log 常量） */
    private fun onLog(priority: Int, msg: String) {
        HookContext.log(priority, msg)
    }
}
