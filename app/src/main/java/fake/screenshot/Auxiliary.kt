package fake.screenshot

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.mutableStateOf
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Auxiliary {
    // exec 系超时上限：子进程 stdout 写满管道缓冲（~64KB）且本端
    // 未消费时，waitFor 永不返回（经典读写死锁）；root 授权弹窗
    // 无人确认、Shizuku binder 无响应等也会无限挂起——超时后放弃
    // 等待（子进程由 destroy 兜底终止），调用方按失败处理。120s
    // 覆盖最长合法场景（守护脚本前台运行前的快速命令均为秒级；
    // "sh watchPath" 类阻塞调用见各调用方，不受此影响——它们
    // 依赖脚本自身退出语义，超时仅作死锁兜底）
    private const val EXEC_TIMEOUT_MS = 120_000L

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

    fun exec(cmd: String) = runCatching {
        val shizukuBinder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (shizukuBinder != null && isShellActivated) {
            IShizukuService.Stub.asInterface(shizukuBinder)
                .newProcess(arrayOf("sh"), null, null)
                .run {
                    ParcelFileDescriptor.AutoCloseOutputStream(outputStream)
                        .use { it.write(cmd.toByteArray()) }
                    drainAndAwait(
                        inputText = { inputStream.text.ifBlank { errorStream.text } },
                        waitAction = { waitFor() },
                        destroyAfter = { destroy() }
                    )
                }
        } else if (isRootActivated) {
            ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(cmd.toByteArray()) }
                    drainAndAwait(
                        inputText = { inputStream.bufferedReader().use { it.readText() } },
                        waitAction = { waitFor() },
                        destroyAfter = { destroy() }
                    )
                }
        } else {
            ProcessBuilder("sh")
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(cmd.toByteArray()) }
                    drainAndAwait(
                        inputText = { inputStream.bufferedReader().use { it.readText() } },
                        waitAction = { waitFor() },
                        destroyAfter = null
                    )
                }
        }
    }.getOrElse {
        1 to it.stackTraceToString()
    }

    fun execWithStdin(cmd: String, stdinData: ByteArray) = runCatching {
        val shizukuBinder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (shizukuBinder != null && isShellActivated) {
            IShizukuService.Stub.asInterface(shizukuBinder)
                .newProcess(arrayOf("sh", "-c", cmd), null, null)
                .run {
                    ParcelFileDescriptor.AutoCloseOutputStream(outputStream)
                        .use { it.write(stdinData) }
                    drainAndAwait(
                        inputText = { inputStream.text.ifBlank { errorStream.text } },
                        waitAction = { waitFor() },
                        destroyAfter = { destroy() }
                    )
                }
        } else if (isRootActivated) {
            ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(stdinData) }
                    drainAndAwait(
                        inputText = { inputStream.bufferedReader().use { it.readText() } },
                        waitAction = { waitFor() },
                        destroyAfter = { destroy() }
                    )
                }
        } else {
            ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
                .run {
                    outputStream.use { it.write(stdinData) }
                    drainAndAwait(
                        inputText = { inputStream.bufferedReader().use { it.readText() } },
                        waitAction = { waitFor() },
                        destroyAfter = null
                    )
                }
        }
    }.getOrElse {
        1 to it.stackTraceToString()
    }

    /**
     * 先消费输出再等待退出（修复两类挂起）：
     * 1. 读写死锁：旧实现 waitFor 在前、读输出在后——子进程 stdout
     *    写满管道缓冲（~64KB）时阻塞在 write，waitFor 永不返回；
     *    Shizuku 分支 errorStream 是独立管道，同样存在此问题
     * 2. 无限挂起：root 授权弹窗无人确认 / Shizuku binder 无响应 /
     *    daemon 自拷贝失败就地 daemonize（永不退出）——读流或等待
     *    之一无限阻塞，调用方（含 startDaemon 的 mutex）永久卡死
     *
     * 读取放到独立线程与 waitFor 并行，先到者胜：正常路径读取完成 +
     *    进程退出即返回；输出未完但超时到达则放弃等待（退出码按失败
     *    处理），destroyAfter 兜底终止子进程/回收 Shizuku 进程对象
     *
     * [waitAction] 显式传入等待逻辑（Process.waitFor / ShizukuProcess
     * .waitFor）：旧实现依赖调用方以 .run{} 包裹、waitFor 经外层
     * lambda 接收者隐式解析——跨函数边界的接收者约定极脆弱（K2 对
     * runCatching{ waitFor() } 的类型参数 R 无法推断），显式化断开耦合
     */
    private fun drainAndAwait(
        inputText: () -> String,
        waitAction: () -> Int,
        destroyAfter: (() -> Unit)?
    ): Pair<Int, String> {
        val output = StringBuilder()
        val reader = Thread {
            runCatching { output.append(inputText()) }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = runCatching {
            reader.join(EXEC_TIMEOUT_MS)
            reader.isAlive.not()
        }.getOrDefault(false)
        return if (finished) {
            val code = runCatching { waitAction() }.getOrDefault(1)
            destroyAfter?.invoke()
            code to output.toString()
        } else {
            // 超时（死锁/挂起）：终止子进程回收资源，按失败返回。
            // destroy 会令阻塞中的读流抛 IOException 结束读线程
            destroyAfter?.invoke()
            -1 to output.toString()
        }
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

    /**
     * 带身份复核的进程终止：仅当 /proc/<pid>/cmdline 仍含预期标记时发信号。
     * PID 复用防护——screenrecord 早期崩溃后其 PID 被系统复用时，
     * 不复核的 kill -2 会打到无关进程（与 daemon 侧 proc_start_ticks
     * 复核同一语义；app 侧无法直接读 /proc starttime，用 cmdline 标记
     * 替代——复用者 cmdline 含同一标记的概率可忽略）。
     * @return true = 已发信号；false = 进程不存在或身份不符（未发信号）
     */
    fun killProcessIfCmdlineMatches(pid: Int, marker: String): Boolean {
        if (pid <= 0) return false
        val (exitCode, _) = exec(
            "grep -q ${shellQuote(marker)} /proc/$pid/cmdline 2>/dev/null && kill -2 $pid"
        )
        return exitCode == 0
    }

    /** sh 安全引用：单引号包裹，内部单引号转义为 '\''（与 daemon 侧 shell_quote 同语义） */
    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

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
        // 注意：不含 ':'——tag 带冒号会拼出畸形的 logcat tag:pri 过滤器
        val special = setOf(
            '_',
            '-',
            '/',
            '.',
            '+',
            '@',
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
                // 命名分组（如 (?<name>...)）：Kotlin 放行而 libc++ std::regex
                // 构造抛异常 → daemon 侧 isRegexValid 拒绝 → 触发规则被静默禁用，
                // 必须在此侧同步拦下
                Regex("\\(\\?<[^=!]").containsMatchIn(pattern) ||
                pattern.contains("++") ||                           // 所有格量词（如 a++）
                Regex("\\\\p\\{").containsMatchIn(pattern) ||       // Unicode 属性（如 \p{L}）
                pattern.contains("\\A") ||                          // 输入开头锚点
                pattern.contains("\\z") ||                          // 输入结尾锚点
                pattern.contains("\\G") ||                          // 上次匹配结尾锚点
                Regex("\\\\[hRv]").containsMatchIn(pattern)
        return@all !cppIncompatible
    }

    fun getCurrentTimestampSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * SHA-256 摘要的 hex 表示（小写，64 字符）。
     * 用于 SSH 主机密钥指纹（TOFU）：指纹是公开值（公钥哈希），仅作
     * 比对/展示，不涉密；失败返回空串（调用方按校验失败处理，fail-closed）
     */
    fun sha256Hex(data: ByteArray): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun isTimestampValid(timestamp: Long, allowedSkewSeconds: Long = 10): Boolean {
        val now = getCurrentTimestampSeconds()
        return kotlin.math.abs(now - timestamp) <= allowedSkewSeconds
    }

    fun getCurrentDateString(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

    fun applyDefinedTimestamp(timestamp: String, path: String) {
        val t = timestamp.trim()
        if (t.isEmpty()) return
        val fmt = runCatching {
            java.time.LocalDateTime.parse(
                t, DateTimeFormatter.ofPattern("yyyy-M-d H:m")
            ).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm.ss"))
        }.getOrNull() ?: return
        // fmt 由 LocalDateTime 格式化而来（纯数字+点，无元字符）；path 含
        // 用户可控的保存路径/前缀/后缀，必须 shellQuote——裸单引号包裹遇
        // 路径内单引号即闭合逃逸，构成用户自伤型命令注入（root 模式放大），
        // 与磁贴输出路径的引用修复同语义
        exec("touch -t $fmt ${shellQuote(path)}")
    }

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

    /**
     * 高强度随机密码（大小写字母 + 数字 + 特殊字符，密码学安全）。
     * 特殊字符集刻意避开 shell 元字符与引号（' " ` $ \ ; & | < > ( )
     * 空白），避免密码进入任何命令行/env 构造时的转义负担；避开
     * 视觉易混淆字符（0/O、1/l/I）。每组至少含一个类别的字符，
     * 洗牌防类别位置固定。
     */
    fun getStrongPassword(length: Int): String {
        require(length >= 8)
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnpqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#%^*-=+?~"
        val all = upper + lower + digits + symbols
        fun pick(set: String) = set[secureRandom.nextInt(set.length)]
        val chars = mutableListOf(
            pick(upper), pick(lower), pick(digits), pick(symbols)
        )
        repeat(length - chars.size) { chars.add(pick(all)) }
        // Fisher-Yates 洗牌（SecureRandom 驱动）
        for (i in chars.size - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return chars.joinToString("")
    }

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