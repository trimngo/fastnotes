package dev.fastnotes.diagnostics

import android.view.MotionEvent
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional, USB-only pen-coordinate relay to the Mac browser whiteboard.
 *
 * Android connects to 127.0.0.1:17172, a socket exposed via
 *   adb reverse tcp:17172 tcp:17172
 * It never connects to Wi-Fi or a public endpoint.
 *
 * The ENote callback calls submit() only; all socket and JSON work occurs on
 * this dedicated worker. Queue overflow drops incoming samples rather than
 * blocking the firmware's live ink callback.
 */
class UsbInkForwarder {
    private data class InkPacket(
        val action: String,
        val x: Float,
        val y: Float,
        val width: Int,
        val height: Int,
        val pressure: Int,
        val eraser: Boolean,
        val clear: Boolean = false
    ) {
        fun wire(): String {
            if (clear) return "{\"type\":\"reset\"}\n"
            return String.format(Locale.ROOT,
                "{\"type\":\"sample\",\"action\":\"%s\",\"x\":%.2f,\"y\":%.2f," +
                    "\"w\":%d,\"h\":%d,\"pressure\":%.4f,\"eraser\":%s}\n",
                action, x, y, width, height,
                (pressure / 4096f).coerceIn(0f, 1f),
                eraser.toString()
            )
        }
    }

    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<InkPacket>(8192)
    @Volatile var connected = false
        private set
    private val worker = Thread({ runLoop() }, "Fastnotes-USB-Ink").apply {
        isDaemon = true
        start()
    }

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
        if (!queue.offer(InkPacket(code, x, y, width, height, pressure, eraser))) {
            // Preserve the firmware callback's responsiveness under a stalled Mac.
            queue.poll()
            queue.offer(InkPacket(code, x, y, width, height, pressure, eraser))
        }
    }

    fun clearRemote() {
        queue.clear()
        queue.offer(InkPacket("", 0f, 0f, 0, 0, 0, false, clear = true))
    }

    fun close() {
        running.set(false)
        worker.interrupt()
        queue.clear()
    }

    private fun runLoop() {
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress("127.0.0.1", 17172), 700)
                    // Discard pen input collected before this viewer was available.
                    queue.clear()
                    connected = true
                    BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)).use { out ->
                        out.write("{\"type\":\"reset\"}\n")
                        out.flush()
                        while (running.get()) {
                            val packet = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                            out.write(packet.wire())
                            out.flush()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // Receiver unavailable or cable unplugged: retry without affecting ink.
            } catch (_: InterruptedException) {
                // close() deliberately interrupts the queue wait.
            } finally {
                connected = false
                queue.clear()
            }
            if (running.get()) {
                try { Thread.sleep(800) } catch (_: InterruptedException) { }
            }
        }
    }
}
