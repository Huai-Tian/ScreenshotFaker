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
 * - 截屏限制 [securePolicy]：角色=被截者（该应用窗口在屏时整个屏幕的可截性）
 * - 检测屏蔽 ×3：角色=检测者（该应用注册的对抗性侦听回调是否被吞）
 * - [imageId]：角色=前台者（截图瞬间该应用前台时使用的替换图，E3 消费）
 */
data class HookTemplate(
    val id: String,
    val name: String,
    /** E1：截屏限制三态，见 [HookConfig.SECURE_*] 常量 */
    val securePolicy: Int,
    /** E2a：屏蔽截屏检测（ScreenCaptureCallback 派发吞噬） */
    val maskCaptureDetection: Boolean,
    /** E2b：屏蔽录屏检测（ScreenRecordingCallback 派发吞噬） */
    val maskRecordDetection: Boolean,
    /**
     * E2c：屏蔽悬浮窗检测（直接信号 = 触摸遮挡标志。模块自有悬浮窗标记
     * trustedOverlay，FLAG_WINDOW_IS_OBSCURED / PARTIALLY_OBSCURED 失活。
     * 检测者由焦点/用量等推理出的悬浮窗结论不在屏蔽范围——开关语义：
     * 只屏蔽直接信号，逻辑推断不处理）
     */
    val maskOverlayDetection: Boolean,
    /**
     * E2d：屏蔽焦点检测（直接信号 = 窗口焦点丢失。FOCUS_LOSS 失活；
     * 悬浮窗/小窗归因若依赖焦点信号属推理链副作用，非本开关目标）
     */
    val maskFocusDetection: Boolean = false,
    /**
     * E2e：屏蔽窗口显示完整性检测（直接信号 = TrustedPresentation 回调，
     * API 35+。SurfaceFlinger 计算的窗口实际渲染像素比例跌出阈值即
     * 回调 false → WINDOW_NOT_FULLY_PRESENTED）
     */
    val maskPresentationDetection: Boolean = false,
    /**
     * E4：自由浮窗穿透——小窗（WINDOWING_MODE_FREEFORM，含 OEM 小窗）
     * 模式下该应用窗口对截图/录屏隐身（skipScreenshot，露出下层内容）
     */
    val pierceFreeform: Boolean = false,
    /** E3：内容替换绑定图（中性文件 id，图片本体经 openRemoteFile 传输） */
    val imageId: String?,
)

data class HookConfig(
    /**
     * E1 全局三态：未配置应用（scope 未命中）的截屏限制，与
     * [HookTemplate.securePolicy] 同一取值域，显式模板覆盖之。
     * 对应需求"截屏限制…同时也支持全局的启用和禁用"。
     * 模块总闸由 LSPosed 模块启停承担，App 内不设总开关
     */
    val globalSecurePolicy: Int = SECURE_FOLLOW,
    /**
     * E3 全局截图替换开关（纯 UI 阶段，hook 侧暂不消费，E3 落地接入）。
     * 关闭时配置状态静默保留（图片文件与 [globalReplaceImage] 不清除），
     * 再开启时"已配置"直接恢复
     */
    val globalReplaceEnabled: Boolean = false,
    /**
     * E3 全局替换图：App 私有 files/replace/ 下的中性文件名（本体拷贝
     * 进私有目录，规避相册 Uri 权限过期/原图被删导致配置虚标）。
     * null = 未配置
     */
    val globalReplaceImage: String? = null,
    val templates: List<HookTemplate> = emptyList(),
    /** 包名 → 模板 id（显式映射；悬空引用按未配置处理） */
    val scope: Map<String, String> = emptyMap(),
    /**
     * E2a 激进检测过滤的包名集（应用详情页单独开关，独立于模板——
     * 未分配模板的应用也可开启）。开启 = 该应用的媒体域
     * ContentObserver 注册被接管，具体档位由子开关
     * [aggressiveAllowSelfMedia] 决定（见其 KDoc）
     */
    val aggressiveFilter: Set<String> = emptySet(),
    /**
     * E2a 激进过滤子开关「允许监听自身媒体事件」的包名集（默认开启——
     * 开启激进总开关时同步入集）：
     * - 在集（子开）：媒体域注册经参数替换为影子 observer 继续成功，
     *   派发按行级 owner_package_name 归属判定——仅放行 owner ==
     *   注册者自己的事件（自插探测通过，屏蔽不可自证），其余全吞
     * - 不在集（子关）：注册即吞（旧行为）——observer 永不触发，
     *   自插探测可识破，换来零归属查询开销
     * 总开关关闭时本集合无意义（不单独消费）
     */
    val aggressiveAllowSelfMedia: Set<String> = emptySet(),
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
            put("gp", config.globalSecurePolicy)
            put("re", config.globalReplaceEnabled)
            config.globalReplaceImage?.let { put("ri", it) }
            put("t", JSONArray().apply {
                config.templates.forEach { tpl ->
                    put(JSONObject().apply {
                        put("i", tpl.id)
                        put("n", tpl.name)
                        put("p", tpl.securePolicy)
                        put("c", tpl.maskCaptureDetection)
                        put("b", tpl.maskRecordDetection)
                        put("o", tpl.maskOverlayDetection)
                        put("x", tpl.maskFocusDetection)
                        put("w", tpl.maskPresentationDetection)
                        put("f", tpl.pierceFreeform)
                        tpl.imageId?.let { img -> put("g", img) }
                    })
                }
            })
            put("s", JSONObject(config.scope))
            put("af", JSONArray(config.aggressiveFilter))
            put("am", JSONArray(config.aggressiveAllowSelfMedia))
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
                        maskFocusDetection = o.optBoolean("x"),
                        maskPresentationDetection = o.optBoolean("w"),
                        pierceFreeform = o.optBoolean("f"),
                        imageId = o.optString("g").ifEmpty { null },
                    )
                )
            }
        }
        val scope = buildMap {
            val o = json.optJSONObject("s") ?: JSONObject()
            o.keys().forEach { pkg -> o.optString(pkg).ifEmpty { return@forEach }.let { put(pkg, it) } }
        }
        val aggressiveFilter = buildSet {
            val arr = json.optJSONArray("af") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optString(i).ifEmpty { continue }.let { add(it) }
            }
        }
        val aggressiveAllowSelfMedia = run {
            val arr = json.optJSONArray("am")
            // 旧配置迁移：am 键缺失 = 子开关未问世时的激进用户，
            // 按默认开启语义升格为影子模式
                ?: return@run aggressiveFilter
            buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).ifEmpty { continue }.let { add(it) }
                }
            }
        }
        return HookConfig(
            globalSecurePolicy = json.optInt("gp", HookConfig.SECURE_FOLLOW).coerceIn(0, 2),
            globalReplaceEnabled = json.optBoolean("re"),
            globalReplaceImage = json.optString("ri").ifEmpty { null },
            templates = templates,
            scope = scope,
            aggressiveFilter = aggressiveFilter,
            aggressiveAllowSelfMedia = aggressiveAllowSelfMedia,
        )
    }
}
