package fake.screenshot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
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

    @Suppress("unused")
    fun encryptByKeystore(data: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateHardwareKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ciphertext = cipher.doFinal(data)
        return Pair(cipher.iv, ciphertext)
    }

    @Suppress("unused")
    fun decryptByKeystore(nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = getOrCreateHardwareKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, nonce))
        }
        return cipher.doFinal(ciphertext)
    }

    fun encryptFileByKeystore(inputFile: File, outputFile: File) {
        val key = getOrCreateHardwareKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
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

    fun decryptFileByKeystore(inputFile: File, outputFile: File) {
        val key = getOrCreateHardwareKey()
        inputFile.inputStream().use { input ->
            val iv = ByteArray(NONCE_LENGTH).also { input.read(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
            }
            CipherInputStream(input, cipher).use { cis ->
                outputFile.outputStream().use { output ->
                    cis.copyTo(output)
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