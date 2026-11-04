package dev.fastnotes.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.fastnotes.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.zip.ZipFile

sealed interface FirmwareEvidenceState {
    data object Idle : FirmwareEvidenceState
    data object Preparing : FirmwareEvidenceState
    data class Ready(val file: File, val summary: FirmwareEvidenceSummary) : FirmwareEvidenceState
    data object Saving : FirmwareEvidenceState
    data class Saved(val summary: FirmwareEvidenceSummary) : FirmwareEvidenceState
    data class Failed(val type: String) : FirmwareEvidenceState
}

/** Retained by Application across rotation, with app Context only and no automatic upload. */
class FirmwareEvidenceController internal constructor(
    private val context: Context,
    private val evidence: FirmwareEvidence,
    private val io: Executor,
    private val document: (Uri) -> OutputStream
) {
    constructor(context: Context) : this(context.applicationContext, FirmwareEvidence(),
        Executors.newSingleThreadExecutor { task -> Thread(task, "firmware-api-export") },
        { uri -> context.applicationContext.contentResolver.openOutputStream(uri, "w") ?: error("Document unavailable") })
    private val main = Handler(Looper.getMainLooper())
    private val observers = ArrayList<(FirmwareEvidenceState) -> Unit>()
    var state: FirmwareEvidenceState = FirmwareEvidenceState.Idle
        private set
    private val busy get() = state == FirmwareEvidenceState.Preparing || state == FirmwareEvidenceState.Saving
    private val archive get() = File(context.cacheDir, "firmware-api/fastnotes-api-evidence.zip")

    fun observe(observer: (FirmwareEvidenceState) -> Unit) {
        if (observers.none { it === observer }) observers.add(observer)
        observer(state)
    }
    fun removeObserver(observer: (FirmwareEvidenceState) -> Unit) { observers.removeAll { it === observer } }
    private fun update(value: FirmwareEvidenceState) {
        state = value; observers.toList().forEach { it(value) }
    }

    fun prepare(report: String) {
        if (busy) return
        update(FirmwareEvidenceState.Preparing)
        io.execute {
            val result = runCatching {
                val file = archive
                val device = JSONObject().put("model", Build.MODEL).put("build", Build.DISPLAY)
                    .put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
                    .put("appVersion", BuildConfig.VERSION_NAME).put("target", context.applicationInfo.targetSdkVersion)
                FirmwareEvidenceState.Ready(file, evidence.write(file, device, report))
            }
            main.post { update(result.getOrElse { FirmwareEvidenceState.Failed(it.javaClass.simpleName) }) }
        }
    }

    fun save(uri: Uri) {
        if (busy) return
        val prepared = state as? FirmwareEvidenceState.Ready
        update(FirmwareEvidenceState.Saving)
        io.execute {
            val result = runCatching {
                val ready = prepared ?: recover()
                check(ready.file.isFile && ready.file.length() == ready.summary.bytes && ready.summary.bytes <= 24L * 1024 * 1024)
                document(uri).use { out -> ready.file.inputStream().use { it.copyTo(out) } }
                FirmwareEvidenceState.Saved(ready.summary)
            }
            main.post { update(result.getOrElse { FirmwareEvidenceState.Failed(it.javaClass.simpleName) }) }
        }
    }

    /** Android may kill the test process while its document picker is in front. */
    private fun recover(): FirmwareEvidenceState.Ready {
        val file = archive
        check(file.isFile && file.length() <= 24L * 1024 * 1024)
        val manifest = ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("No export manifest")
            check(entry.size in 1L..512L * 1024)
            zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
        }
        val device = manifest.getJSONObject("device")
        check(manifest.getInt("schema") == 1 && device.getString("build") == Build.DISPLAY &&
            device.getInt("api") == Build.VERSION.SDK_INT && device.getString("appVersion") == BuildConfig.VERSION_NAME)
        val count = manifest.getInt("codeFiles")
        check(count in 0..64 && count == manifest.getJSONArray("artifacts").length())
        return FirmwareEvidenceState.Ready(file, FirmwareEvidenceSummary(count, file.length(), count == 0))
    }
}
