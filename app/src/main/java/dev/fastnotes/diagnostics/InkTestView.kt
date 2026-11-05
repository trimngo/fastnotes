package dev.fastnotes.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.hypot

/** Temporary scratch strokes only. This view never opens a sheet or uses NoteStore. */
class InkTestView(context: Context) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val lines = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val pressureInk = Paint(ink).apply { style = Paint.Style.FILL }
    private data class ScratchStroke(val path: Path = Path(), val pressureAware: Boolean)
    private val completed = ArrayList<ScratchStroke>()
    private var current: ScratchStroke? = null
    private var pointer = -1
    private var committed = 0
    private var lastX = 0f
    private var lastY = 0f
    private var inside = false
    // Reuse primitive storage during live System input. Outline geometry is built only at UP.
    private val pressurePoints = FloatArray(MAX_SAMPLES * 3)
    private val pressureStarts = BooleanArray(MAX_SAMPLES)
    private var pressurePointCount = 0
    private var nativeSnapshot: Bitmap? = null
    private var captureNative: ((Canvas) -> Boolean)? = null
    private var nativePenDown: (() -> Boolean)? = null
    var firmwareTest = false
        private set
    private var systemHandoff = false
    val completedStrokes get() = completed.size
    private var pressureSamples = 0L
    private var minPressure = 0f
    private var maxPressure = 0f
    val handoffDescription get() = if (systemHandoff)
        "Completed strokes at pen-up; pressure-aware replay built at pen-up, estimated 2–8 px; native curve unverified"
        else if (firmwareTest) "App ink after one second of quiet" else "Android live drawing"
    val pressureSummary get() = if (pressureSamples == 0L) "none" else
        String.format(Locale.ROOT, "%d normalized samples, %.3f–%.3f", pressureSamples, minPressure, maxPressure)
    var samples = 0L
        private set
    var maxEventAgeMs = 0L
        private set
    var onStrokeFinished: (() -> Unit)? = null
    private val settle: Runnable = Runnable {
        // Native completion is authoritative: firmware may suppress duplicate Android UPs.
        if ((captureNative != null || pointer < 0) && nativePenDown?.invoke() != true) {
            captureNative?.let { capture ->
                // Freeze the completed image: an unrelated Android redraw must never expose
                // a new native stroke early and masquerade as firmware presentation.
                val image = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                if (capture(Canvas(image))) {
                    nativeSnapshot?.recycle()
                    nativeSnapshot = image
                } else {
                    image.recycle()
                    // A new native sample may have arrived between the UI guard and the
                    // locked capture. Keep the old image until a full quiet interval.
                    postDelayed(settle, 1000)
                    return@Runnable
                }
            }
            committed = completed.size; invalidate(); onStrokeFinished?.invoke()
        }
    }

    fun mode(firmware: Boolean) {
        captureNative = null; nativePenDown = null
        systemHandoff = false
        clear(); firmwareTest = firmware
    }
    /** Request System preview; Android draws only completed strokes in this mode. */
    fun systemMode() { mode(true); systemHandoff = true }
    /** Focus/pane changes cancel only unfinished ink; completed pressure outlines stay available. */
    fun pauseCurrentSystemStroke() {
        if (!systemHandoff) return
        removeCallbacks(settle)
        current = null; pointer = -1; inside = false; pressurePointCount = 0
        committed = completed.size
        invalidate()
    }
    fun nativeMode(capture: (Canvas) -> Boolean, penDown: () -> Boolean) {
        mode(true)
        captureNative = capture; nativePenDown = penDown
    }
    fun nativeStrokeFinished() { removeCallbacks(settle); postDelayed(settle, 1000) }
    fun clear() {
        removeCallbacks(settle)
        completed.clear(); current = null; pointer = -1; committed = 0; inside = false
        pressurePointCount = 0
        samples = 0; maxEventAgeMs = 0
        pressureSamples = 0; minPressure = 0f; maxPressure = 0f
        nativeSnapshot?.recycle(); nativeSnapshot = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val spacing = 48 * resources.displayMetrics.density
        var y = spacing
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, lines); y += spacing }
        nativeSnapshot?.let { canvas.drawBitmap(it, 0f, 0f, null); return }
        val count = if (firmwareTest) committed else completed.size
        for (index in 0 until count) completed[index].let { canvas.drawPath(it.path, if (it.pressureAware) pressureInk else ink) }
        if (!firmwareTest) current?.let { canvas.drawPath(it.path, ink) }
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
            current = ScratchStroke(pressureAware = systemHandoff); inside = false; pressurePointCount = 0
            point(event.getX(index), event.getY(index), event.getPressure(index), event.eventTime)
        } else if (action == MotionEvent.ACTION_MOVE && pointer >= 0) {
            val pen = event.findPointerIndex(pointer)
            if (pen >= 0) {
                for (history in 0 until event.historySize) point(event.getHistoricalX(pen, history),
                    event.getHistoricalY(pen, history), event.getHistoricalPressure(pen, history), event.getHistoricalEventTime(history))
                point(event.getX(pen), event.getY(pen), event.getPressure(pen), event.eventTime)
            }
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
            event.getPointerId(index) == pointer) {
            point(event.getX(index), event.getY(index), event.getPressure(index), event.eventTime)
            current?.let { stroke ->
                if (stroke.pressureAware) buildPressureOutline(stroke.path)
                if (!stroke.path.isEmpty) completed.add(stroke)
            }
            current = null; pointer = -1; inside = false; pressurePointCount = 0
            if (systemHandoff) {
                // Keep completed ink ready at pen-up instead of waiting for a quiet timer.
                // Never include the current stroke: live motion is the requested System preview.
                committed = completed.size
                invalidate()
                onStrokeFinished?.invoke()
            } else if (firmwareTest) postDelayed(settle, 1000)
            else { invalidate(); onStrokeFinished?.invoke() }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            current = null; pointer = -1; inside = false; pressurePointCount = 0
            if (systemHandoff || !firmwareTest) invalidate() else postDelayed(settle, 1000)
        }
        return true
    }

    private fun point(x: Float, y: Float, pressure: Float, time: Long) {
        if (samples >= MAX_SAMPLES) return
        samples++
        maxEventAgeMs = maxOf(maxEventAgeMs, (SystemClock.uptimeMillis() - time).coerceIn(0, 60_000))
        if (x < 0 || y < 0 || x >= width || y >= height) { inside = false; return }
        val stroke = current ?: return
        val path = stroke.path
        if (stroke.pressureAware) {
            val normalized = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
            if (pressureSamples == 0L) { minPressure = normalized; maxPressure = normalized }
            else { minPressure = minOf(minPressure, normalized); maxPressure = maxOf(maxPressure, normalized) }
            pressureSamples++
            val offset = pressurePointCount * 3
            pressurePoints[offset] = x
            pressurePoints[offset + 1] = y
            pressurePoints[offset + 2] = normalized
            pressureStarts[pressurePointCount++] = !inside
        } else if (!inside) { path.moveTo(x, y); path.lineTo(x + 0.01f, y) }
        else if (x != lastX || y != lastY) path.lineTo(x, y)
        lastX = x; lastY = y; inside = true
        // Firmware mode deliberately never asks Android to show in-flight ink.
        if (!firmwareTest) invalidate()
    }

    private fun buildPressureOutline(path: Path) {
        var previousX = 0f
        var previousY = 0f
        var previousRadius = 1f
        for (index in 0 until pressurePointCount) {
            val offset = index * 3
            val x = pressurePoints[offset]
            val y = pressurePoints[offset + 1]
            // Independent replay estimate, not the firmware's unknown brush curve.
            val radius = (2f + 6f * pressurePoints[offset + 2]) / 2f
            if (!pressureStarts[index]) {
                val distance = hypot(x - previousX, y - previousY)
                if (distance > 0f) {
                    val nx = (y - previousY) / distance
                    val ny = (previousX - x) / distance
                    path.moveTo(previousX + nx * previousRadius, previousY + ny * previousRadius)
                    path.lineTo(x + nx * radius, y + ny * radius)
                    path.lineTo(x - nx * radius, y - ny * radius)
                    path.lineTo(previousX - nx * previousRadius, previousY - ny * previousRadius)
                    path.close()
                }
            }
            path.addCircle(x, y, radius, Path.Direction.CW)
            previousX = x; previousY = y; previousRadius = radius
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle); nativeSnapshot?.recycle(); nativeSnapshot = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_SAMPLES = 50_000
    }
}
