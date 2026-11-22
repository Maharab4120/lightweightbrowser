package com.lightweightbrowser

import java.util.Locale

/** Parsed UDP/IPv4 wrapper around a DNS packet from the VPN interface. */
internal data class DnsEnvelope(
    val ipHeaderLength: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray
)

/** Questions from a DNS query and the offset where the question section ends. */
internal data class DnsQuery(
    val questions: List<String>,
    val questionEnd: Int
)

/**
 * Minimal IPv4/UDP/DNS helpers for the DNS-only VPN filter.
 *
 * Malformed packets return null and are ignored; allowed queries are forwarded
 * byte-for-byte. Blocked queries become a small local NXDOMAIN response so apps
 * do not wait for a timeout and retry.
 */
internal object DnsPackets {

    private const val DNS_PORT = 53
    private const val IPV4_UDP_HEADER = 28
    private const val RCODE_NXDOMAIN = 3

    fun parseIpv4Udp(request: ByteArray): DnsEnvelope? {
        if (request.size < 20) return null
        val version = request[0].toInt() ushr 4
        val headerLength = (request[0].toInt() and 0x0f) * 4
        if (version != 4 || headerLength < 20 || request.size < headerLength + 8) return null

        val totalLength = u16(request, 2)
        if (totalLength < headerLength + 8 || totalLength > request.size) return null
        if ((request[9].toInt() and 0xff) != 17) return null

        val udpLength = u16(request, headerLength + 4)
        val payloadStart = headerLength + 8
        val payloadEnd = minOf(totalLength, headerLength + udpLength)
        if (udpLength < 8 || payloadEnd < payloadStart || payloadEnd > request.size) return null

        val sourcePort = u16(request, headerLength)
        val destinationPort = u16(request, headerLength + 2)
        if (destinationPort != DNS_PORT) return null

        return DnsEnvelope(
            ipHeaderLength = headerLength,
            sourceAddress = request.copyOfRange(12, 16),
            destinationAddress = request.copyOfRange(16, 20),
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = request.copyOfRange(payloadStart, payloadEnd)
        )
    }

    fun parseQuery(payload: ByteArray): DnsQuery? {
        if (payload.size < 12) return null
        val questionCount = u16(payload, 4)
        var offset = 12
        val questions = ArrayList<String>()

        for (i in 0 until questionCount) {
            val (name, afterName) = readName(payload, offset) ?: return null
            if (afterName + 4 > payload.size) return null
            questions.add(name)
            offset = afterName + 4
        }
        return DnsQuery(questions, offset)
    }

    fun nxDomainResponse(query: ByteArray, questionEnd: Int): ByteArray =
        responseWithRcode(query, questionEnd, RCODE_NXDOMAIN)

    /** Builds a small response that preserves the ID and question section. */
    fun responseWithRcode(query: ByteArray, questionEnd: Int, rcode: Int): ByteArray {
        val response = query.copyOf(questionEnd.coerceIn(12, query.size))
        if (response.size < 12) return response
        // Set QR, keep the original ID/RD and question, replace the RCODE.
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xf0) or (rcode and 0x0f)).toByte()
        writeU16(response, 6, 0) // ANCOUNT
        writeU16(response, 8, 0) // NSCOUNT
        writeU16(response, 10, 0) // ARCOUNT
        return response
    }

    fun buildIpv4UdpResponse(request: DnsEnvelope, dnsPayload: ByteArray): ByteArray? {
        if (dnsPayload.isEmpty() || dnsPayload.size > 65_535 - IPV4_UDP_HEADER) return null

        val totalLength = IPV4_UDP_HEADER + dnsPayload.size
        val response = ByteArray(totalLength)

        response[0] = 0x45 // IPv4, 20-byte header
        response[1] = 0
        writeU16(response, 2, totalLength)
        writeU16(response, 4, 0) // Identification
        writeU16(response, 6, 0x4000) // Don't fragment
        response[8] = 64 // TTL
        response[9] = 17 // UDP
        writeU16(response, 10, 0)

        // Source becomes the upstream DNS server; destination becomes the app.
        System.arraycopy(request.destinationAddress, 0, response, 12, 4)
        System.arraycopy(request.sourceAddress, 0, response, 16, 4)
        writeU16(response, 10, ipChecksum(response, 0, 20))

        writeU16(response, 20, request.destinationPort)
        writeU16(response, 22, request.sourcePort)
        writeU16(response, 24, 8 + dnsPayload.size)
        writeU16(response, 26, 0) // UDP checksum is optional for IPv4
        System.arraycopy(dnsPayload, 0, response, 28, dnsPayload.size)
        return response
    }

    private fun readName(buffer: ByteArray, start: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var offset = start
        var jumped = false
        var resumeAt = start
        var totalLength = 0

        for (step in 0 until 128) {
            if (offset >= buffer.size) return null
            val length = buffer[offset].toInt() and 0xff

            if (length == 0) {
                if (!jumped) offset++
                val name = labels.joinToString(".").lowercase(Locale.ROOT)
                return name to if (jumped) resumeAt else offset
            }

            if (length and 0xc0 == 0xc0) {
                if (offset + 1 >= buffer.size) return null
                val pointer = ((length and 0x3f) shl 8) or (buffer[offset + 1].toInt() and 0xff)
                if (pointer >= buffer.size) return null
                if (!jumped) {
                    resumeAt = offset + 2
                    jumped = true
                }
                offset = pointer
                continue
            }

            if (length > 63 || offset + 1 + length > buffer.size) return null
            labels.add(String(buffer, offset + 1, length, Charsets.US_ASCII))
            totalLength += length + 1
            if (totalLength > 255) return null
            offset += 1 + length
        }
        return null
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    private fun ipChecksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            sum += ((buffer[i].toInt() and 0xff) shl 8) or (buffer[i + 1].toInt() and 0xff)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }
}
