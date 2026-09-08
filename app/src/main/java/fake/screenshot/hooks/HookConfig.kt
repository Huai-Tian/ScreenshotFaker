@file:Suppress("unused")

package fake.screenshot.hooks

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hook 层配置模型（App 侧写入 / hook 进程读取，同一 APK 内共享此类）。
 *
 * 双映射结构（HMA-OSS 心智）：templates 定义命名配置组（全部由用户创建，
 * 不预置任何现成模板），scope 把包名映射到模板（多对一）。同一包名在
 * 不同引擎中扮演不同角色，模板把全部角色字段捆绑为一个配置组：
 * - 截屏管控 [securePolicy]：角色=被截者（该应用窗口在屏时整个屏幕的可截性）
 * - 检测屏蔽 ×3：角色=检测者（该应用注册的对抗性侦听回调是否被吞）
 * - [imageId]：角色=前台者（截图瞬间该应用前台时使用的替换图，E3 消费）
 */
data class HookTemplate(
    val id: String,
    val name: String,
    /** E1：截屏管控三态，见 [HookConfig.SECURE_*] 常量 */
    val securePolicy: Int,
    /** E2a：屏蔽截屏检测（ScreenCaptureCallback 派发吞噬） */
    val maskCaptureDetection: Boolean,
    /** E2b：屏蔽录屏检测（ScreenRecordingCallback 派发吞噬） */
    val maskRecordDetection: Boolean,
    /** E2d：屏蔽悬浮窗检测（obscured 遮挡参与位 + TrustedPresentation） */
    val maskOverlayDetection: Boolean,
    /** E3：内容替换绑定图（中性文件 id，图片本体经 openRemoteFile 传输） */
    val imageId: String?,
)

data class HookConfig(
    /** 总开关：false 时一切查询返回原生行为（fail-open，绝不干扰系统） */
    val masterEnabled: Boolean = false,
    /**
     * E1 全局三态：未配置应用（scope 未命中）的截屏管控，与
     * [HookTemplate.securePolicy] 同一取值域，显式模板覆盖之。
     * 对应需求"截屏限制…同时也支持全局的启用和禁用"——全局层只有
     * E1 一个轴（检测屏蔽/替换图均为 per-app），刻意不做 HMA 式
     * "默认模板"隐式应用：未配置应用除 E1 外一律原生行为
     */
    val globalSecurePolicy: Int = SECURE_FOLLOW,
    val templates: List<HookTemplate> = emptyList(),
    /** 包名 → 模板 id（显式映射；悬空引用按未配置处理） */
    val scope: Map<String, String> = emptyMap(),
) {
    companion object {
        /** 全关默认态：与未安装模块的原生行为不可区分 */
        val DEFAULT = HookConfig()

        const val SECURE_FOLLOW = 0 // 跟随应用自身 FLAG_SECURE
        const val SECURE_ALLOW = 1  // 强制允许（穿透，对标 DisableFlagSecure）
        const val SECURE_DENY = 2   // 强制禁止（未设 FLAG_SECURE 也拒截）
    }

    /**
     * 显式模板解析（仅 scope 命中，无隐式默认回落）。
     * 悬空引用（模板已删、scope 未清）容忍为 null = 未配置。
     */
    fun templateFor(pkg: String?): HookTemplate? {
        if (pkg == null) return null
        val id = scope[pkg] ?: return null
        return templates.firstOrNull { it.id == id }
    }

    /** E1 三态解析：显式模板 → 全局三态（更具体者胜） */
    fun securePolicyFor(pkg: String?): Int =
        templateFor(pkg)?.securePolicy ?: globalSecurePolicy
}

/**
 * 配置编解码：模型 ⇄ JSON ⇄ AES-GCM 信封（Base64 文本）。
 *
 * 传输/落盘路径：App 侧（Tink 加密 DataStore，隐私态）→ 编码 →
 * RemotePreferences 单键 → LSPosed 托管区（运行态）→ hook 进程解码。
 *
 * 为什么信封必须加密：RemotePreferences 落盘在 LSPosed 托管目录，
 * 不在模块私有目录——DataStore 的胁迫销毁清扫（sweep）触及不到它。
 * 若明文导出，包名与策略将作为永久残留对抗取证（正是 data_ref 随机化
 * 要消除的那类侧信道的加强版）。加密后磁盘上仅存中性键名 + 密文，
 * 泛化取证扫描（关键字检索包名/策略语义）不可命中。
 *
 * 密钥边界的诚实说明：密钥派生自源码内常量，仓库公开即等于公开——
 * 这是反泛化扫描的混淆层，不是对抗定向逆向的密码学边界（后者本就
 * 不成立：模块 APK 可被任何人获取）。可升级路径为从已安装模块 APK
 * 签名派生（仓库不可得），但 repack 自重打包会破坏派生一致性，暂不做。
 */
object HookConfigCodec {
    /** RemotePreferences 组名与键名：刻意中性，与 OverlayServiceManager 的 s_a..s_d 哲学一致 */
    const val REMOTE_GROUP = "sync"
    const val REMOTE_KEY = "s_c"

    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128

    // 派生过程无秘密性诉求（见类注释），SHA-256 展开仅为得到 256-bit AES 密钥
    private val AES_KEY by lazy {
        val seed = "sf.hookcfg.v1::2f6d9a41c8e7b305d1a4f8c2e6b09d47"
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
    }

    fun encode(config: HookConfig): String {
        val json = JSONObject().apply {
            put("v", 1)
            put("m", config.masterEnabled)
            put("gp", config.globalSecurePolicy)
            put("t", JSONArray().apply {
                config.templates.forEach { tpl ->
                    put(JSONObject().apply {
                        put("i", tpl.id)
                        put("n", tpl.name)
                        put("p", tpl.securePolicy)
                        put("c", tpl.maskCaptureDetection)
                        put("b", tpl.maskRecordDetection)
                        put("o", tpl.maskOverlayDetection)
                        tpl.imageId?.let { img -> put("g", img) }
                    })
                }
            })
            put("s", JSONObject(config.scope))
        }.toString()

        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonce + ct, Base64.NO_WRAP)
    }

    /**
     * 任何失败（null / 空串 / Base64 损坏 / GCM 认证失败 / JSON 结构异常 /
     * 未知版本）一律回落 [HookConfig.DEFAULT]：hook 全关 = 原生行为。
     * hook 进程内绝不因配置问题抛异常（system_server 崩溃 = 整机软重启）。
     */
    fun decode(raw: String?): HookConfig {
        if (raw.isNullOrEmpty()) return HookConfig.DEFAULT
        return runCatching {
            val all = Base64.decode(raw, Base64.NO_WRAP)
            require(all.size > NONCE_LEN) { "truncated envelope" }
            val nonce = all.copyOfRange(0, NONCE_LEN)
            val ct = all.copyOfRange(NONCE_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val json = JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
            fromJson(json)
        }.getOrDefault(HookConfig.DEFAULT)
    }

    private fun fromJson(json: JSONObject): HookConfig {
        if (json.optInt("v") != 1) return HookConfig.DEFAULT
        val templates = buildList {
            val arr = json.optJSONArray("t") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("i")
                if (id.isEmpty()) continue
                add(
                    HookTemplate(
                        id = id,
                        name = o.optString("n"),
                        securePolicy = o.optInt("p", HookConfig.SECURE_FOLLOW).coerceIn(0, 2),
                        maskCaptureDetection = o.optBoolean("c"),
                        maskRecordDetection = o.optBoolean("b"),
                        maskOverlayDetection = o.optBoolean("o"),
                        imageId = o.optString("g").ifEmpty { null },
                    )
                )
            }
        }
        val scope = buildMap {
            val o = json.optJSONObject("s") ?: JSONObject()
            o.keys().forEach { pkg -> o.optString(pkg).ifEmpty { return@forEach }.let { put(pkg, it) } }
        }
        return HookConfig(
            masterEnabled = json.optBoolean("m"),
            globalSecurePolicy = json.optInt("gp", HookConfig.SECURE_FOLLOW).coerceIn(0, 2),
            templates = templates,
            scope = scope,
        )
    }
}
