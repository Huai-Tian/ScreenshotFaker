package fake.screenshot.defense

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import fake.screenshot.wrappers.DaemonManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * L2 启动门禁验证器：安全密码 + 胁迫密码（可选）两级验证 + 会话状态。
 *
 * 只负责"验证与凭据管理"：超时销毁判定见 [IdleWatchdog]，销毁序列
 * 见 [DefenseProtocol]（胁迫命中由调用方转交执行）。
 *
 * 验证器存放在独立明文 SharedPreferences（中性命名），仅存 PBKDF2
 * 哈希与盐，派生参数与软件加密密钥（EncryptManager.deriveKey）完全
 * 独立——泄露验证器无法推出任何可用于解密文件的密钥。
 *
 * 胁迫密码命中时由调用方（GatePage）执行 [DefenseProtocol.destroyForCoercion]
 * 销毁序列；验证器本身保留——门禁行为前后一致，不暴露"销毁发生过"。
 *
 * 密码联动 DK 拆分（[KeyVault]）：设密/改密重拆、移除门禁解除拆分、
 * 解锁组装——本类是 KeyVault 拆分生命周期的唯一编排者。
 */
object GateManager {
    private const val PREFS_NAME = "sync_preferences"
    private const val KEY_SECURITY_HASH = "token_hash"
    private const val KEY_SECURITY_SALT = "token_seed"
    private const val KEY_COERCION_HASH = "backup_hash"
    private const val KEY_COERCION_SALT = "backup_seed"

    // ---- 验证器 v2（Keystore pepper 掺盐，封堵离线试密码）----
    // v1 hash（纯 PBKDF2）是离线 oracle：root 拷走 prefs 即可无限试密码。
    // v2 将 pepper 拼入盐——pepper 由 Keystore 不可导出密钥包裹，脱离
    // 设备无法验证任何猜测，试密码只能回到设备进行（与在线尝试同成本）。
    // v1 兼容：升级用户首次成功验证后自动迁移 v2 并删 v1。
    private const val KEY_SECURITY_HASH_V2 = "token_hash_v2"
    private const val KEY_COERCION_HASH_V2 = "backup_hash_v2"

    // ---- 验证器 v3（解密式验证，反硬件断点 hook）----
    // v1/v2 均为"计算哈希→比较"式：比较函数被断点 hook 成恒真（一行）
    // 门禁即被绕过。v3 消灭比较点：存 AES-GCM 密文（密钥 = PBKDF2
    // (password, salt+pepper)，明文 = 类型标记），验证 = 解密——GCM
    // tag 校验在密码学层（ARMv8 硬件指令），密码错误即解密失败。
    // 要伪造"验证通过"必须 hook Cipher 解密链——hook doFinal 恒成功
    // 会破坏全 app 加解密（Tink DataStore 立刻崩），无法针对性撒谎。
    // 解密成功的明文标记比较走 GuardManager 双实现交叉验证。
    // v1/v2 存量：首次成功验证自动迁移 v3。
    private const val KEY_SECURITY_HASH_V3 = "token_hash_v3"
    private const val KEY_COERCION_HASH_V3 = "backup_hash_v3"
    private const val V3_NONCE_LENGTH = 12
    private val V3_MARK_SECURITY = "SF-GATE-1".toByteArray(Charsets.UTF_8)
    private val V3_MARK_COERCION = "SF-GATE-2".toByteArray(Charsets.UTF_8)

    // armed 哨兵（明文，永不清除）：随验证器同一次 commit 写入——
    // 验证器存在而 armed 消失 = sync_preferences 被定向篡改 = 自毁
    // （判定在 IdleWatchdog）。键名与 IdleWatchdog 共有，冻结不变量
    private const val KEY_ARMED = "armed"

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

    /**
     * 会话锁定：清 DK 与解锁标记（DK 驻留窗口收窄——SIGSTOP 先手 dump
     * 的可利用窗口从"解锁后无限期"压缩到"前台活跃使用中"）。
     * 触发：息屏立即 / 后台 30s / 前台无操作 5min。
     * 磁贴与敏感功能经 KeyVault.isDaemonKeyReady/SensitiveStore
     * 自然 fail-closed；touchIdle 因未解锁拒绝续期——锁定对计时器透明，
     * 超时自毁不受影响。
     * 信道密钥缓存（DaemonManager.cachedKey）一并清除：它是 DK 的
     * SecretKeySpec 强引用副本，不清则锁定后整把 DK 仍以活跃引用驻留
     * 堆中（不属于"SecretKeySpec 副本受 GC 限制"的已声明边界——那指
     * 无引用后的残留，这是可达引用）
     */
    fun lockSession() {
        if (!sessionUnlocked) return
        sessionUnlocked = false
        KeyVault.clearAssembledKey()
        DaemonManager.clearCachedKey()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isGateEnabled(): Boolean =
        prefs().contains(KEY_SECURITY_HASH) || prefs().contains(KEY_SECURITY_HASH_V2) ||
                prefs().contains(KEY_SECURITY_HASH_V3)

    private fun deriveVerifier(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    /** v2 派生：pepper 拼入盐。pepper 未知则无法计算任何候选哈希 */
    private fun deriveVerifierV2(
        password: String, salt: ByteArray, pepper: ByteArray
    ): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(), salt + pepper, PBKDF2_ITERATIONS, KEY_LENGTH
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private fun decodePref(key: String): ByteArray? = prefs().getString(key, null)
        ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }

    /**
     * 验证（v3 解密式优先，v2/v1 兼容并自动迁移 v3）。
     *
     * v3：AES-GCM 解密验证——没有可被断点 hook 的关键比较点（GCM tag
     * 校验在密码学层）。v2/v1：哈希比较（存量过渡），比较走 native
     * 双实现交叉验证；命中后迁移 v3 并删旧键。
     *
     * v3/v2 存在而 pepper 不可用：fail-closed 返回 false（Keystore 全坏
     * 时数据本就不可解，行为一致）。
     */
    private fun verify(
        keyHash: String, keyHashV2: String, keyHashV3: String,
        keySalt: String, mark: ByteArray, password: String
    ): Boolean {
        val salt = decodePref(keySalt) ?: return false

        // v3：解密式（首选，无比较点可 hook）
        decodePref(keyHashV3)?.let { blob ->
            return verifyV3Blob(blob, password, salt, mark)
        }

        // v2：pepper 盐哈希比较（存量，命中迁 v3）
        val v2 = decodePref(keyHashV2)
        if (v2 != null) {
            val pepper = runCatching { KeyVault.getOrCreatePepper() }.getOrNull()
                ?: return false
            if (GuardManager.constantTimeEquals(v2, deriveVerifierV2(password, salt, pepper))) {
                migrateToV3(keyHash, keyHashV2, keyHashV3, password, salt, mark)
                return true
            }
            return false
        }

        // v1：纯 PBKDF2 比较（最早期存量，命中迁 v3）
        val v1 = decodePref(keyHash)
        if (v1 != null) {
            if (GuardManager.constantTimeEquals(v1, deriveVerifier(password, salt))) {
                migrateToV3(keyHash, keyHashV2, keyHashV3, password, salt, mark)
                return true
            }
            return false
        }
        return false
    }

    /** v3 验证：解密 blob，GCM tag 失败 = 密码错误/篡改 = 不命中 */
    private fun verifyV3Blob(
        blob: ByteArray, password: String, salt: ByteArray, mark: ByteArray
    ): Boolean {
        if (blob.size <= V3_NONCE_LENGTH) return false
        val pepper = runCatching { KeyVault.getOrCreatePepper() }.getOrNull()
            ?: return false
        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveVerifierV2(password, salt, pepper), "AES"),
                GCMParameterSpec(128, blob.copyOfRange(0, V3_NONCE_LENGTH))
            )
            cipher.doFinal(blob.copyOfRange(V3_NONCE_LENGTH, blob.size))
        }.getOrNull() ?: return false
        return GuardManager.constantTimeEquals(plain, mark)
    }

    /** v3 blob 生成（写与迁移共用）：nonce + AES-GCM(mark) */
    private fun makeV3Blob(
        password: String, salt: ByteArray, pepper: ByteArray, mark: ByteArray
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(deriveVerifierV2(password, salt, pepper), "AES")
                )
            }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(mark), Base64.DEFAULT)
    }

    /** v1/v2 命中后迁移 v3（pepper 不可用则跳过，下次验证再迁） */
    private fun migrateToV3(
        keyHash: String, keyHashV2: String, keyHashV3: String,
        password: String, salt: ByteArray, mark: ByteArray
    ) {
        runCatching {
            KeyVault.getOrCreatePepper()?.let { pepper ->
                prefs().edit(commit = true) {
                    putString(keyHashV3, makeV3Blob(password, salt, pepper, mark))
                    remove(keyHash).remove(keyHashV2)
                }
            }
        }
    }

    suspend fun verifyGate(password: String): GateResult = withContext(Dispatchers.Default) {
        when {
            verify(
                KEY_SECURITY_HASH, KEY_SECURITY_HASH_V2, KEY_SECURITY_HASH_V3,
                KEY_SECURITY_SALT, V3_MARK_SECURITY, password
            ) -> GateResult.SECURITY
            verify(
                KEY_COERCION_HASH, KEY_COERCION_HASH_V2, KEY_COERCION_HASH_V3,
                KEY_COERCION_SALT, V3_MARK_COERCION, password
            ) -> GateResult.COERCION
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
        verify(
            KEY_SECURITY_HASH, KEY_SECURITY_HASH_V2, KEY_SECURITY_HASH_V3,
            KEY_SECURITY_SALT, V3_MARK_SECURITY, password
        ) || verify(
            KEY_COERCION_HASH, KEY_COERCION_HASH_V2, KEY_COERCION_HASH_V3,
            KEY_COERCION_SALT, V3_MARK_COERCION, password
        )
    }

    /**
     * 设置/修改门禁密码。current 为当前密码（未启用门禁时忽略），
     * 验证由调用方（设置页）先行完成。
     * 密码联动 DK 拆分：已拆分 → 以 current 重组重拆（current 非安全密码
     * 时 DK 孤儿化 = 软销毁）；未拆分 → 激活拆分。
     *
     * 原子性（跨层窗口封堵）：验证器写入不再是独立 commit，而是作为
     * extraCommit 并入 KeyVault 迁移事务的**同一次** commit——"新验证器"
     * 与"新 DK 拆分态"原子落地。任何中断点（含进程死亡）经
     * recoverPendingMigration 回滚后两侧同为旧态，旧密码保持有效；
     * 旧实现"验证器先落盘、DK 迁移后落盘"的中断窗口会把用户锁死在
     * "新验证器 + 旧密码 DK"的不一致态（旧密码无处可验证 → 后续任何
     * 改密必然 DK 孤儿化）。
     *
     * @return true = 完成（含胁迫密码孤儿化路径：门禁可用，历史密钥
     * 产物软销毁）；false = 事务中止（验证器与 DK 均未动，改密失败，
     * 旧密码继续有效——失败优于数据孤儿化）
     */
    suspend fun setPasswords(current: String, security: String, coercion: String): Boolean =
        withContext(Dispatchers.Default) {
            // pepper 尽力而为：可用写 v3（解密式：无可 hook 比较点 + 离线
            // 不可试），不可用退 v1（不劣化于历史版本）。v3 blob 生成的
            // Cipher 调用同样可能因 Keystore 异常抛出（调用方协程未捕获
            // 即崩溃）——单独包裹，失败按无 pepper 退 v1
            val pepper = runCatching { KeyVault.getOrCreatePepper() }.getOrNull()
            val securitySalt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
            val coercionSalt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
            val securityBlob = pepper?.let {
                runCatching { makeV3Blob(security, securitySalt, it, V3_MARK_SECURITY) }.getOrNull()
            }
            val coercionBlob = coercion.takeIf { it.isNotEmpty() }?.let { pwd ->
                pepper?.let {
                    runCatching { makeV3Blob(pwd, coercionSalt, it, V3_MARK_COERCION) }.getOrNull()
                }
            }
            // 验证器写入块：随 DK 迁移事务同一次 commit 落地（见方法注释）
            val verifierCommit: android.content.SharedPreferences.Editor.() -> Unit = {
                // armed 随验证器同一次 commit 写入：设过密码的用户，
                // armed 消失即判篡改（见 IdleWatchdog）
                putBoolean(KEY_ARMED, true)
                putString(KEY_SECURITY_SALT, Base64.encodeToString(securitySalt, Base64.DEFAULT))
                if (securityBlob != null) {
                    putString(KEY_SECURITY_HASH_V3, securityBlob)
                    remove(KEY_SECURITY_HASH).remove(KEY_SECURITY_HASH_V2)
                } else {
                    putString(
                        KEY_SECURITY_HASH,
                        Base64.encodeToString(
                            deriveVerifier(security, securitySalt), Base64.DEFAULT
                        )
                    )
                    remove(KEY_SECURITY_HASH_V2).remove(KEY_SECURITY_HASH_V3)
                }
                if (coercion.isEmpty()) {
                    remove(KEY_COERCION_HASH).remove(KEY_COERCION_SALT)
                        .remove(KEY_COERCION_HASH_V2).remove(KEY_COERCION_HASH_V3)
                } else {
                    putString(KEY_COERCION_SALT, Base64.encodeToString(coercionSalt, Base64.DEFAULT))
                    if (coercionBlob != null) {
                        putString(KEY_COERCION_HASH_V3, coercionBlob)
                        remove(KEY_COERCION_HASH).remove(KEY_COERCION_HASH_V2)
                    } else {
                        putString(
                            KEY_COERCION_HASH,
                            Base64.encodeToString(
                                deriveVerifier(coercion, coercionSalt), Base64.DEFAULT
                            )
                        )
                        remove(KEY_COERCION_HASH_V2).remove(KEY_COERCION_HASH_V3)
                    }
                }
            }
            val done = runCatching {
                if (KeyVault.isSplitActive()) {
                    KeyVault.resplit(current, security, verifierCommit)
                } else {
                    KeyVault.activateSplit(security, verifierCommit)
                }
            }.getOrDefault(false)
            DaemonManager.clearCachedKey()
            done
        }

    /**
     * 移除门禁（仅清验证器条目，不动 armed/idle——超时销毁是独立功能）。
     * 联动解除 DK 拆分：以 current 重组 DK 落回单段（current 非安全密码
     * 时 DK 孤儿化）。
     *
     * 原子性（同 [setPasswords]）：验证器删除并入 deactivateSplit 事务的
     * 同一次 commit——封堵"验证器已删、DK 仍拆分"窗口（该窗口下无门禁
     * → assembleDaemonKey 永不被调用 → DK 功能永久失效）。
     * 事务中止时验证器一并不删（移除门禁失败优于密钥状态死锁）。
     *
     * @return true = 完成；false = 事务中止（验证器与 DK 均未动）
     */
    suspend fun removeGate(current: String): Boolean =
        withContext(Dispatchers.Default) {
            val verifierRemove: android.content.SharedPreferences.Editor.() -> Unit = {
                remove(KEY_SECURITY_HASH).remove(KEY_SECURITY_SALT)
                    .remove(KEY_SECURITY_HASH_V2).remove(KEY_SECURITY_HASH_V3)
                    .remove(KEY_COERCION_HASH).remove(KEY_COERCION_SALT)
                    .remove(KEY_COERCION_HASH_V2).remove(KEY_COERCION_HASH_V3)
            }
            val done = runCatching {
                if (KeyVault.isSplitActive()) {
                    KeyVault.deactivateSplit(current, verifierRemove)
                } else {
                    // 未拆分：无 DK 事务可搭，验证器单独删（无跨层窗口）
                    prefs().edit(commit = true) { verifierRemove() }
                    true
                }
            }.getOrDefault(false)
            DaemonManager.clearCachedKey()
            done
        }

    /**
     * 安全密码解锁后的 DK 编排（门禁页 SECURITY 分支调用）：
     * - 已拆分：组装（A ⊕ B）并经 dk_check 校验
     * - 旧版升级（有门禁但未拆分）：借解锁时机激活拆分（DK 保持不变）
     * 失败不阻断解锁（DK 相关功能 fail-closed）
     */
    suspend fun onSecurityUnlock(password: String) {
        runCatching {
            if (KeyVault.isSplitActive()) {
                KeyVault.assembleDaemonKey(password)
            } else if (isGateEnabled()) {
                KeyVault.activateSplit(password)
            }
        }
    }
}

enum class GateResult { SECURITY, COERCION, INVALID }
