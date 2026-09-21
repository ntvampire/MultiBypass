package com.multibypass.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MultiBypassApplication : Application() {

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "multibypass_vpn_channel"
        const val TG_NOTIFICATION_CHANNEL_ID = "multibypass_tg_channel"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashLogger()
        createNotificationChannels()
    }

    private fun setupCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("MultiBypassCrash", "CRASH in thread ${thread.name}: ${throwable.message}", throwable)
            try {
                val file = java.io.File(filesDir, "last_crash.txt")
                file.writeText("Time: ${java.util.Date()}\nThread: ${thread.name}\n${android.util.Log.getStackTraceString(throwable)}")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val vpnChannel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }

            val tgChannel = NotificationChannel(
                TG_NOTIFICATION_CHANNEL_ID,
                getString(R.string.tg_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(vpnChannel)
            notificationManager.createNotificationChannel(tgChannel)
        }
    }
}
