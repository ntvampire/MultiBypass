package com.multibypass.app.core.vpn

import android.content.Context
import android.util.Log
import com.multibypass.app.core.smarthosts.SmartHostsDnsRelay
import com.multibypass.app.core.tgproxy.TelegramDcRanges
import com.multibypass.app.core.tgproxy.TelegramTransparentBridge
import com.multibypass.app.data.repository.SettingsRepository
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальный SOCKS5-диспетчер трафика:
 * Принимает входящие соединения от hev-socks5-tunnel и выполняет прозрачную маршрутизацию:
 * - Прямые TCP-соединения к серверам Telegram (AS44907, AS62041) перехватываются и
 *   направляются в прозрачный sTGWS-мост (через локальный WebSocket к Cloudflare).
 * - Весь остальной TCP-трафик (сайты, сервисы) передаётся в ByeByeDPI (порт 1080) для обхода DPI.
 * - UDP-трафик (UDP ASSOCIATE):
 *   * DNS-запросы (порт 53) перехватываются SmartHostsDnsRelay (обход геоблока + нативный DNS оператора).
 *   * Прочий UDP-трафик передается в ByeByeDPI.
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
            val nMethods = cIn.read()
            val methods = ByteArray(nMethods)
            readFully(cIn, methods)

            // Отвечаем: Версия 5, без аутентификации (0x00)
            cOut.write(byteArrayOf(0x05, 0x00))
            cOut.flush()

            // 2. Читаем запрос команды: VER(1), CMD(1), RSV(1), ATYP(1)
            val reqVer = cIn.read()
            val reqCmd = cIn.read()
            val reqRsv = cIn.read()
            val atyp = cIn.read()

            if (reqVer != 0x05) {
                client.close()
                return@withContext
            }

            if (reqCmd == 0x03) { // UDP ASSOCIATE
                relayUdpAssociate(context, client, atyp, cIn, cOut, downstreamPort)
                return@withContext
            }

            if (reqCmd != 0x01) { // Поддерживаем только CONNECT (0x01)
                sendSocksReply(cOut, rep = 0x07) // Command not supported
                client.close()
                return@withContext
            }

            // Извлекаем адрес назначения
            var targetIp = ""
            val rawAddrBytes: ByteArray

            when (atyp) {
                0x01 -> { // IPv4
                    val ip4Bytes = ByteArray(4)
                    readFully(cIn, ip4Bytes)
                    rawAddrBytes = ip4Bytes
                    targetIp = "${ip4Bytes[0].toInt() and 0xFF}.${ip4Bytes[1].toInt() and 0xFF}.${ip4Bytes[2].toInt() and 0xFF}.${ip4Bytes[3].toInt() and 0xFF}"
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
            val isTransparentTgActive = tgConfig.enabled
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
        context: Context,
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

            // Читаем ответ downstream
            val respVer = dIn.read()
            val respRep = dIn.read()
            val respRsv = dIn.read()
            val respAtyp = dIn.read()

            when (respAtyp) {
                0x01 -> ByteArray(4).also { readFully(dIn, it) }
                0x03 -> {
                    val len = dIn.read()
                    ByteArray(len).also { readFully(dIn, it) }
                }
                0x04 -> ByteArray(16).also { readFully(dIn, it) }
                else -> ByteArray(4)
            }
            val bp1 = dIn.read().toByte()
            val bp2 = dIn.read().toByte()
            val downstreamUdpPort = ((bp1.toInt() and 0xFF) shl 8) or (bp2.toInt() and 0xFF)

            // Создаем собственный UDP-релей для локального перехвата DNS
            val relaySocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            MultiBypassVpnService.protectSocket(relaySocket)
            val myPort = relaySocket.localPort

            // Отвечаем клиенту (hev-socks5-tunnel) адресом нашего релея
            val resp = byteArrayOf(
                0x05.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
                127.toByte(), 0.toByte(), 0.toByte(), 1.toByte(),
                ((myPort shr 8) and 0xFF).toByte(),
                (myPort and 0xFF).toByte()
            )
            cOut.write(resp)
            cOut.flush()

            val running = AtomicBoolean(true)

            // Контрольный поток TCP
            Thread {
                try {
                    while (cIn.read() != -1) {}
                } catch (_: Exception) {} finally {
                    running.set(false)
                    try { client.close() } catch (_: Exception) {}
                    try { downstream.close() } catch (_: Exception) {}
                    try { relaySocket.close() } catch (_: Exception) {}
                }
            }.start()

            // Поток прокачки и перехвата UDP
            Thread {
                val buf = ByteArray(65535)
                var clientEndpoint: SocketAddress? = null
                val downstreamEndpoint = InetSocketAddress("127.0.0.1", downstreamUdpPort)

                while (running.get() && !relaySocket.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        relaySocket.receive(packet)
                        val len = packet.length
                        if (len < 10) continue

                        if (packet.port == downstreamUdpPort) {
                            // Ответ от downstream (ByeByeDPI) -> пересылаем клиенту
                            val targetClient = clientEndpoint
                            if (targetClient != null) {
                                val p = DatagramPacket(buf, len, targetClient)
                                relaySocket.send(p)
                            }
                        } else {
                            // Пакет от клиента (hev-socks5-tunnel)
                            clientEndpoint = packet.socketAddress

                            // Парсим SOCKS5 UDP заголовок: [RSV 2] [FRAG 1] [ATYP 1] [DST.ADDR var] [DST.PORT 2] [DATA var]
                            val atypVal = buf[3].toInt() and 0xFF
                            val addrLen = when (atypVal) {
                                0x01 -> 4
                                0x03 -> (buf[4].toInt() and 0xFF) + 1
                                0x04 -> 16
                                else -> 0
                            }
                            val portOffset = 4 + addrLen
                            if (portOffset + 2 <= len) {
                                val dstPort = ((buf[portOffset].toInt() and 0xFF) shl 8) or (buf[portOffset + 1].toInt() and 0xFF)
                                val dataOffset = portOffset + 2
                                val dataLen = len - dataOffset

                                if (dstPort == 443) {
                                    // Принудительно отбрасываем QUIC (UDP 443) для моментального отката YouTube/браузеров на TCP
                                    continue
                                }

                                if (dstPort == 53 && dataLen >= 12) {
                                    // Перехват DNS-запроса через SmartHosts
                                    val queryData = ByteArray(dataLen)
                                    System.arraycopy(buf, dataOffset, queryData, 0, dataLen)

                                    val dnsResp = SmartHostsDnsRelay.handleDnsQuery(context, queryData, dataLen)
                                    if (dnsResp != null) {
                                        val respBuf = ByteArray(dataOffset + dnsResp.size)
                                        System.arraycopy(buf, 0, respBuf, 0, dataOffset)
                                        System.arraycopy(dnsResp, 0, respBuf, dataOffset, dnsResp.size)

                                        val sendPacket = DatagramPacket(respBuf, respBuf.size, packet.socketAddress)
                                        relaySocket.send(sendPacket)
                                        continue
                                    }
                                }
                            }

                            // Не DNS или не перехваченный UDP -> пересылаем в ByeByeDPI
                            val p = DatagramPacket(buf, len, downstreamEndpoint)
                            relaySocket.send(p)
                        }
                    } catch (e: Exception) {
                        if (!running.get() || relaySocket.isClosed) break
                    }
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
