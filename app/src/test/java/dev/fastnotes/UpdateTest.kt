package dev.fastnotes

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.fastnotes.updates.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateTest {
    private val app get() = RuntimeEnvironment.getApplication() as FastnotesApp
    private val controllers = ArrayList<UpdateController>()
    private val payload = "An APK payload used only by the transport contract fake".toByteArray()
    private val release = UpdateRelease("v0.4.0-dev.1", "0.4.0", 4, 29, "fastnotes-v0.4.0-dev.1.apk",
        payload.size.toLong(), MessageDigest.getInstance("SHA-256").digest(payload).hex())

    private class FakeTransport : UpdateTransport {
        val responses = HashMap<String, ByteArray>()
        val calls = ArrayList<String>()
        override fun open(url: String): java.io.InputStream {
            synchronized(calls) { calls.add(url) }
            return ByteArrayInputStream(responses[url] ?: throw IOException("Offline"))
        }
    }

    private fun listing(info: UpdateRelease = release, draft: Boolean = false, complete: Boolean = true): JSONObject {
        fun asset(name: String) = JSONObject().put("name", name).put("state", "uploaded")
            .put("browser_download_url", "$RELEASE_ROOT${info.tag}/$name").put("size", info.bytes)
        return JSONObject().put("tag_name", info.tag).put("draft", draft).put("prerelease", true)
            .put("published_at", "2026-10-30T15:00:00Z").put("assets", JSONArray().apply {
                put(asset(info.apkName))
                if (complete) put(asset("update.json"))
            })
    }

    private fun transport(vararg listings: JSONObject) = FakeTransport().apply {
        responses["https://api.github.com/repos/trimngo/fastnotes/releases?per_page=20"] =
            JSONArray(listings.toList()).toString().toByteArray()
        responses["$RELEASE_ROOT${release.tag}/update.json"] = release.toJson().toString().toByteArray()
        responses[release.apkUrl] = payload
    }

    private fun controller(client: ReleaseClient, verify: (File, UpdateRelease) -> Unit = { _, _ -> },
                           clock: () -> Long = { 100_000_000L }): UpdateController =
        UpdateController(app, client, verify, clock).also { controllers.add(it) }

    private fun settle(controller: UpdateController) {
        controller.worker.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Before fun clearProviderCacheBetweenTemporaryApplicationDirectories() {
        // Robolectric changes data directories between tests; AndroidX's static cache survives.
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }

    @After fun stopWorkers() { controllers.forEach { it.worker.shutdownNow() } }

    @Test fun developmentReleasesAreFoundAndDraftsAndIncompleteUploadsAreSkipped() {
        val source = transport(listing(draft = true), listing(complete = false), listing())
        assertEquals(release, ReleaseClient(source).latest(3, 33))
        assertEquals(2, source.calls.size)
    }

    @Test fun versionCodePreventsDowngradesAndSameVersionUpdates() {
        val source = transport(listing())
        assertNull(ReleaseClient(source).latest(4, 33))
        assertNull(ReleaseClient(source).latest(99, 33))
    }

    @Test fun releaseCannotRedirectApkOrMetadataToAnotherRepository() {
        for (index in 0..1) {
            val record = listing()
            record.getJSONArray("assets").getJSONObject(index)
                .put("browser_download_url", "https://github.com/someone/other/releases/download/test/update.json")
            assertThrows(IllegalArgumentException::class.java) { ReleaseClient(transport(record)).latest(3, 33) }
        }
    }

    @Test fun malformedWrongAppAndIncompatibleMetadataAreRejected() {
        for ((key, value) in listOf("schema" to 2, "application_id" to "other.app", "bytes" to MAX_APK_BYTES + 1,
            "sha256" to "bad", "apk_name" to "../../notes.apk")) {
            val source = transport(listing())
            source.responses["$RELEASE_ROOT${release.tag}/update.json"] = release.toJson().put(key, value).toString().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { ReleaseClient(source).latest(3, 33) }
        }
        assertThrows(IllegalStateException::class.java) { ReleaseClient(transport(listing())).latest(3, 28) }
    }

    @Test fun unexpectedAssetSizeAndOversizedMetadataAreRejected() {
        val record = listing()
        record.getJSONArray("assets").getJSONObject(0).put("size", release.bytes + 1)
        assertThrows(IllegalArgumentException::class.java) { ReleaseClient(transport(record)).latest(3, 33) }
        val source = transport(listing())
        source.responses["$RELEASE_ROOT${release.tag}/update.json"] = ByteArray(33 * 1024)
        assertThrows(IllegalArgumentException::class.java) { ReleaseClient(source).latest(3, 33) }
    }

    @Test fun completeDownloadIsVerifiedAndIncompleteOrCorruptDownloadsNeverBecomeApks() {
        val destination = File(app.cacheDir, "download/verified.apk")
        val source = transport(listing())
        val client = ReleaseClient(source)
        client.download(release, destination) {}
        assertArrayEquals(payload, destination.readBytes())
        destination.delete()
        for (bad in listOf(payload.dropLast(1).toByteArray(), payload + byteArrayOf(3), ByteArray(payload.size))) {
            source.responses[release.apkUrl] = bad
            assertThrows(IllegalStateException::class.java) { client.download(release, destination) {} }
            assertFalse(destination.exists())
            assertFalse(File(destination.parentFile, "verified.apk.part").exists())
        }
    }

    @Test fun automaticallyChecksOncePerDayAndManualCheckBypassesThrottle() {
        var now = 100_000_000L
        val source = transport()
        val updater = controller(ReleaseClient(source), clock = { now })
        assertTrue(updater.automatic)
        updater.check(automatic = true); settle(updater)
        assertEquals(UpdateState.Current, updater.state)
        updater.check(automatic = true); settle(updater)
        assertEquals(1, source.calls.size)
        updater.check(); settle(updater)
        assertEquals(2, source.calls.size)
        now += 86_400_000L
        updater.check(automatic = true); settle(updater)
        assertEquals(3, source.calls.size)
        updater.automatic = false
        now += 86_400_000L
        updater.check(automatic = true); settle(updater)
        assertEquals(3, source.calls.size)
    }

    @Test fun offlineCheckCanBeRetriedAndDoesNotBlockSavingNotes() {
        val source = FakeTransport()
        val updater = controller(ReleaseClient(source))
        updater.check(automatic = true); settle(updater)
        assertTrue(updater.state is UpdateState.Failed)
        updater.check(automatic = true); settle(updater)
        assertEquals(1, source.calls.size)
        val note = dev.fastnotes.model.Note(title = "Offline writing")
        app.save(note)
        app.io.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(note.id, app.store.load(note.id).note.id)
        source.responses.putAll(transport().responses)
        updater.check(); settle(updater)
        assertEquals(UpdateState.Current, updater.state)
    }

    @Test fun verifiedDownloadSurvivesProcessRestartAndObserversRunOnMainThread() {
        val source = transport(listing())
        var verified = 0
        val verifier: (File, UpdateRelease) -> Unit = { file, info ->
            assertArrayEquals(payload, file.readBytes()); assertEquals(release, info); verified++
        }
        val updater = controller(ReleaseClient(source), verifier)
        updater.observe { assertEquals(Looper.getMainLooper(), Looper.myLooper()) }
        updater.check(); settle(updater)
        assertTrue(updater.state is UpdateState.Available)
        updater.download(); settle(updater)
        assertTrue(updater.state is UpdateState.Ready)
        val restarted = controller(ReleaseClient(FakeTransport()), verifier)
        restarted.check(); settle(restarted)
        assertTrue(restarted.state is UpdateState.Ready)
        assertEquals(2, verified)
        assertEquals(3, source.calls.size)
    }

    @Test fun availableUpdateRemainsVisibleAfterRestartWithoutAnotherNetworkCheck() {
        val first = controller(ReleaseClient(transport(listing())))
        first.check(automatic = true); settle(first)
        assertTrue(first.state is UpdateState.Available)
        val offline = FakeTransport()
        val restarted = controller(ReleaseClient(offline))
        restarted.check(automatic = true); settle(restarted)
        assertEquals(UpdateState.Available(release), restarted.state)
        assertTrue(offline.calls.isEmpty())
    }

    @Test fun rejectedApkIsRemovedAndRemainsRetryable() {
        val updater = controller(ReleaseClient(transport(listing())), { _, _ -> error("Wrong certificate") })
        updater.check(); settle(updater)
        updater.download(); settle(updater)
        assertTrue(updater.state is UpdateState.Failed)
        assertEquals(release, updater.state.release)
        assertFalse(File(app.cacheDir, "updates/fastnotes-update.apk").exists())
        assertFalse(File(app.cacheDir, "updates/ready.json").exists())
    }

    @Test fun archiveMustMatchInstalledSigningCertificatePackageAndVersion() {
        fun info(packageId: String = app.packageName, code: Int = 4, signer: Int = 1) = PackageInfo().apply {
            packageName = packageId; versionName = release.versionName; longVersionCode = code.toLong()
            applicationInfo = ApplicationInfo().apply { minSdkVersion = 29 }
            signingInfo = SigningInfo().apply { shadowOf(this).setSignatures(arrayOf(Signature(byteArrayOf(signer.toByte())))) }
        }
        val verifier = ApkVerifier(app)
        val installed = info(code = 3)
        verifier.verifyIdentity(installed, info(), release)
        for (wrong in listOf(info(signer = 2), info(packageId = "other.app"), info(code = 3),
            info().apply { signingInfo = null })) {
            assertThrows(IllegalStateException::class.java) { verifier.verifyIdentity(installed, wrong, release) }
        }
    }

    @Test fun installerGetsReadOnlyContentUriAndCannotShareNoteDirectory() {
        val file = File(app.cacheDir, "updates/fastnotes-update.apk").apply { parentFile!!.mkdirs(); writeBytes(payload) }
        val intent = updateInstallIntent(app, file)
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals(app.packageName + ".updates", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertArrayEquals(payload, app.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
        assertThrows(IllegalArgumentException::class.java) {
            updateInstallIntent(app, File(app.filesDir, "sheets/private-note"))
        }
    }

    @Test fun permissionDenialCanBeRetriedAndGrantOpensInstallerOnlyOnce() {
        val signing = SigningInfo().apply { shadowOf(this).setSignatures(arrayOf(Signature(byteArrayOf(1)))) }
        val manager = app.packageManager
        shadowOf(manager).getInternalMutablePackageInfo(app.packageName).signingInfo = signing
        val file = File(app.cacheDir, "updates/fastnotes-update.apk").apply { parentFile!!.mkdirs(); writeBytes(payload) }
        File(file.parentFile, "ready.json").writeText(release.toJson().toString())
        shadowOf(manager).setPackageArchiveInfo(file.path, PackageInfo().apply {
            packageName = app.packageName; longVersionCode = release.versionCode; versionName = release.versionName
            signingInfo = signing
            applicationInfo = ApplicationInfo().apply { minSdkVersion = release.minSdk }
        })
        val updater = app.updates.also { controllers.add(it) }
        updater.check(); settle(updater)
        assertTrue(updater.state is UpdateState.Ready)
        val lifecycle = Robolectric.buildActivity(UpdateActivity::class.java).setup().visible()
        val activity = lifecycle.get()
        fun buttons(view: View): List<Button> = when (view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun install() = buttons(activity.window.decorView).single { it.text == "Install update" }.performClick()
        shadowOf(manager).setCanRequestPackageInstalls(false)
        install()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
        lifecycle.pause().stop().start().resume().visible()
        assertNull(shadowOf(activity).nextStartedActivity)
        install()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
        lifecycle.pause().stop()
        shadowOf(manager).setCanRequestPackageInstalls(true)
        lifecycle.start().resume().visible()
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, shadowOf(activity).nextStartedActivity.action)
        lifecycle.pause().stop().start().resume().visible()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(updater.state is UpdateState.Ready)
        lifecycle.pause().stop().destroy()
    }
}
