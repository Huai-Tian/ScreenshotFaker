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

private const val DATA_STORE_FILE_NAME = "encrypted_settings.preferences_pb"
private const val KEYSTORE_PREF_NAME = "tink_keyset"
private const val MASTER_KEY_URI = "android-keystore://tink_master_key"
private const val INDEX_PREFS_NAME = "sync_preferences"
private const val KEY_DATA_INDEX = "data_index"

object ConfigManager {
    private val dataStoreCache = ConcurrentHashMap<Context, DataStore<Preferences>>()

    @Volatile
    private var dataStoreGeneration: Int = -1

    private fun currentGeneration(context: Context): Int {
        var gen = dataStoreGeneration
        if (gen < 0) {
            gen = context.applicationContext
                .getSharedPreferences(INDEX_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_DATA_INDEX, 0)
            dataStoreGeneration = gen
        }
        return gen
    }

    private fun dataStoreFile(context: Context, gen: Int): File {
        val name = if (gen == 0) DATA_STORE_FILE_NAME else "$DATA_STORE_FILE_NAME$gen"
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
                produceFile = { dataStoreFile(appContext, currentGeneration(appContext)) }
            )
        }
    }

    fun resetForCoercion(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            val oldFile = dataStoreFile(appContext, currentGeneration(appContext))
            val newGen = currentGeneration(appContext) + 1
            appContext.getSharedPreferences(INDEX_PREFS_NAME, Context.MODE_PRIVATE)
                .edit(commit = true) { putInt(KEY_DATA_INDEX, newGen) }
            dataStoreGeneration = newGen
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