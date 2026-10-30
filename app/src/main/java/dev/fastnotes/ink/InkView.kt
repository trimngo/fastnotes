package dev.fastnotes.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.graphics.lowlatency.LowLatencyCanvasView
import androidx.annotation.AnyThread
import dev.fastnotes.model.*
import kotlin.math.*

enum class Tool { PEN, ERASER, MOVE }

/** UI-thread model/input, render-thread bitmap reads. Disk I/O never enters this class. */
class InkView(context: Context, private val panel: ViwoodsPanel = ViwoodsPanel()) : FrameLayout(context) {
    private val lock = Any()
    private var editor: NoteEditor? = null
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val raster = PaperRasterizer()
    private data class Segment(val a: InkPoint, val b: InkPoint, val width: Float)
    private val pending = ArrayDeque<Segment>()
    private var redrawFront = true
    private val classic = object : View(context) {
        override fun onDraw(canvas: Canvas) { drawScene(canvas) }
    }
    private val front = LowLatencyCanvasView(context)
    private var panelFailed = false
    private var nativeFailureReason = ""
    private var drainingNative = false
    private val heldAndroidEvents = ArrayList<MotionEvent>()
    private var heldDownTime = Long.MAX_VALUE
    private val nativeWatchdog = Runnable {
        if (panel.active && heldAndroidEvents.isNotEmpty()) {
            if (panel.lastInkTime >= heldDownTime) clearHeldEvents()
            else failNative("No in-window native pen samples")
        }
    }
    private var frontEnabled = true
    private var panelWanted = false
    private var foreground = false
    private var obscured = false
    private var multiWindow = false
    private var penPointer = -1
    private var movingPen = false
    private var gestureEraser = false
    private val activePoints = ArrayList<InkPoint>()
    private val pieces = ArrayList<List<InkPoint>>()
    private val erased = HashSet<Long>()
    private var lastPenTime = Long.MIN_VALUE / 2
    private var touchCentroid: Float? = null
    private var touchCount = 0
    private var gestureViewport: Viewport? = null
    var tool = Tool.PEN
        set(value) {
            finishGesture(false)
            stopNative()
            field = value
            refreshEngine()
        }
    var palmGuard = true
        set(value) { touchCentroid = null; field = value }
    var onEdited: (() -> Unit)? = null
    var onScrolled: (() -> Unit)? = null
    var onEngineChanged: (() -> Unit)? = null
    var sampleCount = 0L
        private set
    var sampleAgeTotalMs = 0L
        private set

    init {
        panel.onEditsAvailable = { post { drainNative() } }
        panel.onFailure = { post { failNative(panel.status) } }
        setBackgroundColor(Color.WHITE)
        clipChildren = true
        clipToPadding = true
        isFocusable = true
        contentDescription = "Lined handwriting sheet. Use the pen to write. Palm guard uses two fingers to scroll."
        addView(classic, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        front.setRenderCallback(object : LowLatencyCanvasView.Callback {
            override fun onRedrawRequested(canvas: Canvas, width: Int, height: Int) {
                synchronized(lock) {
                    drawScene(canvas)
                    pending.clear()
                    redrawFront = false
                }
            }
            override fun onDrawFrontBufferedLayer(canvas: Canvas, width: Int, height: Int) {
                synchronized(lock) {
                    canvas.save()
                    canvas.clipRect(0, 0, width, height)
                    if (redrawFront) {
                        drawScene(canvas)
                        pending.clear()
                        redrawFront = false
                    } else {
                        while (pending.isNotEmpty()) drawSegment(canvas, pending.removeFirst())
                    }
                    canvas.restore()
                }
            }
        })
        // With software rendering (including JVM UI tests), use the ordinary view.
        front.visibility = View.GONE
    }

    fun bind(editor: NoteEditor) {
        finishGesture(false)
        stopNative()
        this.editor = editor
        rebuild()
        refreshEngine()
    }

    fun setRendering(frontBuffer: Boolean, viwoods: Boolean) {
        frontEnabled = frontBuffer
        panelWanted = viwoods
        panelFailed = false
        nativeFailureReason = ""
        refreshEngine()
    }

    fun setEnvironment(resumed: Boolean, modal: Boolean, multiWindow: Boolean) {
        foreground = resumed
        obscured = modal
        this.multiWindow = multiWindow
        if (!resumed || modal) finishGesture(false)
        refreshEngine()
    }

    val panelAvailable get() = panel.available()
    val nativeDiagnostics get() = panel.status + "\nNative callbacks: " + panel.rawEventCount +
        " · accepted samples: " + panel.sampleCount + " · panel updates: " + panel.renderCount +
        "\nLast native picture mode: " + panel.lastActiveMode + " (4 = FAST)" +
        if (nativeFailureReason.isBlank()) "" else "\nFallback: " + nativeFailureReason
    val nativeActive get() = panel.active
    val engineDescription: String get() = when {
        panel.active -> "Viwoods direct pen input"
        panelFailed -> "Android ink · " + nativeFailureReason
        panelWanted && !panelAvailable -> "Android ink · native callback API unavailable"
        panelWanted && tool != Tool.MOVE && (!foreground || obscured || !hasWindowFocus()) ->
            "Viwoods native ink paused for dialog or background"
        front.visibility == View.VISIBLE -> "Android front-buffer ink"
        else -> "Android compatibility ink"
    }

    fun documentChanged() { finishGesture(false); rebuild() }

    private fun viewport(): Viewport? = editor?.note?.let { Viewport(it.pageWidth, width, height, it.scrollY) }

    fun scrollByScreen(pixels: Float) {
        if ((penPointer >= 0 && !movingPen) || panel.penDown) return
        stopNative()
        val view = viewport() ?: return
        editor?.scroll(view.scrollY + pixels / view.scale)
        rebuild()
        onScrolled?.invoke()
    }

    fun scrollToTop() {
        finishGesture(false)
        editor?.scroll(0f)
        rebuild()
        onScrolled?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        stopNative()
        finishGesture(false)
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        post { refreshEngine() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) finishGesture(false)
        super.onWindowFocusChanged(hasWindowFocus)
        refreshEngine()
    }

    override fun onDetachedFromWindow() {
        finishGesture(false)
        stopNative()
        super.onDetachedFromWindow()
    }

    private fun refreshEngine() {
        stopNative()
        val image = bitmap
        if (editor != null && panelWanted && !panelFailed && tool != Tool.MOVE && foreground && !obscured && hasWindowFocus() &&
            image != null && image.width == width && image.height == height) {
            val location = IntArray(2)
            getLocationOnScreen(location)
            val visible = Rect()
            if (getGlobalVisibleRect(visible) && visible.width() == width && visible.height() == height) {
                val started = panel.start(context, image, editor!!.note, location[0], location[1],
                    resources.configuration.orientation, tool == Tool.ERASER)
                if (!started && panel.available()) {
                    panelFailed = true
                    nativeFailureReason = panel.status
                }
            }
        }
        front.visibility = if (frontEnabled && isHardwareAccelerated && !panel.active) View.VISIBLE else View.GONE
        classic.visibility = if (front.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        presentScene()
        onEngineChanged?.invoke()
    }

    private fun rebuild(restartNative: Boolean = true) {
        if (width <= 0 || height <= 0) return
        // Stop any firmware reference before replacing or repainting its bitmap.
        val wasPanel = panel.active
        if (restartNative) stopNative()
        synchronized(lock) {
            pending.clear()
            if (bitmap?.width != width || bitmap?.height != height) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmapCanvas = Canvas(bitmap!!)
            }
            val canvas = bitmapCanvas ?: return
            val view = viewport() ?: return
            val note = editor!!.note
            raster.page(canvas, note, view, erased)
            if (!gestureEraser && activePoints.isNotEmpty()) raster.stroke(canvas, activePoints, note.penWidth, view)
            if (!gestureEraser) for (piece in pieces) raster.stroke(canvas, piece, note.penWidth, view)
        }
        if (panel.active) classic.invalidate() else presentScene()
        if (restartNative && (wasPanel || panelWanted)) post { refreshEngine() }
    }

    @AnyThread
    private fun drawSegment(canvas: Canvas, segment: Segment) {
        paint.strokeWidth = segment.width
        if (segment.a == segment.b) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(segment.a.x, segment.a.y, segment.width / 2, paint)
            paint.style = Paint.Style.STROKE
        } else canvas.drawLine(segment.a.x, segment.a.y, segment.b.x, segment.b.y, paint)
    }

    @AnyThread
    private fun drawScene(canvas: Canvas) {
        if (panel.drawPreview(canvas)) return
        synchronized(lock) {
            canvas.drawColor(Color.WHITE)
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    private fun presentScene() {
        classic.invalidate()
        if (front.visibility == View.VISIBLE) {
            // commit() only hands off the current hardware buffer; it does not redraw it.
            // Explicitly render the current canonical bitmap before handing it to HWUI.
            synchronized(lock) { redrawFront = true; pending.clear() }
            front.renderFrontBufferedLayer()
            front.commit()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editor == null || obscured) return false
        if (panel.active && tool != Tool.MOVE &&
            (0 until event.pointerCount).any { isPen(event, it) }) {
            lastPenTime = SystemClock.uptimeMillis()
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                panel.finish(true)
                drainNative()
                clearHeldEvents()
            } else if (panel.lastInkTime >= event.downTime) {
                clearHeldEvents()
            } else {
                val firstHeld = heldAndroidEvents.isEmpty()
                if (firstHeld) heldDownTime = event.downTime
                heldAndroidEvents.add(MotionEvent.obtain(event))
                // A connected-but-silent private listener must not silently eat a stroke.
                if (firstHeld) postDelayed(nativeWatchdog, 120)
                if (heldAndroidEvents.size > 100) failNative("Native listener did not produce ink")
            }
            return true
        }
        return androidTouch(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun androidTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            finishGesture(true); touchCentroid = null; return true
        }
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (isPen(event, index)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    requestUnbufferedDispatch(event)
                    finishGesture(false)
                    penPointer = event.getPointerId(index)
                    lastPenTime = SystemClock.uptimeMillis()
                    movingPen = tool == Tool.MOVE
                    gestureEraser = eraser(event, index)
                    gestureViewport = viewport()
                    touchCentroid = if (movingPen) event.getY(index) else null
                    if (!movingPen) sample(event.getX(index), event.getY(index), event.eventTime)
                } else touchCentroid = null
            }
            MotionEvent.ACTION_MOVE -> {
                val penIndex = event.findPointerIndex(penPointer)
                if (penIndex >= 0) {
                    requestUnbufferedDispatch(event)
                    lastPenTime = SystemClock.uptimeMillis()
                    if (movingPen) {
                        val y = event.getY(penIndex)
                        touchCentroid?.let { scrollByScreen(it - y) }
                        touchCentroid = y
                    } else {
                        for (history in 0 until event.historySize) {
                            sample(event.getHistoricalX(penIndex, history), event.getHistoricalY(penIndex, history),
                                event.getHistoricalEventTime(history))
                        }
                        sample(event.getX(penIndex), event.getY(penIndex), event.eventTime)
                    }
                } else scrollTouch(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(index) == penPointer) {
                    val canceled = Build.VERSION.SDK_INT >= 33 &&
                        event.flags and MotionEvent.FLAG_CANCELED != 0
                    if (!movingPen && !canceled) sample(event.getX(index), event.getY(index), event.eventTime)
                    finishGesture(canceled)
                }
                touchCentroid = null
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 0 && isPen(event, 0)) lastPenTime = SystemClock.uptimeMillis()
        return true
    }

    private fun isPen(event: MotionEvent, index: Int) =
        event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER

    private fun eraser(event: MotionEvent, index: Int) = tool == Tool.ERASER ||
        event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
        event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

    private fun sample(x: Float, y: Float, time: Long) {
        if (!x.isFinite() || !y.isFinite()) return
        val view = gestureViewport ?: return
        // Keep separate pieces until pen-up, so CANCEL can discard the entire gesture.
        if (x < 0 || x >= width || y < 0 || y >= height) {
            if (activePoints.isNotEmpty()) {
                if (!gestureEraser) pieces.add(activePoints.toList())
                activePoints.clear()
            }
            return
        }
        val point = view.document(x, y)
        val previous = activePoints.lastOrNull() ?: point
        if (point == previous && activePoints.isNotEmpty()) return
        activePoints.add(point)
        sampleCount++
        sampleAgeTotalMs += (SystemClock.uptimeMillis() - time).coerceIn(0, 10_000)
        if (gestureEraser) {
            val radius = 14f * resources.displayMetrics.density / view.scale
            var changed = false
            for (stroke in editor!!.note.strokes) {
                if (stroke.id !in erased && Eraser.hits(stroke, previous, point, radius)) {
                    erased.add(stroke.id); changed = true
                }
            }
            if (changed) rebuild()
        } else {
            val segment = Segment(view.screen(previous), view.screen(point), editor!!.note.penWidth * view.scale)
            synchronized(lock) {
                bitmapCanvas?.let { drawSegment(it, segment) }
                pending.addLast(segment)
            }
            val padding = ceil(segment.width + 3).toInt()
            val dirty = Rect(floor(min(segment.a.x, segment.b.x)).toInt() - padding,
                floor(min(segment.a.y, segment.b.y)).toInt() - padding,
                ceil(max(segment.a.x, segment.b.x)).toInt() + padding,
                ceil(max(segment.a.y, segment.b.y)).toInt() + padding)
            if (front.visibility == View.VISIBLE) front.renderFrontBufferedLayer()
            else {
                synchronized(lock) { pending.clear() }
                classic.invalidate(dirty)
            }
        }
    }

    fun finishGesture(cancel: Boolean) {
        if (!drainingNative) {
            panel.finish(cancel)
            drainNative()
        }
        val changed = !cancel && (if (gestureEraser) erased.isNotEmpty()
            else activePoints.isNotEmpty() || pieces.isNotEmpty())
        if (changed) {
            if (gestureEraser) editor?.erase(erased)
            else {
                val strokes = (pieces + listOf(activePoints.toList())).filter { it.isNotEmpty() }
                    .map { editor!!.newStroke(it) }
                editor?.addAll(strokes)
            }
        }
        val hadPreview = activePoints.isNotEmpty() || erased.isNotEmpty() || pieces.isNotEmpty()
        activePoints.clear()
        pieces.clear()
        erased.clear()
        penPointer = -1
        gestureViewport = null
        movingPen = false
        synchronized(lock) { pending.clear() }
        if (cancel && hadPreview) rebuild() else if (hadPreview) presentScene()
        if (changed) onEdited?.invoke()
    }

    private fun scrollTouch(event: MotionEvent) {
        if (penPointer >= 0 || panel.penDown || (palmGuard &&
            SystemClock.uptimeMillis() - maxOf(lastPenTime, panel.lastPenTime) < 500)) {
            touchCentroid = null; return
        }
        val indices = (0 until event.pointerCount).filter {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER &&
                event.getTouchMajor(it) < 36 * resources.displayMetrics.density
        }
        val required = if (palmGuard && tool != Tool.MOVE) 2 else 1
        if (indices.size < required) { touchCentroid = null; return }
        val y = indices.sumOf { event.getY(it).toDouble() }.toFloat() / indices.size
        if (touchCount != indices.size) touchCentroid = null
        touchCount = indices.size
        touchCentroid?.let { scrollByScreen(it - y) }
        touchCentroid = y
    }

    private fun drainNative() {
        if (drainingNative) return
        drainingNative = true
        try {
            val completed = panel.drainEdits()
            for (edit in completed) {
                when (edit) {
                    is NativeEdit.Add -> editor?.addAll(edit.strokes)
                    is NativeEdit.Erase -> editor?.erase(edit.ids)
                }
                onEdited?.invoke()
            }
            if (completed.isNotEmpty()) {
                clearHeldEvents()
                // Keep the native preview untouched if a newer stroke is already in flight.
                rebuild(restartNative = false)
            }
            panel.acknowledge()
            if (completed.isNotEmpty()) onEngineChanged?.invoke()
        } finally { drainingNative = false }
    }

    private fun stopNative() {
        panel.stop()
        drainNative()
        clearHeldEvents()
    }

    private fun clearHeldEvents() {
        removeCallbacks(nativeWatchdog)
        heldAndroidEvents.forEach { it.recycle() }
        heldAndroidEvents.clear()
        heldDownTime = Long.MAX_VALUE
    }

    private fun failNative(reason: String) {
        if (panelFailed) return
        panelFailed = true
        nativeFailureReason = reason
        val replay = if (panel.lastInkTime < heldDownTime) heldAndroidEvents.toList() else emptyList()
        val discard = if (replay.isEmpty()) heldAndroidEvents.toList() else emptyList()
        heldAndroidEvents.clear()
        removeCallbacks(nativeWatchdog)
        panel.stop()
        drainNative()
        refreshEngine()
        discard.forEach { it.recycle() }
        for (event in replay) {
            androidTouch(event)
            event.recycle()
        }
    }
}
