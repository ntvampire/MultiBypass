package com.multibypass.app.core.vpn

import android.content.Context
import android.util.Log
import com.multibypass.app.core.smarthosts.SmartHostsDnsRelay
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальный SOCKS5-диспетчер трафика:
 * Принимает входящие соединения от hev-socks5-tunnel и выполняет маршрутизацию:
 * - Все TCP-соединения передаются в ByeByeDPI (порт 1080) для обхода DPI.
 * - UDP-трафик (UDP ASSOCIATE):
 *   * DNS-запросы (порт 53) перехватываются SmartHostsDnsRelay (обход геоблока + нативный DNS оператора).
 *   * Прочий UDP-трафик передается напрямую через защищенный сокет.
 */
object Socks5Dispatcher {

    private const val TAG = "Socks5Dispatcher"
    const val DEFAULT_PORT = 10800
    private const val SOCKET_TIMEOUT_MS = 30000

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Реестр открытых клиентских и нисходящих сокетов для мгновенного сброса при смене сети (Wi-Fi <-> LTE)
    private val activeSockets = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    fun resetActiveConnections() {
        val list = activeSockets.toList()
        Log.i(TAG, "Resetting ${list.size} active connections on network handover")
        for (s in list) {
            try { s.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
    }

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
                        client.soTimeout = SOCKET_TIMEOUT_MS
                        activeSockets.add(client)
                        launch {
                            try {
                                handleClient(context, client, downstreamPort)
                            } finally {
                                activeSockets.remove(client)
                                try { client.close() } catch (_: Exception) {}
                            }
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
            resetActiveConnections()
            Log.i(TAG, "Socks5Dispatcher stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Socks5Dispatcher", e)
        }
    }

    private suspend fun handleClient(context: Context, client: Socket, downstreamPort: Int) = withContext(Dispatchers.IO) {
        try {
            val cIn = client.getInputStream()
            val cOut = client.getOutputStream()

            // 1. Рукопожатие SOCKS5: считываем поддерживаемые методы аутентификации
            val ver = cIn.read()
            if (ver != 0x05) {
                client.close()
                return@withContext
            }

            val nmethods = cIn.read()
            if (nmethods <= 0) {
                client.close()
                return@withContext
            }

            val methods = ByteArray(nmethods)
            readFully(cIn, methods)

            // Отвечаем: 0x05 (SOCKS5), 0x00 (NO AUTHENTICATION REQUIRED)
            cOut.write(byteArrayOf(0x05, 0x00))
            cOut.flush()

            // 2. Обработка запроса клиента (COMMAND REQUEST)
            val reqVer = cIn.read()
            val reqCmd = cIn.read()
            val reqRsv = cIn.read()
            val atyp = cIn.read()

            if (reqVer != 0x05) {
                client.close()
                return@withContext
            }

            // Обработка UDP ASSOCIATE (0x03)
            if (reqCmd == 0x03) {
                relayUdpAssociate(context, client, atyp, cIn, cOut)
                return@withContext
            }

            if (reqCmd != 0x01) { // Поддерживаем только CONNECT (0x01)
                sendSocksReply(cOut, rep = 0x07) // Command not supported
                client.close()
                return@withContext
            }

            // Считываем адрес назначения
            val rawAddrBytes: ByteArray
            when (atyp) {
                0x01 -> { // IPv4 (4 байта)
                    val ip4Bytes = ByteArray(4)
                    readFully(cIn, ip4Bytes)
                    rawAddrBytes = ip4Bytes
                }
                0x03 -> { // Domain name (1 байт длина + имя)
                    val domainLen = cIn.read()
                    val domainBytes = ByteArray(domainLen)
                    readFully(cIn, domainBytes)
                    rawAddrBytes = byteArrayOf(domainLen.toByte()) + domainBytes
                }
                0x04 -> { // IPv6
                    val ip6Bytes = ByteArray(16)
                    readFully(cIn, ip6Bytes)
                    rawAddrBytes = ip6Bytes
                }
                else -> {
                    sendSocksReply(cOut, rep = 0x08) // Address type not supported
                    client.close()
                    return@withContext
                }
            }

            val p1 = cIn.read()
            val p2 = cIn.read()

            // Все TCP-соединения передаются в downstream (ByeByeDPI)
            forwardToDownstream(client, atyp, rawAddrBytes, p1.toByte(), p2.toByte(), downstreamPort)

        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun relayUdpAssociate(
        context: Context,
        client: Socket,
        atyp: Int,
        cIn: InputStream,
        cOut: OutputStream
    ) {
        var localUdpSocket: DatagramSocket? = null
        try {
            // Считываем заявленный клиентом адрес и порт
            when (atyp) {
                0x01 -> {
                    val ip4 = ByteArray(4)
                    readFully(cIn, ip4)
                }
                0x03 -> {
                    val len = cIn.read()
                    val dom = ByteArray(len)
                    readFully(cIn, dom)
                }
                0x04 -> {
                    val ip6 = ByteArray(16)
                    readFully(cIn, ip6)
                }
            }
            cIn.read() // p1
            cIn.read() // p2

            // Открываем локальный UDP сокет для приёма инкапсулированных пакетов
            localUdpSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val boundPort = localUdpSocket.localPort

            // Отправляем SUCCESS ответ c локальным UDP портом
            sendSocksReply(cOut, rep = 0x00, bndPort = boundPort)

            // Запускаем ретранслятор UDP-пакетов
            val udpRelayThread = Thread {
                val recvBuf = ByteArray(65535)
                var clientUdpAddr: SocketAddress? = null

                try {
                    while (isRunning.get() && !localUdpSocket.isClosed) {
                        val packet = DatagramPacket(recvBuf, recvBuf.size)
                        localUdpSocket.receive(packet)

                        if (clientUdpAddr == null) {
                            clientUdpAddr = packet.socketAddress
                        }

                        val data = packet.data
                        val len = packet.length

                        // Разбираем заголовок SOCKS5 UDP Request
                        // RSV (2B), FRAG (1B), ATYP (1B), DST.ADDR, DST.PORT (2B)
                        if (len < 10 || data[2] != 0x00.toByte()) {
                            continue
                        }

                        val uAtyp = data[3].toInt() and 0xFF
                        var headerOffset = 4
                        val destIp: String

                        when (uAtyp) {
                            0x01 -> { // IPv4
                                destIp = "${data[headerOffset].toUByte()}.${data[headerOffset+1].toUByte()}.${data[headerOffset+2].toUByte()}.${data[headerOffset+3].toUByte()}"
                                headerOffset += 4
                            }
                            0x03 -> { // Domain
                                val domLen = data[headerOffset].toInt() and 0xFF
                                headerOffset += 1
                                destIp = String(data, headerOffset, domLen, Charsets.US_ASCII)
                                headerOffset += domLen
                            }
                            0x04 -> { // IPv6
                                destIp = "ipv6"
                                headerOffset += 16
                            }
                            else -> continue
                        }

                        val uPort = ((data[headerOffset].toInt() and 0xFF) shl 8) or (data[headerOffset + 1].toInt() and 0xFF)
                        headerOffset += 2

                        val payload = data.copyOfRange(headerOffset, len)

                        // 1. DNS-запросы (UDP 53): перехватываем SmartHosts
                        if (uPort == 53) {
                            val dnsResponse = SmartHostsDnsRelay.handleDnsQuery(context, payload, payload.size)
                            if (dnsResponse != null && clientUdpAddr != null) {
                                val replyHeader = data.copyOfRange(0, headerOffset)
                                val fullReply = replyHeader + dnsResponse
                                val respPacket = DatagramPacket(fullReply, fullReply.size, clientUdpAddr)
                                localUdpSocket.send(respPacket)
                            }
                            continue
                        }

                        // 2. Обычный UDP-трафик: передаем напрямую через защищенный сокет
                        try {
                            val directUdp = DatagramSocket()
                            MultiBypassVpnService.protectSocket(directUdp)
                            directUdp.soTimeout = 4000
                            val targetAddress = InetAddress.getByName(destIp)
                            val outPacket = DatagramPacket(payload, payload.size, targetAddress, uPort)
                            directUdp.send(outPacket)

                            val directRecv = ByteArray(65535)
                            val inPacket = DatagramPacket(directRecv, directRecv.size)
                            directUdp.receive(inPacket)

                            if (clientUdpAddr != null) {
                                val replyHeader = data.copyOfRange(0, headerOffset)
                                val responsePayload = inPacket.data.copyOfRange(0, inPacket.length)
                                val fullReply = replyHeader + responsePayload
                                val clientReply = DatagramPacket(fullReply, fullReply.size, clientUdpAddr)
                                localUdpSocket.send(clientReply)
                            }
                            directUdp.close()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                } finally {
                    try { localUdpSocket.close() } catch (_: Exception) {}
                }
            }
            udpRelayThread.isDaemon = true
            udpRelayThread.start()

            // Удерживаем TCP-соединение, пока клиент не закроет сокет
            val dummy = ByteArray(128)
            while (isRunning.get()) {
                val n = cIn.read(dummy)
                if (n < 0) break
            }
            localUdpSocket.close()

        } catch (e: Exception) {
            Log.w(TAG, "UDP ASSOCIATE relay error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
            try { localUdpSocket?.close() } catch (_: Exception) {}
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
            val dSocket = Socket()
            dSocket.tcpNoDelay = true
            dSocket.soTimeout = SOCKET_TIMEOUT_MS
            client.soTimeout = SOCKET_TIMEOUT_MS
            dSocket.connect(InetSocketAddress("127.0.0.1", downstreamPort), 3000)
            downstream = dSocket
            activeSockets.add(dSocket)

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

            val cIn = client.getInputStream()
            val cOut = client.getOutputStream()

            // Двунаправленная прокачка данных
            val t1 = Thread {
                val buf = ByteArray(16384)
                try {
                    while (true) {
                        val n = cIn.read(buf)
                        if (n <= 0) break
                        dOut.write(buf, 0, n)
                        dOut.flush()
                    }
                } catch (_: Exception) {} finally {
                    try { dSocket.shutdownOutput() } catch (_: Exception) {}
                }
            }

            val t2 = Thread {
                val buf = ByteArray(16384)
                try {
                    while (true) {
                        val n = dIn.read(buf)
                        if (n <= 0) break
                        cOut.write(buf, 0, n)
                        cOut.flush()
                    }
                } catch (_: Exception) {} finally {
                    try { client.shutdownOutput() } catch (_: Exception) {}
                }
            }

            t1.start()
            t2.start()

            t1.join()
            t2.join()

        } catch (e: Exception) {
            // Игнорируем штатные разрывы соединений
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { downstream?.close() } catch (_: Exception) {}
            downstream?.let { activeSockets.remove(it) }
        }
    }

    private fun sendSocksReply(out: OutputStream, rep: Byte, bndPort: Int = 0) {
        val reply = byteArrayOf(
            0x05, rep, 0x00, 0x01,
            0x7F, 0x00, 0x00, 0x01,
            ((bndPort shr 8) and 0xFF).toByte(),
            (bndPort and 0xFF).toByte()
        )
        out.write(reply)
        out.flush()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw java.io.EOFException("Unexpected end of stream")
            offset += count
        }
    }
}
