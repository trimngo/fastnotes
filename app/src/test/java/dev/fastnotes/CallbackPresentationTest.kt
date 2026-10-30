package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import dev.fastnotes.ink.NativeRefreshPolicy
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.model.Note
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallbackPresentationTest {
    private lateinit var panel: ViwoodsPanel
    private lateinit var fake: FakePanel
    private val visible = Rect(80, 100, 400, 500)

    @Before fun setup() {
        fake = FakePanel.getInstance().apply { reset() }
        panel = ViwoodsPanel(FakePanel::class.java.name, NativeRefreshPolicy.REFERENCE_CALLBACK) {
            error("Callback policy must not create a presentation worker")
        }
        val source = Bitmap.createBitmap(500, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertTrue(panel.start(RuntimeEnvironment.getApplication(), source, Note(pageWidth = 500f), 0, 0, 1, visible = visible))
        fake.trace.clear()
    }
    @After fun cleanup() { panel.stop() }

    @Test fun renderAndTransactionEndRunOnTheInputThreadBeforeUiAcknowledgement() {
        var endedBeforeNotification = false
        panel.onEditsAvailable = { endedBeforeNotification = fake.ends == 1 }
        val callback = Thread {
            fake.emit(MotionEvent.ACTION_DOWN, 100, 153)
            fake.emit(MotionEvent.ACTION_MOVE, 200, 253)
            fake.emit(MotionEvent.ACTION_UP, 300, 353)
        }
        callback.start(); callback.join()
        assertTrue(panel.status, panel.active)
        assertTrue(endedBeforeNotification)
        assertFalse(panel.penDown)
        assertEquals(1, fake.starts)
        assertEquals(1, fake.ends)
        assertEquals(listOf("start", "bitmap", "render", "render", "render", "end"), fake.trace.map { it.first })
        assertTrue(fake.trace.all { it.second == callback.id })
        assertEquals(callback.id, panel.lastCallbackThread)
        assertEquals(callback.id, panel.lastRenderThread)
        assertEquals(callback.id, panel.lastEndThread)
        assertTrue(Color.red(fake.foreground!!.getPixel(200, 253)) < 16)
        assertTrue(fake.rendered.all(visible::contains))
        assertEquals(1, panel.drainEdits().size)
        panel.acknowledge()
        assertEquals(1, fake.ends)
    }

    @Test fun cancelRestoresTheBitmapAndClippingStillRejectsOutsideInput() {
        fake.emit(MotionEvent.ACTION_DOWN, 10, 10)
        assertEquals(0, fake.starts)
        fake.emit(MotionEvent.ACTION_DOWN, 100, 153)
        fake.emit(MotionEvent.ACTION_MOVE, 200, 253)
        assertTrue(Color.red(fake.foreground!!.getPixel(200, 253)) < 16)
        fake.emit(MotionEvent.ACTION_CANCEL, 200, 253)
        assertTrue(panel.drainEdits().isEmpty())
        assertEquals(1, fake.ends)
        assertTrue(fake.rendered.all(visible::contains))
        val preview = Bitmap.createBitmap(500, 600, Bitmap.Config.ARGB_8888)
        assertTrue(panel.drawPreview(Canvas(preview)))
        assertEquals(Color.WHITE, preview.getPixel(200, 253))
    }
}
