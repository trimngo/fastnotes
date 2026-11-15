package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import dev.fastnotes.ink.NativeCompletionToken
import dev.fastnotes.ink.NativeRefreshPolicy
import dev.fastnotes.ink.PaperRasterizer
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.model.Note
import dev.fastnotes.model.Viewport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeCaptureTest {
    private class Running(
        val panel: ViwoodsPanel,
        val fake: FakePanel,
        private val scheduler: () -> ManualNativeInkScheduler?,
        private val source: Bitmap,
        private val note: Note,
        private val clip: Rect,
        private val pressure: Boolean,
    ) {
        fun restart() {
            assertTrue(panel.start(RuntimeEnvironment.getApplication(), source, note, 0, 0, 1,
                visible = clip, inkLayers = true, frameHandoff = true, pressureInk = pressure))
        }
        fun emit(action: Int, x: Int, y: Int, value: Int = 1024, eraser: Boolean = false) {
            fake.emit(action, x, y, pressure = value, tool = if (eraser) 4 else 2)
        }
        fun flush() { scheduler()?.runUntilIdle() }
        fun stroke(y: Int, value: Int = 4096) {
            emit(MotionEvent.ACTION_DOWN, 30, y, value)
            emit(MotionEvent.ACTION_MOVE, 220, y, value)
            emit(MotionEvent.ACTION_UP, 220, y, 0)
            flush()
        }
    }

    private fun running(pressure: Boolean = false,
                        policy: NativeRefreshPolicy = NativeRefreshPolicy.WORKER,
                        clip: Rect = Rect(0, 0, 256, 200),
                        body: (Running) -> Unit) {
        val note = Note(pageWidth = 256f, penWidth = 3f)
        val source = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        PaperRasterizer().page(Canvas(source), note, Viewport(256f, 256, 200, 0f))
        val fake = FakePanel.getInstance().apply { reset() }
        var scheduler: ManualNativeInkScheduler? = null
        val panel = ViwoodsPanel(FakePanel::class.java.name, policy) {
            check(policy == NativeRefreshPolicy.WORKER)
            ManualNativeInkScheduler().also { scheduler = it }
        }
        val state = Running(panel, fake, { scheduler }, source, note, Rect(clip), pressure)
        try { state.restart(); body(state) } finally { panel.stop(); source.recycle() }
    }

    private fun image() = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)

    private fun capture(state: Running): Bitmap = image().also {
        Canvas(it).drawColor(Color.MAGENTA)
        assertNotNull(state.panel.drawCaptureFrame(Canvas(it)))
    }

    private fun sameImage(expected: Bitmap, actual: Bitmap) {
        val a = IntArray(256 * 200)
        val b = IntArray(a.size)
        expected.getPixels(a, 0, 256, 0, 0, 256, 200)
        actual.getPixels(b, 0, 256, 0, 0, 256, 200)
        assertArrayEquals(a, b)
    }

    private fun darkness(image: Bitmap, x: Int, from: Int, to: Int) =
        (from..to).sumOf { 255 - Color.red(image.getPixel(x, it)) }

    @Test fun fixedAndPressureInkAreCapturableBeforePenUpInBothPolicies() {
        for (policy in NativeRefreshPolicy.values()) for (pressure in listOf(false, true)) {
            running(pressure, policy) { state ->
                state.emit(MotionEvent.ACTION_DOWN, 30, 33, 4096)
                state.emit(MotionEvent.ACTION_MOVE, 220, 33, 4096)
                val frame = image()
                try {
                    assertTrue(state.panel.drawCapture(Canvas(frame)))
                    assertEquals(Color.BLACK, frame.getPixel(120, 33))
                    assertTrue(state.panel.penDown)
                    assertTrue(state.panel.drainEdits().isEmpty())
                    assertNull(state.panel.completionToken())
                    assertEquals(0, state.fake.ends)
                } finally { frame.recycle() }
            }
        }
    }

    @Test fun pressureDotsAndZeroPressureUpKeepTheSameCapturedShape() {
        running(pressure = true) { state ->
            state.emit(MotionEvent.ACTION_DOWN, 100, 83, 100)
            val light = capture(state)
            state.emit(MotionEvent.ACTION_MOVE, 100, 83, 4096)
            val heavy = capture(state)
            try {
                assertTrue(darkness(heavy, 100, 73, 93) > darkness(light, 100, 73, 93) * 2)
                assertEquals(2L, state.panel.sampleCount)
                state.emit(MotionEvent.ACTION_UP, 100, 83, 0)
                val completed = capture(state)
                try { sameImage(heavy, completed) } finally { completed.recycle() }
            } finally { light.recycle(); heavy.recycle() }
        }
    }

    @Test fun frozenClipAndPiecesPreventOutsideInkAndReentryBridges() {
        for (pressure in listOf(false, true)) running(pressure, clip = Rect(20, 20, 240, 180)) { state ->
            state.emit(MotionEvent.ACTION_DOWN, 21, 33, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 60, 33, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 260, 83, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 180, 133, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 220, 133, 4096)
            val frame = capture(state)
            try {
                assertEquals(Color.MAGENTA, frame.getPixel(19, 33))
                assertEquals(Color.WHITE, frame.getPixel(120, 83))
                assertEquals(Color.BLACK, frame.getPixel(200, 133))
                state.emit(MotionEvent.ACTION_UP, 220, 133, 0)
                val completed = capture(state)
                try { sameImage(frame, completed) } finally { completed.recycle() }
            } finally { frame.recycle() }
        }
    }

    @Test fun cancelAndActiveEraserCaptureRestoreOrRemoveTheWholeCommittedStroke() {
        running(pressure = true) { state ->
            state.stroke(33)
            state.panel.drainEdits()
            val original = capture(state)
            try {
                state.emit(MotionEvent.ACTION_DOWN, 30, 133, 4096)
                state.emit(MotionEvent.ACTION_MOVE, 220, 133, 4096)
                state.emit(MotionEvent.ACTION_CANCEL, 220, 133, 0)
                val canceled = capture(state)
                try { sameImage(original, canceled) } finally { canceled.recycle() }
                state.emit(MotionEvent.ACTION_DOWN, 120, 33, eraser = true)
                val erasing = capture(state)
                try { assertEquals(Color.WHITE, erasing.getPixel(100, 33)) } finally { erasing.recycle() }
                state.emit(MotionEvent.ACTION_CANCEL, 120, 33, eraser = true)
                val restored = capture(state)
                try { sameImage(original, restored) } finally { restored.recycle() }
                state.emit(MotionEvent.ACTION_DOWN, 120, 33, eraser = true)
                state.emit(MotionEvent.ACTION_UP, 120, 33, eraser = true)
                val erased = capture(state)
                try {
                    assertEquals(Color.WHITE, erased.getPixel(30, 33))
                    assertEquals(Color.WHITE, erased.getPixel(220, 33))
                } finally { erased.recycle() }
            } finally { original.recycle() }
        }
    }

    @Test fun captureTokensCannotAcknowledgeAStillMovingOrNewerGesture() {
        running { state ->
            state.emit(MotionEvent.ACTION_DOWN, 30, 33)
            state.emit(MotionEvent.ACTION_MOVE, 220, 33)
            val frame = image()
            try {
                val moving = requireNotNull(state.panel.drawCaptureFrame(Canvas(frame)))
                assertNull(state.panel.completionToken())
                state.emit(MotionEvent.ACTION_UP, 220, 33)
                state.panel.drainEdits()
                state.flush()
                val completed = requireNotNull(state.panel.completionToken())
                assertNotEquals(moving, completed)
                state.panel.acknowledge(moving)
                assertEquals(0, state.fake.ends)
                assertEquals(completed, state.panel.drawCaptureFrame(Canvas(frame)))
                state.panel.acknowledge(completed)
                assertEquals(1, state.fake.ends)
                assertNull(state.panel.completionToken())
                state.emit(MotionEvent.ACTION_DOWN, 30, 133)
                state.emit(MotionEvent.ACTION_MOVE, 220, 133)
                state.panel.acknowledge(completed)
                assertEquals(1, state.fake.ends)
                state.emit(MotionEvent.ACTION_UP, 220, 133)
                state.panel.drainEdits(); state.flush()
                state.panel.acknowledge(completed)
                assertEquals(1, state.fake.ends)
                state.panel.acknowledge(requireNotNull(state.panel.completionToken()))
                assertEquals(2, state.fake.ends)
            } finally { frame.recycle() }
        }
    }

    @Test fun blockedCaptureRasterizationDoesNotOwnTheLivePenLock() {
        running(pressure = true) { state ->
            state.emit(MotionEvent.ACTION_DOWN, 30, 33, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 100, 33, 4096)
            val began = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val accepted = CountDownLatch(1)
            val result = AtomicReference<NativeCompletionToken?>()
            val failed = AtomicReference<Throwable?>()
            val frame = image()
            val target = object : Canvas(frame) {
                override fun drawColor(color: Int) {
                    began.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    super.drawColor(color)
                }
            }
            val captureThread = Thread {
                try { result.set(state.panel.drawCaptureFrame(target)) } catch (problem: Throwable) { failed.set(problem) }
            }
            val input = Thread {
                state.emit(MotionEvent.ACTION_MOVE, 220, 133, 4096)
                accepted.countDown()
            }
            captureThread.start()
            try {
                assertTrue(began.await(2, TimeUnit.SECONDS))
                input.start()
                assertTrue("Capture rasterization blocked pen input", accepted.await(1, TimeUnit.SECONDS))
            } finally { resume.countDown(); captureThread.join(3000); if (input.isAlive) input.join(2000) }
            try {
                assertNull(failed.get())
                assertNotNull(result.get())
                assertTrue(state.panel.active)
                assertEquals(Color.WHITE, frame.getPixel(200, 133)) // Snapshot predates the newer MOVE.
            } finally { frame.recycle() }
        }
    }

    @Test fun aRestartDuringCaptureInvalidatesTheOldFrameWithoutReadingNativeBitmaps() {
        running { state ->
            state.emit(MotionEvent.ACTION_DOWN, 30, 33)
            state.emit(MotionEvent.ACTION_MOVE, 220, 33)
            val began = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val result = AtomicReference<NativeCompletionToken?>()
            val failed = AtomicReference<Throwable?>()
            val frame = image()
            val target = object : Canvas(frame) {
                override fun drawColor(color: Int) {
                    began.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    super.drawColor(color)
                }
            }
            val captureThread = Thread {
                try { result.set(state.panel.drawCaptureFrame(target)) } catch (problem: Throwable) { failed.set(problem) }
            }
            captureThread.start()
            try {
                assertTrue(began.await(2, TimeUnit.SECONDS))
                state.restart()
            } finally { resume.countDown(); captureThread.join(3000) }
            try {
                assertNull(failed.get())
                assertNull(result.get())
                val fresh = capture(state)
                try { assertEquals(Color.WHITE, fresh.getPixel(120, 33)) } finally { fresh.recycle() }
                state.panel.stop()
                assertNull(state.panel.completionToken())
                assertFalse(state.panel.drawCapture(Canvas(frame)))
            } finally { frame.recycle() }
        }
    }

    @Test fun observerFailureDoesNotDisableNativeInkOrCreateNotificationsForDuplicateSamples() {
        running { state ->
            var notifications = 0
            state.panel.onLiveInkChanged = { notifications++; error("capture observer failure") }
            state.emit(MotionEvent.ACTION_DOWN, 30, 33)
            state.emit(MotionEvent.ACTION_MOVE, 220, 33)
            state.emit(MotionEvent.ACTION_MOVE, 220, 33)
            state.emit(MotionEvent.ACTION_UP, 220, 33)
            assertEquals(3, notifications) // DOWN, changed MOVE, completion.
            assertTrue(state.panel.active)
            assertEquals(1, state.panel.drainEdits().size)
            state.flush()
            assertTrue(state.fake.rendered.isNotEmpty())
        }
    }

    @Test fun aLongPressureGestureCapturesEverySampleAndDoesNotChangeAtPenUp() {
        running(pressure = true) { state ->
            state.emit(MotionEvent.ACTION_DOWN, 20, 33, 1000)
            for (index in 1..2000) {
                state.emit(MotionEvent.ACTION_MOVE, 20 + index % 200, 33 + 2 * (index % 2), index % 4097)
            }
            assertEquals(2001L, state.panel.sampleCount)
            val moving = capture(state)
            try {
                assertTrue(darkness(moving, 100, 23, 43) > 0)
                assertNull(state.panel.completionToken())
                state.emit(MotionEvent.ACTION_UP, 20, 33, 0)
                val completed = capture(state)
                try { sameImage(moving, completed) } finally { completed.recycle() }
            } finally { moving.recycle() }
        }
    }
}
