package com.multibypass.app

import com.multibypass.app.core.smarthosts.DnsPacketCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class DnsPacketCodecTest {

    private fun buildSampleQuery(id: Int, domain: String, qtype: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        // ID
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        // Flags: Standard query (0x0100)
        out.write(0x01)
        out.write(0x00)
        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        // QNAME: e.g. "chatgpt.com"
        for (part in domain.split('.')) {
            out.write(part.length)
            out.write(part.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x00) // End of QNAME

        // QTYPE
        out.write((qtype shr 8) and 0xFF)
        out.write(qtype and 0xFF)

        // QCLASS: IN (1)
        out.write(0x00)
        out.write(0x01)

        return out.toByteArray()
    }

    @Test
    fun testParseValidQuery() {
        val queryBytes = buildSampleQuery(0x1234, "chatgpt.com", 1)
        val parsed = DnsPacketCodec.parseQuery(queryBytes, queryBytes.size)

        assertNotNull(parsed)
        assertEquals(0x1234, parsed!!.id)
        assertEquals("chatgpt.com", parsed.qname)
        assertEquals(1, parsed.qtype)
        assertEquals(1, parsed.qclass)
    }

    @Test
    fun testBuildAResponse() {
        val queryBytes = buildSampleQuery(0xABCD, "api.anthropic.com", 1)
        val parsed = DnsPacketCodec.parseQuery(queryBytes, queryBytes.size)!!

        val response = DnsPacketCodec.buildAResponse(parsed, listOf("143.20.64.192"), ttlSeconds = 300)

        assertTrue(response.size > queryBytes.size)

        // Verify ID matching
        val respId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        assertEquals(0xABCD, respId)

        // Verify Flags (0x8180)
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertEquals(0x8180, flags)

        // Verify QDCOUNT = 1, ANCOUNT = 1
        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        assertEquals(1, qdCount)
        assertEquals(1, anCount)

        // Verify last 4 bytes are IP 143.20.64.192
        val len = response.size
        val ipBytes = byteArrayOf(
            response[len - 4], response[len - 3], response[len - 2], response[len - 1]
        )
        val ipStr = InetAddress.getByAddress(ipBytes).hostAddress
        assertEquals("143.20.64.192", ipStr)
    }

    @Test
    fun testBuildNoDataResponse() {
        val queryBytes = buildSampleQuery(0x5678, "chatgpt.com", 28) // AAAA
        val parsed = DnsPacketCodec.parseQuery(queryBytes, queryBytes.size)!!

        val response = DnsPacketCodec.buildNoDataResponse(parsed)

        // Verify ID matching
        val respId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        assertEquals(0x5678, respId)

        // Verify ANCOUNT = 0
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        assertEquals(0, anCount)
    }
}
