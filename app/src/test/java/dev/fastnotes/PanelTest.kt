package dev.fastnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import dev.fastnotes.ink.ViwoodsPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Contract fake only: cannot establish that real firmware implements this contract. */
class FakePanel {
    var enabled = false
    var foreground: Bitmap? = null
    var background: Bitmap? = null
    var rendered: Rect? = null
    var fail = false
    fun setWritingJavaBitmap(bitmap: Bitmap, rotation: Int, x: Int, y: Int) { foreground = bitmap }
    fun setWritingJavaBackgroundBitmap(bitmap: Bitmap, rotation: Int, x: Int, y: Int) { background = bitmap }
    fun setWritingEnabled(value: Boolean) { enabled = value }
    fun renderWriting(rect: Rect) { check(!fail); rendered = Rect(rect) }
    fun onWritingEnd() {}
    fun releaseWritingJavaBitmap() { foreground = null }
    fun releaseWritingJavaBackgroundBitmap() { background = null }
    companion object {
        private val singleton = FakePanel()
        @JvmStatic fun getInstance() = singleton
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PanelTest {
    @Test fun partialRefreshIsClippedAndOffsetToOnlyOurView() {
        val fake = FakePanel.getInstance().apply { fail = false; rendered = null }
        val panel = ViwoodsPanel(FakePanel::class.java.name)
        assertTrue(panel.available())
        assertTrue(panel.start(RuntimeEnvironment.getApplication(),
            Bitmap.createBitmap(500, 600, Bitmap.Config.ARGB_8888), 120, 80, 1))
        panel.render(Rect(-100, -200, 900, 900))
        assertEquals(Rect(120, 80, 620, 680), fake.rendered)
        fake.rendered = null
        panel.render(Rect(1000, 1000, 1200, 1200))
        assertNull(fake.rendered)
        panel.stop()
        assertFalse(fake.enabled)
        assertNull(fake.foreground)
        assertNull(fake.background)
    }

    @Test fun unavailableApiAndRenderFailuresFallBackWithoutRetainingBitmaps() {
        val missing = ViwoodsPanel("dev.fastnotes.DoesNotExist")
        assertFalse(missing.available())
        assertFalse(missing.start(RuntimeEnvironment.getApplication(),
            Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), 0, 0, 1))
        val fake = FakePanel.getInstance().apply { fail = false }
        val panel = ViwoodsPanel(FakePanel::class.java.name)
        assertTrue(panel.start(RuntimeEnvironment.getApplication(),
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888), 0, 0, 1))
        fake.fail = true
        panel.render(Rect(0, 0, 20, 20))
        assertFalse(panel.active)
        assertFalse(fake.enabled)
        assertNull(fake.foreground)
        assertNull(fake.background)
    }
}
