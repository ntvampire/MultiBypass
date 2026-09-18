package com.multibypass.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.data.repository.SettingsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val repository = SettingsRepository.getInstance(context)
            if (repository.bootAutoStart.value) {
                MultiBypassVpnService.start(context)
            }
        }
    }
}
