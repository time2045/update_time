package com.example.ntpsync

/**
 * 系统时间同步接口。
 *
 * 严格区分“NTP 时间获取成功”和“系统时间修改成功”：
 * 只有真正调通系统级改时间 API 才返回 Success，绝不伪造成功。
 */
interface SystemTimeSynchronizer {

    suspend fun synchronize(
        targetTimeMillis: Long,
    ): SyncResult
}

/** 同步结果：成功 / 无权限 / 失败，三态必须明确区分。 */
sealed class SyncResult {

    /** 系统时间已真正修改成功（调用方还应再读一次时钟做二次验证）。 */
    data object Success : SyncResult()

    /** 当前应用没有修改系统时间的权限（如不是 Device Owner）。绝不能当成功处理。 */
    data class PermissionDenied(
        val message: String,
    ) : SyncResult()

    /** 调用系统 API 失败（被系统拒绝、版本不支持、异常等）。 */
    data class Failed(
        val message: String,
    ) : SyncResult()
}
