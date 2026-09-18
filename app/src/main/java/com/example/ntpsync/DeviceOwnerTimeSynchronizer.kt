package com.example.ntpsync

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 基于 DevicePolicyManager 的系统时间同步实现。
 *
 * DevicePolicyManager.setTime()（API 26+）只允许 Device Owner / Profile Owner 调用：
 *  - 先检查 isDeviceOwnerApp()，不是则直接返回 PermissionDenied；
 *  - 再检查系统版本，低于 Android 8.0 无法调用该 API；
 *  - setTime() 返回 false 或抛 SecurityException 都如实转为失败结果。
 */
class DeviceOwnerTimeSynchronizer(
    private val context: Context,
) : SystemTimeSynchronizer {

    companion object {
        private const val TAG = "NtpSync"
    }

    override suspend fun synchronize(targetTimeMillis: Long): SyncResult {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return SyncResult.Failed("无法获取设备策略服务（DevicePolicyManager）")

        val packageName = context.packageName
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        Log.d(TAG, "Device owner = $isOwner")
        if (!isOwner) {
            return SyncResult.PermissionDenied(
                "当前应用没有修改系统时间的权限（不是 Device Owner）。" +
                    "请将本应用配置为设备所有者（Device Owner）后再同步。",
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return SyncResult.Failed("修改系统时间需要 Android 8.0 及以上版本")
        }

        val admin = ComponentName(context, NtpDeviceAdminReceiver::class.java)
        return try {
            Log.d(TAG, "Setting system time...")
            val ok = dpm.setTime(admin, targetTimeMillis)
            if (ok) {
                Log.d(TAG, "System time synchronized")
                SyncResult.Success
            } else {
                SyncResult.Failed("系统拒绝修改时间（setTime 返回 false）")
            }
        } catch (e: SecurityException) {
            SyncResult.PermissionDenied("系统拒绝修改时间：${e.message}")
        } catch (e: Exception) {
            SyncResult.Failed("修改系统时间异常：${e.message}")
        }
    }
}
