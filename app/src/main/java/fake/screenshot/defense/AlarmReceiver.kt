package fake.screenshot.defense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fake.screenshot.wrappers.DaemonManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 超时销毁到期复查（封堵"重启缺口"的后续窗口）：
 *
 * BootCompletedReceiver 的检查通过但未到期时，由 IdleWatchdog 按当前
 * 锚点剩余时间布防本闹钟（ELAPSED_REALTIME_WAKEUP，单调时钟不可回拨）。
 * 到点无头复查——否则"重启后胁迫者解锁一次、app 永不被打开、设备不再
 * 重启"的场景下，超时判定没有第二个触发点，销毁被无限推迟。
 *
 * 链条自续：复查未到期 → 布防路径重新排下一枚闹钟；闹钟不跨重启
 * （重启 → BootCompletedReceiver → 检查 → 重新布防），链条闭合。
 * 到期销毁全程无需 DK/UI（删 Keystore 条目与密文不依赖解锁态，同
 * BootCompletedReceiver 语义）。
 *
 * 布防条件保证 CE 存储可用：闹钟只能由检查通过路径布防，而检查需要
 * 读 DataStore（仅解锁后可达）——闹钟触发时 CE 存储必已挂载（FBE 下
 * 解锁一次后跨锁屏保持可用，直至下次重启）。
 *
 * 已知边界（与 BootCompletedReceiver 共有）：force-stop 会取消全部
 * 闹钟并停用组件直至用户手动启动 app；长期闲置可能触发系统休眠
 * （同效）。代价是攻击者必须预知该机制的存在与作用。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // init 可能因 Keystore 异常抛出——receiver 崩溃会被系统记录并可能
        // 禁用组件（到期复查链条断裂），必须吞掉
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
