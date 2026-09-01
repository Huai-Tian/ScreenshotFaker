package fake.screenshot.wrappers

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.*
import androidx.datastore.tink.AeadSerializer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.crypto.tink.Aead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit
import fake.screenshot.Auxiliary

private const val DATA_STORE_FILE_NAME = "encrypted_settings.preferences_pb"
private const val KEYSTORE_PREF_NAME = "tink_keyset"
private const val MASTER_KEY_URI = "android-keystore://tink_master_key"
// 明文引用：DataStore 文件随机名（销毁后轮换）。key 不存在 = 默认名。
// 随机名与"从未销毁"不可区分，不暴露重置历史（与通知渠道随机化风格一致）
private const val INDEX_PREFS_NAME = "sync_preferences"
private const val KEY_DATA_REF = "data_ref"

object ConfigManager {
    private val dataStoreCache = ConcurrentHashMap<Context, DataStore<Preferences>>()

    // null = 未加载；持久化于明文 prefs（进程重启后仍指向销毁后的新文件）
    @Volatile
    private var dataStoreRef: String? = null

    // 常量引用"默认名"，非空 = 随机名
    private const val DATA_REF_DEFAULT = ""

    @Synchronized
    private fun currentDataRef(context: Context): String {
        var ref = dataStoreRef
        if (ref == null) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(INDEX_PREFS_NAME, Context.MODE_PRIVATE)
            ref = prefs.getString(KEY_DATA_REF, null)
            if (ref == null) {
                // 首次运行：立即随机化。此后"从未销毁"与"销毁后"均持随机 ref，
                // 状态机不可区分，消除"是否销毁过"侧信道。
                // 注意此处 ref 可能为随机名而非 DATA_REF_DEFAULT
                ref = Auxiliary.getSecureRandomString(32)
                // 旧版本升级迁移：默认名文件存在则复制到新随机名（避免升级丢配置）
                val def = dataStoreFile(appContext, DATA_REF_DEFAULT)
                if (def.exists()) {
                    // 历史迁移失败的半截副本兜底：前次迁移在 copyTo 中途/
                    // delete 失败时残留的随机名文件（不可解密但多文件并存
                    // 是"曾迁移失败"的弱侧信道，且永无清理时机——迁移只认
                    // 默认名、sweep 仅销毁时跑）。进入迁移即历史 ref 未
                    // 持久化（prefs 仍 null），任何随机名文件都不是当前
                    // 活跃文件，可安全清除
                    val datastoreDir = def.parentFile
                    datastoreDir?.listFiles()?.forEach { f ->
                        if (f.name != def.name) {
                            f.delete()
                            File(f.path + ".tmp").delete()
                        }
                    }
                    val copied = runCatching {
                        def.copyTo(dataStoreFile(appContext, ref), overwrite = true)
                    }.isSuccess
                    if (!copied) {
                        // 复制失败（瞬时 IO：磁盘满/短时不可写）：沿用默认名文件，
                        // 不删源、不持久化 ref——本次会话仍读旧配置，下次启动重试
                        // 迁移。旧实现无条件 delete：复制失败 + 删源 = 全部配置
                        // 静默清零回出厂态（误毁方向，违反"绝不误毁"红线）
                        dataStoreFile(appContext, ref).delete() // 清理半截副本
                        dataStoreRef = DATA_REF_DEFAULT
                        return DATA_REF_DEFAULT
                    }
                    // ref 持久化成功才删源：commit 失败（恰在复制耗尽最后磁盘
                    // 空间后）时源已删 + ref 仅存内存 = 下次启动两文件皆失，
                    // 全部配置静默孤儿化——与复制失败同族的误毁缺口，闭环
                    // 同法（源保留、下次启动重试迁移）
                    // androidx 的 edit(commit=true){...} 返回 Unit（内部丢弃
                    // commit() 的 Boolean），感知持久化失败必须走原始 Editor API
                    val committed = prefs.edit().putString(KEY_DATA_REF, ref).commit()
                    if (!committed) {
                        dataStoreFile(appContext, ref).delete() // 副本作废
                        dataStoreRef = DATA_REF_DEFAULT
                        return DATA_REF_DEFAULT
                    }
                    def.delete()
                    File(def.path + ".tmp").delete()
                }
            }
            dataStoreRef = ref
        }
        return ref
    }

    private fun dataStoreFile(context: Context, ref: String): File {
        val name = if (ref == DATA_REF_DEFAULT) DATA_STORE_FILE_NAME
        else "$DATA_STORE_FILE_NAME$ref"
        return context.applicationContext.filesDir.resolve("datastore/$name")
    }

    private fun getEncryptedDataStore(context: Context): DataStore<Preferences> {
        val appContext = context.applicationContext
        // 构造互斥：Kotlin ConcurrentMap.getOrPut 是"先构造后 putIfAbsent"，
        // 两个线程并发首访会各自构造 DataStore 实例——同文件第二个实例构造
        // 即抛 IllegalStateException("multiple DataStores active")。该异常
        // 曾被 IdleWatchdog.readIdleState 的 catch-all 吞成"密文不可读"并
        // 触发无头销毁（Boot/Alarm 侧 IO 协程与 MainActivity.touchIdle 并发
        // 首访的毫秒级窗口）。串行化后第二线程直接命中缓存。
        // 锁只覆盖首访构造（含 Keystore/Tink 初始化，~100-500ms 一次性），
        // 与 resetForCoercion 的 synchronized(this) 同一把锁，销毁轮换
        // ref 期间不会并发构造。
        synchronized(this) {
            return dataStoreCache.getOrPut(appContext) {
                val keysetManager = AndroidKeysetManager.Builder()
                    .withSharedPref(appContext, KEYSTORE_PREF_NAME, "tink_prefs")
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()

                val aead = keysetManager.keysetHandle.getPrimitive(
                    RegistryConfiguration.get(),
                    Aead::class.java
                )

                val serializer = AeadSerializer(
                    aead = aead,
                    wrappedSerializer = PreferencesFileSerializer,
                    associatedData = "fake.screenshot".encodeToByteArray()
                )

                DataStoreFactory.create(
                    serializer = serializer,
                    produceFile = { dataStoreFile(appContext, currentDataRef(appContext)) }
                )
            }
        }
    }

    /**
     * 胁迫销毁：删除当前密文配置并轮换文件名。旧 DataStore 实例的 scope
     * 无法取消，同路径重建会触发 "multiple DataStores active for the same
     * file"，因此清缓存后让新实例走随机新路径（旧实例随进程结束释放）。
     * 随机名不可反推销毁次数，无侧信道。
     * 清扫整个 datastore 目录而非仅当前文件：多次销毁/在途写入复活的历史
     * 文件若残留，磁盘上多文件并存本身就是"发生过销毁"的侧信道。
     */
    fun resetForCoercion(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            // 确保旧 ref 已解析（目录清扫需知道当前文件位置）
            currentDataRef(appContext)
            val newRef = Auxiliary.getSecureRandomString(32)
            appContext.getSharedPreferences(INDEX_PREFS_NAME, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(KEY_DATA_REF, newRef) }
            dataStoreRef = newRef
            dataStoreCache.remove(appContext)
            sweepDatastoreDirLocked(appContext)
        }
    }

    /**
     * 二次清扫（公开入口，销毁序列末尾调用兜底）：
     * 旧 DataStore 实例在途写入可能复活已删除的旧文件，延迟后清除。
     */
    fun sweepDatastoreDir(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            sweepDatastoreDirLocked(appContext)
        }
    }

    /** 删除 datastore 目录下除当前 ref 指向文件外的全部文件（含 .tmp） */
    private fun sweepDatastoreDirLocked(appContext: Context) {
        val keep = dataStoreFile(appContext, currentDataRef(appContext))
        val dir = keep.parentFile ?: return
        dir.listFiles()?.forEach { f ->
            if (f.absolutePath != keep.absolutePath) {
                f.delete()
                File(f.path + ".tmp").delete()
            }
        }
    }

    @Composable
    fun <T> rememberValue(
        context: Context,
        key: String,
        defaultValue: T
    ): State<T> {
        return getData(context, key, defaultValue)
            .collectAsStateWithLifecycle(initialValue = defaultValue)
    }

    fun <T> getData(context: Context, key: String, defaultValue: T): Flow<T> {
        return getEncryptedDataStore(context).data.map { preferences ->
            @Suppress("UNCHECKED_CAST")
            when (defaultValue) {
                is String -> preferences[stringPreferencesKey(key)] as? T ?: defaultValue
                is Int -> preferences[intPreferencesKey(key)] as? T ?: defaultValue
                is Boolean -> preferences[booleanPreferencesKey(key)] as? T ?: defaultValue
                is Long -> preferences[longPreferencesKey(key)] as? T ?: defaultValue
                is Float -> preferences[floatPreferencesKey(key)] as? T ?: defaultValue
                else -> throw IllegalArgumentException("Unsupported data type")
            }
        }
    }

    suspend fun <T> saveData(context: Context, key: String, value: T) {
        getEncryptedDataStore(context).edit { preferences ->
            when (value) {
                is String -> preferences[stringPreferencesKey(key)] = value
                is Int -> preferences[intPreferencesKey(key)] = value
                is Boolean -> preferences[booleanPreferencesKey(key)] = value
                is Long -> preferences[longPreferencesKey(key)] = value
                is Float -> preferences[floatPreferencesKey(key)] = value
                else -> throw IllegalArgumentException("Unsupported data type")
            }
        }
    }

    suspend fun <T> getDataOnce(context: Context, key: String, defaultValue: T): T {
        return getEncryptedDataStore(context).data.map { preferences ->
            @Suppress("UNCHECKED_CAST")
            when (defaultValue) {
                is String -> preferences[stringPreferencesKey(key)] as? T ?: defaultValue
                is Int -> preferences[intPreferencesKey(key)] as? T ?: defaultValue
                is Boolean -> preferences[booleanPreferencesKey(key)] as? T ?: defaultValue
                is Long -> preferences[longPreferencesKey(key)] as? T ?: defaultValue
                is Float -> preferences[floatPreferencesKey(key)] as? T ?: defaultValue
                else -> throw IllegalArgumentException("Unsupported data type")
            }
        }.first()
    }

    /**
     * DataStore 全量快照（备份用）：键名 -> 值（String/Int/Boolean/Long/Float）。
     * 排除超时看门狗键（idle_limit/idle_ts）：它们是设备安全态而非用户偏好，
     * 从旧备份恢复会以陈旧锚点重置死线——距备份超过档位时长的恢复操作
     * 直接触发销毁（不可逆，误毁方向）。armed 在明文 prefs，本就不在
     * DataStore 快照内
     */
    suspend fun snapshotAll(context: Context): Map<String, Any> =
        getEncryptedDataStore(context).data.first().asMap().entries
            .filter { it.key.name != "idle_limit" && it.key.name != "idle_ts" }
            .associate { it.key.name to it.value }

    /**
     * 恢复快照（合并覆盖：快照中的键覆盖现值，快照外的键保持现状——
     * DataStore 无单键删除 API，无法做到"先清空再恢复"）。类型不明的
     * 键跳过；看门狗键同样拒收（见 snapshotAll，双向封堵）
     */
    suspend fun restoreAll(context: Context, data: Map<String, Any>) {
        for ((key, value) in data) {
            if (key == "idle_limit" || key == "idle_ts") continue
            when (value) {
                is String -> saveData(context, key, value)
                is Int -> saveData(context, key, value)
                is Boolean -> saveData(context, key, value)
                is Long -> saveData(context, key, value)
                is Float -> saveData(context, key, value)
            }
        }
    }
}