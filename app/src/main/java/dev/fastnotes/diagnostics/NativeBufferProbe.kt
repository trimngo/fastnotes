package dev.fastnotes.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import dev.fastnotes.ink.PaperRasterizer
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.model.Note
import dev.fastnotes.model.Viewport

enum class NativeBufferMode(val label: String) { PANE("Native pane"), DISPLAY("Native display") }

/** Temporary A/B test. The existing native controller, input and refresh policy are identical. */
class NativeBufferProbe internal constructor(
    private val context: Context,
    private val checkpoint: (String) -> Unit,
    private val panelFactory: () -> ViwoodsPanel
) {
    constructor(context: Context, checkpoint: (String) -> Unit = {}) : this(context, checkpoint, { ViwoodsPanel() })
    private val main = Handler(Looper.getMainLooper())
    private var panel: ViwoodsPanel? = null
    private var canvasBounds = Rect()
    private var bufferBounds = Rect()
    private var visibleBounds = Rect()
    private var mode: NativeBufferMode? = null
    private var panelDirection = 0
    private var lastDetails = "No native buffer test requested."
    var status = "No native buffer test requested."
        private set
    var onStrokeFinished: (() -> Unit)? = null
    var onFailure: (() -> Unit)? = null
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
            bufferBounds = Rect(if (mode == NativeBufferMode.DISPLAY) display else canvas)
            panelDirection = orientation
            val width = bufferBounds.width()
            val height = bufferBounds.height()
            // Two native copies and one temporary source: at most 96 MB of ARGB pixels.
            require(width.toLong() * height <= 8_000_000) { "Display too large for this experiment" }
            checkpoint("${mode.label}: allocating ${width}x$height ARGB buffer at ${bufferBounds.left},${bufferBounds.top}; clip ${visible.toShortString()}; orientation $orientation")
            source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val note = Note(pageWidth = width.toFloat(), penWidth = 3f,
                lineSpacing = 48f * context.resources.displayMetrics.density,
                scrollY = (bufferBounds.top - canvas.top).toFloat())
            PaperRasterizer().page(Canvas(source), note, Viewport(note.pageWidth, width, height, note.scrollY))
            val engine = panelFactory()
            panel = engine
            engine.onEditsAvailable = {
                // No file IO or View access on the firmware callback thread; no scratch edits
                // reach NoteStore. A fresh controller and identity check reject stale callbacks.
                main.post {
                    if (panel === engine && engine.active) {
                        engine.drainEdits()
                        engine.acknowledge()
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
            check(engine.start(context, source, note, bufferBounds.left, bufferBounds.top, orientation, visible = clip))
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

    fun details(): String = panel?.let { engine -> buildString {
        append("${mode?.label}: ${bufferBounds.width()}x${bufferBounds.height()} at ${bufferBounds.left},${bufferBounds.top}")
        append("; visible ${visibleBounds.toShortString()}; orientation $panelDirection\n")
        append("active=${engine.active}; pen down=${engine.penDown}; native callbacks=${engine.rawEventCount}; accepted=${engine.sampleCount}; refreshes=${engine.renderCount}\n")
        append("Picture mode=${engine.lastActiveMode}; longest refresh call=${engine.maxRenderMicros} us; last call=${engine.failureStage}\n")
        append("${engine.status}. Call timing excludes visible panel latency.")
    } } ?: lastDetails

    fun stop() {
        val engine = panel ?: return
        lastDetails = details()
        panel = null // queued callbacks from this controller can no longer touch the test
        engine.onEditsAvailable = null
        engine.onFailure = null
        checkpoint("${mode?.label}: stopping native controller")
        engine.stop()
        lastDetails += "\nController stopped; counters above were captured before stopping."
        status = "${mode?.label}: controller stopped."
        checkpoint("${mode?.label}: native controller stopped")
    }
}
