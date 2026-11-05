package dev.fastnotes.ink

/** Read the same immutable selector used by this firmware's native library. */
internal object EinkBackend {
    fun readType(): String? = runCatching {
        (Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "ro.eink.type") as? String)
            ?.takeIf { it.length in 1..80 && it.matches(Regex("[A-Za-z0-9_.-]+")) }
    }.getOrNull()
}
