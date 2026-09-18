package com.example.ntpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NTP 时间计算相关单元测试（纯 JVM，不依赖 Android）。
 */
class NtpPacketTest {

    @Test
    fun request_firstByte_isLi0Vn4Mode3() {
        val req = NtpPacket.createRequest()
        assertEquals(NtpPacket.PACKET_SIZE, req.size)
        assertEquals(0x1B.toByte(), req[0])
    }

    @Test
    fun timestamp_writeThenParse_roundTrip() {
        val packet = ByteArray(NtpPacket.PACKET_SIZE)
        val unixMillis = 1789828832125L // 2026-09-18 19:20:32.125（UTC+8 显示）
        NtpPacket.writeNtpTimestamp(packet, NtpPacket.OFFSET_TRANSMIT, unixMillis)
        val parsed = NtpPacket.parseNtpTimestamp(packet, NtpPacket.OFFSET_TRANSMIT)
        assertEquals(unixMillis, parsed)
    }

    @Test
    fun offset_zeroDelay_symmetricTimestamps() {
        // 服务端与客户端时钟一致、无延迟的理想情况：offset 应为 0
        val t = 1_700_000_000_000L
        assertEquals(0L, NtpPacket.computeOffset(t, t, t, t))
        assertEquals(0L, NtpPacket.computeDelay(t, t, t, t))
    }

    @Test
    fun offset_clientBehindServer_by1024ms() {
        // 本机比服务器慢 1024ms，网络延迟 18ms：
        // T1=0, T2=1033, T3=1033, T4=18（服务器时钟 = 本机 + 1024，单程 9ms）
        val offset = NtpPacket.computeOffset(0, 1033, 1033, 18)
        val delay = NtpPacket.computeDelay(0, 1033, 1033, 18)
        assertEquals(1024L, offset)
        assertEquals(18L, delay)
    }

    @Test
    fun delay_formula_matchesRfc5905() {
        val t1 = 1000L
        val t2 = 2060L
        val t3 = 2070L
        val t4 = 1130L
        // offset = ((2060-1000) + (2070-1130)) / 2 = (1060+940)/2 = 1000
        // delay = (1130-1000) - (2070-2060) = 130-10 = 120
        assertEquals(1000L, NtpPacket.computeOffset(t1, t2, t3, t4))
        assertEquals(120L, NtpPacket.computeDelay(t1, t2, t3, t4))
    }

    @Test
    fun addressParser_defaultsToPort123() {
        val client = NtpClient()
        assertEquals(
            NtpClient.ServerAddress("192.168.1.100", 123),
            client.parseServerAddress("192.168.1.100"),
        )
        assertEquals(
            NtpClient.ServerAddress("192.168.1.100", 123),
            client.parseServerAddress("  192.168.1.100  "),
        )
        assertEquals(
            NtpClient.ServerAddress("ntp.example.local", 123),
            client.parseServerAddress("ntp.example.local"),
        )
    }

    @Test
    fun addressParser_explicitPort() {
        val client = NtpClient()
        assertEquals(
            NtpClient.ServerAddress("192.168.1.100", 123),
            client.parseServerAddress("192.168.1.100:123"),
        )
        assertEquals(
            NtpClient.ServerAddress("ntp.example.local", 1123),
            client.parseServerAddress("ntp.example.local:1123"),
        )
    }

    @Test
    fun addressParser_invalid_throws() {
        val client = NtpClient()
        var failed = 0
        listOf("", "   ", "192.168.1.100:abc", "192.168.1.100:0", "192.168.1.100:99999", ":123")
            .forEach { input ->
                try {
                    client.parseServerAddress(input)
                } catch (e: IllegalArgumentException) {
                    failed++
                }
            }
        assertEquals(6, failed)
    }

    @Test
    fun epochOffset_convertsNtpZeroToUnixNegative() {
        // NTP 秒 = 0（1900 年）→ Unix 毫秒应为 -2208988800 * 1000
        val packet = ByteArray(NtpPacket.PACKET_SIZE)
        val parsed = NtpPacket.parseNtpTimestamp(packet, NtpPacket.OFFSET_TRANSMIT)
        assertEquals(-NtpPacket.NTP_EPOCH_OFFSET_SECONDS * 1000L, parsed)
        assertTrue(parsed < 0)
    }
}
