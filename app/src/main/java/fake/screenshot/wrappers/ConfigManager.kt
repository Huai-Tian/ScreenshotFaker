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

    private fun currentDataRef(context: Context): String {
        var ref = dataStoreRef
        if (ref == null) {
            ref = context.applicationContext
                .getSharedPreferences(INDEX_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DATA_REF, DATA_REF_DEFAULT)!!
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

    /**
     * 胁迫销毁：删除当前密文配置并轮换文件名。旧 DataStore 实例的 scope
     * 无法取消，同路径重建会触发 "multiple DataStores active for the same
     * file"，因此清缓存后让新实例走随机新路径（旧实例随进程结束释放）。
     * 随机名不可反推销毁次数，无侧信道。
     */
    fun resetForCoercion(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            val oldFile = dataStoreFile(appContext, currentDataRef(appContext))
            val newRef = Auxiliary.getSecureRandomString(32)
            appContext.getSharedPreferences(INDEX_PREFS_NAME, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(KEY_DATA_REF, newRef) }
            dataStoreRef = newRef
            dataStoreCache.remove(appContext)
            oldFile.delete()
            File(oldFile.path + ".tmp").delete()
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
}