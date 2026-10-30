package dev.fastnotes

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.fastnotes.ink.InkView
import dev.fastnotes.ink.Tool
import dev.fastnotes.model.Note
import dev.fastnotes.updates.UpdateActivity
import dev.fastnotes.updates.UpdateState
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val app get() = application as FastnotesApp
    internal lateinit var paper: InkView
        private set
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var undo: Button
    private lateinit var redo: Button
    private lateinit var pen: Button
    private lateinit var eraser: Button
    private lateinit var move: Button
    private lateinit var update: Button
    private val updateObserver: (UpdateState) -> Unit = { state ->
        val label = if (state is UpdateState.Downloading) "Updating…" else "Update"
        if (update.text != label) update.text = label
        update.visibility = if (state.release != null) View.VISIBLE else View.GONE
    }
    private var modalCount = 0
    private var resumed = false
    private var revision = 0L
    private var saveFailed = false
    private var started = false
    private val saveScroll = Runnable { if (!isDestroyed && started) save() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(8), dp(4))
        }
        title = TextView(this).apply {
            text = "Fastnotes"; textSize = 21f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            contentDescription = "Sheet title. Tap to rename."
            setOnClickListener { rename() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        title.gravity = Gravity.CENTER_VERTICAL
        update = button("Update") { openUpdates() }.apply { visibility = View.GONE }
        header.addView(update)
        header.addView(button("Sheets") { sheets() })
        root.addView(header)
        root.addView(rule())

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        pen = button("Pen") { selectTool(Tool.PEN) }
        eraser = button("Erase") { selectTool(Tool.ERASER) }
        move = button("Move") { selectTool(Tool.MOVE) }
        undo = button("Undo") {
            paper.finishGesture(false)
            if (app.editor?.undo() == true) { paper.documentChanged(); edited() }
        }
        redo = button("Redo") {
            paper.finishGesture(false)
            if (app.editor?.redo() == true) { paper.documentChanged(); edited() }
        }
        listOf(pen, eraser, move, undo, redo, button("Settings") { settings() }).forEach {
            tools.addView(it)
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(rule())

        paper = InkView(this).apply {
            onEdited = { edited() }
            onScrolled = {
                revision++
                status.text = "Saving…"
                removeCallbacks(saveScroll)
                postDelayed(saveScroll, 350)
            }
            onEngineChanged = { updateHint() }
            palmGuard = app.preferences.getBoolean("palmGuard", true)
            setRendering(app.preferences.getBoolean("frontBuffer", true),
                app.preferences.getBoolean("viwoodsNativeInk", true))
        }
        root.addView(paper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(rule())
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(8), dp(2))
        }
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "Opening your sheet…"; textSize = 12f; setTextColor(Color.BLACK)
            setOnClickListener { if (saveFailed) save() }
            contentDescription = "Save status. Tap to retry if saving fails."
        }
        hint = TextView(this).apply { textSize = 11f; setTextColor(Color.DKGRAY); maxLines = 2 }
        messages.addView(status)
        messages.addView(hint)
        footer.addView(messages, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(button("↑") { paper.scrollByScreen(-paper.height * 0.7f) }.apply {
            contentDescription = "Scroll up"
        })
        footer.addView(button("↓") { paper.scrollByScreen(paper.height * 0.7f) }.apply {
            contentDescription = "Scroll down"
        })
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        setContentView(root)
        selectTool(Tool.PEN)
        start()
    }

    private fun start() {
        app.start { result ->
            if (isDestroyed || isFinishing) return@start
            result.fold({ loaded ->
                started = true
                paper.bind(app.editor!!)
                title.text = app.editor!!.note.title
                status.text = if (loaded.recovered) "Recovered previous saved copy" else "Saved on this device"
                updateButtons()
            }, {
                status.text = "Couldn’t open sheets. Tap here to retry."
                status.setOnClickListener { start() }
                message("Couldn’t open sheets", "Your existing files have been preserved. Close other apps if storage is full, then retry.")
            })
        }
    }

    override fun onStart() {
        super.onStart()
        app.updates.observe(updateObserver)
    }

    override fun onStop() {
        app.updates.removeObserver(updateObserver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        paper.setEnvironment(true, modalCount > 0, isInMultiWindowMode)
        app.updates.check(automatic = true)
    }

    override fun onPause() {
        resumed = false
        paper.removeCallbacks(saveScroll)
        paper.setEnvironment(false, modalCount > 0, isInMultiWindowMode)
        if (started) save()
        super.onPause()
    }

    override fun onMultiWindowModeChanged(inMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(inMultiWindowMode, newConfig)
        paper.setEnvironment(resumed, modalCount > 0, inMultiWindowMode)
    }

    private fun edited() {
        revision++
        title.text = app.editor?.note?.title ?: "Fastnotes"
        updateButtons()
        save()
    }

    private fun save() {
        val snapshot = app.editor?.note ?: return
        val version = revision
        status.text = "Saving…"
        app.save(snapshot) { success ->
            if (isDestroyed || isFinishing || app.editor?.note?.id != snapshot.id) return@save
            // A late completion cannot hide a failure or pending newer edit.
            if (version != revision) return@save
            saveFailed = !success
            status.text = if (success) "Saved on this device" else "Couldn’t save · tap to retry"
        }
    }

    private fun updateButtons() {
        undo.isEnabled = app.editor?.canUndo == true
        redo.isEnabled = app.editor?.canRedo == true
    }

    private fun selectTool(tool: Tool) {
        if (::paper.isInitialized) paper.tool = tool
        for ((button, value) in listOf(pen to Tool.PEN, eraser to Tool.ERASER, move to Tool.MOVE)) {
            val selected = tool == value
            button.background = border(if (selected) Color.BLACK else Color.WHITE)
            button.setTextColor(if (selected) Color.WHITE else Color.BLACK)
            button.isSelected = selected
        }
        updateHint()
    }

    private fun updateHint() {
        if (!::hint.isInitialized || !::paper.isInitialized) return
        val instruction = when (paper.tool) {
            Tool.ERASER -> "Erase whole strokes · Undo restores them"
            Tool.MOVE -> "Drag with the pen or a finger to scroll"
            Tool.PEN -> if (paper.palmGuard) "Palm guard on · two fingers or arrows to scroll"
                else "Pen writes · one finger scrolls"
        }
        hint.text = (if (paper.nativeActive) "Viwoods native · " else "Android ink · ") + instruction
    }

    private fun sheets() {
        if (!started) return
        paper.finishGesture(false)
        save()
        app.read({ app.store.list() }) { result ->
            if (isDestroyed || isFinishing) return@read
            result.fold({ (sheets, unreadable) ->
                val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                val labels = sheets.map { it.title + "\n" + format.format(Date(it.updatedAt)) }.toTypedArray()
                val builder = AlertDialog.Builder(this).setTitle("Your sheets")
                    .setItems(labels) { _, which -> openSheet(sheets[which].id) }
                    .setPositiveButton("New sheet") { _, _ ->
                        app.select(Note())
                        paper.bind(app.editor!!)
                        revision++
                        title.text = app.editor!!.note.title
                        selectTool(Tool.PEN)
                        save(); updateButtons()
                    }
                    .setNegativeButton("Close", null)
                show(builder.create())
                if (unreadable > 0) Toast.makeText(this, "Some sheets could not be read. Their files were preserved.", Toast.LENGTH_LONG).show()
            }, { message("Couldn’t list sheets", "Your current sheet remains open. Try again after checking available storage.") })
        }
    }

    private fun openSheet(id: String) {
        paper.finishGesture(false)
        save()
        modalCount++
        paper.setEnvironment(resumed, true, isInMultiWindowMode)
        app.read({ app.store.load(id) }) { result ->
            if (isDestroyed || isFinishing) return@read
            modalCount--
            paper.setEnvironment(resumed, modalCount > 0, isInMultiWindowMode)
            result.fold({ loaded ->
                app.select(loaded.note)
                paper.bind(app.editor!!)
                revision++
                title.text = loaded.note.title
                status.text = if (loaded.recovered) "Recovered previous saved copy" else "Saved on this device"
                updateButtons()
            }, { message("Couldn’t open sheet", "The current sheet and the saved files have been preserved.") })
        }
    }

    private fun rename() {
        val editor = app.editor ?: return
        val input = EditText(this).apply {
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(120))
            setText(editor.note.title); selectAll()
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        show(AlertDialog.Builder(this).setTitle("Sheet name").setView(input)
            .setPositiveButton("Save") { _, _ -> editor.rename(input.text.toString()); edited() }
            .setNegativeButton("Cancel", null).create())
    }

    private fun settings() {
        val editor = app.editor ?: return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 14f; setTextColor(Color.BLACK)
            setPadding(0, dp(10), 0, dp(6)); content.addView(this)
        }
        val spacing = label("Line spacing · " + editor.note.lineSpacing.toInt())
        content.addView(SeekBar(this).apply {
            max = 72; progress = editor.note.lineSpacing.toInt() - 24
            contentDescription = "Line spacing"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    if (!user) return
                    editor.paper(spacing = value + 24f)
                    spacing.text = "Line spacing · " + (value + 24)
                    paper.documentChanged()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { edited() }
            })
        })
        label("Changing line spacing leaves existing handwriting in place.")
        val width = label("Pen width · " + editor.note.penWidth)
        content.addView(SeekBar(this).apply {
            max = 14; progress = ((editor.note.penWidth - 1) * 2).toInt()
            contentDescription = "Pen width"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    if (!user) return
                    editor.paper(width = 1 + value / 2f)
                    width.text = "Pen width · " + editor.note.penWidth
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { edited() }
            })
        })
        content.addView(Switch(this).apply {
            text = "Palm guard"; isChecked = paper.palmGuard
            setTextColor(Color.BLACK); minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                paper.palmGuard = checked
                app.preferences.edit().putBoolean("palmGuard", checked).apply()
                updateHint()
            }
        })
        label("On: two fingers scroll. Off: one finger scrolls. Fingers never draw. Move and the arrows always let you scroll.")
        content.addView(Switch(this).apply {
            text = "Low-latency rendering"; isChecked = app.preferences.getBoolean("frontBuffer", true)
            setTextColor(Color.BLACK); minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                app.preferences.edit().putBoolean("frontBuffer", checked).apply()
                paper.setRendering(checked, app.preferences.getBoolean("viwoodsNativeInk", true))
            }
        })
        label("Turn this off to use compatibility rendering if ink does not display correctly.")
        content.addView(Switch(this).apply {
            text = "Viwoods fast ink (recommended)"
            isChecked = paper.panelAvailable && app.preferences.getBoolean("viwoodsNativeInk", true)
            isEnabled = paper.panelAvailable
            setTextColor(Color.BLACK); minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                app.preferences.edit().putBoolean("viwoodsNativeInk", checked).apply()
                paper.setRendering(app.preferences.getBoolean("frontBuffer", true), checked)
            }
        })
        label(if (paper.panelAvailable)
            "Uses firmware pen callbacks directly. Enabled by default. Turn off to compare Android input."
            else "The native Viwoods input interface was not found. Android ink is active.")
        label("Notes stay on this device. Uninstalling removes them. This version has no sync or note encryption.")
        content.addView(button("Device details") { about() })
        content.addView(button("App updates") { openUpdates() })
        show(AlertDialog.Builder(this).setTitle("Paper & input")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done") { _, _ -> save() }.create())
    }

    private fun about() {
        val average = if (paper.sampleCount > 0) paper.sampleAgeTotalMs.toDouble() / paper.sampleCount else 0.0
        val details = "Model: " + Build.MODEL +
            "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
            "\nApp compatibility target: API " + applicationInfo.targetSdkVersion +
            "\nSystem build: " + Build.DISPLAY +
            "\nReported AiPaper firmware: 3.15.15" +
            "\n\n" + paper.engineDescription +
            "\n" + paper.nativeDiagnostics +
            "\nInput samples this session: " + paper.sampleCount +
            "\nAverage input event age: " + String.format(java.util.Locale.ROOT, "%.1f ms", average) +
            "\nThis is app input timing, not pen-to-visible-ink latency." +
            "\n\nFor testing: try both landscape rotations, portrait, Chrome split-screen, pen erasing, scrolling, and reopening a saved sheet."
        show(AlertDialog.Builder(this).setTitle("Fastnotes " + BuildConfig.VERSION_NAME)
            .setMessage(details)
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Fastnotes device details", details))
                Toast.makeText(this, "Device details copied", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null).create())
    }

    private fun openUpdates() {
        paper.finishGesture(false)
        if (started) save()
        startActivity(Intent(this, UpdateActivity::class.java))
    }

    private fun message(title: String, text: String) {
        if (isDestroyed || isFinishing) return
        show(AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).create())
    }

    private fun show(dialog: AlertDialog) {
        modalCount++
        paper.setEnvironment(resumed, true, isInMultiWindowMode)
        dialog.setOnDismissListener {
            modalCount--
            if (!isDestroyed) paper.setEnvironment(resumed, modalCount > 0, isInMultiWindowMode)
        }
        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z) {
            paper.finishGesture(false)
            val changed = if (event.isShiftPressed) app.editor?.redo() else app.editor?.undo()
            if (changed == true) { paper.documentChanged(); edited() }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            paper.scrollByScreen(paper.height * if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -0.7f else 0.7f)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        setTextColor(Color.BLACK); minWidth = dp(if (text == "↑" || text == "↓") 48 else 62)
        minimumWidth = minWidth; minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background = border(Color.WHITE)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
    }

    private fun border(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(4).toFloat(); setStroke(dp(1), Color.rgb(170, 170, 170))
    }
    private fun rule() = View(this).apply {
        setBackgroundColor(Color.rgb(190, 190, 190))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
