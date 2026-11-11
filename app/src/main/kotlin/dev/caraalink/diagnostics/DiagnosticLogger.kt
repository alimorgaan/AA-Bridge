package dev.caraalink.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Writes timestamped lines to ONE shared, app-wide log file (no more
 * separate `aa-session-*.log` / `car-session-*.log` / `usb-sniff-*.log`
 * files that needed merging to read side by side) -- every subsystem's
 * [DiagnosticLogger] instance, whatever [tag] it's constructed with, writes
 * into the same rotating current file, each line prefixed with that tag.
 * Purely a diagnostic tool -- nothing it writes is consumed by the app
 * itself.
 */
class DiagnosticLogger(private val context: Context, private val tag: String) {

    fun log(line: String) = write(context, System.currentTimeMillis(), "[$tag] $line")

    /** Logs [bytes] as a classic hex+ASCII dump, [direction] e.g. "RX"/"TX". */
    fun logBytes(direction: String, bytes: ByteArray, length: Int) {
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (i in 0 until length) {
            val b = bytes[i]
            hex.append(String.format("%02X ", b))
            ascii.append(if (b in 0x20..0x7e) b.toInt().toChar() else '.')
        }
        log("$direction ($length bytes): $hex | $ascii")
    }

    fun latestLogFile(): File = flushedCurrentFile(context)

    /** Builds a share-sheet [Intent] for the current log file. */
    fun shareLatestIntent(): Intent = shareFileIntent(latestLogFile())

    fun shareFileIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Copies [file] into the public Downloads collection via MediaStore --
     * no WRITE_EXTERNAL_STORAGE permission needed (scoped storage, API 29+,
     * same as this app's minSdk), and unlike the share-sheet export this
     * lands directly in the Downloads app/folder with nothing further to
     * tap. Returns the saved file's display name, or null on failure.
     */
    fun saveToDownloads(file: File): String? {
        val name = "AABridge-${file.name}"
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            name
        }.getOrNull()
    }

    companion object {
        private const val MAX_BYTES_PER_FILE = 4L * 1024 * 1024 // 4 MiB, then roll over
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

        // One thread owns the file and callers only enqueue, so the AA receive loop
        // never waits on disk. The fields below are touched on that thread only.
        private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "DiagnosticLogger").apply { isDaemon = true }
        }

        @Volatile
        private var currentFileRef: File? = null
        private var out: Writer? = null
        private var bytesWritten = 0L

        private fun logDir(context: Context): File =
            File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }

        private fun currentFile(context: Context): File {
            val existing = currentFileRef
            if (existing != null && out != null && bytesWritten <= MAX_BYTES_PER_FILE) return existing
            out?.close()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(logDir(context), "caraalink-$stamp.log")
            out = BufferedWriter(FileWriter(file, true))
            bytesWritten = file.length()
            return file.also { currentFileRef = it }
        }

        private fun write(context: Context, timeMs: Long, line: String) = writer.execute {
            runCatching {
                currentFile(context)
                val text = "${TIMESTAMP_FORMAT.format(Date(timeMs))}  $line\n"
                out?.write(text)
                bytesWritten += text.length
                if (writer.queue.isEmpty()) out?.flush()
            }
        }

        private fun flushedCurrentFile(context: Context): File =
            runCatching { writer.submit<File> { currentFile(context).also { out?.flush() } }.get(2, TimeUnit.SECONDS) }
                .getOrElse { currentFileRef ?: File(logDir(context), "caraalink.log") }

        /** Deletes every rolled-over log file and starts fresh on the next write. */
        fun clearAll(context: Context) {
            runCatching {
                writer.submit {
                    runCatching { out?.close() }
                    out = null
                    currentFileRef = null
                    logDir(context).listFiles()?.forEach { it.delete() }
                }.get(2, TimeUnit.SECONDS)
            }
        }
    }
}
