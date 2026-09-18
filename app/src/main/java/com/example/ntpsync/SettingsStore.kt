package com.example.ntpsync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore 单例：只存 NTP 服务器地址，不用 SQLite。 */
private val Context.ntpDataStore by preferencesDataStore(name = "ntp_settings")

/**
 * NTP 服务器地址的读取 / 保存。
 */
class SettingsStore(
    private val context: Context,
) {

    companion object {
        /** 默认局域网 NTP 服务器 */
        const val DEFAULT_SERVER = "192.168.1.100"

        private val KEY_SERVER = stringPreferencesKey("ntp_server")
    }

    /** 保存的服务器地址流；无保存值时发射默认值。 */
    val serverFlow: Flow<String> =
        context.ntpDataStore.data.map { prefs ->
            prefs[KEY_SERVER] ?: DEFAULT_SERVER
        }

    /** 保存用户输入的服务器地址。 */
    suspend fun saveServer(server: String) {
        context.ntpDataStore.edit { prefs ->
            prefs[KEY_SERVER] = server.trim()
        }
    }
}
