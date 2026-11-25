package com.multibypass.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.ui.screens.*
import com.multibypass.app.ui.theme.DarkBackground
import com.multibypass.app.ui.theme.MultiBypassTheme

enum class Screen {
    MAIN,
    ANTIDPI_GROUP,
    AUTO_STRATEGY,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val DEST_AUTO_STRATEGY = "auto_strategy"
    }

    private var currentScreen by mutableStateOf(Screen.MAIN)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            MultiBypassVpnService.start(this)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleNavigationIntent(intent)
        checkNotificationPermission()

        setContent {
            MultiBypassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    if (currentScreen != Screen.MAIN) {
                        BackHandler {
                            currentScreen = when (currentScreen) {
                                Screen.AUTO_STRATEGY -> Screen.ANTIDPI_GROUP
                                else -> Screen.MAIN
                            }
                        }
                    }

                    Crossfade(targetState = currentScreen, label = "screen_transition") { screen ->
                        when (screen) {
                            Screen.MAIN -> MainScreen(
                                onNavigateToAntiDpi = { currentScreen = Screen.ANTIDPI_GROUP },
                                onNavigateToAutoStrategy = { currentScreen = Screen.AUTO_STRATEGY },
                                onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                                onRequestVpnPermission = { requestVpnPermission() }
                            )
                            Screen.ANTIDPI_GROUP -> AntiDpiGroupScreen(
                                onNavigateBack = { currentScreen = Screen.MAIN },
                                onNavigateToAutoStrategy = { currentScreen = Screen.AUTO_STRATEGY }
                            )
                            Screen.AUTO_STRATEGY -> AutoStrategyScreen(
                                onNavigateBack = { currentScreen = Screen.ANTIDPI_GROUP }
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                onNavigateBack = { currentScreen = Screen.MAIN }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_NAVIGATE_TO) == DEST_AUTO_STRATEGY) {
            currentScreen = Screen.AUTO_STRATEGY
        }
    }

    override fun onResume() {
        super.onResume()
        com.multibypass.app.core.updater.AppUpdateManager.checkAndInstallPendingApk(this)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            MultiBypassVpnService.start(this)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
