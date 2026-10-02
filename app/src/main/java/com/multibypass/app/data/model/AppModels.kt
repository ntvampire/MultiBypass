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
        "oaistatic.com",
        "oaiusercontent.com",
        "auth0.openai.com",
        "api.openai.com",
        "chat.openai.com",
        "platform.openai.com",
        "anthropic.com",
        "claude.ai",
        "claude.com",
        "copilot.microsoft.com",
        "sydney.bing.com",
        "edgeservices.bing.com",
        "midjourney.com",
        "perplexity.ai",
        "poe.com",
        "spotify.com",
        "scdn.co",
        "spotifycdn.com",
        "canva.com",
        "notion.so",
        "linkedin.com"
    ),
    val appPackages: List<String> = emptyList()
) {
    fun getEffectiveDns(): String {
        return when (mode) {
            DnsMode.STANDARD -> getEffectiveStandardIp()
            DnsMode.DOH -> {
                when (presetId) {
                    "comss" -> "https://dns.comss.one/dns-query"
                    "xbox" -> "https://dns.comss.one/dns-query"
                    "dns_ai" -> "https://dns.comss.one/dns-query"
                    "nextdns" -> {
                        val profile = nextDnsProfileId.trim().ifBlank { "dns" }
                        "https://dns.nextdns.io/$profile"
                    }
                    else -> customDohUrl.ifBlank { "https://dns.comss.one/dns-query" }
                }
            }
        }
    }

    fun getEffectiveStandardIp(): String {
        return when (presetId) {
            "comss" -> "92.223.109.31"
            "xbox" -> "185.51.200.2"
            "dns_ai" -> "92.223.109.31"
            "nextdns" -> "45.90.28.0"
            else -> customStandardIp.ifBlank { "92.223.109.31" }
        }
    }
}

data class AntiDpiGroupConfig(
    val strategy: String = "-f -1 -e 1 -q 1",
    val domains: List<String> = listOf(
        "youtube.com",
        "youtu.be",
        "googlevideo.com",
        "ytimg.com",
        "i.ytimg.com",
        "ggpht.com",
        "yt3.ggpht.com",
        "yt4.ggpht.com",
        "youtubei.googleapis.com",
        "jnn-pa.googleapis.com",
        "manifest.googlevideo.com",
        "discord.com",
        "discordapp.com",
        "discord.gg",
        "discordapp.net",
        "discord.media",
        "discordcdn.com",
        "discord.dev",
        "dis.gd",
        "instagram.com",
        "cdninstagram.com",
        "i.instagram.com",
        "graph.instagram.com",
        "gateway.instagram.com",
        "facebook.com",
        "fbcdn.net",
        "threads.net",
        "twitter.com",
        "x.com",
        "t.co",
        "twimg.com",
        "rutracker.org",
        "ntc.party",
        "nnmclub.to",
        "rutor.org"
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
    val successfulSitesCount: Int,
    val workingServices: List<String> = emptyList()
)
