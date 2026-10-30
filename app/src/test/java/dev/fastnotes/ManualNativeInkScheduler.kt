package dev.fastnotes

import dev.fastnotes.ink.NativeInkScheduler

/** Controllable monotonic time without sleeps or Android/UI Looper dependencies. */
internal class ManualNativeInkScheduler : NativeInkScheduler {
    private val tasks = LinkedHashMap<Runnable, Long>()
    private var time = 0L
    private var closed = false
    val pendingCount get() = synchronized(tasks) { tasks.size }
    override fun now() = synchronized(tasks) { time }
    override fun postAtTime(task: Runnable, time: Long) = synchronized(tasks) {
        if (!closed) tasks[task] = time
        Unit
    }
    override fun cancel(task: Runnable) { synchronized(tasks) { tasks.remove(task) } }
    override fun close() { synchronized(tasks) { closed = true; tasks.clear() } }
    fun runCurrent() = advanceBy(0)
    fun advanceBy(millis: Long) {
        val target = now() + millis
        while (true) {
            val task = synchronized(tasks) {
                val next = tasks.minByOrNull { it.value }
                if (next == null || next.value > target) { time = target; null }
                else { time = maxOf(time, next.value); tasks.remove(next.key); next.key }
            } ?: return
            task.run()
        }
    }
    fun runUntilIdle() {
        repeat(100) {
            val next = synchronized(tasks) { tasks.values.minOrNull() } ?: return
            advanceBy(maxOf(0, next - now()))
        }
        error("Refresh queue did not settle")
    }
}
