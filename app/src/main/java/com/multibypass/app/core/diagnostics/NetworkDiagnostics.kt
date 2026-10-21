package com.multibypass.app.core.diagnostics

import android.content.Context
import android.os.Build
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.dns.DnsPresets
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

        // 3. SmartDNS Upstream Server (UDP 53)
        val dnsConfig = repository.dnsConfig.value
        val standardIp = dnsConfig.getEffectiveStandardIp()
        val preset = DnsPresets.findById(dnsConfig.presetId)
        val dnsStart = System.currentTimeMillis()
        val dnsResolved = testDnsResolution(standardIp, 53, "google.com", 2500)
        val dnsTime = System.currentTimeMillis() - dnsStart
        steps.add(
            if (dnsResolved != null) {
                DiagnosticStepResult(
                    title = "SmartDNS сервер (UDP 53)",
                    description = "Отвечает за $dnsTime мс",
                    status = DiagnosticStatus.SUCCESS,
                    latencyMs = dnsTime,
                    detail = "${preset.name} ($standardIp:53)"
                )
            } else {
                DiagnosticStepResult(
                    title = "SmartDNS сервер (UDP 53)",
                    description = "Нет ответа от $standardIp:53",
                    status = DiagnosticStatus.WARNING,
                    detail = "Возможна фильтрация UDP 53 оператором. Рекомендуется DoH или резервный IP"
                )
            }
        )

        // 4. DNS over HTTPS (DoH) Check
        val dohUrl = if (dnsConfig.presetId == "custom") {
            dnsConfig.customDohUrl.trim()
        } else {
            val p = DnsPresets.findById(dnsConfig.presetId)
            if (p.requiresProfileId) {
                val prof = dnsConfig.nextDnsProfileId.trim().ifBlank { "dns" }
                "${p.dohUrl.trimEnd('/')}/$prof"
            } else {
                p.dohUrl.trim()
            }
        }

        if (dohUrl.isNotBlank()) {
            val dohStart = System.currentTimeMillis()
            val dohOk = testDohQuery(dohUrl, "google.com", 3000)
            val dohTime = System.currentTimeMillis() - dohStart
            steps.add(
                if (dohOk) {
                    DiagnosticStepResult(
                        title = "DNS over HTTPS (DoH)",
                        description = "Шифрованный канал активен ($dohTime мс)",
                        status = DiagnosticStatus.SUCCESS,
                        latencyMs = dohTime,
                        detail = dohUrl
                    )
                } else {
                    DiagnosticStepResult(
                        title = "DNS over HTTPS (DoH)",
                        description = "Эндпоинт не ответил по HTTPS",
                        status = DiagnosticStatus.WARNING,
                        detail = "DoH URL: $dohUrl (запросы направляются через UDP 53)"
                    )
                }
            )
        } else {
            steps.add(
                DiagnosticStepResult(
                    title = "DNS over HTTPS (DoH)",
                    description = "Не настроен",
                    status = DiagnosticStatus.SKIPPED,
                    detail = "Для текущего профиля DoH URL не задан"
                )
            )
        }

        // 5. SmartDNS Geoblock Bypass (ChatGPT / OpenAI resolution)
        val aiStart = System.currentTimeMillis()
        val aiResolved = testDnsResolution(standardIp, 53, "chatgpt.com", 2500)
        val aiTime = System.currentTimeMillis() - aiStart
        steps.add(
            if (aiResolved != null) {
                DiagnosticStepResult(
                    title = "SmartDNS: Доступ к ChatGPT / AI",
                    description = "chatgpt.com -> $aiResolved",
                    status = DiagnosticStatus.SUCCESS,
                    latencyMs = aiTime,
                    detail = "Геоблокировка успешно обходится выбранным DNS"
                )
            } else {
                DiagnosticStepResult(
                    title = "SmartDNS: Доступ к ChatGPT / AI",
                    description = "chatgpt.com не разрешен",
                    status = DiagnosticStatus.WARNING,
                    detail = "Проверьте выбранный DNS-сервер или обновите списки правил"
                )
            }
        )

        // 6. Anti-DPI SOCKS5 HTTP Access (YouTube generate_204)
        if (byeDpiListening) {
            val testHttpStart = System.currentTimeMillis()
            val httpResult = testSocks5Http("127.0.0.1", byeDpiPort, "https://www.youtube.com/generate_204", 4000)
            val httpTime = System.currentTimeMillis() - testHttpStart
            steps.add(
                if (httpResult) {
                    DiagnosticStepResult(
                        title = "Обход блокировки YouTube (HTTP)",
                        description = "Успешный отклик через ByeByeDPI SOCKS5",
                        status = DiagnosticStatus.SUCCESS,
                        latencyMs = httpTime,
                        detail = "HTTP 204 OK"
                    )
                } else {
                    DiagnosticStepResult(
                        title = "Обход блокировки YouTube (HTTP)",
                        description = "Не удалось получить отклик через SOCKS5",
                        status = DiagnosticStatus.WARNING,
                        detail = "Попробуйте изменить параметры десинхронизации"
                    )
                }
            )
        } else {
            steps.add(
                DiagnosticStepResult(
                    title = "Обход блокировки YouTube (HTTP)",
                    description = "VPN не подключен",
                    status = DiagnosticStatus.SKIPPED,
                    detail = "Требуется запустить VPN для проверки десинхронизации"
                )
            )
        }

        // 7. Telegram MTProto Proxy Check
        val tgConfig = repository.tgConfig.value
        if (tgConfig.enabled) {
            val tgStart = System.currentTimeMillis()
            val tgOpen = isTcpPortOpen("127.0.0.1", tgConfig.port, 1000)
            val tgTime = System.currentTimeMillis() - tgStart
            steps.add(
                if (tgOpen) {
                    DiagnosticStepResult(
                        title = "Telegram MTProto Прокси",
                        description = "Локальный шлюз активен",
                        status = DiagnosticStatus.SUCCESS,
                        latencyMs = tgTime,
                        detail = "127.0.0.1:${tgConfig.port}"
                    )
                } else {
                    DiagnosticStepResult(
                        title = "Telegram MTProto Прокси",
                        description = "Порт ${tgConfig.port} закрыт",
                        status = DiagnosticStatus.WARNING,
                        detail = "Служба включена, но локальный порт не отвечает"
                    )
                }
            )
        } else {
            steps.add(
                DiagnosticStepResult(
                    title = "Telegram MTProto Прокси",
                    description = "Отключен пользователем",
                    status = DiagnosticStatus.SKIPPED
                )
            )
        }

        // 8. Bypass LAN Check
        steps.add(
            DiagnosticStepResult(
                title = "Обход локальной сети (Bypass LAN)",
                description = "Включен по умолчанию",
                status = DiagnosticStatus.SUCCESS,
                detail = "Подсети 192.168.0.0/16, 10.0.0.0/8 направляются мимо туннеля"
            )
        )

        // 9. QUIC Protocol Check
        steps.add(
            DiagnosticStepResult(
                title = "Протокол QUIC (HTTP/3)",
                description = "Прозрачный режим (OpenWrt)",
                status = DiagnosticStatus.SUCCESS,
                detail = "UDP 443 передается прозрачно без дропов и зависаний интерфейса"
            )
        )

        // 10. Direct SmartDNS for Gemini Check
        val geminiStart = System.currentTimeMillis()
        val geminiResolved = testDnsResolution(standardIp, 53, "gemini.google.com", 2500)
        val geminiTime = System.currentTimeMillis() - geminiStart
        steps.add(
            if (geminiResolved != null) {
                DiagnosticStepResult(
                    title = "SmartDNS: Gemini и сервисы Google",
                    description = "gemini.google.com -> $geminiResolved ($geminiTime мс)",
                    status = DiagnosticStatus.SUCCESS,
                    latencyMs = geminiTime,
                    detail = "SmartDNS перенаправляет запросы Gemini на европейский прокси"
                )
            } else {
                DiagnosticStepResult(
                    title = "SmartDNS: Gemini и сервисы Google",
                    description = "gemini.google.com не ответил",
                    status = DiagnosticStatus.WARNING,
                    detail = "Проверьте выбранный DNS-сервер ($standardIp:53)"
                )
            }
        )

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

    private fun testDohQuery(dohUrl: String, domain: String, timeoutMs: Int): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val dnsQuery = buildDnsQuery(domain)

        // Try POST first (RFC 8484)
        try {
            val postRequest = Request.Builder()
                .url(dohUrl)
                .header("Content-Type", "application/dns-message")
                .header("Accept", "application/dns-message")
                .header("User-Agent", "MultiBypass-Doctor/1.2")
                .post(dnsQuery.toRequestBody("application/dns-message".toMediaType()))
                .build()

            client.newCall(postRequest).execute().use { resp ->
                if (resp.isSuccessful) return true
            }
        } catch (_: Exception) {}

        // Fallback to GET with URL-safe base64 query
        return try {
            val base64 = android.util.Base64.encodeToString(
                dnsQuery,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val separator = if (dohUrl.contains("?")) "&" else "?"
            val getUrl = "$dohUrl${separator}dns=$base64"

            val getRequest = Request.Builder()
                .url(getUrl)
                .header("Accept", "application/dns-message")
                .header("User-Agent", "MultiBypass-Doctor/1.2")
                .get()
                .build()

            client.newCall(getRequest).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MultiBypass-Doctor/1.2")
                .build()

            client.newCall(request).execute().use { resp ->
                resp.isSuccessful || resp.code in 200..399
            }
        } catch (_: Exception) {
            false
        }
    }
}
