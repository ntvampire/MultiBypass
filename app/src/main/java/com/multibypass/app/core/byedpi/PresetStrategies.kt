package com.multibypass.app.core.byedpi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.multibypass.app.MultiBypassApplication
import com.multibypass.app.R
import com.multibypass.app.core.vpn.MultiBypassVpnService
import com.multibypass.app.core.watchdog.ServiceWatchdog
import com.multibypass.app.data.model.StrategyTestResult
import com.multibypass.app.data.model.VpnStatus
import com.multibypass.app.data.repository.SettingsRepository
import io.github.romanvht.byedpi.core.ByeDpiProxy
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object PresetStrategies {
    val defaultList = listOf(
        "-o1 -r-5+se -a1",
        "-d1 -s3+s -a1",
        "-s1 -d3+s -a1 -At -r1+s -a1",
        "-d7 -s2 -a1",
        "-o3 -d7 -a1",
        "-o1 -s4 -s6 -a1",
        "-o1+s -d3+s -a1",
        "-f -1 -e 1 -q 1",
        "-q1 -r25+s -a1",
        "-f-1 -t8 -s1+s -a1",
        "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
        "--tlsrec 1+s --auto=torst -a1",
        "--tlsrec 1+s --disorder 1 --split 2 --fake-sni google.com --fake-tls-mod orig --drop-sack --auto t,r,s,c",
        "--disorder 1 --split 2 --fake-sni google.com",
        "-d1 -d3+s -s6+s -d9+s -s20+s -d25+s -s30+s -a1",
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-Kt,h -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -At,r,s -d1 -Qr -f-1 -r2",
        "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1"
    )

    val defaultTestTargets = listOf(
        TestTarget("YouTube", "https://www.youtube.com/generate_204"),
        TestTarget("YouTube Video", "https://redirector.googlevideo.com/generate_204"),
        TestTarget("Instagram", "https://www.instagram.com"),
        TestTarget("Facebook", "https://www.facebook.com")
    )

    fun loadStrategies(context: Context): List<String> {
        val assetList = try {
            val assetName = if (context.assets.list("")?.contains("strategies.txt") == true) "strategies.txt" else "proxytest_strategies.list"
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            }.ifEmpty { defaultList }
        } catch (_: Exception) {
            defaultList
        }

        val downloadedFile = java.io.File(context.filesDir, "strategies.txt")
        if (downloadedFile.exists() && downloadedFile.length() > 0) {
            try {
                val list = downloadedFile.bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
                }
                if (list.size >= assetList.size) return list
            } catch (e: Exception) {
                Log.w("PresetStrategies", "Failed to load downloaded strategies.txt: ${e.message}")
            }
        }

        return assetList
    }

    fun loadTestTargets(context: Context): List<TestTarget> {
        return defaultTestTargets
    }
}

data class TestTarget(
    val name: String,
    val url: String
)

class StrategyTester private constructor(private val context: Context) {
    companion object {
        private const val TAG = "StrategyTester"
        private const val TEST_PORT = 1080
        private const val NOTIF_ID_TESTING = 3001

        @Volatile
        private var INSTANCE: StrategyTester? = null

        fun getInstance(context: Context): StrategyTester {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StrategyTester(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val repository = SettingsRepository.getInstance(context)

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testResults = MutableStateFlow<List<StrategyTestResult>>(repository.lastStrategyResults.value)
    val testResults: StateFlow<List<StrategyTestResult>> = _testResults.asStateFlow()

    private var testJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TEST_PORT)))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun startTest(
        targets: List<TestTarget> = PresetStrategies.defaultTestTargets,
        onComplete: (StrategyTestResult?) -> Unit = {}
    ) {
        if (_isTesting.value) return

        testJob?.cancel()
        testJob = CoroutineScope(Dispatchers.IO).launch {
            _isTesting.value = true
            ServiceWatchdog.setTesting(true)
            _testResults.value = emptyList()

            // Acquire WakeLock to keep CPU running when screen turns off
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiBypass:StrategyBenchmarkWakeLock")
            wakeLock?.acquire(15 * 60 * 1000L)

            val notificationManager = NotificationManagerCompat.from(context)
            val hasNotifPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

            fun updateProgressNotification(current: Int, total: Int, bestLatency: Long?) {
                if (!hasNotifPermission) return
                val title = "Автоподбор стратегий TCP ($current/$total)"
                val text = if (bestLatency != null) "Лучшая: $bestLatency мс" else "Идет тестирование стратегий..."
                val notif = NotificationCompat.Builder(context, MultiBypassApplication.UPDATE_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_vpn)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setProgress(total, current, false)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()
                notificationManager.notify(NOTIF_ID_TESTING, notif)
            }

            val wasVpnConnected = MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED
            val originalStrategy = repository.antiDpiConfig.value.strategy
            ByeDpiController.ensureHostsFile(context)

            var bestResult: StrategyTestResult? = null

            try {
                val strategies = PresetStrategies.loadStrategies(context)
                val results = mutableListOf<StrategyTestResult>()

                for ((index, strategy) in strategies.withIndex()) {
                    if (!isActive) break

                    updateProgressNotification(index + 1, strategies.size, bestResult?.latencyMs)

                    val result = testSingleStrategy(strategy, targets)
                    results.add(result)
                    val sorted = results.sortedWith(
                        compareByDescending<StrategyTestResult> { it.isWorking }
                            .thenByDescending { it.successfulSitesCount }
                            .thenBy { it.latencyMs }
                    )
                    _testResults.value = sorted

                    if (result.isWorking && (bestResult == null || result.latencyMs < bestResult.latencyMs)) {
                        bestResult = result
                    }
                }

                if (_testResults.value.isNotEmpty()) {
                    repository.saveStrategyTestResults(_testResults.value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Testing error: ${e.message}", e)
            } finally {
                try {
                    httpClient.dispatcher.cancelAll()
                    httpClient.connectionPool.evictAll()
                } catch (_: Throwable) {}

                if (wasVpnConnected) {
                    val activeStrat = bestResult?.strategy ?: originalStrategy
                    ByeDpiController.start(activeStrat, TEST_PORT)
                } else {
                    ByeDpiController.stop()
                }

                if (wakeLock?.isHeld == true) {
                    try {
                        wakeLock?.release()
                    } catch (_: Exception) {}
                }
                wakeLock = null

                notificationManager.cancel(NOTIF_ID_TESTING)
                ServiceWatchdog.setTesting(false)
                _isTesting.value = false
                onComplete(bestResult)
            }
        }
    }

    fun stopTest() {
        testJob?.cancel()
        testJob = null
        if (_testResults.value.isNotEmpty()) {
            repository.saveStrategyTestResults(_testResults.value)
        }
        val wasVpnConnected = MultiBypassVpnService.vpnStatus.value == VpnStatus.CONNECTED
        val originalStrategy = repository.antiDpiConfig.value.strategy
        CoroutineScope(Dispatchers.IO).launch {
            try {
                httpClient.dispatcher.cancelAll()
                httpClient.connectionPool.evictAll()
            } catch (_: Throwable) {}

            if (wasVpnConnected) {
                ByeDpiController.start(originalStrategy, TEST_PORT)
            } else {
                ByeDpiController.stop()
            }

            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock?.release()
                } catch (_: Exception) {}
            }
            wakeLock = null

            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(NOTIF_ID_TESTING)

            ServiceWatchdog.setTesting(false)
            _isTesting.value = false
        }
    }

    private suspend fun testSingleStrategy(strategy: String, targets: List<TestTarget>): StrategyTestResult {
        val effectiveStrategy = if (strategy.contains("{sni}")) {
            strategy.replace("{sni}", "www.google.com")
        } else {
            strategy
        }

        try {
            ByeDpiController.start(effectiveStrategy, TEST_PORT)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start ByeDpi with strategy: $strategy", t)
            return StrategyTestResult(
                strategy = strategy,
                isWorking = false,
                latencyMs = 9999L,
                testedSitesCount = targets.size,
                successfulSitesCount = 0,
                workingServices = emptyList(),
                serviceLatencies = emptyMap()
            )
        }

        delay(350)
        httpClient.connectionPool.evictAll()

        var successCount = 0
        var totalLatency = 0L
        val workingServices = mutableListOf<String>()
        val serviceLatencies = mutableMapOf<String, Long>()

        for (target in targets) {
            if (!coroutineContext.isActive) break
            val startTime = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(target.url)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                    .header("Accept", "*/*")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful || response.code in 200..499) {
                    val lat = System.currentTimeMillis() - startTime
                    totalLatency += lat
                    successCount++
                    workingServices.add(target.name)
                    serviceLatencies[target.name] = lat
                }
                response.close()
            } catch (_: Throwable) {}
        }

        val isWorking = successCount > 0
        val avgLatency = if (successCount > 0) totalLatency / successCount else 9999L

        return StrategyTestResult(
            strategy = strategy,
            isWorking = isWorking,
            latencyMs = avgLatency,
            testedSitesCount = targets.size,
            successfulSitesCount = successCount,
            workingServices = workingServices,
            serviceLatencies = serviceLatencies
        )
    }

    private fun parseArgs(cmd: String): Array<String> {
        val list = mutableListOf<String>()
        val regex = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")
        regex.findAll(cmd).forEach { match ->
            val value = match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
            if (!value.isNullOrBlank()) list.add(value)
        }
        if (list.isNotEmpty() && list[0] != "ciadpi") {
            list.add(0, "ciadpi")
        }
        return list.toTypedArray()
    }
}
