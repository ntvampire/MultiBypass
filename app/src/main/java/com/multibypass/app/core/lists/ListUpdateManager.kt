package com.multibypass.app.core.lists

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.multibypass.app.core.dns.DnsPresets
import com.multibypass.app.data.model.DnsPreset
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ListUpdateResult(
    val success: Boolean,
    val dnsCount: Int = 0,
    val antiDpiCount: Int = 0,
    val strategiesCount: Int = 0,
    val dnsProvidersCount: Int = 0,
    val errorMessage: String? = null
)

object ListUpdateManager {
    private const val TAG = "ListUpdateManager"

    // Primary: GitHub Pages. Fallback: GitHub raw repo
    private const val BASE_PAGES_URL = "https://ntvampire.github.io/MultiBypass"
    private const val BASE_RAW_URL = "https://raw.githubusercontent.com/ntvampire/MultiBypass/main/docs"

    suspend fun forceUpdateAll(context: Context): ListUpdateResult = withContext(Dispatchers.IO) {
        try {
            val dnsContent = fetchWithFallback("domains_dns.txt")
            val antiDpiContent = fetchWithFallback("domains_antidpi.txt")
            val strategiesContent = fetchWithFallback("strategies.txt")
            val providersContent = fetchWithFallback("dns_providers.json")

            var dnsCount = 0
            var antiDpiCount = 0
            var strategiesCount = 0
            var providersCount = 0

            val repo = SettingsRepository.getInstance(context)

            if (dnsContent.isNotBlank()) {
                val dnsDomains = parseDomainList(dnsContent)
                if (dnsDomains.isNotEmpty()) {
                    dnsCount = dnsDomains.size
                    File(context.filesDir, "domains_dns.txt").writeText(dnsContent)
                    repo.updateDnsConfig(repo.dnsConfig.value.copy(domains = dnsDomains))
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

            if (providersContent.isNotBlank()) {
                try {
                    val type = object : TypeToken<List<DnsPreset>>() {}.type
                    val presets: List<DnsPreset> = Gson().fromJson(providersContent, type)
                    if (presets.isNotEmpty()) {
                        providersCount = presets.size
                        File(context.filesDir, "dns_providers.json").writeText(providersContent)
                        DnsPresets.updateList(presets)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse dns_providers.json", e)
                }
            }

            val hasAnySuccess = dnsCount > 0 || antiDpiCount > 0 || strategiesCount > 0 || providersCount > 0
            ListUpdateResult(
                success = hasAnySuccess,
                dnsCount = dnsCount,
                antiDpiCount = antiDpiCount,
                strategiesCount = strategiesCount,
                dnsProvidersCount = providersCount,
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
        DnsPresets.loadFromDisk(context)
    }
}
