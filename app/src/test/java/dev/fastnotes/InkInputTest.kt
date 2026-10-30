package dev.fastnotes

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.fastnotes.ink.InkView
import dev.fastnotes.ink.Tool
import dev.fastnotes.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InkInputTest {
    private lateinit var editor: NoteEditor
    private lateinit var view: InkView
    private val stylus = MotionEvent.TOOL_TYPE_STYLUS
    private val finger = MotionEvent.TOOL_TYPE_FINGER

    @Before fun setup() {
        editor = NoteEditor(Note())
        view = InkView(RuntimeEnvironment.getApplication()).apply {
            setRendering(false, false)
            bind(editor)
            layout(0, 0, 1000, 700)
        }
    }

    private fun event(action: Int, points: List<Triple<Int, Float, Float>>, buttons: Int = 0, flags: Int = 0) {
        val properties = points.mapIndexed { index, point ->
            MotionEvent.PointerProperties().apply { id = index + 10; toolType = point.first }
        }.toTypedArray()
        val coords = points.map { point ->
            MotionEvent.PointerCoords().apply { x = point.second; y = point.third; pressure = 1f }
        }.toTypedArray()
        val e = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, points.size, properties, coords,
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags)
        view.onTouchEvent(e)
        e.recycle()
    }

    private fun pen(action: Int, x: Float, y: Float, tool: Int = stylus, buttons: Int = 0, flags: Int = 0) {
        event(action, listOf(Triple(tool, x, y)), buttons, flags)
    }

    @Test fun penWritesAndCanceledInputDoesNotPersist() {
        pen(MotionEvent.ACTION_DOWN, 100f, 100f)
        pen(MotionEvent.ACTION_MOVE, 150f, 140f)
        pen(MotionEvent.ACTION_UP, 200f, 180f)
        assertEquals(3, editor.note.strokes.single().points.size)
        pen(MotionEvent.ACTION_DOWN, 300f, 300f)
        pen(MotionEvent.ACTION_MOVE, 350f, 350f)
        pen(MotionEvent.ACTION_CANCEL, 350f, 350f)
        assertEquals(1, editor.note.strokes.size)
        pen(MotionEvent.ACTION_DOWN, 400f, 400f)
        pen(MotionEvent.ACTION_UP, 410f, 410f, flags = MotionEvent.FLAG_CANCELED)
        assertEquals(1, editor.note.strokes.size)
    }

    @Test fun originalPenEraserRemovesStrokesAndUndoRestoresThem() {
        pen(MotionEvent.ACTION_DOWN, 100f, 100f)
        pen(MotionEvent.ACTION_UP, 200f, 200f)
        pen(MotionEvent.ACTION_DOWN, 150f, 150f, MotionEvent.TOOL_TYPE_ERASER)
        pen(MotionEvent.ACTION_UP, 150f, 150f, MotionEvent.TOOL_TYPE_ERASER)
        assertTrue(editor.note.strokes.isEmpty())
        editor.undo()
        assertEquals(1, editor.note.strokes.size)
        pen(MotionEvent.ACTION_DOWN, 150f, 150f, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        pen(MotionEvent.ACTION_CANCEL, 150f, 150f)
        assertEquals(1, editor.note.strokes.size)
    }

    @Test fun fingersNeverDrawAndPalmGuardPreventsSingleFingerScroll() {
        pen(MotionEvent.ACTION_DOWN, 100f, 500f, finger)
        pen(MotionEvent.ACTION_MOVE, 100f, 400f, finger)
        pen(MotionEvent.ACTION_MOVE, 100f, 300f, finger)
        pen(MotionEvent.ACTION_UP, 100f, 300f, finger)
        assertTrue(editor.note.strokes.isEmpty())
        assertEquals(0f, editor.note.scrollY)
        view.palmGuard = false
        pen(MotionEvent.ACTION_DOWN, 100f, 500f, finger)
        pen(MotionEvent.ACTION_MOVE, 100f, 450f, finger)
        pen(MotionEvent.ACTION_MOVE, 100f, 350f, finger)
        assertEquals(100f, editor.note.scrollY)
    }

    @Test fun penIsTrackedByPointerIdEvenWhenPalmIsPointerZero() {
        event(MotionEvent.ACTION_DOWN, listOf(Triple(finger, 600f, 500f)))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),
            listOf(Triple(finger, 600f, 500f), Triple(stylus, 100f, 100f)))
        event(MotionEvent.ACTION_MOVE,
            listOf(Triple(finger, 500f, 400f), Triple(stylus, 200f, 200f)))
        event(MotionEvent.ACTION_POINTER_UP or (1 shl 8),
            listOf(Triple(finger, 500f, 400f), Triple(stylus, 250f, 250f)))
        assertEquals(InkPoint(100f, 100f), editor.note.strokes.single().points.first())
        assertEquals(InkPoint(250f, 250f), editor.note.strokes.single().points.last())
        assertEquals(0f, editor.note.scrollY)
    }

    @Test fun resizingAndScrollingRetainStrokeGeometry() {
        pen(MotionEvent.ACTION_DOWN, 100f, 100f)
        pen(MotionEvent.ACTION_UP, 200f, 200f)
        val saved = editor.note.strokes.single()
        view.layout(0, 0, 500, 1000)
        view.scrollByScreen(100f)
        pen(MotionEvent.ACTION_DOWN, 100f, 100f)
        pen(MotionEvent.ACTION_UP, 150f, 150f)
        assertEquals(saved, editor.note.strokes.first())
        assertEquals(InkPoint(200f, 400f), editor.note.strokes.last().points.first())
    }

    @Test fun leavingWindowDoesNotJoinAcrossGapAndCancelDiscardsAllPieces() {
        pen(MotionEvent.ACTION_DOWN, 900f, 100f)
        pen(MotionEvent.ACTION_MOVE, 1200f, 150f)
        pen(MotionEvent.ACTION_MOVE, 950f, 200f)
        pen(MotionEvent.ACTION_UP, 900f, 250f)
        assertEquals(2, editor.note.strokes.size)
        assertTrue(editor.note.strokes.all { stroke -> stroke.points.all { it.x in 0f..1000f } })
        editor.undo()
        assertTrue(editor.note.strokes.isEmpty())
        pen(MotionEvent.ACTION_DOWN, 900f, 100f)
        pen(MotionEvent.ACTION_MOVE, 1200f, 150f)
        pen(MotionEvent.ACTION_MOVE, 950f, 200f)
        pen(MotionEvent.ACTION_CANCEL, 950f, 200f)
        assertTrue(editor.note.strokes.isEmpty())
    }

    @Test fun moveToolScrollsWithPenWithoutAddingInk() {
        view.tool = Tool.MOVE
        pen(MotionEvent.ACTION_DOWN, 100f, 500f)
        pen(MotionEvent.ACTION_MOVE, 100f, 400f)
        pen(MotionEvent.ACTION_UP, 100f, 400f)
        assertTrue(editor.note.strokes.isEmpty())
        assertEquals(100f, editor.note.scrollY)
    }

    @Test fun palmGuardAllowsDeliberateTwoFingerScrolling() {
        event(MotionEvent.ACTION_DOWN, listOf(Triple(finger, 100f, 500f)))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),
            listOf(Triple(finger, 100f, 500f), Triple(finger, 200f, 500f)))
        event(MotionEvent.ACTION_MOVE,
            listOf(Triple(finger, 100f, 450f), Triple(finger, 200f, 450f)))
        event(MotionEvent.ACTION_MOVE,
            listOf(Triple(finger, 100f, 350f), Triple(finger, 200f, 350f)))
        assertEquals(100f, editor.note.scrollY)
        assertTrue(editor.note.strokes.isEmpty())
    }

    @Test fun batchedHistoricalSamplesAreRetainedInOrder() {
        pen(MotionEvent.ACTION_DOWN, 100f, 100f)
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 10; toolType = stylus })
        fun coords(x: Float) = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = x; pressure = 1f })
        val now = SystemClock.uptimeMillis()
        val move = MotionEvent.obtain(0, now, MotionEvent.ACTION_MOVE, 1, properties, coords(150f),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        move.addBatch(now + 1, coords(175f), 0)
        move.addBatch(now + 2, coords(200f), 0)
        view.onTouchEvent(move)
        move.recycle()
        pen(MotionEvent.ACTION_UP, 225f, 225f)
        assertEquals(listOf(100f, 150f, 175f, 200f, 225f),
            editor.note.strokes.single().points.map { it.x })
    }
}
