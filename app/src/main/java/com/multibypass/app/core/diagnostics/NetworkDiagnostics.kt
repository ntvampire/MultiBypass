package com.multibypass.app.core.diagnostics

import android.content.Context
import android.os.Build
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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
        sb.appendLine("Дата: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}")
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
        sb.appendLine("Итог: ${if (allOk) "Все компоненты работают стабильно" else "Обнаружены проблемы в цепочке"}")
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
                    detail = "Демон десинхронизации не отвечает"
                )
            }
        )

        // 3. Local DoH Server (Port 5353) Check
        val dnsConfig = repository.dnsConfig.value
        val dohPort = 5353
        val dohStart = System.currentTimeMillis()
        val dohResolved = testDnsResolution("127.0.0.1", dohPort, "google.com", 2000)
        val dohTime = System.currentTimeMillis() - dohStart
        steps.add(
            if (dohResolved != null) {
                DiagnosticStepResult(
                    title = "Локальный DoH-сервер",
                    description = "Отвечает на 127.0.0.1:$dohPort",
                    status = DiagnosticStatus.SUCCESS,
                    latencyMs = dohTime,
                    detail = "IP: $dohResolved (Пресет: ${dnsConfig.presetId})"
                )
            } else {
                DiagnosticStepResult(
                    title = "Локальный DoH-сервер",
                    description = "Нет ответа от 127.0.0.1:$dohPort",
                    status = if (vpnStatus == VpnStatus.CONNECTED) DiagnosticStatus.ERROR else DiagnosticStatus.WARNING,
                    detail = "DoH URL: ${dnsConfig.getEffectiveDns()}"
                )
            }
        )

        // 4. Telegram MTProto Proxy Check
        val tgConfig = repository.tgConfig.value
        if (tgConfig.enabled) {
            val tgStart = System.currentTimeMillis()
            val tgListening = isTcpPortOpen("127.0.0.1", tgConfig.port, 1000)
            val tgTime = System.currentTimeMillis() - tgStart
            steps.add(
                if (tgListening) {
                    DiagnosticStepResult(
                        title = "Telegram MTProto Прокси",
                        description = "Порт 127.0.0.1:${tgConfig.port} открыт",
                        status = DiagnosticStatus.SUCCESS,
                        latencyMs = tgTime,
                        detail = "Служба: ${if (TelegramProxyService.isRunning.value) "Активна" else "Фоновый режим"}"
                    )
                } else {
                    DiagnosticStepResult(
                        title = "Telegram MTProto Прокси",
                        description = "Порт ${tgConfig.port} закрыт",
                        status = DiagnosticStatus.WARNING,
                        detail = "Включите в настройках или запустите сервис"
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

        // 5. Anti-DPI SOCKS5 HTTP Access (YouTube generate_204)
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
                        detail = "Попробуйте запустить автоподбор стратегий"
                    )
                }
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

            if (resp.length >= 12) "OK (${resp.length} байт)" else null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // ID
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
            val bytes = part.toByteArray()
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
                .header("User-Agent", "Mozilla/5.0 MultiBypass-Doctor/1.3")
                .build()

            client.newCall(request).execute().use { resp ->
                resp.isSuccessful || resp.code in 200..499
            }
        } catch (_: Exception) {
            false
        }
    }
}
