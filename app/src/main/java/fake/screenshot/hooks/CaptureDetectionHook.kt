package fake.screenshot.hooks

import android.content.ComponentName
import android.net.Uri
import android.os.Binder
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * E2a 截屏检测吞噬引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者的两类截屏感知通道（对照 ScreenshotDetector 实测源码）：
 *
 * 【通道 1：ScreenCaptureCallback（API 34+ 主路径）】
 * Activity.registerScreenCaptureCallback → binder → ActivityRecord 持有
 * observer；截屏包含 secure 内容时 WMS 回调派发。装配面双吞：
 * - 注册点（ActivityRecord#registerScreenCaptureObserver）：masked →
 *   跳过注册（observer 列表为空，派发永远无从发生；反注册为无遍历删除，
 *   客户端 void 调用无感知）；
 * - 派发点（ActivityRecord#dispatchScreenCaptureCallback）：masked →
 *   吞噬（兜底 OEM 注册路径变体）。
 * 方法名按 "ScreenCapture" 前缀扫描适配（OEM 改名容错），零命中输出
 * 探针日志供校准。
 *
 * 【通道 2：媒体库 ContentObserver（全版本路径 + Shell 通道）】
 * 检测者监听 MediaStore.Images / Downloads（截图落盘 + screencap 落盘
 * Download/）。ContentService#registerContentObserver（system_server）：
 * 调用者 masked 且 URI 属媒体域（authority media/downloads）→ 静默跳过
 * 注册，observer 永不触发）。子域分诊：video 子路径归 [HookContext.maskRecordDetection]
 * （录屏录像，E2b 域），images/downloads 归 maskCaptureDetection——单一
 * ContentService hook 点两域分治（注册无法区分引擎，两引擎共用此腿）。
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

    // ---- ActivityRecord 包名解析器链（install 期装配，事件期顺序取首个命中）----

    private val pkgResolvers = ArrayList<(Any) -> String?>()

    fun installSystemServer(classLoader: ClassLoader) {
        installActivityRecordLeg(classLoader)
        installContentObserverLeg()
    }

    // ==================== 通道 1：ScreenCaptureCallback ====================

    private fun installActivityRecordLeg(classLoader: ClassLoader) {
        runCatching {
            val arClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
            buildPkgResolvers(arClass)
            val candidates = hierarchyOf(arClass)
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name.contains("ScreenCapture") }
                .toList()
            var hooked = 0
            candidates.forEach { m ->
                val skip = when {
                    m.name.startsWith("register") && m.parameterCount == 1 -> true
                    m.name.startsWith("dispatch") && m.parameterCount == 0 -> true
                    else -> false
                }
                if (skip) {
                    m.isAccessible = true
                    HookContext.hookE("E2a", m).intercept { chain ->
                        val pkg = pkgOf(chain.thisObject)
                        if (pkg != null && HookContext.maskCaptureDetection(pkg)) {
                            HookContext.log(Log.INFO, "E2a capture callback swallowed: $pkg")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }
            }
            HookContext.log(
                Log.INFO,
                "E2a activityRecord: $hooked hooked of ${candidates.size} candidates, " +
                        "resolvers=${pkgResolvers.size}"
            )
            if (hooked == 0 && candidates.isNotEmpty()) {
                // OEM 改名校准弹药
                HookContext.log(
                    Log.INFO,
                    "E2a probe: " + candidates.joinToString { "${it.name}(${it.parameterCount})" }
                )
            }
        }.onFailure { HookContext.log(Log.WARN, "E2a activityRecord leg error: ${it.message}") }
    }

    /** 包名解析器链：getOwningPackage / getPackageName / getComponent / 同名字段 */
    private fun buildPkgResolvers(arClass: Class<*>) {
        fun addMethod(name: String, unwrap: (Any) -> String?) {
            methodInHierarchy(arClass, name)?.let { m ->
                m.isAccessible = true
                pkgResolvers.add { target ->
                    runCatching { m.invoke(target) }.getOrNull()?.let(unwrap)
                }
            }
        }
        addMethod("getOwningPackage") { it as? String }
        addMethod("getPackageName") { it as? String }
        addMethod("getComponent") { (it as? ComponentName)?.packageName }
        fieldInHierarchy(arClass, "packageName")?.let { f ->
            pkgResolvers.add { target -> runCatching { f.get(target) as? String }.getOrNull() }
        }
        fieldInHierarchy(arClass, "mActivityComponent")?.let { f ->
            pkgResolvers.add { target ->
                runCatching { (f.get(target) as? ComponentName)?.packageName }.getOrNull()
            }
        }
    }

    private fun pkgOf(activityRecord: Any?): String? {
        if (activityRecord == null) return null
        for (resolve in pkgResolvers) {
            runCatching { resolve(activityRecord) }.getOrNull()?.let { return it }
        }
        return null
    }

    // ==================== 通道 2：媒体库 ContentObserver ====================

    private fun installContentObserverLeg() {
        runCatching {
            val csClass = Class.forName("android.content.ContentService")
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

    /** 调用者 masked 且 URI 属媒体域：video → E2b 录屏域，其余 → E2a 截屏域 */
    private fun isMaskedMediaWatch(uri: Uri): Boolean {
        if (uri.authority !in MEDIA_AUTHORITIES) return false
        val isVideo = uri.pathSegments?.any { it.equals("video", true) } == true
        return HookContext.anyPkgForUid(Binder.getCallingUid()) { pkg ->
            if (isVideo) HookContext.maskRecordDetection(pkg)
            else HookContext.maskCaptureDetection(pkg)
        }
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

    private fun fieldInHierarchy(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) return f
            c = c.superclass
        }
        return null
    }
}
