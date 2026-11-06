package dev.fastnotes

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeStartupFrameViewTest {
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
    private val prepared = ArrayList<Int>()
    private var nativeCaptures = 0

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
        paper.nativeMode(capture = { nativeCaptures++; true }, penDown = { false },
            completedCapture = { nativeCaptures++; null })
    }

    private fun render(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).also {
        paper.draw(Canvas(it))
    }

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

    @Test fun nativePreparationWaitsForFreshPaperDrawAndCommittedFrameOnUi() {
        render().recycle() // A draw before registration cannot establish preparation.
        paper.afterPreparedFrame { prepared.add(1) }
        assertEquals(1, frames.frames.size)
        assertTrue(prepared.isEmpty())
        render().let {
            assertEquals(Color.WHITE, it.getPixel(150, 80))
            it.recycle()
        }
        assertTrue(prepared.isEmpty())
        frames.frames.single().callback.run()
        assertTrue(prepared.isEmpty()) // Hardware callbacks marshal back to UI.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), prepared)
        assertEquals(0, nativeCaptures)
        render().recycle()
        frames.frames.single().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), prepared)
    }

    @Test fun anEarlierCommitCannotBecomePreparedWhenDrawOccursBeforeUiDispatch() {
        paper.afterPreparedFrame { prepared.add(1) }
        val earlier = frames.frames.single()
        earlier.callback.run() // Commit occurs before the requested paper was drawn.
        render().recycle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared.isEmpty())
        if (frames.frames.size == 1) render().recycle()
        assertEquals(2, frames.frames.size)
        render().recycle()
        frames.frames.last().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), prepared)
    }

    @Test fun explicitCancellationDropsAlreadyDispatchedCommitAndCanBeCalledTwice() {
        val cancel = paper.afterPreparedFrame { prepared.add(1) }
        render().recycle()
        frames.frames.last().callback.run()
        cancel()
        cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared.isEmpty())
        assertTrue(frames.frames.single().cancelled)
    }

    @Test fun replacementRejectsOldFrameAndOldCancellationWithoutCancellingNewPreparation() {
        val oldCancel = paper.afterPreparedFrame { prepared.add(1) }
        render().recycle()
        val oldFrame = frames.frames.last()
        paper.afterPreparedFrame { prepared.add(2) }
        val newFrame = frames.frames.last()
        assertTrue(oldFrame.cancelled)
        oldCancel()
        oldFrame.callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared.isEmpty())
        assertFalse(newFrame.cancelled)
        render().recycle()
        newFrame.callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(2), prepared)
    }

    @Test fun clearModeChangeAndDetachEachRejectTheirQueuedPreparation() {
        for ((index, cancel) in listOf<() -> Unit>(
            { paper.clear() },
            { paper.mode(false) },
            { lifecycle.get().setContentView(View(lifecycle.get())) }
        ).withIndex()) {
            paper.afterPreparedFrame { prepared.add(index) }
            render().recycle()
            val frame = frames.frames.last()
            frame.callback.run()
            cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(frame.cancelled)
            assertTrue(prepared.isEmpty())
        }
    }

    @Test fun softwarePreparationPostsAfterDrawingAndItsQueuedCallbackCanBeCancelled() {
        attach(InkFrameCommitter { _, _ -> null })
        paper.afterPreparedFrame { prepared.add(1) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared.isEmpty())
        render().recycle()
        assertTrue(prepared.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), prepared)

        val cancel = paper.afterPreparedFrame { prepared.add(2) }
        render().recycle()
        cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), prepared)
    }

    @Test fun anOffscreenMissedCommitWaitsForActualDrawWithoutRearmingInALoop() {
        paper.afterPreparedFrame { prepared.add(1) }
        paper.visibility = View.INVISIBLE
        frames.frames.single().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared.isEmpty())
        assertEquals(1, frames.frames.size)
        paper.visibility = View.VISIBLE
        render().recycle()
        assertEquals(2, frames.frames.size)
        assertTrue(prepared.isEmpty())
        frames.frames.last().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), prepared)
    }

    @Test fun preparingNativeModeNeverExposesMovingAndroidInkOrCapturesNativeBuffers() {
        paper.afterPreparedFrame { prepared.add(1) }
        send(MotionEvent.ACTION_DOWN, 100f, 80f)
        send(MotionEvent.ACTION_MOVE, 240f, 80f)
        render().let {
            assertEquals(Color.WHITE, it.getPixel(150, 80))
            it.recycle()
        }
        frames.frames.last().callback.run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), prepared)
        assertEquals(0, nativeCaptures)
    }
}
