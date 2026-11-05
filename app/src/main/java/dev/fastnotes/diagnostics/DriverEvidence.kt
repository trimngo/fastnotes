package dev.fastnotes.diagnostics

import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Driver identity and readable module candidates, never a loaded/executed kernel module. */
data class DriverSnapshot(val data: JSONObject, val modules: List<File>)

class DriverEvidence internal constructor(
    private val moduleRoots: List<File>,
    private val sysRoot: File,
    private val kernelVersion: File,
    private val deviceNumbers: () -> Pair<Long, Long>?,
    private val maxMetadataBytes: Int = 2 * 1024 * 1024,
    private val maxNodes: Int = 2048
) {
    constructor() : this(systemModuleRoots(), File("/sys"), File("/proc/version"), {
        val stat = Os.stat("/dev/t1000_spi")
        if (!OsConstants.S_ISCHR(stat.st_mode)) null else {
            // Linux dev_t layout; no device open or ioctl is needed to identify sysfs.
            val dev = stat.st_rdev
            val major = ((dev ushr 8) and 0xfff) or ((dev ushr 32) and 0xfffff000L)
            val minor = (dev and 0xff) or ((dev ushr 12) and 0xffffff00L)
            major to minor
        }
    })

    fun capture(): DriverSnapshot {
        val errors = JSONArray()
        val moduleInfo = JSONArray()
        val moduleNames = linkedSetOf<String>()
        val candidates = linkedMapOf<String, File>()
        val indexMatches = JSONArray()
        var bytes = 0
        var visited = 0
        var lines = 0
        var truncated = false
        fun error(path: File, type: String) {
            if (errors.length() < 32) errors.put(JSONObject().put("path", path.path).put("type", type.take(80)))
        }
        fun inside(file: File, root: File): File? = runCatching {
            val canonical = file.canonicalFile
            canonical.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }
        }.getOrNull()
        fun read(file: File, root: File?, limit: Int): String? {
            val safe = if (root == null) file else inside(file, root)
            if (safe == null) { error(file, "outside permitted root"); return null }
            if (bytes >= maxMetadataBytes) { truncated = true; return null }
            return try {
                val remaining = minOf(limit, maxMetadataBytes - bytes)
                safe.inputStream().use { input ->
                    val buffer = ByteArray(remaining)
                    var count = 0
                    while (count < buffer.size) {
                        val n = input.read(buffer, count, buffer.size - count)
                        if (n < 0) break
                        check(n > 0)
                        bytes += n; count += n
                    }
                    if (count == buffer.size) truncated = true
                    String(buffer, 0, count, Charsets.UTF_8)
                }
            } catch (failure: Exception) { error(file, failure.javaClass.simpleName); null }
        }
        fun uevent(file: File): JSONObject {
            val result = JSONObject()
            read(file, sysRoot, 4096)?.lineSequence()?.take(64)?.forEach { line ->
                val key = line.substringBefore('=')
                if (key in UEVENT_KEYS && '=' in line) result.put(key, line.substringAfter('=').take(256))
            }
            return result
        }
        val data = JSONObject().put("schema", 1).put("model", Build.MODEL).put("board", Build.BOARD)
            .put("hardware", Build.HARDWARE).put("kernelVersion", read(kernelVersion, null, 4096) ?: JSONObject.NULL)
        val device = JSONObject().put("node", "/dev/t1000_spi")
        data.put("device", device)
        val numbers = try { deviceNumbers() } catch (failure: Exception) {
            error(File("/dev/t1000_spi"), failure.javaClass.simpleName); null
        }
        if (numbers != null) {
            device.put("major", numbers.first).put("minor", numbers.second)
            val charPath = File(sysRoot, "dev/char/${numbers.first}:${numbers.second}")
            val resolved = inside(charPath, sysRoot)
            if (resolved != null && resolved.isDirectory) {
                device.put("sysfsPath", resolved.path).put("uevent", uevent(File(resolved, "uevent")))
                val devicePath = inside(File(resolved, "device"), sysRoot)
                if (devicePath != null && devicePath.isDirectory) {
                    device.put("deviceUevent", uevent(File(devicePath, "uevent")))
                    read(File(devicePath, "modalias"), sysRoot, 4096)?.let { device.put("modalias", it.trim().take(256)) }
                }
                val driver = listOf(File(resolved, "driver"), File(resolved, "device/driver"))
                    .mapNotNull { inside(it, sysRoot) }.firstOrNull { it.isDirectory }
                if (driver != null) {
                    device.put("driverPath", driver.path).put("driverName", driver.name)
                    val module = inside(File(driver, "module"), sysRoot)
                    if (module != null && module.isDirectory && validName(module.name)) {
                        moduleNames.add(module.name)
                        device.put("moduleName", module.name)
                    }
                }
            } else device.put("sysfsStatus", "unavailable or outside permitted root")
        } else device.put("status", "device identity unavailable")
        val sysModules = File(sysRoot, "module")
        sysModules.listFiles()?.take(256)?.filter { it.name.contains("t1000", true) && validName(it.name) }
            ?.forEach { if (inside(it, sysRoot)?.isDirectory == true) moduleNames.add(it.name) }
        moduleNames.take(8).forEach { name ->
            val base = File(sysModules, name)
            val item = JSONObject().put("name", name)
            for (field in listOf("version", "srcversion", "initstate")) {
                read(File(base, field), sysRoot, 4096)?.let { item.put(field, it.trim().take(256)) }
            }
            moduleInfo.put(item)
        }
        fun matches(name: String): Boolean {
            val stem = name.substringBefore(".ko").replace('-', '_')
            return name.contains("t1000", true) || moduleNames.any { it.replace('-', '_') == stem }
        }
        val roots = moduleRoots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinctBy { it.path }
        fun candidate(file: File, root: File) {
            if (!MODULE_FILE.matches(file.name) || !matches(file.name)) return
            val safe = inside(file, root) ?: return
            if (candidates.size < 32) candidates.putIfAbsent(safe.path, safe) else truncated = true
        }
        roots.forEach { root ->
            for (index in listOf("modules.dep", "modules.builtin", "modules.load")) {
                val text = read(File(root, index), root, maxMetadataBytes) ?: continue
                for (raw in text.lineSequence()) {
                    if (lines++ >= 20_000) { truncated = true; break }
                    val relative = raw.substringBefore(':').trim()
                    if (relative.length > 1024 || relative.isEmpty() || relative.startsWith('/') ||
                        relative.split('/').any { it == ".." }) continue
                    if (!matches(relative.substringAfterLast('/'))) continue
                    if (indexMatches.length() < 32) indexMatches.put(JSONObject().put("root", root.path)
                        .put("index", index).put("entry", relative))
                    candidate(File(root, if (index == "modules.load" && !relative.contains(".ko")) "$relative.ko" else relative), root)
                }
            }
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            val seen = HashSet<String>()
            while (queue.isNotEmpty() && visited < maxNodes) {
                val (folder, depth) = queue.removeFirst()
                if (!seen.add(folder.canonicalPath)) continue
                val children = folder.listFiles()?.take(maxNodes - visited).orEmpty()
                for (child in children) {
                    visited++
                    val safe = inside(child, root) ?: continue
                    if (safe.isDirectory && depth < 4) queue.add(safe to depth + 1)
                    else candidate(safe, root)
                }
            }
            if (queue.isNotEmpty()) truncated = true
        }
        val files = JSONArray()
        val readable = ArrayList<File>()
        candidates.values.forEach { file ->
            val present = file.isFile
            val canRead = present && file.canRead()
            files.put(JSONObject().put("path", file.path).put("bytes", file.length())
                .put("present", present).put("readable", canRead))
            if (canRead && readable.size < 8) readable.add(file)
        }
        data.put("modules", moduleInfo).put("moduleFiles", files).put("indexMatches", indexMatches)
            .put("metadataBytes", bytes).put("directoryEntries", visited).put("indexLines", minOf(lines, 20_000))
            .put("truncated", truncated).put("errors", errors)
            .put("note", "Read-only driver evidence. Missing module files do not prove the driver is built into the kernel. No device ioctl, boot-image read or module execution.")
        return DriverSnapshot(data, readable)
    }

    companion object {
        fun systemModuleRoots() = listOf("/system/lib/modules", "/vendor/lib/modules", "/vendor_dlkm/lib/modules",
            "/system_dlkm/lib/modules", "/odm/lib/modules", "/odm_dlkm/lib/modules", "/lib/modules").map(::File)
        private val MODULE_FILE = Regex("[A-Za-z0-9_.-]+\\.ko(?:\\.(?:gz|xz|zst))?")
        private val UEVENT_KEYS = setOf("DRIVER", "MODALIAS", "MAJOR", "MINOR", "DEVNAME", "DEVTYPE", "OF_NAME", "OF_FULLNAME")
        private fun validName(name: String) = name.matches(Regex("[A-Za-z0-9_-]{1,80}"))
    }
}
