package fake.screenshot.hooks

import android.util.Log
import java.lang.reflect.Method

/**
 * E2d 遮挡检测隐身引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者的悬浮窗/小窗感知通道（对照 ScreenshotDetector 实测源码）：
 *
 * 【通道 1：窗口焦点丢失轮询（主归因通道，本引擎目标）】
 * 检测者以 200ms 轮询 hasWindowFocus()：RESUMED + 亮屏 + 持续失焦 →
 * FOCUS_LOSS，再经用量统计/无障碍快照归因为 FLOATING_WINDOW（悬浮窗）
 * 或 FREEFORM_WINDOW（自由小窗）。焦点信号经
 * WindowState#reportFocusChangedSerialized(hasFocus, inTouchMode) →
 * IWindow.windowFocusChanged binder 派发至检测者进程。hook：masked 检测
 * 者 + 失焦 → 改报"仍持有焦点"——轮询恒真，unfocusedPolls 归零，
 * FOCUS_LOSS / FLOATING_WINDOW / FREEFORM_WINDOW 全链路失活。
 * 副作用：masked 应用在真实失焦期间自认有焦点（输入框光标等显示态
 * 微失真）——mask 语义内的可接受代价；真实切后台先 onPause，检测
 * 轮询的 RESUMED 前置条件立即失效，不受影响。
 *
 * 【通道 2：TrustedPresentation（API 35+，注册点探测）】
 * 窗口呈现比例回调。若注册/派发经 system_server（WMS/Session/
 * WindowState/ActivityRecord 有 TrustedPresentation 形方法）则吞注册；
 * 探测零命中时输出探针日志（AOSP 实现为客户端 ViewRootImpl 直连
 * SurfaceFlinger 的 commit listener，system_server 无杠杆——文档化边界）。
 *
 * 【已知无杠杆边界（文档化接受）】
 * - FLAG_WINDOW_IS_OBSCURED / PARTIALLY_OBSCURED：InputDispatcher 原生
 *   层计算（native，Java hook 不可达；唯一注入点在检测者进程内，违反
 *   零注入体例）；
 * - TrustedPresentation（若通道 2 探测零命中）：客户端直连 SF；
 * - 无障碍窗口快照（需检测者自启无障碍服务，罕见）与按键流（API<34）；
 * - 用量统计归因：焦点丢失被隐瞒后无归因需求（信号链已在源头断开）。
 */
object OverlayStealthHook {

    fun installSystemServer(classLoader: ClassLoader) {
        installFocusLeg(classLoader)
        installTrustedPresentationProbe(classLoader)
    }

    // ==================== 通道 1：焦点丢失隐瞒 ====================

    private fun installFocusLeg(classLoader: ClassLoader) {
        runCatching {
            val wsClass = classLoader.loadClass("com.android.server.wm.WindowState")
            val getOwningPackage = methodInHierarchy(wsClass, "getOwningPackage")
            val report = methodInHierarchy(
                wsClass, "reportFocusChangedSerialized",
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE
            )
            if (report == null) {
                // OEM 改名校准弹药：列出焦点派发痕迹
                val trace = hierarchyOf(wsClass)
                    .flatMap { it.declaredMethods.toList() }
                    .filter { it.name.contains("FocusChanged", true) }
                    .joinToString { "${it.name}(${it.parameterCount})" }
                HookContext.log(Log.WARN, "E2d focus leg abort: $trace")
                return
            }
            report.isAccessible = true
            HookContext.hookE("E2d", report).intercept { chain ->
                val hasFocus = chain.args.getOrNull(0) as? Boolean ?: return@intercept chain.proceed()
                if (hasFocus) return@intercept chain.proceed()
                val pkg = runCatching {
                    getOwningPackage?.invoke(chain.thisObject) as? String
                }.getOrNull()
                if (pkg != null && HookContext.maskOverlayDetection(pkg)) {
                    HookContext.log(Log.INFO, "E2d focus loss concealed: $pkg")
                    chain.proceed(arrayOf(true, chain.args[1]))
                } else {
                    chain.proceed()
                }
            }
            HookContext.log(Log.INFO, "E2d focus conceal hooked")
        }.onFailure { HookContext.log(Log.WARN, "E2d focus leg error: ${it.message}") }
    }

    // ==================== 通道 2：TrustedPresentation 探测 ====================

    private fun installTrustedPresentationProbe(classLoader: ClassLoader) {
        var hooked = 0
        val probeHits = ArrayList<String>()
        for (fqcn in listOf(
            "com.android.server.wm.WindowManagerService",
            "com.android.server.wm.Session",
            "com.android.server.wm.WindowState",
            "com.android.server.wm.ActivityRecord",
        )) {
            runCatching {
                val clazz = classLoader.loadClass(fqcn)
                clazz.declaredMethods
                    .filter { it.name.contains("TrustedPresentation", true) }
                    .forEach { m ->
                        probeHits.add("${clazz.simpleName}#${m.name}")
                        // 仅吞注册形方法（register*/set*），派发形不碰
                        if (m.name.startsWith("register") || m.name.startsWith("set")) {
                            m.isAccessible = true
                            HookContext.hookE("E2d", m).intercept { chain ->
                                if (HookContext.anyPkgForUid(android.os.Binder.getCallingUid()) {
                                        HookContext.maskOverlayDetection(it)
                                    }
                                ) null else chain.proceed()
                            }
                            hooked++
                        }
                    }
            }
        }
        HookContext.log(
            Log.INFO,
            if (probeHits.isEmpty()) "E2d trustedPresentation: no system-side point (client/SF path, boundary)"
            else "E2d trustedPresentation: $hooked hooked of ${probeHits.size} probes ${probeHits.joinToString()}"
        )
    }

    // ==================== 反射工具 ====================

    private fun hierarchyOf(cls: Class<*>): Sequence<Class<*>> =
        generateSequence(cls) { it.superclass }

    private fun methodInHierarchy(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = runCatching { c!!.getDeclaredMethod(name, *params) }.getOrNull()
            if (m != null) {
                m.isAccessible = true
                return m
            }
            c = c.superclass
        }
        return null
    }
}
