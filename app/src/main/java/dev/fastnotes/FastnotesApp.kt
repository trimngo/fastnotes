package dev.fastnotes

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.fastnotes.model.Note
import dev.fastnotes.model.NoteEditor
import dev.fastnotes.storage.LoadedNote
import dev.fastnotes.storage.NoteStore
import dev.fastnotes.updates.UpdateController
import dev.fastnotes.diagnostics.CrashReports
import java.io.File
import java.util.concurrent.Executors

class FastnotesApp : Application() {
    val preferences by lazy { getSharedPreferences("preferences", MODE_PRIVATE) }
    val store by lazy { NoteStore(File(filesDir, "sheets")) }
    val updates by lazy { UpdateController(this) }
    val crashes by lazy { CrashReports(this) }
    val io = Executors.newSingleThreadExecutor { task -> Thread(task, "sheet-storage") }
    private val main = Handler(Looper.getMainLooper())
    var editor: NoteEditor? = null
        private set
    private var loading = false
    private val waiting = ArrayList<(Result<LoadedNote>) -> Unit>()
    private data class PendingSave(val note: Note, val complete: (Boolean) -> Unit)
    private val pending = LinkedHashMap<String, PendingSave>()
    private var draining = false

    override fun onCreate() {
        super.onCreate()
        crashes.install()
    }

    fun start(callback: (Result<LoadedNote>) -> Unit) {
        editor?.let { callback(Result.success(LoadedNote(it.note, false))); return }
        waiting.add(callback)
        if (loading) return
        loading = true
        io.execute {
            val result = runCatching {
                val (sheets, unreadable) = store.list()
                val last = preferences.getString("lastSheet", null)
                val selected = sheets.firstOrNull { it.id == last } ?: sheets.firstOrNull()
                if (selected != null) store.load(selected.id)
                else {
                    check(unreadable == 0) { "Existing sheets could not be opened; their files were preserved." }
                    val note = Note()
                    store.save(note)
                    LoadedNote(note, false)
                }
            }
            main.post {
                result.onSuccess { select(it.note) }
                loading = false
                val callbacks = waiting.toList()
                waiting.clear()
                callbacks.forEach { it(result) }
            }
        }
    }

    fun select(note: Note) {
        editor = NoteEditor(note)
        preferences.edit().putString("lastSheet", note.id).apply()
    }

    fun save(note: Note, complete: (Boolean) -> Unit = {}) {
        synchronized(pending) {
            // Coalesce rapid edits to a sheet; never build an unbounded disk-write backlog.
            pending[note.id] = PendingSave(note, complete)
            if (draining) return
            draining = true
        }
        io.execute { drainSaves() }
    }

    private fun drainSaves() {
        while (true) {
            val request = synchronized(pending) {
                val first = pending.entries.firstOrNull()
                if (first == null) { draining = false; return }
                pending.remove(first.key)!!
            }
            val success = runCatching { store.save(request.note) }.isSuccess
            main.post { request.complete(success) }
        }
    }

    fun <T> read(work: () -> T, complete: (Result<T>) -> Unit) {
        io.execute {
            val result = runCatching(work)
            main.post { complete(result) }
        }
    }
}
