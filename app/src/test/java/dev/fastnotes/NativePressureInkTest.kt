package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import dev.fastnotes.ink.NativePressureProfile
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativePressureInkTest {
    private val note = Note(pageWidth = 256f, penWidth = 3f)
    private val view = Viewport(256f, 256, 200, 0f)

    private class Running(val panel: ViwoodsPanel, val fake: FakePanel, val scheduler: () -> ManualNativeInkScheduler?) {
        fun flush() { scheduler()?.runUntilIdle() }
        fun emit(action: Int, x: Int, y: Int, pressure: Int = 1024, eraser: Boolean = false) {
            fake.emit(action, x, y, pressure = pressure, tool = if (eraser) 4 else 2)
        }
        fun stroke(y: Int, pressure: Int) {
            emit(MotionEvent.ACTION_DOWN, 30, y, pressure)
            emit(MotionEvent.ACTION_MOVE, 180, y, pressure)
            emit(MotionEvent.ACTION_UP, 220, y, 0)
            flush()
        }
        fun complete(): Bitmap = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888).also {
            assertNotNull(panel.drawCompleted(Canvas(it)))
        }
    }

    private fun running(policy: NativeRefreshPolicy, pressure: Boolean = true,
                        clip: Rect = Rect(0, 0, 256, 200), body: (Running) -> Unit) {
        val fake = FakePanel.getInstance().apply { reset() }
        var scheduler: ManualNativeInkScheduler? = null
        val panel = ViwoodsPanel(FakePanel::class.java.name, policy) {
            check(policy == NativeRefreshPolicy.WORKER) { "Direct ink must not create a worker" }
            ManualNativeInkScheduler().also { scheduler = it }
        }
        val source = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(panel.start(RuntimeEnvironment.getApplication(), source, note, 0, 0, 1,
                visible = clip, inkLayers = true, frameHandoff = true, pressureInk = pressure))
            body(Running(panel, fake) { scheduler })
        } finally { panel.stop(); source.recycle() }
    }

    private fun alphaAtColumn(image: Bitmap, x: Int, fromY: Int, toY: Int) =
        (fromY..toY).sumOf { Color.alpha(image.getPixel(x, it)) }

    private fun paperComposite(ink: Bitmap): Bitmap = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888).also {
        PaperRasterizer().paper(Canvas(it), note, view)
        Canvas(it).drawBitmap(ink, 0f, 0f, null)
    }

    private fun assertSameImage(expected: Bitmap, actual: Bitmap) {
        val left = IntArray(expected.width * expected.height)
        val right = IntArray(left.size)
        expected.getPixels(left, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(right, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals(left, right)
    }

    private fun assertSameCompositeGeometry(expected: Bitmap, actual: Bitmap) {
        var maxChannelDifference = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val left = expected.getPixel(x, y)
            val right = actual.getPixel(x, y)
            assertEquals("Composite alpha at $x,$y", Color.alpha(left), Color.alpha(right))
            maxChannelDifference = maxOf(maxChannelDifference,
                kotlin.math.abs(Color.red(left) - Color.red(right)),
                kotlin.math.abs(Color.green(left) - Color.green(right)),
                kotlin.math.abs(Color.blue(left) - Color.blue(right)))
        }
        // Repeated antialias coverage rounds on transparent ink before paper compositing;
        // replay rounds directly against opaque paper. The measured bound is two channel LSBs.
        assertTrue("Maximum composite channel difference was $maxChannelDifference", maxChannelDifference <= 2)
    }

    @Test fun pressureResponseIsVisibleAtLowPressureBoundedAndMonotonic() {
        assertEquals(1.5f, NativePressureProfile.width(Int.MIN_VALUE, 3f), 0f)
        assertEquals(1.5f, NativePressureProfile.width(410, 3f), 0f)
        assertEquals(6f, NativePressureProfile.width(4096, 3f), 0f)
        assertEquals(6f, NativePressureProfile.width(Int.MAX_VALUE, 3f), 0f)
        assertTrue(NativePressureProfile.width(2253, 3f) > 3.75f) // Log response, not a linear midpoint.
        for (pressure in 0 until 4096) {
            assertTrue(NativePressureProfile.width(pressure + 1, 3f) >= NativePressureProfile.width(pressure, 3f))
        }
    }

    @Test fun bothTransparentPoliciesVaryLiveWidthWithoutEndingBeforeFrameAcknowledgement() {
        for (policy in NativeRefreshPolicy.values()) running(policy) { state ->
            state.stroke(33, 100)
            state.stroke(83, 4096)
            val image = state.fake.foreground!!
            val thin = alphaAtColumn(image, 120, 23, 43)
            val heavy = alphaAtColumn(image, 120, 73, 93)
            assertTrue("Light pressure must remain visible", thin > 0)
            assertTrue("$policy must show heavier pressure", heavy > thin * 2)
            assertEquals(0, state.fake.ends)
            state.panel.drainEdits()
            val complete = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
            try {
                val token = requireNotNull(state.panel.drawCompleted(Canvas(complete)))
                state.panel.acknowledge(token)
                assertEquals(1, state.fake.ends)
            } finally { complete.recycle() }
            assertTrue(state.panel.pressureDescription.contains("not an OEM preset"))
        }
    }

    @Test fun defaultInkRemainsFixedWidthForBothPolicies() {
        for (policy in NativeRefreshPolicy.values()) running(policy, pressure = false) { state ->
            state.stroke(33, 100)
            state.stroke(83, 4096)
            assertEquals(alphaAtColumn(state.fake.foreground!!, 120, 23, 43),
                alphaAtColumn(state.fake.foreground!!, 120, 73, 93))
            assertEquals("Fixed-width ink", state.panel.pressureDescription)
        }
    }

    @Test fun liveAndCanonicalPixelsMatchThroughPressureChangesAndZeroPressureUp() {
        for (policy in NativeRefreshPolicy.values()) running(policy) { state ->
            state.emit(MotionEvent.ACTION_DOWN, 30, 33, 100)
            state.emit(MotionEvent.ACTION_MOVE, 70, 33, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 110, 83, 1400)
            state.emit(MotionEvent.ACTION_MOVE, 180, 83, 4096)
            state.emit(MotionEvent.ACTION_UP, 220, 83, 0)
            state.flush()
            val live = paperComposite(state.fake.foreground!!)
            val complete = state.complete()
            try {
                assertSameCompositeGeometry(live, complete)
                assertTrue("UP pressure zero must retain the last moving width",
                    alphaAtColumn(state.fake.foreground!!, 205, 73, 93) > 1000)
            } finally { live.recycle(); complete.recycle() }
        }
    }

    @Test fun pressureChangesAtTheSamePositionExpandTheDotAndReplayIdentically() {
        running(NativeRefreshPolicy.REFERENCE_CALLBACK) { state ->
            state.emit(MotionEvent.ACTION_DOWN, 100, 83, 100)
            val before = alphaAtColumn(state.fake.foreground!!, 100, 73, 93)
            state.emit(MotionEvent.ACTION_MOVE, 100, 83, 4096)
            state.emit(MotionEvent.ACTION_UP, 100, 83, 0)
            assertEquals(2L, state.panel.sampleCount)
            assertTrue(alphaAtColumn(state.fake.foreground!!, 100, 73, 93) > before * 2)
            val live = paperComposite(state.fake.foreground!!)
            val complete = state.complete()
            try { assertSameImage(live, complete) } finally { live.recycle(); complete.recycle() }
        }
    }

    @Test fun clipLimitsWideCapsAndOutsideSamplesNeverBridgeTheReenteredPiece() {
        val clip = Rect(20, 20, 240, 180)
        for (policy in NativeRefreshPolicy.values()) running(policy, clip = clip) { state ->
            state.emit(MotionEvent.ACTION_DOWN, 21, 33, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 60, 33, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 260, 83, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 180, 133, 4096)
            state.emit(MotionEvent.ACTION_UP, 220, 133, 0)
            state.flush()
            assertEquals(Color.TRANSPARENT, state.fake.foreground!!.getPixel(19, 33))
            assertEquals(Color.TRANSPARENT, state.fake.foreground!!.getPixel(120, 83))
            assertTrue(state.fake.rendered.all { clip.contains(it) })
            val live = paperComposite(state.fake.foreground!!)
            val complete = state.complete()
            try { assertSameImage(live, complete) } finally { live.recycle(); complete.recycle() }
        }
    }

    @Test fun cancelAndCanceledEraserRestorePressureAndConfirmedEraserRemovesWholeGesture() {
        for (policy in NativeRefreshPolicy.values()) running(policy) { state ->
            state.stroke(33, 4096)
            state.panel.drainEdits()
            val original = state.complete()
            state.emit(MotionEvent.ACTION_DOWN, 30, 133, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 180, 133, 4096)
            state.emit(MotionEvent.ACTION_CANCEL, 180, 133, 0)
            state.flush()
            assertTrue(state.panel.drainEdits().isEmpty())
            val canceled = state.complete()
            try { assertSameImage(original, canceled) } finally { canceled.recycle() }
            state.emit(MotionEvent.ACTION_DOWN, 120, 33, eraser = true)
            state.emit(MotionEvent.ACTION_CANCEL, 120, 33, eraser = true)
            state.flush()
            assertTrue(state.panel.drainEdits().isEmpty())
            val canceledErase = state.complete()
            try { assertSameImage(original, canceledErase) } finally { canceledErase.recycle() }
            state.emit(MotionEvent.ACTION_DOWN, 120, 33, eraser = true)
            state.emit(MotionEvent.ACTION_UP, 120, 33, eraser = true)
            state.flush()
            assertEquals(Color.TRANSPARENT, state.fake.foreground!!.getPixel(30, 33))
            assertEquals(Color.TRANSPARENT, state.fake.foreground!!.getPixel(220, 33))
            val erased = state.complete()
            val paper = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
            PaperRasterizer().paper(Canvas(paper), note, view)
            try { assertSameImage(paper, erased) } finally { original.recycle(); erased.recycle(); paper.recycle() }
        }
    }

    @Test fun previousPressureFrameCannotEndOrIncludeTheNextMovingStroke() {
        running(NativeRefreshPolicy.WORKER) { state ->
            state.stroke(33, 4096)
            state.panel.drainEdits()
            val image = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
            val token = requireNotNull(state.panel.drawCompleted(Canvas(image)))
            state.emit(MotionEvent.ACTION_DOWN, 30, 133, 4096)
            state.emit(MotionEvent.ACTION_MOVE, 180, 133, 4096)
            state.flush()
            state.panel.acknowledge(token)
            assertEquals(0, state.fake.ends)
            val during = state.complete()
            try {
                assertEquals(Color.WHITE, during.getPixel(120, 133))
                assertTrue(Color.alpha(state.fake.foreground!!.getPixel(120, 133)) > 200)
            } finally { image.recycle(); during.recycle() }
        }
    }
}
