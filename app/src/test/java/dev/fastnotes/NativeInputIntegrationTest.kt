package dev.fastnotes

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import dev.fastnotes.ink.*
import dev.fastnotes.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeInputIntegrationTest {
    private lateinit var editor: NoteEditor
    private lateinit var panel: ViwoodsPanel
    private lateinit var view: InkView
    private lateinit var fake: FakePanel
    private var downTime = 0L
    private lateinit var activity: ActivityController<Activity>

    @Before fun setup() {
        fake = FakePanel.getInstance().apply { reset() }
        editor = NoteEditor(Note())
        panel = ViwoodsPanel(FakePanel::class.java.name) { ManualNativeInkScheduler() }
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        view = InkView(activity.get(), panel).apply {
            setRendering(false, true)
            bind(editor)
            layout(0, 0, 500, 600)
        }
        activity.get().setContentView(view, ViewGroup.LayoutParams(500, 600))
        activity.visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(panel.start(RuntimeEnvironment.getApplication(),
            Bitmap.createBitmap(500, 600, Bitmap.Config.ARGB_8888), editor.note, 0, 0, 1))
        downTime = SystemClock.uptimeMillis()
    }

    @After fun close() { panel.stop(); activity.pause().stop().destroy() }

    private fun android(action: Int, x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    @Test fun androidAndNativeCopiesOfSameGestureProduceExactlyOneSavedStroke() {
        fake.emit(MotionEvent.ACTION_DOWN, 100, 100)
        android(MotionEvent.ACTION_DOWN, 100f, 100f)
        fake.emit(MotionEvent.ACTION_MOVE, 150, 150)
        android(MotionEvent.ACTION_MOVE, 150f, 150f)
        fake.emit(MotionEvent.ACTION_UP, 200, 200)
        android(MotionEvent.ACTION_UP, 200f, 200f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, editor.note.strokes.size)
        assertEquals(listOf(InkPoint(200f, 200f), InkPoint(300f, 300f), InkPoint(400f, 400f)),
            editor.note.strokes.single().points)
        panel.stop()
    }

    @Test fun silentNativeCallbackFallsBackAndReplaysHeldStrokeInsteadOfLosingIt() {
        android(MotionEvent.ACTION_DOWN, 100f, 100f)
        android(MotionEvent.ACTION_MOVE, 150f, 150f)
        android(MotionEvent.ACTION_UP, 200f, 200f)
        assertTrue(editor.note.strokes.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertFalse(panel.active)
        assertEquals(1, editor.note.strokes.size)
        assertEquals(InkPoint(400f, 400f), editor.note.strokes.single().points.last())
        assertTrue(view.engineDescription.contains("No in-window native pen samples"))
    }
}
