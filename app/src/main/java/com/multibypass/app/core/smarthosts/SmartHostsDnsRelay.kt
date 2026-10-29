package com.multibypass.app.core.smarthosts

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.multibypass.app.core.vpn.MultiBypassVpnService
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * SmartHosts DNS Relay:
 * Intercepts DNS queries on port 53.
 * - If domain is in SmartHosts (ChatGPT, Claude, Gemini, etc.): answers instantly with European SNI proxy IP.
 * - Otherwise: forwards query to the active network DNS (e.g. mobile operator) via a protected socket,
 *   ensuring 100% compatibility with carrier whitelists and domestic services.
 */
object SmartHostsDnsRelay {

    private const val TAG = "SmartHostsDnsRelay"
    private const val FALLBACK_DNS_IP = "77.88.8.8" // Yandex DNS fallback
    private const val DNS_TIMEOUT_MS = 2500

    @Volatile
    private var cachedUpstreamDns: String? = null

    fun refreshUpstreamDns(context: Context) {
        cachedUpstreamDns = null
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
            // 1. Check SmartHosts for geoblocked domain
            if (parsed.qtype == DnsPacketCodec.TYPE_A) {
                val mappedIps = SmartHostsManager.resolve(parsed.qname)
                if (!mappedIps.isNullOrEmpty()) {
                    Log.d(TAG, "SmartHosts HIT: ${parsed.qname} -> ${mappedIps.first()} (instant 0 ms)")
                    return DnsPacketCodec.buildAResponse(parsed, mappedIps)
                }
            } else if (parsed.qtype == DnsPacketCodec.TYPE_AAAA) {
                if (SmartHostsManager.isGeoblocked(parsed.qname)) {
                    Log.d(TAG, "SmartHosts AAAA NODATA: ${parsed.qname}")
                    return DnsPacketCodec.buildNoDataResponse(parsed)
                }
            }
        }

        // 2. Domain not in SmartHosts (or non-A query): forward to upstream system DNS
        return forwardToUpstreamDns(context, queryData, queryLength)
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
