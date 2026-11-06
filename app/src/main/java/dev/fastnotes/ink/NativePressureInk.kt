package dev.fastnotes.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import dev.fastnotes.model.InkPoint
import dev.fastnotes.model.Note
import dev.fastnotes.model.Viewport
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Response recovered from the installed Settings test's Steel pen. The width bounds are
 * Fastnotes choices: the OEM presets and the user's saved pressure settings are unknown.
 */
internal object NativePressureProfile {
    const val DESCRIPTION = "ENote integer pressure; Settings-derived logarithmic response, start 410/4096, ratio 1; " +
        "app width bounds 0.5–2× nominal (not an OEM preset); no sample buffering"

    fun width(pressure: Int, nominal: Float): Float {
        val q = ((pressure.toDouble() - 410.0) / (4096.0 - 410.0)).coerceIn(0.0, 1.0)
        return (nominal * (0.5 + 1.5 * ln(1.0 + 3.0 * q) / ln(4.0))).toFloat()
    }
}

/** One instance per bitmap owner. Live drawing and completed replay use identical primitives. */
internal class NativePressureRasterizer {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val outline = Path()

    fun ink(canvas: Canvas, note: Note, view: Viewport, widths: Map<Long, List<Float>>,
            clip: Rect, fixed: PaperRasterizer, excluded: Set<Long> = emptySet()) {
        if (widths.isEmpty()) {
            fixed.ink(canvas, note, view, excluded)
            return
        }
        for (stroke in note.strokes) {
            if (stroke.id in excluded || stroke.bottom + stroke.width < view.scrollY ||
                stroke.top - stroke.width > view.scrollY + view.visibleHeight) continue
            val pressure = widths[stroke.id]
            if (pressure == null) fixed.stroke(canvas, stroke.points, stroke.width, view)
            else {
                val save = canvas.save()
                try {
                    canvas.clipRect(clip)
                    stroke(canvas, stroke.points, pressure, view)
                } finally { canvas.restoreToCount(save) }
            }
        }
    }

    fun stroke(canvas: Canvas, points: List<InkPoint>, widths: List<Float>, view: Viewport) {
        check(points.size == widths.size)
        if (points.isEmpty()) return
        val first = view.screen(points[0])
        segment(canvas, first, first, widths[0] * view.scale, widths[0] * view.scale)
        for (index in 1 until points.size) {
            segment(canvas, view.screen(points[index - 1]), view.screen(points[index]),
                widths[index - 1] * view.scale, widths[index] * view.scale)
        }
    }

    fun segment(canvas: Canvas, a: InkPoint, b: InkPoint, fromWidth: Float, toWidth: Float) {
        val fromRadius = fromWidth / 2f
        val toRadius = toWidth / 2f
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length > 0f) {
            val nx = -dy / length
            val ny = dx / length
            outline.rewind()
            outline.moveTo(a.x + nx * fromRadius, a.y + ny * fromRadius)
            outline.lineTo(b.x + nx * toRadius, b.y + ny * toRadius)
            outline.lineTo(b.x - nx * toRadius, b.y - ny * toRadius)
            outline.lineTo(a.x - nx * fromRadius, a.y - ny * fromRadius)
            outline.close()
            canvas.drawPath(outline, ink)
        }
        // Repeating the previous cap is intentional: replay follows the exact same draw order.
        if (length > 0f) canvas.drawCircle(a.x, a.y, fromRadius, ink)
        canvas.drawCircle(b.x, b.y, toRadius, ink)
    }
}
