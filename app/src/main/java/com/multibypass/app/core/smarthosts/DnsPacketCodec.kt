package com.multibypass.app.core.smarthosts

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Lightweight DNS Query/Response codec for intercepting and synthesizing DNS packets.
 */
object DnsPacketCodec {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1

    data class ParsedQuery(
        val id: Int,
        val flags: Int,
        val qname: String,
        val qtype: Int,
        val qclass: Int,
        val questionBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedQuery) return false
            return id == other.id && qname == other.qname && qtype == other.qtype
        }

        override fun hashCode(): Int = 31 * id + qname.hashCode()
    }

    /**
     * Parses a DNS query packet. Returns null if invalid or not a standard query.
     */
    fun parseQuery(packet: ByteArray, length: Int): ParsedQuery? {
        if (length < 12) return null

        val id = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)

        // Only parse single-question standard queries (QR = 0, OPCODE = 0)
        val isQuery = (flags and 0x8000) == 0
        val opcode = (flags and 0x7800) shr 11
        if (!isQuery || opcode != 0 || qdCount < 1) return null

        val questionStart = 12
        var pos = questionStart
        val nameSb = StringBuilder()

        while (pos < length) {
            val labelLen = packet[pos].toInt() and 0xFF
            pos++
            if (labelLen == 0) break // End of QNAME

            // If compressed pointer encountered in query (unusual for standard queries)
            if ((labelLen and 0xC0) == 0xC0) {
                pos++ // skip second byte of pointer
                break
            }

            if (pos + labelLen > length) return null

            if (nameSb.isNotEmpty()) nameSb.append('.')
            for (i in 0 until labelLen) {
                nameSb.append(packet[pos + i].toInt().toChar())
            }
            pos += labelLen
        }

        if (pos + 4 > length) return null

        val qtype = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
        val qclass = ((packet[pos + 2].toInt() and 0xFF) shl 8) or (packet[pos + 3].toInt() and 0xFF)
        pos += 4

        val questionLen = pos - questionStart
        val questionBytes = ByteArray(questionLen)
        System.arraycopy(packet, questionStart, questionBytes, 0, questionLen)

        return ParsedQuery(
            id = id,
            flags = flags,
            qname = nameSb.toString().lowercase(),
            qtype = qtype,
            qclass = qclass,
            questionBytes = questionBytes
        )
    }

    /**
     * Builds an authoritative DNS response with IPv4 A records for the specified IPs.
     */
    fun buildAResponse(query: ParsedQuery, ipv4List: List<String>, ttlSeconds: Int = 300): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Transaction ID
        out.write((query.id shr 8) and 0xFF)
        out.write(query.id and 0xFF)

        // 2. Flags: 0x8180 (Response, Opcode 0, Authoritative/Recursion Available, No Error)
        out.write(0x81)
        out.write(0x80)

        // 3. QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)

        // 4. ANCOUNT = number of IPs
        val validIps = ipv4List.filter { it.count { c -> c == '.' } == 3 }
        val count = if (validIps.isNotEmpty()) validIps.size else 0
        out.write((count shr 8) and 0xFF)
        out.write(count and 0xFF)

        // 5. NSCOUNT = 0, ARCOUNT = 0
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        // 6. Question section (echoed)
        out.write(query.questionBytes)

        // 7. Answer section: A records
        for (ipStr in validIps) {
            val ipBytes = InetAddress.getByName(ipStr).address
            if (ipBytes.size == 4) {
                // Name pointer to QNAME at offset 12 (0xC00C)
                out.write(0xC0)
                out.write(0x0C)

                // TYPE: A (0x0001)
                out.write(0x00)
                out.write(0x01)

                // CLASS: IN (0x0001)
                out.write(0x00)
                out.write(0x01)

                // TTL (4 bytes)
                out.write((ttlSeconds shr 24) and 0xFF)
                out.write((ttlSeconds shr 16) and 0xFF)
                out.write((ttlSeconds shr 8) and 0xFF)
                out.write(ttlSeconds and 0xFF)

                // RDLENGTH: 4 bytes
                out.write(0x00)
                out.write(0x04)

                // RDATA
                out.write(ipBytes)
            }
        }

        return out.toByteArray()
    }

    /**
     * Builds a NODATA (NOERROR with ANCOUNT=0) response.
     * Used for AAAA (IPv6) queries on domains that only have IPv4 SNI proxies,
     * so that the OS immediately uses IPv4 without hanging.
     */
    fun buildNoDataResponse(query: ParsedQuery): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Transaction ID
        out.write((query.id shr 8) and 0xFF)
        out.write(query.id and 0xFF)

        // 2. Flags: 0x8180 (Response, Opcode 0, Recursion Available, No Error)
        out.write(0x81)
        out.write(0x80)

        // 3. QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        // 4. Question section (echoed)
        out.write(query.questionBytes)

        return out.toByteArray()
    }
}
