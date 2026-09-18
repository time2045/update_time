package com.example.ntpsync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore 单例：存 NTP 服务器地址列表，不用 SQLite。 */
private val Context.ntpDataStore by preferencesDataStore(name = "ntp_settings")

/**
 * NTP 服务器地址列表的读取 / 保存。
 *
 * 多地址用换行符拼成一个字符串存放，保证顺序不丢失；
 * 兼容老版本的单地址存档（key 为 ntp_server），首次读取时自动迁移。
 */
class SettingsStore(
    private val context: Context,
) {

    companion object {
        /** 默认 NTP 服务器：阿里云公共时间同步服务 */
        const val DEFAULT_SERVER = "ntp.aliyun.com"

        /** 首次启动的默认列表 */
        val DEFAULT_SERVERS = listOf(DEFAULT_SERVER)

        private const val SEPARATOR = "\n"

        private val KEY_SERVERS = stringPreferencesKey("ntp_servers")

        /** 老版本单地址存档，只读做迁移 */
        private val KEY_SERVER_LEGACY = stringPreferencesKey("ntp_server")
    }

    /** 保存的服务器地址流（按用户排序）；无存档时发射默认值。 */
    val serversFlow: Flow<List<String>> =
        context.ntpDataStore.data.map { prefs ->
            val raw = prefs[KEY_SERVERS]
            if (raw != null) {
                raw.split(SEPARATOR)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { DEFAULT_SERVERS }
            } else {
                val legacy = prefs[KEY_SERVER_LEGACY]?.trim()
                if (!legacy.isNullOrEmpty()) listOf(legacy) else DEFAULT_SERVERS
            }
        }

    /** 保存用户维护的服务器地址列表（顺序即同步优先级）。 */
    suspend fun saveServers(servers: List<String>) {
        val clean = servers.map { it.trim() }.filter { it.isNotEmpty() }
        context.ntpDataStore.edit { prefs ->
            if (clean.isEmpty()) {
                prefs.remove(KEY_SERVERS)
            } else {
                prefs[KEY_SERVERS] = clean.joinToString(SEPARATOR)
            }
        }
    }
}
