package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import dev.fastnotes.diagnostics.NativeBufferMode
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.ink.NativeCompletionToken
import dev.fastnotes.ink.NativeRefreshPolicy
import dev.fastnotes.ink.PaperRasterizer
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.model.Note
import dev.fastnotes.model.Viewport
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeCompletionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val note = Note(pageWidth = 256f)
    private lateinit var fake: FakePanel
    private lateinit var panel: ViwoodsPanel
    private lateinit var scheduler: ManualNativeInkScheduler
    private var probe: NativeBufferProbe? = null

    @Before fun setup() {
        fake = FakePanel.getInstance().apply { reset() }
        panel = ViwoodsPanel(FakePanel::class.java.name) {
            ManualNativeInkScheduler().also { scheduler = it }
        }
    }

    @After fun cleanup() { probe?.stop(); panel.stop() }

    private fun start() {
        val source = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        PaperRasterizer().page(Canvas(source), note, Viewport(256f, 256, 200, 0f))
        try {
            assertTrue(panel.start(context, source, note, 0, 0, 1,
                inkLayers = true, frameHandoff = true))
        } finally { source.recycle() }
    }

    private fun stroke(y: Int) {
        fake.emit(MotionEvent.ACTION_DOWN, 20, y)
        fake.emit(MotionEvent.ACTION_MOVE, 220, y)
        fake.emit(MotionEvent.ACTION_UP, 230, y)
    }

    private fun completed(): Pair<Bitmap, NativeCompletionToken> {
        val image = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        return image to requireNotNull(panel.drawCompleted(Canvas(image)))
    }

    @Test fun workerEndsOnlyAfterBothTheMatchingFrameAndNativeFlushAreReady() {
        start()
        stroke(33)
        panel.drainEdits()
        val (image, token) = completed()
        try {
            assertEquals(Color.BLACK, image.getPixel(100, 33))
            assertEquals(0, fake.ends)
            panel.acknowledge(token)
            assertEquals(0, fake.ends) // Native dirty/final rectangles still need presentation.
            scheduler.runUntilIdle()
            assertEquals(1, fake.ends)
            panel.acknowledge(token)
            assertEquals(1, fake.ends)
        } finally { image.recycle() }
    }

    @Test fun completedCaptureExcludesANewMovingStrokeAndItsOldAckCannotEndThatStroke() {
        start()
        stroke(33)
        panel.drainEdits(); scheduler.runUntilIdle()
        val first = completed()
        fake.emit(MotionEvent.ACTION_DOWN, 20, 153)
        fake.emit(MotionEvent.ACTION_MOVE, 220, 153)
        scheduler.runUntilIdle()
        val duringNextStroke = completed()
        try {
            assertEquals(Color.BLACK, duringNextStroke.first.getPixel(100, 33))
            assertEquals(Color.WHITE, duringNextStroke.first.getPixel(100, 153))
            assertTrue(Color.alpha(fake.foreground!!.getPixel(100, 153)) > 200)
            panel.acknowledge(first.second)
            assertEquals(0, fake.ends)
            fake.emit(MotionEvent.ACTION_UP, 230, 153)
            panel.drainEdits(); scheduler.runUntilIdle()
            panel.acknowledge(first.second)
            panel.acknowledge(duringNextStroke.second)
            assertEquals(0, fake.ends) // Both frames omit the newly completed second stroke.
            val latest = completed()
            try {
                assertEquals(Color.BLACK, latest.first.getPixel(100, 153))
                panel.acknowledge(latest.second)
                assertEquals(1, fake.ends)
            } finally { latest.first.recycle() }
        } finally { first.first.recycle(); duringNextStroke.first.recycle() }
    }

    @Test fun aTokenFromThePreviousSessionCannotAcknowledgeAReusedController() {
        start(); stroke(33); panel.drainEdits(); scheduler.runUntilIdle()
        val old = completed()
        panel.stop(); fake.reset()
        start(); stroke(153); panel.drainEdits(); scheduler.runUntilIdle()
        val current = completed()
        try {
            assertNotEquals(old.second.session, current.second.session)
            panel.acknowledge(old.second)
            assertEquals(0, fake.ends)
            panel.acknowledge(current.second)
            assertEquals(1, fake.ends)
        } finally { old.first.recycle(); current.first.recycle() }
    }

    @Test fun cancellationRequiresItsOwnCompletedFrameAndDoesNotReplayUnfinishedInk() {
        start(); stroke(33); panel.drainEdits(); scheduler.runUntilIdle()
        val old = completed()
        fake.emit(MotionEvent.ACTION_DOWN, 20, 153)
        fake.emit(MotionEvent.ACTION_MOVE, 220, 153)
        fake.emit(MotionEvent.ACTION_CANCEL, 230, 153)
        panel.drainEdits(); scheduler.runUntilIdle()
        val cancelled = completed()
        try {
            assertEquals(Color.BLACK, cancelled.first.getPixel(100, 33))
            assertEquals(Color.WHITE, cancelled.first.getPixel(100, 153))
            panel.acknowledge(old.second)
            assertEquals(0, fake.ends)
            panel.acknowledge(cancelled.second)
            assertEquals(1, fake.ends)
        } finally { old.first.recycle(); cancelled.first.recycle() }
    }

    @Test fun directTransparentPresentationUsesTheInputThreadButWaitsForTheCompletedFrame() {
        panel = ViwoodsPanel(FakePanel::class.java.name, NativeRefreshPolicy.REFERENCE_CALLBACK) {
            error("Direct mode must not create an app presentation worker")
        }
        start()
        assertEquals(1, fake.trace.count { it.first == "enable=true" })
        fake.trace.clear()
        val callback = Thread { stroke(33) }
        callback.start(); callback.join(3000)
        assertFalse(callback.isAlive)
        assertEquals(listOf("start", "bitmap", "render", "render", "render"), fake.trace.map { it.first })
        assertTrue(fake.trace.all { it.second == callback.id })
        assertEquals(0, fake.ends)
        assertEquals(Color.TRANSPARENT, fake.background!!.getPixel(100, 33))
        panel.drainEdits()
        val complete = completed()
        try {
            panel.acknowledge(complete.second)
            assertEquals(1, fake.ends)
            assertEquals(Thread.currentThread().id, panel.lastEndThread)
            assertTrue(Color.alpha(fake.background!!.getPixel(100, 33)) > 200)
            val pixels = IntArray(256 * 200)
            fake.foreground!!.getPixels(pixels, 0, 256, 0, 0, 256, 200)
            assertTrue(pixels.all { it and 0x00ffffff == 0 })
        } finally { complete.first.recycle() }
    }

    @Test fun rasterizingACompletedSnapshotDoesNotBlockTheLivePenLock() {
        start(); stroke(33); panel.drainEdits(); scheduler.runUntilIdle()
        val began = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val token = AtomicReference<NativeCompletionToken?>()
        val image = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        val target = object : Canvas(image) {
            override fun drawColor(color: Int) {
                began.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
                super.drawColor(color)
            }
        }
        val capture = Thread { token.set(panel.drawCompleted(target)) }
        capture.start()
        try {
            assertTrue(began.await(2, TimeUnit.SECONDS))
            val input = Thread {
                fake.emit(MotionEvent.ACTION_DOWN, 20, 153)
                fake.emit(MotionEvent.ACTION_MOVE, 220, 153)
                accepted.countDown()
            }
            input.start()
            assertTrue("Completed rasterization must not own the pen lock", accepted.await(500, TimeUnit.MILLISECONDS))
            input.join(2000)
        } finally { resume.countDown(); capture.join(3000) }
        try {
            assertNotNull(token.get())
            assertEquals(Color.BLACK, image.getPixel(100, 33))
            assertEquals(Color.WHITE, image.getPixel(100, 153))
            panel.acknowledge(token.get()!!)
            assertEquals(0, fake.ends)
        } finally { image.recycle() }
    }

    @Test fun probeReturnsAnImmediateCompletedCaptureAndDefersEndingUntilItsAck() {
        val bounds = Rect(0, 0, 256, 200)
        probe = NativeBufferProbe(context, {}) { panel }
        var completions = 0
        probe!!.onStrokeFinished = { completions++ }
        assertTrue(probe!!.start(NativeBufferMode.INK_LAYERS, bounds, bounds, bounds, 1))
        stroke(33)
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.runUntilIdle()
        assertEquals(1, completions)
        assertEquals(0, fake.ends)
        val image = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        val ack = requireNotNull(probe!!.captureCompleted(Canvas(image)))
        try {
            assertEquals(Color.BLACK, image.getPixel(100, 33))
            ack()
            assertEquals(1, fake.ends)
            probe!!.stop()
            val afterStop = fake.ends
            ack()
            assertEquals(afterStop, fake.ends)
        } finally { image.recycle() }
    }
}
