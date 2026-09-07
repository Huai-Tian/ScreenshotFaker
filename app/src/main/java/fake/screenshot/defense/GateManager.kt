package fake.screenshot.defense

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * L2 启动门禁编排器（瘦身后）：验证与密钥操作全部下沉 vault 进程
 * （VaultClient），本类只保留会话状态镜像与历史调用面兼容。
 *
 * 验证哲学变迁：三代验证器（v1 PBKDF2 比较 / v2 pepper / v3 解密式）
 * 与 pepper 体系随 KeyVault 一并退役——vault 内密码正确性 = GCM tag
 * 校验（密码学层，无可 hook 比较点），离线试密码无 oracle（拖库后
 * 既无验证器可算，也无 DK 包裹可离线解开——Argon2id 是唯一成本）。
 *
 * 胁迫语义（vault 内执行）：命中胁迫验证项 → vault 就地改写 sync_key.bin
 * 为销毁态（DK 立即密码学死亡，Java 层被拦截也已完成）→ 返回 COERCION
 * 由 GatePage 触发完整销毁序列。验证项跨销毁保留——门禁行为前后一致。
 *
 * sessionUnlocked / gateEnabled 是 VaultClient 状态镜像的同步视图：
 * - gateEnabled：RPC 后刷新的缓存（init 后首次访问前可能为 false；
 *   冷启动判定以文件级事实为准的调用方见 [VaultClient.status]）
 * - sessionUnlocked：解锁置位、锁定/vault 死亡复位
 */
object GateManager {

    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 会话是否已解锁（vault DK 就绪的镜像；无门禁用户恒 false 但无意义） */
    val sessionUnlocked: Boolean
        get() = VaultClient.sessionUnlocked

    /** 门禁是否启用（同步缓存视图） */
    fun isGateEnabled(): Boolean = VaultClient.gateEnabled

    /** 挂起版（刷新缓存；冷启动判定等精确场景用） */
    suspend fun isGateEnabledFresh(): Boolean = VaultClient.status()?.gateOn ?: VaultClient.gateEnabled

    /**
     * 解锁（= 验证 + DK 组装，一次 Argon2id 在 vault 内完成）。
     * 成功（含胁迫路径）即会话解锁——DK 就绪是 vault 侧事实。
     */
    suspend fun unlock(password: String): VaultClient.UnlockResult =
        VaultClient.unlock(password)

    /**
     * 会话锁定（息屏/后台/前台无操作触发）：vault 清 DK，收窄驻留窗口。
     * 无门禁用户 DK 常驻 vault 为已声明语义（等价旧单段模式），锁定对其
     * 无意义——vault 侧跳过。
     */
    fun lockSession() {
        // 无返回值路径（BroadcastReceiver 等非挂起上下文）：fire-and-forget
        runCatching {
            CoroutineScope(Dispatchers.IO).launch { VaultClient.lock() }
        }
    }
}
