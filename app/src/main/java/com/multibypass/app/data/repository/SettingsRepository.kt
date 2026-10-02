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
        private const val KEY_TG_SECRET = "key_tg_secret"
        private const val KEY_BOOT_AUTOSTART = "key_boot_autostart"
        private const val KEY_WATCHDOG_ENABLED = "key_watchdog_enabled"
        private const val KEY_ROUTED_APPS = "key_routed_apps"
        private const val KEY_ROUTE_ALL_APPS = "key_route_all_apps"
        private const val KEY_DOMAINS_VERSION = "key_domains_version"
        private const val CURRENT_DOMAINS_VERSION = 3

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

    private val _watchdogEnabled = MutableStateFlow(true)
    val watchdogEnabled: StateFlow<Boolean> = _watchdogEnabled.asStateFlow()

    private val _routedApps = MutableStateFlow(loadRoutedApps())
    val routedApps: StateFlow<List<String>> = _routedApps.asStateFlow()

    private val _routeAllApps = MutableStateFlow(prefs.getBoolean(KEY_ROUTE_ALL_APPS, false))
    val routeAllApps: StateFlow<Boolean> = _routeAllApps.asStateFlow()

    init {
        // Enforce watchdog always enabled
        prefs.edit().putBoolean(KEY_WATCHDOG_ENABLED, true).apply()

        // Force-upgrade default domains & default strategy if coming from older versions
        val domainVer = prefs.getInt(KEY_DOMAINS_VERSION, 0)
        if (domainVer < CURRENT_DOMAINS_VERSION) {
            val defaultDns = DnsGroupConfig()
            val currentDns = _dnsConfig.value
            val mergedDnsDomains = (defaultDns.domains + currentDns.domains).distinct()
            val newDns = currentDns.copy(domains = mergedDnsDomains)
            updateDnsConfig(newDns)

            val defaultAntiDpi = AntiDpiGroupConfig()
            val currentAntiDpi = _antiDpiConfig.value
            val mergedAntiDpiDomains = (defaultAntiDpi.domains + currentAntiDpi.domains).distinct()
            val newStrategy = if (currentAntiDpi.strategy == "-f -1 -e 1 -q 1" || currentAntiDpi.strategy.isBlank()) {
                "-o1 -r-5+se -a1"
            } else {
                currentAntiDpi.strategy
            }
            val newAntiDpi = currentAntiDpi.copy(
                domains = mergedAntiDpiDomains,
                strategy = newStrategy
            )
            updateAntiDpiConfig(newAntiDpi)

            prefs.edit().putInt(KEY_DOMAINS_VERSION, CURRENT_DOMAINS_VERSION).apply()
        }
    }

    fun updateRoutedApps(apps: List<String>) {
        _routedApps.value = apps
        prefs.edit().putString(KEY_ROUTED_APPS, gson.toJson(apps)).apply()
        _dnsConfig.value = _dnsConfig.value.copy(appPackages = apps)
        prefs.edit().putString(KEY_DNS_CONFIG, gson.toJson(_dnsConfig.value)).apply()
        _antiDpiConfig.value = _antiDpiConfig.value.copy(appPackages = apps)
        prefs.edit().putString(KEY_ANTIDPI_CONFIG, gson.toJson(_antiDpiConfig.value)).apply()
    }

    fun setRouteAllApps(enabled: Boolean) {
        _routeAllApps.value = enabled
        prefs.edit().putBoolean(KEY_ROUTE_ALL_APPS, enabled).apply()
    }

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
        prefs.edit()
            .putString(KEY_TG_CONFIG, gson.toJson(config))
            .putString(KEY_TG_SECRET, config.secret)
            .apply()
    }

    fun setBootAutoStart(enabled: Boolean) {
        _bootAutoStart.value = enabled
        prefs.edit().putBoolean(KEY_BOOT_AUTOSTART, enabled).apply()
    }

    fun setWatchdogEnabled(enabled: Boolean) {
        _watchdogEnabled.value = true
        prefs.edit().putBoolean(KEY_WATCHDOG_ENABLED, true).apply()
    }

    private fun loadRoutedApps(): List<String> {
        val json = prefs.getString(KEY_ROUTED_APPS, null)
        if (json != null) {
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return (loadDnsConfig().appPackages + loadAntiDpiConfig().appPackages).distinct()
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
        val savedSecret = prefs.getString(KEY_TG_SECRET, null)
        val json = prefs.getString(KEY_TG_CONFIG, null)
        var config: TelegramProxyConfig? = null

        if (json != null) {
            try {
                config = gson.fromJson(json, TelegramProxyConfig::class.java)
            } catch (_: Exception) {}
        }

        val secretCandidate = config?.secret ?: savedSecret
        val isCandidateValid = secretCandidate != null &&
                secretCandidate.length == 32 &&
                secretCandidate.all { it in "0123456789abcdefABCDEF" } &&
                secretCandidate != "00000000000000000000000000000000"

        val finalSecret = if (isCandidateValid && secretCandidate != null) {
            secretCandidate
        } else {
            generateRandomHexSecret()
        }

        val finalConfig = (config ?: TelegramProxyConfig()).copy(secret = finalSecret)

        if (json == null || savedSecret == null || !isCandidateValid) {
            prefs.edit()
                .putString(KEY_TG_CONFIG, gson.toJson(finalConfig))
                .putString(KEY_TG_SECRET, finalSecret)
                .apply()
        }

        return finalConfig
    }

    private fun generateRandomHexSecret(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
