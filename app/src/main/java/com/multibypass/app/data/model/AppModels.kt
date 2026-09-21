package com.multibypass.app.data.model

enum class DnsMode {
    STANDARD,
    DOH
}

data class DnsPreset(
    val id: String,
    val name: String,
    val standardIp: String,
    val dohUrl: String,
    val requiresProfileId: Boolean = false,
    val description: String = ""
)

data class DnsGroupConfig(
    val mode: DnsMode = DnsMode.DOH,
    val presetId: String = "comss",
    val customStandardIp: String = "92.223.109.31",
    val customDohUrl: String = "https://dns.comss.one/dns-query",
    val nextDnsProfileId: String = "",
    val domains: List<String> = listOf(
        "openai.com",
        "chatgpt.com",
        "anthropic.com",
        "claude.ai",
        "spotify.com",
        "scdn.co",
        "canva.com",
        "notion.so"
    ),
    val appPackages: List<String> = emptyList()
) {
    fun getEffectiveDns(): String {
        return when (mode) {
            DnsMode.STANDARD -> {
                when (presetId) {
                    "google" -> "8.8.8.8"
                    "quad9" -> "9.9.9.9"
                    "adguard" -> "94.140.14.14"
                    "comss" -> "92.223.109.31"
                    "yandex" -> "77.88.8.8"
                    "xbox" -> "185.51.200.2"
                    "dns_ai" -> "149.112.112.11"
                    "nextdns" -> "45.90.28.0"
                    else -> customStandardIp.ifBlank { "92.223.109.31" }
                }
            }
            DnsMode.DOH -> {
                when (presetId) {
                    "google" -> "https://dns.google/dns-query"
                    "quad9" -> "https://dns.quad9.net/dns-query"
                    "adguard" -> "https://dns.adguard-dns.com/dns-query"
                    "comss" -> "https://dns.comss.one/dns-query"
                    "yandex" -> "https://common.dot.dns.yandex.net/dns-query"
                    "xbox" -> "https://dns.comss.one/dns-query"
                    "dns_ai" -> "https://dns.quad9.net/dns-query"
                    "nextdns" -> {
                        val profile = nextDnsProfileId.trim().ifBlank { "dns" }
                        "https://dns.nextdns.io/$profile"
                    }
                    else -> customDohUrl.ifBlank { "https://dns.comss.one/dns-query" }
                }
            }
        }
    }
}

data class AntiDpiGroupConfig(
    val strategy: String = "-f -1 -e 1 -q 1",
    val domains: List<String> = listOf(
        "youtube.com",
        "googlevideo.com",
        "ytimg.com",
        "youtu.be",
        "ggpht.com",
        "discord.com",
        "discordapp.com",
        "discord.gg",
        "discordapp.net",
        "instagram.com",
        "cdninstagram.com",
        "twitter.com",
        "x.com",
        "t.co",
        "twimg.com",
        "rutracker.org",
        "ntc.party"
    ),
    val appPackages: List<String> = emptyList()
)

data class TelegramProxyConfig(
    val enabled: Boolean = true,
    val port: Int = 1443,
    val secret: String = "112233445566778899aabbccddeeff00",
    val autoStartWithVpn: Boolean = true
)

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class StrategyTestResult(
    val strategy: String,
    val isWorking: Boolean,
    val latencyMs: Long,
    val testedSitesCount: Int,
    val successfulSitesCount: Int
)
