package dev.fastnotes

import dev.fastnotes.diagnostics.CrashReports
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashReportsTest {
    @Test fun reportSurvivesRestartWithoutExceptionMessagesOrNoteContents() {
        val app = RuntimeEnvironment.getApplication()
        val privateText = "A private handwritten sentence and sheet title"
        CrashReports(app).record(Thread.currentThread(), IllegalStateException(privateText,
            IllegalArgumentException(privateText)))
        val report = CrashReports(app).details()
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("CrashReportsTest"))
        assertFalse(report.contains(privateText))
        assertTrue(report.length < 20_000)
    }

    @Test fun veryDeepStackAndCauseChainsAreBounded() {
        val app = RuntimeEnvironment.getApplication()
        var error: Throwable = IllegalStateException("not logged")
        repeat(50) { error = RuntimeException("not logged", error).apply {
            stackTrace = Array(1000) { StackTraceElement("dev.fastnotes.Example", "operation", "Example.kt", 1) }
        } }
        CrashReports(app).record(Thread.currentThread(), error)
        val report = CrashReports(app).details()
        assertTrue(report.length < 20_000)
        assertFalse(report.contains("not logged"))
    }
}
