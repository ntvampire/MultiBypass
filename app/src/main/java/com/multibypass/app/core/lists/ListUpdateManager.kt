package com.multibypass.app.core.lists

import android.content.Context
import android.util.Log
import com.multibypass.app.core.smarthosts.SmartHostsManager
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ListUpdateResult(
    val success: Boolean,
    val smartHostsCount: Int = 0,
    val antiDpiCount: Int = 0,
    val strategiesCount: Int = 0,
    val errorMessage: String? = null
)

object ListUpdateManager {
    private const val TAG = "ListUpdateManager"

    // Noktomezo RussiaFancyLists smart-no-crutch hosts URL
    private const val NOKTOMEZO_HOSTS_URL = "https://raw.githubusercontent.com/Noktomezo/RussiaFancyLists/main/lists/hosts/smart-no-crutch.hosts"

    // Primary: GitHub Pages. Fallback: GitHub raw repo
    private const val BASE_PAGES_URL = "https://ntvampire.github.io/MultiBypass"
    private const val BASE_RAW_URL = "https://raw.githubusercontent.com/ntvampire/MultiBypass/main/docs"

    suspend fun forceUpdateAll(context: Context): ListUpdateResult = withContext(Dispatchers.IO) {
        try {
            val smartHostsContent = fetchUrlWithRetry(NOKTOMEZO_HOSTS_URL)
            val antiDpiContent = fetchWithFallback("domains_antidpi.txt")
            val strategiesContent = fetchWithFallback("strategies.txt")

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
                    repo.updateAntiDpiConfig(repo.antiDpiConfig.value.copy(domains = antiDpiDomains))
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

    fun initLocalLists(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmartHostsManager.initialize(context)
            } catch (_: Exception) {}
        }
    }
}
