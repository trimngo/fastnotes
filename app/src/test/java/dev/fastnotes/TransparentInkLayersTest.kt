package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import dev.fastnotes.diagnostics.NativeBufferMode
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.ink.NativeEdit
import dev.fastnotes.ink.PaperRasterizer
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.model.InkPoint
import dev.fastnotes.model.Note
import dev.fastnotes.model.Stroke
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
import java.time.Duration

/** Models independently recovered pixel rules; it does not emulate physical e-ink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransparentInkLayersTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var fake: FakePanel
    private lateinit var panel: ViwoodsPanel
    private lateinit var scheduler: ManualNativeInkScheduler
    private var probe: NativeBufferProbe? = null
    private val note = Note(pageWidth = 256f)

    @Before fun setup() {
        fake = FakePanel.getInstance().apply { reset() }
        panel = ViwoodsPanel(FakePanel::class.java.name) {
            ManualNativeInkScheduler().also { scheduler = it }
        }
    }

    @After fun cleanup() { probe?.stop(); panel.stop() }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun start(page: Note = note) {
        val source = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        PaperRasterizer().page(Canvas(source), page, Viewport(page.pageWidth, 256, 200, page.scrollY))
        assertTrue(panel.start(context, source, page, 0, 0, 1, inkLayers = true))
        source.recycle()
    }

    /** Actual PaperRasterizer geometry, not an injected sentinel pixel. */
    private fun antialiasedPair(): Pair<Bitmap, Bitmap> {
        val opaque = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        val transparent = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        val opaqueCanvas = Canvas(opaque)
        val transparentCanvas = Canvas(transparent)
        val raster = PaperRasterizer()
        for (step in 0 until 64) {
            opaqueCanvas.drawColor(Color.WHITE)
            transparentCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // Native callbacks carry integer coordinates; keep the fixture in that domain.
            val from = InkPoint(11f, 18f + step)
            val to = InkPoint(382f, 119f - step)
            raster.segment(opaqueCanvas, from, to, 3f)
            raster.segment(transparentCanvas, from, to, 3f)
            if (pixels(opaque).any { it == ERASE_MASK }) return opaque to transparent
        }
        opaque.recycle(); transparent.recycle()
        error("Representative opaque antialiasing produced no erase sentinel; do not claim a measured collision")
    }

    // External SoftSolution contract: one FE sentinel selects the mask branch for the
    // rectangle, suppressing ordinary foreground pixels; normal FD becomes a command.
    private fun compositeForeground(pixel: Int, maskMode: Boolean): Int = when {
        maskMode -> if (pixel == ERASE_MASK) Color.WHITE else Color.TRANSPARENT
        pixel == Color.WHITE -> Color.TRANSPARENT
        pixel == RESERVED_GRAY -> ERASE_MASK
        else -> pixel
    }

    @Test fun actualOpaqueAntialiasingCanTriggerMaskModeButTransparentBlackInkCannot() {
        val (opaque, transparent) = antialiasedPair()
        try {
            val paperPixels = pixels(opaque)
            val inkPixels = pixels(transparent)
            println("Raster reserved-pixel proof: opaque FE=${paperPixels.count { it == ERASE_MASK }}, " +
                "opaque FD=${paperPixels.count { it == RESERVED_GRAY }}, " +
                "transparent reserved=${inkPixels.count { it == ERASE_MASK || it == RESERVED_GRAY }}")
            val maskMode = paperPixels.any { it == ERASE_MASK }
            assertTrue(maskMode)
            val black = paperPixels.indexOfFirst { it == Color.BLACK }
            assertTrue(black >= 0)
            assertEquals(Color.BLACK, inkPixels[black])
            assertEquals(Color.TRANSPARENT, compositeForeground(paperPixels[black], maskMode))
            assertTrue(inkPixels.any { Color.alpha(it) in 1..254 })
            assertTrue(inkPixels.all { it and 0x00ffffff == 0 })
            assertFalse(inkPixels.any { it == ERASE_MASK || it == RESERVED_GRAY })
            assertEquals(Color.BLACK, compositeForeground(inkPixels[black], false))
        } finally { opaque.recycle(); transparent.recycle() }
    }

    @Test fun nativeLayersRetainTransparentBackgroundAndSrcClearsCancelledLiveInk() {
        start()
        val foreground = fake.foreground!!
        val background = fake.background!!
        assertNotSame(foreground, background)
        assertTrue(pixels(foreground).all { it == Color.TRANSPARENT })
        assertTrue(pixels(background).all { it == Color.TRANSPARENT })

        fake.emit(MotionEvent.ACTION_DOWN, 20, 33)
        fake.emit(MotionEvent.ACTION_MOVE, 230, 113)
        scheduler.runCurrent()
        assertTrue(panel.penDown)
        assertTrue(Color.alpha(foreground.getPixel(20, 33)) > 200)
        assertTrue(pixels(foreground).all { it and 0x00ffffff == 0 })
        assertTrue(pixels(background).all { it == Color.TRANSPARENT })
        fake.emit(MotionEvent.ACTION_UP, 240, 117)
        scheduler.runUntilIdle()
        assertTrue(panel.drainEdits().single() is NativeEdit.Add)
        panel.acknowledge()
        assertSame(foreground, fake.foreground)
        assertSame(background, fake.background)
        assertTrue(Color.alpha(background.getPixel(20, 33)) > 200)

        val completed = pixels(background)
        fake.emit(MotionEvent.ACTION_DOWN, 40, 160)
        fake.emit(MotionEvent.ACTION_MOVE, 180, 170)
        scheduler.runUntilIdle()
        assertTrue(Color.alpha(foreground.getPixel(40, 160)) > 200)
        assertArrayEquals(completed, pixels(background))
        fake.emit(MotionEvent.ACTION_CANCEL, 180, 170)
        scheduler.runUntilIdle()
        assertEquals(Color.TRANSPARENT, foreground.getPixel(40, 160))
        assertTrue(Color.alpha(foreground.getPixel(20, 33)) > 200)
        assertTrue(panel.drainEdits().isEmpty())
        panel.acknowledge()
        // Cancellation rebuilds canonical stroke geometry; its antialias edge coverage can
        // differ from incremental live overdraw. The committed background must match it.
        assertArrayEquals(pixels(foreground), pixels(background))
    }

    @Test fun rebuildingAnInkLayerPreservesCompletedStrokesWithoutNativePaperPixels() {
        val page = note.copy(strokes = listOf(Stroke(1, 3f, listOf(InkPoint(20f, 70f), InkPoint(220f, 70f)))))
        start(page)
        assertTrue(Color.alpha(fake.foreground!!.getPixel(100, 70)) > 200)
        assertEquals(Color.TRANSPARENT, fake.foreground!!.getPixel(100, 100))
        assertTrue(pixels(fake.foreground!!).all { it and 0x00ffffff == 0 })
    }

    @Test fun probeKeepsMovingAppInkHiddenThenCapturesPaperAndInkAfterTheQuietInterval() {
        val display = Rect(0, 0, 256, 200)
        probe = NativeBufferProbe(context, {}) { panel }
        assertTrue(probe!!.start(NativeBufferMode.INK_LAYERS, display, display, display, 1))
        assertTrue(probe!!.details().contains("Transparent native ink layers"))
        val image = Bitmap.createBitmap(256, 200, Bitmap.Config.ARGB_8888)
        try {
            fake.emit(MotionEvent.ACTION_DOWN, 20, 33)
            fake.emit(MotionEvent.ACTION_MOVE, 230, 113)
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            assertFalse(probe!!.capture(Canvas(image)))
            fake.emit(MotionEvent.ACTION_UP, 240, 117)
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.runUntilIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
            assertFalse(probe!!.capture(Canvas(image)))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertTrue(probe!!.capture(Canvas(image)))
            assertEquals(Color.WHITE, image.getPixel(250, 151))
            assertTrue(Color.red(image.getPixel(20, 33)) < 80)
        } finally { image.recycle() }
    }

    private companion object {
        val ERASE_MASK = 0xfffefefe.toInt()
        val RESERVED_GRAY = 0xfffdfdfd.toInt()
    }
}
