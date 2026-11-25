package com.multibypass.app.core.lists

import android.content.Context
import android.util.Log
import com.multibypass.app.core.smarthosts.SmartHostsManager
import com.multibypass.app.data.model.CORE_ANTIDPI_ROOT_DOMAINS
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ListUpdateResult(
    val success: Boolean,
    val smartHostsCount: Int = 0,
    val antiDpiCount: Int = 0,
    val strategiesCount: Int = 0,
    val errorMessage: String? = null
)

object ListUpdateManager {
    private const val TAG = "ListUpdateManager"

    // Noktomezo RussiaFancyLists upstream fallback
    private const val NOKTOMEZO_FALLBACK_URL = "https://raw.githubusercontent.com/Noktomezo/RussiaFancyLists/main/lists/hosts/smart-no-crutch.hosts"

    // Primary: GitHub Pages. Fallback: GitHub raw repo
    private const val BASE_PAGES_URL = "https://ntvampire.github.io/MultiBypass"
    private const val BASE_RAW_URL = "https://raw.githubusercontent.com/ntvampire/MultiBypass/main/docs"

    private val EXCLUDED_SMARTHOSTS = setOf("apis.google.com", "suggestqueries.google.com")

    suspend fun forceUpdateAll(context: Context): ListUpdateResult = withContext(Dispatchers.IO) {
        try {
            val manifestJson = fetchWithFallback("domains_version.json")
            val expectedHashes = parseManifestHashes(manifestJson)

            var fromExternalFallback = false
            var smartHostsContent = fetchWithFallback(SmartHostsManager.HOSTS_FILE_NAME)
            if (smartHostsContent.isNotBlank() && !verifyChecksum(smartHostsContent, expectedHashes["smarthosts"])) {
                Log.w(TAG, "SHA-256 mismatch for smart-no-crutch.hosts, rejecting payload")
                smartHostsContent = ""
            }
            if (smartHostsContent.isBlank()) {
                Log.d(TAG, "Primary smart-hosts fetch failed, trying upstream fallback: $NOKTOMEZO_FALLBACK_URL")
                smartHostsContent = fetchUrlWithRetry(NOKTOMEZO_FALLBACK_URL)
                fromExternalFallback = smartHostsContent.isNotBlank()
            }
            if (fromExternalFallback) {
                smartHostsContent = sanitizeSmartHostsContent(smartHostsContent)
            }

            var antiDpiContent = fetchWithFallback("domains_antidpi.txt")
            if (antiDpiContent.isNotBlank() && !verifyChecksum(antiDpiContent, expectedHashes["antidpi"])) {
                Log.w(TAG, "SHA-256 mismatch for domains_antidpi.txt, rejecting payload")
                antiDpiContent = ""
            }

            var strategiesContent = fetchWithFallback("strategies.txt")
            if (strategiesContent.isNotBlank() && !verifyChecksum(strategiesContent, expectedHashes["strategies"])) {
                Log.w(TAG, "SHA-256 mismatch for strategies.txt, rejecting payload")
                strategiesContent = ""
            }

            var smartHostsCount = 0
            var antiDpiCount = 0
            var strategiesCount = 0

            val repo = SettingsRepository.getInstance(context)

            if (smartHostsContent.isNotBlank()) {
                val hostsFile = File(context.filesDir, SmartHostsManager.HOSTS_FILE_NAME)
                hostsFile.writeText(smartHostsContent)
                val count = SmartHostsManager.loadFromFile(hostsFile)
                if (count > 0) {
                    smartHostsCount = count
                    repo.updateSmartHostsConfig(
                        repo.smartHostsConfig.value.copy(
                            domainCount = count,
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                }
            }

            if (antiDpiContent.isNotBlank()) {
                val antiDpiDomains = parseDomainList(antiDpiContent)
                if (antiDpiDomains.isNotEmpty()) {
                    antiDpiCount = antiDpiDomains.size
                    File(context.filesDir, "domains_antidpi.txt").writeText(antiDpiContent)
                    com.multibypass.app.core.byedpi.ByeDpiController.ensureHostsFile(context)
                    com.multibypass.app.core.smarthosts.SmartHostsDnsRelay.clearAntiDpiCache()
                    val mergedDomains = (antiDpiDomains + repo.antiDpiConfig.value.domains).distinct()
                    repo.updateAntiDpiConfig(repo.antiDpiConfig.value.copy(domains = mergedDomains))
                }
            }

            if (strategiesContent.isNotBlank()) {
                val strategies = strategiesContent.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .distinct()
                    .toList()
                if (strategies.isNotEmpty()) {
                    strategiesCount = strategies.size
                    File(context.filesDir, "strategies.txt").writeText(strategiesContent)
                    File(context.filesDir, "proxytest_strategies.list").writeText(strategiesContent)
                }
            }

            val hasAnySuccess = smartHostsCount > 0 || antiDpiCount > 0 || strategiesCount > 0
            ListUpdateResult(
                success = hasAnySuccess,
                smartHostsCount = smartHostsCount,
                antiDpiCount = antiDpiCount,
                strategiesCount = strategiesCount,
                errorMessage = if (!hasAnySuccess) "Не удалось получить списки из сети" else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "forceUpdateAll failed", e)
            ListUpdateResult(
                success = false,
                errorMessage = e.message ?: "Ошибка обновления списков"
            )
        }
    }

    private fun parseDomainList(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .distinct()
            .toList()
    }

    private fun fetchWithFallback(filename: String): String {
        return try {
            fetchUrl("$BASE_PAGES_URL/$filename")
        } catch (e: Exception) {
            Log.d(TAG, "Pages fetch failed for $filename, trying raw: ${e.message}")
            try {
                fetchUrl("$BASE_RAW_URL/$filename")
            } catch (e2: Exception) {
                Log.w(TAG, "Raw fetch also failed for $filename: ${e2.message}")
                ""
            }
        }
    }

    private fun fetchUrlWithRetry(urlString: String, retries: Int = 2): String {
        for (i in 0..retries) {
            try {
                val res = fetchUrl(urlString)
                if (res.isNotBlank()) return res
            } catch (e: Exception) {
                if (i == retries) Log.w(TAG, "fetchUrlWithRetry failed for $urlString: ${e.message}")
            }
        }
        return ""
    }

    private fun fetchUrl(urlString: String): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MultiBypass-App")
        }
        try {
            if (conn.responseCode in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            throw IllegalStateException("HTTP ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseManifestHashes(manifestJson: String): Map<String, String> {
        if (manifestJson.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(manifestJson)
            val result = mutableMapOf<String, String>()
            for (key in listOf("antidpi", "strategies", "smarthosts")) {
                val sha = root.optJSONObject(key)?.optString("sha256", "")?.trim().orEmpty()
                if (sha.length == 64) {
                    result[key] = sha.lowercase()
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    internal fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun verifyChecksum(content: String, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) return true
        return computeSha256(content).equals(expectedSha256.trim(), ignoreCase = true)
    }

    internal fun sanitizeSmartHostsContent(raw: String): String {
        val out = StringBuilder()
        for (line in raw.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) {
                out.appendLine(line)
                continue
            }
            val tokens = s.split(Regex("\\s+"))
            if (tokens.size < 2) continue
            val ip = tokens[0]
            val filteredDomains = tokens.drop(1).map { it.trim().trimEnd('.').lowercase() }.filter { dom ->
                dom.isNotEmpty() &&
                    !dom.contains("4pda") &&
                    !EXCLUDED_SMARTHOSTS.contains(dom) &&
                    !dom.endsWith(".ru") && !dom.endsWith(".su") && !dom.endsWith(".by") && !dom.endsWith(".xn--p1ai") &&
                    CORE_ANTIDPI_ROOT_DOMAINS.none { dom == it || dom.endsWith(".$it") }
            }
            if (filteredDomains.isNotEmpty()) {
                out.appendLine("$ip ${filteredDomains.joinToString(" ")}")
            }
        }
        return out.toString()
    }

    fun initLocalLists(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmartHostsManager.initialize(context)
            } catch (_: Exception) {}
        }
    }
}
