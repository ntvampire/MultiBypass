package com.multibypass.app.core.smarthosts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * SmartHosts Manager:
 * Manages the local hosts database for geoblock bypassing based on Noktomezo's RussiaFancyLists
 * (smart-no-crutch.hosts).
 *
 * Maps blocked domains (e.g. chatgpt.com, anthropic.com, adobe.com) directly to European SNI proxy IPs.
 */
object SmartHostsManager {

    private const val TAG = "SmartHostsManager"
    const val HOSTS_FILE_NAME = "smart-no-crutch.hosts"

    private val IPV4_PATTERN = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    // Mapping: domain (lowercase) -> list of proxy IPv4 strings
    @Volatile
    private var hostMap: Map<String, List<String>> = emptyMap()

    private val _domainCount = MutableStateFlow(0)
    val domainCount: StateFlow<Int> = _domainCount.asStateFlow()

    private val _lastUpdated = MutableStateFlow(0L)
    val lastUpdated: StateFlow<Long> = _lastUpdated.asStateFlow()

    /**
     * Initializes the manager from cache or bundled assets.
     */
    suspend fun initialize(context: Context): Int = withContext(Dispatchers.IO) {
        val targetFile = File(context.filesDir, HOSTS_FILE_NAME)
        if (!targetFile.exists() || targetFile.length() == 0L) {
            try {
                context.assets.open(HOSTS_FILE_NAME).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Copied bundled $HOSTS_FILE_NAME to filesDir (${targetFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy bundled $HOSTS_FILE_NAME from assets", e)
            }
        }

        if (targetFile.exists()) {
            _lastUpdated.value = targetFile.lastModified()
            loadFromFile(targetFile)
        } else {
            0
        }
    }

    /**
     * Parses a hosts file and updates the in-memory lookup table.
     */
    fun loadFromFile(file: File): Int {
        if (!file.exists()) return 0
        return try {
            val lines = file.readLines()
            loadFromLines(lines)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read hosts file: ${file.absolutePath}", e)
            0
        }
    }

    /**
     * Domains explicitly excluded from SmartHosts SNI relay (e.g. 4pda).
     * These domains break when routed through foreign SNI relays and must be resolved
     * directly by the native operator/carrier DNS.
     */
    fun isExcludedDomain(domain: String): Boolean {
        val d = domain.trim().trimEnd('.').lowercase()
        return d == "4pda.to" || d.endsWith(".4pda.to") ||
               d == "4pda.ru" || d.endsWith(".4pda.ru") ||
               d.contains("4pda")
    }

    /**
     * Parses lines in standard /etc/hosts format: <IP> <domain1> <domain2> ...
     */
    fun loadFromLines(lines: List<String>): Int {
        val newMap = mutableMapOf<String, MutableList<String>>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val tokens = line.split(Regex("\\s+"))
            if (tokens.size < 2) continue

            val ip = tokens[0].trim()
            if (!IPV4_PATTERN.matcher(ip).matches() || ip == "127.0.0.1" || ip == "0.0.0.0") {
                continue
            }

            for (i in 1 until tokens.size) {
                val domain = tokens[i].trim().trimEnd('.').lowercase()
                if (domain.isNotEmpty() && !domain.startsWith("#") && domain != "localhost") {
                    if (isExcludedDomain(domain)) continue
                    val list = newMap.getOrPut(domain) { mutableListOf() }
                    if (!list.contains(ip)) {
                        list.add(ip)
                    }
                }
            }
        }

        hostMap = newMap
        val count = newMap.size
        _domainCount.value = count
        Log.i(TAG, "Loaded $count unique geoblock domains into SmartHosts table")
        return count
    }

    /**
     * Resolves a domain name to its mapped SNI proxy IP list.
     * Supports exact match and parent domain hierarchy.
     */
    fun resolve(domain: String): List<String>? {
        if (hostMap.isEmpty()) return null
        val cleanDomain = domain.trim().trimEnd('.').lowercase()
        if (cleanDomain.isEmpty() || isExcludedDomain(cleanDomain)) return null

        // 1. Exact match
        hostMap[cleanDomain]?.let { return it }

        // 2. Walk up domain hierarchy (e.g. sub.domain.com -> domain.com)
        var dotIndex = cleanDomain.indexOf('.')
        while (dotIndex != -1 && dotIndex < cleanDomain.length - 1) {
            val parentDomain = cleanDomain.substring(dotIndex + 1)
            if (parentDomain.contains('.')) { // Ensure not top-level domain alone
                hostMap[parentDomain]?.let { return it }
            }
            dotIndex = cleanDomain.indexOf('.', dotIndex + 1)
        }

        return null
    }

    /**
     * Checks if a domain is covered by SmartHosts.
     */
    fun isGeoblocked(domain: String): Boolean {
        return resolve(domain) != null
    }

    /**
     * Clear table for testing or reset.
     */
    fun clear() {
        hostMap = emptyMap()
        _domainCount.value = 0
    }
}
