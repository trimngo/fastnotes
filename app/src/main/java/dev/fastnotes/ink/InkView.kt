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
class InkView(context: Context) : FrameLayout(context) {
    private val lock = Any()
    private var editor: NoteEditor? = null
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val paperPaint = Paint().apply { color = Color.rgb(180, 180, 180); strokeWidth = 1f }
    private data class Segment(val a: InkPoint, val b: InkPoint, val width: Float)
    private val pending = ArrayDeque<Segment>()
    private var redrawFront = true
    private val classic = object : View(context) {
        override fun onDraw(canvas: Canvas) { drawScene(canvas) }
    }
    private val front = LowLatencyCanvasView(context)
    private val panel = ViwoodsPanel()
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
        set(value) { finishGesture(false); field = value }
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
        this.editor = editor
        rebuild()
        refreshEngine()
    }

    fun setRendering(frontBuffer: Boolean, viwoods: Boolean) {
        frontEnabled = frontBuffer
        panelWanted = viwoods
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
    val engineDescription: String get() = when {
        panel.active -> panel.status
        panelWanted && multiWindow -> "Android ink · panel mode paused in split-screen"
        panelWanted && panel.status.startsWith("Panel API") -> panel.status
        front.visibility == View.VISIBLE -> "Android front-buffer ink"
        else -> "Android compatibility ink"
    }

    fun documentChanged() { finishGesture(false); rebuild() }

    private fun viewport(): Viewport? = editor?.note?.let { Viewport(it.pageWidth, width, height, it.scrollY) }

    fun scrollByScreen(pixels: Float) {
        if (penPointer >= 0 && !movingPen) return
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
        panel.stop()
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
        panel.stop()
        super.onDetachedFromWindow()
    }

    private fun refreshEngine() {
        panel.stop()
        val image = bitmap
        if (panelWanted && foreground && !obscured && !multiWindow && hasWindowFocus() &&
            image != null && image.width == width && image.height == height) {
            val location = IntArray(2)
            getLocationOnScreen(location)
            val visible = Rect()
            if (getGlobalVisibleRect(visible) && visible.width() == width && visible.height() == height) {
                panel.start(context, image, location[0], location[1], resources.configuration.orientation)
            }
        }
        front.visibility = if (frontEnabled && isHardwareAccelerated && !panel.active) View.VISIBLE else View.GONE
        classic.visibility = if (front.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        presentScene()
        onEngineChanged?.invoke()
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        // Stop any firmware reference before replacing or repainting its bitmap.
        val wasPanel = panel.active
        panel.stop()
        synchronized(lock) {
            pending.clear()
            if (bitmap?.width != width || bitmap?.height != height) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmapCanvas = Canvas(bitmap!!)
            }
            val canvas = bitmapCanvas ?: return
            canvas.drawColor(Color.WHITE)
            val view = viewport() ?: return
            val note = editor!!.note
            val firstLine = floor(view.scrollY / note.lineSpacing).toInt()
            val lastLine = ceil((view.scrollY + view.visibleHeight) / note.lineSpacing).toInt()
            for (i in firstLine..lastLine) {
                val y = (i * note.lineSpacing - view.scrollY) * view.scale
                canvas.drawLine(0f, y, width.toFloat(), y, paperPaint)
            }
            val margin = note.pageWidth * 0.055f * view.scale
            canvas.drawLine(margin, 0f, margin, height.toFloat(), paperPaint)
            for (stroke in note.strokes) {
                if (stroke.id !in erased && stroke.bottom + stroke.width >= view.scrollY &&
                    stroke.top - stroke.width <= view.scrollY + view.visibleHeight) {
                    drawStroke(canvas, stroke.points, stroke.width, view)
                }
            }
            if (!gestureEraser && activePoints.isNotEmpty()) drawStroke(canvas, activePoints, note.penWidth, view)
            if (!gestureEraser) for (piece in pieces) drawStroke(canvas, piece, note.penWidth, view)
        }
        presentScene()
        if (wasPanel) post { refreshEngine() }
    }

    private fun drawStroke(canvas: Canvas, points: List<InkPoint>, width: Float, view: Viewport) {
        if (points.isEmpty()) return
        if (points.size == 1) drawSegment(canvas, Segment(view.screen(points[0]), view.screen(points[0]), width * view.scale))
        for (i in 1 until points.size) drawSegment(canvas, Segment(view.screen(points[i - 1]),
            view.screen(points[i]), width * view.scale))
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
            if (panel.active) {
                panel.render(dirty)
                if (!panel.active) post { refreshEngine() }
            }
            if (front.visibility == View.VISIBLE) front.renderFrontBufferedLayer()
            else {
                synchronized(lock) { pending.clear() }
                classic.invalidate(dirty)
            }
        }
    }

    fun finishGesture(cancel: Boolean) {
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
        if (penPointer >= 0 || (palmGuard && SystemClock.uptimeMillis() - lastPenTime < 500)) {
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
}
