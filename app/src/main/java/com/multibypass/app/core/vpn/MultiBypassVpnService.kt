package com.multibypass.app.core.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multibypass.app.MainActivity
import com.multibypass.app.MultiBypassApplication
import com.multibypass.app.R
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.core.watchdog.ServiceWatchdog
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import io.github.romanvht.byedpi.core.ByeDpiProxy
import hev.htproxy.TProxyService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class MultiBypassVpnService : VpnService() {

    companion object {
        private const val TAG = "MultiBypassVpnService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.multibypass.app.START_VPN"
        const val ACTION_STOP = "com.multibypass.app.STOP_VPN"
        const val ACTION_RECONNECT = "com.multibypass.app.RECONNECT_VPN"

        private val _vpnStatus = MutableStateFlow(VpnStatus.DISCONNECTED)
        val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MultiBypassVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MultiBypassVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, MultiBypassVpnService::class.java).apply {
                action = ACTION_RECONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val vpnMutex = Mutex()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startVpn()
            }
            ACTION_RECONNECT -> {
                reconnectVpn()
            }
            else -> {
                if (_vpnStatus.value == VpnStatus.CONNECTED) {
                    startForeground(NOTIFICATION_ID, createNotification())
                } else {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (_vpnStatus.value == VpnStatus.CONNECTED || _vpnStatus.value == VpnStatus.CONNECTING) {
            return
        }

        _vpnStatus.value = VpnStatus.CONNECTING
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                val repository = SettingsRepository.getInstance(applicationContext)
                val dnsConfig = repository.dnsConfig.value
                val antiDpiConfig = repository.antiDpiConfig.value
                val tgConfig = repository.tgConfig.value

                // 1. Start ByeByeDPI daemon on port 1080 with clean anti-DPI hosts whitelist
                ByeDpiController.ensureHostsFile(applicationContext)
                ByeDpiController.start(strategy = antiDpiConfig.strategy, port = ByeDpiController.DEFAULT_PORT)
                delay(400)
                try {
                    ByeDpiProxy.setDnsRedirectPort(0)
                } catch (_: Exception) {}

                // 2. Start Telegram WS Proxy if enabled
                if (tgConfig.enabled && (tgConfig.autoStartWithVpn || tgConfig.mode == com.multibypass.app.data.model.TelegramMode.TRANSPARENT)) {
                    TelegramProxyService.start(applicationContext)
                }

                // 3. Direct SmartDNS (like OpenWrt / v1.2.1)
                // Real SmartDNS IP (e.g. 92.223.109.31 for Comss, 83.217.211.149 for Zapret)
                // Traffic to port 53 UDP goes directly through SOCKS5 tunnel without local proxy overhead
                val effectiveDns = dnsConfig.getEffectiveStandardIp()

                val routeAll = repository.routeAllApps.value
                val allowedApps = repository.routedApps.value

                val builder = Builder().apply {
                    setSession(getString(R.string.app_name))
                    setConfigureIntent(
                        PendingIntent.getActivity(
                            this@MultiBypassVpnService,
                            0,
                            Intent(this@MultiBypassVpnService, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    addAddress("10.10.10.10", 32)
                    setMtu(1500)
                    setupRoutesWithLanBypass(this)
                    addDnsServer(effectiveDns)

                    allowBypass()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setMetered(false)
                    }

                    // Split-tunnel routing: If specific apps are selected and global mode is disabled, isolate them.
                    // All other apps bypass the VPN at the OS kernel level.
                    if (!routeAll && allowedApps.isNotEmpty()) {
                        for (pkg in allowedApps) {
                            try {
                                addAllowedApplication(pkg)
                            } catch (e: Exception) {
                                Log.w(TAG, "Cannot add allowed app: $pkg", e)
                            }
                        }
                    } else {
                        // Global mode: exclude self to avoid VPN loops
                        try {
                            addDisallowedApplication(packageName)
                        } catch (e: Exception) {
                            Log.w(TAG, "Cannot add disallowed app: $packageName", e)
                        }
                    }
                }

                val fd = builder.establish() ?: throw IllegalStateException("VPN establish() failed")
                tunFd = fd

                // 4. Start Socks5Dispatcher to route Telegram DC traffic to sTGWS and everything else to ByeByeDPI
                Socks5Dispatcher.start(
                    context = applicationContext,
                    listenPort = Socks5Dispatcher.DEFAULT_PORT,
                    downstreamPort = ByeDpiController.DEFAULT_PORT
                )

                // 5. Create hev-socks5-tunnel config pointing to Socks5Dispatcher
                val tunConfig = buildString {
                    appendLine("tunnel:")
                    appendLine("  mtu: 1500")
                    appendLine("misc:")
                    appendLine("  task-stack-size: 81920")
                    appendLine("socks5:")
                    appendLine("  address: 127.0.0.1")
                    appendLine("  port: ${Socks5Dispatcher.DEFAULT_PORT}")
                    appendLine("  udp: udp")
                }

                val configFile = File.createTempFile("hev_tun", ".yml", cacheDir).apply {
                    writeText(tunConfig)
                }

                val started = TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)
                if (!started) {
                    Log.w(TAG, "hev-socks5-tunnel start returned false, tunnel may already be active")
                }

                // 5. Register dynamic underlying network tracking (Wi-Fi <-> LTE seamless migration)
                registerNetworkTracking()

                _vpnStatus.value = VpnStatus.CONNECTED
                Log.i(TAG, "MultiBypass VPN connected successfully (SmartDNS: $effectiveDns:53)")
                ServiceWatchdog.triggerCheck(1000)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN service", e)
                _vpnStatus.value = VpnStatus.FAILED
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        _vpnStatus.value = VpnStatus.DISCONNECTED

        serviceScope.launch {
            vpnMutex.withLock {
                unregisterNetworkTracking()

                try {
                    TProxyService.TProxyStopService()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping TProxy", e)
                }

                delay(100)

                try {
                    Socks5Dispatcher.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping Socks5Dispatcher", e)
                }

                try {
                    tunFd?.close()
                } catch (_: Exception) {}
                tunFd = null

                try {
                    ByeDpiController.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping ByeDPI", e)
                }

                try {
                    ByeDpiProxy.setDnsRedirectPort(0)
                } catch (_: Exception) {}

                val repository = SettingsRepository.getInstance(applicationContext)
                if (repository.tgConfig.value.autoStartWithVpn || repository.tgConfig.value.mode == com.multibypass.app.data.model.TelegramMode.TRANSPARENT) {
                    TelegramProxyService.stop(applicationContext)
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun reconnectVpn() {
        serviceScope.launch {
            if (_vpnStatus.value != VpnStatus.CONNECTED) {
                startVpn()
                return@launch
            }
            Log.i(TAG, "Reconnecting MultiBypass VPN internal sockets...")
            vpnMutex.withLock {
                try {
                    val repository = SettingsRepository.getInstance(applicationContext)
                    val antiDpiConfig = repository.antiDpiConfig.value
                    ByeDpiController.stop()
                    delay(250)
                    ByeDpiController.ensureHostsFile(applicationContext)
                    ByeDpiController.start(strategy = antiDpiConfig.strategy, port = ByeDpiController.DEFAULT_PORT)
                    delay(250)
                    try {
                        ByeDpiProxy.setDnsRedirectPort(0)
                    } catch (_: Exception) {}
                    ServiceWatchdog.triggerCheck(500)
                    Log.i(TAG, "MultiBypass VPN internal sockets reconnected successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during VPN soft reconnect: ${e.message}", e)
                }
            }
        }
    }

    private fun registerNetworkTracking() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Active underlying network changed to: $network")
                    setUnderlyingNetworks(arrayOf(network))
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Active underlying network lost: $network")
                    val active = cm.activeNetwork
                    if (active != null) {
                        setUnderlyingNetworks(arrayOf(active))
                    } else {
                        setUnderlyingNetworks(null)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        setUnderlyingNetworks(arrayOf(network))
                    }
                }
            }
            cm.registerNetworkCallback(request, networkCallback!!)
            val initialNet = cm.activeNetwork
            if (initialNet != null) {
                setUnderlyingNetworks(arrayOf(initialNet))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network tracking: ${e.message}")
        }
    }

    private fun unregisterNetworkTracking() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        connectivityManager = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MultiBypassVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MultiBypassApplication.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_connected))
            .setContentText("DNS маршрутизация и десинхронизация активны")
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.vpn_action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupRoutesWithLanBypass(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            try {
                // Exclude private home/office LANs without excluding 10.0.0.0/8 (which contains the VPN TUN address 10.10.10.10)
                builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("192.168.0.0"), 16))
                builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("172.16.0.0"), 12))
                builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName("169.254.0.0"), 16))
            } catch (e: Exception) {
                Log.w(TAG, "excludeRoute failed, falling back to 0.0.0.0/0", e)
            }
        } else {
            val nonLanSubnets = listOf(
                "1.0.0.0" to 8, "2.0.0.0" to 7, "4.0.0.0" to 6, "8.0.0.0" to 7,
                "11.0.0.0" to 8, "12.0.0.0" to 6, "16.0.0.0" to 4, "32.0.0.0" to 3,
                "64.0.0.0" to 2, "128.0.0.0" to 3, "160.0.0.0" to 5, "168.0.0.0" to 6,
                "170.0.0.0" to 7, "172.0.0.0" to 12, "172.32.0.0" to 11, "172.64.0.0" to 10,
                "172.128.0.0" to 9, "173.0.0.0" to 8, "174.0.0.0" to 7, "176.0.0.0" to 4,
                "192.0.0.0" to 9, "192.128.0.0" to 11, "192.160.0.0" to 13, "192.169.0.0" to 16,
                "192.170.0.0" to 15, "192.172.0.0" to 14, "192.176.0.0" to 12, "192.192.0.0" to 10,
                "193.0.0.0" to 8, "194.0.0.0" to 7, "196.0.0.0" to 6, "200.0.0.0" to 5,
                "208.0.0.0" to 4
            )
            for ((addr, prefix) in nonLanSubnets) {
                try {
                    builder.addRoute(addr, prefix)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add route $addr/$prefix", e)
                }
            }
        }
    }
}
