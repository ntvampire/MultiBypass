package com.multibypass.app.data.model

data class SmartHostsConfig(
    val enabled: Boolean = true,
    val domainCount: Int = 0,
    val lastUpdated: Long = 0L
)

data class AntiDpiGroupConfig(
    val strategy: String = "-s1 -f-1 -t5 -a1",
    val instagramStrategy: String = "-s2 -a1",
    val separateProfiles: Boolean = true,
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
    val secret: String = "",
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
    "ai.anthropic.claudeforandroid",
    "ai.perplexity.app.android",
    "com.spotify.music",
    "notion.id",
    "com.canva.editor"
)

val ANTIDPI_PACKAGES = setOf(
    "com.google.android.youtube",
    "com.google.android.apps.youtube.music",
    "app.revanced.android.youtube",
    "app.revanced.android.apps.youtube.music",
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

val META_ANTIDPI_ROOT_DOMAINS = setOf(
    "instagram.com",
    "cdninstagram.com",
    "i.instagram.com",
    "graph.instagram.com",
    "gateway.instagram.com",
    "facebook.com",
    "fbcdn.net",
    "threads.net",
    "meta.com"
)

val CORE_ANTIDPI_ROOT_DOMAINS = setOf(
    "googlevideo.com",
    "youtube.com",
    "ytimg.com",
    "ggpht.com",
    "googleusercontent.com",
    "gvt1.com",
    "gvt2.com",
    "youtu.be",
    "discord.com",
    "discord.gg",
    "discordapp.com",
    "discordapp.net",
    "discordcdn.com",
    "discord.media",
    "twitter.com",
    "x.com",
    "twimg.com",
    "t.co"
) + META_ANTIDPI_ROOT_DOMAINS

val POPULAR_PACKAGES = GEOBLOCK_PACKAGES + ANTIDPI_PACKAGES
