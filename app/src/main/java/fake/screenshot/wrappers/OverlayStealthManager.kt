package fake.screenshot.wrappers

import android.annotation.SuppressLint
import android.view.WindowManager

/**
 * 悬浮窗反检测（root 托管模式专用）。
 *
 * 触摸事件 FLAG_WINDOW_IS_OBSCURED（穿透遮挡标记）的消除需要
 * PRIVATE_FLAG_TRUSTED_OVERLAY：WMS 对其校验签名权限
 * INTERNAL_SYSTEM_WINDOW，应用进程（普通应用 uid）必然失败；
 * 唯一出路是把窗口放进 root（uid=0）进程——uid=0 在权限校验中
 * 直接 GRANTED（见 fake.screenshot.services.privileged.RootDisplayService）。
 *
 * 刻意不做的（会留痕）：
 * - 修改 Settings.Global（如 block_untrusted_touches）：任何应用可读取，本身即特征。
 * - 修改 appops / 系统属性：同上。
 * - 本地窗口伪装身份：无特权模式一律为普通悬浮窗，不引入伪装逻辑。
 */
object OverlayStealthManager {

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
    @SuppressLint("DiscouragedPrivateApi")
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
