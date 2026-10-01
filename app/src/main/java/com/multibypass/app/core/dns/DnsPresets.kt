package com.multibypass.app.core.dns

import com.multibypass.app.data.model.DnsPreset

object DnsPresets {
    val list = listOf(
        DnsPreset(
            id = "comss",
            name = "Comss.one DNS",
            standardIp = "92.223.109.31",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "SmartDNS: ChatGPT, Claude, Copilot, Spotify, Xbox, Canva, Notion"
        ),
        DnsPreset(
            id = "xbox",
            name = "Xbox / Microsoft DNS",
            standardIp = "185.51.200.2",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "Специализированный DNS для Xbox Live, Microsoft Store и сетевых игр"
        ),
        DnsPreset(
            id = "dns_ai",
            name = "DNS-AI",
            standardIp = "92.223.109.31",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "Оптимизация доступа к ИИ: OpenAI ChatGPT, Anthropic Claude, Midjourney"
        ),
        DnsPreset(
            id = "nextdns",
            name = "NextDNS",
            standardIp = "45.90.28.0",
            dohUrl = "https://dns.nextdns.io/",
            requiresProfileId = true,
            description = "Персональный облачный DNS (требуется ID профиля, например: abc123)"
        ),
        DnsPreset(
            id = "custom",
            name = "Пользовательский (Вручную)",
            standardIp = "",
            dohUrl = "",
            description = "Ручной ввод собственного IP или DoH адреса"
        )
    )

    fun findById(id: String): DnsPreset = list.find { it.id == id } ?: list.first()
}
