package dev.fastnotes

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.fastnotes.diagnostics.InkProbeLog
import dev.fastnotes.diagnostics.InkTestActivity
import dev.fastnotes.diagnostics.InkTestView
import dev.fastnotes.model.Note
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkComparisonTest {
    private val app get() = RuntimeEnvironment.getApplication() as FastnotesApp
    private fun stroke(view: InkTestView) {
        val down = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 100f, MotionEvent.ACTION_MOVE to 200f, MotionEvent.ACTION_UP to 220f)) {
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 100f; pressure = 1f })
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            view.onTouchEvent(event); event.recycle()
        }
    }
    private fun pixel(view: InkTestView): Int {
        val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        return image.getPixel(150, 100)
    }

    @Test fun firmwareTestHasNoAppDrawnInkUntilTheDocumentedQuietDelay() {
        val lifecycle = Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val view = InkTestView(lifecycle.get())
        lifecycle.get().setContentView(view)
        view.layout(0, 0, 400, 400)
        view.mode(true)
        stroke(view)
        assertEquals(3, view.samples)
        assertEquals(Color.WHITE, pixel(view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(Color.WHITE, pixel(view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(Color.red(pixel(view)) < 80)
        view.mode(false); stroke(view)
        assertTrue(Color.red(pixel(view)) < 80)
        lifecycle.pause().stop().destroy()
    }

    @Test fun diagnosticActivityIsPrivateAndInAnIsolatedProcessAndDoesNotOpenNotes() {
        val note = Note(title = "Saved sheet")
        app.store.save(note)
        val original = app.store.load(note.id).note
        assertNull(app.editor)
        val info = app.packageManager.getActivityInfo(ComponentName(app, InkTestActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals(app.packageName + ":inktest", info.processName)
        val launcher = app.packageManager.getActivityInfo(ComponentName(app, app.packageName + ".InkTestLauncher"), 0)
        assertEquals(app.packageName + ":inktest", launcher.processName)
        assertEquals(InkTestActivity::class.java.name, launcher.targetActivity)
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible()
        assertNull(app.editor)
        lifecycle.pause().stop().destroy()
        assertEquals(original, app.store.load(note.id).note)
    }

    @Test fun nativeCallStagesSurviveReopeningAndRemainBounded() {
        val log = InkProbeLog(app)
        repeat(100) { log.event("Stage $it " + "x".repeat(1000)) }
        val report = InkProbeLog.read(app)
        assertTrue(report.contains("Stage 99"))
        assertFalse(report.contains("Stage 0 "))
        assertTrue(report.length < 25_000)
    }
}
