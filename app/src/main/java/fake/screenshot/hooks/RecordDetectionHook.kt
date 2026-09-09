package fake.screenshot.hooks

import android.os.Binder
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * E2b 录屏检测吞噬引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者的录屏/投屏感知通道（对照 ScreenshotDetector 实测源码）：
 *
 * 【通道 1：ScreenRecordingCallback（API 34+）】
 * WindowManager.addScreenRecordingCallback → binder →
 * com.android.server.wm.ScreenRecordingCallbacks#registerScreenRecordingCallback
 * (uid, callback)（注册即回调当前状态）。hook：uid 对应包名 masked →
 * 跳过注册——回调永不触发，检测者永远认为"无录屏"。A13- 无此类（CNFE
 * 容错静默，原生无该检测面）。
 *
 * 【通道 2：虚拟显示器存在性（MediaProjection / 投屏 / 镜像）】
 * 检测者扫描 DisplayManager.getDisplays() + DisplayListener——三方录屏
 * 创建的 VirtualDisplay 被检出（MEDIA_PROJECTION / SCREEN_MIRRORING 项）。
 * DisplayManagerService（system_server）双层隐身：
 * - getDisplayIds：masked 调用者 → 结果剔除虚拟类显示器 id（列表层隐身，
 *   dm.displays 只剩内建屏——检测者的 scanVisibleDisplays 与 null-info
 *   兜底 `hasNonDefaultDisplay(dm.displays)` 全部落空）；
 * - getDisplayInfo：masked 调用者 → 虚拟类显示器返回 null（防 onDisplayAdded
 *   事件后按 id 单点查询）。
 * 类型过滤集 {WIFI(3), OVERLAY(4), VIRTUAL(5)}——HDMI(2) 物理外显保留
 * （EXTERNAL_DISPLAY 语义，非录屏域）。系统内部调用走 clearCallingIdentity
 * （uid=1000）不受影响。
 *
 * 媒体库 Videos 监听（录屏落盘）由 E2a 的 ContentService 腿统一覆盖
 * （video 子域 → maskRecordDetection 门控，见 [CaptureDetectionHook]）。
 *
 * 已知边界（文档化接受）：
 * - Miracast WifiDisplayStatus 隐藏广播（物理协议层，罕见）；
 * - MediaRouter 路由发现（Cast 音频路由，非录屏）。
 */
object RecordDetectionHook {

    /** DisplayInfo.type：对 masked 调用者隐身的显示器类型（WIFI/OVERLAY/VIRTUAL） */
    private val HIDDEN_DISPLAY_TYPES = setOf(3, 4, 5)

    // ---- 反射单点缓存 ----

    /** DMS#getDisplayInfo(int)（getDisplayIds 过滤时内部探测虚拟性用） */
    private var getDisplayInfoM: Method? = null

    /** DisplayInfo.type 公有字段 */
    private var displayInfoTypeField: Field? = null

    /** getDisplayIds 过滤中的重入标记（内部探测 getDisplayInfo 需旁路本引擎过滤） */
    private val filtering = ThreadLocal<Boolean>()

    fun installSystemServer(classLoader: ClassLoader) {
        installScreenRecordingLeg(classLoader)
        installDisplayLeg()
    }

    // ==================== 通道 1：ScreenRecordingCallback ====================

    private fun installScreenRecordingLeg(classLoader: ClassLoader) {
        runCatching {
            val srClass = classLoader.loadClass("com.android.server.wm.ScreenRecordingCallbacks")
            var hooked = 0
            srClass.declaredMethods
                .filter { it.name.contains("registerScreenRecordingCallback") }
                .forEach { m ->
                    val uidIdx = m.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType }
                    if (uidIdx < 0) return@forEach
                    m.isAccessible = true
                    HookContext.hookE("E2b", m).intercept { chain ->
                        val uid = chain.args.getOrNull(uidIdx) as? Int ?: -1
                        if (uid >= 0 && HookContext.anyPkgForUid(uid) { HookContext.maskRecordDetection(it) }) {
                            HookContext.log(Log.INFO, "E2b recording callback swallowed: uid=$uid")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }
            HookContext.log(Log.INFO, "E2b screenRecording: $hooked register paths hooked")
        }.onFailure {
            HookContext.log(Log.INFO, "E2b screenRecording leg unavailable: ${it.message}")
        }
    }

    // ==================== 通道 2：虚拟显示器隐身 ====================

    private fun installDisplayLeg() {
        runCatching {
            val dmsClass = Class.forName("com.android.server.display.DisplayManagerService")
            getDisplayInfoM = dmsClass.declaredMethods
                .firstOrNull { it.name == "getDisplayInfo" && it.parameterCount == 1 }
                ?.apply { isAccessible = true }
            if (getDisplayInfoM == null) {
                HookContext.log(Log.WARN, "E2b display leg abort: getDisplayInfo not found")
                return
            }
            displayInfoTypeField = runCatching {
                getDisplayInfoM!!.returnType.getField("type")
            }.getOrNull()

            var hookedIds = 0
            dmsClass.declaredMethods.filter { it.name == "getDisplayIds" }.forEach { m ->
                m.isAccessible = true
                HookContext.hookE("E2b", m).intercept { chain ->
                    val ids = chain.proceed() as? IntArray ?: return@intercept null
                    if (!callerMasked()) return@intercept ids
                    filtering.set(true)
                    try {
                        ids.filter { id -> !isHiddenDisplay(chain.thisObject, id) }.toIntArray()
                    } finally {
                        filtering.set(false)
                    }
                }
                hookedIds++
            }

            var hookedInfo = 0
            dmsClass.declaredMethods
                .filter { it.name == "getDisplayInfo" && it.parameterCount == 1 }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E2b", m).intercept { chain ->
                        // 重入旁路：getDisplayIds 过滤中的探测调用直通
                        if (filtering.get() == true) return@intercept chain.proceed()
                        val info = chain.proceed() ?: return@intercept null
                        if (!callerMasked()) return@intercept info
                        // 直接判定已取到的 info（不得反射再调 getDisplayInfo——同线程同 uid 会重入本 hook）
                        val type = runCatching { displayInfoTypeField?.getInt(info) }.getOrNull()
                        if (type != null && type in HIDDEN_DISPLAY_TYPES) {
                            HookContext.log(Log.INFO, "E2b virtual display hidden from masked caller")
                            null
                        } else {
                            info
                        }
                    }
                    hookedInfo++
                }
            HookContext.log(Log.INFO, "E2b display: $hookedIds id-lists, $hookedInfo info paths hooked")
        }.onFailure { HookContext.log(Log.WARN, "E2b display leg error: ${it.message}") }
    }

    private fun callerMasked(): Boolean =
        HookContext.anyPkgForUid(Binder.getCallingUid()) { HookContext.maskRecordDetection(it) }

    /** 该显示器是否属于对 masked 调用者隐身的类型（内部探测经 [filtering] 旁路过滤） */
    private fun isHiddenDisplay(dms: Any?, displayId: Int): Boolean {
        if (displayId < 0) return false
        val info = runCatching {
            getDisplayInfoM?.invoke(dms, displayId)
        }.getOrNull() ?: return false
        val type = runCatching { displayInfoTypeField?.getInt(info) }.getOrNull() ?: return false
        return type in HIDDEN_DISPLAY_TYPES
    }
}
