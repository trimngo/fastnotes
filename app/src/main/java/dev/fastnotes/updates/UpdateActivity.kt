package dev.fastnotes.updates

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import dev.fastnotes.BuildConfig
import dev.fastnotes.FastnotesApp
import java.io.File
import java.util.Locale

internal fun updateInstallIntent(context: android.content.Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
    return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        data = uri
        clipData = ClipData.newRawUri("Fastnotes update", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** A normal resizable Activity keeps native ink paused throughout installer/permission dialogs. */
class UpdateActivity : Activity() {
    private val updates get() = (application as FastnotesApp).updates
    private lateinit var status: TextView
    private lateinit var notice: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var check: Button
    private var resumed = false
    private var installWhenReady = false
    private var awaitingPermission = false
    private val observer: (UpdateState) -> Unit = { state -> render(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installWhenReady = savedInstanceState?.getBoolean("installWhenReady") ?: false
        awaitingPermission = savedInstanceState?.getBoolean("awaitingPermission") ?: false
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.BLACK)
            setPadding(0, dp(10), 0, dp(10)); content.addView(this)
        }
        content.addView(button("Back") { finish() })
        text("Fastnotes updates", 24f)
        text("Installed version: ${BuildConfig.VERSION_NAME}")
        content.addView(Switch(this).apply {
            text = "Check automatically"; textSize = 16f; setTextColor(Color.BLACK)
            minHeight = dp(56); isChecked = updates.automatic
            setOnCheckedChangeListener { _, checked -> updates.automatic = checked }
        })
        text("Check when Fastnotes opens, at most once a day. Development releases are included.", 14f)
        status = text("", 18f)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            contentDescription = "Update download progress"
        }
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))
        notice = text("", 14f)
        action = button("Download & install") {
            notice.text = ""
            installWhenReady = true
            if (updates.state is UpdateState.Ready) tryInstall() else updates.download()
        }
        content.addView(action)
        check = button("Check now") { notice.text = ""; updates.check() }
        content.addView(check)
        text("Android will ask you to confirm installation. Your saved sheets stay in place.", 14f)
        text("Only update requests and app downloads use the network. Handwriting and notes stay on this device.", 14f)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onStart() {
        super.onStart()
        updates.observe(observer)
        if (updates.state == UpdateState.Idle) updates.check()
    }

    override fun onStop() {
        updates.removeObserver(observer)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (awaitingPermission) {
            awaitingPermission = false
            if (packageManager.canRequestPackageInstalls()) installWhenReady = true
            else notice.text = "Installation wasn't allowed. Tap Install update to try again."
        }
        tryInstall()
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("installWhenReady", installWhenReady)
        outState.putBoolean("awaitingPermission", awaitingPermission)
        super.onSaveInstanceState(outState)
    }

    private fun render(state: UpdateState) {
        status.text = when (state) {
            UpdateState.Idle -> "Ready to check for updates."
            UpdateState.Checking -> "Checking for updates…"
            UpdateState.Current -> "Fastnotes ${BuildConfig.VERSION_NAME} is up to date."
            is UpdateState.Available -> "Fastnotes ${state.release.versionName} is available."
            is UpdateState.Downloading -> "Downloading Fastnotes ${state.release.versionName}… " +
                "${state.received * 100 / state.release.bytes}%"
            is UpdateState.Ready -> "Fastnotes ${state.release.versionName} is ready to install."
            is UpdateState.Failed -> state.message
        }
        val busy = state is UpdateState.Checking || state is UpdateState.Downloading
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = state is UpdateState.Checking
        if (state is UpdateState.Downloading) progress.progress = (state.received * 100 / state.release.bytes).toInt()
        check.isEnabled = !busy
        check.visibility = if (state is UpdateState.Ready) View.GONE else View.VISIBLE
        action.visibility = if (state.release != null) View.VISIBLE else View.GONE
        action.isEnabled = !busy
        action.text = if (state is UpdateState.Ready) "Install update" else "Download & install"
        if (state is UpdateState.Available) {
            notice.text = String.format(Locale.ROOT, "Download size: %.1f MB", state.release.bytes / 1_000_000.0)
        }
        if (state is UpdateState.Failed) installWhenReady = false
        if (state is UpdateState.Ready && installWhenReady && resumed) status.post { tryInstall() }
    }

    private fun tryInstall() {
        if (!resumed || !installWhenReady || awaitingPermission) return
        val ready = updates.state as? UpdateState.Ready ?: return
        installWhenReady = false
        if (!ready.file.isFile || ready.file.length() != ready.release.bytes) {
            updates.missingDownload()
            notice.text = "Android removed the cached download. Please download it again."
            return
        }
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                notice.text = "Allow Fastnotes to install updates on the next Android screen, then come back."
                awaitingPermission = true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                startActivity(updateInstallIntent(this, ready.file))
            }
        } catch (_: ActivityNotFoundException) {
            awaitingPermission = false
            notice.text = "Android couldn't open the installer or installation settings. You can retry here."
        } catch (_: SecurityException) {
            awaitingPermission = false
            notice.text = "Android blocked installation. Check Allow from this source for Fastnotes, then retry."
        }
    }

    private fun button(label: String, clicked: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; minHeight = dp(52)
        setOnClickListener { clicked() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
