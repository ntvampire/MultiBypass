package com.multibypass.app.core.tgproxy

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.multibypass.app.MainActivity
import com.multibypass.app.MultiBypassApplication
import com.multibypass.app.R
import com.multibypass.app.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class TelegramProxyService : Service() {

    companion object {
        private const val TAG = "TelegramProxyService"
        private const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.multibypass.app.START_TG_PROXY"
        const val ACTION_STOP = "com.multibypass.app.STOP_TG_PROXY"
        const val ACTION_RESTART = "com.multibypass.app.RESTART_TG_PROXY"

        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 25L * 60 * 1000
        private const val STATS_UPDATE_MS = 3_000L
        private const val NATIVE_STOP_WAIT_MS = 3_000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isVerifiedRunning = MutableStateFlow(false)
        val isVerifiedRunning: StateFlow<Boolean> = _isVerifiedRunning.asStateFlow()

        @Volatile
        private var instance: TelegramProxyService? = null

        fun start(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        suspend fun restartProxy(context: Context): Boolean {
            val current = instance
            return if (current != null && _isRunning.value) {
                current.restartNativeProxy()
            } else {
                start(context)
                true
            }
        }

        internal fun updateRunningState(running: Boolean) {
            _isRunning.value = running
            if (!running) {
                _isVerifiedRunning.value = false
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private var statsJob: Job? = null
    @Volatile
    private var stopInProgress = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val repository = SettingsRepository.getInstance(applicationContext)
                repository.updateTgConfig(repository.tgConfig.value.copy(enabled = false))
                stopProxyInternal()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                serviceScope.launch { restartNativeProxy() }
                return START_STICKY
            }
            else -> {
                val repository = SettingsRepository.getInstance(applicationContext)
                if (!repository.tgConfig.value.enabled) {
                    Log.i(TAG, "TG Proxy is disabled in settings, stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProxyInternal()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun isPortAvailable(bindIp: String, port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(bindIp), port))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startProxyInternal() {
        if (_isRunning.value || stopInProgress) return
        stopInProgress = false

        val initialNotification = createNotification("Запуск MTProto прокси...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        acquireWakeLock()

        Thread({
            val repository = SettingsRepository.getInstance(applicationContext)
            val port = repository.tgConfig.value.port
            val secret = repository.tgConfig.value.secret

            if (!isPortAvailable("127.0.0.1", port)) {
                Log.w(TAG, "Port $port is not available")
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@TelegramProxyService, "Порт $port занят другим процессом", Toast.LENGTH_SHORT).show()
                    stopProxyInternal()
                }
                return@Thread
            }

            try {
                ensureCfDomainsCache()
                NativeTgProxy.setPoolSize(4)
                NativeTgProxy.setBufferSizeKb(256)
                NativeTgProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                NativeTgProxy.setCfProxyConfig(enabled = true, priority = true, userDomain = "")
                NativeTgProxy.setDisableSecure(true) // Port 80 fallback for mobile networks

                val res = NativeTgProxy.startProxy(
                    host = "127.0.0.1",
                    port = port,
                    dcIps = "",
                    secret = secret,
                    verbose = true
                )

                if (res == 0) {
                    serviceScope.launch {
                        if (!stopInProgress) {
                            updateRunningState(true)
                            _isVerifiedRunning.value = true
                            updateNotification("Локальный MTProto прокси активен (127.0.0.1:$port)")
                            Log.i(TAG, "Telegram WS Proxy started on 127.0.0.1:$port with secret $secret")
                            startStatsUpdater()
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to start Telegram WS Proxy: code $res")
                    serviceScope.launch {
                        updateRunningState(false)
                        stopProxyInternal()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error starting Telegram WS Proxy", e)
                serviceScope.launch {
                    updateRunningState(false)
                    stopProxyInternal()
                }
            }
        }, "TelegramProxyStart").apply {
            isDaemon = true
            start()
        }
    }

    private fun startStatsUpdater() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            // Wakelock refresh sub-job
            launch {
                while (isActive) {
                    delay(WAKELOCK_REFRESH_MS)
                    refreshWakeLock()
                }
            }

            while (isActive && _isRunning.value && !stopInProgress) {
                delay(STATS_UPDATE_MS)
                try {
                    val rawStats = NativeTgProxy.getStats()
                    if (!rawStats.isNullOrBlank()) {
                        val active = extractStat(rawStats, "active=")
                        val up = extractStat(rawStats, "up=")
                        val down = extractStat(rawStats, "down=")
                        val text = "127.0.0.1:1443 • Подключений: $active • ↓$down ↑$up"
                        updateNotification(text)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Stats update error: ${e.message}")
                }
            }
        }
    }

    private fun extractStat(stats: String, key: String): String {
        val idx = stats.indexOf(key)
        if (idx == -1) return "0"
        val start = idx + key.length
        val end = stats.indexOf(" ", start)
        return if (end == -1) stats.substring(start) else stats.substring(start, end)
    }

    private fun updateNotification(content: String) {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.notify(NOTIFICATION_ID, createNotification(content))
        } catch (_: Exception) {}
    }

    suspend fun restartNativeProxy(): Boolean = mutex.withLock {
        Log.i(TAG, "Restarting Telegram WS Proxy native instance...")
        statsJob?.cancel()
        statsJob = null
        requestNativeStop("restart")
        delay(350)
        return try {
            val repository = SettingsRepository.getInstance(applicationContext)
            val port = repository.tgConfig.value.port
            val secret = repository.tgConfig.value.secret

            ensureCfDomainsCache()
            NativeTgProxy.setPoolSize(4)
            NativeTgProxy.setBufferSizeKb(256)
            NativeTgProxy.setCfProxyCacheDir(cacheDir.absolutePath)
            NativeTgProxy.setCfProxyConfig(enabled = true, priority = true, userDomain = "")
            NativeTgProxy.setDisableSecure(true)

            val res = NativeTgProxy.startProxy(
                host = "127.0.0.1",
                port = port,
                dcIps = "",
                secret = secret,
                verbose = true
            )
            val success = (res == 0)
            updateRunningState(success)
            _isVerifiedRunning.value = success
            if (success) {
                startStatsUpdater()
            }
            Log.i(TAG, "Telegram WS Proxy restart result: $res, success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception restarting Telegram WS Proxy", e)
            updateRunningState(false)
            false
        }
    }

    private fun stopProxyInternal() {
        if (stopInProgress) return
        stopInProgress = true
        statsJob?.cancel()
        statsJob = null

        serviceScope.launch {
            requestNativeStop("stop")
            releaseWakeLock()
            updateRunningState(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private suspend fun requestNativeStop(reason: String): Boolean {
        val completed = CompletableDeferred<Unit>()
        Thread({
            try {
                NativeTgProxy.stopProxy()
            } catch (e: Exception) {
                Log.w(TAG, "StopProxy failed during $reason", e)
            } finally {
                completed.complete(Unit)
            }
        }, "ProxyStop-$reason").apply {
            isDaemon = true
            start()
        }

        return withTimeoutOrNull(NATIVE_STOP_WAIT_MS) {
            completed.await()
            true
        } ?: false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (_isRunning.value) {
            Log.i(TAG, "onTaskRemoved: Telegram proxy stays active")
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MultiBypass::TgProxyWakeLock"
            ).apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MultiBypass::TgProxyWakeLock"
            ).apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        instance = null
        statsJob?.cancel()
        statsJob = null
        releaseWakeLock()
        try {
            NativeTgProxy.stopProxy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Telegram WS Proxy", e)
        } finally {
            updateRunningState(false)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TelegramProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, TelegramProxyService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MultiBypassApplication.TG_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Telegram WS Proxy")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(pendingIntent)
            .addAction(0, "Перезапустить", restartIntent)
            .addAction(0, getString(R.string.vpn_action_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureCfDomainsCache() {
        try {
            val cacheFile = File(cacheDir, "cfproxy-domains-cache.txt")
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                assets.open("cfproxy_domains.txt").use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Pre-seeded cfproxy-domains-cache.txt with Flowseal encoded domain pool")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure CF domains cache: ${e.message}")
        }
    }
}

object TelegramProxyController {

    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "com.radolyn.ayugram",
        "com.exteragram.messenger",
        "org.telegram.plus",
        "ir.ilmili.telegraph",
        "org.telegram.BifToGram",
        "tw.nekomimi.nekogram",
        "xyz.nextalone.nagram",
        "uz.unnarsx.cherrygram",
        "org.telegram.mdgram",
        "org.forkclient.messenger.beta",
        "app.nicegram",
        "top.qwq2333.nullgram",
        "com.iMe.android",
        "ru.dahl.messenger",
        "com.scriptsaz.litegram",
        "org.thunderdog.challegram"
    )

    fun openTelegramProxy(context: Context, port: Int = 1443) {
        val repository = SettingsRepository.getInstance(context)
        if (!TelegramProxyService.isRunning.value) {
            repository.updateTgConfig(repository.tgConfig.value.copy(enabled = true))
            TelegramProxyService.start(context)
        }

        val rawSecret = repository.tgConfig.value.secret.trim()
        val nativeSecret = try { NativeTgProxy.getSecretWithPrefix() } catch (_: Throwable) { null }
        val secret = if (!nativeSecret.isNullOrBlank() && !nativeSecret.contains("00000000000000000000000000000000")) {
            nativeSecret
        } else {
            "dd$rawSecret"
        }

        val proxyHttpUrl = "https://t.me/proxy?server=127.0.0.1&port=$port&secret=$secret"
        val proxyTgUri = "tg://proxy?server=127.0.0.1&port=$port&secret=$secret"

        // Copy proxy link to clipboard
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Telegram Proxy", proxyTgUri))
            Toast.makeText(context, "Настройки скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}

        applyToTelegramPackages(context, proxyTgUri, proxyHttpUrl)
    }

    private fun applyToTelegramPackages(context: Context, tgUri: String, httpUrl: String) {
        val pm = context.packageManager
        val availablePackages = TELEGRAM_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        if (availablePackages.isEmpty()) {
            // General fallback
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tgUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(httpUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (e: Exception) {
                    Log.w("TelegramProxyController", "Failed to open proxy URL: ${e.message}")
                }
            }
            return
        }

        val targetedIntents = availablePackages.map { pkg ->
            Intent(Intent.ACTION_VIEW, Uri.parse(tgUri)).apply {
                setPackage(pkg)
            }
        }

        if (targetedIntents.size == 1) {
            val intent = targetedIntents.first().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w("TelegramProxyController", "Failed to open single Telegram: ${e.message}")
            }
        } else {
            val chooserIntent = Intent.createChooser(targetedIntents.first(), "Выберите клиент Telegram").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedIntents.drop(1).toTypedArray())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(chooserIntent)
            } catch (e: Exception) {
                Log.w("TelegramProxyController", "Failed to open Telegram chooser: ${e.message}")
            }
        }
    }
}
