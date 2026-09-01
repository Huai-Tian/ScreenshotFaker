package fake.screenshot.defense

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import fake.screenshot.wrappers.EncryptManager
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * L1 密钥保管层：数据密钥（DK）拆分、迁移事务、Keystore pepper。
 *
 * DK 拆分（堵 root 静态解包）：
 *   DK = A ⊕ B
 *   A：32 字节随机，Keystore 包裹落盘 hw_key.bin（root 冒充 app uid 可解包）
 *   B：由门禁安全密码 PBKDF2 派生（仅存在于用户记忆）
 * 未设门禁/未激活拆分时退化为单段模式（hw_key.bin 直接存完整 DK）。
 * 胁迫语义：以胁迫密码改密/移除门禁时 dk_check 校验必然失败 →
 * DK 孤儿化重生成（软销毁：历史加密产物永久不可解）。
 *
 * 迁移事务协议（防激活/重拆/解除的崩溃孤儿化窗口）：
 *   ①prefs 置 pending → ②旧文件 rename .bak → ③写新文件 →
 *   ④新 prefs 值 + 清 pending（单次 commit 原子）→ ⑤删 .bak。
 *
 * pepper（封堵离线试密码）：32B 随机、Keystore 不可导出密钥加密落盘——
 * root 拷走全部数据后离线验证任何密码猜测在数学上不可行。
 *
 * 存储格式（不可变更，直接兼容存量数据）：
 *   prefs "sync_preferences"：dk_split/dk_seed/dk_check/dk_migration/gate_pepper
 *   文件 filesDir/hw_key.bin（.bak/.tmp 为事务临时态）
 */
object KeyVault {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val HARDWARE_ALIAS = "hardware_encryption_key"

    private const val DK_FILE_NAME = "hw_key.bin"
    private const val DK_LENGTH = 32
    private const val DK_SEED_LENGTH = 16

    // 拆分状态存明文 prefs（与验证器同文件，中性键名——键名是隐蔽性设计，勿改）
    private const val SPLIT_PREFS_NAME = "sync_preferences"
    private const val KEY_DK_SPLIT = "dk_split"
    private const val KEY_DK_SEED = "dk_seed"
    private const val KEY_DK_CHECK = "dk_check"
    private const val DK_CHECK_PLAINTEXT = "ScreenshotFakerDKCheck"

    // 迁移事务标记
    private const val KEY_DK_MIGRATION = "dk_migration"
    private const val MIGRATION_PENDING = "pending"
    private const val MIGRATION_NONE = "none"

    // pepper（独立 Keystore alias：销毁序列删硬件密钥与 Tink 主密钥时不波及
    // ——验证器保留语义要求 pepper 跨销毁存活）
    private const val KEY_PEPPER_ALIAS = "gate_pepper_key"
    private const val KEY_PEPPER_BLOB = "gate_pepper"
    private const val PEPPER_LENGTH = 32

    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 200000
    private const val KEY_LENGTH = 256

    private lateinit var appContext: Context

    // 会话内已组装 DK（解锁后缓存；进程死亡即失，下次解锁重组）
    @Volatile
    private var assembledDk: ByteArray? = null

    // 迁移事务串行化：activate/resplit/deactivate（Default 协程线程）与
    // init/assembleDaemonKey 的恢复调用（主线程或其他 IO 线程）并发时，
    // 不加锁的 recoverPendingMigration 会把在途事务撕成"新 prefs 值 +
    // 旧 hw_key.bin"的坏钥组合（拆分标记已换新而 A 段还是旧的——新密码
    // 组不出 DK，全部 _sec 密文与加密产物孤儿化）。可重入：事务入口持锁
    // 后内部的 recover 调用直接重入
    private val migrationLock = ReentrantLock()

    fun init(context: Context) {
        appContext = context.applicationContext
        // DK 迁移中断恢复（幂等；任何异常不得逃逸——它是所有 key 操作的前置）
        runCatching { recoverPendingMigration() }
    }

    // ===================== 状态查询 =====================

    /** 拆分是否激活 */
    fun isSplitActive(): Boolean = splitPrefs().getBoolean(KEY_DK_SPLIT, false)

    /**
     * DK 当前是否可用：
     * 拆分激活 = 需本会话已解锁组装；单段 = 文件缺失（首用按需生成）或
     * 存在且可解。文件存在但解不开（Keystore 条目丢失）时如实报未就绪，
     * 磁贴在 encrypt_outputs 开启时先查此标志，未就绪直接放弃（fail-closed，
     * 绝不退化为明文落盘，也绝不静默轮换 DK 孤儿化全部密文）
     */
    fun isDaemonKeyReady(): Boolean = if (isSplitActive()) assembledDk != null else {
        val file = File(appContext.filesDir, DK_FILE_NAME)
        !file.exists() || readStoredPart() != null
    }

    /** 清会话内 DK（锁定/销毁序列/状态重置；SecretKeySpec 内部副本交由 GC，已知边界） */
    fun clearAssembledKey() {
        assembledDk?.fill(0)
        assembledDk = null
    }

    /**
     * 当前可用 DK；拆分激活且本会话未组装 → null（调用方 fail-closed）。
     * 单段模式读盘或按需生成（旧版语义）
     */
    fun getDaemonKeyOrNull(): SecretKeySpec? {
        if (isSplitActive()) return assembledDk?.let { SecretKeySpec(it, "AES") }
        return getOrCreateUnsplitDk()?.let { SecretKeySpec(it, "AES") }
    }

    // ===================== DK 加解密入口（业务侧） =====================

    fun encryptByKeystore(data: ByteArray): Pair<ByteArray, ByteArray> =
        EncryptManager.encryptBytesByPassword(requireDaemonKey(), data)

    fun decryptByKeystore(nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        EncryptManager.decryptBytesByPassword(requireDaemonKey(), nonce, ciphertext)

    fun encryptFileByKeystore(inputFile: File, outputFile: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, requireDaemonKey())
        }
        outputFile.outputStream().use { output ->
            output.write(cipher.iv)
            javax.crypto.CipherOutputStream(output, cipher).use { cos ->
                inputFile.inputStream().use { input -> input.copyTo(cos) }
            }
        }
    }

    // ===================== 拆分生命周期 =====================

    /**
     * 解锁时组装 DK：
     * - 拆分激活：DK = A ⊕ B(password)，经 dk_check 校验后缓存；
     *   校验失败（密码非安全密码 / A 或 check 被篡改 / 密文损坏）返回 false，
     *   DK 保持不可用（fail-closed：宁可不可用，不可用错钥静默产出坏密文）
     * - 单段模式：读盘或按需生成（旧版语义），恒成功
     */
    suspend fun assembleDaemonKey(password: String): Boolean = withContext(Dispatchers.Default) {
        // 迁移中断兜底恢复（幂等；init 的恢复被 runCatching 吞掉异常时由此兜底）
        runCatching { recoverPendingMigration() }
        if (!isSplitActive()) {
            assembledDk?.fill(0)
            // 单段文件存在但解不开（Keystore 条目丢失）→ fail-closed，
            // 不静默轮换 DK（旧密文全部孤儿化）
            val dk = getOrCreateUnsplitDk() ?: return@withContext false
            assembledDk = dk
            return@withContext true
        }
        val a = readStoredPart() ?: return@withContext false
        val seed = prefsSeed() ?: return@withContext false
        val dk = xorBytes(a, derivePartB(password, seed))
        if (!verifyCheck(dk)) return@withContext false
        assembledDk?.fill(0)
        assembledDk = dk
        true
    }

    /**
     * 激活拆分（单段 → 拆分，旧版升级/首次设门禁）：DK 保持不变，
     * A' = DK ⊕ B(newPassword) 落盘。全程走迁移事务协议——任意点崩溃
     * 由 [recoverPendingMigration] 回滚到迁移前自洽旧态，不再孤儿化。
     * 事务失败（备份 rename 失败等）中止且旧态完整，返回 false。
     *
     * [extraCommit]：调用方（GateManager 设密）随迁移同一次 commit 落地的
     * 其他 prefs 键——用于把"新验证器"与"新 DK 状态"绑成同一原子单元，
     * 封堵"验证器已换新密码、DK 仍被旧密码包裹"的跨层不一致窗口
     * （该窗口下旧密码无处可验证 → 后续任何改密必然 DK 孤儿化）。
     * 事务中止时 extraCommit 一并不落地（改密失败优于数据孤儿化）。
     *
     * 单段文件存在但解不开（Keystore 条目丢失）→ 中止返回 false（不重生：
     * 静默轮换 DK 会让全部 `_sec` 密文孤儿化；与 getOrCreateUnsplitDk 的
     * fail-closed 语义对齐）。
     */
    suspend fun activateSplit(
        newPassword: String,
        extraCommit: android.content.SharedPreferences.Editor.() -> Unit = {}
    ): Boolean = withContext(Dispatchers.Default) {
        // 事务全程持锁（含前置恢复——见 migrationLock 注释）：并发恢复/
        // 销毁在锁上排队，任何交错都不会撕裂事务
        migrationLock.lock()
        try {
            recoverPendingMigration()
            val existing = readStoredPart()
            val fileExists = File(appContext.filesDir, DK_FILE_NAME).exists()
            // 会话已组装（最权威）→ 单段文件可解 → 文件缺失（首建，正确重生）
            // → 文件存在但解不开（中止，防孤儿化，见方法注释）
            val dk: ByteArray
            if (assembledDk != null) {
                dk = assembledDk!!
            } else if (existing != null) {
                dk = existing
            } else if (fileExists) {
                return@withContext false
            } else {
                dk = ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
            }
            val seed = ByteArray(DK_SEED_LENGTH).also { SecureRandom().nextBytes(it) }
            val check = makeCheck(dk)
            if (!beginDkMigration()) return@withContext false
            writeWrapped(xorBytes(dk, derivePartB(newPassword, seed)))
            commitDkMigration {
                putString(KEY_DK_SEED, Base64.encodeToString(seed, Base64.NO_WRAP))
                putString(KEY_DK_CHECK, check)
                putBoolean(KEY_DK_SPLIT, true)
                extraCommit()
            }
            finishDkMigration()
            assembledDk?.fill(0)
            assembledDk = dk
            true
        } finally {
            migrationLock.unlock()
        }
    }

    /**
     * 拆分态下改密重拆：以 currentPassword 重组当前 DK 并校验，再以 newPassword 重拆。
     * currentPassword 非安全密码（如胁迫密码）→ 校验失败 → DK 孤儿化重生成
     * （软销毁语义：门禁可继续用，历史硬件加密产物永久不可解——与门禁层
     * 胁迫行为方向一致，且确定性不依赖缓存状态）。迁移事务化：重拆中断
     * 回滚到旧拆分态（旧密码继续有效），不产生半新半旧状态。
     *
     * [extraCommit] 语义见 [activateSplit]：新验证器与新 DK 同 commit 原子
     * 落地；事务中止（begin 失败）时验证器一并不写。
     * @return true = 事务完成（含孤儿化路径）；false = 事务中止（一切未动）
     */
    suspend fun resplit(
        currentPassword: String,
        newPassword: String,
        extraCommit: android.content.SharedPreferences.Editor.() -> Unit = {}
    ): Boolean = withContext(Dispatchers.Default) {
        // 事务全程持锁（见 activateSplit / migrationLock 注释）
        migrationLock.lock()
        try {
            recoverPendingMigration()
            val preserved = reassembleVerified(currentPassword)
            val dk = preserved
                ?: ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
            val seed = ByteArray(DK_SEED_LENGTH).also { SecureRandom().nextBytes(it) }
            val check = makeCheck(dk)
            if (!beginDkMigration()) return@withContext false
            writeWrapped(xorBytes(dk, derivePartB(newPassword, seed)))
            commitDkMigration {
                putString(KEY_DK_SEED, Base64.encodeToString(seed, Base64.NO_WRAP))
                putString(KEY_DK_CHECK, check)
                putBoolean(KEY_DK_SPLIT, true)
                extraCommit()
            }
            finishDkMigration()
            assembledDk?.fill(0)
            assembledDk = dk
            // 到达此处 = 事务已完成（含孤儿化路径：extraCommit 已随 commit 落地）
            true
        } finally {
            migrationLock.unlock()
        }
    }

    /**
     * 解除拆分（移除门禁）：以 currentPassword 重组 DK（校验失败 → 孤儿化重生成），
     * 完整 DK 落盘回单段模式。迁移事务化：中断回滚到拆分态（保持拆分，
     * 门禁仍可用），不出现"单段标记+密钥段文件"的坏钥组合。
     *
     * [extraCommit] 语义见 [activateSplit]：验证器删除与 DK 落回单段同
     * commit——封堵"验证器已删、DK 仍拆分"窗口（该窗口下无门禁 →
     * assembleDaemonKey 永不被调用 → DK 功能永久失效）。
     * 事务中止时验证器一并不删（移除门禁失败优于密钥状态死锁）。
     */
    suspend fun deactivateSplit(
        currentPassword: String,
        extraCommit: android.content.SharedPreferences.Editor.() -> Unit = {}
    ): Boolean = withContext(Dispatchers.Default) {
        // 事务全程持锁（见 activateSplit / migrationLock 注释）
        migrationLock.lock()
        try {
            recoverPendingMigration()
            val preserved = reassembleVerified(currentPassword)
            val dk = preserved
                ?: ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
            if (!beginDkMigration()) return@withContext false
            writeWrapped(dk)
            commitDkMigration {
                remove(KEY_DK_SPLIT).remove(KEY_DK_SEED).remove(KEY_DK_CHECK)
                extraCommit()
            }
            finishDkMigration()
            assembledDk?.fill(0)
            assembledDk = dk
            true
        } finally {
            migrationLock.unlock()
        }
    }

    /** 销毁序列配套：删除 hw_key.bin 主体文件（.bak/.tmp 由 [resetSplitState] 清）。
     *  持迁移锁：与在途迁移事务互斥——否则事务提交的新 prefs 值会落在
     *  已删除的密钥文件上，产生"标记在而密钥无"的悬空态 */
    fun deleteKeyFile() {
        migrationLock.lock()
        try {
            runCatching { File(appContext.filesDir, DK_FILE_NAME).delete() }
        } finally {
            migrationLock.unlock()
        }
    }

    /** 销毁序列配套：清拆分状态（prefs 三键+迁移标记）与缓存；.bak/.tmp 一并删除
     *  （hw_key.bin 主体由 [deleteKeyFile] 另行删除）。持锁理由同 [deleteKeyFile] */
    fun resetSplitState() {
        migrationLock.lock()
        try {
            clearAssembledKey()
            splitPrefs().edit(commit = true) {
                remove(KEY_DK_SPLIT).remove(KEY_DK_SEED).remove(KEY_DK_CHECK)
                    .remove(KEY_DK_MIGRATION)
            }
            runCatching {
                File(appContext.filesDir, "$DK_FILE_NAME.bak").delete()
                File(appContext.filesDir, "$DK_FILE_NAME.tmp").delete()
            }
        } finally {
            migrationLock.unlock()
        }
    }

    // ===================== 迁移事务原语 =====================

    /** 事务开启：置 pending + 备份旧文件（rename 原子）。
     *  备份失败 → 中止返回 false（旧态完整未动） */
    private fun beginDkMigration(): Boolean {
        splitPrefs().edit(commit = true) { putString(KEY_DK_MIGRATION, MIGRATION_PENDING) }
        val main = File(appContext.filesDir, DK_FILE_NAME)
        val bak = File(appContext.filesDir, "$DK_FILE_NAME.bak")
        if (main.exists()) {
            bak.delete()
            if (!main.renameTo(bak)) {
                // 备份失败：文件与 prefs 均为旧值，清标记回到干净旧态
                splitPrefs().edit(commit = true) { putString(KEY_DK_MIGRATION, MIGRATION_NONE) }
                return false
            }
        }
        return true
    }

    /** 事务提交：新 prefs 值与清 pending 同一次 commit（SharedPreferences
     *  单次 commit 整体落盘，多键原子）——标记未清即新值未落地 */
    private fun commitDkMigration(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        splitPrefs().edit(commit = true) {
            edit()
            putString(KEY_DK_MIGRATION, MIGRATION_NONE)
        }
    }

    /** 事务收尾：删备份（此时新态已完整：新文件+新 prefs） */
    private fun finishDkMigration() {
        runCatching { File(appContext.filesDir, "$DK_FILE_NAME.bak").delete() }
    }

    /**
     * 迁移中断恢复（幂等，init 与每次迁移入口调用）：
     * - pending + .bak 存在：中断于"备份后、prefs 提交前"——prefs 仍是
     *   旧值（提交原子性保证），弃新文件、备份归位 = 迁移前完整旧态
     * - pending + 无 .bak：中断于备份前，文件 prefs 均旧值，仅清标记
     * - none + .bak 存在：中断于提交后收尾前——新态已完整，前滚删备份
     *
     * 并发语义：tryLock 失败（迁移事务在途）时直接跳过——此刻回滚会把
     * 在途事务撕成"新 prefs 值 + 旧文件"的坏钥组合（见 migrationLock
     * 注释）；事务入口持锁后已先行恢复，跳过不损失任何语义。可重入：
     * 事务内部调用时已持锁，tryLock 必成功
     */
    fun recoverPendingMigration() {
        if (!migrationLock.tryLock()) return
        try {
            val prefs = splitPrefs()
            val phase = prefs.getString(KEY_DK_MIGRATION, MIGRATION_NONE)
            val main = File(appContext.filesDir, DK_FILE_NAME)
            val bak = File(appContext.filesDir, "$DK_FILE_NAME.bak")
            when {
                phase == MIGRATION_PENDING && bak.exists() -> {
                    main.delete()
                    if (!bak.renameTo(main)) {
                        // 归位失败（文件系统异常）：保留 .bak 等待下次恢复重试，
                        // 不清标记（标记是恢复的依据）
                        return
                    }
                    prefs.edit(commit = true) { putString(KEY_DK_MIGRATION, MIGRATION_NONE) }
                }
                phase == MIGRATION_PENDING -> {
                    prefs.edit(commit = true) { putString(KEY_DK_MIGRATION, MIGRATION_NONE) }
                }
                bak.exists() -> {
                    bak.delete()
                }
            }
        } finally {
            migrationLock.unlock()
        }
    }

    // ===================== Keystore pepper =====================

    /**
     * 取（或首次生成）pepper。首次生成顺序：先落盘加密 blob 再返回——
     * blob 必然先于任何引用它的验证器/check 落盘，无空窗。
     * Keystore 不可用 → null（调用方按无 pepper 行为处理，不阻断功能）
     */
    fun getOrCreatePepper(): ByteArray? {
        splitPrefs().getString(KEY_PEPPER_BLOB, null)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()?.let { blob ->
                return unwrapPepper(blob)
            }
        }
        val pepper = ByteArray(PEPPER_LENGTH).also { SecureRandom().nextBytes(it) }
        val wrapped = wrapPepper(pepper) ?: return null
        splitPrefs().edit(commit = true) {
            putString(KEY_PEPPER_BLOB, Base64.encodeToString(wrapped, Base64.NO_WRAP))
        }
        return pepper
    }

    private fun wrapPepper(pepper: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, getOrCreatePepperKey()) }
        cipher.iv + cipher.doFinal(pepper)
    }.getOrNull()

    private fun unwrapPepper(blob: ByteArray): ByteArray? {
        if (blob.size <= NONCE_LENGTH) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, getOrCreatePepperKey(),
                GCMParameterSpec(TAG_LENGTH, blob.copyOfRange(0, NONCE_LENGTH))
            )
            cipher.doFinal(blob.copyOfRange(NONCE_LENGTH, blob.size))
                .takeIf { it.size == PEPPER_LENGTH }
        }.getOrNull()
    }

    private fun getOrCreatePepperKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_PEPPER_ALIAS, null) as? SecretKey ?: run {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_PEPPER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    // ===================== 内部实现 =====================

    private fun requireDaemonKey(): SecretKeySpec =
        getDaemonKeyOrNull() ?: throw IllegalStateException("daemon key not assembled")

    private fun splitPrefs() =
        appContext.getSharedPreferences(SPLIT_PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefsSeed(): ByteArray? = splitPrefs().getString(KEY_DK_SEED, null)
        ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        ?.takeIf { it.size == DK_SEED_LENGTH }

    private fun derivePartB(password: String, seed: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), seed, PBKDF2_ITERATIONS, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        val r = ByteArray(a.size)
        for (i in a.indices) r[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return r
    }

    private fun makeCheck(dk: ByteArray): String {
        // 明文掺 pepper：root 拿走 prefs+hw_key.bin 离线试密码时，
        // 无 pepper 无法验证猜测（pepper 由 Keystore 不可导出密钥包裹）。
        // pepper 不可用（Keystore 异常）退回常量明文——不阻断功能，
        // 行为不劣化于无 pepper 版本
        val plain = DK_CHECK_PLAINTEXT.toByteArray(Charsets.UTF_8) +
                (getOrCreatePepper() ?: ByteArray(0))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(dk, "AES")) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun verifyCheck(dk: ByteArray): Boolean {
        val blob = splitPrefs().getString(KEY_DK_CHECK, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?: return false
        if (blob.size <= NONCE_LENGTH) return false
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(dk, "AES"),
                GCMParameterSpec(TAG_LENGTH, blob.copyOfRange(0, NONCE_LENGTH))
            )
            val plain = cipher.doFinal(blob.copyOfRange(NONCE_LENGTH, blob.size))
            // 兼容两种明文：掺 pepper（新）与常量（pepper 故障窗口产出）。
            // pepper 不可用时必须退 ByteArray(0)（与 makeCheck 对称）而不是
            // 提前 false——否则常量版 dk_check（恰在 pepper 不可用期间写出）
            // 永远验不过：下方常量回退比较在其唯一被需要的时刻不可达，
            // 正确安全密码也无法组装 DK，改密自救会判为胁迫 → DK 孤儿化
            val pepper = getOrCreatePepper()
            val withPepper = DK_CHECK_PLAINTEXT.toByteArray(Charsets.UTF_8) +
                    (pepper ?: ByteArray(0))
            if (GuardManager.constantTimeEquals(plain, withPepper)) return@runCatching true
            if (GuardManager.constantTimeEquals(
                    plain, DK_CHECK_PLAINTEXT.toByteArray(Charsets.UTF_8)
                )
            ) {
                // 常量版命中（存量升级或 pepper 故障窗口产出）且 pepper 当前
                // 可用：就地把 dk_check 重写为 pepper 版——离线试密码防护
                // 不必等到下次改密才生效（验证通过即 dk 正确，重写安全）
                if (pepper != null) {
                    runCatching {
                        splitPrefs().edit(commit = true) {
                            putString(KEY_DK_CHECK, makeCheck(dk))
                        }
                    }
                }
                return@runCatching true
            }
            false
        }.getOrDefault(false)
    }

    /** 拆分态下以密码重组并校验 DK；失败（含文件/种子缺失）返回 null */
    private fun reassembleVerified(password: String): ByteArray? {
        val a = readStoredPart() ?: return null
        val seed = prefsSeed() ?: return null
        val cand = xorBytes(a, derivePartB(password, seed))
        return if (verifyCheck(cand)) cand else null
    }

    /**
     * 读 Keystore 包裹落盘的密文段（拆分态 = A；单段态 = 完整 DK）。
     * 解包失败返回 null（不重生：拆分态下重生会让后续加密静默使用错误密钥）
     */
    private fun readStoredPart(): ByteArray? {
        val file = File(appContext.filesDir, DK_FILE_NAME)
        // Keystore 服务异常（getInstance/load/getKey 均可抛 KeyStoreException/
        // ProviderException）不得沿 isDaemonKeyReady → 磁贴路径逃逸导致进程
        // 崩溃——与 wrapPepper/unwrapPepper 的 runCatching 防护对齐
        // （fail-closed：异常 = 不可用，调用方按未就绪处理）
        val wrapper = runCatching { getOrCreateHardwareKey() }.getOrNull() ?: return null
        val data = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (data.size <= NONCE_LENGTH) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, wrapper,
                GCMParameterSpec(TAG_LENGTH, data.copyOfRange(0, NONCE_LENGTH))
            )
            cipher.doFinal(data.copyOfRange(NONCE_LENGTH, data.size))
                .takeIf { it.size == DK_LENGTH }
        }.getOrNull()
    }

    /**
     * 单段模式：读（或文件缺失时首次生成）完整 DK。
     * 文件存在但解不开（典型：备份迁移后 hw_key.bin 随文件回来而
     * AndroidKeyStore 条目不随备份迁移）→ fail-closed 返回 null：
     * 旧版"静默重生新 DK 覆盖"会让全部 `_sec` 密文与既有加密产物永久
     * 孤儿化，且"已配置但不可解"与"未配置"不可区分。调用方按
     * fail-closed 处理；用户删除损坏文件后下次调用即走首建分支
     */
    private fun getOrCreateUnsplitDk(): ByteArray? {
        val file = File(appContext.filesDir, DK_FILE_NAME)
        if (file.exists()) {
            return readStoredPart()
        }
        val dk = ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
        writeWrapped(dk)
        return dk
    }

    /** Keystore 包裹写盘（原子：tmp + rename；中途死进程不留截断密文） */
    private fun writeWrapped(bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, getOrCreateHardwareKey()) }
        val blob = cipher.iv + cipher.doFinal(bytes)
        val file = File(appContext.filesDir, DK_FILE_NAME)
        val tmp = File(appContext.filesDir, "$DK_FILE_NAME.tmp")
        tmp.outputStream().use { it.write(blob) }
        file.delete()
        if (!tmp.renameTo(file)) {
            // 极端文件系统不支持 rename 覆盖：退回直写
            file.outputStream().use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
            tmp.delete()
        }
    }

    private fun getOrCreateHardwareKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(HARDWARE_ALIAS, null) as? SecretKey ?: run {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            val spec = KeyGenParameterSpec.Builder(
                HARDWARE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }
}
