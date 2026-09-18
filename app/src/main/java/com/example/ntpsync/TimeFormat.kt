package com.example.ntpsync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间显示：统一格式 yyyy-MM-dd HH:mm:ss.SSS。
 *
 * NTP 本体是 UTC，这里按设备当前时区（ZoneId.systemDefault）显示。
 * java.time 需要 API 26+，本项目 minSdk = 26，可直接使用。
 */
object TimeFormat {

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    /** Unix 毫秒 → 本地时区字符串；非法值显示占位符。 */
    fun format(millis: Long?): String {
        if (millis == null || millis <= 0) return "--"
        return try {
            FORMATTER.format(Instant.ofEpochMilli(millis))
        } catch (e: Exception) {
            "--"
        }
    }

    /** 偏差显示：正数带 + 号，如 +1024 ms / -36 ms。 */
    fun formatOffset(offsetMillis: Long?): String {
        if (offsetMillis == null) return "--"
        return if (offsetMillis >= 0) "+$offsetMillis ms" else "$offsetMillis ms"
    }

    /** 延迟显示，如 18 ms。 */
    fun formatDelay(delayMillis: Long?): String {
        if (delayMillis == null) return "--"
        return "$delayMillis ms"
    }
}
