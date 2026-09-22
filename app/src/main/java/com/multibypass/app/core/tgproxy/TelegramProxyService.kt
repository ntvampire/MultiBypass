package com.multibypass.app.core.tgproxy

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multibypass.app.MainActivity
import com.multibypass.app.MultiBypassApplication
import com.multibypass.app.R
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TelegramProxyService : Service() {

    companion object {
        private const val TAG = "TelegramProxyService"
        private const val NOTIFICATION_ID = 2002

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        private var instance: TelegramProxyService? = null

        fun start(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java)
            context.stopService(intent)
        }

        suspend fun restartProxy(context: Context): Boolean {
            val current = instance
            return if (current != null && _isRunning.value) {
                current.restartNativeProxy()
            } else {
                start(context)
                true
            }
        }

        internal fun updateRunningState(running: Boolean) {
            _isRunning.value = running
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repository = SettingsRepository.getInstance(applicationContext)
        if (!repository.tgConfig.value.enabled) {
            Log.i(TAG, "TG Proxy is disabled in settings, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            mutex.withLock {
                try {
                    val secret = repository.tgConfig.value.secret
                    val res = NativeTgProxy.startProxy(host = "127.0.0.1", port = 1443, secret = secret)
                    if (res == 0) {
                        _isRunning.value = true
                        Log.i(TAG, "Telegram WS Proxy started on 127.0.0.1:1443 with secret $secret")
                    } else {
                        Log.e(TAG, "Failed to start Telegram WS Proxy: code $res")
                        _isRunning.value = false
                        stopSelf()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting Telegram WS Proxy", e)
                    _isRunning.value = false
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    suspend fun restartNativeProxy(): Boolean = mutex.withLock {
        Log.i(TAG, "Restarting Telegram WS Proxy native instance...")
        try {
            NativeTgProxy.stopProxy()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping native proxy during restart", e)
        }
        delay(300)
        return try {
            val repository = SettingsRepository.getInstance(applicationContext)
            val secret = repository.tgConfig.value.secret
            val res = NativeTgProxy.startProxy(host = "127.0.0.1", port = 1443, secret = secret)
            val success = (res == 0)
            _isRunning.value = success
            Log.i(TAG, "Telegram WS Proxy restart result: $res, success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception restarting Telegram WS Proxy", e)
            _isRunning.value = false
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            NativeTgProxy.stopProxy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Telegram WS Proxy", e)
        } finally {
            _isRunning.value = false
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MultiBypassApplication.TG_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Telegram WS Proxy")
            .setContentText("Локальный MTProto прокси активен (порт 1443)")
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

object TelegramProxyController {
    fun openTelegramProxy(context: Context, port: Int = 1443) {
        val repository = SettingsRepository.getInstance(context)
        val rawSecret = repository.tgConfig.value.secret
        val nativeSecret = NativeTgProxy.getSecretWithPrefix()
        val secret = if (!nativeSecret.isNullOrBlank() && !nativeSecret.contains("00000000000000000000000000000000")) {
            nativeSecret
        } else {
            "dd$rawSecret"
        }
        val proxyUri = Uri.parse("tg://proxy?server=127.0.0.1&port=$port&secret=$secret")
        val intent = Intent(Intent.ACTION_VIEW, proxyUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("TgController", "Cannot open Telegram directly: ${e.message}")
        }
    }
}
