package dev.fastnotes.diagnostics

import android.os.SystemClock
import android.view.MotionEvent
import dev.fastnotes.model.Note
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Localhost-only USB ink relay. ADB reverse routes this to the Mac over USB-C.
 * The writer and heartbeat reader never block firmware's direct ink callback.
 * Documents live in NoteStore, not in this volatile socket or queue.
 */
class UsbInkForwarder {
    private data class Packet(
        val action: String, val x: Float, val y: Float,
        val width: Int, val height: Int, val pressure: Int, val eraser: Boolean
    ) {
        fun wire(): String = String.format(Locale.ROOT,
            "{\"type\":\"sample\",\"action\":\"%s\",\"x\":%.2f,\"y\":%.2f," +
                "\"w\":%d,\"h\":%d,\"pressure\":%.4f,\"eraser\":%s}\n",
            action, x, y, width, height, (pressure / 4096f).coerceIn(0f, 1f), eraser.toString())
    }

    private data class Document(val revision: Long, val note: Note, val width: Int, val height: Int) {
        fun wire(): String {
            // The immutable Note is captured on the UI thread, serialized only on the worker.
            val out = StringBuilder(1024 + note.strokes.size * 120)
            out.append("{\"type\":\"snapshot\",\"docId\":")
                .append(JSONObject.quote(note.id))
                .append(",\"title\":").append(JSONObject.quote(note.title))
                .append(",\"pageWidth\":").append(note.pageWidth)
                .append(",\"scrollY\":").append(note.scrollY)
                .append(",\"viewWidth\":").append(width)
                .append(",\"viewHeight\":").append(height)
                .append(",\"strokes\":[")
            note.strokes.forEachIndexed { index, stroke ->
                if (index != 0) out.append(',')
                out.append("{\"id\":").append(stroke.id)
                    .append(",\"width\":").append(stroke.width).append(",\"points\":[")
                stroke.points.forEachIndexed { i, p ->
                    if (i != 0) out.append(',')
                    out.append('[').append(p.x).append(',').append(p.y).append(']')
                }
                out.append("]}")
            }
            out.append("]}\n")
            return out.toString()
        }
    }

    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<Packet>(8192)
    private val documentRevision = AtomicLong(0)
    @Volatile private var latestDocument: Document? = null
    @Volatile private var socket: Socket? = null
    @Volatile var connected = false
        private set

    private val worker = Thread({ runLoop() }, "Fastnotes-USB-Ink").apply {
        isDaemon = true
        start()
    }

    /** Called on UI thread at pen-up, erase, undo, sheet switch, and scroll. */
    fun syncDocument(note: Note, width: Int, height: Int) {
        if (!running.get() || width <= 0 || height <= 0) return
        latestDocument = Document(documentRevision.incrementAndGet(), note, width, height)
    }

    /** No IO, JSON building, locks, waits or allocations proportional to note size. */
    fun submit(action: Int, x: Float, y: Float, width: Int, height: Int, pressure: Int, eraser: Boolean) {
        if (!running.get() || !x.isFinite() || !y.isFinite() || width <= 0 || height <= 0) return
        val code = when (action) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> "hover"
            MotionEvent.ACTION_HOVER_EXIT -> "leave"
            else -> return
        }
        val packet = Packet(code, x, y, width, height, pressure, eraser)
        if (!queue.offer(packet)) {
            queue.poll()
            queue.offer(packet)
        }
    }

    fun close() {
        running.set(false)
        socket?.close()
        worker.interrupt()
        queue.clear()
    }

    private fun runLoop() {
        while (running.get()) {
            try {
                Socket().use { link ->
                    link.tcpNoDelay = true
                    link.connect(InetSocketAddress("127.0.0.1", 17172), 700)
                    socket = link
                    queue.clear() // No stale in-flight pen movements after unplugging.
                    val lastPong = AtomicLong(SystemClock.uptimeMillis())
                    link.soTimeout = 1500
                    val reader = Thread({
                        try {
                            val input = BufferedReader(InputStreamReader(link.getInputStream(), StandardCharsets.UTF_8))
                            while (running.get() && !link.isClosed) {
                                val line = input.readLine() ?: break
                                if (line.contains("\"pong\"")) lastPong.set(SystemClock.uptimeMillis())
                            }
                        } catch (_: java.io.IOException) {
                            // USB connection lost, or heartbeat read timed out.
                        } finally {
                            runCatching { link.close() }
                        }
                    }, "Fastnotes-USB-Ack").apply { isDaemon = true; start() }
                    connected = true
                    BufferedWriter(OutputStreamWriter(link.getOutputStream(), StandardCharsets.UTF_8)).use { out ->
                        var sentRevision = -1L
                        var lastPing = 0L
                        while (running.get() && !link.isClosed) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastPong.get() > 6000) throw java.io.IOException("USB heartbeat expired")
                            if (now - lastPing >= 1200) {
                                out.write("{\"type\":\"ping\"}\n")
                                out.flush()
                                lastPing = now
                            }
                            // Drain live pen events before broadcasting the authoritative
                            // document, so a final UP cannot reappear over a new snapshot.
                            val packet = queue.poll(45, TimeUnit.MILLISECONDS)
                            if (packet != null) {
                                out.write(packet.wire())
                                out.flush()
                            } else {
                                val doc = latestDocument
                                if (doc != null && doc.revision != sentRevision) {
                                    out.write(doc.wire())
                                    out.flush()
                                    sentRevision = doc.revision
                                }
                            }
                        }
                    }
                    reader.interrupt()
                }
            } catch (_: java.io.IOException) {
                // ADB reverse not yet restored, server restart, or cable unplugged.
            } catch (_: InterruptedException) {
                // close() interrupts the queue wait.
            } finally {
                connected = false
                socket = null
                queue.clear()
            }
            if (running.get()) {
                try { Thread.sleep(700) } catch (_: InterruptedException) { }
            }
        }
    }
}
