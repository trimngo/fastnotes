package dev.fastnotes

import dev.fastnotes.model.*
import dev.fastnotes.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun note() = Note(title = "Übung · Grüße", scrollY = 410f,
        strokes = listOf(Stroke(1, 2.5f, listOf(InkPoint(50f, 100f), InkPoint(200f, 300f)))))

    @Test fun savedGeometrySettingsAndUnicodeTitleRoundTrip() {
        val original = note()
        assertEquals(original, NoteCodec.decode(NoteCodec.encode(original)))
    }

    @Test fun damagedLatestSaveRecoversPreviousGoodCopyAndSurvivesNextSave() {
        val dir = temporary.newFolder()
        val store = NoteStore(dir)
        val original = note()
        store.save(original)
        store.save(original.copy(title = "Second"))
        File(dir, original.id + ".fnote").writeText("interrupted save")
        val recovered = store.load(original.id)
        assertTrue(recovered.recovered)
        assertEquals(original, recovered.note)
        store.save(recovered.note.copy(title = "Recovered"))
        assertEquals("Recovered", store.load(original.id).note.title)
        File(dir, original.id + ".fnote").writeText("corrupt again")
        assertEquals(original, store.load(original.id).note)
    }

    @Test fun crashBetweenRenamesRecoversBackupAndIgnoresTemporaryFile() {
        val dir = temporary.newFolder()
        val store = NoteStore(dir)
        val original = note()
        store.save(original)
        File(dir, original.id + ".fnote").renameTo(File(dir, original.id + ".bak"))
        File(dir, original.id + ".tmp").writeText("unfinished")
        assertEquals(original, store.load(original.id).note)
        assertEquals(1, store.list().first.size)
    }

    @Test fun checksumDetectsCorruptionAndTruncation() {
        val bytes = NoteCodec.encode(note())
        val corrupt = bytes.copyOf()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { NoteCodec.decode(corrupt) }
        assertThrows(IllegalArgumentException::class.java) { NoteCodec.decode(bytes.copyOf(bytes.size - 1)) }
    }

    @Test fun untrustedIdentityAndInvalidGeometryAreRejected() {
        val original = note()
        val bad = original.copy(strokes = listOf(Stroke(1, 2f, listOf(InkPoint(Float.NaN, 10f)))))
        assertThrows(IllegalArgumentException::class.java) { NoteCodec.decode(NoteCodec.encode(bad)) }
        val store = NoteStore(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.load("../../outside") }
    }

    @Test fun unreadableFilesArePreservedAndReported() {
        val dir = temporary.newFolder()
        val original = note()
        val file = File(dir, original.id + ".fnote").apply { writeText("broken") }
        val (sheets, unreadable) = NoteStore(dir).list()
        assertTrue(sheets.isEmpty())
        assertEquals(1, unreadable)
        assertEquals("broken", file.readText())
    }
}
