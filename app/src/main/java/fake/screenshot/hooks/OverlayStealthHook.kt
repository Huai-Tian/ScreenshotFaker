package fake.screenshot.hooks

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * E2c 悬浮窗直接信号屏蔽引擎（纯 system_server，检测者进程零注入）。
 *
 * 开关语义（与 E2d 焦点引擎彻底解耦）：每个检测项开关只屏蔽该检测项
 * 的直接信号通道。悬浮窗检测的直接信号 = 触摸遮挡标志——检测者收到
 * MotionEvent 时读取 FLAG_WINDOW_IS_OBSCURED / PARTIALLY_OBSCURED
 * （"不信任的点击"：触点上方存在不可信窗口即置位，native
 * InputDispatcher 按输入窗口句柄计算）。检测者由"失焦 + 用量统计"等
 * 推理得出的悬浮窗结论不属本引擎处理范围（用户语义：逻辑推理不屏蔽）。
 *
 * 杠杆：模块自有悬浮窗（控制条 / 假屏浮层，统一包名 [OWN_PKG]）在
 * populate 阶段标记 InputConfig.TRUSTED_OVERLAY——trusted overlay 不
 * 参与遮挡计算（AOSP 同机制豁免 IME/水印等系统浮层，仅类型白名单
 * isTrustedOverlay 覆盖系统窗，本引擎为自有窗补位），下方窗口触摸事件
 * 的两条遮挡标志一并失活。populate 双通道全覆盖：
 * - populateInputWindowHandle(wrapper, w)：可触摸窗（控制条）；
 * - populateOverlayInputInfo(wrapper, w)：无输入窗（NOT_TOUCHABLE 假屏
 *   浮层——updateInputWindowsLw 的 overlay 分支，同以 handle 参与
 *   遮挡检测）。
 * 写入走 wrapper#setTrustedOverlay(boolean)（延迟式：置 inputConfig 位
 * + 标记 changed，由系统随后的 applyChangesToSurface 随 input 事务下发）；
 * ≤14 wrapper 缺失时回落直写 InputWindowHandle.trustedOverlay 字段。
 *
 * 生效面诚实说明：TRUSTED_OVERLAY 是自有窗口属性，作用于全部下方窗口
 * ——无法按检测者包名区分，任一模板开启屏蔽即标记（全局生效）；关闭
 * 时清除标记。两者均延迟到该窗口下一次 populate（input 窗口刷新，
 * 实践中亚秒级）。仅标记自有窗口，不替第三方悬浮窗背书。
 *
 * 【已知无杠杆边界（文档化接受）】
 * - 无障碍窗口快照（getWindows 直接列出悬浮窗；需检测者自启无障碍
 *   服务，罕见）；
 * - 第三方悬浮窗的遮挡标志（非自有窗口不动，防误伤系统受信浮层）。
 */
object OverlayStealthHook {

    /** 模块自有窗口统一包名（悬浮服务与主 Activity 同包） */
    private const val OWN_PKG = "fake.screenshot"

    // ---- 反射单点缓存 ----

    /** WindowState#getOwningPackage */
    private var getOwningPackage: Method? = null

    /** wrapper#setTrustedOverlay(boolean)（15：延迟式，随系统事务下发） */
    private var wrapperTrustM: Method? = null

    /** ≤14 直写回落：InputWindowHandle.trustedOverlay 公有字段 */
    private var handleTrustF: Field? = null

    /** 最近已留痕的标记值（null=未留痕；开关切换各一条，运行期零日志） */
    @Volatile private var lastLoggedTrust: Boolean? = null

    fun installSystemServer(classLoader: ClassLoader) {
        runCatching {
            val imClass = classLoader.loadClass("com.android.server.wm.InputMonitor")
            val wsClass = classLoader.loadClass("com.android.server.wm.WindowState")
            getOwningPackage = methodNoArgInHierarchy(wsClass, "getOwningPackage")?.apply {
                isAccessible = true
            }
            var legs = 0
            // populate 双通道（可触摸窗 / 无输入 overlay 窗）
            for (name in listOf("populateInputWindowHandle", "populateOverlayInputInfo")) {
                imClass.declaredMethods
                    .filter { it.name == name && it.parameterCount == 2 }
                    .forEach { m ->
                        val wrapIdx = m.parameterTypes.indexOfFirst {
                            it.name.endsWith("InputWindowHandleWrapper") ||
                                    it.name == "android.view.InputWindowHandle"
                        }
                        val wsIdx = m.parameterTypes.indexOfFirst {
                            it.name.endsWith("WindowState")
                        }
                        if (wrapIdx < 0 || wsIdx < 0) return@forEach
                        m.isAccessible = true
                        HookContext.hookE("E2c", m).intercept { chain ->
                            val r = chain.proceed()
                            // 置位须在 populate 之后（系统写完字段再补位）；
                            // 失败静默（populate 高频热路径）
                            runCatching {
                                markOwnWindow(chain.args.getOrNull(wsIdx), chain.args.getOrNull(wrapIdx))
                            }
                            r
                        }
                        legs++
                    }
            }
            if (legs > 0) {
                HookContext.deoptimizeMethods(
                    imClass, "populateInputWindowHandle", "populateOverlayInputInfo"
                )
            }
            HookContext.log(Log.INFO, "E2c overlay conceal legs=$legs")
        }.onFailure { HookContext.log(Log.WARN, "E2c install error: ${it.message}") }
    }

    /**
     * 自有窗口的 trustedOverlay 置位/清除（幂等：实际值变化才标记
     * changed，无额外事务开销）。开 = 任一模板启用悬浮窗屏蔽；关 =
     * 清除恢复原生可检测。非自有窗口零触碰——系统受信浮层（IME 等）
     * 的位由系统管理。
     */
    private fun markOwnWindow(ws: Any?, handleArg: Any?) {
        if (ws == null || handleArg == null) return
        if (owningPackageOf(ws) != OWN_PKG) return
        val trust = HookContext.anyOverlayMaskOn()
        if (handleArg.javaClass.name.endsWith("InputWindowHandleWrapper")) {
            val m = wrapperTrustM ?: handleArg.javaClass.declaredMethods
                .firstOrNull {
                    it.name == "setTrustedOverlay" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == java.lang.Boolean.TYPE
                }
                ?.also { it.isAccessible = true; wrapperTrustM = it }
            ?: return
            m.invoke(handleArg, trust)
        } else {
            val f = handleTrustF
                ?: runCatching { handleArg.javaClass.getField("trustedOverlay") }.getOrNull()
                    ?.also { handleTrustF = it }
                ?: return
            f.setBoolean(handleArg, trust)
        }
        if (trust != lastLoggedTrust) {
            lastLoggedTrust = trust
            HookContext.log(Log.INFO, "E2c own window trustedOverlay=$trust")
        }
    }

    private fun owningPackageOf(ws: Any?): String? =
        runCatching { getOwningPackage?.invoke(ws) as? String }.getOrNull()

    private fun methodNoArgInHierarchy(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = runCatching {
                c!!.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            }.getOrNull()
            if (m != null) return m
            c = c.superclass
        }
        return null
    }
}
