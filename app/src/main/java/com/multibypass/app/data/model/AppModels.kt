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
    val customStandardIp: String = "195.133.25.16",
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
        "linkedin.com",
        "gemini.google.com",
        "bard.google.com",
        "aistudio.google.com",
        "generativeai.google.com",
        "generativeai.google",
        "generativelanguage.googleapis.com",
        "ai.google.dev",
        "apis.google.com",
        "makersuite.google.com",
        "alkalimakersuite-pa.clients6.google.com",
        "proactivebackend-pa.googleapis.com",
        "assistant.google.com",
        "playstation.com",
        "sonyentertainmentnetwork.com",
        "xboxlive.com",
        "medium.com",
        "soundcloud.com",
        "sndcdn.com",
        "audio-ak-spotify-com.akamaized.net",
        "heads4-ak-spotify-com.akamaized.net"
    ),
    val appPackages: List<String> = emptyList()
) {
    fun getEffectiveDns(): String {
        return when (mode) {
            DnsMode.STANDARD -> getEffectiveStandardIp()
            DnsMode.DOH -> {
                val preset = com.multibypass.app.core.dns.DnsPresets.findById(presetId)
                if (preset.id != "custom" && preset.dohUrl.isNotBlank()) {
                    if (preset.requiresProfileId) {
                        val profile = nextDnsProfileId.trim().ifBlank { "dns" }
                        return "${preset.dohUrl.trimEnd('/')}/$profile"
                    }
                    return preset.dohUrl
                }
                when (presetId) {
                    "webshift" -> "https://dns.comss.one/dns-query"
                    "zapret" -> "https://dns.zapret.moe/dns-query"
                    "comss" -> "https://dns.comss.one/dns-query"
                    "geohide" -> "https://geohide.ru/dns-query"
                    "bezmezhau" -> "https://dns.bezmezhau.com/dns-query"
                    "xbox" -> "https://dns.comss.one/dns-query"
                    "dns_ai" -> "https://dns.dns-ai.ru/dns-query"
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
        val preset = com.multibypass.app.core.dns.DnsPresets.findById(presetId)
        if (preset.id != "custom" && preset.standardIp.isNotBlank()) {
            return preset.standardIp
        }
        return when (presetId) {
            "webshift" -> "31.31.207.68"
            "zapret" -> "83.217.211.149"
            "comss" -> "195.133.25.16"
            "geohide" -> "193.233.112.67"
            "bezmezhau" -> "143.20.64.55"
            "xbox" -> "185.51.200.2"
            "dns_ai" -> "45.151.102.219"
            "nextdns" -> "45.90.28.0"
            else -> customStandardIp.ifBlank { "195.133.25.16" }
        }
    }
}

data class AntiDpiGroupConfig(
    val strategy: String = "-o1 -r-5+se -a1",
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
        "wide-youtube.l.google.com",
        "video.google.com",
        "discord.com",
        "discordapp.com",
        "discord.gg",
        "discordapp.net",
        "discord.media",
        "discordcdn.com",
        "discord.dev",
        "dis.gd",
        "gateway.discord.gg",
        "status.discord.com",
        "instagram.com",
        "cdninstagram.com",
        "i.instagram.com",
        "graph.instagram.com",
        "gateway.instagram.com",
        "facebook.com",
        "fbcdn.net",
        "threads.net",
        "meta.com",
        "twitter.com",
        "x.com",
        "t.co",
        "twimg.com",
        "cloudflare-ech.com",
        "signal.org",
        "whispersystems.org",
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
    val workingServices: List<String> = emptyList(),
    val serviceLatencies: Map<String, Long> = emptyMap()
)

val GEOBLOCK_PACKAGES = setOf(
    "com.openai.chatgpt",
    "com.google.android.apps.bard",
    "com.google.android.googlequicksearchbox",
    "com.google.android.gms",
    "ai.anthropic.claudeforandroid",
    "ai.perplexity.app.android",
    "com.spotify.music",
    "notion.id",
    "com.canva.editor"
)

val ANTIDPI_PACKAGES = setOf(
    "com.google.android.youtube",
    "app.revanced.android.youtube",
    "com.vanced.android.youtube",
    "com.discord",
    "com.instagram.android",
    "com.instagram.barcelona",
    "com.facebook.katana",
    "com.facebook.orca",
    "com.twitter.android",
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.telegram.plus",
    "org.thoughtcrime.securesms",
    "com.zhiliaoapp.musically"
)

val POPULAR_PACKAGES = GEOBLOCK_PACKAGES + ANTIDPI_PACKAGES
