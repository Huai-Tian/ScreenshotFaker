package fake.screenshot

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Auxiliary {
    private const val SALT = "ScreenshotFakerSalt"
    private const val PBKDF2_ITERATIONS = 200000
    private const val KEY_LENGTH = 256
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 128
    var isShellActivated by mutableStateOf(
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    )

    fun isModuleActivated() = false
    fun isRootActivated() = false
    fun exec(cmd: String) = runCatching {
        IShizukuService.Stub.asInterface(Shizuku.getBinder())
            .newProcess(arrayOf("sh"), null, null)
            .run {
                ParcelFileDescriptor.AutoCloseOutputStream(outputStream)
                    .use { it.write(cmd.toByteArray()) }
                waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
            }
    }.getOrElse {
        1 to it.stackTraceToString()
    }

    fun getCurrentTimestampSeconds(): Long = System.currentTimeMillis() / 1000

    fun isTimestampValid(timestamp: Long, allowedSkewSeconds: Long = 10): Boolean {
        val now = getCurrentTimestampSeconds()
        return kotlin.math.abs(now - timestamp) <= allowedSkewSeconds
    }

    fun getCurrentDateString(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

    fun getRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    fun deriveKey(password: String): SecretKeySpec {
        val spec =
            PBEKeySpec(password.toCharArray(), SALT.toByteArray(), PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secret = factory.generateSecret(spec)
        val key = SecretKeySpec(secret.encoded, "AES")
        return key
    }

    fun encryptData(key: SecretKeySpec, plaintext: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Pair(nonce, ciphertext)
    }

    fun decryptData(key: SecretKeySpec, nonce: ByteArray, ciphertextWithTag: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, nonce))
        val plain = cipher.doFinal(ciphertextWithTag)
        return String(plain, Charsets.UTF_8)
    }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.bufferedReader().readText() }
}