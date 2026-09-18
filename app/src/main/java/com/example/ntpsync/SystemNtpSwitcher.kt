package com.example.ntpsync

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 系统 NTP 一键切换。
 *
 * 原理：WRITE_SECURE_SETTINGS 是签名级权限，普通安装拿不到，
 * 但可以用 ADB 一次性授予（无需恢复出厂、无需删除账号，重启不掉）：
 *
 *   adb shell pm grant com.example.ntpsync android.permission.WRITE_SECURE_SETTINGS
 *
 * 授权后 App 即可修改 Settings.Global.ntp_server（系统 NTP 服务器），
 * 并通过开关 auto_time 触发系统立即重新同步。走的都是系统正规设置，
 * 不是破解，也不需要 Device Owner。
 *
 * 注意：ntp_server 只接受主机名/IP（系统 NTP 固定用 UDP 123），
 * 用户列表里的自定义端口在这里会被忽略，只取 host 部分。
 */
class SystemNtpSwitcher(
    private val context: Context,
) {

    companion object {
        private const val TAG = "NtpSync"

        const val GRANT_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

        /** 一键授权命令（展示给用户复制执行，只需执行一次） */
        const val GRANT_CMD =
            "adb shell pm grant com.example.ntpsync android.permission.WRITE_SECURE_SETTINGS"

        /**
         * 系统 NTP 服务器的 settings key。
         * 注意：Settings.Global 没有提供该常量，平台内部用的就是裸字符串，
         * 与 `settings put global ntp_server <host>` 是同一个 key。
         */
        private const val KEY_NTP_SERVER = "ntp_server"

        /** 关/开 auto_time 之间的间隔，给系统留出状态落盘时间 */
        private const val TOGGLE_DELAY_MILLIS = 1000L
    }

    /** 是否已获得一次性 ADB 授权 */
    fun hasPermission(): Boolean =
        context.packageManager.checkPermission(
            GRANT_PERMISSION,
            context.packageName,
        ) == PackageManager.PERMISSION_GRANTED

    /** 读取当前系统 NTP 服务器（未设置时返回 null） */
    fun getSystemServer(): String? =
        try {
            Settings.Global.getString(context.contentResolver, KEY_NTP_SERVER)
        } catch (e: Exception) {
            Log.w(TAG, "读取系统 NTP 失败: ${e.message}")
            null
        }

    /**
     * 把系统 NTP 切换到指定主机，并触发一次同步。
     *
     * 必须在后台线程调用（内部已切换到 Dispatchers.IO）。
     * 切换后系统在后台完成同步（通常 1 分钟内），本方法只保证“设置已写入”，
     * 不谎报“时间已同步”，调用方应提示用户稍后查看本机时间。
     */
    suspend fun apply(host: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            Log.d(TAG, "Setting system NTP server = $host")
            Settings.Global.putString(resolver, KEY_NTP_SERVER, host)
            // 关再开，强制系统立即用新服务器同步一次
            Settings.Global.putInt(resolver, Settings.Global.AUTO_TIME, 0)
            delay(TOGGLE_DELAY_MILLIS)
            Settings.Global.putInt(resolver, Settings.Global.AUTO_TIME, 1)
            Log.d(TAG, "System NTP switch requested")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(
                SecurityException("缺少授权：请先用 ADB 执行一次授权命令", e),
            )
        } catch (e: Exception) {
            Result.failure(IOException("切换系统 NTP 失败：${e.message}", e))
        }
    }
}
