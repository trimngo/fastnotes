package dev.fastnotes

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import dev.fastnotes.diagnostics.InkFrameCommitter
import dev.fastnotes.diagnostics.InkTestView
import dev.fastnotes.diagnostics.NativeBufferMode
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.ink.ViwoodsPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capturable Android pixels and ordering, not a physical panel or hardware encoder benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrcpyInkTest {
    private class Frames : InkFrameCommitter {
        val callbacks = ArrayList<Runnable>()
        override fun register(view: View, callback: Runnable): () -> Unit {
            callbacks.add(callback)
            return { /* A dispatched callback can still arrive; the View rejects its stale identity. */ }
        }
    }

    private class Running(val mode: NativeBufferMode) : AutoCloseable {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val frames = Frames()
        val paper = InkTestView(lifecycle.get(), frames)
        val fake = FakePanel.getInstance().apply { reset() }
        val canvas = Rect(600, 100, 1100, 800)
        val visible = Rect(650, 130, 1070, 760)
        val display = Rect(0, 0, 1200, 900)
        private var scheduler: ManualNativeInkScheduler? = null
        var notifications = 0
        val probe = NativeBufferProbe(lifecycle.get(), {}) { policy ->
            ViwoodsPanel(FakePanel::class.java.name, policy.refreshPolicy) {
                ManualNativeInkScheduler().also { scheduler = it }
            }
        }
        init {
            lifecycle.get().setContentView(paper)
            paper.layout(0, 0, canvas.width(), canvas.height())
            probe.onStrokeFinished = { paper.nativeStrokeFinished() }
            probe.onLiveInkChanged = { notifications++; paper.nativeInkChanged() }
            assertTrue(probe.start(mode, canvas, visible, display, 2))
            configure()
        }
        fun configure(live: ((Canvas) -> Boolean)? = if (mode.liveCapture) probe::captureLive else null) {
            paper.nativeMode(probe::capture, { probe.penDown },
                if (mode.inkLayers) probe::captureCompleted else null, live)
        }
        fun emit(action: Int, x: Int, y: Int, pressure: Int = 1024, eraser: Boolean = false) {
            fake.emit(action, x, y, pressure = pressure, tool = if (eraser) 4 else 2)
        }
        fun ui() = shadowOf(Looper.getMainLooper()).idle()
        fun flush() { scheduler?.runUntilIdle(); ui() }
        fun image(): Bitmap {
            paper.layout(0, 0, canvas.width(), canvas.height())
            return Bitmap.createBitmap(canvas.width(), canvas.height(), Bitmap.Config.ARGB_8888).also {
                paper.draw(Canvas(it))
            }
        }
        fun frame() {
            frames.callbacks.last().run()
            ui()
        }
        override fun close() { probe.stop(); lifecycle.pause().stop().destroy() }
    }

    private fun darkAt(image: Bitmap, x: Int, y: Int) = Color.red(image.getPixel(x, y)) < 100

    private fun assertSamePixels(left: Bitmap, right: Bitmap) {
        val a = IntArray(left.width * left.height)
        val b = IntArray(a.size)
        left.getPixels(a, 0, left.width, 0, 0, left.width, left.height)
        right.getPixels(b, 0, right.width, 0, 0, right.width, right.height)
        assertArrayEquals(a, b)
    }

    @Test fun scrcpyCapturesMovingNativeInkWhileExistingInkLayersStillHidesIt() {
        for (mode in listOf(NativeBufferMode.SCRCPY, NativeBufferMode.INK_LAYERS)) {
            Running(mode).use { state ->
                state.emit(MotionEvent.ACTION_DOWN, 700, 253)
                state.emit(MotionEvent.ACTION_MOVE, 900, 253)
                state.flush()
                assertTrue(state.probe.penDown)
                assertEquals(0, state.fake.ends)
                val image = state.image()
                try {
                    assertEquals(mode == NativeBufferMode.SCRCPY, darkAt(image, 200, 153))
                    if (mode == NativeBufferMode.SCRCPY) {
                        System.getProperty("fastnotes.screenshotDir")?.let { directory ->
                            val output = java.io.File(directory, "scrcpy-live-pen-down.png")
                            output.parentFile?.mkdirs()
                            java.io.FileOutputStream(output).use {
                                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                    }
                } finally { image.recycle() }
                assertTrue(state.probe.details().contains("Android live capture=" + mode.liveCapture))
            }
        }
    }

    @Test fun pressureShapeIsAlreadyVisibleBeforeUpAndStaysIdenticalThroughHandoff() {
        Running(NativeBufferMode.SCRCPY).use { state ->
            state.emit(MotionEvent.ACTION_DOWN, 700, 253, 100)
            state.emit(MotionEvent.ACTION_MOVE, 760, 253, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 900, 353, 1400)
            state.flush()
            val moving = state.image()
            state.emit(MotionEvent.ACTION_UP, 900, 353, 0)
            state.flush()
            val completed = state.image()
            try {
                assertTrue(darkAt(moving, 160, 153))
                assertSamePixels(moving, completed)
                assertEquals(0, state.fake.ends)
                state.frame()
                assertEquals(1, state.fake.ends)
                assertTrue(state.paper.handoffDescription.contains("Android live capture"))
            } finally { moving.recycle(); completed.recycle() }
        }
    }

    @Test fun oldCompletedSnapshotNeverPaintsOverTheNextMovingStroke() {
        Running(NativeBufferMode.SCRCPY).use { state ->
            state.emit(MotionEvent.ACTION_DOWN, 700, 253, 4096)
            state.emit(MotionEvent.ACTION_UP, 900, 253, 0)
            state.emit(MotionEvent.ACTION_DOWN, 700, 353, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 900, 353, 4096)
            state.flush()
            val image = state.image()
            try {
                assertTrue(darkAt(image, 200, 153))
                assertTrue(darkAt(image, 200, 253))
            } finally { image.recycle() }
            state.frame()
            assertEquals(0, state.fake.ends) // Acknowledging the old frame cannot end this pen-down.
            state.emit(MotionEvent.ACTION_UP, 900, 353, 0)
            state.flush()
            state.image().recycle()
            state.frame()
            assertEquals(1, state.fake.ends)
        }
    }

    @Test fun translatedClipAndPaneReentryCannotCreateStrayCapsOrBridges() {
        Running(NativeBufferMode.SCRCPY).use { state ->
            state.emit(MotionEvent.ACTION_DOWN, 651, 253, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 700, 253, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 1100, 353, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 1000, 453, 4096)
            state.flush()
            val image = state.image()
            try {
                assertEquals(Color.WHITE, image.getPixel(49, 153))
                assertTrue(darkAt(image, 80, 153))
                assertEquals(Color.WHITE, image.getPixel(250, 253))
                assertTrue(darkAt(image, 400, 353))
            } finally { image.recycle() }
        }
    }

    @Test fun cancelledStrokeAndCancelledEraserRestoreTheRecordedNativeScene() {
        Running(NativeBufferMode.SCRCPY).use { state ->
            state.emit(MotionEvent.ACTION_DOWN, 700, 253, 4096)
            state.emit(MotionEvent.ACTION_UP, 900, 253, 0)
            state.flush()
            val original = state.image()
            state.frame()
            state.emit(MotionEvent.ACTION_DOWN, 700, 353, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 900, 353, 4096)
            state.emit(MotionEvent.ACTION_CANCEL, 900, 353)
            state.flush()
            val canceled = state.image()
            try { assertSamePixels(original, canceled) } finally { canceled.recycle() }
            state.emit(MotionEvent.ACTION_DOWN, 800, 253, eraser = true)
            state.flush()
            val erased = state.image()
            try { assertEquals(Color.WHITE, erased.getPixel(200, 153)) } finally { erased.recycle() }
            state.emit(MotionEvent.ACTION_CANCEL, 800, 253, eraser = true)
            state.flush()
            val restored = state.image()
            try { assertSamePixels(original, restored) } finally { original.recycle(); restored.recycle() }
        }
    }

    @Test fun nativeBurstProducesOneWakeupUntilCaptureThenDeliversTheLatestTail() {
        Running(NativeBufferMode.SCRCPY).use { state ->
            state.emit(MotionEvent.ACTION_DOWN, 700, 253)
            repeat(1000) { state.emit(MotionEvent.ACTION_MOVE, 701 + it % 180, 253) }
            state.ui()
            assertEquals(1, state.notifications)
            repeat(100) { state.emit(MotionEvent.ACTION_MOVE, 701 + it % 180, 253) }
            state.ui()
            assertEquals(1, state.notifications)
            state.image().recycle()
            state.emit(MotionEvent.ACTION_MOVE, 990, 253)
            state.ui()
            assertEquals(2, state.notifications)
            val image = state.image()
            try { assertTrue(darkAt(image, 390, 153)) } finally { image.recycle() }
            state.ui()
            assertEquals(2, state.notifications) // No idle redraw loop.
        }
    }

    @Test fun anUpdateArrivingDuringCaptureIsNotLostAndAStoppedNotificationIsIgnored() {
        Running(NativeBufferMode.SCRCPY).use { state ->
            var inject = true
            state.configure { canvas ->
                val captured = state.probe.captureLive(canvas)
                if (inject) {
                    inject = false
                    state.emit(MotionEvent.ACTION_MOVE, 990, 253)
                }
                captured
            }
            state.emit(MotionEvent.ACTION_DOWN, 700, 253)
            state.emit(MotionEvent.ACTION_MOVE, 800, 253)
            state.ui()
            val first = state.image()
            try { assertEquals(Color.WHITE, first.getPixel(390, 153)) } finally { first.recycle() }
            state.ui()
            assertEquals(2, state.notifications)
            val latest = state.image()
            try { assertTrue(darkAt(latest, 390, 153)) } finally { latest.recycle() }
            state.emit(MotionEvent.ACTION_MOVE, 1000, 253)
            val count = state.notifications
            state.probe.stop()
            state.ui()
            assertEquals(count, state.notifications)
        }
    }

    @Test fun androidTouchEventsCannotDuplicateNativeCaptureInk() {
        Running(NativeBufferMode.SCRCPY).use { state ->
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS
            })
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 100f,
                MotionEvent.ACTION_MOVE to 300f, MotionEvent.ACTION_UP to 300f)) {
                val now = SystemClock.uptimeMillis()
                val coords = arrayOf(MotionEvent.PointerCoords().apply {
                    this.x = x; y = 153f; pressure = 1f
                })
                val event = MotionEvent.obtain(now, now, action, 1, properties, coords, 0, 0,
                    1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { state.paper.onTouchEvent(event) } finally { event.recycle() }
            }
            assertEquals(0L, state.probe.samples)
            val image = state.image()
            try { assertEquals(Color.WHITE, image.getPixel(200, 153)) } finally { image.recycle() }
        }
    }
}
