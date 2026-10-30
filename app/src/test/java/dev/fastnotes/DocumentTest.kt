package dev.fastnotes

import dev.fastnotes.model.*
import org.junit.Assert.*
import org.junit.Test

class DocumentTest {
    @Test fun nativeStrokeIdentifiersRemainUniqueAfterReturningToAndroidInput() {
        val editor = NoteEditor(Note())
        editor.addAll(listOf(Stroke(40, 2f, listOf(InkPoint(10f, 20f)))))
        val androidStroke = editor.newStroke(listOf(InkPoint(20f, 30f)))
        assertEquals(41L, androidStroke.id)
    }

    @Test fun rotationAndSplitScreenPreserveDocumentCoordinates() {
        val original = InkPoint(425f, 1600f)
        for ((width, height) in listOf(1920 to 2400, 2660 to 1400, 900 to 1400)) {
            val view = Viewport(1000f, width, height, 1200f)
            val displayed = view.screen(original)
            val restored = view.document(displayed.x, displayed.y)
            assertEquals(original.x, restored.x, 0.001f)
            assertEquals(original.y, restored.y, 0.001f)
        }
    }

    @Test fun eraserFindsCrossingsBetweenSamplesAndDots() {
        val line = Stroke(1, 2f, listOf(InkPoint(100f, 100f), InkPoint(100f, 300f)))
        assertTrue(Eraser.hits(line, InkPoint(0f, 200f), InkPoint(200f, 200f), 5f))
        assertFalse(Eraser.hits(line, InkPoint(0f, 400f), InkPoint(200f, 400f), 5f))
        val dot = Stroke(2, 4f, listOf(InkPoint(100f, 100f)))
        assertTrue(Eraser.hits(dot, InkPoint(106f, 100f), InkPoint(106f, 100f), 5f))
        assertFalse(Eraser.hits(dot, InkPoint(110f, 100f), InkPoint(110f, 100f), 5f))
    }

    @Test fun eraserUndoRestoresOriginalOrderingAndRedoIsInvalidatedByNewInk() {
        val editor = NoteEditor(Note())
        val a = editor.newStroke(listOf(InkPoint(10f, 10f)))
        val b = editor.newStroke(listOf(InkPoint(20f, 20f)))
        val c = editor.newStroke(listOf(InkPoint(30f, 30f)))
        editor.addAll(listOf(a, b, c))
        editor.erase(setOf(a.id, c.id))
        assertEquals(listOf(b), editor.note.strokes)
        assertTrue(editor.undo())
        assertEquals(listOf(a, b, c), editor.note.strokes)
        assertTrue(editor.redo())
        assertEquals(listOf(b), editor.note.strokes)
        editor.undo()
        editor.add(editor.newStroke(listOf(InkPoint(50f, 50f))))
        assertFalse(editor.canRedo)
    }

    @Test fun paperChangesAndScrollDoNotMoveExistingInkOrEnterUndoHistory() {
        val editor = NoteEditor(Note())
        val stroke = editor.newStroke(listOf(InkPoint(100f, 200f)))
        editor.add(stroke)
        editor.paper(72f, 6f)
        editor.scroll(900f)
        assertEquals(stroke, editor.note.strokes.single())
        editor.undo()
        assertTrue(editor.note.strokes.isEmpty())
        assertEquals(72f, editor.note.lineSpacing)
        assertEquals(900f, editor.note.scrollY)
    }
}
