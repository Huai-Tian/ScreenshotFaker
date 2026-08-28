package fake.screenshot.wrappers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
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

    // 硬件绑定数据密钥（DK）：随机生成，经 Keystore 密钥包裹落盘
    private const val DK_FILE_NAME = "hw_key.bin"
    private const val DK_LENGTH = 32

    private lateinit var appContext: Context

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

    // ===================== 硬件绑定（Keystore 包裹的随机 DK） =====================

    /**
     * 获取（或首次生成）硬件绑定数据密钥 DK：
     * - 首次：SecureRandom 生成 32 字节随机密钥，经 Keystore 密钥（AES/GCM）包裹后
     *   落盘私有目录，明文仅存在于内存；
     * - 之后：读取密文并解包复用；
     * - 解包失败（密文损坏、换机恢复导致 Keystore 密钥失效）→ 重新生成。
     *
     * DK 同时用于守护进程信道加密与硬件绑定文件加密，删除 Keystore 条目即
     * 可令其永久不可恢复（密钥丢弃 / 胁迫销毁的基础）。
     */
    @Synchronized
    fun getOrCreateDaemonKey(): SecretKeySpec {
        val file = File(appContext.filesDir, DK_FILE_NAME)
        val wrapper = getOrCreateHardwareKey()

        val data = runCatching { file.readBytes() }.getOrNull()
        if (data != null && data.size > NONCE_LENGTH) {
            val plain = runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    wrapper,
                    GCMParameterSpec(TAG_LENGTH, data.copyOfRange(0, NONCE_LENGTH))
                )
                cipher.doFinal(data.copyOfRange(NONCE_LENGTH, data.size))
            }.getOrNull()
            if (plain != null && plain.size == DK_LENGTH) {
                return SecretKeySpec(plain, "AES")
            }
        }

        val dk = ByteArray(DK_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrapper)
        }
        file.outputStream().use { it.write(cipher.iv + cipher.doFinal(dk)) }
        return SecretKeySpec(dk, "AES")
    }

    fun encryptByKeystore(data: ByteArray): Pair<ByteArray, ByteArray> =
        encryptBytesByPassword(getOrCreateDaemonKey(), data)

    fun decryptByKeystore(nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        decryptBytesByPassword(getOrCreateDaemonKey(), nonce, ciphertext)

    fun encryptFileByKeystore(inputFile: File, outputFile: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateDaemonKey())
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