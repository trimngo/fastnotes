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
    private var lastRadius = 1f
    private var inside = false
    private var nativeSnapshot: Bitmap? = null
    private var captureNative: ((Canvas) -> Boolean)? = null
    private var nativePenDown: (() -> Boolean)? = null
    var firmwareTest = false
        private set
    private var systemHandoff = false
    var hoverPreparationEnabled = true
    var hoverPreparationRequests = 0L
        private set
    private var systemRegistered = false
    private var registeredSince: Long? = null
    private var lastEndUptime: Long? = null
    private var idleGeneration = 0L
    private var preparedGeneration = -1L
    private var lastHoverRequestUptime: Long? = null
    private var idleSequence = 0L
    private data class IdleStart(
        val sequence: Long, val downEventTime: Long, val idleMs: Long?,
        val downEventAgeMs: Long, val registrationAgeMs: Long?,
        val hoverPreparationAgeMs: Long?, val registered: Boolean,
        val prepareEnabled: Boolean, var firstMoveAfterDownMs: Long? = null
    )
    private var idleStart: IdleStart? = null
    private val idleStarts = ArrayDeque<String>()
    val idleStartSummary get() = buildString {
        append("Hover redraw enabled=$hoverPreparationEnabled; requests=$hoverPreparationRequests. ")
        append("Idle starts (last ${idleStarts.size}; input timings exclude visible ink latency):")
        if (idleStarts.isNotEmpty()) append('\n').append(idleStarts.joinToString("\n"))
    }
    val completedStrokes get() = completed.size
    private var pressureSamples = 0L
    private var minPressure = 0f
    private var maxPressure = 0f
    val handoffDescription get() = if (systemHandoff)
        "Completed strokes at pen-up; pressure-aware replay, estimated 2–8 px; native curve unverified"
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
    /** Firmware supplies live ink; Android prepares only completed strokes for its eventual clear. */
    fun systemMode() { mode(true); systemHandoff = true }
    /** A redraw request is eligible only while this view owns an accepted System ink session. */
    fun setSystemRegistration(active: Boolean) {
        if (active && systemHandoff) {
            if (!systemRegistered) registeredSince = SystemClock.uptimeMillis()
            systemRegistered = true
        } else {
            systemRegistered = false
            registeredSince = null
            lastHoverRequestUptime = null
        }
    }
    /** Focus/pane changes cancel only unfinished ink; completed pressure outlines stay available. */
    fun pauseCurrentSystemStroke() {
        setSystemRegistration(false)
        if (!systemHandoff) return
        finishIdleStart("pause")
        removeCallbacks(settle)
        current = null; pointer = -1; inside = false
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
        samples = 0; maxEventAgeMs = 0
        pressureSamples = 0; minPressure = 0f; maxPressure = 0f
        setSystemRegistration(false)
        lastEndUptime = null; idleGeneration = 0; preparedGeneration = -1
        idleSequence = 0; idleStart = null; idleStarts.clear(); hoverPreparationRequests = 0
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
            if (systemHandoff) beginIdleStart(event.eventTime)
            pointer = event.getPointerId(index)
            current = ScratchStroke(pressureAware = systemHandoff); inside = false
            point(event.getX(index), event.getY(index), event.getPressure(index), event.eventTime)
        } else if (action == MotionEvent.ACTION_MOVE && pointer >= 0) {
            val pen = event.findPointerIndex(pointer)
            if (pen >= 0) {
                idleStart?.let { start ->
                    if (start.firstMoveAfterDownMs == null) {
                        val first = if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime
                        start.firstMoveAfterDownMs = interval(start.downEventTime, first)
                    }
                }
                for (history in 0 until event.historySize) point(event.getHistoricalX(pen, history),
                    event.getHistoricalY(pen, history), event.getHistoricalPressure(pen, history), event.getHistoricalEventTime(history))
                point(event.getX(pen), event.getY(pen), event.getPressure(pen), event.eventTime)
            }
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
            event.getPointerId(index) == pointer) {
            point(event.getX(index), event.getY(index), event.getPressure(index), event.eventTime)
            current?.let { completed.add(it) }
            current = null; pointer = -1; inside = false
            if (systemHandoff) {
                endIdlePeriod("up")
                // Prepare the underlying Android frame before the firmware's 800 ms overlay clear.
                // Never include the current stroke: live motion must still come from System ink.
                committed = completed.size
                invalidate()
                onStrokeFinished?.invoke()
            } else if (firmwareTest) postDelayed(settle, 1000)
            else { invalidate(); onStrokeFinished?.invoke() }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            val hadPointer = pointer >= 0
            current = null; pointer = -1; inside = false
            if (systemHandoff && hadPointer) endIdlePeriod("cancel")
            if (systemHandoff || !firmwareTest) invalidate() else postDelayed(settle, 1000)
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (systemHandoff && systemRegistered && hoverPreparationEnabled && pointer < 0 &&
            event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE)) {
            val ended = lastEndUptime
            val now = SystemClock.uptimeMillis()
            val x = event.getX(0); val y = event.getY(0)
            if (ended != null && interval(ended, now) >= 1000 && preparedGeneration != idleGeneration &&
                x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height) {
                preparedGeneration = idleGeneration
                lastHoverRequestUptime = now
                hoverPreparationRequests++
                // Only request a frame of existing completed ink. This is not a native
                // wake command or evidence that the physical panel has become ready.
                postInvalidateOnAnimation()
            }
        }
        return super.onHoverEvent(event)
    }

    private fun interval(start: Long, end: Long) = (end - start).coerceIn(0, 600_000)

    private fun beginIdleStart(eventTime: Long) {
        val now = SystemClock.uptimeMillis()
        idleStart = IdleStart(++idleSequence, eventTime, lastEndUptime?.let { interval(it, now) },
            (now - eventTime).coerceIn(0, 60_000), registeredSince?.let { interval(it, now) },
            lastHoverRequestUptime?.let { interval(it, now) }, systemRegistered, hoverPreparationEnabled)
    }

    private fun endIdlePeriod(reason: String) {
        finishIdleStart(reason)
        lastEndUptime = SystemClock.uptimeMillis()
        idleGeneration++
        lastHoverRequestUptime = null
    }

    private fun finishIdleStart(reason: String) {
        val start = idleStart ?: return
        idleStart = null
        idleStarts.addLast("#${start.sequence} idleMs=${start.idleMs ?: "none"}; " +
            "downEventAgeMs=${start.downEventAgeMs}; registrationAgeMs=${start.registrationAgeMs ?: "none"}; " +
            "hoverPreparationAgeMs=${start.hoverPreparationAgeMs ?: "none"}; " +
            "firstMoveAfterDownMs=${start.firstMoveAfterDownMs ?: "none"}; " +
            "registered=${start.registered}; prepareEnabled=${start.prepareEnabled}; end=$reason")
        while (idleStarts.size > 6) idleStarts.removeFirst()
    }

    private fun point(x: Float, y: Float, pressure: Float, time: Long) {
        if (samples >= 50_000) return
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
            // Independent replay estimate inspired by the service defaults, not its unknown JNI curve.
            val radius = (2f + 6f * normalized) / 2f
            if (inside) {
                val distance = hypot(x - lastX, y - lastY)
                if (distance > 0f) {
                    val nx = (y - lastY) / distance
                    val ny = (lastX - x) / distance
                    path.moveTo(lastX + nx * lastRadius, lastY + ny * lastRadius)
                    path.lineTo(x + nx * radius, y + ny * radius)
                    path.lineTo(x - nx * radius, y - ny * radius)
                    path.lineTo(lastX - nx * lastRadius, lastY - ny * lastRadius)
                    path.close()
                }
            }
            path.addCircle(x, y, radius, Path.Direction.CW)
            lastRadius = radius
        } else if (!inside) { path.moveTo(x, y); path.lineTo(x + 0.01f, y) }
        else if (x != lastX || y != lastY) path.lineTo(x, y)
        lastX = x; lastY = y; inside = true
        // Firmware mode deliberately never asks Android to show in-flight ink.
        if (!firmwareTest) invalidate()
    }

    override fun onDetachedFromWindow() {
        setSystemRegistration(false)
        removeCallbacks(settle); nativeSnapshot?.recycle(); nativeSnapshot = null
        super.onDetachedFromWindow()
    }
}
