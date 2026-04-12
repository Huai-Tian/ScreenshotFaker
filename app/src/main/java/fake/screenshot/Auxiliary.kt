package fake.screenshot

import android.content.Context
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

    fun getCurrentDateString(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    fun getRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }
    fun getVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: context.getString(R.string.unknown)
        } catch (_: Exception) {
            context.getString(R.string.unknown)
        }
    }

    fun getVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode
        } catch (_: Exception) {
            0L
        }
    }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.bufferedReader().readText() }
}