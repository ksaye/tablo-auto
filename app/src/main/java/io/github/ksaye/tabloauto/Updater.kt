package io.github.ksaye.tabloauto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** A release that is newer than what is installed. */
data class Update(
    val version: String,
    val tag: String,
    val notes: String?,
    val apkUrl: String,
    val sizeBytes: Long
)

/**
 * Keeps the app up to date from its own release feed.
 *
 * Gitea and GitHub answer `/repos/{owner}/{repo}/releases/latest` with the same shape, so one
 * piece of code serves both: this build points at whichever was configured at build time
 * (see `update.env` and `BuildConfig`). A private Gitea repository needs a token, which is
 * compiled in the way the other household apps do it — a read-only service account that can see
 * releases and nothing else, so that revoking it in Gitea turns updates off without touching any
 * installed app.
 *
 * Checking is only ever done from the phone screen, never from the car: an update prompt while
 * driving would be both useless and dangerous.
 */
object Updater {

    private const val TAG = "TabloUpdate"
    private const val LAST_CHECK = "update-last-check"

    val configured: Boolean
        get() = BuildConfig.UPDATE_OWNER.isNotBlank() && BuildConfig.UPDATE_REPO.isNotBlank()

    /** The newest release, when it is newer than this build. */
    suspend fun check(context: Context, client: OkHttpClient): Update? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        try {
            val url = "${BuildConfig.UPDATE_API_BASE.trimEnd('/')}/repos/" +
                "${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}/releases/latest"
            val request = Request.Builder().url(url).apply {
                if (BuildConfig.UPDATE_TOKEN.isNotBlank()) {
                    header("Authorization", "token ${BuildConfig.UPDATE_TOKEN}")
                }
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Release feed answered ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val tag = json.optString("tag_name")
                val version = versionOf(tag)
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            size = asset.optLong("size")
                            break
                        }
                    }
                }

                context.getSharedPreferences("tablo-auto", Context.MODE_PRIVATE).edit()
                    .putLong(LAST_CHECK, System.currentTimeMillis()).apply()

                if (apkUrl.isNullOrBlank() || !isNewer(version, BuildConfig.VERSION_NAME)) {
                    null
                } else {
                    Update(version, tag, json.optString("body").takeIf { it.isNotBlank() }, apkUrl, size)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Fetch the APK. Returns the file to hand to the installer. */
    suspend fun download(context: Context, client: OkHttpClient, update: Update): File? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(update.apkUrl).apply {
                    if (BuildConfig.UPDATE_TOKEN.isNotBlank()) {
                        header("Authorization", "token ${BuildConfig.UPDATE_TOKEN}")
                    }
                }.build()

                val directory = File(context.filesDir, "updates").apply { mkdirs() }
                // One file, replaced every time: an update that was never installed is of no
                // further interest once a newer one exists.
                val target = File(directory, "update.apk")

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Download answered ${response.code}")
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    target.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                target
            } catch (e: Exception) {
                Log.w(TAG, "Download failed: ${e.message}")
                null
            }
        }

    /**
     * Hand the APK to the system installer.
     *
     * Android will not let an app install a package until the user has said this app may, so the
     * first time through this sends them to that switch instead.
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /** `tabloauto-v1.2.0` or `v1.2.0` → `1.2.0`. */
    private fun versionOf(tag: String): String =
        tag.substringAfterLast('-').removePrefix("v").trim()

    /** Numeric, component by component: 1.10.0 is newer than 1.9.0, which a string compare denies. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val b = installed.split('.').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
