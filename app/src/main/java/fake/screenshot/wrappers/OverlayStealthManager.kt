package fake.screenshot.wrappers

import android.annotation.SuppressLint
import android.view.View
import android.view.WindowManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 悬浮窗反检测（隐身模式）——全程不产生任何应用层可读取的痕迹。
 *
 * 第三方应用检测悬浮窗的主要途径与对策：
 * 1. AccessibilityService 监听窗口事件 / getWindows()：可见窗口的包名、标题、层级。
 *    → 将窗口身份伪装为 SystemUI（LayoutParams.packageName 在 ViewRootImpl 中
 *      仅在为 null 时才回填真实包名，反射预设即可生效），
 *      并把窗口内容对无障碍服务隐藏（IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS）。
 * 2. 触摸事件 FLAG_WINDOW_IS_OBSCURED（穿透遮挡标记）：窗口需要 TRUSTED_OVERLAY。
 *    WMS 对 PRIVATE_FLAG_TRUSTED_OVERLAY 校验签名权限 INTERNAL_SYSTEM_WINDOW，
 *    应用进程（uid 为普通应用 uid）必然失败，任何反射/伪装均无效；
 *    唯一出路是把窗口放进 root（uid=0）进程——uid=0 在权限校验中直接 GRANTED
 *    （见 fake.screenshot.services.privileged.RootDisplayService）。应用侧仅提供
 *    开关与连接管理，本类不再负责此事项。
 *
 * 刻意不做的（会留痕）：
 * - 修改 Settings.Global（如 block_untrusted_touches）：任何应用可读取，本身即特征。
 * - 修改 appops / 系统属性：同上。
 *
 * 本类所有伪装仅体现在窗口自身属性上，失败时自动回退为真实身份，
 * 保证悬浮窗功能不受影响，且无论成败均无持久系统状态被写入。
 */
object OverlayStealthManager {

    const val CONFIG_KEY_STEALTH = "overlay_stealth_mode"

    // 窗口归属伪装为 SystemUI：无障碍事件 / dumpsys 中仅可见该包名与标题
    private const val FAKE_PACKAGE_NAME = "com.android.systemui"
    private const val FAKE_WINDOW_TITLE = "NotificationShade"

    private var packageNameField: java.lang.reflect.Field? = null
    private var titleField: java.lang.reflect.Field? = null

    /** 伪装窗口身份（包名/标题）。返回是否成功（失败仅意味着保持真实身份，无副作用）。 */
    @SuppressLint("SoonBlockedPrivateApi")
    private fun disguiseWindowIdentity(params: WindowManager.LayoutParams): Boolean {
        return try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/")
            if (packageNameField == null) {
                packageNameField = WindowManager.LayoutParams::class.java
                    .getDeclaredField("packageName").apply { isAccessible = true }
                titleField = WindowManager.LayoutParams::class.java
                    .getDeclaredField("mTitle").apply { isAccessible = true }
            }
            packageNameField?.set(params, FAKE_PACKAGE_NAME)
            titleField?.set(params, FAKE_WINDOW_TITLE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 隐藏窗口内容：无障碍服务（canRetrieveWindowContent）将无法读取本窗口任何节点。 */
    fun hideFromAccessibility(view: View) {
        try {
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } catch (_: Throwable) {
        }
    }

    /**
     * 以伪装身份添加窗口。若系统拒绝伪装身份（部分版本会校验 appops 中
     * uid 与包名的一致性），自动清除伪装字段重试——ViewRootImpl 会回填真实包名，
     * 悬浮窗功能不受影响。返回窗口是否以伪装身份添加成功。
     */
    fun addViewDisguised(
        windowManager: WindowManager,
        view: View,
        params: WindowManager.LayoutParams
    ): Boolean {
        val disguised = disguiseWindowIdentity(params)
        if (!disguised) {
            windowManager.addView(view, params)
            return false
        }
        return try {
            windowManager.addView(view, params)
            true
        } catch (_: Throwable) {
            // 回退：清除伪装字段，ViewRootImpl 仅在 packageName 为 null 时回填真实包名
            runCatching {
                packageNameField?.set(params, null as String?)
                titleField?.set(params, null as String?)
            }
            windowManager.addView(view, params)
            false
        }
    }

    /**
     * 在窗口 LayoutParams 上写入 PRIVATE_FLAG_TRUSTED_OVERLAY（hidden 常量 1 shl 28）。
     *
     * 仅在 root（uid=0）进程中调用真实生效：WMS 校验签名权限
     * INTERNAL_SYSTEM_WINDOW，uid=0 的 binder 调用直接 GRANTED；之后本窗口
     * （FLAG_NOT_TOUCHABLE 穿透）遮挡下层应用时，InputDispatcher 的遮挡检查
     * 会跳过 trusted overlay，下层应用触摸事件不再携带 FLAG_WINDOW_IS_OBSCURED。
     *
     * 在应用进程中调用会被 WMS 静默忽略（无副作用），因此也可安全用于
     * "写上以防万一"的场景。返回是否成功写入字段。
     */
    fun applyTrustedOverlay(params: WindowManager.LayoutParams): Boolean {
        return try {
            val field = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
            field.isAccessible = true
            field.set(params, (field.get(params) as Int) or (1 shl 28))
            true
        } catch (_: Throwable) {
            false
        }
    }
}
