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
    // 遗留盐（v1 格式）：仅用于解密历史产物，运行时拼装避免整串明文
    // 常量进 dex（编译器常量折叠对拼接字面量同样生效，故用 charArrayOf）
    private val LEGACY_SALT: ByteArray by lazy {
        String(
            charArrayOf(
                'S', 'c', 'r', 'e', 'e', 'n', 's', 'h', 'o', 't', 'F', 'a', 'k', 'e', 'r',
                'S', 'a', 'l', 't'
            )
        ).toByteArray()
    }

    // v2 格式：[1 字节版本 0x02][16 字节随机盐][12 字节 nonce][密文+tag]。
    // 旧格式为 [12 nonce][密文+tag] + 全局硬编码盐——两个问题：
    // 1) 所有安装共用同一盐 → 针对弱口令可预计算字典表跨用户复用；
    // 2) 盐常量本身是 APK 指纹特征串。
    // 版本字节与旧格式 nonce 首 Byte 有 1/256 碰撞概率：解密侧先按 v2
    // 尝试（GCM 认证失败必然抛异常）再回退旧格式，碰撞只多花一次派生
    const val V2_MAGIC: Int = 0x02
    const val V2_SALT_LENGTH = 16

    private const val PBKDF2_ITERATIONS = 200000
    private const val KEY_LENGTH = 256
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 128

    /** v1 遗留格式密钥派生（全局固定盐）——仅解密历史文件 */
    fun deriveKey(password: String): SecretKeySpec =
        deriveKey(password, LEGACY_SALT)

    /** v2 密钥派生：调用方传入随文件存储的随机盐 */
    fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec =
            PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }

    /** v2 随机盐生成 */
    fun generateSalt(): ByteArray =
        ByteArray(V2_SALT_LENGTH).also { SecureRandom().nextBytes(it) }

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
