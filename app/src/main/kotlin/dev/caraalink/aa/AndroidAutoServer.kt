package dev.caraalink.aa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.caraalink.R
import dev.caraalink.link.CarSession
import dev.caraalink.video.CarScreen

/** Android Auto's developer head unit server: detection, opening its settings, and the "needs a tap" notification. */
object AndroidAutoServer {
    const val PORT = 5277
    private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private const val CHANNEL_ID = "aa_needs_tap"
    private const val NOTIFICATION_ID = 3

    fun isRunning(): Boolean = CarSession.isListening(PORT)

    /** Only a waiting car reopens Android Auto by itself; Closed waits for the driver. */
    fun shouldAutoOpen(screen: CarScreen, serverRunning: Boolean): Boolean =
        serverRunning && (screen == CarScreen.WAITING || screen == CarScreen.NOT_RESPONDING)

    /** Android Auto's settings deep link first, then its settings activity, launcher, and app info. */
    fun settingsIntent(context: Context): Intent? = listOfNotNull(
        Intent("com.google.android.projection.gearhead.SETTINGS").setPackage(ANDROID_AUTO_PACKAGE),
        Intent().setClassName(
            ANDROID_AUTO_PACKAGE,
            "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity",
        ),
        context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO_PACKAGE")),
    ).firstOrNull { it.resolveActivity(context.packageManager) != null }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(context: Context): Boolean {
        val intent = settingsIntent(context) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun notifyNeedsTap(context: Context, notResponding: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = settingsIntent(context)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val title = if (notResponding) R.string.notif_not_responding_title else R.string.notif_needs_tap_title
        val text = if (notResponding) R.string.notif_not_responding_text else R.string.notif_needs_tap_text
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply {
                if (open != null) {
                    setContentIntent(open)
                    addAction(0, context.getString(R.string.notif_open_aa), open)
                }
            }
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancelNeedsTap(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
