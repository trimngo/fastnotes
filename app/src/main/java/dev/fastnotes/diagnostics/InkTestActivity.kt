package dev.fastnotes.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.fastnotes.BuildConfig

/** Runs in :inktest. Nothing here opens notes, starts update IO or saves scratch strokes. */
class InkTestActivity : Activity() {
    private lateinit var probe: FirmwarePreviewProbe
    private lateinit var log: InkProbeLog
    private lateinit var paper: InkTestView
    private lateinit var status: TextView
    private lateinit var instructions: TextView
    private var registeredBounds: Rect? = null
    private var oldRun = ""
    private var apiReport = ""
    private val runs = ArrayDeque<String>()
    private val geometryObserver = ViewTreeObserver.OnPreDrawListener {
        if (registeredBounds != null && screenBounds() != registeredBounds) stopTest("Pane changed; select Firmware preview again.")
        true
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        oldRun = InkProbeLog.read(this)
        log = InkProbeLog(this)
        log.event("Ink test ${BuildConfig.VERSION_NAME}; model ${Build.MODEL}; build ${Build.DISPLAY}; API ${Build.VERSION.SDK_INT}; PID ${Process.myPid()}")
        apiReport = FirmwarePreviewProbe.capabilities()
        probe = FirmwarePreviewProbe(this, checkpoint = log::event)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Ink comparison test"; textSize = 22f; setTextColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        })
        val controls = LinearLayout(this)
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; minHeight = dp(48)
            setOnClickListener { action() }
            controls.addView(this)
        }
        button("Back") { finish() }
        button("Android pen") { stopTest("Android pen: write with the pen tip."); paper.mode(false) }
        button("Firmware preview") { startFirmwareTest() }
        button("Clear") { stopTest("Cleared. Choose a test to repeat."); paper.clear() }
        button("Copy report") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fastnotes ink test", report()))
            Toast.makeText(this, "Ink test report copied", Toast.LENGTH_SHORT).show()
        }
        root.addView(HorizontalScrollView(this).apply { addView(controls); isHorizontalScrollBarEnabled = false })
        instructions = TextView(this).apply {
            text = "Scratch test only; saved sheets are not opened. Use the pen tip. Start with Android pen, then compare Firmware preview."
            textSize = 14f; setTextColor(Color.BLACK); setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(instructions)
        paper = InkTestView(this).apply {
            onStrokeFinished = {
                status.text = stats()
            }
        }
        root.addView(paper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "Android pen: write a rapid continuous up/down stroke."
            textSize = 13f; setTextColor(Color.BLACK); minHeight = dp(56)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status)
        setContentView(root)
        paper.viewTreeObserver.addOnPreDrawListener(geometryObserver)
    }

    private fun startFirmwareTest() {
        stopTest("Preparing firmware test…")
        val rect = screenBounds()
        if (rect == null || !hasWindowFocus()) {
            status.text = "Tap this pane, then select Firmware preview again."
            return
        }
        paper.mode(false)
        if (probe.start(rect)) {
            registeredBounds = Rect(rect)
            paper.mode(true)
            instructions.text = "Look for ink WHILE the pen moves. The app holds back its own ink until one second after pen-up, so delayed ink alone does not prove firmware preview works."
            status.text = "Firmware region requested; visible drawing is unverified."
        } else {
            status.text = probe.status
            instructions.text = "The firmware test could not start. Copy report includes API availability and the last completed call. Android pen remains available."
        }
    }

    private fun stopTest(message: String) {
        if (!::probe.isInitialized) return
        if (::paper.isInitialized && paper.samples > 0) {
            runs.addLast(stats())
            while (runs.size > 8) runs.removeFirst()
            log.event(runs.last())
        }
        val released = probe.stop()
        registeredBounds = null
        if (::paper.isInitialized && paper.firmwareTest) paper.mode(false)
        if (::status.isInitialized) status.text = if (released) message else "Firmware cleanup could not be confirmed. Close this test."
        if (::instructions.isInitialized) instructions.text = "Scratch test only; saved sheets are not opened. Use the pen tip. Start with Android pen, then compare Firmware preview."
    }

    private fun screenBounds(): Rect? {
        if (!::paper.isInitialized || !paper.isAttachedToWindow) return null
        val visible = Rect()
        if (!paper.getLocalVisibleRect(visible) || !visible.intersect(0, 0, paper.width, paper.height)) return null
        val origin = IntArray(2)
        paper.getLocationOnScreen(origin)
        visible.offset(origin[0], origin[1])
        return visible
    }

    private fun stats() = (if (paper.firmwareTest) "Firmware-request test" else "Android pen test") +
        ": ${paper.samples} Android samples; max event age ${paper.maxEventAgeMs} ms. " +
        "Event age excludes visible panel latency."

    private fun report(): String = buildString {
        append("Fastnotes ${BuildConfig.VERSION_NAME} ink investigation\n")
        append("Model: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\nBuild: ${Build.DISPLAY}\n")
        append("Target: ${applicationInfo.targetSdkVersion}; split-screen: $isInMultiWindowMode\n")
        append("Display: ").append(paper.display?.let { "${it.mode.physicalWidth}×${it.mode.physicalHeight}; rotation ${it.rotation}" }).append('\n')
        append("Canvas: ").append(screenBounds()?.toShortString()).append('\n')
        append("Firmware status: ").append(probe.status).append('\n')
        append(stats()).append('\n')
        append("Earlier comparisons:\n").append(runs.joinToString("\n")).append('\n')
        append("Current stages:\n").append(InkProbeLog.read(this@InkTestActivity)).append('\n')
        append("Previous run:\n").append(oldRun).append('\n')
        append("\nRead-only API inspection (presence is not proof of working ink):\n").append(apiReport)
        append("\n\n").append(CrashReports(this@InkTestActivity).details())
    }.take(64 * 1024)

    override fun onPause() {
        stopTest("Test paused. Select Firmware preview to start it again.")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) stopTest("Focus changed. Select Firmware preview to start it again.")
    }

    override fun onDestroy() {
        paper.viewTreeObserver.removeOnPreDrawListener(geometryObserver)
        probe.stop()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
