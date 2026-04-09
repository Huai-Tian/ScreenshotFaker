package fake.screenshot

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

object ConfigManager {
    @Composable
    fun <T> rememberValue(
        context: Context,
        key: String,
        defaultValue: T
    ): androidx.compose.runtime.State<T> {
        return getData(context, key, defaultValue)
            .collectAsStateWithLifecycle(initialValue = defaultValue)
    }

    fun <T> getData(context: Context, key: String, defaultValue: T): Flow<T> {
        return context.dataStore.data.map { preferences ->
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
        context.dataStore.edit { preferences ->
            @Suppress("UNCHECKED_CAST") // 加上这个注解消除警告
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
        return context.dataStore.data.map { preferences ->
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