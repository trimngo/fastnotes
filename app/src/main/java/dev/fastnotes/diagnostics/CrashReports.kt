package dev.fastnotes.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Private, bounded failure evidence. Exception messages and note contents are never recorded. */
class CrashReports(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "last-crash.txt")

    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        val previous = if (current is Reporter) current.previous else current
        Thread.setDefaultUncaughtExceptionHandler(Reporter(this, previous))
    }

    internal fun record(thread: Thread, error: Throwable) {
        runCatching {
            val report = buildString {
                append("Fastnotes ${dev.fastnotes.BuildConfig.VERSION_NAME} · ")
                append(DateFormat.getDateTimeInstance().format(Date())).append('\n')
                append("Thread: ").append(thread.name.take(80)).append('\n')
                var cause: Throwable? = error
                repeat(4) {
                    val failure = cause ?: return@repeat
                    append(failure.javaClass.name).append('\n')
                    failure.stackTrace.take(18).forEach { frame -> append("  at ").append(frame).append('\n') }
                    cause = failure.cause?.takeUnless { it === failure }
                }
            }.take(16 * 1024)
            file.writeText(report)
        }
    }

    fun details(): String {
        val saved = runCatching {
            if (file.isFile && file.length() <= 64 * 1024) file.readText().take(16 * 1024) else null
        }.getOrNull()
        val exits = if (Build.VERSION.SDK_INT >= 30) runCatching {
            app.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(app.packageName, 0, 3).joinToString("\n") { info ->
                    val reason = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "Java crash"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory termination"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "user/system request"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "app exit"
                        ApplicationExitInfo.REASON_SIGNALED -> "signal termination"
                        else -> "reason ${info.reason}"
                    }
                    "Android exit: $reason; status ${info.status}; " +
                        DateFormat.getDateTimeInstance().format(Date(info.timestamp)) +
                        "; peak RSS ${info.rss} KB"
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } else null
        return listOfNotNull(saved?.let { "Last recorded crash:\n$it" }, exits)
            .joinToString("\n").ifBlank { "No previous crash details recorded." }
    }

    private class Reporter(val reports: CrashReports, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            reports.record(thread, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
