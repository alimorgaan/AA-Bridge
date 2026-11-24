package dev.caraalink

import android.app.Application
import android.os.Process
import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Starts a persistent, in-app logger the moment the process starts, so
 * diagnostic data stops depending on a live adb/wireless-debugging
 * connection staying up (that connection has repeatedly died mid-test this
 * session -- see claude/roadmap.md). Captures this process's own logcat
 * output, crashes included, to a rotating on-device file via
 * [DiagnosticLogger]. For now this overlaps with the USB sniffer's own
 * logging (both land in the same logs/ directory) rather than being fully
 * separate -- that's deliberate for now, not a final design.
 */
class CarAALinkApplication : Application() {

    private val logger by lazy { DiagnosticLogger(this, "app") }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        startSelfLogcatCapture()
        dev.caraalink.settings.AppSettings.applyFastVideoDefaults(this)
        // Carry a previous crash's full trace into THIS launch's own log
        // file (a new file per launch -- see DiagnosticLogger.currentFile())
        // rather than leaving it stuck in whatever old file was open when it
        // happened. Doesn't clear the marker -- MainActivity's
        // consumeLastCrash() still does that for the one-time Toast.
        dev.caraalink.diagnostics.CrashMarker.peekLastCrashTrace(this)?.let { trace ->
            logger.log("Previous run crashed -- carried forward from the crash marker:\n$trace")
        }
        // Confirmed necessary live: a log once showed behavior that didn't
        // match the current source at all, with no way to tell from the log
        // itself whether that was a real regression or just an install that
        // silently didn't take (older APK still running). Stamping the
        // exact build identity at startup removes that ambiguity for good.
        logger.log("CarAALinkApplication.onCreate build=${dev.caraalink.BuildConfig.GIT_SHA} versionName=${dev.caraalink.BuildConfig.VERSION_NAME}")
        val androidAuto = runCatching {
            packageManager.getPackageInfo("com.google.android.projection.gearhead", 0).let { "${it.versionName} (${it.longVersionCode})" }
        }.getOrDefault("not installed")
        logger.log("phone=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE} " +
            "sdk=${android.os.Build.VERSION.SDK_INT} androidAuto=$androidAuto")
    }

    /**
     * Beyond logging the crash (which was already happening), this now also
     * leaves a one-shot marker so the *next* launch can tell the user it
     * crashed last time instead of that only being visible by digging
     * through an exported log (see [dev.caraalink.diagnostics.CrashMarker]).
     * Purely local -- no network upload, there's no backend to send it to.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logger.log("FATAL on ${thread.name}: ${Log.getStackTraceString(throwable)}")
            }
            runCatching { dev.caraalink.diagnostics.CrashMarker.recordCrash(this, thread.name, throwable) }
            // Owner asked for this directly: a crash log saved automatically,
            // not something to go dig out of Developer tools after the fact.
            // Runs synchronously here, before the default handler terminates
            // the process -- a single small file copy comfortably finishes
            // in that window.
            runCatching {
                if (dev.caraalink.settings.AppSettings.autoSaveCrashLog(this)) {
                    logger.saveToDownloads(logger.latestLogFile())
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Apps can read their own process's logcat output without any special
     * permission -- logd scopes what a non-system UID sees to itself. The
     * explicit --pid makes that intent clear rather than relying on it.
     */
    private fun startSelfLogcatCapture() {
        Thread({
            try {
                val pid = Process.myPid().toString()
                val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid", pid)
                    .redirectErrorStream(true)
                    .start()
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        logger.log(line)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Self-logcat capture failed to start", e)
            }
        }, "SelfLogcatCapture").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "CarAALinkApplication"
    }
}
