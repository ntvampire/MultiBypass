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
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

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
                val json = JSONObject(response)

                val tagName = json.optString("tag_name", "")
                val releaseNotes = json.optString("body", "Новый релиз MultiBypass")
                val assets = json.optJSONArray("assets")

                var apkDownloadUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            // Prefer arm64 or universal
                            if (name.contains("arm64") || name.contains("universal") || apkDownloadUrl.isEmpty()) {
                                apkDownloadUrl = asset.optString("browser_download_url", "")
                            }
                        }
                    }
                }

                val currentVersion = BuildConfig.VERSION_NAME
                val isNewer = isVersionNewer(tagName, currentVersion)

                UpdateInfo(
                    tagName = tagName,
                    downloadUrl = apkDownloadUrl,
                    releaseNotes = releaseNotes,
                    isNewer = isNewer
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
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
