package dev.fastnotes.diagnostics

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.fastnotes.FastnotesApp

/** Save only to the destination explicitly chosen in Android's document picker. */
class FirmwareEvidenceActivity : Activity() {
    private val controller get() = (application as FastnotesApp).firmwareEvidence
    private lateinit var status: TextView
    private lateinit var save: Button
    private var resumed = false
    private var launchWhenReady = false
    private var awaitingDestination = false
    private val observer: (FirmwareEvidenceState) -> Unit = { render(it) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        launchWhenReady = state?.getBoolean("launchWhenReady") ?: false
        awaitingDestination = state?.getBoolean("awaitingDestination") ?: false
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            setPadding(24, 16, 24, 24)
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.BLACK); setPadding(0, 12, 0, 12); content.addView(this)
        }
        content.addView(Button(this).apply { text = "Back"; isAllCaps = false; setOnClickListener { finish() } })
        text("Firmware API files", 24f)
        text("Save the tablet’s system handwriting API code and the ink diagnostic report as a ZIP for investigation.")
        text("Only system framework/API files are collected. Saved sheets, scratch strokes and personal files are excluded. Nothing is uploaded automatically.")
        text("Tap Save ZIP, choose Downloads in Android’s file picker, then Save. Attach that ZIP in the chat where you shared the ink reports.")
        status = text("Ready to prepare the API files.")
        save = Button(this).apply {
            text = "Save ZIP"; isAllCaps = false
            setOnClickListener {
                launchWhenReady = true
                if (controller.state is FirmwareEvidenceState.Ready) launchPicker()
                else controller.prepare(intent.getStringExtra(EXTRA_REPORT).orEmpty().take(64 * 1024))
            }
        }
        content.addView(save)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun render(state: FirmwareEvidenceState) {
        save.isEnabled = state != FirmwareEvidenceState.Preparing && state != FirmwareEvidenceState.Saving
        status.text = when (state) {
            FirmwareEvidenceState.Idle -> "Ready to prepare the API files."
            FirmwareEvidenceState.Preparing -> "Reading system API files…"
            is FirmwareEvidenceState.Ready -> if (state.summary.inventoryOnly)
                "API code was not readable. The ZIP contains the diagnostic report and system-file inventory."
                else "Prepared ${state.summary.codeFiles} API code files. Choose where to save the ZIP."
            FirmwareEvidenceState.Saving -> "Saving ZIP…"
            is FirmwareEvidenceState.Saved -> "ZIP saved. Attach fastnotes-api-evidence.zip in the chat." +
                if (state.summary.inventoryOnly) " API code was unavailable; the inventory explains what was readable." else ""
            is FirmwareEvidenceState.Failed -> "Export could not finish (${state.type}). Tap Save ZIP to retry."
        }
        if (state is FirmwareEvidenceState.Failed) launchWhenReady = false
        if (state is FirmwareEvidenceState.Ready) launchPicker()
    }

    private fun launchPicker() {
        if (!resumed || !launchWhenReady || awaitingDestination || controller.state !is FirmwareEvidenceState.Ready) return
        launchWhenReady = false; awaitingDestination = true
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "fastnotes-api-evidence.zip")
            }, SAVE_REQUEST)
        } catch (_: ActivityNotFoundException) {
            awaitingDestination = false
            status.text = "Android’s file picker is unavailable on this device."
        }
    }

    @Deprecated("Activity result API retained for the minimum-API standalone Activity")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != SAVE_REQUEST) return
        awaitingDestination = false; launchWhenReady = false
        val uri = data?.data
        if (result == RESULT_OK && uri != null) controller.save(uri)
        else status.text = "Save canceled. Tap Save ZIP to choose a destination again."
    }
    override fun onStart() { super.onStart(); controller.observe(observer) }
    override fun onStop() { controller.removeObserver(observer); super.onStop() }
    override fun onResume() { super.onResume(); resumed = true; launchPicker() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("launchWhenReady", launchWhenReady); state.putBoolean("awaitingDestination", awaitingDestination)
        super.onSaveInstanceState(state)
    }
    companion object { const val EXTRA_REPORT = "inkReport"; private const val SAVE_REQUEST = 204 }
}
