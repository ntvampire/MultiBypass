package com.multibypass.app.core.byedpi

import android.content.Context
import android.util.Log
import com.multibypass.app.data.model.StrategyTestResult
import io.github.romanvht.byedpi.core.ByeDpiProxy
import kotlinx.coroutines.*
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
        "-f -1 -e 1 -q 1",
        "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1",
        "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1",
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1",
        "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -a1",
        "-f-200 -Qr -s3:5+sm -a1 -As -d1 -s4+sm -s8+sh -f-300 -d6+sh -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -At,r,s -d1:11+sm -S -a1",
        "-s1 -d3+s -a1 -At -r1+s -a1",
        "-f-1 -t8 -s1+s -a1",
        "-o1 -s4 -s6 -a1",
        "-d1 -s3+s -a1"
    )

    fun loadStrategies(context: Context): List<String> {
        return try {
            context.assets.open("proxytest_strategies.list").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            }.ifEmpty { defaultList }
        } catch (e: Exception) {
            defaultList
        }
    }

    fun loadTestSites(context: Context): List<String> {
        val list = mutableListOf<String>()
        val files = listOf("proxytest_youtube.sites", "proxytest_discord.sites", "proxytest_general.sites")
        for (f in files) {
            try {
                context.assets.open(f).bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .forEach { list.add(if (it.startsWith("http")) it else "https://$it") }
                }
            } catch (_: Exception) {}
        }
        return if (list.isNotEmpty()) list.distinct().take(6) else listOf(
            "https://www.youtube.com/generate_204",
            "https://discord.com",
            "https://www.google.com/generate_204"
        )
    }
}

class StrategyTester(private val context: Context) {
    companion object {
        private const val TAG = "StrategyTester"
        private const val TEST_PORT = 1081
    }

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testResults = MutableStateFlow<List<StrategyTestResult>>(emptyList())
    val testResults: StateFlow<List<StrategyTestResult>> = _testResults.asStateFlow()

    private var testJob: Job? = null

    fun startTest(onComplete: (StrategyTestResult?) -> Unit = {}) {
        if (_isTesting.value) return

        testJob?.cancel()
        testJob = CoroutineScope(Dispatchers.IO).launch {
            _isTesting.value = true
            _testResults.value = emptyList()

            val strategies = PresetStrategies.loadStrategies(context)
            val sites = PresetStrategies.loadTestSites(context)
            val results = mutableListOf<StrategyTestResult>()

            var bestResult: StrategyTestResult? = null

            for (strategy in strategies) {
                if (!isActive) break

                val result = testSingleStrategy(strategy, sites)
                results.add(result)
                _testResults.value = results.sortedByDescending { it.successfulSitesCount }
                    .sortedBy { if (it.isWorking) it.latencyMs else Long.MAX_VALUE }

                if (result.isWorking && (bestResult == null || result.latencyMs < bestResult.latencyMs)) {
                    bestResult = result
                }
            }

            _isTesting.value = false
            onComplete(bestResult)
        }
    }

    fun stopTest() {
        testJob?.cancel()
        testJob = null
        _isTesting.value = false
    }

    private suspend fun testSingleStrategy(strategy: String, sites: List<String>): StrategyTestResult {
        // Start ByeDPI on TEST_PORT
        val proxyArgs = parseArgs("-i 127.0.0.1 -p $TEST_PORT $strategy")
        val proxyJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                ByeDpiProxy.jniStartProxy(proxyArgs)
            } catch (e: Exception) {
                Log.w(TAG, "Proxy start error: ${e.message}")
            }
        }

        delay(300)

        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TEST_PORT)))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        var successCount = 0
        var totalLatency = 0L

        for (site in sites) {
            val startTime = System.currentTimeMillis()
            try {
                val request = Request.Builder().url(site).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful || response.code in 200..399) {
                    val lat = System.currentTimeMillis() - startTime
                    totalLatency += lat
                    successCount++
                }
                response.close()
            } catch (_: Exception) {}
        }

        // Stop test proxy
        try {
            ByeDpiProxy.jniStopProxy()
            proxyJob.cancel()
        } catch (_: Exception) {}

        val isWorking = successCount > 0
        val avgLatency = if (successCount > 0) totalLatency / successCount else 9999L

        return StrategyTestResult(
            strategy = strategy,
            isWorking = isWorking,
            latencyMs = avgLatency,
            testedSitesCount = sites.size,
            successfulSitesCount = successCount
        )
    }

    private fun parseArgs(cmd: String): Array<String> {
        val list = mutableListOf<String>()
        val regex = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")
        regex.findAll(cmd).forEach { match ->
            val value = match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
            if (!value.isNullOrBlank()) list.add(value)
        }
        return list.toTypedArray()
    }
}
