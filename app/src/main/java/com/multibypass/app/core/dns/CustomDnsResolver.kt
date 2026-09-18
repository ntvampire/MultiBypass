package com.multibypass.app.core.dns

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class CustomDnsResolver(
    private val bootstrapIp: String = "8.8.8.8"
) {
    companion object {
        private const val TAG = "CustomDnsResolver"
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun resolveViaDoh(dohUrl: String, hostname: String): List<InetAddress> {
        return try {
            val appDns = DnsOverHttps.Builder()
                .client(baseClient)
                .url(dohUrl.toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName(bootstrapIp))
                .includeIPv6(false)
                .build()

            appDns.lookup(hostname)
        } catch (e: Exception) {
            Log.w(TAG, "DoH lookup failed for $hostname via $dohUrl: ${e.message}")
            try {
                // Fallback to standard resolution
                InetAddress.getAllByName(hostname).toList()
            } catch (ex: Exception) {
                emptyList()
            }
        }
    }
}
