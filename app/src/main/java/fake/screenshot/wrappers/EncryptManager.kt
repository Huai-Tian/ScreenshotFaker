package fake.screenshot.wrappers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptManager {
    //Software
    private const val SALT = "ScreenshotFakerSalt"
    private const val PBKDF2_ITERATIONS = 200000
    private const val KEY_LENGTH = 256
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 128

    //Hardware
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val HARDWARE_ALIAS = "hardware_encryption_key"

    // 硬件绑定数据密钥（DK）拆分存储：
    //   DK = A ⊕ B
    //   A：32 字节随机，Keystore 包裹落盘 hw_key.bin（root 冒充 app uid 可解包）
    //   B：由门禁安全密码 PBKDF2 派生（仅存在于用户记忆——堵 root 静态解包）
    // 未设门禁/未激活拆分时退化为单段模式（hw_key.bin 直接存完整 DK，旧语义）。
    // 胁迫语义保留：胁迫密码命中门禁即销毁；以胁迫密码改密/移除门禁时
    // DK 校验必然失败 → 孤儿化（软销毁：历史加密产物永久不可解）。
    private const val DK_FILE_NAME = "hw_key.bin"
    private const val DK_LENGTH = 32
    private const val DK_SEED_LENGTH = 16

    // 拆分状态存明文 prefs（与验证器同文件，中性键名）；
    // dk_check 为 DK 对常量的 AES-GCM 密文——用于组装时校验派生正确性
    // （其离线爆破成本与门禁验证器等同，均已文档化接受）
    private const val SPLIT_PREFS_NAME = "sync_preferences"
    private const val KEY_DK_SPLIT = "dk_split"
    private const val KEY_DK_SEED = "dk_seed"
    private const val KEY_DK_CHECK = "dk_check"
    private const val DK_CHECK_PLAINTEXT = "ScreenshotFakerDKCheck"

    private lateinit var appContext: Context

    // 会话内已组装 DK（解锁后缓存；进程死亡即失，下次解锁重组）
    @Volatile
    private var assembledDk: ByteArray? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun deriveKey(password: String): SecretKeySpec {
        val spec =
            PBEKeySpec(password.toCharArray(), SALT.toByteArray(), PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secret = factory.generateSecret(spec)
        val key = SecretKeySpec(secret.encoded, "AES")
        return key
    }

    fun encryptByPassword(key: SecretKeySpec, plaintext: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Pair(nonce, ciphertext)
    }

    fun decryptByPassword(
        key: SecretKeySpec,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, nonce))
        val plain = cipher.doFinal(ciphertextWithTag)
        return String(plain, Charsets.UTF_8)
    }

    fun encryptBytesByPassword(key: SecretKeySpec, data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, nonce))
        val ciphertext = cipher.doFinal(data)
        return Pair(nonce, ciphertext)
    }

    fun decryptBytesByPassword(
        key: SecretKeySpec,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, nonce))
        return cipher.doFinal(ciphertextWithTag)
    }

    // ===================== 硬件绑定（Keystore 包裹 + 可选密码拆分） =====================

    /** 拆分是否激活 */
    fun isSplitActive(): Boolean =
        appContext.getSharedPreferences(SPLIT_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DK_SPLIT, false)

    /**
     * DK 当前是否可用：
     * 拆分激活 = 需本会话已解锁组装；单段 = 恒可用（读盘按需）。
     * 磁贴在 encrypt_outputs 开启时先查此标志，未就绪直接放弃（fail-closed，
     * 绝不退化为明文落盘）
     */
    fun isDaemonKeyReady(): Boolean = if (isSplitActive()) assembledDk != null else true

    /** 清会话内 DK（销毁序列/状态重置； SecretKeySpec 内部副本交由 GC，已知边界） */
    fun clearAssembledKey() {
        assembledDk?.fill(0)
        assembledDk = null
    }

    /** 销毁序列配套：清拆分状态（prefs 三键）与缓存（hw_key.bin 由销毁序列另行删除） */
    fun resetSplitState() {
        clearAssembledKey()
        appContext.getSharedPreferences(SPLIT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                remove(KEY_DK_SPLIT).remove(KEY_DK_SEED).remove(KEY_DK_CHECK)
            }
    }

    /**
     * 解锁时组装 DK：
     * - 拆分激活：DK = A ⊕ B(password)，经 dk_check 校验后缓存；
     *   校验失败（密码非安全密码 / A 或 check 被篡改 / 密文损坏）返回 false，
     *   DK 保持不可用（fail-closed：宁可不可用，不可用错钥静默产出坏密文）
     * - 单段模式：读盘或按需生成（旧版语义），恒成功
     */
    suspend fun assembleDaemonKey(password: String): Boolean = withContext(Dispatchers.Default) {
        if (!isSplitActive()) {
            assembledDk?.fill(0)
            assembledDk = getOrCreateUnsplitDk()
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
     * A' = DK ⊕ B(newPassword) 落盘。写入顺序：先文件后 prefs 置位——
     * 崩溃窗口留下的是自洽的单段态（最坏孤儿化旧密文，无坏钥产出）
     */
    suspend fun activateSplit(newPassword: String): Boolean = withContext(Dispatchers.Default) {
        val dk = assembledDk ?: readStoredPart()
        ?: ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
        val seed = ByteArray(DK_SEED_LENGTH).also { SecureRandom().nextBytes(it) }
        writeWrapped(xorBytes(dk, derivePartB(newPassword, seed)))
        appContext.getSharedPreferences(SPLIT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString(KEY_DK_SEED, Base64.encodeToString(seed, Base64.NO_WRAP))
                putString(KEY_DK_CHECK, makeCheck(dk))
                putBoolean(KEY_DK_SPLIT, true)
            }
        assembledDk?.fill(0)
        assembledDk = dk
        true
    }

    /**
     * 拆分态下改密重拆：以 currentPassword 重组当前 DK 并校验，再以 newPassword 重拆。
     * currentPassword 非安全密码（如胁迫密码）→ 校验失败 → DK 孤儿化重生成
     * （软销毁语义：门禁可继续用，历史硬件加密产物永久不可解——与门禁层
     * 胁迫行为方向一致，且确定性不依赖缓存状态）
     * @return true = 保留了原 DK
     */
    suspend fun resplit(currentPassword: String, newPassword: String): Boolean =
        withContext(Dispatchers.Default) {
            val preserved = reassembleVerified(currentPassword)
            val dk = preserved
                ?: ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
            val seed = ByteArray(DK_SEED_LENGTH).also { SecureRandom().nextBytes(it) }
            writeWrapped(xorBytes(dk, derivePartB(newPassword, seed)))
            appContext.getSharedPreferences(SPLIT_PREFS_NAME, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    putString(KEY_DK_SEED, Base64.encodeToString(seed, Base64.NO_WRAP))
                    putString(KEY_DK_CHECK, makeCheck(dk))
                    putBoolean(KEY_DK_SPLIT, true)
                }
            assembledDk?.fill(0)
            assembledDk = dk
            preserved != null
        }

    /**
     * 解除拆分（移除门禁）：以 currentPassword 重组 DK（校验失败 → 孤儿化重生成），
     * 完整 DK 落盘回单段模式。写入顺序：先清 prefs 后写文件——崩溃窗口
     * 留下的是自洽的单段态
     */
    suspend fun deactivateSplit(currentPassword: String) = withContext(Dispatchers.Default) {
        val preserved = reassembleVerified(currentPassword)
        val dk = preserved
            ?: ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
        appContext.getSharedPreferences(SPLIT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                remove(KEY_DK_SPLIT).remove(KEY_DK_SEED).remove(KEY_DK_CHECK)
            }
        writeWrapped(dk)
        assembledDk?.fill(0)
        assembledDk = dk
    }

    /**
     * 当前可用 DK；拆分激活且本会话未组装 → null（调用方 fail-closed）。
     * 单段模式读盘或按需生成（旧版语义）
     */
    fun getDaemonKeyOrNull(): SecretKeySpec? {
        if (isSplitActive()) return assembledDk?.let { SecretKeySpec(it, "AES") }
        return SecretKeySpec(getOrCreateUnsplitDk(), "AES")
    }

    fun encryptByKeystore(data: ByteArray): Pair<ByteArray, ByteArray> =
        encryptBytesByPassword(requireDaemonKey(), data)

    fun decryptByKeystore(nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        decryptBytesByPassword(requireDaemonKey(), nonce, ciphertext)

    fun encryptFileByKeystore(inputFile: File, outputFile: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, requireDaemonKey())
        }
        outputFile.outputStream().use { output ->
            output.write(cipher.iv)
            CipherOutputStream(output, cipher).use { cos ->
                inputFile.inputStream().use { input ->
                    input.copyTo(cos)
                }
            }
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(dk, "AES")) }
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(DK_CHECK_PLAINTEXT.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
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
            MessageDigest.isEqual(
                cipher.doFinal(blob.copyOfRange(NONCE_LENGTH, blob.size)),
                DK_CHECK_PLAINTEXT.toByteArray(Charsets.UTF_8)
            )
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
        val wrapper = getOrCreateHardwareKey()
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

    /** 单段模式：读（或首次生成）完整 DK；解包失败重生回写（旧版语义） */
    private fun getOrCreateUnsplitDk(): ByteArray {
        readStoredPart()?.let { return it }
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