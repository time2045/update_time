package com.example.ntpsync

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 轻量级 NTP 客户端：标准 NTP over UDP，默认端口 123。
 *
 * 精度说明：T1 取 [System.currentTimeMillis]，T4 不再直接读系统时钟，
 * 而是用 T1 +（收发之间的 elapsedRealtime 差值）推算，避免收包期间
 * 系统时钟跳变污染 RTT / offset 计算。elapsedRealtime 是单调时钟，
 * 只用于测量间隔，不直接当 Unix 时间使用。
 */
class NtpClient(
    /** UDP 收发超时，默认 3000 ms */
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3000
        private const val TAG = "NtpSync"
    }

    /** 一次 NTP 查询的结果 */
    data class NtpResult(
        /** 校准后的服务器“此刻”时间（Unix 毫秒，本机此刻 + offset） */
        val serverTimeMillis: Long,
        /** 时钟偏差 offset（毫秒，正值表示本机比服务器慢） */
        val offsetMillis: Long,
        /** 网络往返延迟 delay（毫秒） */
        val delayMillis: Long,
    )

    /** 解析后的服务器地址 */
    data class ServerAddress(val host: String, val port: Int)

    /**
     * 解析用户输入的 NTP 服务器地址，支持：
     * 192.168.1.100 / 192.168.1.100:123 / ntp.example.local / ntp.example.local:123
     * 只填 IP/域名时默认 UDP 123。
     *
     * @throws IllegalArgumentException 地址非法时抛出
     */
    fun parseServerAddress(input: String): ServerAddress {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "NTP 服务器地址不能为空" }

        val host: String
        var port = NtpPacket.NTP_PORT
        if (trimmed.startsWith("[")) {
            // [ipv6] 或 [ipv6]:port
            val end = trimmed.indexOf(']')
            require(end > 1) { "IPv6 地址格式非法" }
            host = trimmed.substring(1, end)
            val rest = trimmed.substring(end + 1)
            if (rest.isNotEmpty()) {
                require(rest.startsWith(":")) { "IPv6 地址格式非法" }
                port = rest.drop(1).toIntOrNull()
                    ?: throw IllegalArgumentException("端口必须是数字")
            }
        } else if (trimmed.indexOf(':') >= 0 && trimmed.indexOf(':') == trimmed.lastIndexOf(':')) {
            // 只有一个冒号：host:port（裸 IPv6 含多个冒号，不走此分支）
            val colon = trimmed.lastIndexOf(':')
            host = trimmed.substring(0, colon)
            port = trimmed.substring(colon + 1).toIntOrNull()
                ?: throw IllegalArgumentException("端口必须是数字")
        } else {
            host = trimmed
        }
        require(host.isNotEmpty()) { "主机名不能为空" }
        require(port in 1..65535) { "端口超出范围（1-65535）" }
        return ServerAddress(host, port)
    }

    /**
     * 发送 NTP 请求并按标准算法计算 offset / delay。
     *
     * 必须在后台线程调用（内部已切换到 Dispatchers.IO）。
     *
     * @throws IOException 超时 / 响应非法 / 服务器异常时间等情况抛出
     */
    suspend fun query(address: ServerAddress): NtpResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "NTP server = ${address.host}:${address.port}")
        var socket: DatagramSocket? = null
        try {
            // DNS 解析失败抛 UnknownHostException（属 IOException）
            val inet = InetAddress.getByName(address.host)
            val request = NtpPacket.createRequest()

            socket = DatagramSocket()
            socket.soTimeout = timeoutMillis

            val t1 = System.currentTimeMillis()
            val t1Elapsed = SystemClock.elapsedRealtime()
            Log.d(TAG, "Sending NTP request...")
            socket.send(DatagramPacket(request, request.size, inet, address.port))

            val buffer = ByteArray(NtpPacket.PACKET_SIZE)
            val response = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(response)
            } catch (e: SocketTimeoutException) {
                throw IOException("NTP 响应超时（${timeoutMillis}ms），请检查 UDP 123 是否开放", e)
            }
            // T4 用单调时钟推算，不直接读系统时钟
            val t4 = t1 + (SystemClock.elapsedRealtime() - t1Elapsed)
            Log.d(TAG, "NTP response received")

            if (response.length < NtpPacket.PACKET_SIZE) {
                throw IOException("NTP 响应格式错误：长度不足 ${NtpPacket.PACKET_SIZE} 字节")
            }
            val mode = buffer[0].toInt() and 0x07
            if (mode != 4) {
                throw IOException("NTP 响应格式错误：不是服务器应答（mode=$mode）")
            }
            val stratum = buffer[1].toInt() and 0xFF
            if (stratum == 0) {
                throw IOException("服务器拒绝服务（stratum=0，可能被限流）")
            }

            val t2 = NtpPacket.parseNtpTimestamp(buffer, NtpPacket.OFFSET_RECEIVE)
            val t3 = NtpPacket.parseNtpTimestamp(buffer, NtpPacket.OFFSET_TRANSMIT)
            if (t2 <= 0 || t3 <= 0) {
                throw IOException("服务器返回异常时间")
            }

            val offset = NtpPacket.computeOffset(t1, t2, t3, t4)
            val delay = NtpPacket.computeDelay(t1, t2, t3, t4)
            Log.d(TAG, "RTT = ${delay}ms")
            Log.d(TAG, "Offset = ${offset}ms")
            NtpResult(
                serverTimeMillis = System.currentTimeMillis() + offset,
                offsetMillis = offset,
                delayMillis = delay,
            )
        } finally {
            socket?.close()
        }
    }
}
