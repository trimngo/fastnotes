package dev.fastnotes.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import dev.fastnotes.BuildConfig
import dev.fastnotes.ink.panelOrientation
import dev.fastnotes.updates.UpdateActivity

/** Runs in :inktest. Scratch images and native edits never enter NoteStore. */
class InkTestActivity : Activity() {
    private lateinit var nativeProbe: NativeBufferProbe
    private lateinit var log: InkProbeLog
    private lateinit var paper: InkTestView
    private lateinit var status: TextView
    private lateinit var observations: RadioGroup
    private data class Geometry(val canvas: Rect, val visible: Rect, val display: Rect, val orientation: Int, val rotation: Int)
    private var registeredGeometry: Geometry? = null
    private var oldRun = ""
    private var apiReport = ""
    private var modeLabel = "Android pen"
    private var observed = "Not recorded"
    private val runs = ArrayDeque<String>()
    private val geometryObserver = ViewTreeObserver.OnPreDrawListener {
        if (registeredGeometry != null && geometry() != registeredGeometry) stopTest("Pane changed; select a native test again.")
        true
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        oldRun = InkProbeLog.read(this)
        log = InkProbeLog(this)
        log.event("Ink test ${BuildConfig.VERSION_NAME}; model ${Build.MODEL}; build ${Build.DISPLAY}; API ${Build.VERSION.SDK_INT}; PID ${Process.myPid()}")
        apiReport = FirmwarePreviewProbe.capabilities()
        nativeProbe = NativeBufferProbe(this, log::event).also { probe ->
            probe.onStrokeFinished = { paper.nativeStrokeFinished() }
            probe.onFailure = { stopTest(probe.status) }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(TextView(this).apply {
            text = "Ink comparison test"; textSize = 22f; setTextColor(Color.BLACK)
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        fun row(vararg choices: Pair<String, () -> Unit>) {
            val controls = LinearLayout(this)
            choices.forEach { (label, action) -> controls.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
                setPadding(dp(2), 0, dp(2), 0); maxLines = 2
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f)) }
            root.addView(controls)
        }
        row("Back" to { finish() }, "Updates" to {
            stopTest("Opening app updates…")
            startActivity(Intent(this, UpdateActivity::class.java))
        }, "Clear" to { stopTest("Cleared. Choose a test to repeat."); resetObservation() }, "Copy report" to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fastnotes ink test", report()))
            Toast.makeText(this, "Ink test report copied", Toast.LENGTH_SHORT).show()
        })
        row("Android pen" to {
            stopTest("Android pen: write with the pen tip."); modeLabel = "Android pen"; resetObservation()
        }, "Worker refresh" to { startNativeTest(NativeBufferMode.DISPLAY) }, "Reference callback" to { startNativeTest(NativeBufferMode.REFERENCE_CALLBACK) })
        root.addView(TextView(this).apply {
            // Fixed text preserves canvas geometry when tests/status change.
            text = "Temporary scratchpad. Compare Worker refresh and Reference callback with the same fast pen strokes. Both use full-display buffers. App ink waits one second after pen-up. Record what you saw while writing, then Copy report."
            textSize = 13f; setTextColor(Color.BLACK); setPadding(dp(12), dp(4), dp(12), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Ink while the pen moves:"; textSize = 13f; setTextColor(Color.BLACK); setPadding(dp(12), 0, 0, 0)
        })
        observations = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("Smooth", "Gaps / lag", "No live ink").forEach { label ->
                addView(RadioButton(this@InkTestActivity).apply {
                    id = View.generateViewId(); text = label; textSize = 12f; setTextColor(Color.BLACK)
                    minWidth = 0; minimumWidth = 0; maxLines = 2
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            setOnCheckedChangeListener { group, id ->
                observed = group.findViewById<RadioButton>(id)?.text?.toString() ?: "Not recorded"
                if (id != View.NO_ID) log.event("User observed $modeLabel: $observed")
            }
        }
        root.addView(observations)
        paper = InkTestView(this).apply { onStrokeFinished = { status.text = stats() } }
        root.addView(paper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "Android pen: write a rapid continuous up/down stroke."
            textSize = 12f; setTextColor(Color.BLACK); maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        setContentView(root)
        paper.viewTreeObserver.addOnPreDrawListener(geometryObserver)
    }

    private fun startNativeTest(mode: NativeBufferMode) {
        stopTest("Preparing ${mode.label}…")
        modeLabel = mode.label
        resetObservation()
        val geometry = geometry()
        if (geometry == null || !hasWindowFocus()) {
            status.text = "Tap this pane, then select the test again."
            return
        }
        if (nativeProbe.start(mode, geometry.canvas, geometry.visible, geometry.display, geometry.orientation)) {
            registeredGeometry = geometry
            paper.nativeMode(nativeProbe::capture) { nativeProbe.penDown }
            status.text = "${mode.label} requested. Look for complete ink while moving, then record the result."
        } else status.text = nativeProbe.status
    }

    private fun resetObservation() { observations.clearCheck(); observed = "Not recorded" }

    private fun stopTest(message: String) {
        if (!::nativeProbe.isInitialized) return
        if (::paper.isInitialized && (paper.samples > 0 || nativeProbe.active || nativeProbe.samples > 0)) {
            val summary = stats() + "; user observed: $observed" +
                if (nativeProbe.active || nativeProbe.samples > 0) "\n" + nativeProbe.details() else ""
            if (runs.lastOrNull() != summary) runs.addLast(summary)
            while (runs.size > 8) runs.removeFirst()
            log.event(summary)
        }
        nativeProbe.stop()
        registeredGeometry = null
        if (::paper.isInitialized) paper.mode(false)
        modeLabel = "Idle (select a test)"
        if (::status.isInitialized) status.text = message
    }

    @Suppress("DEPRECATION") // Full display coordinates are required; pane metrics would defeat this experiment.
    private fun geometry(): Geometry? {
        if (!::paper.isInitialized || !paper.isAttachedToWindow) return null
        val visible = Rect()
        if (!paper.getLocalVisibleRect(visible) || !visible.intersect(0, 0, paper.width, paper.height)) return null
        val origin = IntArray(2)
        paper.getLocationOnScreen(origin)
        visible.offset(origin[0], origin[1])
        val display = paper.display ?: return null
        val size = Point().also { display.getRealSize(it) }
        val mode = display.mode
        return Geometry(Rect(origin[0], origin[1], origin[0] + paper.width, origin[1] + paper.height),
            visible, Rect(0, 0, size.x, size.y), panelOrientation(mode.physicalWidth, mode.physicalHeight, display.rotation), display.rotation)
    }

    private fun stats() = "$modeLabel: ${paper.samples} Android samples; max event age ${paper.maxEventAgeMs} ms. " +
        (if (nativeProbe.active) "Native accepted=${nativeProbe.samples}. " else "") + "Timing excludes visible panel latency."

    private fun report(): String = buildString {
        append("Fastnotes ${BuildConfig.VERSION_NAME} ink investigation\n")
        append("Model: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\nBuild: ${Build.DISPLAY}\n")
        append("Target: ${applicationInfo.targetSdkVersion}; split-screen: $isInMultiWindowMode\n")
        val geometry = geometry()
        append("Display: ${geometry?.display?.toShortString()}; rotation ${geometry?.rotation}\n")
        append("Canvas: ${geometry?.canvas?.toShortString()}; visible ${geometry?.visible?.toShortString()}\n")
        append("Test status: ${nativeProbe.status}\n")
        append("User observed ink while moving: $observed\n")
        append(stats()).append('\n')
        append("Native buffer details (last request):\n").append(nativeProbe.details()).append('\n')
        append("Earlier comparisons:\n").append(runs.joinToString("\n\n")).append('\n')
        append("Current stages:\n").append(InkProbeLog.read(this@InkTestActivity)).append('\n')
        append("Previous run:\n").append(oldRun).append('\n')
        append("\nRead-only API inspection (presence is not proof of working ink):\n").append(apiReport)
        append("\n\n").append(CrashReports(this@InkTestActivity).details())
    }.take(64 * 1024)

    override fun onPause() { stopTest("Test paused. Select a test to start again."); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) stopTest("Focus changed. Select a test to start again.")
    }
    override fun onDestroy() {
        paper.viewTreeObserver.removeOnPreDrawListener(geometryObserver)
        nativeProbe.stop()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
