package dev.fastnotes.model

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class InkPoint(val x: Float, val y: Float)

data class Stroke(val id: Long, val width: Float, val points: List<InkPoint>) {
    val left = points.minOfOrNull { it.x } ?: 0f
    val right = points.maxOfOrNull { it.x } ?: 0f
    val top = points.minOfOrNull { it.y } ?: 0f
    val bottom = points.maxOfOrNull { it.y } ?: 0f
}

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Practice",
    val updatedAt: Long = System.currentTimeMillis(),
    val pageWidth: Float = 1000f,
    val lineSpacing: Float = 48f,
    val penWidth: Float = 2.5f,
    val scrollY: Float = 0f,
    val strokes: List<Stroke> = emptyList(),
)

/** Document coordinates never depend on orientation or the current window size. */
class Viewport(val pageWidth: Float, val width: Int, val height: Int, val scrollY: Float) {
    val scale = width.coerceAtLeast(1) / pageWidth
    fun document(x: Float, y: Float) = InkPoint(x / scale, y / scale + scrollY)
    fun screen(p: InkPoint) = InkPoint(p.x * scale, (p.y - scrollY) * scale)
    val visibleHeight get() = height / scale
}

/** Bounded, reversible editing history. A hardware eraser gesture is one undo step. */
class NoteEditor(initial: Note) {
    var note: Note = initial
        private set
    private data class Edit(val before: List<Stroke>, val after: List<Stroke>)
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private var nextId = (initial.strokes.maxOfOrNull { it.id } ?: 0L) + 1
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun newStroke(points: List<InkPoint>) = Stroke(nextId++, note.penWidth, points.toList())

    fun add(stroke: Stroke) {
        if (stroke.points.isNotEmpty()) change(note.strokes + stroke)
    }

    fun addAll(strokes: List<Stroke>) {
        val nonempty = strokes.filter { it.points.isNotEmpty() }
        if (nonempty.isNotEmpty()) change(note.strokes + nonempty)
    }

    fun erase(ids: Set<Long>) {
        if (ids.isNotEmpty()) change(note.strokes.filterNot { it.id in ids })
    }

    private fun change(strokes: List<Stroke>) {
        if (strokes == note.strokes) return
        undo.addLast(Edit(note.strokes, strokes))
        if (undo.size > 60) undo.removeFirst()
        redo.clear()
        note = note.copy(strokes = strokes, updatedAt = System.currentTimeMillis())
    }

    fun undo(): Boolean {
        val edit = undo.removeLastOrNull() ?: return false
        redo.addLast(edit)
        note = note.copy(strokes = edit.before, updatedAt = System.currentTimeMillis())
        return true
    }

    fun redo(): Boolean {
        val edit = redo.removeLastOrNull() ?: return false
        undo.addLast(edit)
        note = note.copy(strokes = edit.after, updatedAt = System.currentTimeMillis())
        return true
    }

    fun paper(spacing: Float = note.lineSpacing, width: Float = note.penWidth) {
        note = note.copy(lineSpacing = spacing.coerceIn(24f, 96f),
            penWidth = width.coerceIn(1f, 8f), updatedAt = System.currentTimeMillis())
    }

    fun rename(title: String) {
        note = note.copy(title = title.trim().take(120).ifBlank { "Practice" },
            updatedAt = System.currentTimeMillis())
    }

    fun scroll(value: Float) { note = note.copy(scrollY = value.coerceIn(0f, 10_000_000f)) }
}

/** Segment distance catches eraser sweeps between sparse samples, including stroke dots. */
object Eraser {
    fun hits(stroke: Stroke, a: InkPoint, b: InkPoint, radius: Float): Boolean {
        val r = radius + stroke.width / 2
        if (max(a.x, b.x) + r < stroke.left || min(a.x, b.x) - r > stroke.right ||
            max(a.y, b.y) + r < stroke.top || min(a.y, b.y) - r > stroke.bottom) return false
        val p = stroke.points
        if (p.size == 1) return pointSegmentSquared(p[0], a, b) <= r * r
        for (i in 1 until p.size) {
            if (segmentSquared(a, b, p[i - 1], p[i]) <= r * r) return true
        }
        return false
    }

    private fun pointSegmentSquared(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else
            (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
        val x = p.x - a.x - t * dx
        val y = p.y - a.y - t * dy
        return x * x + y * y
    }

    private fun segmentSquared(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): Float {
        val ux = b.x - a.x; val uy = b.y - a.y
        val vx = d.x - c.x; val vy = d.y - c.y
        val cross = ux * vy - uy * vx
        if (abs(cross) > 0.000001f) {
            val wx = c.x - a.x; val wy = c.y - a.y
            val s = (wx * vy - wy * vx) / cross
            val t = (wx * uy - wy * ux) / cross
            if (s in 0f..1f && t in 0f..1f) return 0f
        }
        return minOf(pointSegmentSquared(a, c, d), pointSegmentSquared(b, c, d),
            pointSegmentSquared(c, a, b), pointSegmentSquared(d, a, b))
    }
}
