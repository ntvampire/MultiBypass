package com.multibypass.app.core.updater

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.multibypass.app.BuildConfig
import com.multibypass.app.MultiBypassApplication
import com.multibypass.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean
)

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "ntvampire/MultiBypass"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases"

    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(RELEASES_API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MultiBypass/${BuildConfig.VERSION_NAME}")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (connection.responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(response)
                val isBetaTrack = BuildConfig.VERSION_NAME.contains("beta", ignoreCase = true)

                var targetRelease: JSONObject? = null
                for (i in 0 until releases.length()) {
                    val rel = releases.optJSONObject(i) ?: continue
                    val isPrerelease = rel.optBoolean("prerelease", false)
                    // If running stable, do not offer beta/prereleases. If running beta, check all releases.
                    if (!isBetaTrack && isPrerelease) continue

                    val tag = rel.optString("tag_name", "")
                    if (isVersionNewer(tag, BuildConfig.VERSION_NAME)) {
                        targetRelease = rel
                        break
                    }
                }

                if (targetRelease == null) {
                    return@withContext null
                }

                val tagName = targetRelease.optString("tag_name", "")
                val releaseNotes = targetRelease.optString("body", "Новый релиз MultiBypass")
                val assets = targetRelease.optJSONArray("assets")

                var apkDownloadUrl = ""
                if (assets != null) {
                    val supportedAbis = android.os.Build.SUPPORTED_ABIS.toList()
                    val isArm64 = supportedAbis.any { it.contains("arm64", ignoreCase = true) }
                    val isArmV7 = supportedAbis.any { it.contains("armeabi", ignoreCase = true) }

                    var arm64Url = ""
                    var armv7Url = ""
                    var universalUrl = ""
                    var fallbackUrl = ""

                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            if (name.contains("arm64", ignoreCase = true)) {
                                arm64Url = downloadUrl
                            } else if (name.contains("v7a", ignoreCase = true) || name.contains("armeabi", ignoreCase = true)) {
                                armv7Url = downloadUrl
                            } else if (name.contains("universal", ignoreCase = true)) {
                                universalUrl = downloadUrl
                            }
                            if (fallbackUrl.isEmpty()) {
                                fallbackUrl = downloadUrl
                            }
                        }
                    }

                    apkDownloadUrl = when {
                        isArm64 && arm64Url.isNotEmpty() -> arm64Url
                        isArmV7 && armv7Url.isNotEmpty() -> armv7Url
                        universalUrl.isNotEmpty() -> universalUrl
                        arm64Url.isNotEmpty() -> arm64Url
                        else -> fallbackUrl
                    }
                }

                UpdateInfo(
                    tagName = tagName,
                    downloadUrl = apkDownloadUrl,
                    releaseNotes = releaseNotes,
                    isNewer = true
                )
            } else {
                Log.w(TAG, "Update check failed with code: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null

    fun downloadAndInstallApk(context: Context, downloadUrl: String) {
        if (downloadJob?.isActive == true) {
            Log.d(TAG, "Download is already running in background")
            return
        }
        val appContext = context.applicationContext
        downloadJob = updateScope.launch {
            downloadAndInstallInternal(appContext, downloadUrl)
        }
    }

    private suspend fun downloadAndInstallInternal(context: Context, downloadUrl: String) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiBypass:AppUpdateWakeLock")
        wakeLock?.acquire(15 * 60 * 1000L)

        val notificationManager = NotificationManagerCompat.from(context)
        val notifId = 1002
        val notifBuilder = NotificationCompat.Builder(context, MultiBypassApplication.UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("MultiBypass Beta")
            .setContentText("Загрузка обновления...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(notifId, notifBuilder.build())
            }

            _downloadProgress.value = 0
            val url = URL(downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            val fileLength = connection.contentLength
            val apkFile = File(context.cacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    var count: Int
                    var lastPercent = -1
                    var lastNotifTime = 0L

                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        total += count
                        if (fileLength > 0) {
                            val percent = ((total * 100) / fileLength).toInt().coerceIn(0, 100)
                            _downloadProgress.value = percent
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent && (percent % 5 == 0 || now - lastNotifTime > 800)) {
                                lastPercent = percent
                                lastNotifTime = now
                                notifBuilder.setProgress(100, percent, false)
                                    .setContentText("Загрузка обновления: $percent%")
                                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                    notificationManager.notify(notifId, notifBuilder.build())
                                }
                            }
                        }
                    }
                }
            }

            _downloadProgress.value = 100
            installApk(context, apkFile, notificationManager, notifId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update APK", e)
            _downloadProgress.value = null
            notificationManager.cancel(notifId)
        } finally {
            connection?.disconnect()
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun installApk(
        context: Context,
        file: File,
        notificationManager: NotificationManagerCompat,
        notifId: Int
    ) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val completeNotif = NotificationCompat.Builder(context, MultiBypassApplication.UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle("MultiBypass Beta")
                .setContentText("Обновление загружено. Нажмите для установки.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(notifId, completeNotif)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }

    private fun isVersionNewer(remoteTag: String, currentVersion: String): Boolean {
        val cleanRemote = remoteTag.removePrefix("v").trim()
        val cleanCurrent = currentVersion.removePrefix("v").trim()

        // Split into base version and prerelease: e.g. "1.1.0-beta.2" -> ("1.1.0", "beta.2")
        val remoteParts = cleanRemote.split("-", limit = 2)
        val currentParts = cleanCurrent.split("-", limit = 2)

        val remoteNums = remoteParts[0].split(".").mapNotNull { it.toIntOrNull() }
        val currentNums = currentParts[0].split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteNums.size, currentNums.size)
        for (i in 0 until maxLen) {
            val r = remoteNums.getOrElse(i) { 0 }
            val c = currentNums.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }

        // Base numbers are equal: check prerelease component
        val remotePre = remoteParts.getOrNull(1)
        val currentPre = currentParts.getOrNull(1)

        // A final/stable release is newer than a prerelease of the same base (e.g. 1.1.0 > 1.1.0-beta.2)
        if (remotePre == null && currentPre != null) return true
        if (remotePre != null && currentPre == null) return false
        if (remotePre != null && currentPre != null) {
            val rPreNums = remotePre.split(".").mapNotNull { it.toIntOrNull() }
            val cPreNums = currentPre.split(".").mapNotNull { it.toIntOrNull() }
            val preMaxLen = maxOf(rPreNums.size, cPreNums.size)
            for (i in 0 until preMaxLen) {
                val r = rPreNums.getOrElse(i) { 0 }
                val c = cPreNums.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        }

        return false
    }
}
