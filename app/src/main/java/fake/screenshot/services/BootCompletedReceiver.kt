package fake.screenshot.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fake.screenshot.defense.DefenseProtocol
import fake.screenshot.defense.IdleWatchdog
import fake.screenshot.wrappers.DaemonManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 重启后的超时自毁检查（填补"重启缺口"的核心组件）。
 *
 * 威胁场景：取证者拿到开机设备后第一动作往往是重启——重启杀死 daemon
 * （且 DK 拆分使其无法自启），若 app 从不被打开，超时自毁永不触发，
 * 5 分钟档与 12 个月档在重启面前等价。
 *
 * 本 receiver 在用户解锁锁屏（FBE CE 存储可用）的瞬间无头运行
 * [IdleWatchdog.checkIdleExpired]：
 * - 未超期：静默退出，并按剩余时间布防到期复查闹钟（AlarmReceiver，
 *   封堵"解锁后 app 永不再打开且不再重启"的触发点缺口）。开机不是
 *   "有效使用"，绝不 touch（防续命）
 * - 超期/篡改：完成销毁（删 Keystore 条目与密文均不需要 DK，无头可执行）
 *
 * 已知军备竞赛边界（记录于威胁模型）：root 可在解锁前 pm disable 本组件
 * 或 force-stop app——代价是攻击者必须预先知晓该机制的存在与作用。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // init 可能因 Keystore 异常抛出——receiver 崩溃会被系统记录并可能
        // 禁用组件（重启自毁链条断裂），必须吞掉
        runCatching {
            DaemonManager.init(context)
            DefenseProtocol.init(context)
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 任何异常不得逃逸：receiver 崩溃会被系统记录并可能禁用组件
                runCatching { IdleWatchdog.checkIdleExpired() }
            } finally {
                pending.finish()
            }
        }
    }
}
