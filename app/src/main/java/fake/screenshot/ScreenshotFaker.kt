package fake.screenshot

import android.util.Log
import fake.screenshot.hooks.HookContext
import fake.screenshot.hooks.HookContext.ProcessKind
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
     * ClassLoader 是框架对象（classloader-neutral），经 savedInstanceState
     * 传递合法；模块自建对象禁止传递（会钉住旧代 classloader）。
     */
    private var hookParam: Pair<String, ClassLoader>? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(Log.INFO, TAG, "module loaded in ${param.processName}, system=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        hookParam = "system" to param.classLoader
        HookContext.init(this, ProcessKind.SYSTEM_SERVER)
        installHooks()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (!param.isFirstPackage) return
        if (param.packageName !in SCREENSHOT_PACKAGES) return
        hookParam = param.packageName to param.classLoader
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
                // E2a CaptureDetectionHook —— ScreenCaptureCallback 派发吞噬（检测者）
                // E2b RecordDetectionHook  —— ScreenRecordingCallback 派发吞噬（检测者）
                // E2c/2d OverlayStealthHook—— TrustedPresentation + 遮挡参与位（检测者）
                // E3b ProjectionReplaceHook—— MediaProjection 虚拟屏假图层（全局）
                // E4 FreeformStealthHook   —— 被配置 app 自由小窗截图隐身
            }
            ProcessKind.SCREENSHOT_APP -> {
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
        if (saved is Pair<*, *> && saved.first is String && saved.second is ClassLoader) {
            hookParam = saved.first as String to saved.second as ClassLoader
            installHooks()
        }
        param.oldHookHandles.forEach { h ->
            if (!HookContext.isHookInstalled(h.id)) h.unhook()
        }
        HookContext.log(Log.INFO, "hot reloaded, ${param.oldHookHandles.size} old handles")
    }
}
