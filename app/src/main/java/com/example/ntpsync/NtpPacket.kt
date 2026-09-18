package com.example.ntpsync

/**
 * NTP 协议报文构造 / 解析与偏差计算。
 *
 * 纯 Kotlin 实现，不依赖任何 Android API，方便做 JVM 单元测试。
 *
 * 标准 NTP 算法（RFC 5905）：
 *   T1 = 客户端发送请求时刻（客户端时钟）
 *   T2 = 服务器收到请求时刻（服务器时钟）
 *   T3 = 服务器发送应答时刻（服务器时钟）
 *   T4 = 客户端收到应答时刻（客户端时钟）
 *   offset = ((T2 - T1) + (T3 - T4)) / 2   // 客户端相对服务器的时钟偏差
 *   delay  = (T4 - T1) - (T3 - T2)         // 网络往返延迟
 */
object NtpPacket {

    /** NTP 报文固定长度：48 字节 */
    const val PACKET_SIZE = 48

    /** NTP 默认端口：UDP 123 */
    const val NTP_PORT = 123

    /** NTP 纪元（1900-01-01）与 Unix 纪元（1970-01-01）之间相差的秒数 */
    const val NTP_EPOCH_OFFSET_SECONDS = 2208988800L

    /** 2^32，用于秒小数部分与毫秒互转 */
    private const val FRACTION_SCALE = 0x1_0000_0000L

    // ---- 报文内时间戳字段偏移 ----
    /** Originate 时间戳（客户端发包时刻，服务端原样回显） */
    const val OFFSET_ORIGINATE = 24

    /** Receive 时间戳（T2，服务端收包时刻） */
    const val OFFSET_RECEIVE = 32

    /** Transmit 时间戳（T3，服务端发包时刻） */
    const val OFFSET_TRANSMIT = 40

    /**
     * 构造客户端请求报文。
     *
     * 首字节 0x1B：LI（闰秒指示）= 0，VN（版本号）= 4，Mode = 3（客户端）。
     */
    fun createRequest(): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = 0x1B
        return packet
    }

    /**
     * 解析报文中指定偏移处的 8 字节 NTP 时间戳，转为 Unix 毫秒。
     *
     * @param packet 完整 NTP 报文
     * @param offset 时间戳字段起始偏移
     * @throws IllegalArgumentException 报文长度不足时抛出
     */
    fun parseNtpTimestamp(packet: ByteArray, offset: Int): Long {
        require(packet.size >= offset + 8) { "NTP 报文长度不足" }
        var seconds = 0L
        for (i in 0 until 4) {
            seconds = (seconds shl 8) or (packet[offset + i].toLong() and 0xFF)
        }
        var fraction = 0L
        for (i in 4 until 8) {
            fraction = (fraction shl 8) or (packet[offset + i].toLong() and 0xFF)
        }
        val unixSeconds = seconds - NTP_EPOCH_OFFSET_SECONDS
        return unixSeconds * 1000L + fraction * 1000L / FRACTION_SCALE
    }

    /**
     * 把 Unix 毫秒写入 8 字节 NTP 时间戳字段（主要用于单元测试构造报文）。
     */
    fun writeNtpTimestamp(packet: ByteArray, offset: Int, unixMillis: Long) {
        require(packet.size >= offset + 8) { "NTP 报文长度不足" }
        val seconds = unixMillis / 1000L + NTP_EPOCH_OFFSET_SECONDS
        val fraction = (unixMillis % 1000L) * FRACTION_SCALE / 1000L
        for (i in 0 until 4) {
            packet[offset + i] = (seconds shr (8 * (3 - i))).toByte()
        }
        for (i in 0 until 4) {
            packet[offset + 4 + i] = (fraction shr (8 * (3 - i))).toByte()
        }
    }

    /** 按标准 NTP 公式计算时钟偏差 offset（毫秒）。 */
    fun computeOffset(t1: Long, t2: Long, t3: Long, t4: Long): Long =
        ((t2 - t1) + (t3 - t4)) / 2

    /** 按标准 NTP 公式计算网络延迟 delay（毫秒）。 */
    fun computeDelay(t1: Long, t2: Long, t3: Long, t4: Long): Long =
        (t4 - t1) - (t3 - t2)
}
