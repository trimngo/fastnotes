package dev.fastnotes.ink

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/** One replaceable wakeup, independent of the UI and ENote input loop. */
internal interface NativeInkScheduler {
    fun now(): Long
    fun postAtTime(task: Runnable, time: Long)
    fun cancel(task: Runnable)
    fun close()
}

internal class HandlerNativeInkScheduler : NativeInkScheduler {
    private val thread = HandlerThread("viwoods-refresh").apply { start() }
    private val handler = Handler(thread.looper)
    override fun now() = SystemClock.uptimeMillis()
    override fun postAtTime(task: Runnable, time: Long) { handler.postAtTime(task, time) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
    override fun close() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
