package com.multibypass.app.core.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multibypass.app.MainActivity
import com.multibypass.app.MultiBypassApplication
import com.multibypass.app.R
import com.multibypass.app.core.byedpi.ByeDpiController
import com.multibypass.app.core.dns.LocalDohServer
import com.multibypass.app.core.tgproxy.TelegramProxyService
import com.multibypass.app.core.watchdog.ServiceWatchdog
import com.multibypass.app.data.model.DnsMode
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.TProxyService
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
    private var dohServer: LocalDohServer? = null
    private val vpnMutex = kotlinx.coroutines.sync.Mutex()
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

                // 1. Start ByeByeDPI daemon on port 1080 and configure QUIC blocking policy
                ByeDpiController.start(strategy = antiDpiConfig.strategy, port = ByeDpiController.DEFAULT_PORT)
                try {
                    ByeDpiProxy.setBlockQuic(antiDpiConfig.blockQuic)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot set QUIC block policy: ${e.message}")
                }
                delay(400)

                // 2. Start Telegram WS Proxy if enabled
                if (tgConfig.enabled && tgConfig.autoStartWithVpn) {
                    TelegramProxyService.start(applicationContext)
                }

                // 3. Configure DNS & Local DoH Server
                val effectiveDns = dnsConfig.getEffectiveDns()
                val fallbackStandardIp = dnsConfig.getEffectiveStandardIp()
                val dnsServer = if (dnsConfig.mode == DnsMode.DOH) {
                    try {
                        dohServer?.stop()
                        dohServer = LocalDohServer(
                            port = 5353,
                            dohUrl = effectiveDns,
                            fallbackStandardIp = fallbackStandardIp
                        ).apply { start() }
                        ByeDpiProxy.setDnsRedirectPort(5353)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting LocalDoHServer: ${e.message}", e)
                    }
                    "10.10.10.1"
                } else {
                    try {
                        dohServer?.stop()
                        dohServer = null
                        ByeDpiProxy.setDnsRedirectPort(0)
                    } catch (_: Exception) {}
                    effectiveDns
                }

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
                    if (repository.bypassLan.value) {
                        addSubnetsExcludingLan(this)
                    } else {
                        addRoute("0.0.0.0", 0)
                    }
                    addDnsServer(dnsServer)

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

                // 4. Create hev-socks5-tunnel config and start tunnel
                val tunConfig = buildString {
                    appendLine("tunnel:")
                    appendLine("  mtu: 8500")
                    appendLine("misc:")
                    appendLine("  task-stack-size: 81920")
                    appendLine("socks5:")
                    appendLine("  address: 127.0.0.1")
                    appendLine("  port: ${ByeDpiController.DEFAULT_PORT}")
                    appendLine("  udp: udp")
                }

                val configFile = File.createTempFile("hev_tun", ".yml", cacheDir).apply {
                    writeText(tunConfig)
                }

                TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)

                _vpnStatus.value = VpnStatus.CONNECTED
                Log.i(TAG, "MultiBypass VPN connected successfully")
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
                try {
                    TProxyService.TProxyStopService()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping TProxy", e)
                }

                delay(100)

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
                    dohServer?.stop()
                    dohServer = null
                    ByeDpiProxy.setDnsRedirectPort(0)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping Local DoH Server", e)
                }

                val repository = SettingsRepository.getInstance(applicationContext)
                if (repository.tgConfig.value.autoStartWithVpn) {
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
                    ByeDpiController.start(strategy = antiDpiConfig.strategy, port = ByeDpiController.DEFAULT_PORT)
                    ServiceWatchdog.triggerCheck(500)
                    Log.i(TAG, "MultiBypass VPN internal sockets reconnected successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during VPN soft reconnect: ${e.message}", e)
                }
            }
        }
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

    private fun addSubnetsExcludingLan(builder: Builder) {
        val publicSubnets = listOf(
            "1.0.0.0" to 8,
            "2.0.0.0" to 7,
            "4.0.0.0" to 6,
            "8.0.0.0" to 7,
            "11.0.0.0" to 8,
            "12.0.0.0" to 6,
            "16.0.0.0" to 4,
            "32.0.0.0" to 3,
            "64.0.0.0" to 2,
            "128.0.0.0" to 3,
            "160.0.0.0" to 5,
            "168.0.0.0" to 6,
            "170.0.0.0" to 7,
            "172.0.0.0" to 12,
            "172.32.0.0" to 11,
            "172.64.0.0" to 10,
            "172.128.0.0" to 9,
            "173.0.0.0" to 8,
            "174.0.0.0" to 7,
            "176.0.0.0" to 4,
            "192.0.0.0" to 9,
            "192.128.0.0" to 11,
            "192.160.0.0" to 13,
            "192.169.0.0" to 16,
            "192.170.0.0" to 15,
            "192.172.0.0" to 14,
            "192.176.0.0" to 12,
            "192.192.0.0" to 10,
            "193.0.0.0" to 8,
            "194.0.0.0" to 7,
            "196.0.0.0" to 6,
            "200.0.0.0" to 5,
            "208.0.0.0" to 4
        )
        for ((ip, prefix) in publicSubnets) {
            try {
                builder.addRoute(ip, prefix)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot add route $ip/$prefix: ${e.message}")
            }
        }
    }
}
