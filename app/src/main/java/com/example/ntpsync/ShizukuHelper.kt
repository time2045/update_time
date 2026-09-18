package com.example.ntpsync

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 免电脑授权。
 *
 * 为什么 Shizuku 必须是另一个 App：shell 身份只能由系统亲手交出来
 * （电脑 adb 或无线调试配对），普通 App 自己变不出来。
 * Shizuku 充当“接特权再分发”的中间人：用户装一次、手机上配对一次，
 * 之后任何 client App（含本 App）都能借它的 shell 身份执行命令。
 *
 * 本 App 只借一次：以 shell 身份给自己执行
 *   pm grant <包名> android.permission.WRITE_SECURE_SETTINGS
 * 拿到后读写系统 NTP 就不再需要 Shizuku（甚至可以卸载它），
 * 只有卸载重装本 App 才需重来。
 */
object ShizukuHelper {

    private const val TAG = "NtpSync"

    /** Shizuku 服务是否在运行（未安装/未启动都返回 false） */
    fun isRunning(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

    /** 本 App 是否已获 Shizuku 授权 */
    fun isPermissionGranted(): Boolean =
        try {
            isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

    /**
     * 通过 Shizuku 给自己授予 WRITE_SECURE_SETTINGS。
     * 必须在后台线程调用（内部已切换到 Dispatchers.IO）。
     */
    suspend fun grantSelf(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = arrayOf(
                "pm",
                "grant",
                context.packageName,
                SystemNtpSwitcher.GRANT_PERMISSION,
            )
            val process = Shizuku.newProcess(cmd, null, null)
            // 读完输出再等退出，避免管道阻塞
            try {
                process.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                Log.w(TAG, "读取授权命令输出失败: ${e.message}")
            }
            val exit = process.waitFor()
            Log.d(TAG, "Shizuku grant exit = $exit")
            exit == 0
        } catch (e: NoSuchMethodError) {
            // 装的 Shizuku 太旧，没有 newProcess：提示用户升级
            Log.w(TAG, "Shizuku 版本过旧，请升级 Shizuku")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 授权失败: ${e.message}")
            false
        }
    }
}
