package com.multibypass.app.core.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.core.vpn.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket

object ServiceWatchdog {
    private const val TAG = "ServiceWatchdog"
    private const val CHECK_INTERVAL_MS = 60_000L
    private const val PROBE_TIMEOUT_MS = 400

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val checkMutex = Mutex()

    @Volatile
    private var isInitialized = false
    private var appContext: Context? = null
    private var periodicJob: Job? = null

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val app = context.applicationContext
            appContext = app
            isInitialized = true

            registerNetworkCallback(app)
            registerScreenReceiver(app)
            startPeriodicCheck()

            Log.i(TAG, "ServiceWatchdog initialized successfully")
        }
    }

    fun triggerCheck(delayMs: Long = 0L) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            checkAllServices("manual-or-event-trigger")
        }
    }

    private fun startPeriodicCheck() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkAllServices("periodic-ticker")
            }
        }
    }

    private suspend fun checkAllServices(source: String) {
        val context = appContext ?: return
        val repository = SettingsRepository.getInstance(context)

        if (!repository.watchdogEnabled.value) {
            return
        }

        checkMutex.withLock {
            try {
                checkTelegramProxy(context, repository, source)
                checkVpnComponents(context, repository, source)
            } catch (e: Exception) {
                Log.w(TAG, "Error in ServiceWatchdog check ($source): ${e.message}")
            }
        }
    }

    private suspend fun checkTelegramProxy(context: Context, repository: SettingsRepository, source: String) {
        val tgConfig = repository.tgConfig.value
        if (!tgConfig.enabled) {
            return
        }

        val port = tgConfig.port
        val isListening = isPortListening("127.0.0.1", port, PROBE_TIMEOUT_MS)

        if (!isListening) {
            Log.w(TAG, "Watchdog ($source): TG Proxy on 127.0.0.1:$port is unreachable. Restarting...")
            TelegramProxyService.updateRunningState(false)
            val success = TelegramProxyService.restartProxy(context)
            if (success) {
                delay(400)
                val recovered = isPortListening("127.0.0.1", port, PROBE_TIMEOUT_MS)
                Log.i(TAG, "Watchdog ($source): TG Proxy restart recovered=$recovered")
                TelegramProxyService.updateRunningState(recovered)
            }
        } else {
            if (!TelegramProxyService.isRunning.value) {
                TelegramProxyService.updateRunningState(true)
            }
        }
    }

    private fun checkVpnComponents(context: Context, repository: SettingsRepository, source: String) {
        val vpnConnected = MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED
        if (!vpnConnected) {
            return
        }

        // Check ByeByeDPI port
        val byeDpiPort = ByeDpiController.DEFAULT_PORT
        val byeDpiListening = isPortListening("127.0.0.1", byeDpiPort, PROBE_TIMEOUT_MS)
        if (!byeDpiListening) {
            Log.w(TAG, "Watchdog ($source): ByeByeDPI on port $byeDpiPort is not responding. Restarting...")
            val antiDpiConfig = repository.antiDpiConfig.value
            ByeDpiController.start(strategy = antiDpiConfig.strategy, port = byeDpiPort)
        }
    }

    private fun isPortListening(host: String = "127.0.0.1", port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network became available, triggering watchdog check in 1000ms")
                    triggerCheck(delayMs = 1000L)
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost, triggering watchdog check in 1500ms")
                    triggerCheck(delayMs = 1500L)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // Validated network interface change
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback: ${e.message}")
        }
    }

    private fun registerScreenReceiver(context: Context) {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    Log.d(TAG, "Screen ON / User present detected, checking services")
                    triggerCheck(delayMs = 300L)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen BroadcastReceiver: ${e.message}")
        }
    }
}
