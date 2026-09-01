package fake.screenshot.wrappers

import android.content.Context
import fake.screenshot.defense.SensitiveStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 接收端实例与配置的管理器（单例）。
 *
 * [ScreenShareReceiver] 是普通类，每个实例接收一路共享（对应一部发送设备），
 * 本管理器以单例形式统一管理：
 * - 配置持久化：以分隔符序列化存储于加密 DataStore（ConfigManager），
 *   支持保存任意多个接收配置；
 * - 实例生命周期：按配置 id 缓存活跃的接收端实例。
 */
object ScreenShareReceiverManager {

    private const val IDS_KEY = "receive_screen_share_config_ids"
    private const val CONFIG_PREFIX = "receive_screen_share_config_"
    private const val SEPARATOR = "\u001F"

    private val receivers = ConcurrentHashMap<Int, ScreenShareReceiver>()

    suspend fun loadConfigs(context: Context): List<ScreenShareReceiverConfig> {
        return loadIds(context).mapNotNull { loadConfig(context, it) }
    }

    suspend fun loadConfig(
        context: Context,
        id: Int
    ): ScreenShareReceiverConfig? {
        // 接收配置含 SSH 服务器凭据与共享认证密码：整包经 DK 第二层加密存储
        // （防 root-as-uid 读 DataStore 提取用户的基础设施凭据）。
        // 页面在门禁之后，DK 恒可用；迁移由 getSensitive 自动完成
        val raw = SensitiveStore.getSensitive(context, CONFIG_PREFIX + id, "")
        if (raw.isEmpty()) return null
        val parts = raw.split(SEPARATOR)
        if (parts.size < 9) return null
        return runCatching {
            ScreenShareReceiverConfig(
                id = id,
                name = parts[0],
                address = parts[1],
                port = parts[2].toInt(),
                useSsh = parts[3].toBoolean(),
                sshPort = parts[4].toInt(),
                sshUserName = parts[5],
                sshPassword = parts[6],
                // 槽位 7/8 原为 enableAudio/enableControl，已随"自动适配通道"移除，
                // 读取时跳过（旧数据的值无意义）；槽位 9 为 password
                password = parts.getOrElse(9) { "" }
            )
        }.getOrNull()
    }

    /**
     * @return false = DK 不可用导致密文写入失败（配置未保存——继续登记 id
     * 会造成"保存成功"的假象，刷新后配置消失）
     */
    suspend fun saveConfig(
        context: Context,
        config: ScreenShareReceiverConfig
    ): Boolean {
        // 槽位 7/8 写固定占位保持格式稳定（password 固定在槽位 9），
        // 使旧版本数据与新数据共用同一解析路径
        val raw = listOf(
            config.name, config.address, config.port.toString(), config.useSsh.toString(),
            config.sshPort.toString(), config.sshUserName, config.sshPassword,
            "true", "true",
            config.password
        ).joinToString(SEPARATOR)
        if (!SensitiveStore.putSensitive(context, CONFIG_PREFIX + config.id, raw)) {
            return false
        }
        val ids = loadIds(context).toMutableSet()
        ids.add(config.id)
        ConfigManager.saveData(context, IDS_KEY, ids.joinToString(","))
        // 配置已变更，缓存中的旧实例不再有效：停止并移除，
        // 下次使用时按新配置重建（新增时无缓存实例，无副作用）
        receivers.remove(config.id)?.stop()
        return true
    }

    /** @return false = 密文清除失败（配置仍可能复活——调用方应提示） */
    suspend fun deleteConfig(context: Context, id: Int): Boolean {
        if (!SensitiveStore.putSensitive(context, CONFIG_PREFIX + id, "")) {
            return false
        }
        val ids = loadIds(context).toMutableSet()
        ids.remove(id)
        ConfigManager.saveData(context, IDS_KEY, ids.joinToString(","))
        receivers.remove(id)?.stop()
        return true
    }

    suspend fun nextId(context: Context): Int {
        return (loadIds(context).maxOrNull() ?: 0) + 1
    }

    private suspend fun loadIds(context: Context): List<Int> {
        return ConfigManager.getDataOnce(context, IDS_KEY, "")
            .split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
    }

    fun getOrCreate(context: Context, config: ScreenShareReceiverConfig): ScreenShareReceiver =
        receivers.getOrPut(config.id) {
            // applicationContext：实例跨页面生命周期存活，持 activity 级
            // context 会泄漏——接收端 TOFU 指纹存取只需应用级 context
            ScreenShareReceiver(config, context.applicationContext)
        }

    fun get(id: Int): ScreenShareReceiver? = receivers[id]

    fun stopAll() {
        receivers.values.forEach { it.stop() }
    }
}
