package com.example.ntpsync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.UnknownHostException
import kotlin.math.abs

/**
 * 校时流程编排：解析地址 → NTP 查询 → 计算目标时间 → 尝试改系统时间 → 二次验证。
 *
 * 核心原则：“NTP 获取成功”≠“系统时间修改成功”，两者状态严格分开上报，
 * 修改后必须重读 System.currentTimeMillis() 验证，不符合预期就报失败。
 */
class TimeSyncManager(
    context: Context,
    private val ntpClient: NtpClient = NtpClient(),
    private val synchronizer: SystemTimeSynchronizer = DeviceOwnerTimeSynchronizer(context),
) {

    companion object {
        private const val TAG = "NtpSync"

        /**
         * 改时间后的验证容差：预期时刻与实际读数相差超过该值则判失败。
         * 用 2000ms 容忍 Binder 调用与时钟读取之间的正常开销。
         */
        private const val VERIFY_TOLERANCE_MILLIS = 2000L
    }

    /** 一次同步的最终结果（UI 直接按此展示，不做二次解读）。 */
    sealed interface SyncOutcome {

        /** NTP 就没连上：地址错 / DNS 失败 / 超时 / 响应非法等 */
        data class NtpFailed(
            val serverInput: String,
            val message: String,
        ) : SyncOutcome

        /** NTP 成功但 App 无权改系统时间：必须展示 NTP 时间 + 本机时间 + 偏差 */
        data class NoPermission(
            val ntp: NtpClient.NtpResult,
            val localBefore: Long,
            val message: String,
        ) : SyncOutcome

        /** 系统时间真正被修改且通过二次验证 */
        data class Success(
            val ntp: NtpClient.NtpResult,
            val localBefore: Long,
            val localAfter: Long,
        ) : SyncOutcome

        /** setTime 返回 false / 验证未通过 / 其他异常 */
        data class SyncFailed(
            val ntp: NtpClient.NtpResult?,
            val message: String,
        ) : SyncOutcome
    }

    /**
     * 执行一次完整同步。已在 IO 线程做网络请求，可直接在协程中调用。
     */
    suspend fun sync(serverInput: String): SyncOutcome {
        // 1. 解析地址
        val address = try {
            ntpClient.parseServerAddress(serverInput)
        } catch (e: IllegalArgumentException) {
            return SyncOutcome.NtpFailed(serverInput, "服务器地址无效：${e.message}")
        }

        // 2. NTP 查询
        val localBefore = System.currentTimeMillis()
        val ntp = try {
            ntpClient.query(address)
        } catch (e: UnknownHostException) {
            Log.w(TAG, "DNS 解析失败: ${e.message}")
            return SyncOutcome.NtpFailed(
                serverInput,
                "DNS 解析失败：${address.host}，请检查地址是否正确、设备是否接入局域网",
            )
        } catch (e: IOException) {
            Log.w(TAG, "NTP 查询失败: ${e.message}")
            return SyncOutcome.NtpFailed(
                serverInput,
                "无法连接 NTP 服务器 ${address.host}:${address.port}：${e.message}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "NTP 查询异常: ${e.message}")
            return SyncOutcome.NtpFailed(serverInput, "NTP 查询异常：${e.message}")
        }

        // 3. 目标时间 = 本机此刻 + offset（单调时钟锚定，避免两次读表之间跳变）
        val anchorElapsed = SystemClock.elapsedRealtime()
        val targetTime = System.currentTimeMillis() + ntp.offsetMillis

        // 4. 尝试修改系统时间
        return when (val r = synchronizer.synchronize(targetTime)) {
            is SyncResult.PermissionDenied -> {
                SyncOutcome.NoPermission(ntp, localBefore, r.message)
            }
            is SyncResult.Failed -> {
                SyncOutcome.SyncFailed(ntp, r.message)
            }
            is SyncResult.Success -> {
                // 5. 二次验证：重读时钟，确认时间真的按预期变化了
                val localAfter = System.currentTimeMillis()
                val expected = targetTime + (SystemClock.elapsedRealtime() - anchorElapsed)
                if (abs(localAfter - expected) <= VERIFY_TOLERANCE_MILLIS) {
                    SyncOutcome.Success(ntp, localBefore, localAfter)
                } else {
                    SyncOutcome.SyncFailed(
                        ntp,
                        "系统时间修改后验证未通过（预期 $expected，实际 $localAfter）",
                    )
                }
            }
        }
    }
}
