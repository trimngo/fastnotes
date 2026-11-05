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
class SystemInkIdleTest {
    private lateinit var lifecycle: ActivityController<Activity>
    private lateinit var paper: InkTestView

    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        paper = InkTestView(lifecycle.get())
        lifecycle.get().setContentView(paper)
        paper.layout(0, 0, 400, 400)
        paper.systemMode()
        paper.setSystemRegistration(true)
    }

    @After fun cleanup() { lifecycle.pause().stop().destroy() }

    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun coordinates(x: Float, y: Float, pressure: Float) = arrayOf(
        MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure }
    )
    private fun event(action: Int, x: Float, y: Float, pressure: Float = 0.5f,
                      tool: Int = MotionEvent.TOOL_TYPE_STYLUS,
                      time: Long = SystemClock.uptimeMillis()): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = tool })
        return MotionEvent.obtain(time, time, action, 1, properties, coordinates(x, y, pressure),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }
    private fun send(action: Int, x: Float, y: Float, pressure: Float = 0.5f) {
        event(action, x, y, pressure).let { paper.onTouchEvent(it); it.recycle() }
    }
    private fun hover(action: Int = MotionEvent.ACTION_HOVER_MOVE,
                      tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        event(action, 200f, 150f, 0f, tool).let { paper.onHoverEvent(it); it.recycle() }
    }
    private fun stroke(y: Float = 100f) {
        send(MotionEvent.ACTION_DOWN, 100f, y)
        send(MotionEvent.ACTION_MOVE, 220f, y)
        send(MotionEvent.ACTION_UP, 250f, y)
    }
    private fun render(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).also {
        paper.draw(Canvas(it))
    }
    private fun pixels(image: Bitmap) = IntArray(400 * 400).also { image.getPixels(it, 0, 400, 0, 0, 400, 400) }

    @Test fun preparationNeedsIdleRegisteredSystemInkAndRunsOncePerPause() {
        advance(1500)
        hover(MotionEvent.ACTION_HOVER_ENTER)
        assertEquals(0L, paper.hoverPreparationRequests) // A fresh session has no completed pen-up.
        stroke()
        advance(999)
        hover()
        assertEquals(0L, paper.hoverPreparationRequests)
        advance(1)
        hover(tool = MotionEvent.TOOL_TYPE_MOUSE)
        assertEquals(0L, paper.hoverPreparationRequests)
        hover(MotionEvent.ACTION_HOVER_ENTER)
        assertEquals(1L, paper.hoverPreparationRequests)
        repeat(6) { hover() }
        advance(3000)
        hover()
        assertEquals(1L, paper.hoverPreparationRequests)

        stroke(200f)
        advance(1000)
        paper.hoverPreparationEnabled = false
        hover()
        assertEquals(1L, paper.hoverPreparationRequests)
        paper.hoverPreparationEnabled = true
        hover()
        assertEquals(2L, paper.hoverPreparationRequests)

        stroke(250f)
        advance(1000)
        paper.setSystemRegistration(false)
        hover()
        assertEquals(2L, paper.hoverPreparationRequests)
        paper.setSystemRegistration(true)
        hover()
        assertEquals(3L, paper.hoverPreparationRequests)
    }

    @Test fun hoverRedrawPreservesCompletedInkAndCannotExposeTheMovingPath() {
        stroke()
        advance(1000)
        val before = render()
        hover()
        val prepared = render()
        assertArrayEquals(pixels(before), pixels(prepared))
        assertTrue(Color.red(prepared.getPixel(150, 100)) < 80)
        before.recycle(); prepared.recycle()
        val requests = paper.hoverPreparationRequests
        send(MotionEvent.ACTION_DOWN, 100f, 200f)
        send(MotionEvent.ACTION_MOVE, 220f, 200f)
        advance(1500)
        hover()
        assertEquals(requests, paper.hoverPreparationRequests)
        render().let {
            assertTrue(Color.red(it.getPixel(150, 100)) < 80)
            assertEquals(Color.WHITE, it.getPixel(150, 200))
            it.recycle()
        }
        send(MotionEvent.ACTION_UP, 250f, 200f)
        advance(1000)
        hover()
        assertEquals(requests + 1, paper.hoverPreparationRequests)
    }

    @Test fun cancellationCreatesANewIdlePeriodWhilePauseAndModeResetGatePreparation() {
        stroke()
        advance(1000)
        hover()
        send(MotionEvent.ACTION_DOWN, 100f, 200f)
        send(MotionEvent.ACTION_CANCEL, 200f, 200f)
        advance(1000)
        hover()
        assertEquals(2L, paper.hoverPreparationRequests)
        assertEquals(1, paper.completedStrokes)
        paper.pauseCurrentSystemStroke()
        hover()
        assertEquals(2L, paper.hoverPreparationRequests)
        assertEquals(1, paper.completedStrokes)

        paper.mode(false)
        advance(1000)
        hover()
        assertEquals(0L, paper.hoverPreparationRequests)
        paper.systemMode()
        paper.setSystemRegistration(true)
        advance(1000)
        hover()
        assertEquals(0L, paper.hoverPreparationRequests)
        stroke()
        paper.clear()
        stroke()
        advance(1000)
        hover()
        assertEquals(0L, paper.hoverPreparationRequests) // clear retained the mode but removed registration.
        paper.setSystemRegistration(true)
        hover()
        assertEquals(1L, paper.hoverPreparationRequests)
    }

    @Test fun reportUsesEarliestBatchedMoveAndRetainsOnlySixTimingRecordsWithoutStrokeData() {
        stroke()
        advance(1500)
        val hoverAt = SystemClock.uptimeMillis()
        hover()
        assertEquals(1L, paper.hoverPreparationRequests)
        advance(25)
        val downReceivedAt = SystemClock.uptimeMillis()
        val base = downReceivedAt - 7
        event(MotionEvent.ACTION_DOWN, 137f, 173f, time = base).let { paper.onTouchEvent(it); it.recycle() }
        advance(33)
        val move = event(MotionEvent.ACTION_MOVE, 187f, 173f, time = base + 20)
        move.addBatch(base + 35, coordinates(237f, 173f, 0.7f), 0)
        assertEquals(1, move.historySize)
        paper.onTouchEvent(move); move.recycle()
        advance(12)
        event(MotionEvent.ACTION_UP, 277f, 173f, time = base + 50).let { paper.onTouchEvent(it); it.recycle() }
        val summary = paper.idleStartSummary
        assertTrue(summary, summary.contains("downEventAgeMs=7"))
        // The scheduled preparation frame may advance Robolectric's clock while
        // it runs; verify the observed request-to-DOWN interval, not an assumed
        // idleFor duration. This still checks the actual clock-derived metric.
        assertTrue(summary, summary.contains("hoverPreparationAgeMs=${downReceivedAt - hoverAt};"))
        assertTrue(summary, summary.contains("firstMoveAfterDownMs=20"))
        assertTrue(summary.contains("registered=true"))
        assertTrue(summary.contains("exclude visible ink latency"))
        assertFalse(summary.contains("137.0"))
        assertFalse(summary.contains("173.0"))
        assertFalse(summary.contains("pressure="))

        repeat(8) { stroke(250f) }
        val bounded = paper.idleStartSummary
        val records = bounded.lineSequence().filter { it.startsWith("#") }.toList()
        assertEquals(6, records.size)
        assertTrue(records.first().startsWith("#5 "))
        assertTrue(records.last().startsWith("#10 "))
        assertTrue(bounded.length < 4000)
    }
}
