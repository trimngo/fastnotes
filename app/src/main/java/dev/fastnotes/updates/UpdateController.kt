package dev.fastnotes.updates

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.fastnotes.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

sealed class UpdateState(open val release: UpdateRelease? = null) {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object Current : UpdateState()
    data class Available(override val release: UpdateRelease) : UpdateState(release)
    data class Downloading(override val release: UpdateRelease, val received: Long) : UpdateState(release)
    data class Ready(override val release: UpdateRelease, val file: File) : UpdateState(release)
    data class Failed(val message: String, override val release: UpdateRelease? = null) : UpdateState(release)
}

/** All observable state is main-thread-owned. Update IO never shares the note-saving executor. */
class UpdateController(
    context: Context,
    private val client: ReleaseClient = ReleaseClient(),
    private val verify: (File, UpdateRelease) -> Unit = ApkVerifier(context.applicationContext)::verify,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    internal val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "app-updates") }
    private val directory get() = File(app.cacheDir, "updates")
    private val apk get() = File(directory, "fastnotes-update.apk")
    private val receipt get() = File(directory, "ready.json")
    private val observers = LinkedHashSet<(UpdateState) -> Unit>()
    var state: UpdateState = UpdateState.Idle
        private set
    private var restored = false
    var automatic: Boolean
        get() = preferences.getBoolean("autoUpdates", true)
        set(value) { preferences.edit().putBoolean("autoUpdates", value).apply() }

    fun observe(observer: (UpdateState) -> Unit) { observers.add(observer); observer(state) }
    fun removeObserver(observer: (UpdateState) -> Unit) { observers.remove(observer) }
    private fun publish(next: UpdateState) {
        state = next
        observers.toList().forEach { it(next) }
    }

    fun check(automatic: Boolean = false) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Ready) return
        if (automatic && (!this.automatic || state.release != null)) return
        val last = preferences.getLong("lastUpdateAttempt", 0)
        val interval = if (preferences.getBoolean("lastUpdateSucceeded", false)) 86_400_000L else 3_600_000L
        val elapsed = clock() - last
        val due = last == 0L || elapsed < 0 || elapsed >= interval
        if (automatic && restored && !due) return
        restored = true
        publish(UpdateState.Checking)
        worker.execute {
            val cached = restore()
            if (cached != null) {
                main.post { publish(cached) }
            } else if (automatic && !due) {
                val known = runCatching {
                    val json = JSONObject(preferences.getString("knownUpdate", null) ?: error("No pending update"))
                    UpdateRelease.parse(json, json.getString("tag")).takeIf {
                        it.versionCode > BuildConfig.VERSION_CODE && it.minSdk <= Build.VERSION.SDK_INT
                    }
                }.getOrNull()
                main.post { publish(known?.let(UpdateState::Available) ?: UpdateState.Idle) }
            } else {
                preferences.edit().putLong("lastUpdateAttempt", clock()).putBoolean("lastUpdateSucceeded", false).apply()
                val result = runCatching { client.latest(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }
                result.onSuccess {
                    preferences.edit().putBoolean("lastUpdateSucceeded", true)
                        .putString("knownUpdate", it?.toJson()?.toString()).apply()
                }
                main.post {
                    publish(result.fold({ it?.let(UpdateState::Available) ?: UpdateState.Current }, {
                        UpdateState.Failed(if (it is java.io.IOException)
                            "Couldn't check for updates. Check your connection and try again."
                        else "Couldn't read the latest update. Please try again later.")
                    }))
                }
            }
        }
    }

    fun download() {
        val release = state.release ?: return
        if (state is UpdateState.Downloading || state is UpdateState.Ready) return
        publish(UpdateState.Downloading(release, 0))
        worker.execute {
            var lastProgress = 0L
            val result = runCatching {
                receipt.delete()
                client.download(release, apk) { received ->
                    val now = System.nanoTime()
                    if (now - lastProgress >= 1_000_000_000L) {
                        lastProgress = now
                        main.post { publish(UpdateState.Downloading(release, received)) }
                    }
                }
                verify(apk, release)
                receipt.writeText(release.toJson().toString())
                UpdateState.Ready(release, apk)
            }
            if (result.isFailure) { apk.delete(); receipt.delete() }
            main.post {
                publish(result.getOrElse {
                    UpdateState.Failed("Couldn't download or verify the update. Check your connection and available storage, then retry.", release)
                })
            }
        }
    }

    fun missingDownload() {
        val release = state.release ?: return
        receipt.delete()
        publish(UpdateState.Available(release))
    }

    private fun restore(): UpdateState.Ready? {
        val result = runCatching {
            check(receipt.isFile && receipt.length() <= 32 * 1024)
            val json = JSONObject(receipt.readText())
            val release = UpdateRelease.parse(json, json.getString("tag"))
            check(release.versionCode > BuildConfig.VERSION_CODE)
            verify(apk, release)
            UpdateState.Ready(release, apk)
        }.getOrNull()
        if (result == null) { apk.delete(); receipt.delete(); File(directory, "fastnotes-update.apk.part").delete() }
        return result
    }
}
