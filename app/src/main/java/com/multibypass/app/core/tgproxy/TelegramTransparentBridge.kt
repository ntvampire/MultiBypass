package com.multibypass.app.core.tgproxy

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Прозрачный мост Telegram (sTGWS + Direct Fast-Path):
 * 1. Проверяет доступность целевого дата-центра напрямую («боем», по логике Zapret Manager).
 *    Если ДЦ (особенно медийные DC2/DC4) не заблокирован оператором связи — направляет
 *    трафик напрямую на полной скорости сети (сотни Мбит/с) без оверхеда Cloudflare.
 * 2. Если прямой маршрут заблокирован (ТСПУ / сброс TCP) — прозрачно переводит рукопожатие
 *    и туннелирует трафик через защищённый локальный WebSocket-шлюз sTGWS.
 */
object TelegramTransparentBridge {

    private const val TAG = "TgTransparentBridge"
    private const val BUFFER_SIZE = 32768
    private const val SOCKET_TIMEOUT_MS = 30000
    private const val DIRECT_PROBE_TIMEOUT_MS = 1000
    private const val CACHE_TTL_MS = 3 * 60 * 1000L // 3 минуты кэш маршрута

    enum class RouteMode { DIRECT, STGWS }

    // Кэш решений по номерам ДЦ (dc -> Mode + Timestamp)
    private val dcRouteCache = ConcurrentHashMap<Int, Pair<RouteMode, Long>>()

    fun clearCache() {
        dcRouteCache.clear()
        Log.d(TAG, "Telegram DC route cache cleared (handover / refresh)")
    }

    suspend fun handleConnection(
        clientSocket: Socket,
        targetIp: String,
        targetPort: Int,
        localProxyPort: Int = TelegramBridgeController.LOCAL_PROXY_PORT,
        secret: String,
        fallbackDownstreamPort: Int = 1080
    ) = withContext(Dispatchers.IO) {
        var proxySocket: Socket? = null
        var directSocket: Socket? = null

        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = SOCKET_TIMEOUT_MS
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. Считываем первые 64 байта рукопожатия прямого клиента
            val clientHs = ByteArray(TelegramHandshakeTranslator.HANDSHAKE_LEN)
            if (!readFully(clientIn, clientHs)) {
                Log.w(TAG, "Failed to read 64-byte handshake from client ($targetIp:$targetPort)")
                clientSocket.close()
                return@withContext
            }

            val guessedDc = TelegramDcRanges.guessDcForIp(targetIp)

            // 2. Проверяем кэш прямого пути для данного ДЦ
            val cached = dcRouteCache[guessedDc]
            val isCacheValid = cached != null && (System.currentTimeMillis() - cached.second < CACHE_TTL_MS)
            val currentMode = if (isCacheValid) cached?.first else null

            var useDirect = (currentMode == RouteMode.DIRECT)

            // Если статус ещё не известен — пробуем прямое подключение к IP назначения («проверка боем»)
            if (currentMode == null) {
                try {
                    val dSocket = Socket()
                    dSocket.tcpNoDelay = true
                    dSocket.soTimeout = SOCKET_TIMEOUT_MS
                    dSocket.connect(InetSocketAddress(targetIp, targetPort), DIRECT_PROBE_TIMEOUT_MS)
                    directSocket = dSocket
                    useDirect = true
                    dcRouteCache[guessedDc] = Pair(RouteMode.DIRECT, System.currentTimeMillis())
                    Log.i(TAG, "Telegram DC $guessedDc ($targetIp:$targetPort) is directly ACCESSIBLE. Using high-speed direct path!")
                } catch (e: Exception) {
                    useDirect = false
                    try { directSocket?.close() } catch (_: Exception) {}
                    directSocket = null
                    dcRouteCache[guessedDc] = Pair(RouteMode.STGWS, System.currentTimeMillis())
                    Log.d(TAG, "Telegram DC $guessedDc ($targetIp:$targetPort) direct connect failed (${e.message}). Routing via sTGWS Cloudflare bridge.")
                }
            }

            // 3. Быстрый прямой путь (Direct Fast-Path)
            if (useDirect) {
                if (directSocket == null) {
                    val dSocket = Socket()
                    dSocket.tcpNoDelay = true
                    dSocket.soTimeout = SOCKET_TIMEOUT_MS
                    dSocket.connect(InetSocketAddress(targetIp, targetPort), 2500)
                    directSocket = dSocket
                }

                bridgeDirect(clientSocket, directSocket, clientHs)
                return@withContext
            }

            // 4. Обходной путь sTGWS (WebSocket-мост через Cloudflare)
            val session = TelegramHandshakeTranslator.translateHandshake(
                clientHs = clientHs,
                proxySecretHex = secret,
                guessedDc = guessedDc
            )
            if (session == null) {
                // Не является MTProto — направляем в ByeByeDPI
                Log.d(TAG, "Non-MTProto traffic to $targetIp:$targetPort, falling back to downstream SOCKS5")
                forwardFallback(clientSocket, clientHs, targetIp, targetPort, fallbackDownstreamPort)
                return@withContext
            }

            Log.i(TAG, "Bridging Telegram -> DC ${session.dc} (media=${session.isMedia}) via local sTGWS proxy :$localProxyPort")

            val pSocket = Socket()
            pSocket.tcpNoDelay = true
            pSocket.soTimeout = SOCKET_TIMEOUT_MS
            pSocket.connect(InetSocketAddress("127.0.0.1", localProxyPort), 5000)
            proxySocket = pSocket

            val proxyIn = pSocket.getInputStream()
            val proxyOut = pSocket.getOutputStream()

            // Отправляем транслированное рукопожатие в локальный прокси
            proxyOut.write(session.proxyHandshake)
            proxyOut.flush()

            // Двунаправленная прокачка данных с трансляцией потокового шифра
            val clientToProxy = launch {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isActive) {
                        val n = clientIn.read(buffer)
                        if (n <= 0) break
                        val plain = session.clientDecryptCipher.update(buffer, 0, n)
                        val proxyEnc = session.proxyEncryptCipher.update(plain)
                        proxyOut.write(proxyEnc)
                        proxyOut.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    try { pSocket.shutdownOutput() } catch (_: Exception) {}
                }
            }

            val proxyToClient = launch {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (isActive) {
                        val n = proxyIn.read(buffer)
                        if (n <= 0) break
                        val plain = session.proxyDecryptCipher.update(buffer, 0, n)
                        val clientEnc = session.clientEncryptCipher.update(plain)
                        clientOut.write(clientEnc)
                        clientOut.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    try { clientSocket.shutdownOutput() } catch (_: Exception) {}
                }
            }

            selectFirst(clientToProxy, proxyToClient)
            clientToProxy.cancel()
            proxyToClient.cancel()

        } catch (e: Exception) {
            Log.w(TAG, "Bridge connection error to $targetIp:$targetPort: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { proxySocket?.close() } catch (_: Exception) {}
            try { directSocket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun bridgeDirect(
        clientSocket: Socket,
        directSocket: Socket,
        clientHs: ByteArray
    ) = coroutineScope {
        val dOut = directSocket.getOutputStream()
        val dIn = directSocket.getInputStream()
        val cOut = clientSocket.getOutputStream()
        val cIn = clientSocket.getInputStream()

        // Передаем исходное рукопожатие прямому серверу Telegram
        dOut.write(clientHs)
        dOut.flush()

        val clientToDirect = launch {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (isActive) {
                    val n = cIn.read(buffer)
                    if (n <= 0) break
                    dOut.write(buffer, 0, n)
                    dOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { directSocket.shutdownOutput() } catch (_: Exception) {}
            }
        }

        val directToClient = launch {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (isActive) {
                    val n = dIn.read(buffer)
                    if (n <= 0) break
                    cOut.write(buffer, 0, n)
                    cOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { clientSocket.shutdownOutput() } catch (_: Exception) {}
            }
        }

        selectFirst(clientToDirect, directToClient)
        clientToDirect.cancel()
        directToClient.cancel()
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        val len = buffer.size
        while (offset < len) {
            val count = inputStream.read(buffer, offset, len - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private suspend fun selectFirst(jobA: Job, jobB: Job) {
        val completed = CompletableDeferred<Unit>()
        jobA.invokeOnCompletion { completed.complete(Unit) }
        jobB.invokeOnCompletion { completed.complete(Unit) }
        completed.await()
    }

    private fun forwardFallback(
        clientSocket: Socket,
        initialBytes: ByteArray,
        targetIp: String,
        targetPort: Int,
        downstreamPort: Int
    ) {
        try {
            val downstream = Socket()
            downstream.tcpNoDelay = true
            downstream.soTimeout = SOCKET_TIMEOUT_MS
            downstream.connect(InetSocketAddress("127.0.0.1", downstreamPort), 3000)

            val dIn = downstream.getInputStream()
            val dOut = downstream.getOutputStream()

            // SOCKS5 greeting
            dOut.write(byteArrayOf(0x05, 0x01, 0x00))
            dOut.flush()
            val greetingResp = ByteArray(2)
            dIn.read(greetingResp)

            // SOCKS5 connect IPv4
            val parts = targetIp.split(".").map { it.toInt().toByte() }
            val req = ByteArray(10)
            req[0] = 0x05
            req[1] = 0x01 // CONNECT
            req[2] = 0x00
            req[3] = 0x01 // IPv4
            req[4] = parts[0]
            req[5] = parts[1]
            req[6] = parts[2]
            req[7] = parts[3]
            req[8] = ((targetPort shr 8) and 0xFF).toByte()
            req[9] = (targetPort and 0xFF).toByte()
            dOut.write(req)
            dOut.flush()

            val resp = ByteArray(10)
            dIn.read(resp)

            // Отправляем уже вычитанные первые байты
            dOut.write(initialBytes)
            dOut.flush()

            val cIn = clientSocket.getInputStream()
            val cOut = clientSocket.getOutputStream()

            Thread {
                try {
                    cIn.copyTo(dOut)
                } catch (_: Exception) {}
            }.start()

            Thread {
                try {
                    dIn.copyTo(cOut)
                } catch (_: Exception) {}
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "Fallback forward error: ${e.message}")
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }
}
