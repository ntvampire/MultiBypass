package com.multibypass.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
    DNS_GROUP,
    ANTIDPI_GROUP,
    AUTO_STRATEGY,
    SETTINGS
}

class MainActivity : ComponentActivity() {

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

        checkNotificationPermission()

        setContent {
            MultiBypassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    var currentScreen by remember { mutableStateOf(Screen.MAIN) }

                    Crossfade(targetState = currentScreen, label = "screen_transition") { screen ->
                        when (screen) {
                            Screen.MAIN -> MainScreen(
                                onNavigateToDns = { currentScreen = Screen.DNS_GROUP },
                                onNavigateToAntiDpi = { currentScreen = Screen.ANTIDPI_GROUP },
                                onNavigateToAutoStrategy = { currentScreen = Screen.AUTO_STRATEGY },
                                onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                                onRequestVpnPermission = { requestVpnPermission() }
                            )
                            Screen.DNS_GROUP -> DnsGroupScreen(
                                onNavigateBack = { currentScreen = Screen.MAIN }
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
