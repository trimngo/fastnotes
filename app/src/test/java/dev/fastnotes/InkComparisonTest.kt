package dev.fastnotes

import android.content.ComponentName
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.diagnostics.InkProbeLog
import dev.fastnotes.diagnostics.InkTestActivity
import dev.fastnotes.diagnostics.InkTestView
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.ink.NativeRefreshPolicy
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
    private fun stroke(view: InkTestView, complete: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val actions = if (complete) listOf(MotionEvent.ACTION_DOWN to 100f, MotionEvent.ACTION_MOVE to 200f, MotionEvent.ACTION_UP to 220f)
            else listOf(MotionEvent.ACTION_DOWN to 100f)
        for ((action, x) in actions) {
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

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun nativeDisplaySurvivesStatusUpdatesRecordsVisualResultsAndStopsOnFocusLossAndResize() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val view = descendants(root).filterIsInstance<InkTestView>().single()
        val start = descendants(root).filterIsInstance<Button>().single { it.text == "Reference callback" }
        val noInk = descendants(root).filterIsInstance<RadioButton>().single { it.text == "No live ink" }
        val copy = descendants(root).filterIsInstance<Button>().single { it.text == "Copy report" }
        fun layout(width: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(600, 0, 600 + width, 800)
            view.viewTreeObserver.dispatchOnPreDraw()
        }
        val fake = FakePanel.getInstance().apply { reset() }
        val probe = NativeBufferProbe(activity, {}) { mode ->
            val policy = if (mode == dev.fastnotes.diagnostics.NativeBufferMode.REFERENCE_CALLBACK) NativeRefreshPolicy.REFERENCE_CALLBACK else NativeRefreshPolicy.WORKER
            ViwoodsPanel(FakePanel::class.java.name, policy) { ManualNativeInkScheduler() }
        }.apply { onStrokeFinished = { view.nativeStrokeFinished() } }
        InkTestActivity::class.java.getDeclaredField("nativeProbe").apply { isAccessible = true }.set(activity, probe)
        try {
            layout(600)
            val before = android.graphics.Rect(view.left, view.top, view.right, view.bottom)
            start.performClick()
            assertTrue(probe.status, probe.active)
            assertEquals(android.graphics.Rect(0, 0, 1200, 800), fake.foregroundBounds)
            assertTrue(view.firmwareTest)
            stroke(view)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            layout(600)
            assertEquals(before, android.graphics.Rect(view.left, view.top, view.right, view.bottom))
            assertTrue(probe.active)
            noInk.performClick()
            copy.performClick()
            val report = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("User observed ink while moving: No live ink"))
            assertTrue(report.contains("Reference callback: 1200x800 at 0,0"))

            lifecycle.windowFocusChanged(false)
            assertFalse(probe.active)
            assertNull(fake.input)
            lifecycle.windowFocusChanged(true)
            assertFalse(probe.active)
            start.performClick()
            assertTrue(probe.active)
            assertFalse(noInk.isChecked)
            layout(360)
            assertFalse(probe.active)
            assertNull(fake.input)
            assertFalse(view.firmwareTest)
            start.performClick()
            assertTrue(probe.active)
            descendants(root).filterIsInstance<Button>().single { it.text == "Updates" }.performClick()
            assertFalse(probe.active)
            assertNull(fake.input)
            assertEquals("dev.fastnotes.updates.UpdateActivity", shadowOf(activity).nextStartedActivity.component!!.className)
            assertNull(app.editor)
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun nativeInkSnapshotsStayFrozenUntilQuietEvenWithoutAndroidPenEvents() {
        val lifecycle = Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val view = InkTestView(lifecycle.get())
        lifecycle.get().setContentView(view)
        view.layout(0, 0, 400, 400)
        var newInk = false
        var down = false
        view.nativeMode({ canvas ->
            canvas.drawColor(Color.WHITE)
            val paint = android.graphics.Paint().apply { color = Color.BLACK }
            canvas.drawRect(100f, 90f, 200f, 110f, paint)
            if (newInk) canvas.drawRect(250f, 90f, 300f, 110f, paint)
            true
        }, { down })
        fun extraPixel(): Int {
            val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image)); return image.getPixel(270, 100)
        }
        view.nativeStrokeFinished()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(Color.WHITE, pixel(view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(Color.BLACK, pixel(view))
        assertEquals(0, view.samples)
        stroke(view, complete = false) // a duplicate Android DOWN with its UP suppressed
        newInk = true; down = true
        view.nativeStrokeFinished()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(Color.WHITE, extraPixel())
        down = false
        view.nativeStrokeFinished()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(Color.BLACK, extraPixel())
        lifecycle.pause().stop().destroy()
    }
}
