package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import dev.fastnotes.model.*
import dev.fastnotes.storage.NoteStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityTest {
    @Before fun disableRealUpdateRequests() {
        (RuntimeEnvironment.getApplication() as FastnotesApp).updates.automatic = false
    }

    @Test fun coldStartReopensTheLastSelectedSheetFromDisk() {
        val app = RuntimeEnvironment.getApplication() as FastnotesApp
        val first = Note(title = "First")
        val second = Note(title = "Second", strokes = listOf(Stroke(1, 3f, listOf(InkPoint(100f, 200f)))))
        app.store.save(first)
        app.store.save(second)
        app.preferences.edit().putString("lastSheet", second.id).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        app.io.submit {}.get(20, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(second.id, app.editor!!.note.id)
        assertEquals(second.strokes, app.editor!!.note.strokes)
        controller.pause().stop().destroy()
        app.io.submit {}.get(20, TimeUnit.SECONDS)
    }

    @Test fun activityRecreationKeepsInkAndWritesReopenableSheet() {
        val app = RuntimeEnvironment.getApplication() as FastnotesApp
        val note = Note(title = "German practice", strokes = listOf(Stroke(1, 3f,
            listOf(InkPoint(100f, 150f), InkPoint(400f, 230f), InkPoint(700f, 150f)))))
        app.select(note)
        app.preferences.edit().putBoolean("frontBuffer", false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = controller.get()
        layout(activity.window.decorView, 1200, 1600)
        assertTrue(activity.paper.width > 0)
        controller.recreate()
        val recreated = controller.get()
        layout(recreated.window.decorView, 1600, 1000)
        assertEquals(note.id, app.editor!!.note.id)
        assertEquals(note.strokes, app.editor!!.note.strokes)
        controller.pause().stop()
        app.io.submit {}.get(20, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val reopened = NoteStore(File(app.filesDir, "sheets")).load(note.id)
        assertEquals(note.strokes, reopened.note.strokes)
        controller.destroy()
    }

    @Test fun portraitLandscapeAndNarrowWindowRenderPaperAndControls() {
        val app = RuntimeEnvironment.getApplication() as FastnotesApp
        app.select(Note(strokes = listOf(Stroke(1, 4f, listOf(InkPoint(120f, 200f), InkPoint(700f, 260f))))))
        app.preferences.edit().putBoolean("frontBuffer", false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = controller.get()
        for ((name, size) in listOf("portrait" to (1200 to 1600), "landscape" to (1600 to 1000),
            "split-screen" to (600 to 1000))) {
            layout(activity.window.decorView, size.first, size.second)
            val image = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            activity.window.decorView.draw(Canvas(image))
            assertTrue(activity.paper.height > 200)
            val screen = IntArray(2)
            activity.paper.getLocationInWindow(screen)
            val px = screen[0] + (0.5f * activity.paper.width).toInt()
            val py = screen[1] + (0.23931f * activity.paper.width).toInt()
            // A rendered ink segment is dark; a separate blank area remains paper white.
            assertTrue("Ink is visible in $name", Color.red(image.getPixel(px, py)) < 80)
            assertEquals(Color.WHITE, image.getPixel(screen[0] + activity.paper.width - 5, screen[1] + 35))
            val directory = System.getProperty("fastnotes.screenshotDir")
            if (!directory.isNullOrBlank()) {
                val dir = File(directory).apply { mkdirs() }
                FileOutputStream(File(dir, "$name.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        controller.pause().stop().destroy()
        app.io.submit {}.get(20, TimeUnit.SECONDS)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
