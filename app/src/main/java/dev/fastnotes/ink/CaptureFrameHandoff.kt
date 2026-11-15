package dev.fastnotes.ink

import android.os.Looper
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation for a hardware callback, or null when the owner uses software rendering. */
internal fun interface CaptureFrameCommitter {
    fun register(view: View, callback: Runnable): (() -> Unit)?
}

private object AndroidCaptureFrameCommitter : CaptureFrameCommitter {
    override fun register(view: View, callback: Runnable): (() -> Unit)? {
        if (!view.isHardwareAccelerated) return null
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return null
        observer.registerFrameCommitCallback(callback)
        return { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
    }
}

/**
 * One matching captured Android frame must be submitted before ending a native completion.
 * Submission is not a physical e-ink update. State/cancellation belong to the UI thread.
 */
internal class CaptureFrameHandoff(
    private val view: View,
    private val acknowledge: (NativeCompletionToken) -> Unit,
    private val committer: CaptureFrameCommitter = AndroidCaptureFrameCommitter
) {
    private data class Drawn(val request: Long, val token: NativeCompletionToken?)
    @Volatile private var drawn = Drawn(0, null)
    private var serial = 0L
    private var pending: Pending? = null

    private inner class Pending(val id: Long, val token: NativeCompletionToken) {
        var registration: Registration? = null
        var software = false
        var softwarePosted = false
        var needsRegistration = false
        val softwareCommit = Runnable {
            if (pending === this && drawn.request == id && drawn.token == token) finish(this)
        }
    }

    private inner class Registration(val request: Pending) {
        private val fired = AtomicBoolean()
        private var atCommit = Drawn(0, null)
        var cancellation: (() -> Unit)? = null
        val onUi = Runnable { committed(this, atCommit) }
        val callback = Runnable {
            // Snapshot at the actual render callback, not after a later UI draw.
            val sample = drawn
            if (fired.compareAndSet(false, true)) {
                atCommit = sample
                view.post(onUi)
            }
        }
    }

    fun request(token: NativeCompletionToken) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (pending?.token == token) return
        cancel()
        val request = Pending(++serial, token)
        pending = request
        register(request) // Before invalidation: onDraw registration can miss this frame.
        view.postInvalidateOnAnimation()
    }

    fun onDrawn(token: NativeCompletionToken?) {
        val request = pending
        drawn = Drawn(if (request != null && request.token == token && token != null) request.id else 0, token)
        if (request == null || request.token != token) return
        if (request.needsRegistration) {
            register(request)
            if (!request.software) view.postInvalidateOnAnimation()
        }
        if (request.software && !request.softwarePosted) {
            request.softwarePosted = true
            // Software ViewRoot submits its buffer only after drawing returns.
            view.post(request.softwareCommit)
        }
    }

    fun cancel() {
        val request = pending ?: return
        pending = null
        request.registration?.let {
            it.cancellation?.invoke()
            view.removeCallbacks(it.onUi)
        }
        view.removeCallbacks(request.softwareCommit)
    }

    private fun register(request: Pending) {
        if (pending !== request) return
        request.needsRegistration = false
        val registration = Registration(request)
        request.registration = registration
        registration.cancellation = committer.register(view, registration.callback)
        request.software = registration.cancellation == null
    }

    private fun committed(registration: Registration, sample: Drawn) {
        val request = registration.request
        if (pending !== request || request.registration !== registration) return
        registration.cancellation = null // One-shot callback has already fired.
        if (sample.request == request.id && sample.token == request.token) finish(request)
        else {
            request.registration = null
            request.needsRegistration = true
            if (view.isAttachedToWindow && view.isShown) {
                register(request)
                view.postInvalidateOnAnimation()
            }
        }
    }

    private fun finish(request: Pending) {
        if (pending !== request) return
        pending = null
        request.registration?.let {
            it.cancellation?.invoke()
            view.removeCallbacks(it.onUi)
        }
        view.removeCallbacks(request.softwareCommit)
        acknowledge(request.token)
    }
}
