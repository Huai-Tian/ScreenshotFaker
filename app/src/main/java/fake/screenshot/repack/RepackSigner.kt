package fake.screenshot.repack

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * 重打包 APK 签名器。
 *
 * 修改过 manifest 的 APK 已无法用原签名（私钥不在手上），必须重签：
 * 使用 AndroidKeyStore 内生成的 RSA 密钥做 v2/v3 签名。minSdk 30 起 v2 签名
 * 即为完整签名方案，且 v2 签名只追加 APK Signing Block、不改动 ZIP 条目布局，
 * 可保住 resources.arsc 的对齐。
 *
 * 反特征策略：每次重打包都生成全新密钥与随机证书主题。
 * 任何应用都能读取已安装包的签名证书（GET_SIGNING_CERTIFICATES），
 * 若跨克隆复用同一密钥或固定 CN（本项目开源，固定 CN 一旦入库即可精准匹配），
 * 单次特征匹配就能关联所有克隆。代价是克隆之间签名互不相同、无法同签名互相
 * 更新——换取证书指纹完全不可关联。
 */
object RepackSigner {

    /** 与项目 minSdk 一致；显式指定以固定 apksig 的摘要算法选择，不依赖其自动探测 */
    private const val MIN_SDK = 30

    /**
     * 重打包密钥别名前缀。别名只存在于本应用自身的 AndroidKeyStore 内，
     * 对第三方应用不可见；仅用于重打包前清理历史密钥，避免槽位无限累积。
     */
    private const val ALIAS_PREFIX = "repack_"

    private val random = SecureRandom()

    fun sign(inputApk: File, outputApk: File) {
        val (privateKey, certificate) = generateFreshKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "CERT",
            privateKey,
            listOf(certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setMinSdkVersion(MIN_SDK)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .build()
            .sign()
    }

    /**
     * 签名后自检：在设备上对产物做完整验签。
     * 若 AndroidKeyStore 签名链路产出无效签名，这里会给出精确原因，
     * 而不是把坏包交给系统安装器报"安装包已损坏"。
     */
    fun verifySignedApk(apk: File): ApkVerifier.Result =
        ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(MIN_SDK)
            .build()
            .verify()

    /**
     * 每次签名生成全新密钥：
     * 1. 先删除历史重打包密钥——已安装克隆的验签用的是 APK 内嵌证书，
     *    删除旧私钥不影响已装应用，仅意味着旧克隆无法再收到同签名更新（预期取舍）；
     * 2. 以随机别名生成新的 RSA 密钥对，密钥不可导出。
     */
    private fun generateFreshKey(): Pair<PrivateKey, X509Certificate> {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.aliases().toList()
            .filter { it.startsWith(ALIAS_PREFIX) }
            .forEach { runCatching { keyStore.deleteEntry(it) } }

        val alias = ALIAS_PREFIX + randomToken()
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                // 随机证书主题：固定 CN 是跨克隆的硬关联特征；
                // 拟真的随机姓名组合与海量普通自签名开发证书无异，
                // 避免"乱码 CN"本身成为重打包的软特征
                .setCertificateSubject(randomSubject())
                .setDigests(KeyProperties.DIGEST_SHA256)
                // apksig 对 RSA 使用 SHA256withRSA（PKCS#1 v1.5）
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()
        )
        generator.generateKeyPair()

        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to entry.certificate as X509Certificate
    }

    private fun randomToken(length: Int = 16): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length).map { chars[random.nextInt(chars.size)] }.joinToString("")
    }

    private fun randomSubject(): X500Principal {
        val first = FIRST_NAMES[random.nextInt(FIRST_NAMES.size)]
        val last = LAST_NAMES[random.nextInt(LAST_NAMES.size)]
        return X500Principal("CN=$first $last")
    }

    private val FIRST_NAMES = listOf(
        "James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael",
        "Linda", "David", "Elizabeth", "William", "Barbara", "Richard", "Susan",
        "Joseph", "Jessica", "Thomas", "Sarah", "Charles", "Karen", "Daniel",
        "Lisa", "Matthew", "Nancy", "Anthony", "Betty", "Mark", "Sandra",
        "Andrew", "Ashley", "Joshua", "Kimberly"
    )

    private val LAST_NAMES = listOf(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
        "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
        "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
        "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
        "Ramirez", "Lewis", "Robinson", "Walker", "Young"
    )
}
