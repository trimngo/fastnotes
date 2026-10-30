package dev.fastnotes.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/** Temporary scratch strokes only. This view never opens a sheet or uses NoteStore. */
class InkTestView(context: Context) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val lines = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val completed = ArrayList<Path>()
    private var current: Path? = null
    private var pointer = -1
    private var committed = 0
    private var lastX = 0f
    private var lastY = 0f
    private var inside = false
    var firmwareTest = false
        private set
    var samples = 0L
        private set
    var maxEventAgeMs = 0L
        private set
    var onStrokeFinished: (() -> Unit)? = null
    private val settle = Runnable {
        if (pointer < 0) { committed = completed.size; invalidate(); onStrokeFinished?.invoke() }
    }

    fun mode(firmware: Boolean) { clear(); firmwareTest = firmware }
    fun clear() {
        removeCallbacks(settle)
        completed.clear(); current = null; pointer = -1; committed = 0; inside = false
        samples = 0; maxEventAgeMs = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val spacing = 48 * resources.displayMetrics.density
        var y = spacing
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, lines); y += spacing }
        val count = if (firmwareTest) committed else completed.size
        for (index in 0 until count) canvas.drawPath(completed[index], ink)
        if (!firmwareTest) current?.let { canvas.drawPath(it, ink) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return true
            if (pointer >= 0) return true
            requestUnbufferedDispatch(event)
            parent?.requestDisallowInterceptTouchEvent(true)
            removeCallbacks(settle)
            pointer = event.getPointerId(index)
            current = Path(); inside = false
            point(event.getX(index), event.getY(index), event.eventTime)
        } else if (action == MotionEvent.ACTION_MOVE && pointer >= 0) {
            val pen = event.findPointerIndex(pointer)
            if (pen >= 0) {
                for (history in 0 until event.historySize) point(event.getHistoricalX(pen, history),
                    event.getHistoricalY(pen, history), event.getHistoricalEventTime(history))
                point(event.getX(pen), event.getY(pen), event.eventTime)
            }
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
            event.getPointerId(index) == pointer) {
            point(event.getX(index), event.getY(index), event.eventTime)
            current?.let { completed.add(it) }
            current = null; pointer = -1; inside = false
            if (firmwareTest) postDelayed(settle, 1000) else invalidate()
            if (!firmwareTest) onStrokeFinished?.invoke()
        } else if (action == MotionEvent.ACTION_CANCEL) {
            current = null; pointer = -1; inside = false
            if (firmwareTest) postDelayed(settle, 1000) else invalidate()
        }
        return true
    }

    private fun point(x: Float, y: Float, time: Long) {
        if (samples >= 50_000) return
        samples++
        maxEventAgeMs = maxOf(maxEventAgeMs, (SystemClock.uptimeMillis() - time).coerceIn(0, 60_000))
        if (x < 0 || y < 0 || x >= width || y >= height) { inside = false; return }
        val path = current ?: return
        if (!inside) { path.moveTo(x, y); path.lineTo(x + 0.01f, y) }
        else if (x != lastX || y != lastY) path.lineTo(x, y)
        lastX = x; lastY = y; inside = true
        // Firmware mode deliberately never asks Android to show in-flight ink.
        if (!firmwareTest) invalidate()
    }

    override fun onDetachedFromWindow() { removeCallbacks(settle); super.onDetachedFromWindow() }
}
