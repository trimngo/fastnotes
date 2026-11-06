package dev.fastnotes.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.PorterDuff
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

enum class NativeRefreshPolicy { WORKER, REFERENCE_CALLBACK }

/** A completed image can release only the native session/revision it actually contains. */
internal data class NativeCompletionToken(val session: Long, val revision: Long)

/**
 * Independent implementation of the direct ENote callback protocol researched in ForestNote.
 * The callback thread owns its bitmap; the UI receives immutable completed edits, never points.
 * No View access, file I/O, global auto-draw rectangles or proprietary binaries in this class.
 */
class ViwoodsPanel internal constructor(
    private val className: String,
    private val schedulerFactory: () -> NativeInkScheduler
) {
    constructor(className: String = "android.os.enote.ENoteSetting", policy: NativeRefreshPolicy = NativeRefreshPolicy.WORKER) :
        this(className, { HandlerNativeInkScheduler() }) { refreshPolicy = policy }
    internal constructor(className: String, policy: NativeRefreshPolicy, schedulerFactory: () -> NativeInkScheduler) :
        this(className, schedulerFactory) { refreshPolicy = policy }
    var refreshPolicy = NativeRefreshPolicy.WORKER
        private set

    private val lock = Any()
    // Never acquire lock while holding firmwareLock: a slow native render must not block MOVE.
    private val firmwareLock = Any()
    private var instance: Any? = null
    private var methods = emptyList<Method>()
    private var listener: Any? = null
    @Volatile private var generation = 0L
    private var frame: Bitmap? = null
    private var canvas: Canvas? = null
    private var displayFrame: Bitmap? = null
    private var displayCanvas: Canvas? = null
    private var backgroundFrame: Bitmap? = null
    private var backgroundCanvas: Canvas? = null
    private val backgroundCopy = Paint().apply { blendMode = BlendMode.SRC }
    private var inkLayers = false
    private var frameHandoff = false
    private var pressureInk = false
    // Scratch-only widths. Note/InkPoint and the notebook's on-disk format stay unchanged.
    // Each immutable list belongs to the same committed Stroke ID and point order.
    private var pressureWidths: Map<Long, List<Float>> = emptyMap()
    private var completedRevision = 0L
    val bitmapDescription get() = if (inkLayers) "Transparent native ink layers" else "Opaque paper bitmap"
    val pressureDescription get() = if (pressureInk) NativePressureProfile.DESCRIPTION else "Fixed-width ink"
    private val raster = PaperRasterizer()
    private val pressureRaster = NativePressureRasterizer()
    private var page = Note()
    private var view = Viewport(1000f, 1, 1, 0f)
    private var screenBounds = Rect()
    private var visibleLocal = Rect()
    private var orientation = 1
    private var previousMode: Int? = null
    private var nextId = 1L
    private var forceEraser = false
    private var eraserRadius = 14f
    private val points = ArrayList<InkPoint>()
    private val pieces = ArrayList<List<InkPoint>>()
    private val pointWidths = ArrayList<Float>()
    private val pieceWidths = ArrayList<List<Float>>()
    private var lastPressureWidth: Float? = null
    private val erased = HashSet<Long>()
    private val edits = ArrayList<NativeEdit>()
    private var strokeIsEraser = false
    private var waitingForHandoff = false
    private val dirty = Rect()
    private val strokeDamage = Rect()
    private val finalDamage = Rect()
    private data class Damage(val time: Long, val rect: Rect)
    private val recentDamage = ArrayDeque<Damage>()
    private var scheduler: NativeInkScheduler? = null
    private var refreshTask: Runnable? = null
    private var scheduledAt = Long.MAX_VALUE
    private var nextRefreshAt = 0L
    private var finalRefreshAt = Long.MAX_VALUE
    private var refreshInFlight = false
    private var handoffRequested = false
    @Volatile var maxRenderMicros = 0L
        private set
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
    @Volatile var lastCallbackThread = 0L
        private set
    @Volatile var lastRenderThread = 0L
        private set
    @Volatile var lastEndThread = 0L
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
              orientation: Int, erase: Boolean = false, visible: Rect? = null, inkLayers: Boolean = false,
              frameHandoff: Boolean = false, pressureInk: Boolean = false): Boolean {
        stop()
        val report = availabilityReport()
        if (report != "Available") { status = report; return false }
        return try {
            failureStage = "getInstance"
            val type = Class.forName(className)
            methods = type.methods.toList()
            instance = type.getMethod("getInstance").invoke(null)
            synchronized(lock) {
                this.inkLayers = inkLayers
                this.frameHandoff = frameHandoff
                this.pressureInk = pressureInk
                pressureWidths = emptyMap()
                completedRevision = 0
                frame = source.copy(Bitmap.Config.ARGB_8888, true)
                canvas = Canvas(frame!!)
                page = note
                view = Viewport(note.pageWidth, source.width, source.height, note.scrollY)
                if (inkLayers) redrawPage()
                displayFrame = frame!!.copy(Bitmap.Config.ARGB_8888, true)
                displayCanvas = Canvas(displayFrame!!)
                // ENote deduplicates registrations by bitmap identity as well as layer kind.
                // Sharing this object with the foreground replaces that layer in SoftSolution.
                backgroundFrame = frame!!.copy(Bitmap.Config.ARGB_8888, true)
                backgroundCanvas = Canvas(backgroundFrame!!)
                screenBounds = Rect(x, y, x + source.width, y + source.height)
                visibleLocal = visible?.let(::Rect) ?: Rect(0, 0, source.width, source.height)
                check(visibleLocal.intersect(0, 0, source.width, source.height)) { "Empty editor visibility" }
                this.orientation = orientation
                nextId = (note.strokes.maxOfOrNull { it.id } ?: 0L) + 1
                forceEraser = erase
                eraserRadius = 14f * context.resources.displayMetrics.density / view.scale
                points.clear(); pieces.clear(); erased.clear()
                pointWidths.clear(); pieceWidths.clear(); lastPressureWidth = null
                penDown = false; waitingForHandoff = false
                dirty.setEmpty(); strokeDamage.setEmpty(); finalDamage.setEmpty(); recentDamage.clear()
                scheduledAt = Long.MAX_VALUE; nextRefreshAt = 0; finalRefreshAt = Long.MAX_VALUE
                refreshInFlight = false; handoffRequested = false
                generation++
                val epoch = generation
                scheduler = if (refreshPolicy == NativeRefreshPolicy.WORKER) schedulerFactory() else null
                refreshTask = if (scheduler != null) Runnable { refresh(epoch) } else null
            }
            optional("setApplicationContext", context.applicationContext)
            previousMode = optional("getPictureMode") as? Int
            call("setWritingJavaBitmap", displayFrame, orientation, x, y)
            call("setWritingJavaBackgroundBitmap", backgroundFrame, orientation, x, y)
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
                lastCallbackThread = Thread.currentThread().id
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
                        handoffRequested = false
                        strokeDamage.setEmpty()
                        strokeIsEraser = forceEraser || type == MotionEvent.TOOL_TYPE_ERASER ||
                            buttons and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                        points.clear(); pieces.clear(); erased.clear()
                        pointWidths.clear(); pieceWidths.clear(); lastPressureWidth = null
                        call("onWritingStart")
                        if (refreshPolicy == NativeRefreshPolicy.WORKER) call("setWritingEnabled", true)
                        call("setWritingJavaBitmap", displayFrame, orientation, screenBounds.left, screenBounds.top)
                        append(x, y, pressure)
                    }
                    MotionEvent.ACTION_MOVE -> if (penDown) append(x, y, pressure)
                    MotionEvent.ACTION_UP -> if (penDown) {
                        append(x, y, pressure, preserveWidth = pressure <= 0)
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

    private fun inside(x: Float, y: Float) = x >= visibleLocal.left && y >= visibleLocal.top &&
        x < visibleLocal.right && y < visibleLocal.bottom

    private fun append(x: Float, y: Float, pressure: Int, preserveWidth: Boolean = false) {
        if (!inside(x, y)) {
            if (!strokeIsEraser && points.isNotEmpty()) {
                pieces.add(points.toList())
                if (pressureInk) pieceWidths.add(pointWidths.toList())
            }
            points.clear()
            pointWidths.clear()
            return
        }
        val point = view.document(x, y)
        val previous = points.lastOrNull() ?: point
        val width = if (pressureInk && !strokeIsEraser) {
            if (preserveWidth) lastPressureWidth ?: NativePressureProfile.width(pressure, page.penWidth)
            else NativePressureProfile.width(pressure, page.penWidth)
        } else page.penWidth
        val previousWidth = pointWidths.lastOrNull() ?: width
        if (point == previous && points.isNotEmpty() &&
            (!pressureInk || strokeIsEraser || width == previousWidth)) return
        points.add(point)
        if (pressureInk && !strokeIsEraser) {
            pointWidths.add(width)
            lastPressureWidth = width
        }
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
                redrawPage(erased)
                queueRender(dirty)
            }
        } else {
            val a = view.screen(previous)
            val b = view.screen(point)
            val screenWidth = width * view.scale
            val previousScreenWidth = previousWidth * view.scale
            if (pressureInk) {
                val target = canvas!!
                val save = target.save()
                try {
                    target.clipRect(visibleLocal)
                    pressureRaster.segment(target, a, b, previousScreenWidth, screenWidth)
                } finally { target.restoreToCount(save) }
            } else raster.segment(canvas!!, a, b, screenWidth)
            val pad = ceil(max(screenWidth, previousScreenWidth) / 2f).toInt() + 4
            queueRender(Rect(floor(min(a.x, b.x)).toInt() - pad, floor(min(a.y, b.y)).toInt() - pad,
                ceil(max(a.x, b.x)).toInt() + pad, ceil(max(a.y, b.y)).toInt() + pad))
        }
    }

    private fun queueRender(local: Rect) {
        val clipped = Rect(local)
        if (!clipped.intersect(visibleLocal)) return
        if (refreshPolicy == NativeRefreshPolicy.REFERENCE_CALLBACK) {
            strokeDamage.union(clipped)
            // Follow the inspected SDK's callback topology: paint, present and later end the
            // transaction on this callback thread, without an extra writing-enable transition.
            displayCanvas!!.drawBitmap(frame!!, clipped, clipped, if (inkLayers) backgroundCopy else null)
            val screen = Rect(clipped).apply { offset(screenBounds.left, screenBounds.top) }
            val started = System.nanoTime()
            lastRenderThread = Thread.currentThread().id
            call("renderWriting", screen)
            renderCount++
            maxRenderMicros = max(maxRenderMicros, (System.nanoTime() - started) / 1000)
            return
        }
        dirty.union(clipped)
        strokeDamage.union(clipped)
        scheduleRefresh()
    }

    /** Called under lock. There is at most one pending wakeup, even for a callback burst. */
    private fun scheduleRefresh() {
        val clock = scheduler ?: return
        val task = refreshTask ?: return
        val due = when {
            !dirty.isEmpty -> max(clock.now(), nextRefreshAt)
            !finalDamage.isEmpty -> max(finalRefreshAt, nextRefreshAt)
            else -> return
        }
        if (due >= scheduledAt) return
        clock.cancel(task)
        scheduledAt = due
        clock.postAtTime(task, due)
    }

    private fun refresh(epoch: Long) {
        val screen: Rect
        synchronized(lock) {
            if (!active || generation != epoch) return
            val clock = scheduler ?: return
            scheduledAt = Long.MAX_VALUE
            val now = clock.now()
            if (now < nextRefreshAt) { scheduleRefresh(); return }
            val changed = Rect(dirty)
            dirty.setEmpty()
            if (!finalDamage.isEmpty && now >= finalRefreshAt) {
                changed.union(finalDamage)
                finalDamage.setEmpty(); finalRefreshAt = Long.MAX_VALUE
            }
            if (changed.isEmpty) { scheduleRefresh(); return }
            while (recentDamage.isNotEmpty() && now - recentDamage.first().time > COVERAGE_MS) {
                recentDamage.removeFirst()
            }
            val covered = Rect(changed)
            for (damage in recentDamage) covered.union(damage.rect)
            recentDamage.addLast(Damage(now, changed))
            // ENote reads a distinct presentation bitmap. Input can continue drawing the preview
            // while renderWriting is busy; neither a live Canvas nor a mutable Rect is shared.
            displayCanvas!!.drawBitmap(frame!!, covered, covered, if (inkLayers) backgroundCopy else null)
            screen = Rect(covered).apply { offset(screenBounds.left, screenBounds.top) }
            nextRefreshAt = now + FRAME_MS
            refreshInFlight = true
        }
        try {
            val started = System.nanoTime()
            val rendered = synchronized(firmwareLock) {
                if (!active || generation != epoch) false
                else { lastRenderThread = Thread.currentThread().id; call("renderWriting", screen); true }
            }
            val duration = (System.nanoTime() - started) / 1000
            synchronized(lock) {
                if (!active || generation != epoch) return
                refreshInFlight = false
                if (rendered) {
                    renderCount++
                    maxRenderMicros = max(maxRenderMicros, duration)
                }
                scheduleRefresh()
                endWritingIfReady()
            }
        } catch (_: Throwable) {
            synchronized(lock) {
                if (generation != epoch) return
                refreshInFlight = false
                status = "Native ink stopped at renderWriting"
                active = false
            }
            onFailure?.invoke()
        }
    }

    private fun finishLocked(cancel: Boolean) {
        if (!penDown) return
        penDown = false
        if (cancel) {
            redrawPage()
            queueRender(Rect(0, 0, view.width, view.height))
        } else if (strokeIsEraser && erased.isNotEmpty()) {
            val ids = erased.toSet()
            page = page.copy(strokes = page.strokes.filterNot { it.id in ids })
            pressureWidths = pressureWidths.filterKeys { it !in ids }
            edits.add(NativeEdit.Erase(ids))
        } else if (!strokeIsEraser) {
            val all = ArrayList<Stroke>()
            val newWidths = if (pressureInk) pressureWidths.toMutableMap() else null
            val allPoints = pieces + listOf(points.toList())
            val allWidths = if (pressureInk) pieceWidths + listOf(pointWidths.toList()) else emptyList()
            for ((index, strokePoints) in allPoints.withIndex()) {
                if (strokePoints.isEmpty()) continue
                val widths = if (pressureInk) allWidths[index] else null
                val stroke = Stroke(nextId++, widths?.maxOrNull() ?: page.penWidth, strokePoints)
                all.add(stroke)
                if (widths != null) newWidths!![stroke.id] = widths
            }
            if (all.isNotEmpty()) {
                page = page.copy(strokes = page.strokes + all)
                if (newWidths != null) pressureWidths = newWidths.toMap()
                edits.add(NativeEdit.Add(all))
            }
        }
        if (refreshPolicy == NativeRefreshPolicy.WORKER && !strokeDamage.isEmpty) {
            finalDamage.union(strokeDamage)
            finalRefreshAt = min(finalRefreshAt, (scheduler?.now() ?: 0) + SETTLE_MS)
            scheduleRefresh()
        }
        points.clear(); pieces.clear(); erased.clear()
        pointWidths.clear(); pieceWidths.clear(); lastPressureWidth = null
        completedRevision++
        waitingForHandoff = refreshPolicy == NativeRefreshPolicy.WORKER || frameHandoff
        if (refreshPolicy == NativeRefreshPolicy.REFERENCE_CALLBACK && !frameHandoff) {
            updateCompletedBackground()
            lastEndThread = Thread.currentThread().id
            call("onWritingEnd")
        }
        onEditsAvailable?.invoke()
    }

    fun drainEdits(): List<NativeEdit> = synchronized(lock) {
        val result = edits.toList()
        edits.clear()
        result
    }

    fun acknowledge() = synchronized(lock) {
        handoffRequested = true
        endWritingIfReady()
    }

    /** A late frame may not end a newer stroke, completion or restarted native session. */
    internal fun acknowledge(token: NativeCompletionToken) = synchronized(lock) {
        if (active && !penDown && token.session == generation && token.revision == completedRevision) {
            handoffRequested = true
            endWritingIfReady()
        }
    }

    private data class CompletedSnapshot(val page: Note, val view: Viewport,
        val widths: Map<Long, List<Float>>, val clip: Rect, val token: NativeCompletionToken)

    /**
     * Snapshot immutable completed vectors briefly, then rasterize outside the live input lock.
     * A private rasterizer avoids sharing mutable Paint state with the native callback thread.
     */
    internal fun drawCompleted(target: Canvas): NativeCompletionToken? {
        val snapshot = synchronized(lock) {
            if (!active) return null
            CompletedSnapshot(page, view, pressureWidths, Rect(visibleLocal),
                NativeCompletionToken(generation, completedRevision))
        }
        val completedRaster = PaperRasterizer()
        completedRaster.paper(target, snapshot.page, snapshot.view)
        NativePressureRasterizer().ink(target, snapshot.page, snapshot.view, snapshot.widths,
            snapshot.clip, completedRaster)
        return synchronized(lock) {
            snapshot.token.takeIf { active && it.session == generation }
        }
    }

    private fun endWritingIfReady() {
        if (active && !penDown && edits.isEmpty() && waitingForHandoff && handoffRequested &&
            dirty.isEmpty && finalDamage.isEmpty && !refreshInFlight) {
            waitingForHandoff = false
            handoffRequested = false
            try {
                updateCompletedBackground()
                lastEndThread = Thread.currentThread().id
                call("onWritingEnd")
            } catch (_: Throwable) {
                status = "Native handoff failed at " + failureStage
                active = false
                onFailure?.invoke()
            }
        }
    }

    /** Called under lock, only once the completed frame is ready, never from MOVE. */
    private fun updateCompletedBackground() = synchronized(firmwareLock) {
        // Native rendering retains this bitmap. Serialize its update with every JNI read,
        // and keep it unchanged while the next stroke is being drawn into frame.
        backgroundCanvas!!.drawBitmap(frame!!, 0f, 0f, backgroundCopy)
        call("setWritingJavaBackgroundBitmap", backgroundFrame, orientation, screenBounds.left, screenBounds.top)
    }

    /** Only the isolated ink-layer comparison separates Android paper from native ink. */
    private fun redrawPage(excluded: Set<Long> = emptySet()) {
        if (inkLayers) {
            canvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } else raster.paper(canvas!!, page, view)
        pressureRaster.ink(canvas!!, page, view, pressureWidths, visibleLocal, raster, excluded)
    }

    fun finish(cancel: Boolean) = synchronized(lock) { if (penDown) finishLocked(cancel) }

    fun drawPreview(target: Canvas, quietMs: Long = 0): Boolean = synchronized(lock) {
        if (!active) return false
        // The diagnostic snapshot checks under the same lock that owns pen/sample updates.
        // Normal notebook drawing keeps its existing immediate behavior (quietMs = 0).
        if (quietMs > 0 && (penDown || SystemClock.uptimeMillis() - lastInkTime < quietMs)) return false
        val image = frame ?: return false
        if (inkLayers) raster.paper(target, page, view)
        target.drawBitmap(image, 0f, 0f, null)
        true
    }

    fun stop() {
        val oldScheduler: NativeInkScheduler?
        synchronized(lock) {
            try { if (penDown) finishLocked(false) } catch (_: Throwable) { }
            active = false
            generation++
            penDown = false
            oldScheduler = scheduler
            scheduler = null; refreshTask = null
            dirty.setEmpty(); finalDamage.setEmpty(); recentDamage.clear()
        }
        oldScheduler?.close()
        synchronized(firmwareLock) {
            optional("setWritingInputlistener", null)
            optional("setWritingEnabled", false)
            optional("onWritingEnd")
            optional("releaseWritingJavaBitmap")
            optional("releaseWritingJavaBackgroundBitmap")
            if (previousMode != null && optional("getPictureMode") == 4) optional("setPictureMode", previousMode)
            previousMode = null
            instance = null; listener = null; methods = emptyList()
        }
        synchronized(lock) {
            frame = null; canvas = null; displayFrame = null; displayCanvas = null
            backgroundFrame = null; backgroundCanvas = null
            pressureWidths = emptyMap()
            pointWidths.clear(); pieceWidths.clear(); lastPressureWidth = null
        }
    }

    private fun optional(name: String, vararg args: Any?): Any? =
        try { invoke(name, args) } catch (_: Throwable) { null }

    private fun call(name: String, vararg args: Any?): Any? {
        failureStage = name
        val result = invoke(name, args)
        check(result != false) { "Firmware rejected call" }
        return result
    }

    private fun invoke(name: String, args: Array<out Any?>): Any? = synchronized(firmwareLock) {
        val receiver = instance ?: return@synchronized null
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
        method.invoke(receiver, *args)
    }

    companion object {
        private const val FRAME_MS = 8L
        private const val COVERAGE_MS = 32L
        private const val SETTLE_MS = 24L
        private val REQUIRED = setOf("getInstance", "setWritingInputlistener", "setWritingJavaBitmap",
            "setWritingJavaBackgroundBitmap", "setWritingEnabled", "onWritingStart", "onWritingEnd",
            "renderWriting", "releaseWritingJavaBitmap", "releaseWritingJavaBackgroundBitmap")
    }
}
