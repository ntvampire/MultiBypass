package com.multibypass.app

import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.lists.ListUpdateManager
import com.multibypass.app.core.updater.AppUpdateManager
import com.multibypass.app.data.model.CORE_ANTIDPI_ROOT_DOMAINS
import com.multibypass.app.data.model.META_ANTIDPI_ROOT_DOMAINS
import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {

    @Test
    fun testByeDpiSeparateProfilesUsesAutoNoneFlag() {
        val cmd = ByeDpiController.buildStartCommand(
            strategy = "-s1 -f-1 -t5 -a1",
            instagramStrategy = "-s2 -a1",
            separateProfiles = true,
            port = 1080,
            useHosts = true,
            hostsPath = "/tmp/all.txt",
            metaHostsPath = "/tmp/meta.txt",
            generalHostsPath = "/tmp/general.txt"
        )
        assertTrue(cmd.contains("-A n --hosts \"/tmp/general.txt\""))
        val args = ByeDpiController.parseArgs(cmd)
        assertEquals("ciadpi", args[0])
        val autoIndex = args.indexOf("-A")
        assertTrue(autoIndex > 0)
        assertEquals("n", args[autoIndex + 1])
        assertEquals("--hosts", args[autoIndex + 2])
    }

    @Test
    fun testByeDpiSanitizesSniPlaceholder() {
        val cmd = ByeDpiController.buildStartCommand(
            strategy = "-o1 -n {sni} -a1",
            useHosts = false
        )
        assertFalse(cmd.contains("{sni}"))
        assertTrue(cmd.contains("www.google.com"))
    }

    @Test
    fun testAppUpdateVersionAndCleanReinstallDetection() {
        assertTrue(AppUpdateManager.isVersionNewer("v3.3.3", "3.3.2"))
        assertTrue(AppUpdateManager.isVersionNewer("v4.0.0", "3.3.3"))
        assertTrue(AppUpdateManager.isVersionNewer("v3.4.0", "3.4.0-beta.1"))
        assertFalse(AppUpdateManager.isVersionNewer("v3.3.2", "3.3.2"))
        assertFalse(AppUpdateManager.isVersionNewer("v3.3.1", "3.3.2"))

        assertFalse(AppUpdateManager.requiresCleanReinstallUpgrade("v3.3.3", "3.3.2", "Bug fixes"))
        assertTrue(AppUpdateManager.requiresCleanReinstallUpgrade("v4.0.0", "3.3.3", "Major release"))
        assertTrue(AppUpdateManager.requiresCleanReinstallUpgrade("v3.4.0", "3.3.3", "Important: [NEW_SIGNATURE] clean install"))
    }

    @Test
    fun testListUpdateSanitizationAndSha256() {
        val rawFallback = """
            # comment
            5.175.161.3 chatgpt.com 4pda.to apis.google.com suggestqueries.google.com
            5.175.161.40 youtube.com rr1---sn.googlevideo.com instagram.com sberbank.ru claude.ai
        """.trimIndent()

        val sanitized = ListUpdateManager.sanitizeSmartHostsContent(rawFallback)
        assertTrue(sanitized.contains("chatgpt.com"))
        assertTrue(sanitized.contains("claude.ai"))
        assertFalse(sanitized.contains("4pda"))
        assertFalse(sanitized.contains("apis.google.com"))
        assertFalse(sanitized.contains("suggestqueries.google.com"))
        assertFalse(sanitized.contains("youtube.com"))
        assertFalse(sanitized.contains("googlevideo.com"))
        assertFalse(sanitized.contains("instagram.com"))
        assertFalse(sanitized.contains("sberbank.ru"))

        val sample = "hello multibypass"
        val hash = ListUpdateManager.computeSha256(sample)
        assertTrue(ListUpdateManager.verifyChecksum(sample, hash))
        assertFalse(ListUpdateManager.verifyChecksum("tampered", hash))
        assertTrue(ListUpdateManager.verifyChecksum(sample, null))
    }

    @Test
    fun testSharedDomainConstantsConsistency() {
        assertTrue(CORE_ANTIDPI_ROOT_DOMAINS.containsAll(META_ANTIDPI_ROOT_DOMAINS))
        assertTrue(CORE_ANTIDPI_ROOT_DOMAINS.contains("googlevideo.com"))
        assertTrue(CORE_ANTIDPI_ROOT_DOMAINS.contains("i.instagram.com"))
    }
}
