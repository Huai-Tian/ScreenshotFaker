package fake.screenshot

import android.util.Log
import android.util.Pair as AndroidPair
import fake.screenshot.hooks.CaptureDetectionHook
import fake.screenshot.hooks.FreeformPierceHook
import fake.screenshot.hooks.HookContext
import fake.screenshot.hooks.HookContext.ProcessKind
import fake.screenshot.hooks.OverlayStealthHook
import fake.screenshot.hooks.RecordDetectionHook
import fake.screenshot.hooks.SecurePolicyHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口：纯生命周期路由与热重载协调，不含任何引擎逻辑。
 * 引擎各自独立于 fake.screenshot.hooks 包（单文件单引擎），经 [HookContext]
 * 共享配置与工具，互相不引用。
 *
 * 进程分流（API 102 语义）：
 * - system_server：首个包回调被 onSystemServerStarting 替代（onPackageReady
 *   的 isFirstPackage 在 system_server 恒为 false）——E1/E2/E3b/E4 全部在此装配
 * - 截屏应用（scope.list 静态清单）：onPackageReady + isFirstPackage——E3a 在此装配
 */
class ScreenshotFaker : XposedModule() {
    companion object {
        private const val TAG = "SF"

        /**
         * 截屏应用白名单（scope 泛滥防御）。用户手动把无关应用加入 LSPosed
         * 作用域时静默忽略——不 hook、不弹窗（DFS 对未知包弹窗退出，我们
         * 的 scope 是静态预置的，不存在配置错误的用户路径）。
         */
        private val SCREENSHOT_PACKAGES = setOf(
            "com.android.systemui",
            "com.flyme.systemuiex",
            "com.miui.screenshot",
            "com.oplus.appplatform",
            "com.oplus.screenshot",
        )
    }

    /**
     * 热重载跨代状态（DFS 同款）：当前进程的 hook 装配参数。
     * 必须 android.util.Pair（boot classloader 类）：kotlin.Pair 由模块
     * 自身 classloader 加载（设备 BOOTCLASSPATH 无 kotlin-stdlib），框架
     * 的 setSavedInstanceState 检测器会抛 IllegalArgumentException 导致
     * 热重载失败（实测 ColorOS 15 / LSPosed 2.2.0）。ClassLoader 本体是
     * 框架对象，classloader-neutral，可安全跨代。
     */
    private var hookParam: AndroidPair<String, ClassLoader>? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(Log.INFO, TAG, "module loaded in ${param.processName}, system=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        hookParam = AndroidPair.create("system", param.classLoader)
        HookContext.init(this, ProcessKind.SYSTEM_SERVER)
        installHooks()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (!param.isFirstPackage) return
        if (param.packageName !in SCREENSHOT_PACKAGES) return
        hookParam = AndroidPair.create(param.packageName, param.classLoader)
        HookContext.init(this, ProcessKind.SCREENSHOT_APP)
        installHooks()
    }

    /**
     * 引擎挂载点：按进程装配（地基阶段仅验证链路，引擎在后续阶段逐个落地）。
     * 每个引擎独立 try-catch——单引擎失败不拖垮同进程其他引擎（对齐 DFS 的
     * 容错粒度）。
     */
    private fun installHooks() {
        val p = hookParam ?: return
        when (HookContext.kind) {
            ProcessKind.SYSTEM_SERVER -> {
                // E1 SecurePolicyHook      —— isSecureLocked 三态（被截者）
                runCatching { SecurePolicyHook.installSystemServer(p.second) }
                    .onFailure { HookContext.log(Log.ERROR, "E1 install failed", it) }
                // E4 FreeformPierceHook —— 被配置 app 自由小窗截图穿透（纯
                // system_server，skipScreenshot 双层标记，被穿透 app 零注入）
                runCatching { FreeformPierceHook.installSystemServer(p.second) }
                    .onFailure { HookContext.log(Log.ERROR, "E4 install failed", it) }
                // E2a CaptureDetectionHook —— 截屏感知吞噬：ScreenCaptureCallback
                // 注册/派发 + 媒体库 ContentObserver（检测者，视频子域兼护 E2b）
                runCatching { CaptureDetectionHook.installSystemServer(p.second) }
                    .onFailure { HookContext.log(Log.ERROR, "E2a install failed", it) }
                // E2b RecordDetectionHook —— 录屏感知吞噬：ScreenRecordingCallback
                // + 虚拟显示器存在性隐身（检测者）
                runCatching { RecordDetectionHook.installSystemServer(p.second) }
                    .onFailure { HookContext.log(Log.ERROR, "E2b install failed", it) }
                // E2d OverlayStealthHook —— 遮挡感知隐身：焦点丢失隐瞒 +
                // TrustedPresentation 注册点探测（检测者）
                runCatching { OverlayStealthHook.installSystemServer(p.second) }
                    .onFailure { HookContext.log(Log.ERROR, "E2d install failed", it) }
                // E3b ProjectionReplaceHook—— MediaProjection 虚拟屏假图层（全局）
            }
            ProcessKind.SCREENSHOT_APP -> {
                // E1 SecurePolicyHook（捕获管线放行腿，仅 ALLOW 态激活）
                runCatching { SecurePolicyHook.installScreenshotApp(p.first, p.second) }
                    .onFailure { HookContext.log(Log.ERROR, "E1 install failed for ${p.first}", it) }
                // E3a ScreenshotReplaceHook —— 截图族 API 拦截返回模板图（前台者选图）
            }
            ProcessKind.OTHER -> return
        }
        HookContext.log(Log.INFO, "hooks installed for ${p.first}")
    }

    /**
     * 热重载放行前的旧代清理。本模块在目标进程内不创建线程/native/外部回调
     * （体例铁律，见 HookContext），唯一外部触点是 RemotePreferences
     * listener——注销即达无残留标准，返回 true 放行。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        HookContext.prepareHotReload()
        param.setSavedInstanceState(hookParam)
        return true
    }

    /**
     * 新代替换（不调 super——默认实现会 unhook 全部旧句柄，我们采用 DFS 的
     * id 去重模式）：installHooks 以相同 id 重新安装（框架按 id 原子去重），
     * 旧句柄中 id 不在新集合的（新版已移除的 hook）解除。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        HookContext.resetForHotReload(this, param.isSystemServer)
        val saved = param.savedInstanceState
        if (saved is AndroidPair<*, *> && saved.first is String && saved.second is ClassLoader) {
            hookParam = AndroidPair.create(saved.first as String, saved.second as ClassLoader)
            installHooks()
        }
        param.oldHookHandles.forEach { h ->
            if (!HookContext.isHookInstalled(h.id)) h.unhook()
        }
        HookContext.log(Log.INFO, "hot reloaded, ${param.oldHookHandles.size} old handles")
    }
}
