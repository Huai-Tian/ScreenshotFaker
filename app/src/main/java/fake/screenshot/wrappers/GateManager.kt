package fake.screenshot.wrappers

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
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
import kotlin.time.Duration.Companion.milliseconds

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
    // armed 哨兵（明文，永不清除）+ idle_limit/idle_ts（密文 DataStore）。
    // armed 随验证器同写（setPasswords）：验证器存在而 armed 消失
    // = sync_preferences 被定向篡改 = 直接自毁
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

    /**
     * 设置/修改门禁密码。current 为当前密码（未启用门禁时忽略），
     * 验证由调用方（设置页）先行完成。
     * 密码联动 DK 拆分：已拆分 → 以 current 重组重拆（current 非安全密码
     * 时 DK 孤儿化 = 软销毁）；未拆分 → 激活拆分。密钥侧失败不阻断门禁
     * 写入（fail-closed：DK 相关功能退化为不可用，不产出坏密文）
     */
    suspend fun setPasswords(current: String, security: String, coercion: String) =
        withContext(Dispatchers.Default) {
            prefs().edit {
                // armed 随验证器同写：设过密码的用户，armed 消失即判篡改
                putBoolean(KEY_ARMED, true)
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
            runCatching {
                if (EncryptManager.isSplitActive()) {
                    EncryptManager.resplit(current, security)
                } else {
                    EncryptManager.activateSplit(security)
                }
            }
            DaemonManager.clearCachedKey()
        }

    /**
     * 移除门禁（仅清验证器条目，不动 armed/idle——超时销毁是独立功能）。
     * 联动解除 DK 拆分：以 current 重组 DK 落回单段（current 非安全密码
     * 时 DK 孤儿化）；密钥侧失败不阻断
     */
    suspend fun removeGate(current: String) {
        prefs().edit {
            remove(KEY_SECURITY_HASH).remove(KEY_SECURITY_SALT)
                .remove(KEY_COERCION_HASH).remove(KEY_COERCION_SALT)
        }
        runCatching {
            if (EncryptManager.isSplitActive()) EncryptManager.deactivateSplit(current)
        }
        DaemonManager.clearCachedKey()
    }

    /**
     * 安全密码解锁后的 DK 编排（门禁页 SECURITY 分支调用）：
     * - 已拆分：组装（A ⊕ B）并经 dk_check 校验
     * - 旧版升级（有门禁但未拆分）：借解锁时机激活拆分（DK 保持不变）
     * 失败不阻断解锁（DK 相关功能 fail-closed）
     */
    suspend fun onSecurityUnlock(password: String) {
        runCatching {
            if (EncryptManager.isSplitActive()) {
                EncryptManager.assembleDaemonKey(password)
            } else if (isGateEnabled()) {
                EncryptManager.activateSplit(password)
            }
        }
    }

    // ===================== 未使用自动销毁 =====================

    /**
     * 可选档位（分钟）：5分钟 ~ 12个月，无禁用项
     */
    val idleTimeoutOptions: List<Long> = listOf(
        5L, 30L, 60L, 360L, 1440L, 10080L,
        43200L, 129600L, 259200L, 525600L
    )

    fun isIdleArmed(): Boolean = prefs().getBoolean(KEY_ARMED, false)

    /**
     * idle 密文状态快照。
     * readable=false 表示 DataStore/Tink 解密失败（密钥已死或密文损坏），
     * 调用方按"已销毁/被篡改"处理——绝不能让异常直接逃逸导致启动崩溃循环。
     */
    private data class IdleState(val readable: Boolean, val limit: Long, val ts: String)

    private suspend fun readIdleState(): IdleState = try {
        IdleState(
            true,
            ConfigManager.getDataOnce(appContext, CONFIG_KEY_IDLE_LIMIT, 0L),
            ConfigManager.getDataOnce(appContext, CONFIG_KEY_IDLE_TS, "")
        )
    } catch (_: Exception) {
        IdleState(false, 0L, "")
    }

    /**
     * 读当前开机次数（跨重启单调递增，由 system_server 维护，用户态不可回拨）。
     * 读取失败返回 -1（个别设备不支持），调用方回退到双锚点启发式。
     */
    private fun readBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    /**
     * 写入三段式锚点：boot,elapsedRealtime,currentTimeMillis。
     * boot 显式区分开机周期——er 跨开机比较无意义，禁止用 er 大小猜测是否重启。
     */
    private suspend fun writeAnchor() {
        val ts = "${readBootCount()},${SystemClock.elapsedRealtime()},${System.currentTimeMillis()}"
        runCatching { ConfigManager.saveData(appContext, CONFIG_KEY_IDLE_TS, ts) }
    }

    /**
     * 超时销毁是否真正启用过（区别于 armed-only 的门禁态）。
     * 判定只看 limit：limit>0 即启用，ts 是否存在不参与
     * （写入时序为 ts 先 limit 后，limit>0 而 ts 缺失 = 篡改，交给雷管处理）。
     */
    private suspend fun isIdleActivated(): Boolean {
        if (!isIdleArmed()) return false
        return readIdleState().let { it.readable && it.limit > 0 }
    }

    /**
     * 冷启动超时判定（在门禁验证与一切配置加载之前调用）。
     * 读到任何不合法状态 → 雷管引爆（fail-destroy）。
     *
     * 状态判定规则（新状态机，修复旧版三向缺陷）：
     * - limit<=0：一律视为未启用，忽略 ts 残留（部分写入态/垃圾值不引爆）
     * - limit>0 且不在档位表：引爆
     * - limit>0 且 ts 为空：引爆（正常写入时序为 ts 先写，此状态不可达，
     *   出现即密文被定向篡改；旧版本启用过超时的用户本就会被旧代码引爆，
     *   行为一致）
     * - 锚点三段式 boot,er,wc：boot 相等走双锚点交叉校验；boot 增大（重启）
     *   走墙钟判定；boot 减小（不可回拨却回拨）引爆
     * - 旧版两段式锚点 er,wc：按旧规则评估一次（不比旧代码更严），通过后
     *   迁移写入新格式
     *
     * @return true = 已触发销毁（调用方无需额外处理，销毁含复位）
     */
    suspend fun checkIdleExpired(): Boolean {
        // 验证器存在而 armed 消失 = sync_preferences 被定向篡改 = 自毁
        if (isGateEnabled() && !isIdleArmed()) {
            destroyForCoercion()
            return true
        }
        // 未 armed 且未设门禁 = 从未启用（全新安装/存量未使用用户），正常流程
        if (!isIdleArmed()) return false

        val st = readIdleState()
        // 密文不可读（Tink/Keystore 已死或密文损坏）：按已销毁处理，走销毁复位，
        // 不让异常逃逸造成崩溃循环
        if (!st.readable) {
            destroyForCoercion()
            return true
        }
        // limit<=0：未启用。ts 残留视为垃圾忽略（修复：旧版会把 (0, ts≠"") 引爆）
        if (st.limit <= 0L) return false
        if (st.limit !in idleTimeoutOptions) {
            destroyForCoercion()
            return true
        }
        // limit>0 而 ts 为空：写入时序上不可达（ts 先写），出现即篡改
        if (st.ts.isEmpty()) {
            destroyForCoercion()
            return true
        }

        val limitMs = st.limit * 60_000L
        val boot = readBootCount()
        val er = SystemClock.elapsedRealtime()
        val wc = System.currentTimeMillis()
        val parts = st.ts.split(",")

        when (parts.size) {
            3 -> {
                val boot0 = parts[0].toIntOrNull()
                val er0 = parts[1].toLongOrNull()
                val wc0 = parts[2].toLongOrNull()
                if (boot0 == null || er0 == null || wc0 == null ||
                    er0 < 0 || wc0 < WC0_MIN
                ) {
                    destroyForCoercion()
                    return true
                }
                if (boot < 0 || boot0 < 0) {
                    // 设备不支持 BOOT_COUNT：退回双锚点启发式（与旧行为一致）
                    if (checkLegacyAnchor(er0, wc0, er, wc, limitMs)) {
                        destroyForCoercion()
                        return true
                    }
                    return false
                }
                when {
                    boot == boot0 -> {
                        // 同一开机：er 单调，er<er0 即非法
                        if (er < er0) {
                            destroyForCoercion()
                            return true
                        }
                        val erDiff = er - er0
                        val wcDiff = wc - wc0
                        // 双锚点交叉校验（只改其一会被捕获）
                        if (wcDiff < 0 || kotlin.math.abs(erDiff - wcDiff) > ANCHOR_DRIFT_TOLERANCE_MS) {
                            destroyForCoercion()
                            return true
                        }
                        if (erDiff >= limitMs) {
                            destroyForCoercion()
                            return true
                        }
                    }

                    boot > boot0 -> {
                        // 重启过（可能多次）：er 跨开机比较无意义，墙钟判定。
                        // 墙钟倒退 = 回拨 = 非法
                        if (wc < wc0) {
                            destroyForCoercion()
                            return true
                        }
                        if (wc - wc0 >= limitMs) {
                            destroyForCoercion()
                            return true
                        }
                    }

                    else -> {
                        // BOOT_COUNT 单调递减：不可回拨却回拨 = 篡改
                        destroyForCoercion()
                        return true
                    }
                }
            }

            2 -> {
                // 旧版两段式锚点（更早版本写入）：按旧规则评估一次，通过则迁移新格式。
                // 评估逻辑与旧代码一致，不比旧版更严格（升级用户不引入新误炸）
                val er0 = parts[0].toLongOrNull()
                val wc0 = parts[1].toLongOrNull()
                if (er0 == null || wc0 == null || er0 < 0 || wc0 < WC0_MIN) {
                    destroyForCoercion()
                    return true
                }
                if (checkLegacyAnchor(er0, wc0, er, wc, limitMs)) {
                    destroyForCoercion()
                    return true
                }
                writeAnchor()
            }

            else -> {
                destroyForCoercion()
                return true
            }
        }
        return false
    }

    /**
     * 双锚点启发式（旧版规则，仅两处使用）：
     * er>=er0 视为同一开机做交叉校验；er<er0 视为重启走墙钟判定。
     * BOOT_COUNT 不可用设备的新锚点（boot=-1）也走此路径。
     */
    private fun checkLegacyAnchor(
        er0: Long, wc0: Long, er: Long, wc: Long, limitMs: Long
    ): Boolean {
        if (er >= er0) {
            val erDiff = er - er0
            val wcDiff = wc - wc0
            if (wcDiff < 0 || kotlin.math.abs(erDiff - wcDiff) > ANCHOR_DRIFT_TOLERANCE_MS) {
                return true
            }
            return erDiff >= limitMs
        }
        // er 倒退：按重启处理，墙钟判定
        if (wc < wc0) return true
        return wc - wc0 >= limitMs
    }

    /**
     * 刷新计时锚点（写入三段式锚点）。只在"有效使用"时调用：
     * 无门禁冷启动判定后 / 门禁验证通过后 / onStart 回前台 / RESUMED 心跳。
     *
     * 有门禁且本会话未验证通过时拒绝刷新——未验证的打开（含冷启动后的
     * 首个 onStart）对计时器透明，防止胁迫者在门禁页停留/反复打开续命。
     *
     * 门禁判定只看 limit>0（旧版要求 ts 非空导致首写不可达，已修复）。
     * 锚点写入后顺带向守护进程续期（daemon 不在线则静默跳过）。
     */
    suspend fun touchIdle() {
        if (!isIdleArmed()) return
        if (isGateEnabled() && !sessionUnlocked) return
        val st = readIdleState()
        if (!st.readable || st.limit <= 0) return
        writeAnchor()
        DaemonManager.renewIdleDeadline(st.limit)
    }

    /**
     * 首次启用/修改档位。写入时序严格：
     * ① armed 哨兵（明文，先立于不败）→ ② 锚点（无生效意义）→ ③ limit（提交标志）。
     * 任意步骤间崩溃产生的部分状态均为合法态：
     * (armed, 0, "") / (armed, 0, ts) → 未启用；(armed, limit>0, ts) → 完整启用。
     * "limit>0 而 ts 空"不可达，出现即篡改（雷管覆盖）。
     */
    suspend fun setIdleTimeout(minutes: Long) {
        prefs().edit { putBoolean(KEY_ARMED, true) }
        writeAnchor()
        ConfigManager.saveData(appContext, CONFIG_KEY_IDLE_LIMIT, minutes)
        DaemonManager.renewIdleDeadline(minutes)
    }

    /** 当前档位（未启用返回 null，用于设置页副标题） */
    suspend fun getCurrentIdleTimeout(): Long? {
        if (!isIdleArmed()) return null
        val st = readIdleState()
        if (!st.readable) return null
        return if (st.limit > 0 && st.limit in idleTimeoutOptions) st.limit else null
    }

    /**
     * 销毁后复位：防连环雷管自毁循环（销毁清空 DataStore → 读默认 0 → 再引爆）。
     * 激活态由调用方在 wipe 之前快照传入（wipe 后读取恒为未启用，旧实现因此恒为 no-op）。
     * 仅"真正启用过"（limit>0）时写默认档 + 当前锚点，计时器自愈；
     * armed-only（只设过门禁未启用超时）销毁后回到未启用态 = 全新状态语义。
     * armed 永不清除。
     */
    private suspend fun resetIdleAfterDestroy(wasActivated: Boolean) {
        if (!wasActivated) return
        ConfigManager.saveData(
            appContext, CONFIG_KEY_IDLE_LIMIT, DEFAULT_IDLE_LIMIT_MINUTES
        )
        writeAnchor()
    }

    /**
     * 胁迫销毁序列，顺序严格：
     * 0. 快照 idle 激活态（此后即将销毁一切密文，wipe 后无法再判定）；
     * 1. 停 app 侧屏幕共享（杀 relay 与守护脚本，防"销毁后仍在推流"）；
     * 2. 停守护进程（此时密钥/配置仍在，stop 依赖端口与信道密钥）；
     * 3. 删 Keystore 条目（Tink 主密钥、硬件密钥）与密文文件（keyset、硬件 DK）；
     * 4. 删除密文配置并轮换 DataStore 文件随机名（同进程重建走新路径，
     *    规避 "multiple DataStores active for the same file"，且不暴露销毁史）；
     * 5. 清进程内信道密钥缓存；
     * 6. armed 启用过超时时复位写默认档（防连环雷管；写入触发新 keyset 生成）；
     * 7. 二次清扫 datastore 目录（旧实例在途写入可能复活已删文件，保留当前 ref）。
     * 验证器保留，销毁幂等（重复触发无副作用），每步独立容错。
     */
    suspend fun destroyForCoercion() = withContext(Dispatchers.IO) {
        // 0. wipe 前快照（修复：旧实现在 wipe 后判定，恒为 no-op）
        val wasIdleActivated = runCatching { isIdleActivated() }.getOrDefault(false)

        // 1. 停 app 侧共享（同步执行：内部 exec 阻塞至进程清理完成）
        runCatching { ScreenShareManager.stopScreenShare() }

        // 2. 停守护进程（stop 后其自身完成 tmp 明文/锚点/自拷贝清理）
        runCatching { DaemonManager.stopDaemon() }

        // 3. 删 Keystore 条目——密码学擦除优先于文件删除：
        // 此步完成后即使后续删除全部失败，所有密文在数学上已不可恢复
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("tink_master_key")
            keyStore.deleteEntry("hardware_encryption_key")
        }

        // 4. 删密文文件
        runCatching { appContext.deleteSharedPreferences("tink_prefs") }
        runCatching { File(appContext.filesDir, "hw_key.bin").delete() }

        // 5. 删密文配置 + 轮换 DataStore 随机文件名（全目录清扫）
        runCatching { ConfigManager.resetForCoercion(appContext) }

        // 6. 清信道密钥缓存与 DK 拆分状态（后续走重新生成的 DK；
        //    拆分三键随销毁清除——验证器保留但密钥状态归零，与全新安装一致）
        DaemonManager.clearCachedKey()
        runCatching { EncryptManager.resetSplitState() }

        // 7. 复位默认档（首次写入触发新 keyset 生成，与 Keystore 新主密钥配对）
        runCatching { resetIdleAfterDestroy(wasIdleActivated) }

        // 8. 二次清扫：旧 DataStore 实例的在途写入可能在步骤 5 之后落盘复活旧文件，
        //    稍作等待后清除（保留当前 ref 指向的新文件）
        runCatching {
            kotlinx.coroutines.delay(500.milliseconds)
            ConfigManager.sweepDatastoreDir(appContext)
        }
    }
}

enum class GateResult { SECURITY, COERCION, INVALID }
