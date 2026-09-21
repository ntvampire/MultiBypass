package com.multibypass.app.core.byedpi

import android.util.Log
import io.github.romanvht.byedpi.core.ByeDpiProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ByeDpiController {
    private const val TAG = "ByeDpiController"
    const val DEFAULT_PORT = 1080

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var proxyJob: Job? = null

    fun start(strategy: String = "-f -1 -e 1 -q 1", port: Int = DEFAULT_PORT) {
        if (_isRunning.value) {
            stop()
        }

        val fullCmd = "ciadpi -i 127.0.0.1 -p $port $strategy"
        val args = parseArgs(fullCmd)

        proxyJob = CoroutineScope(Dispatchers.IO).launch {
            _isRunning.value = true
            Log.i(TAG, "Starting ByeByeDPI with args: $fullCmd")
            val code = ByeDpiProxy.jniStartProxy(args)
            Log.i(TAG, "ByeByeDPI exited with code: $code")
            _isRunning.value = false
        }
    }

    fun stop() {
        try {
            ByeDpiProxy.jniStopProxy()
            proxyJob?.cancel()
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
