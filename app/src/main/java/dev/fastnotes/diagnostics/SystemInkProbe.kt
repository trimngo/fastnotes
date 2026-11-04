package dev.fastnotes.diagnostics

import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.Parcel

internal data class SystemInkDevice(val model: String, val build: String, val api: Int)

/**
 * Scoped IPC contract verified from this device's 3.15.15 framework and service.
 * This never initializes app-side ENoteWriting or changes global drawing settings.
 */
class SystemInkProbe internal constructor(
    private val checkpoint: (String) -> Unit,
    private val device: () -> SystemInkDevice,
    private val connect: () -> IBinder?
) {
    constructor(checkpoint: (String) -> Unit = {}) : this(checkpoint,
        { SystemInkDevice(Build.MODEL, Build.DISPLAY, Build.VERSION.SDK_INT) },
        {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "ENoteSetting") as? IBinder
        })

    private var binder: IBinder? = null
    private var registered: Rect? = null
    private var stage = "Not started"
    val hasRegistration get() = registered != null
    var status = "System ink has not been requested."
        private set

    fun start(rect: Rect): Boolean {
        if (!stop()) {
            status = "Previous system ink cleanup is unconfirmed. Close this test."
            return false
        }
        if (rect.isEmpty || rect.left < 0 || rect.top < 0) {
            status = "The writing area is not visible."
            return false
        }
        val current = device()
        if (current != SUPPORTED_DEVICE) {
            status = "System ink protocol is verified only for Viwoods AiPaper, Android 13/API 33, build 3.15.15."
            record("System ink blocked: device/build does not match inspected protocol")
            return false
        }
        return try {
            record("Looking up ENoteSetting system service")
            val service = connect() ?: error("System service unavailable")
            check(service.isBinderAlive)
            record("Checking system service interface descriptor")
            check(service.interfaceDescriptor == DESCRIPTOR)
            binder = service
            // Retain ownership before IPC: the service can act before its reply fails.
            registered = Rect(rect)
            request(ADD_RECT, Rect(rect), "addAutoDrawRect")
            status = "System writing area accepted; visible ink is unverified."
            record(status)
            true
        } catch (failure: Throwable) {
            val failedAt = stage
            val type = failureType(failure)
            val cleaned = stop()
            status = "System ink failed at $failedAt ($type)." +
                if (cleaned) "" else " Cleanup is unconfirmed; close this test."
            record(status)
            false
        }
    }

    fun stop(): Boolean {
        val rect = registered
        if (rect == null) {
            binder = null
            return true
        }
        return try {
            request(REMOVE_RECT, Rect(rect), "removeAutoDrawRect")
            registered = null
            binder = null
            status = "System writing area removal accepted; native cleanup is asynchronous."
            record(status)
            true
        } catch (failure: Throwable) {
            status = "System ink cleanup failed (${failureType(failure)}); registration retained for retry."
            record(status)
            false
        }
    }

    private fun request(code: Int, rect: Rect, name: String) {
        val service = binder ?: error("No system service")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeTypedObject(rect, 0)
            record("Calling system $name ${rect.toShortString()}")
            check(service.transact(code, data, reply, 0))
            reply.readException()
            record("Returned from system $name")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun record(value: String) {
        stage = value.take(400)
        runCatching { checkpoint(stage) }
    }

    private fun failureType(failure: Throwable): String = generateSequence(failure) { it.cause }
        .take(5).last().javaClass.simpleName.take(80)

    companion object {
        internal const val DESCRIPTOR = "android.os.enote.IENoteSetting"
        // Exact transaction constants and Rect parcel schema inspected on 3.15.15.
        private const val ADD_RECT = 0x18
        private const val REMOVE_RECT = 0x19
        private val SUPPORTED_DEVICE = SystemInkDevice("Viwoods AiPaper", "3.15.15", 33)
    }
}
