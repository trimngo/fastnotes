package dev.fastnotes.storage

import dev.fastnotes.model.Note
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class LoadedNote(val note: Note, val recovered: Boolean)
data class SheetSummary(val id: String, val title: String, val updatedAt: Long)

/** Single writer; durable temporary file + previous-good backup, then atomic rename. */
class NoteStore(private val directory: File) {
    private val badPrimary = HashSet<String>()

    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot open note storage" } }

    @Synchronized
    fun load(id: String): LoadedNote {
        val primary = file(id, ".fnote")
        try {
            val note = read(primary, id)
            badPrimary.remove(id)
            return LoadedNote(note, false)
        } catch (error: Exception) {
            val backup = file(id, ".bak")
            val recovered = try { read(backup, id) } catch (_: Exception) { throw error }
            badPrimary.add(id)
            return LoadedNote(recovered, true)
        }
    }

    @Synchronized
    fun save(note: Note) {
        val primary = file(note.id, ".fnote")
        val temporary = file(note.id, ".tmp")
        val backup = file(note.id, ".bak")
        val bytes = NoteCodec.encode(note)
        FileOutputStream(temporary).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (primary.exists() && note.id !in badPrimary) {
            check(primary.renameTo(backup)) { "Cannot preserve previous sheet" }
        }
        // On Linux/Android rename replaces the destination atomically, including a corrupt primary.
        check(temporary.renameTo(primary)) { "Cannot commit sheet" }
        badPrimary.remove(note.id)
    }

    @Synchronized
    fun list(): Pair<List<SheetSummary>, Int> {
        val ids = directory.listFiles().orEmpty().filter {
            it.extension == "fnote" || it.extension == "bak"
        }.map { it.nameWithoutExtension }.distinct()
        var unreadable = 0
        val summaries = ids.mapNotNull { id ->
            try {
                val note = load(id).note
                SheetSummary(note.id, note.title, note.updatedAt)
            } catch (_: Exception) { unreadable++; null }
        }.sortedByDescending { it.updatedAt }
        return summaries to unreadable
    }

    private fun read(file: File, id: String): Note {
        require(file.length() in 20..NoteCodec.MAX_BYTES.toLong() + 20) { "Invalid sheet size" }
        val note = NoteCodec.decode(file.readBytes())
        require(note.id == id) { "Sheet identity mismatch" }
        return note
    }

    private fun file(id: String, suffix: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid sheet identifier" }
        return File(directory, id + suffix)
    }
}
