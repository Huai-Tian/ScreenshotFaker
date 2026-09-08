package fake.screenshot.defense

import android.content.Context
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.DaemonManager
import fake.screenshot.wrappers.OverlayServiceManager
import fake.screenshot.wrappers.ScreenShareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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
 * 销毁序列（两阶段，按路径分叉；验证器保留——门禁行为前后一致，
 * 销毁幂等，每步独立容错）：
 *
 * **全量路径**（注入检测/超时销毁——无观演者，完整性优先）：
 * 1. 置共享销毁闩锁；
 * 2. 停进程清理三项并行（共享/overlay/daemon，各有界 3s/3.5s——
 *    串行 6.5s 压缩到 ~3.5s，无头 goAsync ~10s 预算内到达 Keystore
 *    删除的余量翻倍；daemon 仍在 vault 销毁之前：信道干净 stop
 *    依赖 vault 内存 DK）；
 * 3. vault 层销毁（OP_DESTROY 清 DK/CK + 删 WK 包裹；胁迫路径下
 *    第一层已在解锁命中时就地完成）；
 * 4-6. 删 Keystore 条目/密文文件/密文配置（密码学擦除优先于文件
 *    删除）+ DataStore 文件名轮换（规避 "multiple DataStores active
 *    for the same file"，不暴露销毁史）；
 * 7. 复位默认档（始终武装：写锚点 + 默认 6 个月；写入触发新 keyset）；
 * 8. 二次清扫 datastore 目录（旧实例在途写入可能复活已删文件）。
 *
 * **演出路径**（胁迫解锁 keepVaultSession——时序敏感，胁迫者在场
 * 盯着解锁耗时）：关键路径只含本地操作（~0.5s：闩锁 → 停 overlay →
 * wrap 清扫 → Keystore/密文/配置擦除 → 默认档复位 → 默认共享密码
 * 兜底 → 闩锁解除），**解锁总耗时与正常解锁不可区分**；停共享/停
 * daemon/二次清扫后台并行（boundedScope 独立生命周期）。旧顺序
 * 约束"daemon stop 先于 vault 销毁"在本路径自然失效：DK 已随重生
 * 换代，信道 stop 必败、pkill 兜底是唯一路径，擦除先行不破坏语义。
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
     * defense 组件统一初始化（幂等，多入口重复调用无害）。
     * VaultClient 最先（库加载；vault 进程懒 spawn）。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        VaultClient.init(context)
        GateManager.init(context)
        IdleWatchdog.init(context)
    }

    /**
     * 公开销毁入口（胁迫解锁 / 注入检测命中时调用）：加锁执行完整序列。
     *
     * keepVaultSession（默认 false）：胁迫演出路径（GatePage）置 true——
     * vault 已在解锁命中时就地重生（旧 DK 孤儿化 + 新 DK 会话）。本路径
     * 两阶段：擦除关键路径（本地操作 ~0.5s：停 overlay/wrap 清扫/
     * Keystore/密文/配置/默认共享密码/闩锁解除）同步完成后即返回——
     * **解锁总耗时与正常解锁不可区分**（慢解锁本身即穿帮信号）；
     * 慢速进程清理（停共享/停 daemon/二次清扫）后台并行，不阻塞 UI。
     * 注入检测/超时销毁路径不置（会话已死或用户自毁，无观演者——
     * 完整性优先：清理三项并行后仍全量等待）。
     *
     * NonCancellable：GatePage 曾把销毁跑在 rememberCoroutineScope 上——
     * 销毁关键路径虽已压缩，期间 Activity 重建（旋转/深色模式切换/
     * 折叠屏展开）仍会取消协程，中断发生在 Keystore 删除之前时
     * 销毁完全未发生，而该次胁迫输入已被消费：用户以为已销毁，密文
     * 实际完好且后续检查判定"状态合法"永不补发——胁迫功能被静默废除。
     * 全调用方（GatePage/MainActivity/Application）统一在此处包裹，
     * 调用方作用域被取消只影响其后的 UI 回调，不影响销毁本身。
     * （runBounded 的 withTimeoutOrNull 在 NonCancellable 上下文中
     * 依然生效：超时取消的是其自建的 TimeoutCoroutine 子作用域）
     */
    suspend fun destroyForCoercion(keepVaultSession: Boolean = false) {
        withContext(NonCancellable) {
            checkMutex.withLock { destroyForCoercionLocked(keepVaultSession) }
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
     * 避免锁内重入死锁；默认全量销毁）。调用方必须已持有 [checkMutex]。
     *
     * keepVaultSession=true 走演出路径（时序敏感）：擦除关键路径同步
     * （~0.5s 本地操作），慢速进程清理后台并行；false 走全量路径
     * （完整性优先）：清理三项并行后全量等待。两路径的密码学擦除
     * 步骤与语义完全一致。
     */
    internal suspend fun destroyForCoercionLocked(keepVaultSession: Boolean = false) =
        withContext(Dispatchers.IO) {
            // -1. 置共享销毁闩锁（必须先于一切步骤）：磁贴触发的共享启动协程
            //     与本序列并发时，其已快照的旧凭据可能在清理的 pkill/rm
            //     因特权断连失效后仍拉起 server（"已销毁"后旧密码继续推流）。
            //     闩锁使该协程在拉起前复查点强制放弃（见 ScreenShareManager
            //     coercionDestroyed 注释）
            runCatching { ScreenShareManager.markCoercionDestroyed() }

            if (keepVaultSession) {
                // ===================== 演出路径（时序敏感）=====================
                // 关键路径 = 密码学擦除 + 状态复位 + 演出收尾，全部本地操作
                // （~0.5s）——胁迫者看到的解锁耗时与正常解锁不可区分。
                // 旧实现串行等待停共享/停 daemon（有界等待最长 ~6.5s）：
                // 慢解锁本身就是穿帮信号

                // 停 overlay 悬浮窗（binder/stopService，无 exec 挂起面）：
                // 悬浮窗在场 = "全新 app"演出破绽，必须在 UI 进入前消失
                runCatching { OverlayServiceManager.stop(appContext) }

                // vault 层：重生会话保留（OP_DESTROY 会杀死它 = 功能全废
                // 穿帮），仅清 app 侧 wrap 文件
                runCatching { VaultClient.destroyWrapFileOnly() }

                // 删 Keystore 条目——密码学擦除优先于文件删除。有界包裹：
                // 本路径它已是冗余防线（vault 重生已令旧 DK 死亡，密文随
                // 配置清扫删除），keystored binder 挂起不得拖慢关键路径，
                // 超时后后台继续完成
                runBounded(1500L) {
                    runCatching {
                        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        keyStore.deleteEntry("tink_master_key")
                        keyStore.deleteEntry("hardware_encryption_key")
                    }
                }

                // 删密文文件（tink keyset）+ 删密文配置并轮换 DataStore 文件名
                runCatching { appContext.deleteSharedPreferences("tink_prefs") }
                runCatching { ConfigManager.resetForCoercion(appContext) }

                // 复位默认档（真实首装 app 装机即写默认锚点——重生会话
                // 写入 = 全新 app 行为）
                runCatching { IdleWatchdog.resetIdleAfterDestroy() }

                // 演出收尾（顺序即安全）：默认共享密码兜底先行（共享的
                // fail-closed 门只拦"已配置但不可解"，拦不住"未配置" =
                // 无鉴权启动——缺位窗口内竞态 toggle 会拉起无鉴权 server），
                // 密码就位后才解除共享闩锁；兜底失败保持 fail-closed
                // （共享被拒的演出代价 < 无鉴权推流的安全代价）。旧纪元
                // relay 协程由 destroyEpoch 复查继续封堵
                val sharePwReady = runCatching {
                    SensitiveStore.ensureDefaultSharePassword(appContext)
                    SensitiveStore.isSensitiveConfigured(appContext, "screenShare_password")
                }.getOrDefault(false)
                if (sharePwReady) {
                    runCatching { ScreenShareManager.clearCoercionDestroyedForRebirth() }
                }

                // ===== 后台慢速清理（不阻塞 UI 进入）：boundedScope 独立于
                // 调用方生命周期（GatePage 协程随 Activity 重建被取消也不
                // 中断），各项互不等待、并行执行，exec/socket 各自内置
                // 超时防挂死。停共享最先启动（释放 relay 端口——晚到的
                // 共享启动不撞端口）。旧顺序约束"daemon stop 先于 vault
                // 销毁"在本路径自然失效：DK 已换代，信道 stop 必败，
                // pkill 兜底是唯一路径——擦除先行不破坏任何语义 =====
                boundedScope.launch {
                    runCatching { ScreenShareManager.stopScreenShare() }
                }
                boundedScope.launch {
                    runCatching { DaemonManager.stopDaemon(purge = true) }
                }
                boundedScope.launch {
                    runCatching {
                        kotlinx.coroutines.delay(500.milliseconds)
                        ConfigManager.sweepDatastoreDir(appContext)
                    }
                }
                return@withContext
            }

            // ===================== 全量路径（完整性优先）=====================
            // 停进程清理三项并行：串行 6.5s 有界等待压缩到 ~3.5s——无头
            // goAsync ~10s 预算内到达 Keystore 删除的余量翻倍（ANR 先杀
            // 窗口减半）。停 daemon 仍在 vault 销毁之前：全量路径 DK 尚在，
            // 信道干净 stop 优先于 pkill 兜底
            coroutineScope {
                // 停 app 侧共享（有界：内部 exec 在 root 授权弹窗等情形会挂起）
                launch { runBounded(3000L) { runCatching { ScreenShareManager.stopScreenShare() } } }
                // 停 overlay：root 路线宿主进程独立于 app 进程存续；binder/
                // stopService 无 exec 挂起面，无需有界包装；未启动时幂等 no-op
                launch { runCatching { OverlayServiceManager.stop(appContext) } }
                // 停守护进程（purge：顺带清扫 app 侧共享——app 侧清理依赖
                // shell 特权，Shizuku 断连时由持特权的 daemon 兜底；stop 后
                // 其自身完成 tmp 明文/锚点/自拷贝清理）。同样有界
                launch { runBounded(3500L) { runCatching { DaemonManager.stopDaemon(purge = true) } } }
            }

            // vault 层销毁：清 DK/CK 内存、删 sync_wrap.bin（WK 包裹）；
            // vault 不在则等价（进程不在 = 内存无密钥）。验证项（sync_key.bin
            // 的门禁条目）保留——门禁行为前后一致
            runBounded(1000L) { runCatching { VaultClient.destroy() } }

            // 删 Keystore 条目——密码学擦除优先于文件删除：
            // 此步完成后即使后续删除全部失败，所有密文在数学上已不可恢复
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keyStore.deleteEntry("tink_master_key")
                keyStore.deleteEntry("hardware_encryption_key")
            }

            // 删密文文件（tink keyset；DK 文件已由 vault 层处理）
            runCatching { appContext.deleteSharedPreferences("tink_prefs") }

            // 删密文配置 + 轮换 DataStore 随机文件名（全目录清扫）
            runCatching { ConfigManager.resetForCoercion(appContext) }

            // 复位默认档（始终武装：无 wasActivated 快照——见 IdleWatchdog）
            runCatching { IdleWatchdog.resetIdleAfterDestroy() }

            // 二次清扫：旧 DataStore 实例的在途写入可能在配置轮换之后落盘
            // 复活旧文件，稍作等待后清除（保留当前 ref 指向的新文件）
            runCatching {
                kotlinx.coroutines.delay(500.milliseconds)
                ConfigManager.sweepDatastoreDir(appContext)
            }
        }
}
