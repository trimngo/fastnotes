package dev.fastnotes

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import dev.fastnotes.diagnostics.InkFrameCommitter
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
class NativeFrameHandoffViewTest {
    private class Frame(val callback: Runnable) { var cancelled = false }
    private class ControlledFrames : InkFrameCommitter {
        val frames = ArrayList<Frame>()
        override fun register(view: View, callback: Runnable): (() -> Unit) {
            val frame = Frame(callback)
            frames.add(frame)
            return { frame.cancelled = true }
        }
    }

    private lateinit var lifecycle: ActivityController<Activity>
    private lateinit var paper: InkTestView
    private lateinit var frames: ControlledFrames
    private var finished = 0
    private var penDown = false
    private var legacyCaptures = 0
    private val acknowledgements = ArrayList<Int>()
    private val black = Paint().apply { color = Color.BLACK; strokeWidth = 3f }

    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        frames = ControlledFrames()
        attach(frames)
    }

    @After fun cleanup() { lifecycle.pause().stop().destroy() }

    private fun attach(committer: InkFrameCommitter) {
        paper = InkTestView(lifecycle.get(), committer)
        lifecycle.get().setContentView(paper)
        paper.layout(0, 0, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        paper.nativeMode(
            capture = {
                legacyCaptures++
                drawCompleted(it, finished)
                true
            },
            penDown = { penDown },
            completedCapture = { target ->
                val revision = finished
                drawCompleted(target, revision)
                ({ acknowledgements.add(revision); Unit })
            }
        )
    }

    private fun drawCompleted(canvas: Canvas, count: Int) {
        canvas.drawColor(Color.WHITE)
        for (index in 1..count) canvas.drawLine(100f, index * 80f, 250f, index * 80f, black)
    }

    private fun render(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).also {
        paper.draw(Canvas(it))
    }

    private fun isBlack(image: Bitmap, x: Int, y: Int) = Color.red(image.getPixel(x, y)) < 80

    private fun finish(count: Int) { finished = count; paper.nativeStrokeFinished() }

    private fun send(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f
        })
        MotionEvent.obtain(now, now, action, 1, properties, coordinates, 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0).let {
            paper.onTouchEvent(it); it.recycle()
        }
    }

    @Test fun completedImageIsAvailableImmediatelyButNativeWaitsForItsCommittedFrame() {
        finish(1)
        assertEquals(0, legacyCaptures)
        assertEquals(1, frames.frames.size)
        assertTrue(acknowledgements.isEmpty())
        render().let { assertTrue(isBlack(it, 150, 80)); it.recycle() }
        assertTrue(acknowledgements.isEmpty())
        frames.frames.single().callback.run()
        assertTrue(acknowledgements.isEmpty()) // Frame callback marshals back to UI.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), acknowledgements)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        assertEquals(listOf(1), acknowledgements)
        assertEquals(0, legacyCaptures)
    }

    @Test fun anEarlierFrameCommitCannotBecomeValidWhenUiDrawsBeforeHandlingItsCallback() {
        finish(1)
        val earlier = frames.frames.single()
        earlier.callback.run() // No completed snapshot had been drawn at commit time.
        render().recycle()     // Drawing now cannot retroactively validate that frame.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(acknowledgements.isEmpty())
        // Offscreen/software test environments can wait until the next actual draw.
        if (frames.frames.size == 1) render().recycle()
        assertEquals(2, frames.frames.size)
        frames.frames.last().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), acknowledgements)
    }

    @Test fun movingAndroidInputNeverAppearsOverTheCompletedNativeSnapshot() {
        finish(1)
        render().recycle()
        frames.frames.last().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        penDown = true
        send(MotionEvent.ACTION_DOWN, 100f, 200f)
        send(MotionEvent.ACTION_MOVE, 240f, 200f)
        render().let {
            assertTrue(isBlack(it, 150, 80))
            assertFalse(isBlack(it, 150, 200))
            it.recycle()
        }
        assertEquals(0, legacyCaptures)
    }

    @Test fun rapidCompletionsReplacePendingAcknowledgementsWithoutExposingAnOldFrame() {
        finish(1)
        val first = frames.frames.last()
        finish(2)
        val second = frames.frames.last()
        assertTrue(first.cancelled)
        render().let {
            assertTrue(isBlack(it, 150, 80)); assertTrue(isBlack(it, 150, 160)); it.recycle()
        }
        first.callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(acknowledgements.isEmpty())
        second.callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(2), acknowledgements)
    }

    @Test fun resetAndDetachInvalidateAlreadyDispatchedFrameCallbacks() {
        finish(1)
        render().recycle()
        val oldMode = frames.frames.last()
        oldMode.callback.run()
        paper.mode(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(acknowledgements.isEmpty())

        attach(frames)
        finish(2)
        render().recycle()
        val detached = frames.frames.last()
        lifecycle.get().setContentView(View(lifecycle.get()))
        assertTrue(detached.cancelled)
        detached.callback.run()
        lifecycle.get().setContentView(paper)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(acknowledgements.isEmpty())
    }

    @Test fun softwareHandoffPostsOnlyAfterDrawingTheCompletedImage() {
        attach(InkFrameCommitter { _, _ -> null })
        finish(1)
        assertTrue(acknowledgements.isEmpty())
        render().recycle()
        assertTrue(acknowledgements.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), acknowledgements)
    }

    @Test fun lateAndroidUpAndCancelCannotRescheduleTheOldQuietCapture() {
        send(MotionEvent.ACTION_DOWN, 100f, 80f)
        send(MotionEvent.ACTION_MOVE, 240f, 80f)
        finish(1) // Native completion can precede Android UP delivery.
        send(MotionEvent.ACTION_UP, 250f, 80f)
        render().recycle()
        frames.frames.last().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        send(MotionEvent.ACTION_DOWN, 100f, 240f)
        send(MotionEvent.ACTION_CANCEL, 100f, 240f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        assertEquals(0, legacyCaptures)
        assertEquals(listOf(1), acknowledgements)
        render().let { assertTrue(isBlack(it, 150, 80)); it.recycle() }
    }

    @Test fun legacyNativeComparisonRetainsItsFullQuietDelay() {
        paper.nativeMode(capture = {
            legacyCaptures++
            drawCompleted(it, 1)
            true
        }, penDown = { false })
        paper.nativeStrokeFinished()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(0, legacyCaptures)
        render().let { assertFalse(isBlack(it, 150, 80)); it.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, legacyCaptures)
        render().let { assertTrue(isBlack(it, 150, 80)); it.recycle() }
        assertTrue(frames.frames.isEmpty())
    }
}
