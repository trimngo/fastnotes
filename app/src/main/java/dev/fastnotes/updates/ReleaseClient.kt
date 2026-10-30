package dev.fastnotes.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.fastnotes.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal const val MAX_APK_BYTES = 64L * 1024 * 1024
internal const val RELEASE_ROOT = "https://github.com/trimngo/fastnotes/releases/download/"
private const val RELEASES_API = "https://api.github.com/repos/trimngo/fastnotes/releases?per_page=20"

data class UpdateRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val apkName: String,
    val bytes: Long,
    val sha256: String
) {
    val apkUrl get() = RELEASE_ROOT + tag + "/" + apkName

    fun toJson() = JSONObject().put("schema", 1).put("application_id", BuildConfig.APPLICATION_ID)
        .put("version_name", versionName).put("version_code", versionCode).put("min_sdk", minSdk)
        .put("apk_name", apkName).put("bytes", bytes).put("sha256", sha256).put("tag", tag)

    companion object {
        fun parse(json: JSONObject, tag: String): UpdateRelease {
            require(json.getInt("schema") == 1) { "Unsupported update format" }
            require(json.getString("application_id") == BuildConfig.APPLICATION_ID) { "Wrong app" }
            require(tag.matches(Regex("v[0-9A-Za-z._-]+"))) { "Invalid release tag" }
            val name = json.getString("apk_name")
            require(name == "fastnotes-$tag.apk") { "Invalid APK name" }
            val code = json.getLong("version_code")
            val version = json.getString("version_name")
            val bytes = json.getLong("bytes")
            val hash = json.getString("sha256")
            val minSdk = json.getInt("min_sdk")
            require(code in 1..Int.MAX_VALUE.toLong() && version.length in 1..60) { "Invalid version" }
            require(bytes in 1..MAX_APK_BYTES && hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid APK digest/size" }
            require(minSdk in 1..1000) { "Invalid Android version" }
            return UpdateRelease(tag, version, code, minSdk, name, bytes, hash)
        }
    }
}

fun interface UpdateTransport {
    fun open(url: String): InputStream
}

/** No authentication or note data is sent. Redirects stay on GitHub's HTTPS asset hosts. */
class GitHubTransport : UpdateTransport {
    override fun open(url: String): InputStream {
        var next = URI(url)
        repeat(6) {
            require(next.scheme == "https" && next.rawUserInfo == null &&
                next.port in listOf(-1, 443) && next.host in setOf("api.github.com", "github.com",
                    "release-assets.githubusercontent.com", "objects.githubusercontent.com",
                    "github-releases.githubusercontent.com", "github-cloud.s3.amazonaws.com")) {
                "Untrusted update destination"
            }
            val connection = URL(next.toString()).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Fastnotes/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", if (next.host == "api.github.com")
                "application/vnd.github+json" else "application/octet-stream")
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    next = next.resolve(connection.getHeaderField("Location") ?: error("Missing redirect"))
                    connection.disconnect()
                } else {
                    if (code == 403 || code == 429) throw IOException("GitHub is busy. Please try again later.")
                    if (code != 200) throw IOException("GitHub couldn't provide the update (HTTP $code).")
                    return object : FilterInputStream(connection.inputStream) {
                        override fun close() { try { super.close() } finally { connection.disconnect() } }
                    }
                }
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
        }
        throw IOException("Too many update redirects")
    }
}

class ReleaseClient(private val transport: UpdateTransport = GitHubTransport()) {
    fun latest(installedCode: Long, sdk: Int): UpdateRelease? {
        val releases = JSONArray(text(RELEASES_API, 1024 * 1024))
        val published = (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .sortedByDescending { it.optString("published_at") }
        // /latest excludes prereleases. Published development builds are our update channel.
        for (release in published) {
            val tag = release.optString("tag_name")
            if (!tag.matches(Regex("v[0-9A-Za-z._-]+"))) continue
            val assets = release.optJSONArray("assets") ?: continue
            val uploaded = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("state") == "uploaded" }
            val manifest = uploaded.singleOrNull { it.optString("name") == "update.json" } ?: continue
            val apkName = "fastnotes-$tag.apk"
            val apk = uploaded.singleOrNull { it.optString("name") == apkName } ?: continue
            // Asset URLs cannot be supplied by another repository or an arbitrary server.
            require(manifest.getString("browser_download_url") == "$RELEASE_ROOT$tag/update.json")
            require(apk.getString("browser_download_url") == "$RELEASE_ROOT$tag/$apkName")
            val info = UpdateRelease.parse(JSONObject(text(manifest.getString("browser_download_url"), 32 * 1024)), tag)
            require(info.bytes == apk.getLong("size")) { "Release size mismatch" }
            if (info.versionCode <= installedCode) return null
            check(info.minSdk <= sdk) { "This update requires a newer Android version." }
            return info
        }
        return null
    }

    fun download(release: UpdateRelease, destination: File, progress: (Long) -> Unit) {
        require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val deadline = System.nanoTime() + 180_000_000_000L
        try {
            transport.open(release.apkUrl).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        check(total <= release.bytes && total <= MAX_APK_BYTES) { "The update download is too large." }
                        check(System.nanoTime() < deadline) { "The update download timed out. Please retry." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress(total)
                    }
                    check(total == release.bytes) { "The update download was interrupted. Please retry." }
                    output.fd.sync()
                }
            }
            check(digest.digest().hex() == release.sha256) { "The update checksum didn't match. Please retry." }
            check(partial.renameTo(destination)) { "Couldn't save the update. Check available storage." }
        } finally {
            partial.delete()
        }
    }

    private fun text(url: String, limit: Int): String = transport.open(url).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= limit) { "Update response is too large" }
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

class ApkVerifier(private val context: Context) {
    fun verify(file: File, release: UpdateRelease) {
        check(file.isFile && file.length() == release.bytes) { "The update file is incomplete. Please download it again." }
        val hash = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
            digest.digest().hex()
        }
        check(hash == release.sha256) { "The update checksum didn't match. Please download it again." }
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Android couldn't read the update APK.")
        verifyIdentity(installed, archive, release)
    }

    internal fun verifyIdentity(installed: PackageInfo, archive: PackageInfo, release: UpdateRelease) {
        check(archive.packageName == context.packageName && archive.packageName == installed.packageName) {
            "This update belongs to another app."
        }
        check(archive.longVersionCode == release.versionCode && archive.longVersionCode > installed.longVersionCode &&
            archive.versionName == release.versionName) { "The update version didn't match." }
        check(archive.applicationInfo?.minSdkVersion == release.minSdk && release.minSdk <= android.os.Build.VERSION.SDK_INT) {
            "This update requires a different Android version."
        }
        fun signers(info: PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }?.toSet().orEmpty()
        val currentSigners = signers(installed)
        check(currentSigners.isNotEmpty() && currentSigners == signers(archive)) {
            "The update isn't signed by the installed Fastnotes app."
        }
    }
}
