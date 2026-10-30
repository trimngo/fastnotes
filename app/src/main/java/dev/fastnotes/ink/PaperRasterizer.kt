package dev.fastnotes.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.fastnotes.model.InkPoint
import dev.fastnotes.model.Note
import dev.fastnotes.model.Viewport
import kotlin.math.ceil
import kotlin.math.floor

/** One instance per rendering owner; callers synchronize their own bitmap. */
class PaperRasterizer {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val ruling = Paint().apply { color = Color.rgb(180, 180, 180); strokeWidth = 1f }

    fun page(canvas: Canvas, note: Note, view: Viewport, excluded: Set<Long> = emptySet()) {
        canvas.drawColor(Color.WHITE)
        val first = floor(view.scrollY / note.lineSpacing).toInt()
        val last = ceil((view.scrollY + view.visibleHeight) / note.lineSpacing).toInt()
        for (line in first..last) {
            val y = (line * note.lineSpacing - view.scrollY) * view.scale
            canvas.drawLine(0f, y, view.width.toFloat(), y, ruling)
        }
        val margin = note.pageWidth * 0.055f * view.scale
        canvas.drawLine(margin, 0f, margin, view.height.toFloat(), ruling)
        for (stroke in note.strokes) {
            if (stroke.id !in excluded && stroke.bottom + stroke.width >= view.scrollY &&
                stroke.top - stroke.width <= view.scrollY + view.visibleHeight) {
                stroke(canvas, stroke.points, stroke.width, view)
            }
        }
    }

    fun stroke(canvas: Canvas, points: List<InkPoint>, width: Float, view: Viewport) {
        if (points.isEmpty()) return
        if (points.size == 1) segment(canvas, view.screen(points[0]), view.screen(points[0]), width * view.scale)
        for (i in 1 until points.size) {
            segment(canvas, view.screen(points[i - 1]), view.screen(points[i]), width * view.scale)
        }
    }

    fun segment(canvas: Canvas, a: InkPoint, b: InkPoint, width: Float) {
        ink.strokeWidth = width
        if (a == b) {
            ink.style = Paint.Style.FILL
            canvas.drawCircle(a.x, a.y, width / 2f, ink)
            ink.style = Paint.Style.STROKE
        } else canvas.drawLine(a.x, a.y, b.x, b.y, ink)
    }
}
