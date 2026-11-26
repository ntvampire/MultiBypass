package com.multibypass.app.core.byedpi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "Остановить" notification action while strategy auto-testing is active.
 */
class StrategyStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == StrategyTester.ACTION_STOP_STRATEGY_TEST) {
            StrategyTester.getInstance(context).stopTest()
        }
    }
}
