package fake.screenshot.hooks

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.util.Log
import java.lang.reflect.Modifier

/**
 * E3a 截图内容替换引擎（截屏应用进程，被截内容假图替换）。
 *
 * 威胁模型：对端拿到设备后主动截屏取证（系统手势/截屏应用，最终产物
 * 是相册里的截图文件）。本引擎在截屏应用的保存管线内把画面替换为
 * 预置假图——截屏操作成功、保存文件可看，内容却是无敏感信息的替换图。
 *
 * 命中语义（HookContext.replacementImageId）：截图瞬间前台应用的
 * 显式模板图 > 全局图（开关开启且已配置）；无命中 → 原生截图。
 *
 * hook 矩阵（framework 层被 OEM 封装共同消费的捕获/转换点，全反射 +
 * 运行时探测 + CNFE 容错；替换命中记每图一次事件日志防高频刷屏）：
 * - 【Bitmap 直接产出腿】`SurfaceControl#screenshot` 静态族（返回
 *   Bitmap，A13- 主路径 + 部分 OEM A14+ 兼容路径）——E1 矩阵实证
 *   ColorOS 截屏应用的捕获链最终仍经 framework 静态 API 取画面
 * - 【HardwareBuffer→Bitmap 腿】`Bitmap#wrapHardwareBuffer` 静态
 *   （A14+ ScreenshotHardwareBuffer 消费端标准转换：SystemUI
 *   ImageExporter / OEM 保存管线）；假图以软件位图返回——下游
 *   compress/encode 完全兼容
 * - 【OEM buffer 腿】`ScreenshotHardwareBuffer#asBitmap`（AOSP 存在
 *   则 hook，ColorOS 15 的 OplusScreenCapture 若经此转换即命中）
 *
 * 前台解析：共用 [HookContext.screenshotForegroundPackage] 的
 * getRunningTasks 特权查询（E1 system_server 腿 getTasks 白名单放行，
 * 跳过截屏应用自己人）。解析失败 → null → 全局图回落
 * （replacementImageId(null)），再失败 fail-open。
 *
 * fail-open 全链：无策略 / 无图 / 解码失败 → 原生结果原样返回——
 * 替换功能绝不阻断截屏流程本身（截屏失败比真内容更可疑）。
 *
 * 冷启动竞态护栏（ColorOS 15 实测）：截屏进程夜间被 OEM 后台清理
 * （o-stop）杀死后，次日首截从进程拉起到捕获执行仅 ~0.4-0.8s，而
 * RemotePreferences 首推 ~1.25s 才到——策略未命中时先有界等待配置
 * （每进程至多一次），等待后仍无策略才 fail-open。无护栏时首截必以
 * DEFAULT 配置放行真内容落盘（见 replaceBitmap）。
 */
object ScreenshotReplaceHook {

    /** 每图一次的命中日志（替换发生即记，防高频刷屏） */
    private val hitLogged = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 冷启动配置竞态的有界等待上限（覆盖实测 ~1.25s 推送延迟 + 余量） */
    private const val COLD_CONFIG_WAIT_MS = 2000L

    fun installScreenshotApp(packageName: String, classLoader: ClassLoader) {
        // ---- 进程过滤（scope 泛滥防御第二轮）----
        // LSPosed 按包授权：scope 含 com.android.systemui（为其 :screenshot
        // 截屏子进程）时，主进程与 :tuner/:fgservices 等全部子进程都会进入
        // 本安装路径。但 ColorOS 15 实测 SystemUI 主进程的
        // SurfaceControl#screenshot 用于下拉状态栏"实时屏幕背景"——误装
        // 会把替换图真实显示在屏幕上（下拉背景变替换图）。
        // 判据：进程名含 "screenshot"（com.oplus.screenshot[/:activity]、
        // com.android.systemui:screenshot/:appclips.screenshot、
        // com.miui.screenshot 等截屏管线进程）或 Flyme 截屏载体
        // com.flyme.systemuiex。其余（SystemUI 主进程/UI 子进程/
        // com.oplus.appplatform——实测从未命中画面捕获）跳过。
        // 已知边界：AOSP 原生截屏在 SystemUI 主进程，此判据下不装 →
        // fail-open 原生截图（可见异常的代价高于功能缺失）
        val processName = Application.getProcessName()
        val isCaptureProcess =
            processName.contains("screenshot", ignoreCase = true) ||
                    processName == "com.flyme.systemuiex"
        if (!isCaptureProcess) {
            HookContext.log(Log.INFO, "E3a skipped for non-capture process $processName")
            return
        }

        ReplaceImageStore.ensureInstalled()

        var hooked = 0
        // 【腿 1】SurfaceControl#screenshot（返回 Bitmap 的静态重载族）
        runCatching {
            val scClass = Class.forName("android.view.SurfaceControl")
            scClass.declaredMethods
                .filter { it.name == "screenshot" && Modifier.isStatic(it.modifiers) && it.returnType == Bitmap::class.java }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E3a", m).intercept { chain ->
                        val native = chain.proceed() as? Bitmap ?: return@intercept null
                        replaceBitmap(native.width, native.height) ?: native
                    }
                    hooked++
                }
        }.onFailure { HookContext.log(Log.WARN, "E3a screenshot leg error: ${it.message}") }

        // 【腿 2】Bitmap#wrapHardwareBuffer（公开静态，A14+ 保存管线转换点）
        runCatching {
            val m = Bitmap::class.java.getDeclaredMethod(
                "wrapHardwareBuffer", HardwareBuffer::class.java, ColorSpace::class.java
            ).apply { isAccessible = true }
            HookContext.hookE("E3a", m).intercept { chain ->
                val native = chain.proceed() as? Bitmap ?: return@intercept null
                val hb = chain.args.getOrNull(0) as? HardwareBuffer ?: return@intercept native
                replaceBitmap(hb.width, hb.height) ?: native
            }
            hooked++
        }.onFailure { HookContext.log(Log.WARN, "E3a wrapHardwareBuffer leg error: ${it.message}") }

        // 【腿 3】ScreenshotHardwareBuffer#asBitmap（hidden，存在才 hook；
        // A13/A14+ 内部类宿主双候选）
        for (fqcn in listOf(
            "android.window.ScreenCapture\$ScreenshotHardwareBuffer",
            "android.view.SurfaceControl\$ScreenshotHardwareBuffer",
        )) {
            runCatching {
                val clazz = classLoader.loadClass(fqcn)
                clazz.declaredMethods
                    .filter { it.name == "asBitmap" && it.parameterCount == 0 && it.returnType == Bitmap::class.java }
                    .forEach { m ->
                        m.isAccessible = true
                        HookContext.hookE("E3a", m).intercept { chain ->
                            val native = chain.proceed() as? Bitmap ?: return@intercept null
                            replaceBitmap(native.width, native.height) ?: native
                        }
                        hooked++
                    }
            } // CNFE = 本 ROM 无此内部类形态，静默
        }

        HookContext.log(Log.INFO, "E3a installed ($hooked hook points)")
    }

    // ==================== 替换核心 ====================

    /**
     * 按前台命中取假图并缩放到原生结果尺寸；null = 不替换。
     * 热路径先查策略存在性（无锁快照，无配置直接原路返回——非截图
     * 场景误触 wrapHardwareBuffer 时零额外开销）
     *
     * 冷启动竞态护栏：策略未命中且配置未同步时，有界等待推送到达后
     * 重查（进程被杀后的首截捕获早于 RemotePreferences 首推，见类头）。
     * 每进程至多一次完整等待，超时后按 fail-open 放行——截屏流程
     * 本身绝不因等待被无限阻断
     */
    private fun replaceBitmap(targetW: Int, targetH: Int): Bitmap? {
        if (targetW <= 0 || targetH <= 0) return null
        if (!HookContext.hasReplacePolicy()) {
            if (!HookContext.awaitConfigSynced(COLD_CONFIG_WAIT_MS) ||
                !HookContext.hasReplacePolicy()
            ) return null
        }
        val imageId = HookContext.replacementImageId(foregroundPackage()) ?: return null
        val fake = ReplaceImageStore.bitmapFor(imageId) ?: return null
        if (hitLogged.add(imageId)) {
            HookContext.log(Log.INFO, "E3a replacing capture with image=$imageId (${targetW}x$targetH)")
        }
        return if (fake.width == targetW && fake.height == targetH) fake
        else Bitmap.createScaledBitmap(fake, targetW, targetH, true)
    }

    // ==================== 前台解析 ====================

    /**
     * 截屏瞬间的前台包名，共用 [HookContext.screenshotForegroundPackage]
     * 的 getRunningTasks 特权查询。解析失败 null
     * （replacementImageId(null) = 全局图回落）
     */
    private fun foregroundPackage(): String? = HookContext.screenshotForegroundPackage()
}
