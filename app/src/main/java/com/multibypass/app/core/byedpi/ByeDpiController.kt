package com.multibypass.app.core.byedpi

import android.util.Log
import io.github.romanvht.byedpi.core.ByeDpiProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ByeDpiController {
    private const val TAG = "ByeDpiController"
    const val DEFAULT_PORT = 1080

    @Volatile
    var hostsFilePath: String? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val lock = Mutex()
    private var proxyJob: Job? = null

    fun ensureHostsFile(context: android.content.Context): String? {
        return try {
            val targetFile = java.io.File(context.filesDir, "domains_antidpi_clean.txt")
            val srcFile = java.io.File(context.filesDir, "domains_antidpi.txt")
            val content = if (srcFile.exists() && srcFile.length() > 0) {
                srcFile.readText()
            } else {
                context.assets.open("domains_antidpi.txt").bufferedReader().use { it.readText() }
            }
            val cleanDomains = content.lines()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            targetFile.writeText(cleanDomains.joinToString("\n"))
            hostsFilePath = targetFile.absolutePath
            Log.i(TAG, "Prepared clean anti-DPI hosts file: ${targetFile.absolutePath} (${cleanDomains.size} domains)")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare hosts file", e)
            null
        }
    }

    suspend fun start(strategy: String = "-o1 -r-5+se -a1", port: Int = DEFAULT_PORT) {
        lock.withLock {
            stopLocked()
            delay(150)

            val hostsArg = if (!hostsFilePath.isNullOrBlank()) " --hosts \"$hostsFilePath\"" else ""
            val fullCmd = "ciadpi -i 127.0.0.1 -p $port $strategy$hostsArg"
            val args = parseArgs(fullCmd)

            proxyJob = CoroutineScope(Dispatchers.IO).launch {
                _isRunning.value = true
                Log.i(TAG, "Starting ByeByeDPI with args: $fullCmd")
                val code = ByeDpiProxy.jniStartProxy(args)
                Log.i(TAG, "ByeByeDPI exited with code: $code")
                _isRunning.value = false
            }
        }
    }

    suspend fun stop() {
        lock.withLock {
            stopLocked()
        }
    }

    fun stopAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            stop()
        }
    }

    private suspend fun stopLocked() {
        try {
            ByeDpiProxy.jniStopProxy()
            proxyJob?.cancel()
            proxyJob?.join()
            proxyJob = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ByeByeDPI", e)
            try {
                ByeDpiProxy.jniForceClose()
            } catch (_: Exception) {}
        } finally {
            _isRunning.value = false
        }
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
