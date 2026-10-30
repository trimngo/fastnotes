package dev.fastnotes.diagnostics

import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.util.AtomicFile
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class FirmwareEndpoint(val receiver: Any, val api: Class<*>, val route: String)

/** Scoped system-service experiment. No Java bitmap, input listener, initWriting or raw Binder codes. */
class FirmwarePreviewProbe internal constructor(
    private val context: Context,
    private val checkpoint: (String) -> Unit,
    private val connection: () -> FirmwareEndpoint
) {
    constructor(context: Context, className: String = "android.os.enote.ENoteSetting", checkpoint: (String) -> Unit = {}) :
        this(context, checkpoint, { connect(className, checkpoint) })
    private var receiver: Any? = null
    private var methods = emptyList<Method>()
    private var registered: Rect? = null
    private var previousMode: Int? = null
    private var previousTool: Int? = null
    private var enabledChange: Triple<String, String, Boolean>? = null
    var status = "Firmware preview has not been requested."
        private set
    val hasRegistration get() = registered != null

    fun start(rect: Rect): Boolean {
        if (!stop()) { status = "Previous firmware test cleanup could not be confirmed. Close this test."; return false }
        if (rect.isEmpty) { status = "The test area is not visible."; return false }
        return try {
            val endpoint = connection()
            receiver = endpoint.receiver
            // Invoke methods declared on the actual ROM interface, not a possibly non-public
            // Binder Proxy implementation. No guessed transaction IDs or bundled AIDL stubs.
            methods = endpoint.api.methods.toList()
            check(find("addAutoDrawRect", arrayOf(rect)) != null && find("removeAutoDrawRect", arrayOf(rect)) != null)
            checkpoint("Connected through ${endpoint.route}")
            if (find("setApplicationContext", arrayOf(context.applicationContext)) != null) {
                call("setApplicationContext", context.applicationContext)
            }
            val mode = read("getPictureMode") as? Int
            if (mode in setOf(0, 1, 2, 3, 4, 17) && find("setPictureMode", arrayOf(4)) != null) {
                previousMode = mode
                call("setPictureMode", 4)
            }
            // Do not blindly change a global AutoDraw flag. Only touch it if its old state can
            // be read and restored. Several ROMs expose only a setter; those are left alone.
            val enablePairs = listOf("getT1000AutoDrawEnabled" to "setT1000AutoDrawEnabled",
                "isT1000AutoDrawEnabled" to "setT1000AutoDrawEnabled",
                "getT1000AutoDrawEnable" to "setT1000AutoDrawEnable")
            var enableObserved: Boolean? = null
            for ((getter, setter) in enablePairs) {
                val old = read(getter) as? Boolean ?: continue
                enableObserved = old
                if (!old && find(setter, arrayOf(true)) != null) {
                    enabledChange = Triple(getter, setter, old)
                    call(setter, true)
                }
                break
            }
            checkpoint(if (enableObserved == null) "AutoDraw enable getter unavailable; global flag left unchanged"
                else "AutoDraw enable observed=$enableObserved; changed=${enabledChange != null}")
            val tool = read("getAutoDrawToolType") as? Int
            checkpoint("AutoDraw tool observed=$tool")
            if (tool != null && tool != 2 && find("setAutoDrawToolType", arrayOf(2)) != null) {
                previousTool = tool
                call("setAutoDrawToolType", 2)
            }
            // Save the exact rect before calling: even a failing IPC may have registered it.
            registered = Rect(rect)
            call("addAutoDrawRect", Rect(rect))
            status = "Region request accepted via ${endpoint.route}. Visible firmware ink is still unverified."
            checkpoint(status + " Rect=" + rect.toShortString())
            true
        } catch (failure: Throwable) {
            val type = failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName
            val released = stop()
            status = "Firmware preview request failed ($type)." + if (released) "" else " Cleanup could not be confirmed."
            checkpoint(status)
            false
        }
    }

    fun stop(): Boolean {
        var released = true
        val hadRegistration = registered != null
        registered?.let { rect ->
            if (runCatching { call("removeAutoDrawRect", Rect(rect)) }.isSuccess) registered = null
            else { released = false; checkpoint("Region release failed") }
        }
        previousTool?.let { old ->
            when (read("getAutoDrawToolType")) {
                null -> released = false
                2 -> if (runCatching { call("setAutoDrawToolType", old) }.isSuccess) previousTool = null else released = false
                else -> previousTool = null
            }
        }
        enabledChange?.let { (getter, setter, old) ->
            when (read(getter)) {
                null -> released = false
                true -> if (runCatching { call(setter, old) }.isSuccess) enabledChange = null else released = false
                else -> enabledChange = null
            }
        }
        previousMode?.let { old ->
            when (read("getPictureMode")) {
                null -> released = false
                4 -> if (runCatching { call("setPictureMode", old) }.isSuccess) previousMode = null else released = false
                else -> previousMode = null
            }
        }
        if (released) { receiver = null; methods = emptyList() }
        if (!released) status = "Firmware test cleanup could not be confirmed."
        else if (hadRegistration) status = "Firmware region removed; visible ink was not evaluated automatically."
        return released
    }

    private fun read(name: String): Any? = runCatching {
        if (receiver == null) null else find(name, emptyArray())?.invoke(receiver)
    }.getOrNull()

    private fun call(name: String, vararg args: Any?) {
        val method = find(name, args) ?: throw NoSuchMethodException(name)
        checkpoint("Calling $name")
        check(method.invoke(receiver, *args) != false)
        checkpoint("Returned from $name")
    }

    private fun find(name: String, args: Array<out Any?>): Method? = methods.firstOrNull { method ->
        method.name == name && method.parameterTypes.size == args.size &&
            method.parameterTypes.indices.all { index ->
                val value = args[index]
                when (val type = method.parameterTypes[index]) {
                    Integer.TYPE -> value is Int
                    java.lang.Boolean.TYPE -> value is Boolean
                    else -> value != null && type.isInstance(value)
                }
            }
    }

    companion object {
        private fun hasRegionApi(type: Class<*>) = listOf("addAutoDrawRect", "removeAutoDrawRect").all { name ->
            type.methods.any { it.name == name && it.parameterTypes.contentEquals(arrayOf(Rect::class.java)) }
        }

        private fun connect(className: String, checkpoint: (String) -> Unit): FirmwareEndpoint {
            val loader = FirmwarePreviewProbe::class.java.classLoader
            if (className == "android.os.enote.ENoteSetting") {
                try {
                    val apiName = "android.os.enote.IENoteSetting"
                    val api = Class.forName(apiName, false, loader)
                    check(hasRegionApi(api))
                    val manager = Class.forName("android.os.ServiceManager", false, loader)
                    val stub = Class.forName(apiName + "\$Stub", false, loader)
                    checkpoint("Calling system service lookup")
                    val binder = manager.getMethod("getService", String::class.java).invoke(null, "ENoteSetting") as? IBinder
                        ?: error("Service unavailable")
                    checkpoint("Returned from system service lookup")
                    checkpoint("Calling ROM Stub.asInterface")
                    val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                        ?: error("Interface unavailable")
                    checkpoint("Returned from ROM Stub.asInterface")
                    return FirmwareEndpoint(service, api, "ROM system service")
                } catch (failure: Throwable) {
                    checkpoint("Typed service unavailable (${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}); checking wrapper")
                }
            }
            val type = Class.forName(className, false, loader)
            check(hasRegionApi(type))
            checkpoint("Calling getInstance")
            val instance = type.getMethod("getInstance").invoke(null) ?: error("No firmware instance")
            checkpoint("Returned from getInstance")
            return FirmwareEndpoint(instance, type, "framework wrapper")
        }

        /** Class inspection deliberately does not initialize ENote or invoke vendor methods. */
        fun capabilities(classNames: List<String> = listOf("android.os.enote.ENoteSetting", "android.os.enote.ENoteWriting",
            "android.os.enote.IENoteSetting", "android.os.enote.IENoteSetting\$Stub")): String =
            classNames.joinToString("\n\n") { name ->
                runCatching {
                    val type = Class.forName(name, false, FirmwarePreviewProbe::class.java.classLoader)
                    val relevant = type.declaredMethods.filter { method ->
                        listOf("writing", "draw", "mipi", "input", "region", "render", "pen", "mode", "context", "refresh", "surface", "instance")
                            .any { method.name.contains(it, ignoreCase = true) }
                    }.sortedBy { it.name + it.parameterTypes.joinToString { p -> p.name } }
                    name + " (inspection only)\n" + relevant.take(180).joinToString("\n") { method ->
                        Modifier.toString(method.modifiers) + " " + method.returnType.simpleName + " " + method.name +
                            method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
                    }
                }.getOrElse { name + ": unavailable (" + it.javaClass.simpleName + ")" }
            }.take(40 * 1024)
    }
}

/** Atomic stage evidence survives a native crash; test point coordinates are never written. */
class InkProbeLog(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "ink-test-status.txt"))
    private val entries = ArrayDeque<String>()
    fun event(text: String) {
        entries.addLast(text.take(400))
        while (entries.size > 60) entries.removeFirst()
        runCatching {
            val output = file.startWrite()
            try {
                output.write(entries.joinToString("\n").toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (failure: Throwable) { file.failWrite(output); throw failure }
        }
    }
    companion object {
        fun read(context: Context): String = runCatching {
            val file = File(context.filesDir, "ink-test-status.txt")
            if (file.isFile && file.length() <= 32 * 1024) file.readText() else "No firmware test recorded."
        }.getOrDefault("Firmware test report could not be read.")
    }
}
