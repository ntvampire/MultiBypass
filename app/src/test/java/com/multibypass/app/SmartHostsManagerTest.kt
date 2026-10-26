package com.multibypass.app

import com.multibypass.app.core.smarthosts.SmartHostsManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmartHostsManagerTest {

    @Before
    fun setUp() {
        SmartHostsManager.clear()
    }

    @After
    fun tearDown() {
        SmartHostsManager.clear()
    }

    @Test
    fun testParseAndResolveExactDomains() {
        val sampleHosts = listOf(
            "# Loopback",
            "127.0.0.1 localhost",
            "::1 localhost",
            "",
            "# Geoblock",
            "143.20.64.192 37signals.com www.37signals.com",
            "181.215.40.49 ai.com",
            "31.25.239.132 api.anthropic.com"
        )

        val count = SmartHostsManager.loadFromLines(sampleHosts)
        assertEquals(4, count) // 37signals.com, www.37signals.com, ai.com, api.anthropic.com

        val resAi = SmartHostsManager.resolve("ai.com")
        assertNotNull(resAi)
        assertEquals(listOf("181.215.40.49"), resAi)

        val res37 = SmartHostsManager.resolve("37signals.com")
        assertNotNull(res37)
        assertEquals(listOf("143.20.64.192"), res37)

        val resWww37 = SmartHostsManager.resolve("www.37signals.com")
        assertNotNull(resWww37)
        assertEquals(listOf("143.20.64.192"), resWww37)
    }

    @Test
    fun testCaseInsensitiveAndTrailingDot() {
        val lines = listOf("181.215.40.49 chatgpt.com")
        SmartHostsManager.loadFromLines(lines)

        val res1 = SmartHostsManager.resolve("ChatGPT.COM")
        assertNotNull(res1)
        assertEquals("181.215.40.49", res1!!.first())

        val res2 = SmartHostsManager.resolve("chatgpt.com.")
        assertNotNull(res2)
        assertEquals("181.215.40.49", res2!!.first())
    }

    @Test
    fun testSubdomainHierarchyMatching() {
        val lines = listOf("143.20.64.192 anthropic.com")
        SmartHostsManager.loadFromLines(lines)

        // Subdomain should match parent domain
        val resSub = SmartHostsManager.resolve("billing.console.anthropic.com")
        assertNotNull(resSub)
        assertEquals("143.20.64.192", resSub!!.first())

        // Non-matching domain should return null
        val resOther = SmartHostsManager.resolve("google.com")
        assertNull(resOther)
    }

    @Test
    fun testMultipleIpsPerDomain() {
        val lines = listOf(
            "143.20.64.192 chatgpt.com",
            "31.25.239.132 chatgpt.com"
        )
        SmartHostsManager.loadFromLines(lines)

        val res = SmartHostsManager.resolve("chatgpt.com")
        assertNotNull(res)
        assertEquals(2, res!!.size)
        assertTrue(res.contains("143.20.64.192"))
        assertTrue(res.contains("31.25.239.132"))
    }
}
