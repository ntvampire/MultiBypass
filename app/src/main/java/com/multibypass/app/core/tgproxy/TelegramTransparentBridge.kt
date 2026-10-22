package com.multibypass.app.core.tgproxy

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Прозрачный мост (sTGWS Bridge):
 * Принимает прямое соединение от Telegram клиента, переводит его рукопожатие
 * и прозрачно передаёт данные в локальный сервис туннелирования.
 */
object TelegramTransparentBridge {

    private const val TAG = "TgTransparentBridge"
    private const val BUFFER_SIZE = 16384

    suspend fun handleConnection(
        clientSocket: Socket,
        targetIp: String,
        targetPort: Int,
        localProxyPort: Int = 1443,
        secret: String,
        fallbackDownstreamPort: Int = 1080
    ) = withContext(Dispatchers.IO) {
        var proxySocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. Считываем первые 64 байта рукопожатия прямого клиента
            val clientHs = ByteArray(TelegramHandshakeTranslator.HANDSHAKE_LEN)
            if (!readFully(clientIn, clientHs)) {
                Log.w(TAG, "Failed to read 64-byte handshake from client ($targetIp:$targetPort)")
                clientSocket.close()
                return@withContext
            }

            // 2. Транслируем рукопожатие
            val session = TelegramHandshakeTranslator.translateHandshake(clientHs, secret)
            if (session == null) {
                // Если входящий пакет не является MTProto (например, сторонний HTTP probe),
                // прозрачно перенаправляем его на ByeByeDPI
                Log.d(TAG, "Non-MTProto traffic to $targetIp:$targetPort, falling back to downstream SOCKS5")
                forwardFallback(clientSocket, clientHs, targetIp, targetPort, fallbackDownstreamPort)
                return@withContext
            }

            Log.i(TAG, "Bridging Telegram -> DC ${session.dc} (media=${session.isMedia}, proto=0x${Integer.toHexString(session.protoTag)}) via local proxy :$localProxyPort")

            // 3. Подключаемся к локальному WS-прокси
            val pSocket = Socket()
            pSocket.tcpNoDelay = true
            pSocket.connect(InetSocketAddress("127.0.0.1", localProxyPort), 5000)
            proxySocket = pSocket

            val proxyIn = pSocket.getInputStream()
            val proxyOut = pSocket.getOutputStream()

            // 4. Отправляем транслированное рукопожатие в локальный прокси
            proxyOut.write(session.proxyHandshake)
            proxyOut.flush()

            // 5. Двунаправленная прокачка данных с трансляцией потокового шифра
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

            // Ожидаем завершения передачи в любую из сторон
            selectFirst(clientToProxy, proxyToClient)
            clientToProxy.cancel()
            proxyToClient.cancel()

        } catch (e: Exception) {
            Log.w(TAG, "Bridge connection error to $targetIp:$targetPort", e)
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { proxySocket?.close() } catch (_: Exception) {}
        }
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
        // Fallback: подключаемся к ByeByeDPI по SOCKS5
        try {
            val downstream = Socket("127.0.0.1", downstreamPort)
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

            // Прямая пересылка
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
            Log.w(TAG, "Fallback forward error", e)
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }
}
