package com.multibypass.app.core.vpn

import android.content.Context
import android.util.Log
import com.multibypass.app.core.tgproxy.TelegramDcRanges
import com.multibypass.app.core.tgproxy.TelegramTransparentBridge
import com.multibypass.app.data.model.TelegramMode
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальный SOCKS5-диспетчер трафика:
 * Принимает входящие соединения от hev-socks5-tunnel и выполняет прозрачную маршрутизацию:
 * - Прямые TCP-соединения к серверам Telegram (AS44907, AS62041) перехватываются и
 *   направляются в прозрачный sTGWS-мост (через локальный WebSocket к Cloudflare).
 * - Весь остальной TCP-трафик (сайты, сервисы) передаётся в ByeByeDPI (порт 1080) для обхода DPI.
 * - UDP-трафик (UDP ASSOCIATE для звонков и DNS) напрямую транслируется в ByeByeDPI.
 */
object Socks5Dispatcher {

    private const val TAG = "Socks5Dispatcher"
    const val DEFAULT_PORT = 10800

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Synchronized
    fun start(context: Context, listenPort: Int = DEFAULT_PORT, downstreamPort: Int = 1080) {
        if (isRunning.get()) {
            Log.d(TAG, "Socks5Dispatcher already running on port $listenPort")
            return
        }

        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", listenPort), 128)
            serverSocket = server
            isRunning.set(true)

            Log.i(TAG, "Socks5Dispatcher started on 127.0.0.1:$listenPort -> downstream 127.0.0.1:$downstreamPort")

            serverJob = scope.launch {
                while (isActive && isRunning.get()) {
                    try {
                        val client = server.accept()
                        client.tcpNoDelay = true
                        launch {
                            handleClient(context, client, downstreamPort)
                        }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.w(TAG, "Error accepting connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Socks5Dispatcher on port $listenPort", e)
            isRunning.set(false)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            serverJob?.cancel()
            serverJob = null
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "Socks5Dispatcher stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Socks5Dispatcher", e)
        }
    }

    fun isRunning(): Boolean = isRunning.get()

    private suspend fun handleClient(context: Context, client: Socket, downstreamPort: Int) = withContext(Dispatchers.IO) {
        try {
            val cIn = client.getInputStream()
            val cOut = client.getOutputStream()

            // 1. SOCKS5 согласование методов
            val ver = cIn.read()
            if (ver != 0x05) {
                client.close()
                return@withContext
            }
            val nMethods = cIn.read()
            if (nMethods <= 0) {
                client.close()
                return@withContext
            }
            val methods = ByteArray(nMethods)
            readFully(cIn, methods)

            // Отвечаем: 0x05, 0x00 (NO AUTH REQUIRED)
            cOut.write(byteArrayOf(0x05, 0x00))
            cOut.flush()

            // 2. SOCKS5 запрос
            val reqVer = cIn.read()
            val cmd = cIn.read()
            val rsv = cIn.read()
            val atyp = cIn.read()

            if (reqVer != 0x05) {
                client.close()
                return@withContext
            }

            // Проверяем UDP ASSOCIATE (CMD = 0x03)
            if (cmd == 0x03) {
                relayUdpAssociate(client, atyp, cIn, cOut, downstreamPort)
                return@withContext
            }

            if (cmd != 0x01) { // Если не CONNECT
                sendSocksReply(cOut, rep = 0x07) // Command not supported
                client.close()
                return@withContext
            }

            // Читаем адрес назначения
            val targetIp: String
            val rawAddrBytes: ByteArray
            when (atyp) {
                0x01 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    readFully(cIn, ipBytes)
                    rawAddrBytes = ipBytes
                    targetIp = "${ipBytes[0].toInt() and 0xFF}.${ipBytes[1].toInt() and 0xFF}.${ipBytes[2].toInt() and 0xFF}.${ipBytes[3].toInt() and 0xFF}"
                }
                0x03 -> { // Domain name
                    val len = cIn.read()
                    val domainBytes = ByteArray(len)
                    readFully(cIn, domainBytes)
                    rawAddrBytes = byteArrayOf(len.toByte()) + domainBytes
                    targetIp = String(domainBytes)
                }
                0x04 -> { // IPv6
                    val ip6Bytes = ByteArray(16)
                    readFully(cIn, ip6Bytes)
                    rawAddrBytes = ip6Bytes
                    targetIp = "ipv6"
                }
                else -> {
                    sendSocksReply(cOut, rep = 0x08) // Address type not supported
                    client.close()
                    return@withContext
                }
            }

            val p1 = cIn.read()
            val p2 = cIn.read()
            val targetPort = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)

            // 3. Классификация маршрута: Telegram или ByeByeDPI
            val repository = SettingsRepository.getInstance(context)
            val tgConfig = repository.tgConfig.value
            val isTransparentTgActive = tgConfig.enabled && tgConfig.mode == TelegramMode.TRANSPARENT
            val isTgTarget = (atyp == 0x01) && TelegramDcRanges.isTelegramTarget(targetIp, targetPort)

            if (isTransparentTgActive && isTgTarget) {
                // Прямой запрос к дата-центрам Telegram -> подтверждаем CONNECT и передаём в sTGWS мост
                sendSocksReply(cOut, rep = 0x00, bndPort = 1443)
                TelegramTransparentBridge.handleConnection(
                    clientSocket = client,
                    targetIp = targetIp,
                    targetPort = targetPort,
                    localProxyPort = 1443,
                    secret = tgConfig.secret,
                    fallbackDownstreamPort = downstreamPort
                )
            } else {
                // Обычный трафик -> передаём в ByeByeDPI
                forwardToDownstream(client, atyp, rawAddrBytes, p1.toByte(), p2.toByte(), downstreamPort)
            }

        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun relayUdpAssociate(
        client: Socket,
        atyp: Int,
        cIn: InputStream,
        cOut: OutputStream,
        downstreamPort: Int
    ) {
        try {
            val downstream = Socket("127.0.0.1", downstreamPort)
            downstream.tcpNoDelay = true
            val dIn = downstream.getInputStream()
            val dOut = downstream.getOutputStream()

            // Рукопожатие с downstream
            dOut.write(byteArrayOf(0x05, 0x01, 0x00))
            dOut.flush()
            val greetingResp = ByteArray(2)
            readFully(dIn, greetingResp)

            // Читаем параметры UDP ASSOCIATE от клиента
            val addrBytes: ByteArray = when (atyp) {
                0x01 -> ByteArray(4).also { readFully(cIn, it) }
                0x03 -> {
                    val len = cIn.read()
                    val b = ByteArray(len)
                    readFully(cIn, b)
                    byteArrayOf(len.toByte()) + b
                }
                0x04 -> ByteArray(16).also { readFully(cIn, it) }
                else -> ByteArray(0)
            }
            val p1 = cIn.read().toByte()
            val p2 = cIn.read().toByte()

            // Отправляем UDP ASSOCIATE в downstream
            val req = byteArrayOf(0x05, 0x03, 0x00, atyp.toByte()) + addrBytes + byteArrayOf(p1, p2)
            dOut.write(req)
            dOut.flush()

            // Читаем ответ downstream и пересылаем клиенту
            val respVer = dIn.read()
            val respRep = dIn.read()
            val respRsv = dIn.read()
            val respAtyp = dIn.read()

            val bndAddrBytes = when (respAtyp) {
                0x01 -> ByteArray(4).also { readFully(dIn, it) }
                0x03 -> {
                    val len = dIn.read()
                    val b = ByteArray(len)
                    readFully(dIn, b)
                    byteArrayOf(len.toByte()) + b
                }
                0x04 -> ByteArray(16).also { readFully(dIn, it) }
                else -> ByteArray(4)
            }
            val bp1 = dIn.read().toByte()
            val bp2 = dIn.read().toByte()

            val resp = byteArrayOf(respVer.toByte(), respRep.toByte(), respRsv.toByte(), respAtyp.toByte()) +
                    bndAddrBytes + byteArrayOf(bp1, bp2)
            cOut.write(resp)
            cOut.flush()

            // Удерживаем TCP-канал контроля активным
            Thread {
                try {
                    while (cIn.read() != -1) {}
                } catch (_: Exception) {} finally {
                    try { client.close() } catch (_: Exception) {}
                    try { downstream.close() } catch (_: Exception) {}
                }
            }.start()

        } catch (e: Exception) {
            Log.w(TAG, "UDP ASSOCIATE relay error", e)
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun forwardToDownstream(
        client: Socket,
        atyp: Int,
        rawAddrBytes: ByteArray,
        p1: Byte,
        p2: Byte,
        downstreamPort: Int
    ) {
        var downstream: Socket? = null
        try {
            val dSocket = Socket("127.0.0.1", downstreamPort)
            dSocket.tcpNoDelay = true
            downstream = dSocket

            val dIn = dSocket.getInputStream()
            val dOut = dSocket.getOutputStream()

            // Рукопожатие с ByeByeDPI
            dOut.write(byteArrayOf(0x05, 0x01, 0x00))
            dOut.flush()
            val greeting = ByteArray(2)
            readFully(dIn, greeting)

            // Передаем CONNECT запрос
            val req = byteArrayOf(0x05, 0x01, 0x00, atyp.toByte()) + rawAddrBytes + byteArrayOf(p1, p2)
            dOut.write(req)
            dOut.flush()

            // Читаем ответ ByeByeDPI
            val respVer = dIn.read()
            val respRep = dIn.read()
            val respRsv = dIn.read()
            val respAtyp = dIn.read()

            val bndAddrBytes = when (respAtyp) {
                0x01 -> ByteArray(4).also { readFully(dIn, it) }
                0x03 -> {
                    val len = dIn.read()
                    val b = ByteArray(len)
                    readFully(dIn, b)
                    byteArrayOf(len.toByte()) + b
                }
                0x04 -> ByteArray(16).also { readFully(dIn, it) }
                else -> ByteArray(4)
            }
            val bp1 = dIn.read().toByte()
            val bp2 = dIn.read().toByte()

            val cOut = client.getOutputStream()
            val resp = byteArrayOf(respVer.toByte(), respRep.toByte(), respRsv.toByte(), respAtyp.toByte()) +
                    bndAddrBytes + byteArrayOf(bp1, bp2)
            cOut.write(resp)
            cOut.flush()

            val cIn = client.getInputStream()

            // Двунаправленный конвейер
            val t1 = Thread {
                try {
                    cIn.copyTo(dOut)
                } catch (_: Exception) {} finally {
                    try { dSocket.shutdownOutput() } catch (_: Exception) {}
                }
            }
            val t2 = Thread {
                try {
                    dIn.copyTo(cOut)
                } catch (_: Exception) {} finally {
                    try { client.shutdownOutput() } catch (_: Exception) {}
                }
            }
            t1.start()
            t2.start()

        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
            try { downstream?.close() } catch (_: Exception) {}
        }
    }

    private fun sendSocksReply(out: OutputStream, rep: Byte, bndPort: Int = 0) {
        val p1 = ((bndPort shr 8) and 0xFF).toByte()
        val p2 = (bndPort and 0xFF).toByte()
        out.write(byteArrayOf(0x05, rep, 0x00, 0x01, 0x7F, 0x00, 0x00, 0x01, p1, p2))
        out.flush()
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray) {
        var offset = 0
        val len = buffer.size
        while (offset < len) {
            val count = inputStream.read(buffer, offset, len - offset)
            if (count < 0) throw java.io.EOFException("Premature EOF")
            offset += count
        }
    }
}
