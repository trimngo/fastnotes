package dev.fastnotes

import android.graphics.*
import android.view.MotionEvent
import dev.fastnotes.ink.*
import dev.fastnotes.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

interface FakeNativeListener {
    fun onInput(x: Int, y: Int, pressure: Int, tilt: Float, tool: Int, action: Int, button: Int, buttons: Int)
}

/** API contract fake, not a claim about physical firmware. */
class FakePanel {
    private data class Buffer(val kind: Int, val bitmap: Bitmap)
    private val buffers = ArrayList<Buffer>()
    var enabled = false
    var foreground: Bitmap? = null
    var background: Bitmap? = null
    var foregroundBounds: Rect? = null
    var nativeOrientation = 0
    val trace = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
    private fun trace(name: String) { trace.add(name to Thread.currentThread().id) }
    var rendered = ArrayList<Rect>()
    var fail = false
    var failBackground = false
    var input: FakeNativeListener? = null
    var starts = 0
    var ends = 0
    var pictureMode = 3
    var listenerBindings = 0
    var renderStarted: CountDownLatch? = null
    var resumeRender: CountDownLatch? = null
    fun setWritingJavaBitmap(bitmap: Bitmap, rotation: Int, x: Int, y: Int) {
        trace("bitmap")
        register(1, bitmap)
        foregroundBounds = Rect(x, y, x + bitmap.width, y + bitmap.height); nativeOrientation = rotation
    }
    fun setWritingJavaBackgroundBitmap(bitmap: Bitmap, rotation: Int, x: Int, y: Int) {
        check(!failBackground)
        register(0, bitmap)
    }
    private fun register(kind: Int, bitmap: Bitmap) {
        // Recovered ENote contract: an existing identity OR kind replaces that entry.
        // In particular, reusing foreground as background removes the foreground layer.
        val index = buffers.indexOfFirst { it.bitmap === bitmap || it.kind == kind }
        if (index < 0) buffers.add(Buffer(kind, bitmap)) else buffers[index] = Buffer(kind, bitmap)
        refreshBuffers()
    }
    private fun refreshBuffers() {
        foreground = buffers.firstOrNull { it.kind == 1 }?.bitmap
        background = buffers.firstOrNull { it.kind == 0 }?.bitmap
    }
    fun setWritingEnabled(value: Boolean) { trace("enable=$value"); enabled = value }
    fun setWritingInputlistener(listener: FakeNativeListener?) {
        input = listener
        if (listener != null) listenerBindings++
    }
    fun setWritingInputJumpPointCount(value: Int) {}
    fun setRenderWritingDelayCount(value: Int) {}
    fun renderWriting(rect: Rect) {
        trace("render")
        renderStarted?.countDown()
        check(resumeRender?.await(5, TimeUnit.SECONDS) != false)
        check(!fail)
        rendered.add(Rect(rect))
    }
    fun onWritingStart() { trace("start"); starts++ }
    fun onWritingEnd() { trace("end"); ends++ }
    fun releaseWritingJavaBitmap() { buffers.removeAll { it.kind == 1 }; refreshBuffers() }
    fun releaseWritingJavaBackgroundBitmap() { buffers.removeAll { it.kind == 0 }; refreshBuffers() }
    fun emit(action: Int, x: Int, y: Int, tool: Int = 2, pressure: Int = 1024, buttons: Int = 0) {
        input?.onInput(x, y, pressure, 0f, tool, action, 0, buttons)
    }
    fun reset() {
        enabled = false; foreground = null; background = null; foregroundBounds = null; nativeOrientation = 0; rendered.clear()
        buffers.clear(); fail = false; failBackground = false; input = null; starts = 0; ends = 0; pictureMode = 3
        renderStarted = null; resumeRender = null
        listenerBindings = 0; trace.clear()
    }
    companion object {
        private val singleton = FakePanel()
        @JvmStatic fun getInstance() = singleton
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelTest {
    private lateinit var fake: FakePanel
    private lateinit var panel: ViwoodsPanel
    private lateinit var source: Bitmap
    private lateinit var scheduler: ManualNativeInkScheduler
    private val note = Note()

    @Before fun setup() {
        fake = FakePanel.getInstance().apply { reset() }
        panel = ViwoodsPanel(FakePanel::class.java.name) {
            ManualNativeInkScheduler().also { scheduler = it }
        }
        source = Bitmap.createBitmap(500, 600, Bitmap.Config.ARGB_8888)
        PaperRasterizer().page(Canvas(source), note, Viewport(1000f, 500, 600, 0f))
    }

    @After fun release() { panel.stop() }

    private fun start(page: Note = note) {
        assertTrue(panel.available())
        assertTrue(panel.start(RuntimeEnvironment.getApplication(), source, page, 120, 80, 1))
    }

    @Test fun callbackThreadDrawsImmediatelyIntoSeparateBitmapAndHandsOffAfterModelCommit() {
        start()
        val worker = Thread {
            fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
            fake.emit(MotionEvent.ACTION_MOVE, 250, 253)
        }
        worker.start(); worker.join()
        assertTrue(panel.penDown)
        assertTrue(panel.drainEdits().isEmpty())
        assertNotSame(source, fake.foreground)
        // Input updates the preview immediately; the presentation bitmap changes on a frame.
        val preview = Bitmap.createBitmap(500, 600, Bitmap.Config.ARGB_8888)
        assertTrue(panel.drawPreview(Canvas(preview)))
        assertEquals(Color.BLACK, preview.getPixel(80, 123))
        assertEquals(Color.WHITE, fake.foreground!!.getPixel(80, 123))
        scheduler.runCurrent()
        assertEquals(Color.BLACK, fake.foreground!!.getPixel(80, 123))
        assertEquals(Color.WHITE, source.getPixel(80, 123))
        fake.emit(MotionEvent.ACTION_UP, 300, 303)
        val add = panel.drainEdits().single() as NativeEdit.Add
        assertEquals(InkPoint(160f, 246f), add.strokes.single().points.first())
        assertEquals(InkPoint(360f, 446f), add.strokes.single().points.last())
        assertEquals(1, fake.starts)
        assertEquals(0, fake.ends)
        panel.acknowledge()
        assertEquals(0, fake.ends)
        scheduler.runUntilIdle()
        assertEquals(1, fake.ends)
        assertTrue(fake.rendered.isNotEmpty())
        panel.stop()
    }

    @Test fun completedBackgroundRemainsSeparateAndStableUntilTheStrokeIsCommitted() {
        start()
        val foreground = fake.foreground!!
        val background = fake.background!!
        assertNotSame(source, foreground)
        assertNotSame(source, background)
        assertNotSame(foreground, background)
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        fake.emit(MotionEvent.ACTION_MOVE, 250, 253)
        scheduler.runCurrent()
        assertEquals(Color.BLACK, foreground.getPixel(80, 123))
        assertEquals(Color.WHITE, background.getPixel(80, 123))
        fake.emit(MotionEvent.ACTION_UP, 300, 303)
        scheduler.runUntilIdle()
        // Presentation alone does not commit the underlying background.
        assertEquals(Color.WHITE, background.getPixel(80, 123))
        panel.drainEdits()
        panel.acknowledge()
        assertSame(foreground, fake.foreground)
        assertSame(background, fake.background)
        assertEquals(Color.BLACK, background.getPixel(80, 123))
        fake.emit(MotionEvent.ACTION_DOWN, 400, 403)
        scheduler.runUntilIdle()
        assertTrue(Color.red(foreground.getPixel(280, 323)) < 220)
        assertEquals(Color.WHITE, background.getPixel(280, 323))
        panel.stop()
        assertNull(fake.foreground)
        assertNull(fake.background)
    }

    @Test fun completedBackgroundFailureStopsTheSessionWithoutDiscardingTheCommittedEdit() {
        start()
        var failures = 0
        panel.onFailure = { failures++ }
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        fake.emit(MotionEvent.ACTION_UP, 300, 303)
        assertEquals(1, panel.drainEdits().size)
        panel.acknowledge()
        fake.failBackground = true
        scheduler.runUntilIdle()
        assertFalse(panel.active)
        assertEquals(1, failures)
        assertTrue(panel.status.contains("handoff failed"))
        panel.stop()
        assertNull(fake.foreground)
        assertNull(fake.background)
    }

    @Test fun renderingIsClippedToOurFrozenWindowAndOutsidePenDownIsIgnored() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 10, 10)
        assertEquals(0, fake.starts)
        fake.emit(MotionEvent.ACTION_DOWN, 121, 81)
        fake.emit(MotionEvent.ACTION_MOVE, 619, 679)
        fake.emit(MotionEvent.ACTION_UP, 619, 679)
        scheduler.runUntilIdle()
        val bounds = Rect(120, 80, 620, 680)
        assertTrue(fake.rendered.all { bounds.contains(it) })
        val added = (panel.drainEdits().single() as NativeEdit.Add).strokes.single()
        assertTrue(added.points.all { it.x in 0f..1000f && it.y in 0f..1200f })
        panel.stop()
    }

    @Test fun cancelRestoresPreviewAndNeverPersistsCanceledInk() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        fake.emit(MotionEvent.ACTION_MOVE, 300, 303)
        scheduler.runCurrent()
        assertEquals(Color.BLACK, fake.foreground!!.getPixel(80, 123))
        fake.emit(MotionEvent.ACTION_CANCEL, 300, 303)
        scheduler.runUntilIdle()
        assertTrue(panel.drainEdits().isEmpty())
        assertEquals(Color.WHITE, fake.foreground!!.getPixel(80, 123))
        panel.acknowledge()
        assertEquals(1, fake.ends)
        panel.stop()
    }

    @Test fun rapidNextStrokeIsNotOverwrittenByPreviousHandoff() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        fake.emit(MotionEvent.ACTION_UP, 210, 213)
        fake.emit(MotionEvent.ACTION_DOWN, 300, 403)
        fake.emit(MotionEvent.ACTION_MOVE, 310, 413)
        scheduler.runCurrent()
        val first = panel.drainEdits().single() as NativeEdit.Add
        panel.acknowledge()
        assertEquals(0, fake.ends)
        assertEquals(Color.BLACK, fake.foreground!!.getPixel(180, 323))
        fake.emit(MotionEvent.ACTION_UP, 320, 423)
        val second = panel.drainEdits().single() as NativeEdit.Add
        assertNotEquals(first.strokes.single().id, second.strokes.single().id)
        panel.acknowledge()
        scheduler.runUntilIdle()
        assertEquals(1, fake.ends)
        panel.stop()
    }

    @Test fun hardwareEraserRemovesWholeStrokeAndCanceledEraseRestoresIt() {
        val stroke = Stroke(5, 3f, listOf(InkPoint(160f, 246f), InkPoint(360f, 446f)))
        val page = note.copy(strokes = listOf(stroke))
        PaperRasterizer().page(Canvas(source), page, Viewport(1000f, 500, 600, 0f))
        start(page)
        fake.emit(MotionEvent.ACTION_DOWN, 250, 253, tool = 4)
        fake.emit(MotionEvent.ACTION_CANCEL, 250, 253, tool = 4)
        scheduler.runUntilIdle()
        assertTrue(panel.drainEdits().isEmpty())
        assertEquals(Color.BLACK, fake.foreground!!.getPixel(80, 123))
        fake.emit(MotionEvent.ACTION_DOWN, 250, 253, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        fake.emit(MotionEvent.ACTION_UP, 250, 253, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals(setOf(5L), (panel.drainEdits().single() as NativeEdit.Erase).ids)
        panel.stop()
    }

    @Test fun pressureBearingHoverSamplesAreTreatedAsInkAndFingerDataIsIgnored() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203, tool = 1)
        assertEquals(0, fake.starts)
        fake.emit(MotionEvent.ACTION_HOVER_MOVE, 200, 203)
        fake.emit(MotionEvent.ACTION_HOVER_MOVE, 250, 253)
        fake.emit(MotionEvent.ACTION_UP, 300, 303)
        assertEquals(3, (panel.drainEdits().single() as NativeEdit.Add).strokes.single().points.size)
        panel.stop()
    }

    @Test fun stoppedListenerCannotWriteIntoRestartedSessionAndModeIsRestored() {
        start()
        assertEquals(4, fake.pictureMode)
        val stale = fake.input!!
        panel.stop()
        assertNull(fake.input)
        assertFalse(fake.enabled)
        assertNull(fake.foreground)
        assertEquals(3, fake.pictureMode)
        start()
        stale.onInput(200, 203, 1024, 0f, 2, MotionEvent.ACTION_DOWN, 0, 0)
        assertFalse(panel.penDown)
        assertTrue(panel.drainEdits().isEmpty())
        panel.stop()
    }

    @Test fun renderFailureAndUnavailableApiReleaseResourcesWithoutThrowing() {
        val absent = ViwoodsPanel("dev.fastnotes.DoesNotExist")
        assertFalse(absent.available())
        assertFalse(absent.start(RuntimeEnvironment.getApplication(), source, note, 0, 0, 1))
        start()
        var failures = 0
        panel.onFailure = { failures++ }
        fake.fail = true
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        scheduler.runCurrent()
        assertFalse(panel.active)
        assertEquals(1, failures)
        panel.stop()
        assertNull(fake.input)
        assertNull(fake.foreground)
        assertNull(fake.background)
        assertEquals(3, fake.pictureMode)
    }

    @Test fun hundredsOfRapidSamplesUseOneBoundedWakeupAndTheLastSampleIsNotStranded() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        scheduler.runCurrent()
        for (i in 1..300) fake.emit(MotionEvent.ACTION_MOVE, 200 + i, 203 + i)
        assertEquals(1, scheduler.pendingCount)
        assertEquals(1, fake.rendered.size)
        scheduler.advanceBy(7)
        assertEquals(1, fake.rendered.size)
        scheduler.advanceBy(1)
        assertEquals(2, fake.rendered.size)
        assertTrue(Color.red(fake.foreground!!.getPixel(380, 423)) < 200)
        assertTrue(panel.penDown)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test fun latestRefreshCoversEarlierFramesWhenDriverDropsIntermediateRequests() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        scheduler.runCurrent()
        fake.emit(MotionEvent.ACTION_MOVE, 200, 603)
        scheduler.advanceBy(8)
        fake.emit(MotionEvent.ACTION_MOVE, 300, 203)
        scheduler.advanceBy(8)
        // A last-request-wins panel consumer sees only this newest rectangle. Earlier ink must
        // already be included, without lifting the pen or relying on a canonical Android draw.
        val displayed = source.copy(Bitmap.Config.ARGB_8888, true)
        val local = Rect(fake.rendered.last()).apply { offset(-120, -80) }
        Canvas(displayed).drawBitmap(fake.foreground!!, local, local, null)
        assertTrue(Color.red(displayed.getPixel(80, 323)) < 150)
        assertTrue(Color.red(displayed.getPixel(130, 323)) < 150)
        assertTrue(panel.penDown)
        assertTrue(panel.drainEdits().isEmpty())
    }

    @Test fun penUpRechecksEntireStrokeAndWaitsForPresentationBeforeEndingWriting() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        scheduler.runCurrent()
        scheduler.advanceBy(100)
        fake.emit(MotionEvent.ACTION_MOVE, 400, 503)
        scheduler.runCurrent()
        fake.emit(MotionEvent.ACTION_UP, 410, 513)
        panel.drainEdits()
        panel.acknowledge()
        assertEquals(0, fake.ends)
        scheduler.advanceBy(24)
        assertTrue(fake.rendered.last().contains(200, 203))
        assertTrue(fake.rendered.last().contains(410, 513))
        assertEquals(1, fake.ends)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test fun slowRefreshDoesNotHoldThePenCallbackLockOrExposeTheLiveCanvas() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        val began = CountDownLatch(1)
        val resume = CountDownLatch(1)
        fake.renderStarted = began; fake.resumeRender = resume
        val renderer = Thread { scheduler.runCurrent() }
        renderer.start()
        try {
            assertTrue(began.await(2, TimeUnit.SECONDS))
            val accepted = CountDownLatch(1)
            val input = Thread { fake.emit(MotionEvent.ACTION_MOVE, 400, 403); accepted.countDown() }
            input.start()
            assertTrue("Input must not wait for a blocked renderWriting call", accepted.await(500, TimeUnit.MILLISECONDS))
            input.join()
            assertEquals(2, panel.sampleCount)
            assertEquals(Color.WHITE, fake.foreground!!.getPixel(180, 223))
            val preview = source.copy(Bitmap.Config.ARGB_8888, true)
            panel.drawPreview(Canvas(preview))
            assertEquals(Color.BLACK, preview.getPixel(180, 223))
        } finally { resume.countDown(); renderer.join(3000) }
        scheduler.advanceBy(8)
        assertEquals(Color.BLACK, fake.foreground!!.getPixel(180, 223))
    }

    @Test fun stoppingWithQueuedFramesNeverRendersIntoAReplacementSession() {
        start()
        fake.emit(MotionEvent.ACTION_DOWN, 200, 203)
        val previous = scheduler
        panel.stop()
        start()
        previous.runUntilIdle()
        assertTrue(fake.rendered.isEmpty())
        assertEquals(0, scheduler.pendingCount)
        assertEquals(Color.WHITE, fake.foreground!!.getPixel(80, 123))
    }
}
