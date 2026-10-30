package dev.fastnotes.storage

import dev.fastnotes.model.InkPoint
import dev.fastnotes.model.Note
import dev.fastnotes.model.Stroke
import java.io.*
import java.util.UUID
import java.util.zip.CRC32

/** Versioned, bounded binary format. No note contents are written to logs. */
object NoteCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAGIC = 0x464E4F54 // FNOT
    private const val VERSION = 1
    private const val MAX_POINTS = 2_000_000

    fun encode(note: Note): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeUTF(note.id)
            out.writeUTF(note.title)
            out.writeLong(note.updatedAt)
            out.writeFloat(note.pageWidth)
            out.writeFloat(note.lineSpacing)
            out.writeFloat(note.penWidth)
            out.writeFloat(note.scrollY)
            out.writeInt(note.strokes.size)
            for (stroke in note.strokes) {
                out.writeLong(stroke.id)
                out.writeFloat(stroke.width)
                out.writeInt(stroke.points.size)
                for (point in stroke.points) {
                    out.writeFloat(point.x); out.writeFloat(point.y)
                }
            }
        }
        val body = payload.toByteArray()
        require(body.size <= MAX_BYTES) { "Sheet is too large" }
        val bytes = ByteArrayOutputStream(body.size + 20)
        DataOutputStream(bytes).use {
            it.writeInt(MAGIC); it.writeInt(VERSION); it.writeInt(body.size)
            it.writeLong(CRC32().apply { update(body) }.value)
            it.write(body)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): Note {
        require(bytes.size in 20..MAX_BYTES + 20) { "Invalid sheet size" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown sheet format" }
            require(input.readInt() == VERSION) { "Unsupported sheet version" }
            val size = input.readInt()
            require(size == bytes.size - 20) { "Incomplete sheet" }
            val checksum = input.readLong()
            val body = ByteArray(size)
            input.readFully(body)
            require(CRC32().apply { update(body) }.value == checksum) { "Sheet checksum failed" }
            DataInputStream(ByteArrayInputStream(body)).use { data ->
                val id = data.readUTF()
                require(UUID.fromString(id).toString() == id) { "Invalid sheet identifier" }
                val title = data.readUTF()
                require(title.length in 1..120) { "Invalid title" }
                val updated = data.readLong()
                val width = bounded(data.readFloat(), 100f, 5000f)
                val spacing = bounded(data.readFloat(), 24f, 96f)
                val pen = bounded(data.readFloat(), 1f, 8f)
                val scroll = bounded(data.readFloat(), 0f, 10_000_000f)
                val count = data.readInt()
                require(count in 0..200_000) { "Too many strokes" }
                val strokes = ArrayList<Stroke>()
                val ids = HashSet<Long>()
                var total = 0
                repeat(count) {
                    val strokeId = data.readLong()
                    require(strokeId in 1 until Long.MAX_VALUE && ids.add(strokeId)) { "Invalid stroke identifier" }
                    val strokeWidth = bounded(data.readFloat(), 1f, 8f)
                    val length = data.readInt()
                    require(length in 1..MAX_POINTS - total) { "Too many points" }
                    require(length.toLong() * 8 <= data.available()) { "Incomplete stroke" }
                    total += length
                    val points = ArrayList<InkPoint>(length)
                    repeat(length) {
                        points.add(InkPoint(bounded(data.readFloat(), -5000f, 10000f),
                            bounded(data.readFloat(), 0f, 11_000_000f)))
                    }
                    strokes.add(Stroke(strokeId, strokeWidth, points))
                }
                require(data.available() == 0) { "Unexpected sheet data" }
                return Note(id, title, updated, width, spacing, pen, scroll, strokes)
            }
        }
    }

    private fun bounded(value: Float, low: Float, high: Float): Float {
        require(value.isFinite() && value in low..high) { "Invalid sheet geometry" }
        return value
    }
}
