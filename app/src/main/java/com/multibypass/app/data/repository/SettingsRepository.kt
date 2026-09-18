package com.multibypass.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.multibypass.app.data.model.AntiDpiGroupConfig
import com.multibypass.app.data.model.DnsGroupConfig
import com.multibypass.app.data.model.TelegramProxyConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "multibypass_prefs"
        private const val KEY_DNS_CONFIG = "key_dns_config"
        private const val KEY_ANTIDPI_CONFIG = "key_antidpi_config"
        private const val KEY_TG_CONFIG = "key_tg_config"
        private const val KEY_BOOT_AUTOSTART = "key_boot_autostart"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _dnsConfig = MutableStateFlow(loadDnsConfig())
    val dnsConfig: StateFlow<DnsGroupConfig> = _dnsConfig.asStateFlow()

    private val _antiDpiConfig = MutableStateFlow(loadAntiDpiConfig())
    val antiDpiConfig: StateFlow<AntiDpiGroupConfig> = _antiDpiConfig.asStateFlow()

    private val _tgConfig = MutableStateFlow(loadTgConfig())
    val tgConfig: StateFlow<TelegramProxyConfig> = _tgConfig.asStateFlow()

    private val _bootAutoStart = MutableStateFlow(prefs.getBoolean(KEY_BOOT_AUTOSTART, false))
    val bootAutoStart: StateFlow<Boolean> = _bootAutoStart.asStateFlow()

    fun updateDnsConfig(config: DnsGroupConfig) {
        _dnsConfig.value = config
        prefs.edit().putString(KEY_DNS_CONFIG, gson.toJson(config)).apply()
    }

    fun updateAntiDpiConfig(config: AntiDpiGroupConfig) {
        _antiDpiConfig.value = config
        prefs.edit().putString(KEY_ANTIDPI_CONFIG, gson.toJson(config)).apply()
    }

    fun updateTgConfig(config: TelegramProxyConfig) {
        _tgConfig.value = config
        prefs.edit().putString(KEY_TG_CONFIG, gson.toJson(config)).apply()
    }

    fun setBootAutoStart(enabled: Boolean) {
        _bootAutoStart.value = enabled
        prefs.edit().putBoolean(KEY_BOOT_AUTOSTART, enabled).apply()
    }

    private fun loadDnsConfig(): DnsGroupConfig {
        val json = prefs.getString(KEY_DNS_CONFIG, null) ?: return DnsGroupConfig()
        return try {
            gson.fromJson(json, DnsGroupConfig::class.java) ?: DnsGroupConfig()
        } catch (_: Exception) {
            DnsGroupConfig()
        }
    }

    private fun loadAntiDpiConfig(): AntiDpiGroupConfig {
        val json = prefs.getString(KEY_ANTIDPI_CONFIG, null) ?: return AntiDpiGroupConfig()
        return try {
            gson.fromJson(json, AntiDpiGroupConfig::class.java) ?: AntiDpiGroupConfig()
        } catch (_: Exception) {
            AntiDpiGroupConfig()
        }
    }

    private fun loadTgConfig(): TelegramProxyConfig {
        val json = prefs.getString(KEY_TG_CONFIG, null) ?: return TelegramProxyConfig()
        return try {
            gson.fromJson(json, TelegramProxyConfig::class.java) ?: TelegramProxyConfig()
        } catch (_: Exception) {
            TelegramProxyConfig()
        }
    }
}
