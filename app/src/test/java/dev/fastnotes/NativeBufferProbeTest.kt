package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import dev.fastnotes.diagnostics.NativeBufferMode
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.ink.ViwoodsPanel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeBufferProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val display = Rect(0, 0, 1200, 900)
    private val canvas = Rect(600, 100, 1100, 800)
    private val visible = Rect(650, 130, 1070, 760)
    private lateinit var probe: NativeBufferProbe
    private lateinit var fake: FakePanel
    private lateinit var scheduler: ManualNativeInkScheduler
    private var factories = 0

    @Before fun setup() {
        fake = FakePanel.getInstance().apply { reset() }
        probe = NativeBufferProbe(context, {}) {
            factories++
            ViwoodsPanel(FakePanel::class.java.name) { ManualNativeInkScheduler().also { scheduler = it } }
        }
    }
    @After fun cleanup() { probe.stop() }

    @Test fun paneAndDisplayUseDifferentBufferPlacementButIdenticalClippedScreenInk() {
        for (mode in NativeBufferMode.entries) {
            probe.stop(); fake.reset()
            assertTrue(probe.start(mode, canvas, visible, display, 2))
            assertEquals(if (mode == NativeBufferMode.DISPLAY) display else canvas, fake.foregroundBounds)
            assertEquals(2, fake.nativeOrientation)
            fake.emit(MotionEvent.ACTION_DOWN, 610, 110) // in the canvas, outside its visible clip
            assertEquals(0, fake.starts)
            fake.emit(MotionEvent.ACTION_DOWN, 700, 253)
            fake.emit(MotionEvent.ACTION_MOVE, 800, 353)
            fake.emit(MotionEvent.ACTION_UP, 850, 403)
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.runUntilIdle()
            assertEquals(3, probe.samples)
            assertTrue(fake.rendered.isNotEmpty())
            assertTrue(fake.rendered.all(visible::contains))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            val capture = Bitmap.createBitmap(canvas.width(), canvas.height(), Bitmap.Config.ARGB_8888)
            assertTrue(probe.capture(Canvas(capture)))
            assertEquals(Color.BLACK, capture.getPixel(100, 153))
            assertTrue(probe.details().contains("accepted=3"))
            assertTrue(probe.details().contains("mode", ignoreCase = true))
        }
    }

    @Test fun callbackOnlyStrokeCompletionWorksWithoutAndroidMotionEvents() {
        var finished = 0
        probe.onStrokeFinished = { finished++ }
        assertTrue(probe.start(NativeBufferMode.DISPLAY, canvas, visible, display, 2))
        fake.emit(MotionEvent.ACTION_DOWN, 700, 253)
        fake.emit(MotionEvent.ACTION_UP, 800, 353)
        assertEquals(0, finished)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, finished)
        scheduler.runUntilIdle()
        assertEquals(1, fake.ends)
    }

    @Test fun stoppedControllerCannotSendAQueuedCompletionIntoAReplacement() {
        var finished = 0
        probe.onStrokeFinished = { finished++ }
        assertTrue(probe.start(NativeBufferMode.PANE, canvas, visible, display, 2))
        fake.emit(MotionEvent.ACTION_DOWN, 700, 253)
        fake.emit(MotionEvent.ACTION_UP, 800, 353)
        assertTrue(probe.start(NativeBufferMode.DISPLAY, canvas, visible, display, 2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, finished)
        assertEquals(0, probe.samples)
        fake.emit(MotionEvent.ACTION_DOWN, 700, 253)
        fake.emit(MotionEvent.ACTION_UP, 800, 353)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, finished)
    }

    @Test fun invalidOrOversizedGeometryIsRejectedBeforeNativeInitialization() {
        assertFalse(probe.start(NativeBufferMode.DISPLAY, canvas, Rect(1, 1, 40, 40), display, 2))
        assertFalse(probe.start(NativeBufferMode.DISPLAY, canvas, visible, Rect(0, 0, 4000, 4000), 2))
        assertFalse(probe.start(NativeBufferMode.DISPLAY, canvas, visible, display, 0))
        assertEquals(0, factories)
        assertNull(fake.input)
        assertNull(fake.foreground)
    }

    @Test fun stopReleasesNativeReferencesAndRetainsOnlyDiagnosticCounters() {
        assertTrue(probe.start(NativeBufferMode.DISPLAY, canvas, visible, display, 2))
        fake.emit(MotionEvent.ACTION_DOWN, 700, 253)
        fake.emit(MotionEvent.ACTION_UP, 800, 353)
        probe.stop()
        assertFalse(probe.active)
        assertNull(fake.input)
        assertNull(fake.foreground)
        assertNull(fake.background)
        assertFalse(fake.enabled)
        assertTrue(probe.details().contains("accepted=2"))
    }

    @Test fun lockedCaptureRequiresPenUpAndAFullQuietIntervalFromTheLatestNativeSample() {
        assertTrue(probe.start(NativeBufferMode.DISPLAY, canvas, visible, display, 2))
        val target = Canvas(Bitmap.createBitmap(500, 700, Bitmap.Config.ARGB_8888))
        fake.emit(MotionEvent.ACTION_DOWN, 700, 253)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertFalse(probe.capture(target))
        fake.emit(MotionEvent.ACTION_UP, 800, 353)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertFalse(probe.capture(target))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(probe.capture(target))
    }
}
