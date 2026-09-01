package fake.screenshot.defense

import android.content.Context
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.DaemonManager
import fake.screenshot.wrappers.OverlayServiceManager
import fake.screenshot.wrappers.ScreenShareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyStore
import kotlin.time.Duration.Companion.milliseconds

/**
 * L2 防御协议层：统一销毁入口 + 检查/销毁互斥锁 + defense 组件初始化。
 *
 * 三类触发源汇聚到同一把锁、同一段销毁序列：
 * - 胁迫密码命中（GateManager 验证结果 → GatePage 调用）
 * - 注入检测命中（GuardManager.checkNow → Application/Activity 调用）
 * - 超时/篡改判定命中（IdleWatchdog.checkIdleExpired → Boot/Activity 调用）
 *
 * 并发语义：BootReceiver、MainActivity（onCreate/onStart）、胁迫解锁
 * 三处可能并发触发检查或销毁；read-judge-destroy 必须整体串行，
 * 防止读到半销毁状态做出错误判定或双销毁交错。检查路径用 tryLock
 * （在途检查读取同一状态，其判定覆盖本次，后来者直接放行）。
 *
 * 销毁序列，顺序严格：
 * 0. 快照 idle 激活态（此后即将销毁一切密文，wipe 后无法再判定）；
 * 1. 停 app 侧屏幕共享（杀 relay 与守护脚本，防"销毁后仍在推流"；
 *    有界等待——exec 挂起时不得阻塞后续步骤，见 runBounded）；
 * 1.5 停 overlay 悬浮窗（root 路线宿主进程独立于 app 进程，不停则
 *    销毁后悬浮窗继续显示、输入监视通道继续运行）；
 * 2. 停守护进程（此时密钥/配置仍在，stop 依赖端口与信道密钥；同样有界）；
 * 3. 删 Keystore 条目（Tink 主密钥、硬件密钥）与密文文件（keyset、硬件 DK）；
 * 4. 删除密文配置并轮换 DataStore 文件随机名（同进程重建走新路径，
 *    规避 "multiple DataStores active for the same file"，且不暴露销毁史）；
 * 5. 清进程内信道密钥缓存与 DK 拆分状态；
 * 6. armed 启用过超时时复位写默认档（防连环雷管；写入触发新 keyset 生成）；
 * 7. 二次清扫 datastore 目录（旧实例在途写入可能复活已删文件，保留当前 ref）。
 * 验证器保留（门禁行为前后一致，不暴露"销毁发生过"），销毁幂等
 * （重复触发无副作用），每步独立容错。
 *
 * 层级例外（唯一向上引用点）：销毁必须停业务服务（共享/守护进程/
 * 悬浮窗），因此本类引用 wrappers.DaemonManager/ScreenShareManager/
 * OverlayServiceManager——这是 defense 包对业务层唯一的依赖方向，
 * 新增引用需先在此文档化理由。
 */
object DefenseProtocol {

    // 检查/销毁互斥锁：与 IdleWatchdog.checkIdleExpired 的判定路径共享
    private val checkMutex = Mutex()

    // 有界步骤的兜底执行作用域：超时后任务继续在后台线程完成（exec 的
    // waitFor 不可中断），销毁序列不再等待——见 runBounded
    private val boundedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context

    /**
     * 有界执行：正常情况等到完成；超时（exec 挂起：root 授权弹窗、
     * shell 无响应等）则放弃等待、销毁序列继续推进。
     *
     * 为什么必须有界：无头销毁路径（Boot/AlarmReceiver 的 goAsync）只有
     * ~10s 广播超时预算，而 Auxiliary.exec 在 root 设备上可能因 su 授权
     * 弹窗挂起数十秒——若无界等待，进程会在"删 Keystore 条目"（密码学
     * 销毁的关键步骤）之前被 ANR 杀掉，重启后无头销毁恰好失效。
     * 停共享/停 daemon 是尽力而为的清理（daemon 侧另有独立死线自毁），
     * 排序上让位于 Keystore 删除的确定性。
     */
    private suspend fun runBounded(timeoutMs: Long, block: suspend () -> Unit) {
        val job = boundedScope.launch { runCatching { block() } }
        withTimeoutOrNull(timeoutMs) { job.join() }
    }

    /**
     * defense 组件统一初始化（替代旧 EncryptManager.init + GateManager.init）。
     * KeyVault 必须最先（其余组件的密钥操作以其迁移恢复为前置）。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        KeyVault.init(context)
        GateManager.init(context)
        IdleWatchdog.init(context)
    }

    /**
     * 公开销毁入口（胁迫解锁 / 注入检测命中时调用）：加锁执行完整序列。
     *
     * NonCancellable：GatePage 曾把销毁跑在 rememberCoroutineScope 上——
     * 销毁序列含 ~6.5s 有界等待，期间 Activity 重建（旋转/深色模式切换/
     * 折叠屏展开）即取消协程，中断发生在步骤 3（删 Keystore）之前时
     * 销毁完全未发生，而该次胁迫输入已被消费：用户以为已销毁，密文
     * 实际完好且后续检查判定"状态合法"永不补发——胁迫功能被静默废除。
     * 全调用方（GatePage/MainActivity/Application）统一在此处包裹，
     * 调用方作用域被取消只影响其后的 UI 回调，不影响销毁本身。
     * （runBounded 的 withTimeoutOrNull 在 NonCancellable 上下文中
     * 依然生效：超时取消的是其自建的 TimeoutCoroutine 子作用域）
     */
    suspend fun destroyForCoercion() {
        withContext(NonCancellable) {
            checkMutex.withLock { destroyForCoercionLocked() }
        }
    }

    /**
     * 检查路径互斥入口：已有检查/销毁在途时返回 null（调用方放行——
     * 在途操作读取同一状态，其判定覆盖本次）。
     */
    suspend fun <T> tryWithDestroyLock(action: suspend () -> T): T? {
        if (!checkMutex.tryLock()) return null
        try {
            return action()
        } finally {
            checkMutex.unlock()
        }
    }

    /**
     * 已持锁的销毁执行（IdleWatchdog 判定命中时在同一临界区内直接调用，
     * 避免锁内重入死锁）。调用方必须已持有 [checkMutex]。
     */
    internal suspend fun destroyForCoercionLocked() = withContext(Dispatchers.IO) {
        // 0. wipe 前快照（修复：旧实现在 wipe 后判定，恒为 no-op）
        val wasIdleActivated = runCatching { IdleWatchdog.isIdleActivated() }.getOrDefault(false)

        // 1. 停 app 侧共享（有界：内部 exec 在 root 授权弹窗等情形会挂起，
        //    无界等待会吃尽 receiver 的广播超时预算，见 runBounded）
        runBounded(3000L) { runCatching { ScreenShareManager.stopScreenShare() } }

        // 1.5 停 overlay 悬浮窗：root 路线宿主进程（su app_process/Shizuku
        //     UserService）独立于 app 进程存续——不在此停止则胁迫销毁完成后
        //     悬浮窗继续显示、GestureInputMonitor 输入监视通道继续运行、
        //     FGS 状态仍上报"运行中"，用户以为已销毁而展示/监视未停。
        //     stop 走 binder/stopService（root 路线同步 unbind + destroy 帧，
        //     普通路线 stopService），无 exec 挂起面，无需有界包装；未启动
        //     时为幂等 no-op（stopService 对未运行服务无副作用）
        runCatching { OverlayServiceManager.stop(appContext) }

        // 2. 停守护进程（purge：顺带清扫 app 侧共享——app 侧清理依赖 shell
        //    特权，Shizuku 断连时由持特权的 daemon 兜底；stop 后其自身完成
        //    tmp 明文/锚点/自拷贝清理）。同样有界（同步骤 1 理由）
        runBounded(3500L) { runCatching { DaemonManager.stopDaemon(purge = true) } }

        // 3. 删 Keystore 条目——密码学擦除优先于文件删除：
        // 此步完成后即使后续删除全部失败，所有密文在数学上已不可恢复
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("tink_master_key")
            keyStore.deleteEntry("hardware_encryption_key")
        }

        // 4. 删密文文件（tink keyset 与硬件 DK；pepper 条目保留——
        //    验证器保留语义要求 pepper 跨销毁存活）
        runCatching { appContext.deleteSharedPreferences("tink_prefs") }
        runCatching { KeyVault.deleteKeyFile() }

        // 5. 删密文配置 + 轮换 DataStore 随机文件名（全目录清扫）
        runCatching { ConfigManager.resetForCoercion(appContext) }

        // 6. 清信道密钥缓存与 DK 拆分状态（后续走重新生成的 DK；
        //    拆分三键随销毁清除——验证器保留但密钥状态归零，与全新安装一致）
        runCatching { DaemonManager.clearCachedKey() }
        runCatching { KeyVault.resetSplitState() }

        // 7. 复位默认档（首次写入触发新 keyset 生成，与 Keystore 新主密钥配对）
        runCatching { IdleWatchdog.resetIdleAfterDestroy(wasIdleActivated) }

        // 8. 二次清扫：旧 DataStore 实例的在途写入可能在步骤 5 之后落盘复活旧文件，
        //    稍作等待后清除（保留当前 ref 指向的新文件）
        runCatching {
            kotlinx.coroutines.delay(500.milliseconds)
            ConfigManager.sweepDatastoreDir(appContext)
        }
    }
}
