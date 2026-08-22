package com.nahida.touchmap.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nahida.touchmap.model.VirtualKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 按键配置持久化：DataStore + kotlinx.serialization。
 */
object ConfigStore {

    private val Context.dataStore by preferencesDataStore(name = "touchmap_config")

    private val KEY_KEYS = stringPreferencesKey("virtual_keys")
    private val KEY_EDIT_MODE = booleanPreferencesKey("edit_mode")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 按键配置流 */
    fun keysFlow(context: Context): Flow<List<VirtualKey>> =
        context.dataStore.data.map { prefs ->
            runCatching {
                json.decodeFromString<List<VirtualKey>>(prefs[KEY_KEYS] ?: "[]")
            }.getOrDefault(emptyList())
        }

    suspend fun saveKeys(context: Context, list: List<VirtualKey>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEYS] = json.encodeToString(list)
        }
    }

    fun editModeFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_EDIT_MODE] ?: false }

    suspend fun setEditMode(context: Context, edit: Boolean) {
        context.dataStore.edit { it[KEY_EDIT_MODE] = edit }
    }
}
