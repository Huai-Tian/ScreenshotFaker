package fake.screenshot.wrappers

import android.content.Context
import android.os.SystemClock
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

    // 未使用自动销毁（TG 账号超时销毁式，独立于门禁）：
    // armed 哨兵（明文，永不清除）+ idle_limit/idle_ts（密文 DataStore）
    private const val KEY_ARMED = "armed"
    private const val CONFIG_KEY_IDLE_LIMIT = "idle_limit"
    private const val CONFIG_KEY_IDLE_TS = "idle_ts"

    // 超时销毁默认档：6 个月（分钟）。首次启用/销毁后复位都用此值
    private const val DEFAULT_IDLE_LIMIT_MINUTES = 259200L

    // 双锚点自洽容差：同一开机内 (er-er0) 与 (wc-wc0) 偏差超过此值 = 非法
    private const val ANCHOR_DRIFT_TOLERANCE_MS = 10 * 60 * 1000L

    // wc0 合理性区间下限（2020-01-01），防垃圾值
    private const val WC0_MIN = 1_577_836_800_000L

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

    /** 移除门禁（仅清验证器条目，不动 armed/idle——超时销毁是独立功能） */
    fun removeGate() {
        prefs().edit {
            remove(KEY_SECURITY_HASH).remove(KEY_SECURITY_SALT)
                .remove(KEY_COERCION_HASH).remove(KEY_COERCION_SALT)
        }
    }

    // ===================== 未使用自动销毁 =====================

    /** 可选档位（分钟）：5分钟 ~ 12个月，无禁用项 */
    val idleTimeoutOptions: List<Long> = listOf(
        5L, 30L, 60L, 360L, 1440L, 10080L,
        43200L, 129600L, 259200L, 525600L
    )

    fun isIdleArmed(): Boolean = prefs().getBoolean(KEY_ARMED, false)

    /**
     * 冷启动超时判定（在门禁验证与一切配置加载之前调用）。
     * 读到任何不合法状态 → 雷管引爆（fail-destroy）。
     *
     * @return true = 已触发销毁（调用方无需额外处理，销毁含复位）
     */
    suspend fun checkIdleExpired(): Boolean {
        if (!isIdleArmed()) return false

        val limitMinutes = ConfigManager.getDataOnce(
            appContext, CONFIG_KEY_IDLE_LIMIT, 0L
        )
        val tsRaw = ConfigManager.getDataOnce(
            appContext, CONFIG_KEY_IDLE_TS, ""
        )

        // ---- 合法性判定：任一不满足 = 雷管 ----
        if (limitMinutes <= 0 || limitMinutes !in idleTimeoutOptions) {
            destroyForCoercion()
            return true
        }

        val parts = tsRaw.split(",")
        if (parts.size != 2) {
            destroyForCoercion()
            return true
        }
        val er0 = parts[0].toLongOrNull()
        val wc0 = parts[1].toLongOrNull()
        if (er0 == null || wc0 == null || er0 < 0 || wc0 < WC0_MIN) {
            destroyForCoercion()
            return true
        }

        val er = SystemClock.elapsedRealtime()
        val wc = System.currentTimeMillis()
        val limitMs = limitMinutes * 60_000L

        // er 倒退（单调时钟被伪造）= 非法
        if (er < er0) {
            // 重启场景：er 回绕，转 wall clock 判定
            if (wc < wc0) {
                // wall 时钟倒退 = 回拨 = 非法
                destroyForCoercion()
                return true
            }
            if (wc - wc0 >= limitMs) {
                destroyForCoercion()
                return true
            }
        } else {
            // 同一开机：双锚点交叉校验（只改其一会被捕获）
            val erDiff = er - er0
            val wcDiff = wc - wc0
            if (wcDiff < 0 || kotlin.math.abs(erDiff - wcDiff) > ANCHOR_DRIFT_TOLERANCE_MS) {
                destroyForCoercion()
                return true
            }
            if (erDiff >= limitMs) {
                destroyForCoercion()
                return true
            }
        }
        return false
    }

    /**
     * 刷新计时锚点（写入双锚点）。只在"有效使用"时调用：
     * 无门禁冷启动判定后 / 门禁验证通过后 / onStart 回前台 / 10s 心跳。
     *
     * 有门禁且本会话未验证通过时拒绝刷新——未验证的打开（含冷启动后的
     * 首个 onStart）对计时器透明，防止胁迫者在门禁页停留/反复打开续命。
     */
    suspend fun touchIdle() {
        if (!isIdleArmed()) return
        if (isGateEnabled() && !sessionUnlocked) return
        val ts = "${SystemClock.elapsedRealtime()},${System.currentTimeMillis()}"
        ConfigManager.saveData(appContext, CONFIG_KEY_IDLE_TS, ts)
    }

    /** 首次启用/修改档位：写哨兵 + 档位 + 当前锚点 */
    suspend fun setIdleTimeout(minutes: Long) {
        prefs().edit { putBoolean(KEY_ARMED, true) }
        ConfigManager.saveData(appContext, CONFIG_KEY_IDLE_LIMIT, minutes)
        touchIdle()
    }

    /** 当前档位（未启用返回 null，用于设置页副标题） */
    suspend fun getCurrentIdleTimeout(): Long? {
        if (!isIdleArmed()) return null
        val v = ConfigManager.getDataOnce(appContext, CONFIG_KEY_IDLE_LIMIT, 0L)
        return if (v > 0 && v in idleTimeoutOptions) v else null
    }

    /**
     * 销毁后复位：防连环雷管自毁循环（销毁清空 DataStore → 读默认 0 → 再引爆）。
     * 写默认档 + 当前锚点，计时器自愈。armed 永不清除。
     */
    private suspend fun resetIdleAfterDestroy() {
        if (!isIdleArmed()) return
        ConfigManager.saveData(
            appContext, CONFIG_KEY_IDLE_LIMIT, DEFAULT_IDLE_LIMIT_MINUTES
        )
        val ts = "${SystemClock.elapsedRealtime()},${System.currentTimeMillis()}"
        ConfigManager.saveData(appContext, CONFIG_KEY_IDLE_TS, ts)
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
        // 超时销毁复位：armed 存在时写默认档防连环雷管（幂等，未启用则跳过）
        resetIdleAfterDestroy()
    }
}

enum class GateResult { SECURITY, COERCION, INVALID }
