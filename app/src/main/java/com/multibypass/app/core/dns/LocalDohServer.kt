package com.multibypass.app.core.dns

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalDohServer(
    private val port: Int = 5353,
    private val dohUrl: String = "https://dns.comss.one/dns-query"
) {
    companion object {
        private const val TAG = "LocalDohServer"
        private const val BUFFER_SIZE = 4096
        private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()

        private val BOOTSTRAP_HOSTS = mapOf(
            "dns.comss.one" to listOf("92.223.109.31"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.11"),
            "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
            "common.dot.dns.yandex.net" to listOf("77.88.8.8"),
            "dns.nextdns.io" to listOf("45.90.28.0", "45.90.30.0")
        )
    }

    private var socket: DatagramSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val ips = BOOTSTRAP_HOSTS[hostname]
                if (ips != null) {
                    return ips.map { InetAddress.getByName(it) }
                }
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    listOf(InetAddress.getByName("92.223.109.31"))
                }
            }
        })
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Long, ByteArray>>()

    fun start() {
        if (serverJob != null) return

        serverJob = scope.launch {
            try {
                val s = DatagramSocket(port, InetAddress.getByName("127.0.0.1")).apply {
                    reuseAddress = true
                }
                socket = s
                Log.i(TAG, "Local DoH Server started on 127.0.0.1:$port forwarding to $dohUrl")

                val buffer = ByteArray(BUFFER_SIZE)

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                    } catch (e: Exception) {
                        if (!isActive) break
                        continue
                    }

                    val queryBytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val clientAddress = packet.address
                    val clientPort = packet.port

                    launch {
                        processQuery(queryBytes, clientAddress, clientPort, s)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Local DoH Server: ${e.message}", e)
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null
            }
        }
    }

    private suspend fun processQuery(
        queryBytes: ByteArray,
        clientAddress: InetAddress,
        clientPort: Int,
        s: DatagramSocket
    ) {
        if (queryBytes.size < 12) return

        val queryHash = queryBytes.drop(2).toByteArray().contentHashCode().toString()
        val now = System.currentTimeMillis()
        val cached = cache[queryHash]
        if (cached != null && (now - cached.first) < 60_000) {
            val resp = cached.second.clone()
            resp[0] = queryBytes[0]
            resp[1] = queryBytes[1]
            try {
                val respPacket = DatagramPacket(resp, resp.size, clientAddress, clientPort)
                s.send(respPacket)
                return
            } catch (_: Exception) {}
        }

        try {
            val request = Request.Builder()
                .url(dohUrl)
                .addHeader("Accept", "application/dns-message")
                .addHeader("Content-Type", "application/dns-message")
                .post(queryBytes.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.bytes()
                    if (body != null && body.size >= 12) {
                        cache[queryHash] = Pair(now, body)
                        val respPacket = DatagramPacket(body, body.size, clientAddress, clientPort)
                        s.send(respPacket)
                    }
                } else {
                    Log.w(TAG, "DoH server error: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH query error: ${e.message}")
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        cache.clear()
        Log.i(TAG, "Local DoH Server stopped")
    }
}
