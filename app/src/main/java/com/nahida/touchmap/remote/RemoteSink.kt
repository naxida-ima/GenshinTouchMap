package com.nahida.touchmap.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nahida.touchmap.net.TouchProtocol
import com.nahida.touchmap.mapper.EngineManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 接收端：把发射端的 keyId 指令转换为对游戏屏幕坐标的注入。
 * 游戏设备全程无真实触摸 → 不触发触摸仲裁 → 长按/多指稳定。
 */
class RemoteSink(
    private val screenW: Int,
    private val screenH: Int,
    private val keys: Map<Int, RemoteKey>
) {

    fun onCmd(keyId: Int, action: Int, vx: Float, vy: Float) {
        val cfg = keys[keyId] ?: return
        val injector = EngineManager.current() ?: return
        when (action) {
            TouchProtocol.ACTION_DOWN -> {
                injector.press(keyId, cfg.targetX * screenW, cfg.targetY * screenH)
            }
            TouchProtocol.ACTION_MOVE -> {
                if (cfg.isJoystick) {
                    val r = cfg.joystickRadius * screenH
                    injector.move(
                        keyId,
                        cfg.targetX * screenW + vx * r,
                        cfg.targetY * screenH + vy * r
                    )
                }
                // 普通按键无 move
            }
            TouchProtocol.ACTION_UP -> {
                injector.release(keyId)
            }
        }
    }
}

/** 接收端按键配置持久化 */
object RemoteKeyStore {

    private val Context.dataStore by preferencesDataStore(name = "remote_key_config")
    private val KEY_REMOTE_KEYS = stringPreferencesKey("remote_keys")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun keysFlow(context: Context): Flow<List<RemoteKey>> =
        context.dataStore.data.map { prefs ->
            runCatching {
                json.decodeFromString<List<RemoteKey>>(prefs[KEY_REMOTE_KEYS] ?: "")
            }.getOrDefault(emptyList()).ifEmpty { GENSHIN_PRESET }
        }

    suspend fun saveKeys(context: Context, list: List<RemoteKey>) {
        context.dataStore.edit { it[KEY_REMOTE_KEYS] = json.encodeToString(list) }
    }
}
