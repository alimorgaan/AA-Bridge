package dev.caraalink.update

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.caraalink.BuildConfig
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for something newer than this install: a tagged Release
 * (Stable channel) or the rolling `dev` pre-release
 * (`.github/workflows/android-ci.yml`'s "Publish rolling dev pre-release"
 * step -- overwritten on every push to `main` with the latest debug APK).
 * Downloads the APK asset itself and hands it to the system installer --
 * previously this just opened the release page and left the user to find
 * and tap the asset themselves.
 */
object UpdateChecker {

    private const val OWNER = "alimorgaan"
    private const val REPO = "AA-Bridge"
    private const val DEV_RELEASE_TAG = "dev"
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 4

    /** [apkUrl] is null if the release has no `.apk` asset attached (nothing to auto-download). */
    private data class UpdateInfo(val title: String, val htmlUrl: String, val apkUrl: String?)

    private fun logger(context: Context) = DiagnosticLogger(context, "update-checker")

    /** Called from MainActivity.onCreate() -- silent unless something newer actually exists. */
    fun checkIfDue(context: Context) {
        if (!AppSettings.updateAutoCheck(context)) return
        checkNow(context, manual = false)
    }

    /** [manual] controls whether a "you're up to date" / error Toast shows; a found update always prompts. */
    fun checkNow(context: Context, manual: Boolean) {
        Thread({
            val result = runCatching {
                when (AppSettings.updateChannel(context)) {
                    AppSettings.UpdateChannel.STABLE -> checkStable()
                    AppSettings.UpdateChannel.DEV -> checkDev()
                }
            }
            val outcome = result.getOrNull()
            logger(context).log("update check (${AppSettings.updateChannel(context)}): ${result.exceptionOrNull() ?: outcome ?: "up to date"}")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when {
                    result.isFailure -> if (manual) toast(context, "Update check failed: ${result.exceptionOrNull()?.message}")
                    outcome != null -> promptUpdate(context, outcome)
                    manual -> toast(context, "Already on the latest ${if (AppSettings.updateChannel(context) == AppSettings.UpdateChannel.DEV) "dev build" else "release"}")
                }
            }
        }, "UpdateChecker").start()
    }

    /**
     * Returns info for a newer tagged release, else null. `/releases/latest`
     * is a hard 404 (not an empty list) when the repo has never had a single
     * Release published -- the owner cuts those manually, so a dev build
     * like this one will hit that until the first one ever exists. That's
     * "nothing to compare against", not a failure -- treated as up to date,
     * no error surfaced.
     */
    private fun checkStable(): UpdateInfo? {
        val body = get("https://api.github.com/repos/$OWNER/$REPO/releases/latest") ?: return null
        val json = JSONObject(body)
        val tag = json.getString("tag_name").removePrefix("v")
        if (tag == BuildConfig.VERSION_NAME) return null
        return UpdateInfo("Update available: v$tag", json.getString("html_url"), apkAssetUrl(json))
    }

    /**
     * Returns info for the rolling `dev` pre-release's build if it's newer
     * than this install, else null. Two calls: the Release itself (for its
     * APK asset + page URL) and the git ref the `dev` tag actually points at
     * (to resolve it to a commit, since the Release API's own JSON doesn't
     * include that). No Release published under that tag yet -> nothing to
     * compare against, same as Stable's no-releases-yet case.
     */
    private fun checkDev(): UpdateInfo? {
        val releaseBody = get("https://api.github.com/repos/$OWNER/$REPO/releases/tags/$DEV_RELEASE_TAG") ?: return null
        val release = JSONObject(releaseBody)
        val refBody = get("https://api.github.com/repos/$OWNER/$REPO/git/ref/tags/$DEV_RELEASE_TAG") ?: return null
        val sha = JSONObject(refBody).getJSONObject("object").getString("sha").take(BuildConfig.GIT_SHA.length)
        if (sha == BuildConfig.GIT_SHA || BuildConfig.GIT_SHA == "unknown") return null
        return UpdateInfo("Dev build updated ($sha)", release.getString("html_url"), apkAssetUrl(release))
    }

    /** First `.apk` asset's direct download URL, or null if the release has none attached. */
    private fun apkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) return asset.getString("browser_download_url")
        }
        return null
    }

    /** null on a 404 (nothing there to compare against); throws on any other non-2xx. */
    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            when (conn.responseCode) {
                in 200..299 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                404 -> null
                else -> error("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /**
     * Shows a popup with a Download button when called while the app is in
     * the foreground (the only caller, MainActivity, always passes itself as
     * an Activity context); also raises a notification covering the case
     * where the check landed while the user wasn't looking at the dialog
     * (e.g. they dismissed it, or the screen was off during the auto-check).
     */
    private fun promptUpdate(context: Context, info: UpdateInfo) {
        notify(context, info)
        val activity = context as? android.app.Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(info.title)
            .setMessage(
                if (info.apkUrl != null) "Download and install now?" else "No APK attached to this release -- opening its GitHub page instead.",
            )
            .setPositiveButton("Download") { _, _ ->
                if (info.apkUrl != null) downloadAndInstall(context, info.apkUrl, info.title) else openPage(context, info.htmlUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openPage(context: Context, url: String) =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /**
     * Downloads [apkUrl] via DownloadManager (so it survives the dialog
     * closing and shows its own progress notification), then on completion
     * either launches the system package installer directly (if this app
     * already has the "install unknown apps" grant) or sends the user to
     * the one-time system settings screen to grant it -- can't be skipped,
     * Android gates this at the OS level regardless of what permission this
     * app declares.
     */
    private fun downloadAndInstall(context: Context, apkUrl: String, title: String) {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        dest.delete()
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(title)
            .setDescription("Downloading AA Bridge update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(dest))
        val manager = context.getSystemService(DownloadManager::class.java)
        val id = manager.enqueue(request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                context.unregisterReceiver(this)
                if (!dest.exists()) {
                    toast(context, "Update download failed")
                    return
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(install)
            }
        }
        // Context.registerReceiver's 3-arg (flags) overload only exists on API
        // 33+ -- this app's minSdk is 29, so that overload doesn't exist to
        // call at all on older devices (not just a flag value question).
        // ContextCompat backports it correctly across the whole range.
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        logger(context).log("downloading update APK from $apkUrl")
    }

    private fun notify(context: Context, info: UpdateInfo) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        // Tapping the notification re-opens the app rather than a browser --
        // MainActivity.onCreate() runs checkIfDue() again, which re-shows the
        // download dialog since the same newer version is still out there.
        val intent = Intent(context, context.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.caraalink.R.drawable.ic_stat_bridge)
            .setContentTitle(info.title)
            .setContentText("Tap to download and install")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
