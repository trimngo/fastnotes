package dev.fastnotes.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import dev.fastnotes.model.*
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.*

sealed interface NativeEdit {
    data class Add(val strokes: List<Stroke>) : NativeEdit
    data class Erase(val ids: Set<Long>) : NativeEdit
}

/**
 * Independent implementation of the direct ENote callback protocol researched in ForestNote.
 * The callback thread owns its bitmap; the UI receives immutable completed edits, never points.
 * No View access, file I/O, global auto-draw rectangles or proprietary binaries in this class.
 */
class ViwoodsPanel(private val className: String = "android.os.enote.ENoteSetting") {
    private val lock = Any()
    private var instance: Any? = null
    private var methods = emptyList<Method>()
    private var listener: Any? = null
    private var generation = 0L
    private var frame: Bitmap? = null
    private var canvas: Canvas? = null
    private val raster = PaperRasterizer()
    private var page = Note()
    private var view = Viewport(1000f, 1, 1, 0f)
    private var screenBounds = Rect()
    private var orientation = 1
    private var previousMode: Int? = null
    private var nextId = 1L
    private var forceEraser = false
    private var eraserRadius = 14f
    private val points = ArrayList<InkPoint>()
    private val pieces = ArrayList<List<InkPoint>>()
    private val erased = HashSet<Long>()
    private val edits = ArrayList<NativeEdit>()
    private var strokeIsEraser = false
    private var waitingForHandoff = false
    private var renderBatch = Rect()
    private var batchCount = 0
    var onEditsAvailable: (() -> Unit)? = null
    var onFailure: (() -> Unit)? = null
    @Volatile var active = false
        private set
    @Volatile var penDown = false
        private set
    @Volatile var lastPenTime = Long.MIN_VALUE / 2
        private set
    @Volatile var sampleCount = 0L
        private set
    @Volatile var rawEventCount = 0L
        private set
    @Volatile var lastInkTime = Long.MIN_VALUE / 2
        private set
    @Volatile var renderCount = 0L
        private set
    @Volatile var lastActiveMode = -1
        private set
    @Volatile var status = "Native ink not started"
        private set
    @Volatile var failureStage = ""
        private set

    fun availabilityReport(): String = try {
        val type = Class.forName(className, false, javaClass.classLoader)
        val all = type.methods
        val missing = REQUIRED.filterNot { name -> all.any { it.name == name } }
        when {
            missing.isNotEmpty() -> "Missing native methods: " + missing.joinToString(", ")
            all.none { it.name == "setWritingInputlistener" &&
                it.parameterTypes.singleOrNull()?.isInterface == true } -> "Native input listener has an unsupported signature"
            else -> "Available"
        }
    } catch (_: Throwable) { "Native ENoteSetting class not found" }

    fun available() = availabilityReport() == "Available"

    fun start(context: Context, source: Bitmap, note: Note, x: Int, y: Int,
              orientation: Int, erase: Boolean = false): Boolean {
        stop()
        val report = availabilityReport()
        if (report != "Available") { status = report; return false }
        return try {
            failureStage = "getInstance"
            val type = Class.forName(className)
            methods = type.methods.toList()
            instance = type.getMethod("getInstance").invoke(null)
            synchronized(lock) {
                frame = source.copy(Bitmap.Config.ARGB_8888, true)
                canvas = Canvas(frame!!)
                page = note
                view = Viewport(note.pageWidth, source.width, source.height, note.scrollY)
                screenBounds = Rect(x, y, x + source.width, y + source.height)
                this.orientation = orientation
                nextId = (note.strokes.maxOfOrNull { it.id } ?: 0L) + 1
                forceEraser = erase
                eraserRadius = 14f * context.resources.displayMetrics.density / view.scale
                points.clear(); pieces.clear(); erased.clear()
                penDown = false; waitingForHandoff = false
                renderBatch.setEmpty(); batchCount = 0
                generation++
            }
            optional("setApplicationContext", context.applicationContext)
            previousMode = optional("getPictureMode") as? Int
            call("setWritingJavaBitmap", frame, orientation, x, y)
            call("setWritingJavaBackgroundBitmap", frame, orientation, x, y)
            optional("setWritingInputJumpPointCount", 1)
            optional("setRenderWritingDelayCount", 0)
            val listenerType = methods.first { it.name == "setWritingInputlistener" &&
                it.parameterTypes.size == 1 }.parameterTypes[0]
            val epoch = generation
            listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "Fastnotes ENote listener"
                    "onInput" -> {
                        if (args != null && args.size == 8) receive(epoch, args)
                        null
                    }
                    else -> null
                }
            }
            call("setWritingInputlistener", listener)
            active = true
            call("setWritingEnabled", true)
            if (previousMode in setOf(0, 1, 2, 3, 4, 17)) optional("setPictureMode", 4)
            lastActiveMode = optional("getPictureMode") as? Int ?: -1
            status = "Viwoods direct pen input"
            failureStage = ""
            true
        } catch (_: Throwable) {
            val stage = failureStage
            stop()
            status = "Native ink unavailable at " + stage
            false
        }
    }

    private fun receive(epoch: Long, args: Array<out Any?>) {
        try {
            synchronized(lock) {
                if (!active || generation != epoch) return
                rawEventCount++
                val type = (args[4] as? Number)?.toInt() ?: return
                if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER) return
                lastPenTime = SystemClock.uptimeMillis()
                val pressure = (args[2] as Number).toInt()
                val rawAction = (args[5] as Number).toInt()
                val action = if (rawAction == MotionEvent.ACTION_HOVER_MOVE && (penDown || pressure > 0)) {
                    if (penDown) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_DOWN
                } else rawAction
                val x = (args[0] as Number).toFloat() - screenBounds.left
                val y = (args[1] as Number).toFloat() - screenBounds.top
                val buttons = (args[7] as Number).toInt()
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!inside(x, y)) return
                        if (penDown) finishLocked(false)
                        penDown = true
                        waitingForHandoff = false
                        strokeIsEraser = forceEraser || type == MotionEvent.TOOL_TYPE_ERASER ||
                            buttons and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                        points.clear(); pieces.clear(); erased.clear()
                        call("onWritingStart")
                        call("setWritingEnabled", true)
                        call("setWritingJavaBitmap", frame, orientation, screenBounds.left, screenBounds.top)
                        append(x, y)
                        flush()
                    }
                    MotionEvent.ACTION_MOVE -> if (penDown) append(x, y)
                    MotionEvent.ACTION_UP -> if (penDown) {
                        append(x, y)
                        finishLocked(false)
                    }
                    MotionEvent.ACTION_CANCEL -> if (penDown) finishLocked(true)
                }
            }
        } catch (_: Throwable) {
            status = "Native ink stopped at " + failureStage
            active = false
            onFailure?.invoke()
        }
    }

    private fun inside(x: Float, y: Float) = x >= 0 && y >= 0 && x < view.width && y < view.height

    private fun append(x: Float, y: Float) {
        if (!inside(x, y)) {
            if (!strokeIsEraser && points.isNotEmpty()) pieces.add(points.toList())
            points.clear()
            return
        }
        val point = view.document(x, y)
        val previous = points.lastOrNull() ?: point
        if (point == previous && points.isNotEmpty()) return
        points.add(point)
        sampleCount++
        lastInkTime = SystemClock.uptimeMillis()
        if (strokeIsEraser) {
            var dirty: Rect? = null
            for (stroke in page.strokes) {
                if (stroke.id !in erased && Eraser.hits(stroke, previous, point, eraserRadius)) {
                    erased.add(stroke.id)
                    val a = view.screen(InkPoint(stroke.left, stroke.top))
                    val b = view.screen(InkPoint(stroke.right, stroke.bottom))
                    val pad = ceil(stroke.width * view.scale + 5).toInt()
                    val rect = Rect(floor(a.x).toInt() - pad, floor(a.y).toInt() - pad,
                        ceil(b.x).toInt() + pad, ceil(b.y).toInt() + pad)
                    if (dirty == null) dirty = rect else dirty.union(rect)
                }
            }
            if (dirty != null) {
                raster.page(canvas!!, page, view, erased)
                queueRender(dirty, true)
            }
        } else {
            val a = view.screen(previous)
            val b = view.screen(point)
            val width = page.penWidth * view.scale
            raster.segment(canvas!!, a, b, width)
            val pad = ceil(width / 2f).toInt() + 4
            queueRender(Rect(floor(min(a.x, b.x)).toInt() - pad, floor(min(a.y, b.y)).toInt() - pad,
                ceil(max(a.x, b.x)).toInt() + pad, ceil(max(a.y, b.y)).toInt() + pad), false)
        }
    }

    private fun queueRender(local: Rect, force: Boolean) {
        val clipped = Rect(local)
        if (!clipped.intersect(0, 0, view.width, view.height)) return
        if (renderBatch.isEmpty) renderBatch.set(clipped) else renderBatch.union(clipped)
        if (++batchCount >= 2 || force) flush()
    }

    private fun flush() {
        if (renderBatch.isEmpty) return
        val screen = Rect(renderBatch)
        screen.offset(screenBounds.left, screenBounds.top)
        if (screen.intersect(screenBounds)) {
            call("renderWriting", screen)
            renderCount++
        }
        renderBatch.setEmpty(); batchCount = 0
    }

    private fun finishLocked(cancel: Boolean) {
        if (!penDown) return
        penDown = false
        if (cancel) {
            raster.page(canvas!!, page, view)
            queueRender(Rect(0, 0, view.width, view.height), true)
        } else if (strokeIsEraser && erased.isNotEmpty()) {
            val ids = erased.toSet()
            page = page.copy(strokes = page.strokes.filterNot { it.id in ids })
            edits.add(NativeEdit.Erase(ids))
        } else if (!strokeIsEraser) {
            val all = (pieces + listOf(points.toList())).filter { it.isNotEmpty() }
                .map { Stroke(nextId++, page.penWidth, it) }
            if (all.isNotEmpty()) {
                page = page.copy(strokes = page.strokes + all)
                edits.add(NativeEdit.Add(all))
            }
        }
        flush()
        points.clear(); pieces.clear(); erased.clear()
        waitingForHandoff = true
        onEditsAvailable?.invoke()
    }

    fun drainEdits(): List<NativeEdit> = synchronized(lock) {
        val result = edits.toList()
        edits.clear()
        result
    }

    fun acknowledge() = synchronized(lock) {
        if (active && !penDown && edits.isEmpty() && waitingForHandoff) {
            waitingForHandoff = false
            try {
                call("setWritingJavaBackgroundBitmap", frame, orientation, screenBounds.left, screenBounds.top)
                call("onWritingEnd")
            } catch (_: Throwable) {
                status = "Native handoff failed at " + failureStage
                active = false
                onFailure?.invoke()
            }
        }
    }

    fun finish(cancel: Boolean) = synchronized(lock) { if (penDown) finishLocked(cancel) }

    fun drawPreview(target: Canvas): Boolean = synchronized(lock) {
        if (!active) return false
        val image = frame ?: return false
        target.drawBitmap(image, 0f, 0f, null)
        true
    }

    fun stop() {
        synchronized(lock) {
            try { if (penDown) finishLocked(false) } catch (_: Throwable) { }
            active = false
            generation++
            penDown = false
        }
        optional("setWritingInputlistener", null)
        optional("setWritingEnabled", false)
        optional("onWritingEnd")
        optional("releaseWritingJavaBitmap")
        optional("releaseWritingJavaBackgroundBitmap")
        if (previousMode != null && optional("getPictureMode") == 4) optional("setPictureMode", previousMode)
        previousMode = null
        instance = null; listener = null; methods = emptyList()
    }

    private fun optional(name: String, vararg args: Any?): Any? =
        try { invoke(name, args) } catch (_: Throwable) { null }

    private fun call(name: String, vararg args: Any?): Any? {
        failureStage = name
        val result = invoke(name, args)
        check(result != false) { "Firmware rejected call" }
        return result
    }

    private fun invoke(name: String, args: Array<out Any?>): Any? {
        val receiver = instance ?: return null
        val method = methods.firstOrNull { method ->
            method.name == name && method.parameterTypes.size == args.size &&
                method.parameterTypes.indices.all { i ->
                    val type = method.parameterTypes[i]
                    val arg = args[i]
                    if (arg == null) !type.isPrimitive else when (type) {
                        Integer.TYPE -> arg is Int
                        java.lang.Boolean.TYPE -> arg is Boolean
                        else -> type.isInstance(arg)
                    }
                }
        } ?: throw NoSuchMethodException(name)
        return method.invoke(receiver, *args)
    }

    companion object {
        private val REQUIRED = setOf("getInstance", "setWritingInputlistener", "setWritingJavaBitmap",
            "setWritingJavaBackgroundBitmap", "setWritingEnabled", "onWritingStart", "onWritingEnd",
            "renderWriting", "releaseWritingJavaBitmap", "releaseWritingJavaBackgroundBitmap")
    }
}
