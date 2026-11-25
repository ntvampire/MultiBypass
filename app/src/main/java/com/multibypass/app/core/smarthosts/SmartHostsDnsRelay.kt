package com.multibypass.app.core.smarthosts

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.CORE_ANTIDPI_ROOT_DOMAINS
import com.multibypass.app.data.repository.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SmartHosts DNS Relay:
 * Intercepts DNS queries on port 53.
 * - Suppresses DNS Type 65 (HTTPS/SVCB) for Anti-DPI and Geoblocked domains to force instant TCP TLS 1.3.
 * - Suppresses IPv6 (AAAA) for Anti-DPI and Geoblocked domains since TUN only intercepts IPv4.
 * - SmartHosts (ChatGPT, Claude, Gemini, etc.): answers instantly with European SNI proxy IP.
 * - Anti-DPI (YouTube, Instagram, Discord, X): resolves via encrypted DoH (with RAM cache & UDP fallback)
 *   to prevent carrier DNS spoofing or UDP:53 hijacking on cellular networks.
 * - Everything else: forwards to active carrier/Wi-Fi DNS via protected socket for whitelist compatibility.
 */
object SmartHostsDnsRelay {

    private const val TAG = "SmartHostsDnsRelay"
    private const val FALLBACK_DNS_IP = "77.88.8.8" // Yandex DNS fallback
    private const val DNS_TIMEOUT_MS = 2500
    private const val ANTI_DPI_CACHE_TTL_MS = 120_000L
    private const val ANTI_DPI_CACHE_MAX_SIZE = 512

    private data class CachedIpv4Entry(
        val ips: List<String>,
        val expiresAtMs: Long
    )

    private val antiDpiIpCache = ConcurrentHashMap<String, CachedIpv4Entry>()

    private val dohBaseClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1800, TimeUnit.MILLISECONDS)
            .readTimeout(1800, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val dohResolvers: List<DnsOverHttps> by lazy {
        listOfNotNull(
            createDoh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
            createDoh("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112")
        )
    }

    private fun createDoh(url: String, vararg bootstrapIps: String): DnsOverHttps? {
        return try {
            DnsOverHttps.Builder()
                .client(dohBaseClient)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(bootstrapIps.map { InetAddress.getByName(it) })
                .includeIPv6(false)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to init DoH resolver $url: ${t.message}")
            null
        }
    }

    private val CLEAN_DNS_IPS = listOf("8.8.8.8", "9.9.9.9", "77.88.8.8", "1.1.1.1")

    @Volatile
    private var cachedUpstreamDns: String? = null

    @Volatile
    private var antiDpiDomainsCache: Set<String>? = null

    fun clearAntiDpiCache() {
        antiDpiDomainsCache = null
        antiDpiIpCache.clear()
    }

    private fun getAntiDpiDomains(context: Context): Set<String> {
        antiDpiDomainsCache?.let { return it }
        val fileDomains = try {
            val srcFile = java.io.File(context.filesDir, "domains_antidpi_clean.txt")
            val content = if (srcFile.exists() && srcFile.length() > 0) {
                srcFile.readText()
            } else {
                val orig = java.io.File(context.filesDir, "domains_antidpi.txt")
                if (orig.exists() && orig.length() > 0) orig.readText()
                else context.assets.open("domains_antidpi.txt").bufferedReader().use { it.readText() }
            }
            content.lines()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
        } catch (_: Exception) {
            emptyList()
        }
        val userDomains = try {
            SettingsRepository.getInstance(context).antiDpiConfig.value.domains
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
        val set = (fileDomains + userDomains).toSet()
        antiDpiDomainsCache = set
        return set
    }

    fun isAntiDpiDomain(context: Context, domain: String): Boolean {
        val q = domain.trim().lowercase().removeSuffix(".")
        if (CORE_ANTIDPI_ROOT_DOMAINS.any { q == it || q.endsWith(".$it") }) {
            return true
        }
        val set = getAntiDpiDomains(context)
        return set.any { q == it || q.endsWith(".$it") }
    }

    fun refreshUpstreamDns(context: Context) {
        cachedUpstreamDns = null
        antiDpiIpCache.clear()
        try {
            dohBaseClient.connectionPool.evictAll()
        } catch (_: Throwable) {}
        val newDns = getUpstreamDnsIp(context)
        cachedUpstreamDns = newDns
        Log.i(TAG, "Upstream DNS refreshed to: $newDns")
    }

    /**
     * Resolves a DNS query packet.
     * Returns the DNS response ByteArray, or null if resolution failed.
     */
    fun handleDnsQuery(
        context: Context,
        queryData: ByteArray,
        queryLength: Int
    ): ByteArray? {
        val parsed = DnsPacketCodec.parseQuery(queryData, queryLength)

        if (parsed != null) {
            val smartHostsEnabled = try {
                SettingsRepository.getInstance(context).smartHostsConfig.value.enabled
            } catch (_: Exception) {
                true
            }
            val isAntiDpi = isAntiDpiDomain(context, parsed.qname)
            val isGeoblocked = smartHostsEnabled && SmartHostsManager.isGeoblocked(parsed.qname)

            // 0. Suppress DNS Type 65 (HTTPS / SVCB) for Anti-DPI & Geoblocked domains
            // Forces Android Cronet/Chrome/YouTube/Instagram onto TCP TLS 1.3 without waiting for QUIC UDP timeouts.
            if (parsed.qtype == DnsPacketCodec.TYPE_HTTPS && (isAntiDpi || isGeoblocked)) {
                Log.d(TAG, "HTTPS Type 65 suppressed (NODATA -> instant TCP fallback): ${parsed.qname}")
                return DnsPacketCodec.buildNoDataResponse(parsed)
            }

            // 1. Suppress AAAA (IPv6) for SmartHosts & Anti-DPI domains so LTE IPv6 never bypasses our IPv4 TUN
            if (parsed.qtype == DnsPacketCodec.TYPE_AAAA && (isGeoblocked || isAntiDpi)) {
                Log.d(TAG, "AAAA suppressed (NODATA -> keep traffic inside IPv4 TUN): ${parsed.qname}")
                return DnsPacketCodec.buildNoDataResponse(parsed)
            }

            // 2. Check SmartHosts for geoblocked domain (only when enabled)
            if (smartHostsEnabled && parsed.qtype == DnsPacketCodec.TYPE_A) {
                val mappedIps = SmartHostsManager.resolve(parsed.qname)
                if (!mappedIps.isNullOrEmpty()) {
                    Log.d(TAG, "SmartHosts HIT: ${parsed.qname} -> ${mappedIps.first()} (instant 0 ms)")
                    return DnsPacketCodec.buildAResponse(parsed, mappedIps)
                }
            }

            // 3. Anti-DPI domain A query: resolve via DoH (+ RAM cache) to bypass mobile UDP:53 hijacking
            if (isAntiDpi && parsed.qtype == DnsPacketCodec.TYPE_A) {
                val cleanIps = resolveCleanIpv4(parsed.qname)
                if (!cleanIps.isNullOrEmpty()) {
                    return DnsPacketCodec.buildAResponse(parsed, cleanIps, ttlSeconds = 120)
                }
                val udpClean = queryCleanDns(queryData, queryLength)
                if (udpClean != null) {
                    return udpClean
                }
            }
        }

        // 4. Domain not in SmartHosts/AntiDPI: forward to upstream system DNS
        val resp = forwardToUpstreamDns(context, queryData, queryLength)
        if (resp != null) {
            return resp
        }

        // Fallback to clean DNS if operator DNS is unreachable/times out
        return queryCleanDns(queryData, queryLength)
    }

    private fun resolveCleanIpv4(qname: String): List<String>? {
        val now = System.currentTimeMillis()
        antiDpiIpCache[qname]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return cached.ips
            }
            antiDpiIpCache.remove(qname)
        }

        for (resolver in dohResolvers) {
            try {
                val addrs = resolver.lookup(qname)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .filter { it != "0.0.0.0" && !it.startsWith("127.") }
                    .distinct()
                if (addrs.isNotEmpty()) {
                    if (antiDpiIpCache.size >= ANTI_DPI_CACHE_MAX_SIZE) {
                        antiDpiIpCache.clear()
                    }
                    antiDpiIpCache[qname] = CachedIpv4Entry(addrs, now + ANTI_DPI_CACHE_TTL_MS)
                    return addrs
                }
            } catch (_: Throwable) {
                // Try next DoH resolver
            }
        }
        return null
    }

    /**
     * Queries unpoisoned public DNS servers via protected socket.
     */
    private fun queryCleanDns(queryData: ByteArray, queryLength: Int): ByteArray? {
        for (dnsIp in CLEAN_DNS_IPS) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                MultiBypassVpnService.protectSocket(socket)
                socket.soTimeout = 2000
                val targetAddr = InetAddress.getByName(dnsIp)
                val sendPacket = DatagramPacket(queryData, 0, queryLength, targetAddr, 53)
                socket.send(sendPacket)

                val recvBuf = ByteArray(1500)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)

                val response = ByteArray(recvPacket.length)
                System.arraycopy(recvBuf, 0, response, 0, recvPacket.length)
                return response
            } catch (_: Exception) {
                // Try next clean DNS server
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Forwards standard DNS query to the underlying network DNS using a VPN-protected socket.
     */
    private fun forwardToUpstreamDns(
        context: Context,
        queryData: ByteArray,
        queryLength: Int
    ): ByteArray? {
        val upstreamDns = cachedUpstreamDns ?: getUpstreamDnsIp(context)
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            MultiBypassVpnService.protectSocket(socket)
            socket.soTimeout = DNS_TIMEOUT_MS

            val targetAddr = InetAddress.getByName(upstreamDns)
            val sendPacket = DatagramPacket(queryData, 0, queryLength, targetAddr, 53)
            socket.send(sendPacket)

            val recvBuf = ByteArray(1500)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val response = ByteArray(recvPacket.length)
            System.arraycopy(recvBuf, 0, response, 0, recvPacket.length)
            response
        } catch (e: Exception) {
            Log.w(TAG, "Upstream DNS query failed for $upstreamDns: ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Determines the active network's native DNS server (e.g. cellular carrier or Wi-Fi gateway).
     */
    fun getUpstreamDnsIp(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            if (activeNetwork != null) {
                val linkProps = cm.getLinkProperties(activeNetwork)
                val servers = linkProps?.dnsServers
                if (!servers.isNullOrEmpty()) {
                    val ipv4 = servers.firstOrNull { it.address.size == 4 }
                    if (ipv4 != null) {
                        return ipv4.hostAddress ?: FALLBACK_DNS_IP
                    }
                }
            }
            FALLBACK_DNS_IP
        } catch (e: Exception) {
            FALLBACK_DNS_IP
        }
    }
}
