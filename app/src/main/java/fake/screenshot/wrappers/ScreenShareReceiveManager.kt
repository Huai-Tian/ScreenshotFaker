package fake.screenshot.wrappers

import android.content.Context
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
        val raw = ConfigManager.getDataOnce(context, CONFIG_PREFIX + id, "")
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
                enableAudio = parts[7].toBoolean(),
                enableControl = parts[8].toBoolean(),
                password = parts.getOrElse(9) { "" }
            )
        }.getOrNull()
    }

    suspend fun saveConfig(
        context: Context,
        config: ScreenShareReceiverConfig
    ) {
        val raw = listOf(
            config.name, config.address, config.port.toString(), config.useSsh.toString(),
            config.sshPort.toString(), config.sshUserName, config.sshPassword,
            config.enableAudio.toString(), config.enableControl.toString(),
            config.password
        ).joinToString(SEPARATOR)
        ConfigManager.saveData(context, CONFIG_PREFIX + config.id, raw)
        val ids = loadIds(context).toMutableSet()
        ids.add(config.id)
        ConfigManager.saveData(context, IDS_KEY, ids.joinToString(","))
        // 配置已变更，缓存中的旧实例不再有效：停止并移除，
        // 下次使用时按新配置重建（新增时无缓存实例，无副作用）
        receivers.remove(config.id)?.stop()
    }

    suspend fun deleteConfig(context: Context, id: Int) {
        ConfigManager.saveData(context, CONFIG_PREFIX + id, "")
        val ids = loadIds(context).toMutableSet()
        ids.remove(id)
        ConfigManager.saveData(context, IDS_KEY, ids.joinToString(","))
        receivers.remove(id)?.stop()
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

    fun getOrCreate(config: ScreenShareReceiverConfig): ScreenShareReceiver =
        receivers.getOrPut(config.id) { ScreenShareReceiver(config) }

    fun get(id: Int): ScreenShareReceiver? = receivers[id]

    fun stopAll() {
        receivers.values.forEach { it.stop() }
    }
}
