package fake.screenshot.defense

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fake.screenshot.Auxiliary
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.EncryptManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.crypto.spec.SecretKeySpec

/**
 * L1 敏感字段层：SSH 凭据/共享密码等基础设施凭据的 DK 第二层加密。
 *
 * 威胁：root 冒充 app uid 可用 Keystore 主密钥解密 DataStore（Tink 层对
 * root-as-uid 无防护），凭据全文暴露。本层将敏感字段以 DK 包裹后再入
 * DataStore：root 拿到密文也缺 B（安全密码派生份），与 DK 拆分的防护
 * 语义一致。
 *
 * 存储：DataStore "<key>_sec" = Base64(nonce + ciphertext)；旧明文 key
 * 保留读取兼容并在首次有 DK 的读取时迁移（迁移后旧值清空）。
 * 已知窗口：升级后用户解锁一次之前，旧明文值仍以原样存在（与升级前
 * 的暴露面一致，不劣化）。
 *
 * 无状态组件：DK 经 [KeyVault]（已 init）获取，存储经 [ConfigManager]
 * （调用方传 context），自身无需 init。
 */
object SensitiveStore {
    private const val SEC_SUFFIX = "_sec"
    private const val NONCE_LENGTH = 12

    /**
     * SSH 主机密钥指纹的存储键（TOFU）。按 host:port 隔离：更换服务器
     * 地址/端口各自独立进行首次信任，互不影响（换回旧服务器仍按旧指纹
     * 校验）。地址字符集受 UI 校验限制（字母数字 . -），可安全拼入键名。
     * 纪元键（ssh_hostkey_epoch，ConfigManager 明文存储）：设置页"重置
     * 指纹"时自增，经 config 下发——daemon 侧据此丢弃其本地缓存的旧纪元
     * 条目，否则换钥后 daemon 仍按旧指纹拒绝（app 重置而 daemon 不知情）
     */
    fun sshHostKeyStoreKey(address: String, port: Int): String =
        "ssh_host_key_" + address + "_" + port


    /** DK 可用时解密敏感字段；不可用（锁定态）或密文损坏 → null */
    private fun decryptSensitiveOrNull(blob: String): String? {
        val dk = KeyVault.getDaemonKeyOrNull() ?: return null
        return runCatching {
            val data = Base64.decode(blob, Base64.NO_WRAP)
            if (data.size <= NONCE_LENGTH) return@runCatching null
            val plain = EncryptManager.decryptBytesByPassword(
                dk, data.copyOfRange(0, NONCE_LENGTH),
                data.copyOfRange(NONCE_LENGTH, data.size)
            )
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 一次性读取敏感字段：
     * - _sec 存在 → DK 解密（失败回退 default，fail-closed）
     * - _sec 不存在且旧明文存在 → 有 DK 则就地迁移（写 _sec + 清旧值），
     *   无 DK（锁定态/升级后未解锁）返回旧明文——兼容窗口，暴露面与升级前一致
     */
    suspend fun getSensitive(context: Context, key: String, default: String): String {
        val sec = runCatching {
            ConfigManager.getDataOnce(context, key + SEC_SUFFIX, "")
        }.getOrDefault("")
        if (sec.isNotEmpty()) {
            // 部分迁移兜底：_sec 已就位但旧明文因中途崩溃残留 → 顺手清除
            val legacyLeft = runCatching {
                ConfigManager.getDataOnce(context, key, "")
            }.getOrDefault("")
            if (legacyLeft.isNotEmpty()) {
                runCatching { ConfigManager.saveData(context, key, "") }
            }
            return decryptSensitiveOrNull(sec) ?: default
        }
        val legacy = runCatching {
            ConfigManager.getDataOnce(context, key, default)
        }.getOrDefault(default)
        if (legacy.isNotEmpty() && legacy != default &&
            KeyVault.getDaemonKeyOrNull() != null
        ) {
            runCatching { putSensitive(context, key, legacy) }
        }
        return legacy
    }

    /**
     * 写入敏感字段（DK 包裹）。DK 不可用（锁定/组装失败）→ false（fail-closed，
     * 不降级为明文）。调用方均在解锁后的 UI 上下文，正常路径恒可用
     */
    suspend fun putSensitive(context: Context, key: String, value: String): Boolean {
        val dk: SecretKeySpec = KeyVault.getDaemonKeyOrNull() ?: return false
        return runCatching {
            if (value.isEmpty()) {
                // 清空 = 未配置语义：直接写空串（而非加密空 blob——密文空串
                // 非空，isSensitiveConfigured 会误判"已配置"，app 侧共享
                // fail-closed 永久拒绝、daemon 侧无鉴权启动，两侧行为分裂）
                ConfigManager.saveData(context, key + SEC_SUFFIX, "")
            } else {
                val (nonce, ct) = EncryptManager.encryptBytesByPassword(
                    dk, value.toByteArray(Charsets.UTF_8)
                )
                ConfigManager.saveData(
                    context, key + SEC_SUFFIX,
                    Base64.encodeToString(nonce + ct, Base64.NO_WRAP)
                )
            }
            // 旧明文清空（DataStore 无删除单键 API，写空即抹除明文值）
            ConfigManager.saveData(context, key, "")
            true
        }.getOrDefault(false)
    }

    /**
     * 首装默认共享密码兜底（幂等；DK 可用时才可能成功写入）。
     *
     * 默认配置下共享认证关闭 = 局域网任意设备可观看/控制/读写剪贴板，
     * 故首装即生成随机高强度密码（DK 加密存本表）——默认值必须是
     * "有密码"；允许无密码是用户的显式决策。
     *
     * 调用点（两处互补）：
     * - LSPosedServiceManager.onCreate：无门禁/未拆分用户冷启动即成功；
     *   有门禁用户冷启动处于锁定态（DK 不可用，putSensitive fail-closed
     *   失败）
     * - GatePage 安全密码解锁后：DK 已组装，锁定态失败的那次在此补跑
     *
     * 三重防覆盖（任一命中即不生成）：
     * - _sec 已配置（非空密文）：既有密码（含显式设置/迁移完成态）
     * - 旧明文仍有值：升级用户既存密码（DK 可用时 getSensitive 顺手
     *   完成迁移）——随机密码静默覆盖会让既有连接全部认证失败
     * - 生成标记为 true：首次成功生成后写入；用户此后显式清空密码
     *   （putSensitive("") 抹掉 _sec = isConfigured 归零）是主动选择
     *   无密码，重启/解锁不得复活随机密码（重新上密码走 UI 手输）
     */
    suspend fun ensureDefaultSharePassword(context: Context) {
        runCatching {
            if (isSensitiveConfigured(context, "screenShare_password")) return
            if (getSensitive(context, "screenShare_password", "").isNotEmpty()) return
            if (ConfigManager.getDataOnce(context, "screenShare_password_generated", false)) return
            val pwd = Auxiliary.getStrongPassword(Auxiliary.getSecureRandomInt(14..18))
            if (putSensitive(context, "screenShare_password", pwd)) {
                // 标记仅在成功落库后写入：锁定态失败不置位，解锁补跑仍生效
                ConfigManager.saveData(context, "screenShare_password_generated", true)
            }
        }
    }

    /**
     * 敏感字段是否已配置（_sec 密文存在，不尝试解密）。
     * 用于 fail-closed 判定：密文存在而解密不可得（锁定态）= 有配置但
     * 本会话不可用——调用方必须拒绝相应功能而非静默降级为"未配置"
     */
    suspend fun isSensitiveConfigured(context: Context, key: String): Boolean =
        runCatching {
            ConfigManager.getDataOnce(context, key + SEC_SUFFIX, "")
        }.getOrDefault("").isNotEmpty()

    /** 敏感字段响应式流（与 ConfigManager.rememberValue 同形：初值 default，异步发射解密值） */
    fun sensitiveFlow(context: Context, key: String, default: String): Flow<String> =
        ConfigManager.getData(context, key + SEC_SUFFIX, "").map { sec ->
            if (sec.isNotEmpty()) {
                decryptSensitiveOrNull(sec) ?: default
            } else {
                // 未迁移：回退旧明文保显示连续性（迁移由 getSensitive/putSensitive 完成）
                runCatching { ConfigManager.getDataOnce(context, key, default) }
                    .getOrDefault(default)
            }
        }

    @Composable
    fun rememberSensitiveValue(context: Context, key: String, default: String): State<String> =
        sensitiveFlow(context, key, default)
            .collectAsStateWithLifecycle(initialValue = default)
}
