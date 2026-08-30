package fake.screenshot.wrappers

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import androidx.core.content.edit

/**
 * 启动门禁：安全密码 + 胁迫密码（可选）两级验证。
 *
 * 验证器存放在独立明文 SharedPreferences（中性命名），仅存 PBKDF2 哈希与盐，
 * 派生参数与软件加密密钥（EncryptManager.deriveKey）完全独立——泄露验证器
 * 无法推出任何可用于解密文件的密钥。
 *
 * 胁迫密码命中时执行销毁序列：Keystore 密钥与全部密文清除、配置恢复默认值；
 * 验证器本身保留——门禁行为前后一致，不暴露"销毁发生过"。
 */
object GateManager {
    private const val PREFS_NAME = "sync_preferences"
    private const val KEY_SECURITY_HASH = "token_hash"
    private const val KEY_SECURITY_SALT = "token_seed"
    private const val KEY_COERCION_HASH = "backup_hash"
    private const val KEY_COERCION_SALT = "backup_seed"

    private const val PBKDF2_ITERATIONS = 200000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16

    private lateinit var appContext: Context

    // 进程内已解锁标记：配置变更（旋转等）重建 Activity 时不重复弹门禁
    @Volatile
    var sessionUnlocked = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun markUnlocked() {
        sessionUnlocked = true
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isGateEnabled(): Boolean = prefs().contains(KEY_SECURITY_HASH)

    private fun deriveVerifier(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private fun verify(keyHash: String, keySalt: String, password: String): Boolean {
        val hash = prefs().getString(keyHash, null)?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        } ?: return false
        val salt = prefs().getString(keySalt, null)?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        } ?: return false
        return MessageDigest.isEqual(hash, deriveVerifier(password, salt))
    }

    suspend fun verifyGate(password: String): GateResult = withContext(Dispatchers.Default) {
        when {
            verify(KEY_SECURITY_HASH, KEY_SECURITY_SALT, password) -> GateResult.SECURITY
            verify(KEY_COERCION_HASH, KEY_COERCION_SALT, password) -> GateResult.COERCION
            else -> GateResult.INVALID
        }
    }

    /**
     * 校验门禁密码（安全或胁迫任一命中即通过）。
     * 设置页"当前密码"必须与启动门禁行为一致——两级密码都接受，
     * 否则胁迫者用刚解锁的密码在"当前密码"处得到"密码错误"，
     * 反而暴露存在两种密码。
     */
    suspend fun verifyGatePassword(password: String): Boolean = withContext(Dispatchers.Default) {
        verify(KEY_SECURITY_HASH, KEY_SECURITY_SALT, password) ||
                verify(KEY_COERCION_HASH, KEY_COERCION_SALT, password)
    }

    suspend fun setPasswords(security: String, coercion: String) =
        withContext(Dispatchers.Default) {
            prefs().edit {
                val securitySalt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
                putString(
                    KEY_SECURITY_SALT,
                    Base64.encodeToString(securitySalt, Base64.DEFAULT)
                )
                putString(
                    KEY_SECURITY_HASH,
                    Base64.encodeToString(deriveVerifier(security, securitySalt), Base64.DEFAULT)
                )
                if (coercion.isEmpty()) {
                    remove(KEY_COERCION_HASH).remove(KEY_COERCION_SALT)
                } else {
                    val coercionSalt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
                    putString(
                        KEY_COERCION_SALT,
                        Base64.encodeToString(coercionSalt, Base64.DEFAULT)
                    )
                    putString(
                        KEY_COERCION_HASH,
                        Base64.encodeToString(
                            deriveVerifier(coercion, coercionSalt),
                            Base64.DEFAULT
                        )
                    )
                }
            }
        }

    /** 移除门禁（仅清验证器条目，不动 data_index 等其他数据） */
    fun removeGate() {
        prefs().edit {
            remove(KEY_SECURITY_HASH).remove(KEY_SECURITY_SALT)
                .remove(KEY_COERCION_HASH).remove(KEY_COERCION_SALT)
        }
    }

    /**
     * 胁迫销毁序列，顺序严格：
     * 1. 先停守护进程（此时密钥/配置仍在，stop 依赖端口与信道密钥）；
     * 2. 删 Keystore 条目（Tink 主密钥、硬件密钥）与密文文件（keyset、硬件 DK）；
     * 3. 删除密文配置并推进 DataStore 代次（同进程重建走新文件路径，
     *    规避 "multiple DataStores active for the same file"）；
     * 4. 清进程内信道密钥缓存。
     * 验证器保留，销毁幂等（重复触发无副作用）。
     */
    suspend fun destroyForCoercion() = withContext(Dispatchers.IO) {
        runCatching { DaemonManager.stopDaemon() }

        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("tink_master_key")
            keyStore.deleteEntry("hardware_encryption_key")
        }

        appContext.deleteSharedPreferences("tink_prefs")
        File(appContext.filesDir, "hw_key.bin").delete()
        ConfigManager.resetForCoercion(appContext)
        DaemonManager.clearCachedKey()
    }
}

enum class GateResult { SECURITY, COERCION, INVALID }
