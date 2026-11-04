package dev.fastnotes

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.fastnotes.diagnostics.*
import dev.fastnotes.model.Note
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FirmwareEvidenceTest {
    private val app get() = RuntimeEnvironment.getApplication() as FastnotesApp
    private val enote = "Landroid/os/enote/ENoteSetting;"
    private fun root() = Files.createTempDirectory(app.cacheDir.toPath(), "system-api-").toFile()
    private fun dex(definitions: List<String>, references: List<String> = emptyList()): ByteArray {
        val names = definitions + references
        val strings = 112
        val types = strings + names.size * 4
        val classes = types + names.size * 4
        val data = classes + definitions.size * 32
        val bytes = ByteArray(data + names.sumOf { it.length + 2 })
        "dex\n039\u0000".toByteArray().copyInto(bytes)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(32, bytes.size); header.putInt(36, 112); header.putInt(40, 0x12345678)
        header.putInt(56, names.size); header.putInt(60, strings)
        header.putInt(64, names.size); header.putInt(68, types)
        header.putInt(96, definitions.size); header.putInt(100, classes)
        var offset = data
        names.forEachIndexed { index, name ->
            check(name.length < 128)
            header.putInt(strings + index * 4, offset); header.putInt(types + index * 4, index)
            bytes[offset++] = name.length.toByte()
            name.toByteArray().copyInto(bytes, offset); offset += name.length; bytes[offset++] = 0
        }
        definitions.indices.forEach { header.putInt(classes + it * 32, it) }
        return bytes
    }
    private fun jar(file: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
        return file
    }
    private fun manifest(file: File): JSONObject = ZipFile(file).use { zip ->
        JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
    }

    @Test fun dexSelectionRequiresAnActualVendorClassDefinitionAndRejectsBrokenTables() {
        assertEquals(listOf(enote), enoteDefinitions(dex(listOf(enote))))
        val service = "Lcom/android/server/autodraw/T1000AutoDrawPolicy;"
        assertEquals(listOf(service), enoteDefinitions(dex(listOf(service))))
        assertTrue(enoteDefinitions(dex(listOf("Lexample/Other;"), listOf(enote))).isEmpty())
        val bad = dex(listOf(enote))
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(100, Int.MAX_VALUE)
        assertTrue(enoteDefinitions(bad).isEmpty())
        assertTrue(enoteDefinitions(byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test fun exportSelectsVendorDexAndKnownBinaryContentWithAuditableHashes() {
        val system = root()
        val code = dex(listOf(enote))
        val framework = jar(File(system, "framework.jar"), "classes.dex" to dex(listOf("Lexample/Other;"), listOf(enote)),
            "classes2.dex" to code, "../classes3.dex" to code)
        val library = File(system, "libpaintworker.so").apply { writeBytes(byteArrayOf(127, 69, 76, 70, 1, 2, 3, 4)) }
        val output = File(app.cacheDir, "export.zip")
        val result = FirmwareEvidence(listOf(system), { listOf(framework, library) }).write(output, JSONObject(), "diagnostic counters")
        assertEquals(2, result.codeFiles)
        assertFalse(result.inventoryOnly)
        val info = manifest(output)
        val artifacts = info.getJSONArray("artifacts")
        assertEquals("native-library", artifacts.getJSONObject(0).getString("kind"))
        assertEquals("dex", artifacts.getJSONObject(1).getString("kind"))
        val expected = MessageDigest.getInstance("SHA-256").digest(code).joinToString("") { "%02x".format(it) }
        assertEquals(expected, artifacts.getJSONObject(1).getString("sha256"))
        ZipFile(output).use { zip ->
            assertEquals(code.toList(), zip.getInputStream(zip.getEntry(artifacts.getJSONObject(1).getString("entry"))).readBytes().toList())
            assertFalse(zip.entries().toList().any { it.name.contains("..") })
            assertEquals("diagnostic counters", zip.getInputStream(zip.getEntry("ink-report.txt")).bufferedReader().readText())
        }
    }

    @Test fun privateCandidatesAndSymlinksOutsideSystemRootsNeverEnterTheArchive() {
        val system = root()
        val personal = jar(File(app.filesDir, "private-note.jar"), "classes.dex" to dex(listOf(enote)))
        val link = File(system, "linked.jar")
        Files.createSymbolicLink(link.toPath(), personal.toPath())
        val output = File(app.cacheDir, "confined.zip")
        val result = FirmwareEvidence(listOf(system), { listOf(personal, link) }).write(output, JSONObject(), "report")
        assertTrue(result.inventoryOnly)
        assertEquals(0, manifest(output).getJSONArray("inventory").length())
        assertFalse(manifest(output).toString().contains("private-note"))
    }

    @Test fun oversizedEntriesAndExportBudgetProduceAValidInventoryOnlyArchive() {
        val system = root()
        val framework = jar(File(system, "framework.jar"), "classes.dex" to dex(listOf(enote)))
        for (smallEntryLimit in listOf(true, false)) {
            val output = File(app.cacheDir, "bounded-$smallEntryLimit.zip")
            val evidence = FirmwareEvidence(listOf(system), { listOf(framework) },
                maxEntryBytes = if (smallEntryLimit) 120 else 1024, maxCodeBytes = 100)
            assertTrue(evidence.write(output, JSONObject(), "report").inventoryOnly)
            assertEquals(0, manifest(output).getInt("codeFiles"))
            assertFalse(File(output.path + ".part").exists())
        }
    }

    @Test fun readableVdexIsRetainedWhenFrameworkDexIsStripped() {
        val system = root()
        val framework = jar(File(system, "framework.jar"), "META-INF/MANIFEST.MF" to "stripped".toByteArray())
        val vdex = File(system, "boot-framework.vdex").apply { writeBytes("vdex019".toByteArray()) }
        val output = File(app.cacheDir, "preoptimized.zip")
        assertEquals(1, FirmwareEvidence(listOf(system), { listOf(framework, vdex) }).write(output, JSONObject(), "report").codeFiles)
        assertEquals("vdex", manifest(output).getJSONArray("artifacts").getJSONObject(0).getString("kind"))
    }

    @Test fun nativeLibrariesHavePriorityOverDexExportAndCandidateLimits() {
        val system = root()
        val code = dex(listOf(enote))
        val framework = jar(File(system, "framework.jar"), "classes.dex" to code)
        val lib64 = File(system, "lib64").apply { mkdirs() }
        val library = File(lib64, "libpaintworker.so").apply { writeBytes(byteArrayOf(127, 69, 76, 70, 1, 2, 3, 4)) }
        val output = File(app.cacheDir, "native-priority.zip")
        // There is room for either DEX or native code, but not both.
        assertEquals(1, FirmwareEvidence(listOf(system), { listOf(framework, library) }, maxCodeBytes = code.size)
            .write(output, JSONObject(), "report").codeFiles)
        val artifact = manifest(output).getJSONArray("artifacts").getJSONObject(0)
        assertEquals("native-library", artifact.getString("kind"))
        assertEquals(library.canonicalPath, artifact.getString("source"))
        assertTrue(artifact.getString("entry").endsWith("lib64-libpaintworker.so"))

        val many = (0..69).map { File(system, "absent-$it.jar") } + library
        val capped = File(app.cacheDir, "native-candidate-priority.zip")
        assertEquals(1, FirmwareEvidence(listOf(system), { many }).write(capped, JSONObject(), "report").codeFiles)
        assertEquals(library.canonicalPath, manifest(capped).getJSONArray("artifacts").getJSONObject(0).getString("source"))
    }

    @Test fun nativeCodeDoesNotSuppressVdexFallbackWhenNoDexWasExported() {
        val system = root()
        val framework = jar(File(system, "framework.jar"), "META-INF/MANIFEST.MF" to "stripped".toByteArray())
        val library = File(system, "libpaintworker.so").apply { writeBytes(byteArrayOf(127, 69, 76, 70, 1, 2, 3, 4)) }
        val vdex = File(system, "boot-framework.vdex").apply { writeBytes("vdex019".toByteArray()) }
        val output = File(app.cacheDir, "native-and-preoptimized.zip")
        assertEquals(2, FirmwareEvidence(listOf(system), { listOf(framework, library, vdex) })
            .write(output, JSONObject(), "report").codeFiles)
        val artifacts = manifest(output).getJSONArray("artifacts")
        assertEquals("native-library", artifacts.getJSONObject(0).getString("kind"))
        assertEquals("vdex", artifacts.getJSONObject(1).getString("kind"))
    }

    @Test fun actualReadBytesStayChargedAfterOversizedEntriesAndExhaustTheSharedScanBudget() {
        class Counted(source: InputStream) : FilterInputStream(source) {
            var consumed = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also {
                if (it > 0) consumed += it
            }
        }
        val scan = EvidenceScanBudget(100)
        val first = Counted(ByteArrayInputStream(ByteArray(200)))
        assertThrows(IllegalStateException::class.java) { scan.read(first, 50) }
        assertEquals(51, first.consumed) // One bounded entry-overflow sentinel is charged too.
        assertEquals(51L, scan.bytes)
        val second = Counted(ByteArrayInputStream(ByteArray(200)))
        assertThrows(IllegalStateException::class.java) { scan.read(second, 200) }
        assertEquals(49, second.consumed)
        assertEquals(100L, scan.bytes)
        val third = Counted(ByteArrayInputStream(ByteArray(200)))
        assertThrows(IllegalStateException::class.java) { scan.read(third, 200) }
        assertEquals(0, third.consumed)
    }

    @Test fun controllerWaitsForAChosenDestinationAndRecoversAfterProcessRecreation() {
        val system = root()
        val evidence = FirmwareEvidence(listOf(system), { emptyList() })
        val tasks = ArrayDeque<Runnable>()
        val io = Executor { tasks.add(it) }
        var opens = 0
        val copied = ByteArrayOutputStream()
        val first = FirmwareEvidenceController(app, evidence, io) { opens++; copied }
        val calls = ArrayList<FirmwareEvidenceState>()
        val observer: (FirmwareEvidenceState) -> Unit = calls::add
        first.observe(observer)
        first.prepare("diagnostic report")
        assertEquals(FirmwareEvidenceState.Preparing, first.state)
        first.removeObserver(observer)
        tasks.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(first.state is FirmwareEvidenceState.Ready)
        assertEquals(2, calls.size)
        assertEquals(0, opens)
        val recreated = FirmwareEvidenceController(app, evidence, io) { opens++; copied }
        recreated.save(Uri.parse("content://documents/chosen.zip"))
        assertEquals(FirmwareEvidenceState.Saving, recreated.state)
        tasks.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(recreated.state is FirmwareEvidenceState.Saved)
        assertEquals(1, opens)
        assertTrue(copied.size() > 0)
    }

    @Test fun exportActivityUsesTheDocumentPickerAndPreservesSavedNotesAcrossRotation() {
        val note = Note(title = "Private sheet")
        app.store.save(note)
        val before = app.store.load(note.id).note
        val evidence = FirmwareEvidence(listOf(root()), { emptyList() })
        val copied = ByteArrayOutputStream()
        val controller = FirmwareEvidenceController(app, evidence, Executor { it.run() }) { copied }
        FastnotesApp::class.java.getDeclaredField("firmwareEvidence\$delegate").apply { isAccessible = true }
            .set(app, lazyOf(controller))
        val intent = Intent(app, FirmwareEvidenceActivity::class.java).putExtra(FirmwareEvidenceActivity.EXTRA_REPORT, "report only")
        val lifecycle = Robolectric.buildActivity(FirmwareEvidenceActivity::class.java, intent).setup().visible()
        fun children(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
        }
        val activity = lifecycle.get()
        children(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Save ZIP" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("application/zip", request.intent.type)
        assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("fastnotes-api-evidence.zip", request.intent.getStringExtra(Intent.EXTRA_TITLE))
        lifecycle.recreate()
        val current = lifecycle.get()
        assertNull(shadowOf(current).nextStartedActivityForResult)
        FirmwareEvidenceActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(current, request.requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://documents/export.zip")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.state is FirmwareEvidenceState.Saved)
        val info = app.packageManager.getActivityInfo(ComponentName(app, FirmwareEvidenceActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals(app.packageName + ":inktest", info.processName)
        assertNull(app.editor)
        assertEquals(before, app.store.load(note.id).note)
        lifecycle.pause().stop().destroy()
    }

    @Test fun recoveryRejectsApiEvidenceFromAnotherFirmwareBeforeOpeningTheDestination() {
        val evidence = FirmwareEvidence(listOf(root()), { emptyList() })
        val archive = File(app.cacheDir, "firmware-api/fastnotes-api-evidence.zip")
        evidence.write(archive, JSONObject().put("build", "another firmware").put("api", 33)
            .put("appVersion", BuildConfig.VERSION_NAME), "old report")
        var opened = false
        val controller = FirmwareEvidenceController(app, evidence, Executor { it.run() }) {
            opened = true; ByteArrayOutputStream()
        }
        controller.save(Uri.parse("content://documents/chosen.zip"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.state is FirmwareEvidenceState.Failed)
        assertFalse(opened)
    }
}
