package com.example.ntpsync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.UnknownHostException
import kotlin.math.abs

/**
 * 校时流程编排：按用户排序依次尝试多个 NTP 服务器。
 *
 * 策略：
 *  - 逐个查询，第一个 NTP 查询成功的服务器即用于本次校时；
 *  - 一旦 NTP 成功就尝试改系统时间，不再继续试后面的服务器
 *    （避免反复修改系统时钟）；
 *  - 每次尝试的结果都记录在 attempts 中，供界面逐行展示。
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

    /** 单个服务器的尝试结果：ntp 为空表示 NTP 查询失败，看 error。 */
    data class Attempt(
        val server: String,
        val ntp: NtpClient.NtpResult?,
        val error: String?,
    )

    /** 一次同步的最终结果（UI 直接按此展示，不做二次解读）。 */
    sealed interface SyncOutcome {

        /** 列表为空：提示用户先添加服务器 */
        data class EmptyServers(
            val message: String,
        ) : SyncOutcome

        /** 所有服务器 NTP 查询都失败 */
        data class AllNtpFailed(
            val attempts: List<Attempt>,
        ) : SyncOutcome

        /** NTP 成功但 App 无权改系统时间：必须展示 NTP 时间 + 本机时间 + 偏差 */
        data class NoPermission(
            val server: String,
            val ntp: NtpClient.NtpResult,
            val localBefore: Long,
            val message: String,
            val attempts: List<Attempt>,
        ) : SyncOutcome

        /** 系统时间真正被修改且通过二次验证 */
        data class Success(
            val server: String,
            val ntp: NtpClient.NtpResult,
            val localBefore: Long,
            val localAfter: Long,
            val attempts: List<Attempt>,
        ) : SyncOutcome

        /** setTime 返回 false / 验证未通过 */
        data class SyncFailed(
            val server: String,
            val ntp: NtpClient.NtpResult?,
            val message: String,
            val attempts: List<Attempt>,
        ) : SyncOutcome
    }

    /**
     * 按顺序同步。已在 IO 线程做网络请求，可直接在协程中调用。
     */
    suspend fun syncInOrder(servers: List<String>): SyncOutcome {
        val clean = servers.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) {
            return SyncOutcome.EmptyServers("请先添加 NTP 服务器地址")
        }

        val attempts = mutableListOf<Attempt>()
        for (raw in clean) {
            // 1. 解析地址（单个非法只跳过该条，不中断整体）
            val address = try {
                ntpClient.parseServerAddress(raw)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "地址无效，已跳过: $raw")
                attempts += Attempt(raw, null, "地址无效：${e.message}")
                continue
            }

            // 2. NTP 查询
            val localBefore = System.currentTimeMillis()
            val ntp = try {
                ntpClient.query(address)
            } catch (e: UnknownHostException) {
                Log.w(TAG, "DNS 解析失败: ${address.host}")
                attempts += Attempt(raw, null, "DNS 解析失败，请检查地址与网络")
                continue
            } catch (e: IOException) {
                Log.w(TAG, "NTP 查询失败($raw): ${e.message}")
                attempts += Attempt(raw, null, e.message ?: "连接失败")
                continue
            } catch (e: Exception) {
                Log.w(TAG, "NTP 查询异常($raw): ${e.message}")
                attempts += Attempt(raw, null, "查询异常：${e.message}")
                continue
            }

            // 3. 目标时间 = 本机此刻 + offset（单调时钟锚定，避免两次读表之间跳变）
            val anchorElapsed = SystemClock.elapsedRealtime()
            val targetTime = System.currentTimeMillis() + ntp.offsetMillis

            // 4. 尝试修改系统时间（只对第一个 NTP 成功的服务器执行）
            attempts += Attempt(raw, ntp, null)
            val snapshot = attempts.toList()
            return when (val r = synchronizer.synchronize(targetTime)) {
                is SyncResult.PermissionDenied -> {
                    SyncOutcome.NoPermission(raw, ntp, localBefore, r.message, snapshot)
                }
                is SyncResult.Failed -> {
                    SyncOutcome.SyncFailed(raw, ntp, r.message, snapshot)
                }
                is SyncResult.Success -> {
                    // 5. 二次验证：重读时钟，确认时间真的按预期变化了
                    val localAfter = System.currentTimeMillis()
                    val expected = targetTime + (SystemClock.elapsedRealtime() - anchorElapsed)
                    if (abs(localAfter - expected) <= VERIFY_TOLERANCE_MILLIS) {
                        SyncOutcome.Success(raw, ntp, localBefore, localAfter, snapshot)
                    } else {
                        SyncOutcome.SyncFailed(
                            raw,
                            ntp,
                            "系统时间修改后验证未通过（预期 $expected，实际 $localAfter）",
                            snapshot,
                        )
                    }
                }
            }
        }
        return SyncOutcome.AllNtpFailed(attempts.toList())
    }
}
