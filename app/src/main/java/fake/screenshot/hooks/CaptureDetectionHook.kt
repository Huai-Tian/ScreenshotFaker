package fake.screenshot.hooks

import android.net.Uri
import android.os.Binder
import android.util.Log
import java.lang.reflect.Method

/**
 * E2a 截屏检测吞噬引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者的两类截屏感知通道（对照 ScreenshotDetector 实测源码）：
 *
 * 【通道 1：ScreenCaptureCallback（API 34+ 主路径）】
 * 检测者（Activity.registerScreenCaptureCallback）→ binder →
 * ActivityRecord 持有 observer；截屏时 WMS 回调派发。双点吞噬
 * （精确方法名，AOSP 标准形 + ColorOS 实证形）：
 * - registerScreenCaptureObserver（AOSP 注册）：masked → 跳过注册
 *   （observer 列表为空，派发无从发生；反注册为无遍历删除，客户端
 *   void 调用无感知）；
 * - dispatchScreenCaptureCallback（AOSP 派发）/ reportScreenCaptured
 *   （ColorOS 15 派发，OEM 改名，实测）：masked → 吞噬——与注册路径
 *   互为冗余覆盖。
 * 查询形（isRegisteredForScreenCaptureCallback）不碰：吞噬查询会
 * 破坏 WMS 内部派发判定。
 *
 * 【通道 2：媒体库 ContentObserver（激进检测过滤，全版本路径 + Shell 通道）】
 * 检测者监听 MediaStore.Images / Downloads（截图落盘 + screencap 落盘
 * Download/）。ContentService#registerContentObserver（system_server）：
 * 调用者开启激进过滤且 URI 属媒体域（authority media/downloads）→
 * 静默跳过注册——注册/反注册 binder 调用正常返回（客户端零感知），
 * observer 永不触发。激进过滤为 per-app 独立开关（应用详情页），
 * 不依赖模板分配；开启即该应用媒体域监听全吞（含 video 子域——
 * 检测者的截屏与录屏媒体库监听一并隐身，不再按域分诊）。
 * 副作用：应用自身的媒体库变更感知同步失效（相册类自动刷新）——
 * 「激进」语义。
 *
 * 与 E1 的组合语义：DENY 时截屏已被拒（媒体库无新增、secure 派发不发生），
 * 本引擎作用于 ALLOW/FOLLOW 下"截屏成功但检测者不应知晓"的场景。
 *
 * 已知边界（文档化接受，system_server 无杠杆）：
 * - MediaProvider 冷启动回查（检测者进程对 media provider 的 query，
 *   15s 回看窗内命中——需注入 media 进程，违反零注入体例）；
 * - API<34 无障碍按键流回退路径（需检测者自启无障碍服务，罕见）。
 */
object CaptureDetectionHook {

    /** 媒体域 authority（MediaStore 全系 + 旧版 Downloads provider） */
    private val MEDIA_AUTHORITIES = setOf("media", "downloads")

    /**
     * 吞噬目标（AOSP 标准形 + ColorOS 15 实证形；精确名匹配，
     * 查询形 isRegistered* 不碰——见类注释）
     */
    private val AR_CAPTURE_METHODS = setOf(
        "registerScreenCaptureObserver",
        "dispatchScreenCaptureCallback",
        "reportScreenCaptured",
    )

    fun installSystemServer(classLoader: ClassLoader) {
        installActivityRecordLeg(classLoader)
        installContentObserverLeg(classLoader)
    }

    // ==================== 通道 1：ScreenCaptureCallback ====================

    private fun installActivityRecordLeg(classLoader: ClassLoader) {
        runCatching {
            val arClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
            // ActivityRecord 继承 WindowToken：getOwningPackage 为 AOSP 标准
            // 方法，跨 ROM 稳定（实测 ColorOS 15 命中）
            val getOwningPackage = methodInHierarchy(arClass, "getOwningPackage")
                ?.apply { isAccessible = true }
            if (getOwningPackage == null) {
                HookContext.log(Log.WARN, "E2a activityRecord leg abort: no getOwningPackage")
                return
            }
            var hooked = 0
            hierarchyOf(arClass)
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name in AR_CAPTURE_METHODS }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E2a", m).intercept { chain ->
                        val pkg = runCatching {
                            getOwningPackage.invoke(chain.thisObject) as? String
                        }.getOrNull()
                        if (pkg != null && HookContext.maskCaptureDetection(pkg)) {
                            HookContext.log(Log.INFO, "E2a capture callback swallowed: $pkg")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }
            HookContext.log(
                Log.INFO,
                "E2a activityRecord: $hooked hooked of ${AR_CAPTURE_METHODS.size} targets"
            )
        }.onFailure { HookContext.log(Log.WARN, "E2a activityRecord leg error: ${it.message}") }
    }

    // ==================== 通道 2：媒体库 ContentObserver ====================

    private fun installContentObserverLeg(classLoader: ClassLoader) {
        runCatching {
            // ColorOS 15 实测：ContentService 不在模块 CL 可见域（bare
            // Class.forName CNFE），须经 system_server CL 装载
            val csClass = classLoader.loadClass("android.content.ContentService")
            var hooked = 0
            csClass.declaredMethods.filter { it.name == "registerContentObserver" }.forEach { m ->
                val uriIdx = m.parameterTypes.indexOfFirst { it == Uri::class.java }
                if (uriIdx < 0) return@forEach
                m.isAccessible = true
                HookContext.hookE("E2a", m).intercept { chain ->
                    val uri = chain.args.getOrNull(uriIdx) as? Uri
                    val masked = uri != null && isMaskedMediaWatch(uri)
                    if (masked) null else chain.proceed()
                }
                hooked++
            }
            HookContext.log(Log.INFO, "E2a contentObserver: $hooked register paths hooked")
        }.onFailure { HookContext.log(Log.WARN, "E2a contentObserver leg error: ${it.message}") }
    }

    /** 开启激进过滤的调用者且 URI 属媒体域（media/downloads，含 video 子域） */
    private fun isMaskedMediaWatch(uri: Uri): Boolean {
        if (uri.authority !in MEDIA_AUTHORITIES) return false
        return HookContext.anyPkgForUid(Binder.getCallingUid()) { HookContext.aggressiveFilter(it) }
    }

    // ==================== 反射工具 ====================

    private fun hierarchyOf(cls: Class<*>): Sequence<Class<*>> =
        generateSequence(cls) { it.superclass }

    private fun methodInHierarchy(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = runCatching { c!!.getDeclaredMethod(name, *params) }.getOrNull()
            if (m != null) return m
            c = c.superclass
        }
        return null
    }
}
