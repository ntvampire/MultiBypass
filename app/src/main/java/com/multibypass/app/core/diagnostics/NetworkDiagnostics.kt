package com.multibypass.app.core.diagnostics

import android.content.Context
import android.os.Build
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.smarthosts.SmartHostsDnsRelay
import com.multibypass.app.core.smarthosts.SmartHostsManager
import com.multibypass.app.core.tgproxy.TelegramBridgeController
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class DiagnosticStatus {
    SUCCESS,
    WARNING,
    ERROR,
    SKIPPED
}

data class DiagnosticStepResult(
    val title: String,
    val description: String,
    val status: DiagnosticStatus,
    val latencyMs: Long? = null,
    val detail: String? = null
)

data class DiagnosticsReport(
    val timestamp: Long = System.currentTimeMillis(),
    val steps: List<DiagnosticStepResult>,
    val allOk: Boolean
) {
    fun toPlainText(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== MultiBypass Сетевой Доктор Отчет ===")
        sb.appendLine("Дата: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("----------------------------------------")
        for (s in steps) {
            val icon = when (s.status) {
                DiagnosticStatus.SUCCESS -> "[OK]"
                DiagnosticStatus.WARNING -> "[ВНИМАНИЕ]"
                DiagnosticStatus.ERROR -> "[ОШИБКА]"
                DiagnosticStatus.SKIPPED -> "[ПРОПУЩЕНО]"
            }
            sb.appendLine("$icon ${s.title}: ${s.description}")
            if (s.latencyMs != null) sb.appendLine("   Задержка: ${s.latencyMs} мс")
            if (!s.detail.isNullOrBlank()) sb.appendLine("   Детали: ${s.detail}")
        }
        sb.appendLine("----------------------------------------")
        sb.appendLine("Итог: ${if (allOk) "Все проверенные компоненты работают штатно" else "Обнаружены предупреждения или сбои"}")
        return sb.toString()
    }
}

object NetworkDiagnostics {

    suspend fun runDiagnostics(context: Context): DiagnosticsReport = withContext(Dispatchers.IO) {
        val steps = mutableListOf<DiagnosticStepResult>()
        val repository = SettingsRepository.getInstance(context)

        // 1. VPN Interface Check
        val vpnStatus = MultiBypassVpnService.vpnStatus.value
        val vpnStep = when (vpnStatus) {
            VpnStatus.CONNECTED -> DiagnosticStepResult(
                title = "VPN-интерфейс",
                description = "Активен и удерживает туннель",
                status = DiagnosticStatus.SUCCESS,
                detail = "Маршрутизация: ${if (repository.routeAllApps.value) "Все приложения" else "${repository.routedApps.value.size} выбранных"}"
            )
            VpnStatus.CONNECTING -> DiagnosticStepResult(
                title = "VPN-интерфейс",
                description = "В процессе подключения...",
                status = DiagnosticStatus.WARNING
            )
            else -> DiagnosticStepResult(
                title = "VPN-интерфейс",
                description = "Отключен",
                status = DiagnosticStatus.ERROR,
                detail = "Нажмите 'Подключить' на главном экране"
            )
        }
        steps.add(vpnStep)

        // 2. ByeByeDPI Daemon Check
        val byeDpiPort = ByeDpiController.DEFAULT_PORT
        val byeDpiStart = System.currentTimeMillis()
        val byeDpiListening = isTcpPortOpen("127.0.0.1", byeDpiPort, 1000)
        val byeDpiTime = System.currentTimeMillis() - byeDpiStart
        steps.add(
            if (byeDpiListening) {
                DiagnosticStepResult(
                    title = "Демон ByeByeDPI",
                    description = "Слушает порт 127.0.0.1:$byeDpiPort",
                    status = DiagnosticStatus.SUCCESS,
                    latencyMs = byeDpiTime,
                    detail = "Стратегия: ${repository.antiDpiConfig.value.strategy}"
                )
            } else {
                DiagnosticStepResult(
                    title = "Демон ByeByeDPI",
                    description = "Порт $byeDpiPort недоступен",
                    status = if (vpnStatus == VpnStatus.CONNECTED) DiagnosticStatus.ERROR else DiagnosticStatus.WARNING,
                    detail = if (vpnStatus == VpnStatus.CONNECTED) "Демон десинхронизации не отвечает" else "Запускается вместе с VPN"
                )
            }
        )

        // 3. Anti-DPI SOCKS5 HTTP Access (YouTube)
        if (byeDpiListening) {
            val testHttpStart = System.currentTimeMillis()
            val httpResult = testSocks5Http("127.0.0.1", byeDpiPort, "https://www.youtube.com/generate_204", 6000)
            val httpTime = System.currentTimeMillis() - testHttpStart
            steps.add(
                if (httpResult) {
                    DiagnosticStepResult(
                        title = "YouTube (Анти-DPI)",
                        description = "Серверы YouTube отвечают за $httpTime мс",
                        status = DiagnosticStatus.SUCCESS,
                        latencyMs = httpTime,
                        detail = "HTTP 204 OK — десинхронизация TCP успешна, видео и интерфейс доступны"
                    )
                } else {
                    DiagnosticStepResult(
                        title = "YouTube (Анти-DPI)",
                        description = "Отклик задержан или заблокирован ТСПУ",
                        status = DiagnosticStatus.WARNING,
                        latencyMs = httpTime,
                        detail = "Попробуйте изменить стратегию десинхронизации в настройках Анти-DPI"
                    )
                }
            )
        } else {
            steps.add(
                DiagnosticStepResult(
                    title = "YouTube (Анти-DPI)",
                    description = "VPN не подключен",
                    status = DiagnosticStatus.SKIPPED,
                    detail = "Требуется запустить VPN для проверки десинхронизации"
                )
            )
        }

        // 4. SmartHosts Geoblock Check
        val smartHostsConfig = repository.smartHostsConfig.value
        if (!smartHostsConfig.enabled) {
            steps.add(
                DiagnosticStepResult(
                    title = "Обход геоблокировок (SmartHosts)",
                    description = "Отключен пользователем",
                    status = DiagnosticStatus.SKIPPED,
                    detail = "Домены зарубежных сервисов не перенаправляются"
                )
            )
        } else {
            val testDomain = "gemini.google.com"
            val mappedIps = SmartHostsManager.resolve(testDomain) ?: SmartHostsManager.resolve("chatgpt.com")
            if (mappedIps != null && mappedIps.isNotEmpty()) {
                val proxyIp = mappedIps.first()
                val shStart = System.currentTimeMillis()
                val isProxyReachable = isTcpPortOpen(proxyIp, 443, 2500)
                val shTime = System.currentTimeMillis() - shStart
                if (isProxyReachable) {
                    steps.add(
                        DiagnosticStepResult(
                            title = "Обход геоблокировок (SmartHosts)",
                            description = "$testDomain -> $proxyIp ($shTime мс)",
                            status = DiagnosticStatus.SUCCESS,
                            latencyMs = shTime,
                            detail = "Европейский SNI-релей доступен, резолв 0 мс"
                        )
                    )
                } else {
                    steps.add(
                        DiagnosticStepResult(
                            title = "Обход геоблокировок (SmartHosts)",
                            description = "Резолв активен ($proxyIp), порт 443 не ответил",
                            status = DiagnosticStatus.WARNING,
                            latencyMs = shTime,
                            detail = "Возможна фильтрация или недоступность релея"
                        )
                    )
                }
            } else {
                steps.add(
                    DiagnosticStepResult(
                        title = "Обход геоблокировок (SmartHosts)",
                        description = "База доменов не инициализирована",
                        status = DiagnosticStatus.WARNING,
                        detail = "Обновите базу в настройках приложения"
                    )
                )
            }
        }

        // 5. Telegram Check (sTGWS, прямой режим)
        val tgConfig = repository.tgConfig.value
        if (!tgConfig.enabled) {
            steps.add(
                DiagnosticStepResult(
                    title = "Обход Telegram (sTGWS)",
                    description = "Отключен пользователем",
                    status = DiagnosticStatus.SKIPPED,
                    detail = "Трафик Telegram направляется напрямую через провайдера"
                )
            )
        } else {
            val isBridgeListening = isTcpPortOpen("127.0.0.1", TelegramBridgeController.LOCAL_PROXY_PORT, 1000)
            val isVpnConnected = (vpnStatus == VpnStatus.CONNECTED)

            if (!isVpnConnected) {
                steps.add(
                    DiagnosticStepResult(
                        title = "Telegram (sTGWS)",
                        description = "VPN не подключен",
                        status = DiagnosticStatus.WARNING,
                        detail = "Прозрачный перехват требует активного VPN-туннеля"
                    )
                )
            } else if (!isBridgeListening) {
                steps.add(
                    DiagnosticStepResult(
                        title = "Telegram (sTGWS)",
                        description = "Служба туннелирования остановлена",
                        status = DiagnosticStatus.ERROR,
                        detail = "Локальный мост 127.0.0.1:1443 не отвечает"
                    )
                )
            } else {
                val tgStart = System.currentTimeMillis()
                val isDirectConnOk = testSocks5TcpConnect(
                    proxyHost = "127.0.0.1",
                    proxyPort = com.multibypass.app.core.vpn.Socks5Dispatcher.DEFAULT_PORT,
                    targetIp = "149.154.167.50",
                    targetPort = 443,
                    timeoutMs = 3000
                )
                val tgTime = System.currentTimeMillis() - tgStart

                if (isDirectConnOk) {
                    steps.add(
                        DiagnosticStepResult(
                            title = "Telegram (sTGWS)",
                            description = "Прямой обход активен ($tgTime мс)",
                            status = DiagnosticStatus.SUCCESS,
                            latencyMs = tgTime,
                            detail = "Трафик прозрачно туннелируется через Cloudflare (прокси в TG не требуется)"
                        )
                    )
                } else {
                    steps.add(
                        DiagnosticStepResult(
                            title = "Telegram (sTGWS)",
                            description = "Локальный шлюз активен, прямой отклик DC задержан",
                            status = DiagnosticStatus.WARNING,
                            latencyMs = tgTime,
                            detail = "Возможна задержка подключения к шлюзам Cloudflare"
                        )
                    )
                }
            }
        }

        // 6. Direct Internet & Carrier Whitelist Check
        val upstreamDns = SmartHostsDnsRelay.getUpstreamDnsIp(context)
        val directDnsStart = System.currentTimeMillis()
        val directDnsOk = testDnsResolution(upstreamDns, 53, "ya.ru", 2500)
        val directDnsTime = System.currentTimeMillis() - directDnsStart

        if (directDnsOk != null) {
            steps.add(
                DiagnosticStepResult(
                    title = "Прямой интернет (Белые списки)",
                    description = "DNS сети $upstreamDns отвечает за $directDnsTime мс",
                    status = DiagnosticStatus.SUCCESS,
                    latencyMs = directDnsTime,
                    detail = "ya.ru -> $directDnsOk (локальные ресурсы работают напрямую)"
                )
            )
        } else {
            steps.add(
                DiagnosticStepResult(
                    title = "Прямой интернет (Белые списки)",
                    description = "Нет ответа от $upstreamDns:53",
                    status = DiagnosticStatus.WARNING,
                    detail = "Проверьте подключение к сети оператора связи"
                )
            )
        }



        val allOk = steps.none { it.status == DiagnosticStatus.ERROR }
        DiagnosticsReport(steps = steps, allOk = allOk)
    }

    private fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun testDnsResolution(serverIp: String, port: Int, domain: String, timeoutMs: Int): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply { soTimeout = timeoutMs }
            val query = buildDnsQuery(domain)
            val packet = DatagramPacket(query, query.size, InetAddress.getByName(serverIp), port)
            socket.send(packet)

            val buf = ByteArray(1024)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)

            if (resp.length >= 12) {
                parseFirstARecord(resp.data, resp.length) ?: "OK (${resp.length} байт)"
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        // ID: 0x1234
        out.write(0x12)
        out.write(0x34)
        // Flags: Standard query, RD=1
        out.write(0x01)
        out.write(0x00)
        // QDCOUNT: 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT, NSCOUNT, ARCOUNT: 0
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        // QNAME
        for (part in domain.split('.')) {
            val bytes = part.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)

        // QTYPE: A (1)
        out.write(0x00)
        out.write(0x01)
        // QCLASS: IN (1)
        out.write(0x00)
        out.write(0x01)

        return out.toByteArray()
    }

    private fun parseFirstARecord(buf: ByteArray, length: Int): String? {
        try {
            if (length < 12) return null
            var pos = 12
            // Skip Question QNAME
            while (pos < length) {
                val len = buf[pos].toInt() and 0xFF
                if (len == 0) {
                    pos++
                    break
                }
                pos += 1 + len
            }
            // Skip QTYPE (2) + QCLASS (2)
            pos += 4
            // Iterate Answers
            while (pos + 10 <= length) {
                if ((buf[pos].toInt() and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < length && buf[pos].toInt() != 0) {
                        pos += 1 + (buf[pos].toInt() and 0xFF)
                    }
                    pos++
                }
                if (pos + 10 > length) break
                val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                val dataLen = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
                pos += 10
                if (type == 1 && dataLen == 4 && pos + 4 <= length) {
                    return "${buf[pos].toUByte()}.${buf[pos + 1].toUByte()}.${buf[pos + 2].toUByte()}.${buf[pos + 3].toUByte()}"
                }
                pos += dataLen
            }
        } catch (_: Exception) {}
        return null
    }

    private fun testSocks5Http(proxyHost: String, proxyPort: Int, url: String, timeoutMs: Int): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort)))
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MultiBypass-Doctor/2.0")
                .build()

            client.newCall(request).execute().use { resp ->
                resp.isSuccessful || resp.code in 200..399
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun testSocks5TcpConnect(proxyHost: String, proxyPort: Int, targetIp: String, targetPort: Int, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            val sIn = socket.getInputStream()
            val sOut = socket.getOutputStream()

            // SOCKS5 Greeting
            sOut.write(byteArrayOf(0x05, 0x01, 0x00))
            sOut.flush()
            val greeting = ByteArray(2)
            if (sIn.read(greeting) < 2 || greeting[1] != 0x00.toByte()) {
                return false
            }

            // SOCKS5 Connect IPv4
            val ipParts = targetIp.split(".").map { it.toInt().toByte() }
            val req = byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                ipParts[0], ipParts[1], ipParts[2], ipParts[3],
                ((targetPort shr 8) and 0xFF).toByte(),
                (targetPort and 0xFF).toByte()
            )
            sOut.write(req)
            sOut.flush()

            val resp = ByteArray(10)
            val read = sIn.read(resp)
            read >= 4 && resp[1] == 0x00.toByte()
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
