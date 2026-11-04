package dev.fastnotes.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Read standard DEX class definitions, not strings that merely reference the vendor API. */
internal fun enoteDefinitions(bytes: ByteArray): List<String> = runCatching {
    require(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)))
    fun uint(offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 4)
        val value = (0..3).fold(0L) { n, i -> n or ((bytes[offset + i].toLong() and 255) shl (i * 8)) }
        require(value <= Int.MAX_VALUE)
        return value.toInt()
    }
    require(uint(32) == bytes.size && uint(36) >= 112 && uint(40) == 0x12345678)
    val strings = uint(56); val stringBase = uint(60)
    val types = uint(64); val typeBase = uint(68)
    val classes = uint(96); val classBase = uint(100)
    require(types <= 65_536 && classes <= 65_536)
    fun table(base: Int, count: Int, size: Int) {
        require(base >= 0 && count.toLong() * size + base <= bytes.size)
    }
    table(stringBase, strings, 4); table(typeBase, types, 4); table(classBase, classes, 32)
    val names = ArrayList<String>()
    for (index in 0 until classes) {
        val type = uint(classBase + index * 32); require(type < types)
        val string = uint(typeBase + type * 4); require(string < strings)
        var start = uint(stringBase + string * 4)
        var prefix = 0
        do { require(start < bytes.size && prefix++ < 5) } while (bytes[start++].toInt() and 128 != 0)
        var end = start
        while (end < bytes.size && end - start < 256 && bytes[end] != 0.toByte()) end++
        if (end - start >= 256) continue
        require(end < bytes.size)
        val name = String(bytes, start, end - start, Charsets.UTF_8)
        val simple = name.substringAfterLast('/').substringBefore('$').removeSuffix(";")
        val relevant = name.contains("/enote/", true) || name.contains("/autodraw/", true) ||
            simple in setOf("ENoteSettingService", "FocusMonitorService", "T1000AutoDrawPolicy")
        if (relevant && name.endsWith(';')) names.add(name)
        if (names.size >= 256) break
    }
    names.distinct().sorted()
}.getOrDefault(emptyList())

data class FirmwareEvidenceSummary(val codeFiles: Int, val bytes: Long, val inventoryOnly: Boolean)

/** Only explicit system roots can supply bytes; no app, note, clipboard or shared-storage scan. */
class FirmwareEvidence internal constructor(
    private val roots: List<File>,
    private val candidates: () -> List<File>,
    private val maxEntryBytes: Int = 20 * 1024 * 1024,
    private val maxCodeBytes: Int = 20 * 1024 * 1024,
    private val maxScanBytes: Long = 64L * 1024 * 1024
) {
    constructor() : this(systemRoots(), ::systemCandidates)

    fun write(destination: File, device: JSONObject, report: String): FirmwareEvidenceSummary {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        val artifacts = JSONArray()
        val inventory = JSONArray()
        var scanned = 0L
        var exported = 0
        var count = 0
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                fun add(name: String, data: ByteArray) {
                    try { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                    catch (failure: java.io.IOException) { throw EvidenceWriteFailure(failure) }
                }
                fun include(source: String, label: String, kind: String, data: ByteArray, classes: List<String> = emptyList()) {
                    if (data.size > maxCodeBytes - exported) {
                        inventory.put(JSONObject().put("source", source).put("status", "export budget reached")); return
                    }
                    val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
                    val name = "api/${++count}-$safeLabel"
                    add(name, data)
                    exported += data.size
                    artifacts.put(JSONObject().put("entry", name).put("source", source).put("kind", kind)
                        .put("bytes", data.size).put("sha256", sha256(data)).put("classes", JSONArray(classes)))
                }
                val allowedRoots = roots.map { it.canonicalFile }
                val files = candidates().mapNotNull { candidate ->
                    val file = runCatching { candidate.canonicalFile }.getOrNull() ?: return@mapNotNull null
                    file.takeIf { f -> allowedRoots.any { root -> f.path.startsWith(root.path + File.separator) } }
                }.distinctBy { it.path }.take(64)
                // DEX before optional native/preoptimized code: preserve the most useful API bodies.
                for (file in files.filter { it.extension == "jar" }) {
                    val item = JSONObject().put("source", file.path).put("bytes", file.length())
                        .put("present", file.isFile).put("readable", file.canRead())
                    inventory.put(item)
                    if (!file.isFile || !file.canRead()) { item.put("status", "missing or unreadable"); continue }
                    if (file.length() > maxScanBytes) { item.put("status", "archive too large"); continue }
                    try {
                        var defined = 0
                        var dexEntries = 0
                        ZipFile(file).use { archive ->
                            val entries = archive.entries()
                            var visited = 0
                            while (entries.hasMoreElements() && visited++ < 4096) {
                                val entry = entries.nextElement()
                                if (!entry.name.matches(Regex("classes[0-9]*\\.dex"))) continue
                                dexEntries++
                                if (entry.size !in 112L..maxEntryBytes.toLong() || scanned + entry.size > maxScanBytes) {
                                    item.put("skippedOversizeOrBudget", true); continue
                                }
                                val data = archive.getInputStream(entry).use { readBounded(it, maxEntryBytes) }
                                scanned += data.size
                                val classes = enoteDefinitions(data)
                                if (classes.isNotEmpty()) {
                                    defined += classes.size
                                    include(file.path + "!/" + entry.name, file.nameWithoutExtension + "-" + entry.name, "dex", data, classes)
                                }
                            }
                        }
                        item.put("status", "inspected").put("dexEntries", dexEntries).put("enoteDefinitions", defined)
                    } catch (failure: Exception) {
                        if (failure is EvidenceWriteFailure) throw failure
                        item.put("status", "read failed: " + failure.javaClass.simpleName)
                    }
                }
                val haveDex = artifacts.length() > 0
                for (file in files.filter { it.extension == "so" || (!haveDex && it.extension == "vdex") }) {
                    val item = JSONObject().put("source", file.path).put("bytes", file.length())
                        .put("present", file.isFile).put("readable", file.canRead())
                    inventory.put(item)
                    if (!file.isFile || !file.canRead()) { item.put("status", "missing or unreadable"); continue }
                    if (file.length() !in 4L..maxEntryBytes.toLong() || scanned + file.length() > maxScanBytes ||
                        file.length() > maxCodeBytes - exported) { item.put("status", "size or budget limit"); continue }
                    try {
                        val data = file.inputStream().use { readBounded(it, maxEntryBytes) }
                        scanned += data.size
                        val valid = if (file.extension == "so") data.take(4) == listOf<Byte>(127, 69, 76, 70)
                            else data.take(4) == "vdex".toByteArray().toList()
                        if (valid) {
                            include(file.path, file.name, if (file.extension == "so") "native-library" else "vdex", data)
                            item.put("status", "included")
                        } else item.put("status", "unrecognized header")
                    } catch (failure: Exception) {
                        if (failure is EvidenceWriteFailure) throw failure
                        item.put("status", "read failed: " + failure.javaClass.simpleName)
                    }
                }
                add("ink-report.txt", report.take(64 * 1024).toByteArray(Charsets.UTF_8))
                val manifest = JSONObject().put("schema", 1).put("device", device).put("artifacts", artifacts)
                    .put("inventory", inventory).put("codeFiles", count).put("codeBytes", exported)
                    .put("note", "System API implementation evidence. No note files or scratch coordinates are collected. No automatic upload.")
                add("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
            }
            check(partial.length() <= 24L * 1024 * 1024) { "Evidence archive exceeds upload size" }
            check(partial.renameTo(destination)) { "Evidence archive could not be finalized" }
            return FirmwareEvidenceSummary(count, destination.length(), count == 0)
        } catch (failure: Throwable) { partial.delete(); throw failure }
    }

    companion object {
        private fun systemRoots() = listOf("/system/framework", "/system_ext/framework", "/vendor/framework", "/product/framework",
            "/system/lib64", "/system/lib", "/system_ext/lib64", "/system_ext/lib",
            "/vendor/lib64", "/vendor/lib", "/product/lib64", "/product/lib").map(::File)
        private fun systemCandidates(): List<File> {
            val roots = systemRoots()
            val boot = (System.getProperty("java.boot.class.path") ?: "").split(File.pathSeparator)
                .filter { it.endsWith(".jar") }.map(::File)
            val frameworks = roots.filter { it.name == "framework" }.flatMap { root ->
                val known = listOf("framework.jar", "ext.jar", "enote.jar", "ENote.jar", "services.jar").map { File(root, it) }
                val named = root.listFiles()?.take(256)?.filter { it.extension == "jar" &&
                    (it.name.contains("enote", true) || it.name.contains("eink", true)) }.orEmpty()
                known + named + listOf("arm64/boot-framework.vdex", "arm/boot-framework.vdex").map { File(root, it) }
            }
            val libraries = roots.filter { it.name.startsWith("lib") }.flatMap { root ->
                listOf("libpaintworker.so", "libenote.so", "libenotewriting.so").map { File(root, it) }
            }
            return frameworks + boot + libraries
        }
        private fun readBounded(input: InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                check(read <= max - out.size()) { "Entry exceeds limit" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}

private class EvidenceWriteFailure(cause: java.io.IOException) : java.io.IOException(cause)
