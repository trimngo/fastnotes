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
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.fastnotes.BuildConfig
import dev.fastnotes.FastnotesApp
import dev.fastnotes.ink.panelOrientation
import dev.fastnotes.ink.EinkBackend
import dev.fastnotes.updates.UpdateActivity

/** Runs in :inktest. Scratch images and native edits never enter NoteStore. */
class InkTestActivity : Activity() {
    private lateinit var nativeProbe: NativeBufferProbe
    private lateinit var systemProbe: SystemInkProbe
    private lateinit var log: InkProbeLog
    private lateinit var paper: InkTestView
    private lateinit var status: TextView
    private lateinit var observations: RadioGroup
    private lateinit var systemOptions: Spinner
    private data class Geometry(val canvas: Rect, val visible: Rect, val display: Rect, val orientation: Int, val rotation: Int)
    private data class NativePreparation(val mode: NativeBufferMode, val geometry: Geometry, val selectedAt: Long)
    private data class SystemPreparation(val geometry: Geometry, val selectedAt: Long)
    private enum class SystemExperiment(val choice: String, val modeLabel: String) {
        ORIGINAL("Original", "System ink"),
        PREPARED("Prepared screen", "System ink (prepared)"),
        FULL_DISPLAY("Full display", "System ink (full display)")
    }
    private var pressureEnabled = true
    private var systemExperiment = SystemExperiment.ORIGINAL
    private var nativePreparation: NativePreparation? = null
    private var cancelNativePreparation: (() -> Unit)? = null
    private var activeNativeMode: NativeBufferMode? = null
    private var systemPreparation: SystemPreparation? = null
    private var cancelSystemPreparation: (() -> Unit)? = null
    private var systemStartup = "No System ink experiment requested."
    private var lastSystemArea: Rect? = null
    private var nativeStartup = "No prepared-frame native start requested."
    private var registeredGeometry: Geometry? = null
    private var lastDrawGeometry: Geometry? = null
    private var stableGeometry: Geometry? = null
    private var resumed = false
    private var systemSelected = false
    private var systemResumePending = false
    private var systemStartFailed = false
    private var oldRun = ""
    private var apiReport = ""
    private var modeLabel = "Android pen"
    private var observed = "Not recorded"
    private val runs = ArrayDeque<String>()
    private val geometryObserver = ViewTreeObserver.OnPreDrawListener {
        val current = geometry()
        stableGeometry = current?.takeIf { it == lastDrawGeometry && !paper.isLayoutRequested }
        lastDrawGeometry = current
        if (nativePreparation?.let { current != it.geometry } == true) {
            stopTest("Pane changed; select a native test again.")
        }
        if (systemPreparation?.let { current != it.geometry } == true) {
            pauseSystemTest("System ink paused while the writing area changes.")
        }
        if (registeredGeometry != null && current != registeredGeometry) {
            if (systemSelected) pauseSystemTest("System ink paused while the writing area changes.")
            else stopTest("Pane changed; select a native test again.")
        }
        if (systemResumePending) resumeSystemTest()
        true
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pressureEnabled = state?.getBoolean("pressureEnabled", true) ?: true
        systemExperiment = SystemExperiment.entries.firstOrNull { it.name == state?.getString("systemExperiment") }
            ?: SystemExperiment.ORIGINAL
        systemSelected = state?.getBoolean("systemSelected") ?: false
        systemStartFailed = state?.getBoolean("systemStartFailed") ?: false
        systemResumePending = systemSelected && !systemStartFailed
        oldRun = InkProbeLog.read(this)
        val app = application as FastnotesApp
        log = app.inkProbeLog
        systemProbe = app.systemInk
        log.event("Ink test ${BuildConfig.VERSION_NAME}; model ${Build.MODEL}; build ${Build.DISPLAY}; API ${Build.VERSION.SDK_INT}; PID ${Process.myPid()}")
        if (systemProbe.hasRegistration) systemProbe.stop()
        apiReport = FirmwarePreviewProbe.capabilities()
        nativeProbe = NativeBufferProbe(this, log::event).also { probe ->
            probe.pressureEnabled = pressureEnabled
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
        row("Back" to { if (stopTest("Closing the ink test.")) finish() }, "Updates" to {
            if (stopTest("Opening app updates…")) startActivity(Intent(this, UpdateActivity::class.java))
        }, "API files" to {
            val currentReport = report()
            if (stopTest("Opening firmware API export…")) {
                startActivity(Intent(this, FirmwareEvidenceActivity::class.java).putExtra(FirmwareEvidenceActivity.EXTRA_REPORT, currentReport))
            }
        }, "Copy report" to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fastnotes ink test", report()))
            Toast.makeText(this, "Ink test report copied", Toast.LENGTH_SHORT).show()
        })
        row("Android pen" to {
            if (stopTest("Android pen: write with the pen tip.")) { modeLabel = "Android pen"; resetObservation() }
        }, "System ink" to { startSystemTest() }, "Worker refresh" to { startNativeTest(NativeBufferMode.DISPLAY) },
            "Ink direct" to { startNativeTest(NativeBufferMode.INK_DIRECT) },
            "Ink layers" to { startNativeTest(NativeBufferMode.INK_LAYERS) })
        val options = LinearLayout(this)
        options.addView(CheckBox(this).apply {
            text = "Pressure"; textSize = 12f; isChecked = pressureEnabled
            tooltipText = "Applies to Ink layers and Ink direct; uses an app pressure curve."
            setOnCheckedChangeListener { _, checked ->
                pressureEnabled = checked
                nativeProbe.pressureEnabled = checked
                val mode = nativePreparation?.mode ?: activeNativeMode
                if (mode?.inkLayers == true && (nativePreparation != null || nativeProbe.active)) startNativeTest(mode)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        options.addView(TextView(this).apply {
            text = "System:"; textSize = 12f; setTextColor(Color.BLACK)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        systemOptions = Spinner(this).apply {
            contentDescription = "System ink experiment"
            adapter = ArrayAdapter(this@InkTestActivity, android.R.layout.simple_spinner_dropdown_item,
                SystemExperiment.entries.map { it.choice })
            setSelection(systemExperiment.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = SystemExperiment.entries[position]
                    if (selected == systemExperiment) return
                    if (systemSelected || systemProbe.hasRegistration) {
                        if (!stopTest("System experiment changed; select System ink to start.", retainSystem = systemSelected)) {
                            systemResumePending = false
                            setSelection(systemExperiment.ordinal)
                            return
                        }
                        systemSelected = false
                        systemResumePending = false
                        systemStartFailed = false
                    }
                    systemExperiment = selected
                    systemStartup = "${selected.modeLabel} selected; press System ink to start."
                }
            }
        }
        options.addView(systemOptions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        root.addView(options, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(TextView(this).apply {
            // Fixed text preserves canvas geometry when tests/status change.
            text = "Temporary scratchpad. Compare Ink layers with Ink direct using the pen tip. Watch ink while moving, then check it stays visible after lifting. Completed ink is handed over after an Android frame. Worker refresh keeps its earlier one-second snapshot delay."
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
        if (systemSelected) {
            paper.systemMode()
            modeLabel = systemExperiment.modeLabel
            status.text = "System ink paused; waiting for the writing area."
        }
        if (systemProbe.hasRegistration) {
            systemResumePending = false
            if (!systemSelected) paper.mode(true)
            modeLabel = "Cleanup pending"
            status.text = systemProbe.status
        }
        paper.viewTreeObserver.addOnPreDrawListener(geometryObserver)
    }

    private fun startNativeTest(mode: NativeBufferMode) {
        if (!stopTest("Preparing ${mode.label}…")) return
        nativeProbe.pressureEnabled = pressureEnabled
        modeLabel = mode.label
        resetObservation()
        val geometry = geometry()
        if (geometry == null || !resumed || !hasWindowFocus() || !paper.isShown) {
            status.text = "Tap this pane, then select the test again."
            return
        }
        if (mode.inkLayers) {
            // Finish the mode-selection reset before enabling the native overlay.
            // Posting a Runnable alone would not establish draw/submission ordering.
            paper.nativeMode(nativeProbe::capture, { nativeProbe.penDown }, nativeProbe::captureCompleted)
            status.text = "${mode.label} selected. Write with the pen tip and check ink while moving."
            val request = NativePreparation(mode, geometry, SystemClock.uptimeMillis())
            nativePreparation = request
            nativeStartup = "${mode.label}: waiting for the prepared Android frame; panel readiness is unverified."
            cancelNativePreparation = paper.afterPreparedFrame {
                if (nativePreparation !== request) return@afterPreparedFrame
                nativePreparation = null
                cancelNativePreparation = null
                if (!resumed || !hasWindowFocus() || !paper.isAttachedToWindow || !paper.isShown ||
                    paper.isLayoutRequested || this.geometry() != request.geometry) {
                    nativeStartup = "${mode.label}: preparation canceled because the writing area changed."
                    stopTest("Writing area changed; select a native test again.")
                    return@afterPreparedFrame
                }
                val startAt = SystemClock.uptimeMillis()
                if (nativeProbe.start(mode, geometry.canvas, geometry.visible, geometry.display, geometry.orientation)) {
                    registeredGeometry = geometry
                    activeNativeMode = mode
                    nativeStartup = "${mode.label}: prepared frame submitted after ${startAt - request.selectedAt} ms; " +
                        "native start returned in ${SystemClock.uptimeMillis() - startAt} ms. " +
                        "App timings exclude physical panel readiness and visible ink latency."
                    // Do not clear/invalidate paper or redraw a success prompt after enabling.
                    log.event(nativeStartup)
                } else {
                    nativeStartup = "${mode.label}: native start failed after prepared frame submission."
                    paper.mode(false)
                    status.text = nativeProbe.status
                }
            }
            return
        }
        // Opaque controls retain their previous ordering for comparison.
        if (nativeProbe.start(mode, geometry.canvas, geometry.visible, geometry.display, geometry.orientation)) {
            registeredGeometry = geometry
            activeNativeMode = mode
            paper.nativeMode(nativeProbe::capture, { nativeProbe.penDown },
                if (mode.inkLayers) nativeProbe::captureCompleted else null)
            status.text = "${mode.label} requested. Look for complete ink while moving, then record the result."
        } else status.text = nativeProbe.status
    }

    private fun startSystemTest() {
        // Original/full-display compare only bounds. Prepared screen changes only startup order.
        if (!stopTest("Preparing System ink…")) return
        systemSelected = true
        systemStartFailed = false
        modeLabel = systemExperiment.modeLabel
        resetObservation()
        systemResumePending = true
        // A deliberate button selection can use the current laid-out pane. Automatic
        // restoration below requires the independently observed stable geometry.
        val current = geometry()
        if (resumed && hasWindowFocus() && current != null) registerSystemTest(current, initializePaper = true)
        else {
            paper.systemMode()
            resumeSystemTest()
        }
    }

    private fun registerSystemTest(current: Geometry, initializePaper: Boolean = false) {
        systemResumePending = false
        if (systemExperiment == SystemExperiment.PREPARED) {
            if (systemPreparation != null) return
            if (initializePaper) paper.systemMode()
            status.text = "${systemExperiment.modeLabel} selected. Check ink while moving."
            val request = SystemPreparation(current, SystemClock.uptimeMillis())
            systemPreparation = request
            systemStartup = "${systemExperiment.modeLabel}: waiting for the prepared Android frame; panel readiness is unverified."
            cancelSystemPreparation = paper.afterPreparedFrame {
                if (systemPreparation !== request) return@afterPreparedFrame
                systemPreparation = null
                cancelSystemPreparation = null
                if (!systemSelected || systemExperiment != SystemExperiment.PREPARED || !resumed || !hasWindowFocus() ||
                    !paper.isAttachedToWindow || !paper.isShown || paper.isLayoutRequested || geometry() != request.geometry) {
                    systemStartup = "System ink preparation canceled because the writing area changed."
                    pauseSystemTest("System ink paused; waiting for the writing area.")
                    return@afterPreparedFrame
                }
                val startAt = SystemClock.uptimeMillis()
                registerSystemNow(current, prepared = true)
                if (registeredGeometry != null) {
                    systemStartup = "${systemExperiment.modeLabel}: prepared frame submitted after ${startAt - request.selectedAt} ms; " +
                        "registration returned in ${SystemClock.uptimeMillis() - startAt} ms. " +
                        "App timings exclude physical panel readiness and visible ink latency."
                    log.event(systemStartup)
                }
            }
            return
        }
        registerSystemNow(current, initializePaper)
    }

    private fun registerSystemNow(current: Geometry, initializePaper: Boolean = false, prepared: Boolean = false) {
        val requestedArea = Rect(if (systemExperiment == SystemExperiment.FULL_DISPLAY) current.display else current.visible)
        lastSystemArea = Rect(requestedArea)
        log.event("${systemExperiment.modeLabel}: requesting ${requestedArea.toShortString()}")
        if (systemProbe.start(requestedArea)) {
            registeredGeometry = current
            if (initializePaper) paper.systemMode()
            modeLabel = systemExperiment.modeLabel
            if (!prepared) {
                systemStartup = "${systemExperiment.modeLabel}: registered before preparing the paper; visible ink is unverified."
                status.text = "${systemExperiment.modeLabel} requested. Look for complete ink while moving, then record the result."
            }
        } else {
            registeredGeometry = null
            systemStartFailed = true
            systemStartup = "${systemExperiment.modeLabel}: registration failed. ${systemProbe.status}"
            if (systemProbe.hasRegistration && !paper.firmwareTest) paper.mode(true)
            status.text = "Select System ink to retry. ${systemProbe.status}"
        }
    }

    private fun resumeSystemTest(retryCleanup: Boolean = false) {
        if (!systemSelected || !resumed || !hasWindowFocus() || registeredGeometry != null || systemPreparation != null) return
        if (systemProbe.hasRegistration) {
            if (!retryCleanup) return
            if (!systemProbe.stop()) {
                systemResumePending = false
                status.text = systemProbe.status
                return
            }
        }
        if (systemStartFailed) {
            systemResumePending = false
            status.text = "Select System ink to retry. ${systemProbe.status}"
            return
        }
        val current = geometry()
        if (current == null || current != stableGeometry || paper.isLayoutRequested) {
            systemResumePending = true
            status.text = "System ink paused; waiting for the writing area."
            if (current != null) paper.postInvalidateOnAnimation()
            return
        }
        registerSystemTest(current)
    }

    private fun pauseSystemTest(message: String) {
        cancelPreparedSystemStart()
        systemResumePending = !systemStartFailed
        if (systemStartFailed && !systemProbe.hasRegistration) {
            paper.pauseCurrentSystemStroke()
            status.text = "Select System ink to retry. ${systemProbe.status}"
            return
        }
        // A prior failed removal has already retained ownership. Do not retry IPC
        // on every draw, or a second loss callback; focus gain/manual retry can try once.
        if (registeredGeometry == null && systemProbe.hasRegistration) {
            systemResumePending = false
            paper.pauseCurrentSystemStroke()
            status.text = systemProbe.status
            return
        }
        if (!stopTest(message, retainSystem = true)) systemResumePending = false
    }

    private fun resetObservation() { observations.clearCheck(); observed = "Not recorded" }

    private fun cancelPreparedSystemStart() {
        systemPreparation = null
        cancelSystemPreparation?.invoke()
        cancelSystemPreparation = null
    }

    private fun stopTest(message: String, retainSystem: Boolean = false): Boolean {
        if (nativePreparation != null) nativeStartup = "Native preparation canceled: $message"
        nativePreparation = null
        cancelNativePreparation?.invoke()
        cancelNativePreparation = null
        cancelPreparedSystemStart()
        if (!retainSystem) {
            systemSelected = false
            systemResumePending = false
            systemStartFailed = false
        }
        if (!::nativeProbe.isInitialized) return true
        if (::paper.isInitialized && (paper.samples > 0 || nativeProbe.active || nativeProbe.samples > 0 || systemProbe.hasRegistration)) {
            val summary = stats() + "; user observed: $observed" +
                if (systemProbe.hasRegistration || modeLabel.startsWith("System ink")) "\n" + systemProbe.status
                else if (nativeProbe.active || nativeProbe.samples > 0) "\n" + nativeProbe.details() else ""
            if (runs.lastOrNull() != summary) runs.addLast(summary)
            while (runs.size > 8) runs.removeFirst()
            log.event(summary)
        }
        nativeProbe.stop()
        activeNativeMode = null
        val released = systemProbe.stop()
        registeredGeometry = null
        if (::paper.isInitialized) {
            if (retainSystem) paper.pauseCurrentSystemStroke() else paper.mode(!released)
        }
        modeLabel = if (retainSystem) systemExperiment.modeLabel else "Idle (select a test)"
        if (::status.isInitialized) status.text = if (released) message else systemProbe.status
        return released
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
        (if (nativeProbe.active) "Native accepted=${nativeProbe.samples}. " else "") +
        (if (modeLabel.startsWith("System ink")) "Completed=${paper.completedStrokes}; pressure: ${paper.pressureSummary}. " else "") +
        "Timing excludes visible panel latency."

    private fun report(): String = buildString {
        append("Fastnotes ${BuildConfig.VERSION_NAME} ink investigation\n")
        append("Model: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\nBuild: ${Build.DISPLAY}\n")
        append("E-ink backend: ${EinkBackend.readType() ?: "unavailable"}\n")
        append("Target: ${applicationInfo.targetSdkVersion}; split-screen: $isInMultiWindowMode\n")
        val geometry = geometry()
        append("Display: ${geometry?.display?.toShortString()}; rotation ${geometry?.rotation}\n")
        append("Canvas: ${geometry?.canvas?.toShortString()}; visible ${geometry?.visible?.toShortString()}\n")
        append("Test status: ${nativeProbe.status}\n")
        append("System ink: ${systemProbe.status}\n")
        append("System experiment: ${systemExperiment.choice}; last requested area: ${lastSystemArea?.toShortString()}\n")
        append("System startup: $systemStartup\n")
        append("System selection: $systemSelected; waiting to resume: $systemResumePending; manual retry required: $systemStartFailed\n")
        append("Native pressure enabled: $pressureEnabled (Ink layers / Ink direct; app curve)\n")
        append("Scratch handoff: ${paper.handoffDescription}\n")
        append("Native startup: $nativeStartup\n")
        append("User observed ink while moving: $observed\n")
        append(stats()).append('\n')
        append("Native buffer details (last request):\n").append(nativeProbe.details()).append('\n')
        append("Earlier comparisons:\n").append(runs.joinToString("\n\n")).append('\n')
        append("Current stages:\n").append(InkProbeLog.read(this@InkTestActivity)).append('\n')
        append("Previous run:\n").append(oldRun).append('\n')
        append("\nRead-only API inspection (presence is not proof of working ink):\n").append(apiReport)
        append("\n\n").append(CrashReports(this@InkTestActivity).details())
    }.take(64 * 1024)

    override fun onResume() {
        super.onResume()
        resumed = true
        resumeSystemTest(retryCleanup = true)
    }
    override fun onPause() {
        resumed = false
        if (systemSelected) pauseSystemTest("System ink paused; it will resume when this pane has focus.")
        else stopTest("Test paused. Select a test to start again.")
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::paper.isInitialized) return
        if (hasFocus) resumeSystemTest(retryCleanup = true)
        else if (systemSelected) pauseSystemTest("System ink paused; tap this pane to resume.")
        else stopTest("Focus changed. Select a test to start again.")
    }
    @Deprecated("Activity back callback retained for this standalone Activity")
    override fun onBackPressed() {
        if (stopTest("Closing the ink test.")) super.onBackPressed()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("systemSelected", systemSelected)
        state.putBoolean("systemStartFailed", systemStartFailed)
        state.putBoolean("pressureEnabled", pressureEnabled)
        state.putString("systemExperiment", systemExperiment.name)
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() {
        resumed = false
        systemResumePending = false
        nativePreparation = null
        cancelNativePreparation?.invoke()
        cancelNativePreparation = null
        cancelPreparedSystemStart()
        paper.viewTreeObserver.removeOnPreDrawListener(geometryObserver)
        nativeProbe.stop()
        systemProbe.stop()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
