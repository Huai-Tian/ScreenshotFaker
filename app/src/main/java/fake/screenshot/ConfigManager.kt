package fake.screenshot

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
import java.util.concurrent.ConcurrentHashMap

private const val DATA_STORE_FILE_NAME = "encrypted_settings.preferences_pb"
private const val KEYSTORE_PREF_NAME = "tink_keyset"
private const val MASTER_KEY_URI = "android-keystore://tink_master_key"

object ConfigManager {
    private val dataStoreCache = ConcurrentHashMap<Context, DataStore<Preferences>>()

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
                produceFile = { appContext.filesDir.resolve("datastore/$DATA_STORE_FILE_NAME") }
            )
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