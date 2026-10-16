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
    val domains: List<String> = emptyList(),
    val appPackages: List<String> = emptyList()
) {
    fun getEffectiveDns(): String = getDohUrl()

    fun getDohUrl(): String {
        return when (presetId) {
            "comss" -> "https://dns.comss.one/dns-query"
            "geohide" -> "https://geohide.ru/dns-query"
            "bezmezhau" -> "https://dns.bezmezhau.com/dns-query"
            "xbox" -> "https://dns.comss.one/dns-query"
            "dns_ai" -> "https://dns.comss.one/dns-query"
            "nextdns" -> {
                val profile = nextDnsProfileId.trim().ifBlank { "dns" }
                "https://dns.nextdns.io/$profile"
            }
            else -> customDohUrl.ifBlank { "https://dns.comss.one/dns-query" }
        }
    }

    fun getEffectiveStandardIp(): String {
        return when (presetId) {
            "comss" -> "92.223.109.31"
            "geohide" -> "193.233.112.67"
            "bezmezhau" -> "143.20.64.55"
            "xbox" -> "185.51.200.2"
            "dns_ai" -> "92.223.109.31"
            "nextdns" -> "45.90.28.0"
            else -> customStandardIp.ifBlank { "92.223.109.31" }
        }
    }
}

data class AntiDpiGroupConfig(
    val strategy: String = "-o1 -r-5+se -a1",
    val blockQuic: Boolean = true,
    val domains: List<String> = emptyList(),
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
    val successfulSitesCount: Int,
    val workingServices: List<String> = emptyList(),
    val serviceLatencies: Map<String, Long> = emptyMap()
)

val POPULAR_PACKAGES = setOf(
    "com.google.android.youtube",
    "app.revanced.android.youtube",
    "com.vanced.android.youtube",
    "com.instagram.android",
    "com.discord",
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.telegram.plus",
    "com.openai.chatgpt",
    "com.twitter.android",
    "com.android.chrome",
    "com.spotify.music",
    "com.zhiliaoapp.musically",
    "com.facebook.katana",
    "com.facebook.orca"
)
