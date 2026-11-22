package com.lightweightbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsPacketsTest {

    @Test
    fun parsesDnsQueryAndBuildsBlockedResponse() {
        val payload = dnsQuery("ads.example.com")
        val packet = ipv4Udp(payload, sourcePort = 40_000)

        val envelope = DnsPackets.parseIpv4Udp(packet)!!
        assertEquals(20, envelope.ipHeaderLength)
        assertEquals(40_000, envelope.sourcePort)
        assertEquals(53, envelope.destinationPort)

        val query = DnsPackets.parseQuery(envelope.payload)!!
        assertEquals(listOf("ads.example.com"), query.questions)

        val nxDomain = DnsPackets.nxDomainResponse(envelope.payload, query.questionEnd)
        assertEquals(0x1234, u16(nxDomain, 0))
        assertTrue(nxDomain[2].toInt() and 0x80 != 0)
        assertEquals(3, nxDomain[3].toInt() and 0x0f)
        assertEquals(0, u16(nxDomain, 6))

        val response = DnsPackets.buildIpv4UdpResponse(envelope, nxDomain)!!
        assertEquals(17, response[9].toInt() and 0xff)
        assertEquals(53, u16(response, 20))
        assertEquals(40_000, u16(response, 22))
        assertEquals(28 + nxDomain.size, u16(response, 2))
    }

    @Test
    fun malformedPacketsAreIgnored() {
        assertNull(DnsPackets.parseIpv4Udp(ByteArray(8)))

        val nonUdp = ipv4Udp(dnsQuery("example.com")).also { it[9] = 6 }
        assertNull(DnsPackets.parseIpv4Udp(nonUdp))

        assertNull(DnsPackets.parseQuery(byteArrayOf(0, 1)))
        assertNull(DnsPackets.parseQuery(byteArrayOf(0, 1, 0, 1, 0, 1)))
    }

    @Test
    fun serverFailureResponseKeepsQuestionAndId() {
        val payload = dnsQuery("example.com")
        val query = DnsPackets.parseQuery(payload)!!
        val failure = DnsPackets.responseWithRcode(payload, query.questionEnd, 2)

        assertEquals(0x1234, u16(failure, 0))
        assertTrue(failure[2].toInt() and 0x80 != 0)
        assertEquals(2, failure[3].toInt() and 0x0f)
        assertEquals(0, u16(failure, 6))
        assertTrue(failure.size >= query.questionEnd)
    }

    private fun dnsQuery(name: String, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0x0100) // RD
        writeU16(out, 1) // QDCOUNT
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        writeU16(out, 1) // A
        writeU16(out, 1) // IN
        return out.toByteArray()
    }

    private fun ipv4Udp(payload: ByteArray, sourcePort: Int = 40_000): ByteArray {
        val totalLength = 28 + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        writeU16(packet, 2, totalLength)
        packet[8] = 64
        packet[9] = 17 // UDP
        packet[12] = 10
        packet[13] = 0
        packet[14] = 0
        packet[15] = 5
        packet[16] = 10
        packet[17] = 23
        packet[18] = 42
        packet[19] = 2
        writeU16(packet, 20, sourcePort)
        writeU16(packet, 22, 53)
        writeU16(packet, 24, 8 + payload.size)
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun writeU16(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value ushr 8)
        stream.write(value)
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)
}
