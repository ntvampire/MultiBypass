package com.multibypass.app.core.dns

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.multibypass.app.data.model.DnsPreset
import java.io.File

object DnsPresets {
    private const val TAG = "DnsPresets"

    private val defaultPresets = listOf(
        DnsPreset(
            id = "webshift",
            name = "WebShift DNS",
            standardIp = "31.31.207.68",
            dohUrl = "https://d.webshiftvpn.ru/dns-query",
            description = "SmartDNS: ChatGPT, Gemini, Claude, Copilot, Xbox, Notion (400+ сервисов)"
        ),
        DnsPreset(
            id = "zapret",
            name = "Zapret DNS",
            standardIp = "83.217.211.149",
            dohUrl = "https://dns.zapret.moe/dns-query",
            description = "SmartDNS: ChatGPT, Claude, Spotify и обход гео-блокировок"
        ),
        DnsPreset(
            id = "comss",
            name = "Comss.one DNS",
            standardIp = "92.223.109.31",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "SmartDNS: ChatGPT, Claude, Copilot, Spotify, Xbox, Canva, Notion"
        ),
        DnsPreset(
            id = "geohide",
            name = "GeoHide DNS",
            standardIp = "193.233.112.67",
            dohUrl = "https://geohide.ru/dns-query",
            description = "SmartDNS: обход гео-блокировок зарубежных сервисов и ИИ (Россия)"
        ),
        DnsPreset(
            id = "bezmezhau",
            name = "BezMezhAU DNS",
            standardIp = "143.20.64.55",
            dohUrl = "https://dns.bezmezhau.com/dns-query",
            description = "SmartDNS: обход санкций и блокировок ChatGPT/Spotify/YouTube (Беларусь/РФ)"
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

    @Volatile
    var list: List<DnsPreset> = defaultPresets
        private set

    fun updateList(newList: List<DnsPreset>) {
        if (newList.isNotEmpty()) {
            list = newList
        }
    }

    fun loadFromDisk(context: Context) {
        try {
            val file = File(context.filesDir, "dns_providers.json")
            val json = if (file.exists() && file.length() > 0) {
                file.readText()
            } else {
                context.assets.open("dns_providers.json").bufferedReader().use { it.readText() }
            }
            val type = object : TypeToken<List<DnsPreset>>() {}.type
            val loaded: List<DnsPreset> = Gson().fromJson(json, type)
            if (loaded.isNotEmpty()) {
                list = loaded
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load DNS presets from disk", e)
        }
    }

    fun findById(id: String): DnsPreset = list.find { it.id == id } ?: list.first()
}
