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

    @Volatile
    var metaHostsFilePath: String? = null

    @Volatile
    var generalHostsFilePath: String? = null

    val META_DOMAINS = setOf(
        "instagram.com",
        "cdninstagram.com",
        "i.instagram.com",
        "graph.instagram.com",
        "gateway.instagram.com",
        "facebook.com",
        "fbcdn.net",
        "threads.net",
        "meta.com"
    )

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
            val coreRoots = setOf(
                "googlevideo.com",
                "youtube.com",
                "ytimg.com",
                "ggpht.com",
                "googleusercontent.com",
                "gvt1.com",
                "gvt2.com",
                "youtu.be",
                "instagram.com",
                "cdninstagram.com",
                "facebook.com",
                "fbcdn.net",
                "threads.net",
                "meta.com",
                "discord.com",
                "discord.gg",
                "discordapp.com",
                "discordapp.net",
                "discordcdn.com",
                "discord.media",
                "twitter.com",
                "x.com",
                "twimg.com",
                "t.co"
            )
            val parsedLines = content.lines()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            val cleanDomains = (parsedLines + coreRoots).distinct()

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

            fun sanitize(s: String): String {
                val trimmed = s.trim()
                return if (trimmed.contains("{sni}")) {
                    trimmed.replace("{sni}", "www.google.com")
                } else {
                    trimmed
                }
            }

            val fullCmd = if (useHosts && separateProfiles && !metaHostsFilePath.isNullOrBlank() && !generalHostsFilePath.isNullOrBlank()) {
                val sMeta = sanitize(instagramStrategy)
                val sGeneral = sanitize(strategy)
                "ciadpi -i 127.0.0.1 -p $port -J --hosts \"$metaHostsFilePath\" $sMeta -A --hosts \"$generalHostsFilePath\" $sGeneral"
            } else if (useHosts && !hostsFilePath.isNullOrBlank()) {
                val s = sanitize(strategy)
                "ciadpi -i 127.0.0.1 -p $port -J --hosts \"$hostsFilePath\" $s"
            } else {
                val s = sanitize(strategy)
                "ciadpi -i 127.0.0.1 -p $port -J $s"
            }

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
