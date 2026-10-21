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
            id = "comss",
            name = "Comss.one DNS",
            standardIp = "195.133.25.16",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "SmartDNS: Gemini, ChatGPT, Claude, Spotify, Canva, Notion, Xbox"
        ),
        DnsPreset(
            id = "wlrus",
            name = "Wlrus DNS",
            standardIp = "138.124.254.134",
            dohUrl = "https://dns.wlrus.cfd/dns-query",
            description = "SmartDNS (16.5 мс Anycast): Gemini, ChatGPT, Claude, зарубежные сервисы"
        ),
        DnsPreset(
            id = "webshift",
            name = "WebShift DNS",
            standardIp = "31.31.207.68",
            dohUrl = "https://dns.comss.one/dns-query",
            description = "Быстрый SmartDNS (UDP 53) для Gemini и 400+ зарубежных сервисов"
        ),
        DnsPreset(
            id = "dns_ai",
            name = "DNS-AI",
            standardIp = "45.151.102.219",
            dohUrl = "https://dns.dns-ai.ru/dns-query",
            description = "Скоростной DoH/DoT для ИИ-сервисов (Gemini, ChatGPT, Claude)"
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
            val json = if (file.exists()) {
                file.readText()
            } else {
                context.assets.open("dns_providers.json").bufferedReader().use { it.readText() }
            }
            val type = object : TypeToken<List<DnsPreset>>() {}.type
            val loaded: List<DnsPreset> = Gson().fromJson(json, type)
            if (loaded.isNotEmpty()) {
                list = loaded
                Log.i(TAG, "Loaded ${loaded.size} DNS providers")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load DNS providers, using defaults: ${e.message}")
            list = defaultPresets
        }
    }

    fun findById(id: String): DnsPreset = list.find { it.id == id } ?: list.first()
}
