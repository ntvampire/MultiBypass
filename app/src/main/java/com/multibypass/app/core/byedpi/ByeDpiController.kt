package com.multibypass.app.core.byedpi

import android.util.Log
import com.multibypass.app.data.model.CORE_ANTIDPI_ROOT_DOMAINS
import com.multibypass.app.data.model.META_ANTIDPI_ROOT_DOMAINS
import com.multibypass.app.data.repository.SettingsRepository
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

    @Volatile
    var metaHostsFilePath: String? = null

    @Volatile
    var generalHostsFilePath: String? = null

    val META_DOMAINS = META_ANTIDPI_ROOT_DOMAINS

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val lock = Mutex()
    private var proxyJob: Job? = null

    fun ensureHostsFile(context: android.content.Context): String? {
        return try {
            val targetFile = java.io.File(context.filesDir, "domains_antidpi_clean.txt")
            val metaFile = java.io.File(context.filesDir, "domains_antidpi_meta.txt")
            val generalFile = java.io.File(context.filesDir, "domains_antidpi_general.txt")

            val srcFile = java.io.File(context.filesDir, "domains_antidpi.txt")
            val content = if (srcFile.exists() && srcFile.length() > 0) {
                srcFile.readText()
            } else {
                context.assets.open("domains_antidpi.txt").bufferedReader().use { it.readText() }
            }
            val parsedLines = content.lines()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            val userDomains = try {
                SettingsRepository.getInstance(context).antiDpiConfig.value.domains
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
            val cleanDomains = (parsedLines + userDomains + CORE_ANTIDPI_ROOT_DOMAINS).distinct()

            targetFile.writeText(cleanDomains.joinToString("\n"))
            hostsFilePath = targetFile.absolutePath

            val metaDomains = cleanDomains.filter { domain ->
                META_DOMAINS.any { domain == it || domain.endsWith(".$it") }
            }
            metaFile.writeText(metaDomains.joinToString("\n"))
            metaHostsFilePath = metaFile.absolutePath

            val generalDomains = cleanDomains.filter { domain ->
                !META_DOMAINS.any { domain == it || domain.endsWith(".$it") }
            }
            generalFile.writeText(generalDomains.joinToString("\n"))
            generalHostsFilePath = generalFile.absolutePath

            Log.i(TAG, "Prepared anti-DPI hosts: total=${cleanDomains.size}, meta=${metaDomains.size}, general=${generalDomains.size}")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare hosts file", e)
            null
        }
    }

    suspend fun start(
        strategy: String = "-s1 -f-1 -t5 -a1",
        instagramStrategy: String = "-s2 -a1",
        separateProfiles: Boolean = true,
        port: Int = DEFAULT_PORT,
        useHosts: Boolean = true
    ) {
        lock.withLock {
            stopLocked()
            delay(150)

            val fullCmd = buildStartCommand(
                strategy = strategy,
                instagramStrategy = instagramStrategy,
                separateProfiles = separateProfiles,
                port = port,
                useHosts = useHosts,
                hostsPath = hostsFilePath,
                metaHostsPath = metaHostsFilePath,
                generalHostsPath = generalHostsFilePath
            )

            val args = parseArgs(fullCmd)

            proxyJob = CoroutineScope(Dispatchers.IO).launch {
                _isRunning.value = true
                Log.i(TAG, "Starting ByeByeDPI with args: $fullCmd")
                val code = ByeDpiProxy.jniStartProxy(args)
                if (code != 0) {
                    Log.e(TAG, "ByeByeDPI exited with non-zero code: $code (cmd: $fullCmd)")
                } else {
                    Log.i(TAG, "ByeByeDPI exited normally with code: 0")
                }
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
            val cleanExit = withTimeoutOrNull(600L) {
                proxyJob?.join()
                true
            }
            if (cleanExit == null) {
                Log.w(TAG, "Proxy job did not terminate cleanly within 600ms, forcing native close")
                try {
                    ByeDpiProxy.jniForceClose()
                } catch (fe: Exception) {
                    Log.e(TAG, "jniForceClose error", fe)
                }
            }
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

    internal fun buildStartCommand(
        strategy: String,
        instagramStrategy: String = "-s2 -a1",
        separateProfiles: Boolean = true,
        port: Int = DEFAULT_PORT,
        useHosts: Boolean = true,
        hostsPath: String? = hostsFilePath,
        metaHostsPath: String? = metaHostsFilePath,
        generalHostsPath: String? = generalHostsFilePath
    ): String {
        fun sanitize(s: String): String {
            val trimmed = s.trim()
            return if (trimmed.contains("{sni}")) {
                trimmed.replace("{sni}", "www.google.com")
            } else {
                trimmed
            }
        }

        return if (useHosts && separateProfiles && !metaHostsPath.isNullOrBlank() && !generalHostsPath.isNullOrBlank()) {
            val sMeta = sanitize(instagramStrategy)
            val sGeneral = sanitize(strategy)
            // -A n switches to the next group strictly by --hosts match without auto-failover triggers
            "ciadpi -i 127.0.0.1 -p $port -J --hosts \"$metaHostsPath\" $sMeta -A n --hosts \"$generalHostsPath\" $sGeneral"
        } else if (useHosts && !hostsPath.isNullOrBlank()) {
            val s = sanitize(strategy)
            "ciadpi -i 127.0.0.1 -p $port -J --hosts \"$hostsPath\" $s"
        } else {
            val s = sanitize(strategy)
            "ciadpi -i 127.0.0.1 -p $port -J $s"
        }
    }

    internal fun parseArgs(cmd: String): Array<String> {
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
