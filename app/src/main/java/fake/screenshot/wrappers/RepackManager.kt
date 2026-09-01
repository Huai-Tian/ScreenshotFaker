package fake.screenshot.wrappers

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.LocaleListCompat
import fake.screenshot.repack.ApkBuilder
import fake.screenshot.repack.AxmlEditor
import fake.screenshot.repack.RepackSigner
import fake.screenshot.repack.ResourceTableParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import androidx.core.graphics.scale
import androidx.core.net.toUri
import fake.screenshot.Auxiliary

/**
 * 重打包身份配置。
 *
 * 应用名与应用描述需要按地区提供：简体中文环境写入中文应用名/描述，
 * 其他地区（英语等）写入英文应用名/描述。
 */
data class RepackIdentity(
    val packageName: String,
    val appNameEn: String,
    val appNameZh: String,
    val descriptionEn: String = "",
    val descriptionZh: String = ""
) {
    /** 当前系统地区是否为简体中文 */
    val isSimplifiedChinese: Boolean
        get() = detectSimplifiedChinese()

    /**
     * 按当前系统地区解析出的应用名；对应语言未填写时返回 null，
     * 表示保留原 APK 中的应用名不变。
     */
    fun resolveAppName(): String? =
        (if (isSimplifiedChinese) appNameZh else appNameEn).takeIf { it.isNotBlank() }

    /**
     * 按当前系统地区解析出的应用描述；对应语言未填写时返回 null，
     * 表示保留原 APK 中的应用描述不变。
     */
    fun resolveDescription(): String? =
        (if (isSimplifiedChinese) descriptionZh else descriptionEn).takeIf { it.isNotBlank() }

    /** 输入校验，返回 null 表示合法，否则为错误原因 */
    fun validate(): String? {
        if (!PACKAGE_NAME_REGEX.matches(packageName)) return "包名不合法"
        if (packageName == "fake.screenshot") return "包名必须与当前包名不同"
        // 应用名/描述长度上限：AXML 字符串池对超长值本身可编码（>0x7FFF
        // 双字扩展），但 launcher 显示与安装器解析对极端长度无合理用途，
        // 上界同时封死"用户粘贴整段文本"造成的池体积失控
        if (appNameEn.length > MAX_LABEL_LENGTH || appNameZh.length > MAX_LABEL_LENGTH) {
            return "应用名过长（≤${MAX_LABEL_LENGTH} 字符）"
        }
        if (descriptionEn.length > MAX_LABEL_LENGTH || descriptionZh.length > MAX_LABEL_LENGTH) {
            return "应用描述过长（≤${MAX_LABEL_LENGTH} 字符）"
        }
        return null
    }

    companion object {
        private val PACKAGE_NAME_REGEX =
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        /** 应用名/描述长度上限（字符） */
        private const val MAX_LABEL_LENGTH = 200

        /** 简体中文 = zh-Hans 或 zh-CN / zh-SG / zh-MY / 无地区后缀的 zh */
        fun detectSimplifiedChinese(): Boolean {
            val locales = LocaleListCompat.getDefault()
            for (index in 0 until locales.size()) {
                val locale = locales[index] ?: continue
                if (!locale.language.equals("zh", ignoreCase = true)) continue
                if (locale.script.equals("Hans", ignoreCase = true)) return true
                when (locale.country.uppercase(Locale.ROOT)) {
                    "CN", "SG", "MY", "" -> return true
                }
            }
            return false
        }
    }
}

object RepackManager {

    // repack 互斥：RepackSigner.sign 每次签名前删除所有 repack_ 前缀别名，
    // 并发的第二次 repack 会删掉第一次正在使用的私钥令签名中途失败；
    // UI 单对话框防重入不构成互斥（对话框状态不约束其他入口）。加锁后
    // 同一进程内 repack 严格串行
    private val repackMutex = kotlinx.coroutines.sync.Mutex()

    /** install 等待系统安装器最终状态的兜底超时（毫秒），见 [installSession] */
    private const val INSTALL_RESULT_TIMEOUT_MS = 10 * 60 * 1000L


    suspend fun repack(
        context: Context,
        identity: RepackIdentity,
        icon: Bitmap? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            identity.validate()?.let { throw IllegalArgumentException(it) }
            repackMutex.withLock {
                val appContext = context.applicationContext
                val sourceApk = File(appContext.applicationInfo.sourceDir)
                // 历史孤儿清扫：repack 成功返回后若调用方未走到 install（进程
                // 死亡/用户放弃/页面销毁），已签名克隆 APK 会无限期残留 cacheDir
                // ——install 内部的删除逻辑覆盖不到该窗口。工作目录名均为
                // 20..35 位随机串（本方法唯一落盘来源），入口全量清扫：
                // 当前会话目录在锁内创建于清扫之后，不受影响
                sweepOrphanWorkDirs(appContext)
                val workDir = File(
                    appContext.cacheDir,
                    Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(20..35))
                ).apply { mkdirs() }
                val unsignedApk = File(workDir, Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(20..35)))
                val signedApk = File(workDir, Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(20..35)))

                try {
                    val replacements = buildReplacements(appContext, sourceApk, identity, icon)
                    ApkBuilder.build(sourceApk, unsignedApk, replacements)
                    RepackSigner.sign(unsignedApk, signedApk)
                    unsignedApk.delete()
                    // 签名自检：AndroidKeyStore 签名链路产出无效签名时在此给出
                    // 精确原因，而不是把坏包交给系统安装器报"安装包已损坏"
                    val verifyResult = RepackSigner.verifySignedApk(signedApk)
                    check(!verifyResult.containsErrors()) {
                        "verify failed: " + verifyResult.errors.joinToString("; ")
                    }
                    signedApk
                } catch (t: Throwable) {
                    // 失败清理：含已改写 manifest 的完整中间产物不得残留在 cacheDir
                    //（敏感中间产物 + 磁盘泄漏）；删除整个随机名工作目录
                    runCatching { workDir.deleteRecursively() }
                    throw t
                }
            }
        }
    }

    /** 清扫 cacheDir 下的 repack 孤儿工作目录（20..35 位随机名，见 repack） */
    private fun sweepOrphanWorkDirs(appContext: Context) {
        runCatching {
            appContext.cacheDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name.length in 20..35 &&
                    dir.name.all { it.isLetterOrDigit() }
                ) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    /**
     * install 的异步最终结果（commit 只代表"已提交系统安装器"，
     * 用户确认/取消与安装成败在之后的系统回调里才见分晓）。
     * - [SUCCESS]/[FAILURE]：系统安装器给出的终态（主线程回调）
     * - [TIMEOUT]：确认窗口超时（用户一直未在系统弹窗操作，接收器
     *   兜底注销，防泄漏；会话仍在，用户可回系统安装器继续）
     */
    enum class InstallResult { SUCCESS, FAILURE, TIMEOUT }

    @SuppressLint("RequestInstallPackagesPolicy")
    fun install(
        context: Context,
        apk: File,
        newPackageName: String,
        onInstallResult: ((InstallResult, String?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        try {
            installSession(appContext, apk, newPackageName, onInstallResult)
            // commit 前 APK 已完整拷入安装会话（openWrite + fsync），commit 后
            // 系统不再引用源文件，此刻删除数据安全。不保留到"安装成功"：
            // 已签名的伪装克隆 APK 残留 cacheDir 本身是暴露面（root 取证可
            // 直接取得伪装身份产物），重试代价只是重新打包（秒级）
            apk.delete()
            runCatching { apk.parentFile?.delete() }
        } catch (t: Throwable) {
            // 全部失败路径（未授权安装/包名已装/会话异常/进程死亡前抛出）同样
            // 不得残留已签名克隆 APK——"删除只在成功路径执行"会让首次使用
            // （跳设置开权限必然失败一次）与所有失败重试无限期泄漏产物
            runCatching { apk.delete() }
            runCatching { apk.parentFile?.delete() }
            throw t
        }
    }

    private fun installSession(
        appContext: Context,
        apk: File,
        newPackageName: String,
        onInstallResult: ((InstallResult, String?) -> Unit)?
    ) {
        val pm = appContext.packageManager

        if (runCatching { pm.getPackageInfo(newPackageName, 0) }.isSuccess) {
            throw IllegalStateException("package already installed: $newPackageName")
        }

        if (!pm.canRequestPackageInstalls()) {
            appContext.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${appContext.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw IllegalStateException("install permission not granted")
        }

        val statusAction = "fake.screenshot.repack.INSTALL_STATUS"
        val mainHandler = Handler(Looper.getMainLooper())

        // 终态（成功/失败/超时）到达后一次性收尾；PENDING_USER_ACTION 不是
        // 终态——注销后系统不会再发最终状态广播，旧实现首广播即注销 =
        // 用户取消/安装失败时 UI 永远停留在"请在系统弹窗中确认安装"
        var finished = false
        var receiverRef: BroadcastReceiver? = null
        var timeoutAction: Runnable? = null
        var sessionId = -1

        fun finishReceiver() {
            if (finished) return
            finished = true
            timeoutAction?.let { mainHandler.removeCallbacks(it) }
            receiverRef?.let { runCatching { appContext.unregisterReceiver(it) } }
        }

        val statusReceiver = object : BroadcastReceiver() {
            @SuppressLint("UnsafeIntentLaunch")
            override fun onReceive(ctx: Context, intent: Intent) {
                // 并发两次 repack（第一次还在等系统确认弹窗）共用同一 action：
                // 按会话 ID 过滤，各接收器只消费自己会话的回调
                if (sessionId != -1 &&
                    intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId
                ) {
                    return
                }
                when (intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent = IntentCompat.getParcelableExtra(
                            intent, Intent.EXTRA_INTENT, Intent::class.java
                        ) ?: return
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { appContext.startActivity(confirmIntent) }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        finishReceiver()
                        onInstallResult?.invoke(InstallResult.SUCCESS, null)
                    }

                    else -> {
                        finishReceiver()
                        onInstallResult?.invoke(InstallResult.FAILURE, statusReason(intent))
                    }
                }
            }
        }
        receiverRef = statusReceiver
        ContextCompat.registerReceiver(
            appContext, statusReceiver, IntentFilter(statusAction),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 确认窗口兜底超时：系统弹窗无自动超时，用户搁置则接收器与回调
        // 永久悬挂（泄漏 + UI 无反馈）。10 分钟覆盖正常确认时长；超时只
        // 注销接收器并回报 TIMEOUT，会话由系统按自身策略回收
        val timeout = Runnable {
            finishReceiver()
            onInstallResult?.invoke(InstallResult.TIMEOUT, null)
        }
        timeoutAction = timeout
        mainHandler.postDelayed(timeout, INSTALL_RESULT_TIMEOUT_MS)

        val receiverSender = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(statusAction).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender

        val installer = pm.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(newPackageName) }
        sessionId = try {
            installer.createSession(params)
        } catch (t: Throwable) {
            // 注册/超时已挂载，createSession 失败必须同样收尾（防接收器泄漏）
            finishReceiver()
            throw t
        }
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(receiverSender)
            }
        } catch (t: Throwable) {
            finishReceiver()
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    /** 终态失败的可读原因：状态类别 + 系统附加消息（均非敏感信息） */
    private fun statusReason(intent: Intent): String {
        val name = when (intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "storage"
            else -> "failure"
        }
        return intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.let { "$name: $it" } ?: name
    }


    @SuppressLint("DiscouragedApi")
    private fun buildReplacements(
        context: Context,
        sourceApk: File,
        identity: RepackIdentity,
        icon: Bitmap?
    ): Map<String, ByteArray> {
        val replacements = HashMap<String, ByteArray>()
        ZipFile(sourceApk).use { zip ->
            fun readEntry(name: String): ByteArray? =
                zip.getEntry(name)?.let { zip.getInputStream(it).readBytes() }

            val manifest = AxmlEditor(
                readEntry("AndroidManifest.xml") ?: error("AndroidManifest.xml not found")
            )
            check(
                manifest.setAttributeValue(
                    "manifest", "package", identity.packageName, androidNs = false
                )
            ) { "manifest package attribute not found" }
            identity.resolveAppName()?.let { name ->
                check(
                    manifest.setAttributeValue("application", "label", name)
                ) { "application label attribute not found" }
            }
            identity.resolveDescription()?.let { description ->
                manifest.setAttributeValue("application", "description", description)
            }

            val oldPrefix = "${context.packageName}."
            val newPrefix = "${identity.packageName}."
            for (element in listOf("permission", "uses-permission")) {
                manifest.replaceAttributeValuePrefix(element, "name", oldPrefix, newPrefix)
            }
            manifest.replaceAttributeValuePrefix("provider", "authorities", oldPrefix, newPrefix)

            replacements["AndroidManifest.xml"] = manifest.build()

            if (icon != null) {
                val arsc = ResourceTableParser(
                    readEntry("resources.arsc") ?: error("resources.arsc not found")
                )
                val resources = context.resources
                val iconResId = resources.getIdentifier("ic_launcher", "mipmap", arsc.packageName)
                val iconRoundResId =
                    resources.getIdentifier("ic_launcher_round", "mipmap", arsc.packageName)
                for (resId in intArrayOf(iconResId, iconRoundResId)) {
                    if (resId == 0) continue
                    for (path in arsc.pathsForResource(resId)) {
                        val lower = path.lowercase()
                        val format = when {
                            lower.endsWith(".webp") -> Bitmap.CompressFormat.WEBP_LOSSY
                            lower.endsWith(".png") -> Bitmap.CompressFormat.PNG
                            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ->
                                Bitmap.CompressFormat.JPEG
                            lower.endsWith(".xml") -> continue
                            else -> Bitmap.CompressFormat.PNG
                        }
                        val original = readEntry(path) ?: continue
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(original, 0, original.size, options)
                        val size = maxOf(options.outWidth, options.outHeight)
                            .takeIf { it > 0 } ?: 192
                        val scaled = if (icon.width == size && icon.height == size) icon
                        else icon.scale(size, size)
                        val encoded = ByteArrayOutputStream().also {
                            scaled.compress(format, 100, it)
                        }.toByteArray()
                        replacements[path] = encoded
                    }
                }

                for (resId in intArrayOf(iconResId, iconRoundResId)) {
                    if (resId == 0) continue
                    arsc.removeAdaptiveIconEntries(resId)
                }
                arsc.buildIfPatched()?.let { replacements["resources.arsc"] = it }
            }
        }
        return replacements
    }

}
