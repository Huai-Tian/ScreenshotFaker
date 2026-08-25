package fake.screenshot

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import androidx.core.graphics.scale
import androidx.core.net.toUri

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
        return null
    }

    companion object {
        private val PACKAGE_NAME_REGEX =
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

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

    suspend fun repack(
        context: Context,
        identity: RepackIdentity,
        icon: Bitmap? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            identity.validate()?.let { throw IllegalArgumentException(it) }
            val appContext = context.applicationContext
            val sourceApk = File(appContext.applicationInfo.sourceDir)
            // 私有缓存目录：仅本应用可读，中间产物对外不可见
            val workDir = File(appContext.cacheDir, Auxiliary.getRandomStringEx((20..35).random())).apply { mkdirs() }
            val unsignedApk = File(workDir, Auxiliary.getRandomStringEx((20..35).random()))
            val signedApk = File(workDir, Auxiliary.getRandomStringEx((20..35).random()))

            val replacements = buildReplacements(appContext, sourceApk, identity, icon)
            ApkBuilder.build(sourceApk, unsignedApk, replacements)
            RepackSigner.sign(unsignedApk, signedApk)
            unsignedApk.delete()

            signedApk
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    fun install(context: Context, apk: File, newPackageName: String) {
        val appContext = context.applicationContext
        val pm = appContext.packageManager

        if (runCatching { pm.getPackageInfo(newPackageName, 0) }.isSuccess) {
            throw IllegalStateException()
        }

        if (!pm.canRequestPackageInstalls()) {
            appContext.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${appContext.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw IllegalStateException()
        }

        val statusAction = "fake.screenshot.repack.INSTALL_STATUS"

        val statusReceiver = object : BroadcastReceiver() {
            @SuppressLint("UnsafeIntentLaunch")
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching { appContext.unregisterReceiver(this) }
                if (intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    ) == PackageInstaller.STATUS_PENDING_USER_ACTION
                ) {
                    val confirmIntent = IntentCompat.getParcelableExtra(
                        intent, Intent.EXTRA_INTENT, Intent::class.java
                    ) ?: return
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { appContext.startActivity(confirmIntent) }
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext, statusReceiver, IntentFilter(statusAction),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val receiverSender = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(statusAction).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(newPackageName) }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(receiverSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            runCatching { appContext.unregisterReceiver(statusReceiver) }
            throw t
        }

        apk.delete()
        runCatching { apk.parentFile?.delete() }
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
                val resources = context.resources
                val iconResId = resources.getIdentifier("ic_launcher", "mipmap", context.packageName)
                val iconRoundResId =
                    resources.getIdentifier("ic_launcher_round", "mipmap", context.packageName)

                val arsc = ResourceTableParser(
                    readEntry("resources.arsc") ?: error("resources.arsc not found")
                )
                for (resId in intArrayOf(iconResId, iconRoundResId)) {
                    if (resId == 0) continue
                    for (path in arsc.pathsForResource(resId)) {
                        val lower = path.lowercase()
                        val format = when {
                            lower.endsWith(".webp") -> Bitmap.CompressFormat.WEBP_LOSSLESS
                            lower.endsWith(".png") -> Bitmap.CompressFormat.PNG
                            else -> continue
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

                val primaryResId = if (iconResId != 0) iconResId else iconRoundResId
                if (primaryResId != 0) {
                    val primaryPaths = arsc.pathsForResource(primaryResId).toHashSet()
                    val roundPaths =
                        if (iconRoundResId != 0) arsc.pathsForResource(iconRoundResId).toHashSet()
                        else hashSetOf()
                    val hasBitmap = { paths: Set<String> ->
                        paths.any { it.endsWith(".webp") || it.endsWith(".png") }
                    }
                    val carrierResId = if (primaryResId == iconResId && iconRoundResId != 0 &&
                        hasBitmap(roundPaths)
                    ) iconRoundResId else primaryResId
                    val carrierPaths =
                        if (carrierResId == primaryResId) primaryPaths else roundPaths
                    val bestBitmap = arsc.bestBitmapPath(carrierResId)

                    if (bestBitmap != null) {
                        for (xmlPath in carrierPaths.filter { it.endsWith(".xml") }) {
                            arsc.repointPath(carrierResId, xmlPath, bestBitmap)
                        }

                        if (carrierResId != primaryResId) {
                            for (entry in zip.entries().asSequence()) {
                                if (entry.name !in primaryPaths || !entry.name.endsWith(".xml")) continue
                                val editor = runCatching {
                                    AxmlEditor(zip.getInputStream(entry).readBytes())
                                }.getOrNull() ?: continue
                                if (editor.rootElementName != "adaptive-icon") continue
                                editor.setAttributeReference("background", "drawable", carrierResId)
                                editor.setAttributeReference("monochrome", "drawable", carrierResId)
                                editor.setAttributeReference(
                                    "foreground", "drawable", android.R.color.transparent
                                )
                                replacements[entry.name] = editor.build()
                            }
                        }

                        arsc.buildIfPatched()?.let { replacements["resources.arsc"] = it }
                    }
                }
            }
        }
        return replacements
    }

}
