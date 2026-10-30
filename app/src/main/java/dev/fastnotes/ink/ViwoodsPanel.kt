package dev.fastnotes.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.lang.reflect.Method

/**
 * Independent, best-effort adapter for the API documented by the Viwoods community.
 * No vendor binaries, raw input interception, shell commands, global waveform changes,
 * auto-draw registrations or device configuration writes. Only our bitmap and clipped rects.
 * Disabled by default, and never started in multi-window or under a dialog.
 */
class ViwoodsPanel(private val className: String = "android.os.enote.ENoteSetting") {
    private var instance: Any? = null
    private var methods: Map<String, Method> = emptyMap()
    private var bounds = Rect()
    var active = false
        private set
    var status = "Not enabled"
        private set

    fun available(): Boolean = try {
        val cls = Class.forName(className, false, javaClass.classLoader)
        REQUIRED.all { name -> cls.methods.any { it.name == name } }
    } catch (_: Throwable) { false }

    fun start(context: Context, bitmap: Bitmap, originX: Int, originY: Int, orientation: Int): Boolean {
        stop()
        return try {
            val cls = Class.forName(className)
            methods = cls.methods.filter { it.name in REQUIRED || it.name == "setApplicationContext" }
                .associateBy { it.name }
            check(REQUIRED.all { it in methods })
            instance = cls.getMethod("getInstance").invoke(null)
            bounds = Rect(originX, originY, originX + bitmap.width, originY + bitmap.height)
            methods["setApplicationContext"]?.invoke(instance, context.applicationContext)
            call("setWritingJavaBitmap", bitmap, orientation, originX, originY)
            call("setWritingJavaBackgroundBitmap", bitmap, orientation, originX, originY)
            call("setWritingEnabled", true)
            active = true
            status = "Viwoods panel (experimental)"
            true
        } catch (_: Throwable) {
            stop()
            status = "Panel API unavailable; using Android ink"
            false
        }
    }

    fun render(local: Rect) {
        if (!active) return
        val rect = Rect(local)
        rect.offset(bounds.left, bounds.top)
        if (!rect.intersect(bounds)) return
        try {
            call("renderWriting", rect)
        } catch (_: Throwable) {
            stop()
            status = "Panel API stopped; using Android ink"
        }
    }

    fun stop() {
        // Release even after partial startup. Retain the bitmap until these calls finish.
        for ((name, args) in listOf(
            "setWritingEnabled" to arrayOf<Any>(false),
            "onWritingEnd" to emptyArray(),
            "releaseWritingJavaBitmap" to emptyArray(),
            "releaseWritingJavaBackgroundBitmap" to emptyArray(),
        )) {
            try { if (instance != null) methods[name]?.invoke(instance, *args) } catch (_: Throwable) { }
        }
        active = false
        instance = null
        methods = emptyMap()
        bounds.setEmpty()
    }

    private fun call(name: String, vararg args: Any) {
        check(methods.getValue(name).invoke(instance, *args) != false)
    }

    companion object {
        private val REQUIRED = setOf("setWritingJavaBitmap", "setWritingJavaBackgroundBitmap",
            "setWritingEnabled", "renderWriting", "onWritingEnd",
            "releaseWritingJavaBitmap", "releaseWritingJavaBackgroundBitmap")
    }
}
