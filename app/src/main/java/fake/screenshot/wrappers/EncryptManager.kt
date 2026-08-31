package fake.screenshot.wrappers

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 纯软件密码学原语（无状态、无 Android 依赖、无密钥落盘）：
 * PBKDF2 派生 + AES-GCM 加解密（String 与 ByteArray 两种形态）。
 *
 * 防御语义（密钥拆分/迁移事务/pepper/敏感字段）已迁至 defense 包：
 * - fake.screenshot.defense.KeyVault（DK 拆分、迁移事务、Keystore pepper）
 * - fake.screenshot.defense.SensitiveStore（敏感字段 DK 二次加密）
 * 本类不再持有 appContext 与任何初始化状态。
 */
object EncryptManager {
    //Software
    private const val SALT = "ScreenshotFakerSalt"
    private const val PBKDF2_ITERATIONS = 200000
    private const val KEY_LENGTH = 256
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 128

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
}
