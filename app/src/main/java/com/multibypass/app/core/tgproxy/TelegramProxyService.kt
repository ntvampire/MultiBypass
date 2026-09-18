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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TelegramProxyService : Service() {

    companion object {
        private const val TAG = "TelegramProxyService"
        private const val NOTIFICATION_ID = 2002

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                val secret = "ee112233445566778899aabbccddeeff7777772e676f6f676c652e636f6d"
                val res = NativeTgProxy.startProxy(host = "127.0.0.1", port = 1443, secret = secret)
                if (res == 0) {
                    _isRunning.value = true
                    Log.i(TAG, "Telegram WS Proxy started on 127.0.0.1:1443")
                } else {
                    Log.e(TAG, "Failed to start Telegram WS Proxy: code $res")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Telegram WS Proxy", e)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            try {
                NativeTgProxy.stopProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Telegram WS Proxy", e)
            }
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

object TelegramProxyController {
    fun openTelegramProxy(context: Context, port: Int = 1443) {
        val secret = NativeTgProxy.getSecretWithPrefix() ?: "dd112233445566778899aabbccddeeff"
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
