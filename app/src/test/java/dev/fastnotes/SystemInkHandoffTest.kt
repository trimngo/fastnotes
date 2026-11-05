package dev.fastnotes

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.fastnotes.diagnostics.InkTestView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemInkHandoffTest {
    private lateinit var lifecycle: ActivityController<Activity>
    private lateinit var paper: InkTestView
    private val properties = arrayOf(MotionEvent.PointerProperties().apply {
        id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS
    })

    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        paper = InkTestView(lifecycle.get())
        lifecycle.get().setContentView(paper)
        paper.layout(0, 0, 400, 400)
        paper.systemMode()
    }

    @After fun cleanup() { lifecycle.pause().stop().destroy() }

    private fun coordinates(x: Float, y: Float, pressure: Float) = arrayOf(
        MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure }
    )

    private fun event(action: Int, x: Float, y: Float, pressure: Float = 1f): MotionEvent {
        val time = SystemClock.uptimeMillis()
        return MotionEvent.obtain(time, time, action, 1, properties, coordinates(x, y, pressure),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun send(action: Int, x: Float, y: Float, pressure: Float = 1f) {
        event(action, x, y, pressure).let { paper.onTouchEvent(it); it.recycle() }
    }

    private fun stroke(y: Float, pressure: Float = 1f) {
        send(MotionEvent.ACTION_DOWN, 100f, y, pressure)
        send(MotionEvent.ACTION_MOVE, 220f, y, pressure)
        send(MotionEvent.ACTION_UP, 250f, y, pressure)
    }

    private fun render(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).also {
        // Explicitly force redraws even when the view did not request one.
        paper.draw(Canvas(it))
    }

    private fun black(image: Bitmap, x: Int, y: Int) = Color.red(image.getPixel(x, y)) < 80

    @Test fun livePathStaysHiddenButCompletedInkAppearsAtPenUpAndOutlastsOverlayClear() {
        var finished = 0
        paper.onStrokeFinished = { finished++ }
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        send(MotionEvent.ACTION_MOVE, 220f, 100f)
        render().let { assertFalse(black(it, 150, 100)); it.recycle() }
        assertEquals(0, paper.completedStrokes)
        assertEquals(0, finished)
        send(MotionEvent.ACTION_UP, 250f, 100f)
        assertEquals(1, paper.completedStrokes)
        assertEquals(1, finished)
        render().let { assertTrue(black(it, 150, 100)); it.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        render().let { assertTrue(black(it, 150, 100)); it.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        render().let { assertTrue(black(it, 150, 100)); it.recycle() }
        assertEquals(1, finished) // No delayed completion callback can re-commit it.
    }

    @Test fun rapidNextStrokeAndCancellationRetainPriorInkWithoutExposingTheCurrentPath() {
        stroke(100f)
        send(MotionEvent.ACTION_DOWN, 100f, 200f)
        send(MotionEvent.ACTION_MOVE, 220f, 200f)
        render().let {
            assertTrue(black(it, 150, 100))
            assertFalse(black(it, 150, 200))
            it.recycle()
        }
        send(MotionEvent.ACTION_CANCEL, 220f, 200f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        render().let {
            assertTrue(black(it, 150, 100))
            assertFalse(black(it, 150, 200))
            it.recycle()
        }
        assertEquals(1, paper.completedStrokes)
        stroke(250f, 0.2f)
        render().let {
            assertTrue(black(it, 150, 100))
            assertTrue(black(it, 150, 250))
            assertFalse(black(it, 150, 200))
            it.recycle()
        }
        assertEquals(2, paper.completedStrokes)
    }

    @Test fun historicalPressurePeakSurvivesReplayAndLaterStrokesCannotChangeItsWidth() {
        send(MotionEvent.ACTION_DOWN, 100f, 100f, 0f)
        val move = event(MotionEvent.ACTION_MOVE, 200f, 100f, 1f)
        move.addBatch(SystemClock.uptimeMillis() + 1, coordinates(300f, 100f, 0f), 0)
        assertEquals(1, move.historySize)
        paper.onTouchEvent(move); move.recycle()
        send(MotionEvent.ACTION_UP, 320f, 100f, 0f)
        val before = render()
        assertFalse(black(before, 110, 103))
        assertTrue(black(before, 200, 102)) // Peak existed only in the batched historical sample.
        assertTrue(paper.pressureSummary.contains("4 normalized samples"))
        assertTrue(paper.pressureSummary.contains("0.000–1.000"))
        stroke(200f, 0.05f)
        val after = render()
        for (y in 90..110) for (x in 90..330) assertEquals(before.getPixel(x, y), after.getPixel(x, y))
        before.recycle(); after.recycle()
    }

    @Test fun nonfiniteAndOutOfRangePressureNeverCreatesUnboundedInk() {
        val pressures = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -10f, 10f)
        pressures.forEachIndexed { index, pressure ->
            val y = 70f + index * 55f
            stroke(y, pressure)
        }
        val image = render()
        pressures.indices.forEach { index ->
            val y = 70 + index * 55
            assertTrue(black(image, 150, y))
            assertFalse(black(image, 150, y - 6))
            assertFalse(black(image, 150, y + 6))
        }
        assertFalse(paper.pressureSummary.contains("NaN"))
        assertFalse(paper.pressureSummary.contains("Infinity"))
        assertTrue(paper.pressureSummary.contains("0.000–1.000"))
        assertEquals(pressures.size, paper.completedStrokes)
        image.recycle()
    }

    @Test fun outOfBoundsSamplesBreakDeferredPressureGeometryInsteadOfBridgingThePage() {
        stroke(70f)
        send(MotionEvent.ACTION_DOWN, 100f, 150f, 0.2f)
        send(MotionEvent.ACTION_MOVE, 160f, 150f, 1f)
        send(MotionEvent.ACTION_MOVE, -20f, 190f, 1f)
        send(MotionEvent.ACTION_MOVE, 240f, 210f, 1f)
        send(MotionEvent.ACTION_MOVE, 280f, 210f, 0.2f)
        render().let {
            assertTrue(black(it, 150, 70))
            assertFalse(black(it, 130, 150))
            assertFalse(black(it, 260, 210))
            it.recycle()
        }
        send(MotionEvent.ACTION_UP, 320f, 210f, 0.2f)
        render().let {
            assertTrue(black(it, 130, 150))
            assertTrue(black(it, 260, 210))
            assertFalse(black(it, 200, 180))
            assertTrue(black(it, 150, 70))
            it.recycle()
        }
        assertEquals(2, paper.completedStrokes)
    }

    @Test fun focusPauseDiscardsUnfinishedPressureCaptureBeforeAnotherStrokeCompletes() {
        stroke(100f)
        send(MotionEvent.ACTION_DOWN, 100f, 200f)
        send(MotionEvent.ACTION_MOVE, 220f, 200f)
        paper.pauseCurrentSystemStroke()
        send(MotionEvent.ACTION_UP, 250f, 200f)
        stroke(250f)
        render().let {
            assertTrue(black(it, 150, 100))
            assertFalse(black(it, 150, 200))
            assertTrue(black(it, 150, 250))
            it.recycle()
        }
        assertEquals(2, paper.completedStrokes)
    }
}
