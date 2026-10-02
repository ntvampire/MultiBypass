package com.multibypass.app.core.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.multibypass.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    suspend fun downloadAndInstallApk(context: Context, downloadUrl: String) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            _downloadProgress.value = 0
            val url = URL(downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
            }

            val fileLength = connection.contentLength
            val apkFile = File(context.cacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        total += count
                        if (fileLength > 0) {
                            _downloadProgress.value = ((total * 100) / fileLength).toInt()
                        }
                    }
                }
            }

            _downloadProgress.value = 100
            installApk(context, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update APK", e)
            _downloadProgress.value = null
        } finally {
            connection?.disconnect()
        }
    }

    private fun installApk(context: Context, file: File) {
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
