package com.multibypass.app.core.dns

import com.multibypass.app.data.model.DnsPreset

object DnsPresets {
    val list = listOf(
        DnsPreset(
            id = "comss",
            name = "Comss.one DNS",
            standardIp = "92.223.109.31",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "Оптимизирован для обхода региональных ограничений"
        ),
        DnsPreset(
            id = "adguard",
            name = "AdGuard DNS",
            standardIp = "94.140.14.14",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            description = "Блокировка рекламы и защита конфиденциальности"
        ),
        DnsPreset(
            id = "google",
            name = "Google Public DNS",
            standardIp = "8.8.8.8",
            dohUrl = "https://dns.google/dns-query",
            description = "Быстрый и надежный глобальный DNS"
        ),
        DnsPreset(
            id = "quad9",
            name = "Quad9 DNS",
            standardIp = "9.9.9.9",
            dohUrl = "https://dns.quad9.net/dns-query",
            description = "Защита от вредоносных доменов и приватность"
        ),
        DnsPreset(
            id = "yandex",
            name = "Яндекс DNS",
            standardIp = "77.88.8.8",
            dohUrl = "https://common.dot.dns.yandex.net/dns-query",
            description = "Быстрый DNS с серверами в РФ"
        ),
        DnsPreset(
            id = "xbox",
            name = "Xbox DNS",
            standardIp = "185.51.200.2",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "DNS для доступа к Xbox Live / Microsoft"
        ),
        DnsPreset(
            id = "dns_ai",
            name = "DNS-AI",
            standardIp = "149.112.112.11",
            dohUrl = "https://dns.quad9.net/dns-query",
            description = "Маршрутизация к серверам искусственного интеллекта"
        ),
        DnsPreset(
            id = "nextdns",
            name = "NextDNS",
            standardIp = "45.90.28.0",
            dohUrl = "https://dns.nextdns.io/",
            requiresProfileId = true,
            description = "Персональный облачный DNS (требуется ID профиля)"
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
