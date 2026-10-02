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
    private val dohUrl: String = "https://dns.comss.one/dns-query",
    private val fallbackStandardIp: String = "92.223.109.31"
) {
    companion object {
        private const val TAG = "LocalDohServer"
        private const val BUFFER_SIZE = 4096
        private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()

        private val BOOTSTRAP_HOSTS = mapOf(
            "dns.comss.one" to listOf("92.223.109.31", "94.130.180.225"),
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
                Log.i(TAG, "Local DoH Server started on 127.0.0.1:$port forwarding to $dohUrl (fallback: $fallbackStandardIp:53)")

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

        var handled = false

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
                        handled = true
                    }
                } else {
                    Log.w(TAG, "DoH server returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH query error: ${e.message}")
        }

        // Automatic fallback to Standard UDP DNS (port 53) if DoH was blocked, timed out or failed
        if (!handled && fallbackStandardIp.isNotBlank()) {
            try {
                val fallbackResp = queryStandardDns(queryBytes, fallbackStandardIp)
                if (fallbackResp != null && fallbackResp.size >= 12) {
                    cache[queryHash] = Pair(now, fallbackResp)
                    val respPacket = DatagramPacket(fallbackResp, fallbackResp.size, clientAddress, clientPort)
                    s.send(respPacket)
                    Log.d(TAG, "Resolved query via Standard DNS fallback ($fallbackStandardIp:53)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Standard DNS fallback error: ${e.message}")
            }
        }
    }

    private fun queryStandardDns(
        queryBytes: ByteArray,
        serverIp: String,
        timeoutMs: Int = 3000
    ): ByteArray? {
        var clientSocket: DatagramSocket? = null
        return try {
            clientSocket = DatagramSocket().apply {
                soTimeout = timeoutMs
            }
            val serverAddr = InetAddress.getByName(serverIp)
            val sendPacket = DatagramPacket(queryBytes, queryBytes.size, serverAddr, 53)
            clientSocket.send(sendPacket)

            val buf = ByteArray(BUFFER_SIZE)
            val recvPacket = DatagramPacket(buf, buf.size)
            clientSocket.receive(recvPacket)
            recvPacket.data.copyOfRange(recvPacket.offset, recvPacket.offset + recvPacket.length)
        } catch (e: Exception) {
            Log.w(TAG, "Standard DNS fallback UDP failed to $serverIp:53: ${e.message}")
            null
        } finally {
            try {
                clientSocket?.close()
            } catch (_: Exception) {}
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
