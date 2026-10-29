package com.multibypass.app.core.tgproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Локальный контроллер нативного прокси Telegram (sTGWS / libtgwsproxy.so).
 * Управляется напрямую из MultiBypassVpnService без создания отдельного
 * системного Android-сервиса и без лишних уведомлений в шторке.
 */
object TelegramBridgeController {
    private const val TAG = "TelegramBridgeController"
    const val LOCAL_PROXY_PORT = 1443

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Synchronized
    fun start(context: Context, secret: String): Boolean {
        if (_isRunning.value) return true
        return try {
            ensureCfDomainsCache(context)
            NativeTgProxy.setCfProxyCacheDir(context.cacheDir.absolutePath)
            NativeTgProxy.setCfProxyConfig(enabled = true, priority = 1, userDomain = "")

            val res = NativeTgProxy.startProxy(host = "127.0.0.1", port = LOCAL_PROXY_PORT, secret = secret)
            val success = (res == 0)
            _isRunning.value = success
            if (success) {
                Log.i(TAG, "Native Telegram WS Proxy started on 127.0.0.1:$LOCAL_PROXY_PORT")
            } else {
                Log.e(TAG, "Failed to start Native Telegram WS Proxy: code $res")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Native Telegram WS Proxy", e)
            _isRunning.value = false
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!_isRunning.value) return
        try {
            NativeTgProxy.stopProxy()
            Log.i(TAG, "Native Telegram WS Proxy stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Native Telegram WS Proxy", e)
        } finally {
            _isRunning.value = false
        }
    }

    @Synchronized
    fun restart(context: Context, secret: String): Boolean {
        stop()
        try { Thread.sleep(200) } catch (_: Exception) {}
        return start(context, secret)
    }

    private fun ensureCfDomainsCache(context: Context) {
        try {
            val cacheFile = File(context.cacheDir, "cfproxy-domains-cache.txt")
            // Всегда синхронизируем актуальный проверенный список доменов из assets
            context.assets.open("cfproxy_domains.txt").use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Synchronized cfproxy-domains-cache.txt from assets (${cacheFile.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure CF domains cache: ${e.message}")
        }
    }
}
