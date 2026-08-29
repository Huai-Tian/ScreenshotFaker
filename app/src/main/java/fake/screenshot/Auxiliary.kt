package fake.screenshot

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Auxiliary {
    private val suPaths = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/bin/su",
        "/vendor/bin/su", "/product/bin/su", "/data/local/xbin/su", "/data/local/bin/su"
    )
    private val moduleActivatedState by lazy { mutableStateOf(false) }
    val suBinaryPaths: Array<String> get() = suPaths
    var isModuleActivated: Boolean
        get() = moduleActivatedState.value
        set(value) {
            moduleActivatedState.value = value
        }

    private val shellActivatedState by lazy {
        mutableStateOf(
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        )
    }
    var isShellActivated: Boolean
        get() = shellActivatedState.value
        set(value) {
            shellActivatedState.value = value
        }

    private val rootActivatedState by lazy {
        mutableStateOf(
            try {
                (isShellActivated && Shizuku.getUid() == 0) || suPaths.any { File(it).exists() }
            } catch (_: Exception) {
                false
            }
        )
    }
    var isRootActivated: Boolean
        get() = rootActivatedState.value
        set(value) {
            rootActivatedState.value = value
        }

    fun hasSuBinary(): Boolean = suPaths.any { File(it).exists() }

    /**
     * 统一命令执行入口（Shizuku / 直连 su）。
     *
     * - Shizuku 可用 → 经 Shizuku shell（uid 决定权限，root 型 Shizuku 天然 uid=0）；
     * - Shizuku 不可用但 root 可用 → 本地直连 su：stdin 模式递交命令（cmdline
     *   仅剩 "su"，与 RootOverlayService 的无痕启动方式一致），redirectErrorStream
     *   合流 stderr（对齐 Hail HShell 的行为）；
     * - 都不可用 → 回退本地 sh（非 root，供探测类命令使用）。
     *
     * 任何一层失败返回 1 to 堆栈，与既有调用点约定（exitCode==0 判成功）不变。
     */
    fun exec(cmd: String) = runCatching {
        val shizukuBinder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (shizukuBinder != null && isShellActivated) {
            IShizukuService.Stub.asInterface(shizukuBinder)
                .newProcess(arrayOf("sh"), null, null)
                .run {
                    ParcelFileDescriptor.AutoCloseOutputStream(outputStream)
                        .use { it.write(cmd.toByteArray()) }
                    waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
                }
        } else if (isRootActivated) {
            ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(cmd.toByteArray()) }
                    // 合流后 stderr 已并入 stdout，勿再读 errorStream
                    // （与其共享管道，读已关闭端会抛 IOException）
                    waitFor() to inputStream.bufferedReader().use { it.readText() }
                        .also { destroy() }
                }
        } else {
            ProcessBuilder("sh")
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(cmd.toByteArray()) }
                    waitFor() to inputStream.bufferedReader().use { it.readText() }
                }
        }
    }.getOrElse {
        1 to it.stackTraceToString()
    }

    /**
     * exec 的二进制 stdin 变体：命令经 argv 递交（sh -c），stdin 管道完整保留给
     * 目标进程读取二进制数据（如守护进程的裸密钥 32 字节）。
     *
     * 命令不走 stdin 的原因：shell 以脚本模式读 stdin 时可能预读吞掉后续字节；
     * sh -c 则完全不消费 stdin。cmdline 仅含 "sh -c <cmd>"，不含 stdin 数据。
     */
    fun execWithStdin(cmd: String, stdinData: ByteArray) = runCatching {
        val shizukuBinder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (shizukuBinder != null && isShellActivated) {
            IShizukuService.Stub.asInterface(shizukuBinder)
                .newProcess(arrayOf("sh", "-c", cmd), null, null)
                .run {
                    ParcelFileDescriptor.AutoCloseOutputStream(outputStream)
                        .use { it.write(stdinData) }
                    waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
                }
        } else if (isRootActivated) {
            ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(stdinData) }
                    waitFor() to inputStream.bufferedReader().use { it.readText() }
                        .also { destroy() }
                }
        } else {
            ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(stdinData) }
                    waitFor() to inputStream.bufferedReader().use { it.readText() }
                }
        }
    }.getOrElse {
        1 to it.stackTraceToString()
    }

    fun execGetPid(cmd: String): Int? {
        val (exitCode, output) = exec(cmd)
        return if (exitCode == 0) {
            output.trim().toIntOrNull()
        } else {
            null
        }
    }

    fun killProcess(pid: Int): Boolean {
        val (exitCode, _) = exec("kill -2 $pid")
        return exitCode == 0
    }

    fun refreshShellState() {
        isShellActivated = try {
            val binder = Shizuku.getBinder()
            val result =
                binder != null && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            result
        } catch (_: Exception) {
            false
        }
        refreshRootState()
    }

    fun refreshRootState() {
        isRootActivated = when {
            isShellActivated && runCatching { Shizuku.getUid() == 0 }.getOrDefault(false) -> true
            isShellActivated -> exec("command -v su").first == 0
            else -> suPaths.any { File(it).exists() }
        }
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

    private val secureRandom = java.security.SecureRandom()

    /**
     * 随机字符串（字母数字，密码学安全）。
     *
     * 全项目随机命名的统一入口：内部为 SecureRandom——观测任意数量
     * 输出也无法恢复状态或预测后续输出（kotlin.random 的 XORWOW/
     * PCG 状态可由数十个输出恢复，曾经的旧实现因此不可用于安全命名）。
     * 输出不可预测即同时满足不可关联：跨会话的名字之间无统计关联。
     */
    fun getRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars[secureRandom.nextInt(allowedChars.size)] }
            .joinToString("")
    }

    /**
     * 随机字符串（首字符限字母数字，其余可含 '-'/'_'，密码学安全）。
     * 语义与 [getRandomString] 一致，仅字符集扩展（文件名/组件名等
     * 首字符受限场景），随机源同为 SecureRandom。
     */
    fun getRandomStringEx(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val fullChars = allowedChars + listOf('-', '_')
        val first = allowedChars[secureRandom.nextInt(allowedChars.size)]
        val rest = (1 until length)
            .map { fullChars[secureRandom.nextInt(fullChars.size)] }
            .joinToString("")
        return first + rest
    }

    /** 密码学安全随机整数（range 闭区间均匀分布）。 */
    fun getSecureRandomInt(range: IntRange): Int =
        range.first + secureRandom.nextInt(range.last - range.first + 1)

    /** 密码学安全随机浮点（[0,1) 均匀分布）。 */
    fun getSecureRandomFloat(): Float = secureRandom.nextFloat()

    /** 密码学安全随机长整数（[0,bound) 均匀分布，拒绝采样）。 */
    fun getSecureRandomLong(bound: Long): Long {
        require(bound > 0)
        while (true) {
            val bits = secureRandom.nextLong() ushr 1
            val v = bits % bound
            if (bits - v + (bound - 1) >= 0) return v // 无偏：丢弃造成模偏差的样本
        }
    }

    /** [getRandomString] 的别名（安全语义显式化场景使用）。 */
    fun getSecureRandomString(length: Int): String = getRandomString(length)

    @SuppressLint("BlockedPrivateApi")
    fun View.enableScreenshotExclusion(): Boolean {
        return try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/")

            val parent = rootView.parent ?: return false
            val surfaceField = parent.javaClass.getDeclaredField("mSurfaceControl")
            surfaceField.isAccessible = true
            val surfaceControl = surfaceField.get(parent) ?: return false

            val isValid = surfaceControl.javaClass.getDeclaredMethod("isValid")
            isValid.isAccessible = true
            if (isValid.invoke(surfaceControl) != true) return false

            val scClass = Class.forName("android.view.SurfaceControl")
            val transClass = Class.forName($$"android.view.SurfaceControl$Transaction")
            val constructor = transClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val transaction = constructor.newInstance()

            val setMethod = transClass.getDeclaredMethod(
                "setSkipScreenshot",
                scClass,
                Boolean::class.javaPrimitiveType
            )
            setMethod.invoke(transaction, surfaceControl, true)

            transClass.getDeclaredMethod("apply").invoke(transaction)
            true
        } catch (_: Exception) {
            false
        }
    }


    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader()
            .use { it.readText() }
}