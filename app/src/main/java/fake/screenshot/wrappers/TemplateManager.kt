package fake.screenshot.wrappers

import android.content.Context
import fake.screenshot.LSPosedServiceManager
import fake.screenshot.hooks.HookConfig
import fake.screenshot.hooks.HookConfigCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App 侧 hook 配置管理：模板模型在加密 DataStore 的持久化（隐私态），
 * 与向 LSPosed 托管区 RemotePreferences 的单向导出（运行态）。
 *
 * 两个存储区的职责边界：
 * - 加密 DataStore：唯一权威源。Tink 密文，密钥在 Keystore（vault 哲学），
 *   胁迫销毁时随 ConfigManager.resetForCoercion 轮换清除
 * - RemotePreferences（group "sync"）：hook 进程的只读投递通道。落盘在
 *   LSPosed 托管目录而非模块私有目录——DataStore 的销毁清扫触及不到，
 *   因此导出内容必须是 AES 信封（见 HookConfigCodec），且销毁序列显式
 *   中和远端键（[neutralizeRemoteForCoercion]）
 *
 * 导出时序闭环：保存时即时导出（服务未连接则静默跳过）+ 服务绑定时
 * catch-up 导出（覆盖未连接期的全部变更）。断连期间的变更在下一次
 * 绑定必然送达，无丢失窗口。
 */
object TemplateManager {
    /** DataStore 键：密文态无泄露面，但备份快照（snapshotAll）会暴露明文键名，故取中性名 */
    private const val KEY_CONFIG = "tpl_cfg"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 上次已导出的信封（去重：内容未变的重复导出只产生无谓 binder 流量与 hook 侧 reload） */
    @Volatile
    private var lastExported: String? = null

    /** 配置流（UI 消费，phase 5 模板页） */
    fun configFlow(context: Context): Flow<HookConfig> =
        ConfigManager.getData(context, KEY_CONFIG, "")
            .map { HookConfigCodec.decode(it.ifEmpty { null }) }

    suspend fun saveConfig(context: Context, config: HookConfig) {
        ConfigManager.saveData(context, KEY_CONFIG, HookConfigCodec.encode(config))
        exportNow(context)
    }

    /** 服务绑定 catch-up（onServiceBind 调用）：补发未连接期的变更 */
    fun onServiceBound(context: Context) {
        scope.launch { runCatching { exportNow(context) } }
    }

    private suspend fun exportNow(context: Context) {
        val service = LSPosedServiceManager.mService ?: return
        val raw = ConfigManager.getDataOnce(context, KEY_CONFIG, "")
        if (raw == lastExported) return
        val editor = service.getRemotePreferences(HookConfigCodec.REMOTE_GROUP).edit()
        if (raw.isEmpty()) {
            editor.remove(HookConfigCodec.REMOTE_KEY) // 无配置：远端回到"键不存在"= hook 侧解码 null = 全关
        } else {
            editor.putString(HookConfigCodec.REMOTE_KEY, raw)
        }
        editor.apply() // 异步 binder 提交：导出非关键路径，失败由下次保存/绑定重试
        lastExported = raw
    }

    /**
     * 胁迫销毁中和（ConfigManager.resetForCoercion 末尾调用）：
     * remove 远端键（同步 commit——销毁序列要求中和在本进程结束前落定）。
     * hook 侧收到变更事件 → 解码 null → 全关默认态。
     * 服务未绑定（残留无法即时清除）时：留在托管区的仅是中性键名下的
     * AES 信封密文，无语义可扫描；下次绑定 exportNow 以空配置 remove 覆盖。
     */
    fun neutralizeRemoteForCoercion() {
        val service = LSPosedServiceManager.mService ?: return
        runCatching {
            service.getRemotePreferences(HookConfigCodec.REMOTE_GROUP)
                .edit().remove(HookConfigCodec.REMOTE_KEY).commit()
        }
        lastExported = null
    }
}
