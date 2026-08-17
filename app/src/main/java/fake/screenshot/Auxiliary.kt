package fake.screenshot

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Auxiliary {
    var isModuleActivated by mutableStateOf(false)
    var isShellActivated by mutableStateOf(
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    )

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

    fun isConfigValid(vararg config: String) = config.all {
        val special = setOf(
            '_',
            '-',
            '/',
            '.',
            '+',
            '@',
            ':',
            '=',
            '%',
            ','
        )
        it.isEmpty() || it.all { char ->
            char.isLetterOrDigit() || char in special
        }
    }

    fun isRegexValid(vararg patterns: String) = patterns.all { pattern ->
        if (pattern.isEmpty()) return@all true
        try {
            Regex(pattern)
        } catch (_: Exception) {
            return@all false
        }
        val cppIncompatible = pattern.contains("(?<=") ||          // 后顾断言
                pattern.contains("(?<!") ||                        // 负后顾断言
                pattern.contains("++") ||                           // 所有格量词（如 a++）
                Regex("\\\\p\\{").containsMatchIn(pattern) ||       // Unicode 属性（如 \p{L}）
                pattern.contains("\\A") ||                          // 输入开头锚点
                pattern.contains("\\z") ||                          // 输入结尾锚点
                pattern.contains("\\G") ||                          // 上次匹配结尾锚点
                Regex("\\\\[hRv]").containsMatchIn(pattern)
        return@all !cppIncompatible
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

    fun getRandomStringEx(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val fullChars = allowedChars + listOf('-', '_')
        val first = allowedChars.random()
        val rest = (1 until length)
            .map { fullChars.random() }
            .joinToString("")
        return first + rest
    }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.bufferedReader().readText() }
}