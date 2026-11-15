package dev.fastnotes.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import dev.fastnotes.ink.PaperRasterizer
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.ink.NativeRefreshPolicy
import dev.fastnotes.model.Note
import dev.fastnotes.model.Viewport

enum class NativeBufferMode(
    val label: String,
    val refreshPolicy: NativeRefreshPolicy = NativeRefreshPolicy.WORKER,
    val inkLayers: Boolean = false,
    val liveCapture: Boolean = false
) {
    PANE("Native pane"),
    DISPLAY("Worker refresh"),
    REFERENCE_CALLBACK("Reference callback", NativeRefreshPolicy.REFERENCE_CALLBACK),
    INK_LAYERS("Ink layers", inkLayers = true),
    INK_DIRECT("Ink direct", NativeRefreshPolicy.REFERENCE_CALLBACK, inkLayers = true),
    SCRCPY("Scrcpy", inkLayers = true, liveCapture = true)
}

/** Temporary geometry/execution comparisons; live performance requires a user observation. */
class NativeBufferProbe internal constructor(
    private val context: Context,
    private val checkpoint: (String) -> Unit,
    private val panelFactory: (NativeBufferMode) -> ViwoodsPanel
) {
    constructor(context: Context, checkpoint: (String) -> Unit = {}) : this(context, checkpoint, { mode ->
        ViwoodsPanel(policy = mode.refreshPolicy)
    })
    private val main = Handler(Looper.getMainLooper())
    private var panel: ViwoodsPanel? = null
    private var canvasBounds = Rect()
    private var bufferBounds = Rect()
    private var visibleBounds = Rect()
    private var mode: NativeBufferMode? = null
    private var panelDirection = 0
    private var lastDetails = "No native buffer test requested."
    private var capturePending = AtomicBoolean()
    private var captureNotification: Runnable? = null
    private var liveFrames = 0L
    private var liveNotifications = 0L
    var status = "No native buffer test requested."
        private set
    var onStrokeFinished: (() -> Unit)? = null
    var onFailure: (() -> Unit)? = null
    var onLiveInkChanged: (() -> Unit)? = null
    var pressureEnabled = true
    val active get() = panel?.active == true
    val penDown get() = panel?.penDown == true
    val samples get() = panel?.sampleCount ?: 0

    fun start(mode: NativeBufferMode, canvas: Rect, visible: Rect, display: Rect, orientation: Int): Boolean {
        stop()
        var source: Bitmap? = null
        return try {
            require(!canvas.isEmpty && !visible.isEmpty && !display.isEmpty)
            require(display.left == 0 && display.top == 0 && canvas.contains(visible) && display.contains(visible))
            require(orientation == 1 || orientation == 2)
            this.mode = mode
            canvasBounds = Rect(canvas)
            visibleBounds = Rect(visible)
            bufferBounds = Rect(if (mode != NativeBufferMode.PANE) display else canvas)
            panelDirection = orientation
            val width = bufferBounds.width()
            val height = bufferBounds.height()
            // Preview, distinct native foreground/background, and temporary source: at most 96 MB.
            require(width.toLong() * height <= 6_000_000) { "Display too large for this experiment" }
            checkpoint("${mode.label}: allocating ${width}x$height ARGB buffer at ${bufferBounds.left},${bufferBounds.top}; clip ${visible.toShortString()}; orientation $orientation")
            source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val note = Note(pageWidth = width.toFloat(), penWidth = 3f,
                lineSpacing = 48f * context.resources.displayMetrics.density,
                scrollY = (bufferBounds.top - canvas.top).toFloat())
            val raster = PaperRasterizer()
            val viewport = Viewport(note.pageWidth, width, height, note.scrollY)
            if (mode.inkLayers) raster.ink(Canvas(source), note, viewport)
            else raster.page(Canvas(source), note, viewport)
            val engine = panelFactory(mode)
            panel = engine
            val pendingCapture = AtomicBoolean()
            capturePending = pendingCapture
            liveFrames = 0
            liveNotifications = 0
            val notifyCapture = Runnable {
                if (panel === engine && engine.active && mode.liveCapture) {
                    liveNotifications++
                    onLiveInkChanged?.invoke()
                } else pendingCapture.set(false)
            }
            captureNotification = notifyCapture
            if (mode.liveCapture) engine.onLiveInkChanged = {
                // Keep one UI wakeup until a draw consumes the newest native scene.
                // The native callback neither reads a View nor copies a screen bitmap.
                if (pendingCapture.compareAndSet(false, true)) main.post(notifyCapture)
            }
            engine.onEditsAvailable = {
                // No file IO or View access on the firmware callback thread; no scratch edits
                // reach NoteStore. A fresh controller and identity check reject stale callbacks.
                main.post {
                    if (panel === engine && engine.active) {
                        engine.drainEdits()
                        // Transparent modes keep native completion visible until the View has
                        // submitted its completed-only frame. Headless/legacy callers retain
                        // their immediate acknowledgement instead of stranding the session.
                        if (!mode.inkLayers || onStrokeFinished == null) engine.acknowledge()
                        onStrokeFinished?.invoke()
                    }
                }
            }
            engine.onFailure = {
                main.post {
                    if (panel === engine) {
                        status = "${mode.label}: ${engine.status}"
                        checkpoint(status)
                        onFailure?.invoke()
                    }
                }
            }
            val clip = Rect(visible).apply { offset(-bufferBounds.left, -bufferBounds.top) }
            checkpoint("${mode.label}: calling native controller start")
            check(engine.start(context, source, note, bufferBounds.left, bufferBounds.top, orientation,
                visible = clip, inkLayers = mode.inkLayers, frameHandoff = mode.inkLayers,
                pressureInk = mode.inkLayers && pressureEnabled))
            status = "${mode.label} requested. Live ink is unverified."
            checkpoint("${mode.label}: native controller returned; active=${engine.active}; mode=${engine.lastActiveMode}")
            true
        } catch (failure: Throwable) {
            val reason = panel?.status ?: failure.javaClass.simpleName
            stop()
            status = "${mode.label} could not start: $reason (${failure.javaClass.simpleName})."
            checkpoint(status)
            false
        } finally { source?.recycle() }
    }

    /** Used only by the one-second quiet snapshot, never by a live Android onDraw. */
    fun capture(target: Canvas): Boolean {
        val engine = panel ?: return false
        val save = target.save()
        return try {
            target.translate((bufferBounds.left - canvasBounds.left).toFloat(), (bufferBounds.top - canvasBounds.top).toFloat())
            engine.drawPreview(target, quietMs = 1000)
        } finally { target.restoreToCount(save) }
    }

    /** Native-derived vectors in Android composition, including a still-moving stroke. */
    fun captureLive(target: Canvas): Boolean {
        val engine = panel ?: return false
        if (mode?.liveCapture != true) return false
        // Consume before drawing: an update arriving during rasterization needs another frame.
        capturePending.set(false)
        val save = target.save()
        val captured = try {
            target.translate((bufferBounds.left - canvasBounds.left).toFloat(),
                (bufferBounds.top - canvasBounds.top).toFloat())
            engine.drawCapture(target)
        } finally { target.restoreToCount(save) }
        if (!captured || panel !== engine || !engine.active) return false
        liveFrames++
        return true
    }

    /** Returns the acknowledgement for exactly the completed-only image just captured. */
    fun captureCompleted(target: Canvas): (() -> Unit)? {
        val engine = panel ?: return null
        if (mode?.inkLayers != true) return null
        val save = target.save()
        val token = try {
            target.translate((bufferBounds.left - canvasBounds.left).toFloat(),
                (bufferBounds.top - canvasBounds.top).toFloat())
            engine.drawCompleted(target)
        } finally { target.restoreToCount(save) }
        if (token == null || panel !== engine || !engine.active) return null
        return {
            if (panel === engine && engine.active) engine.acknowledge(token)
        }
    }

    fun details(): String = panel?.let { engine -> buildString {
        append("${mode?.label}: ${bufferBounds.width()}x${bufferBounds.height()} at ${bufferBounds.left},${bufferBounds.top}")
        append("; visible ${visibleBounds.toShortString()}; orientation $panelDirection\n")
        append("active=${engine.active}; pen down=${engine.penDown}; native callbacks=${engine.rawEventCount}; accepted=${engine.sampleCount}; refreshes=${engine.renderCount}\n")
        append("Picture mode=${engine.lastActiveMode}; longest refresh call=${engine.maxRenderMicros} us; last call=${engine.failureStage}\n")
        append("Policy=${engine.refreshPolicy}; callback thread=${engine.lastCallbackThread}; render thread=${engine.lastRenderThread}; stroke-end thread=${engine.lastEndThread}\n")
        append("Bitmap=${engine.bitmapDescription}\n")
        append("Pressure=${engine.pressureDescription}\n")
        append("Android live capture=${mode?.liveCapture == true}; frames=$liveFrames; UI notifications=$liveNotifications. Capture/encoder latency is unverified.\n")
        append("${engine.status}. Call timing excludes visible panel latency.")
    } } ?: lastDetails

    fun stop() {
        val engine = panel ?: return
        lastDetails = details()
        panel = null // queued callbacks from this controller can no longer touch the test
        engine.onEditsAvailable = null
        engine.onFailure = null
        engine.onLiveInkChanged = null
        captureNotification?.let(main::removeCallbacks)
        captureNotification = null
        capturePending.set(false)
        checkpoint("${mode?.label}: stopping native controller")
        engine.stop()
        lastDetails += "\nController stopped; counters above were captured before stopping."
        status = "${mode?.label}: controller stopped."
        checkpoint("${mode?.label}: native controller stopped")
    }
}
