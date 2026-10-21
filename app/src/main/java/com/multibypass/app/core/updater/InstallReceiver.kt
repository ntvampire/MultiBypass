package com.multibypass.app.core.updater

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multibypass.app.MultiBypassApplication
import com.multibypass.app.R

class InstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.multibypass.app.ACTION_INSTALL_STATUS"
        const val NOTIF_ID_CONFIRM = 2002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.d(TAG, "Install status received: $status ($message)")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot start confirmation activity directly, showing notification", e)
                        val pi = PendingIntent.getActivity(
                            context,
                            0,
                            confirmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val notif = NotificationCompat.Builder(context, MultiBypassApplication.UPDATE_NOTIFICATION_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_stat_vpn)
                            .setContentTitle(context.getString(R.string.app_name))
                            .setContentText("Нажмите для подтверждения установки обновления")
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true)
                            .setContentIntent(pi)
                            .build()
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        nm?.notify(NOTIF_ID_CONFIRM, notif)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed successfully")
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIF_ID_CONFIRM)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Update was cancelled by user")
            }
            else -> {
                Log.e(TAG, "Package installation failed: $status ($message), triggering fallback")
                AppUpdateManager.fallbackInstall(context)
            }
        }
    }
}
