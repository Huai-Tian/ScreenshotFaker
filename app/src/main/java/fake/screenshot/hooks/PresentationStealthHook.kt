package fake.screenshot.hooks

import android.annotation.SuppressLint
import android.os.Binder
import android.util.Log
import java.util.Collections
import java.util.WeakHashMap

/**
 * E2e 窗口显示完整性屏蔽引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者信号链（对照 ScreenshotDetector 实测源码，API 35+）：
 * WindowManager#registerTrustedPresentationListener(token, thresholds,
 * executor, consumer) → system_server 侧 TrustedPresentationListener
 * Controller（TPLC）持续以 WindowInfos 计算窗口实际渲染像素比例
 * （fractionRendered：可见区域 × 缩放 × alpha），状态迁移时回调
 * ITrustedPresentationListener#onTrustedPresentationChanged(int[] trustedIds,
 * int[] untrustedIds) → 检测者 Consumer<Boolean>。false 边沿（含自家弹层
 * 与后台离场排除后）→ WINDOW_NOT_FULLY_PRESENTED。另有 bootstrap 兜底：
 * 注册后 3s 未收到任何回调（启动即被遮挡、状态恒 untrusted 无迁移）也上报。
 *
 * 腿结构：
 * - 腿 1（登记）：WindowManagerService#registerTrustedPresentationListener
 *   （binder 入口，4 参——客户端 WindowManagerGlobal 经 IWindowManager
 *   直达 WMS，不经 Session）hook 后 proceed 照常——注册必须成功，true
 *   回调才能抵达（bootstrap 依赖"收到过任意回调"）。以
 *   Binder.getCallingUid() 解析注册方包名，masked 时登记 listener → pkg
 *   （弱表随 binder 死亡回收自然清理）；
 * - 腿 2（改写）：ITrustedPresentationListener$Stub$Proxy#
 *   onTrustedPresentationChanged 派发层——TPLC 向客户端派发的唯一出口；
 *   masked 监听者的 untrustedIds 整体挪入 trustedIds：客户端永远只
 *   收到 true。启动即遮挡（初始 untrusted）→ 首帧即 true →
 *   callbackSeen 置位，bootstrap 兜底不触发；运行中遮挡迁移 → true
 *   重申（检测者只取 false 边沿）→ 不上报。遮挡消失的真实 true 恢复
 *   照常，无需区分。
 *
 * 覆盖面：一切使呈现不完整的因素（外部悬浮窗/系统浮层/手势导航离场
 * 动画/半透明化），与遮挡源无关——派发层按检测者包名精准命中，优于
 * 窗口属性方案（canOccludePresentation 是 SurfaceControl native 层
 * 标志，Java 无杠杆）。
 *
 * 版本边界：TrustedPresentation 为 API 35+，检测者亦仅在 35+ 注册。
 * <35 或 OEM 形变时 hook 静默不命中（方法清单为空），引擎空转零开销。
 */
object PresentationStealthHook {

    /** ITrustedPresentationListener proxy → 注册方包名（腿 1 登记 / 腿 2 查询） */
    private val listenerOwners =
        Collections.synchronizedMap(WeakHashMap<Any, String>())

    @SuppressLint("PrivateApi")
    fun installSystemServer(classLoader: ClassLoader) {
        var legs = 0
        // ---- 腿 1：注册登记（binder 入口，拿 callingUid 定归属）----
        runCatching {
            val wmsClass = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            wmsClass.declaredMethods
                .filter { m ->
                    m.name == "registerTrustedPresentationListener" &&
                            m.parameterTypes.any { it.name == "android.window.ITrustedPresentationListener" }
                }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E2e", m).intercept { chain ->
                        runCatching {
                            val listener = chain.args.firstOrNull {
                                it?.javaClass?.name?.contains("ITrustedPresentationListener") == true
                            } ?: return@runCatching
                            val pkgs = HookContext.packagesForUid(Binder.getCallingUid())
                            val pkg = pkgs?.firstOrNull { HookContext.maskPresentationDetection(it) }
                            if (pkg != null) {
                                listenerOwners[listener] = pkg
                                HookContext.log(Log.INFO, "E2e presentation listener masked: $pkg")
                            }
                        }
                        chain.proceed()
                    }
                    legs++
                }
        }.onFailure { HookContext.log(Log.WARN, "E2e register leg error: ${it.message}") }
        // ---- 腿 2：派发改写（协议层唯一出口，aidl 接口跨版本稳定）----
        runCatching {
            val proxyClass = classLoader.loadClass("android.window.ITrustedPresentationListener\$Stub\$Proxy")
            val methods = proxyClass.declaredMethods
                .filter { it.name == "onTrustedPresentationChanged" }
                .ifEmpty {
                    proxyClass.declaredMethods.filter { it.name.contains("Presentation", true) }
                }
            methods.forEach { m ->
                // 协议位：(int[] trustedIds, int[] untrustedIds)
                val intArrIdx = m.parameterTypes.mapIndexed { i, t ->
                    if (t == IntArray::class.java) i else -1
                }.filter { it >= 0 }
                if (intArrIdx.size < 2) return@forEach
                m.isAccessible = true
                HookContext.hookE("E2e", m).intercept { chain ->
                    val pkg = listenerOwners[chain.thisObject]
                        ?: return@intercept chain.proceed()
                    if (!HookContext.maskPresentationDetection(pkg)) return@intercept chain.proceed()
                    val trusted = chain.args.getOrNull(intArrIdx[0]) as? IntArray
                    val untrusted = chain.args.getOrNull(intArrIdx[1]) as? IntArray
                    if (untrusted == null || untrusted.isEmpty()) return@intercept chain.proceed()
                    // untrusted → trusted 改写：客户端只见 true（含首帧补发，
                    // bootstrap 兜底失效）
                    HookContext.log(Log.INFO, "E2e presentation suppressed: $pkg")
                    val patched = chain.args.toTypedArray()
                    patched[intArrIdx[0]] = (trusted ?: IntArray(0)) + untrusted
                    patched[intArrIdx[1]] = IntArray(0)
                    chain.proceed(patched)
                }
            }
            if (methods.isNotEmpty()) {
                HookContext.deoptimizeMethods(proxyClass, "onTrustedPresentationChanged")
            }
            legs += methods.size
        }.onFailure { HookContext.log(Log.WARN, "E2e dispatch leg error: ${it.message}") }
        HookContext.log(Log.INFO, "E2e presentation conceal legs=$legs")
    }
}
