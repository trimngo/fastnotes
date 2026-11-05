package dev.fastnotes

import dev.fastnotes.diagnostics.DriverEvidence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DriverEvidenceTest {
    private fun root() = Files.createTempDirectory(RuntimeEnvironment.getApplication().cacheDir.toPath(), "driver-evidence-").toFile()
    private fun write(base: File, name: String, value: String) = File(base, name).apply {
        parentFile!!.mkdirs(); writeText(value)
    }
    private fun link(file: File, target: File) {
        file.parentFile!!.mkdirs(); Files.createSymbolicLink(file.toPath(), target.toPath())
    }
    private fun module(base: File, name: String) = File(base, name).apply {
        parentFile!!.mkdirs(); writeBytes(byteArrayOf(127, 69, 76, 70, 1, 2, 3, 4))
    }

    @Test fun deviceIdentityResolvesDriverModuleAndDeepIndexCandidateWithoutPersonalUeventFields() {
        val fixture = root()
        val sys = File(fixture, "sys").apply { mkdirs() }
        val mods = File(fixture, "vendor/lib/modules").apply { mkdirs() }
        val actual = File(sys, "devices/platform/t1000_misc").apply { mkdirs() }
        val driver = File(sys, "bus/platform/drivers/t1000_spi").apply { mkdirs() }
        val sysModule = File(sys, "module/paper_bridge").apply { mkdirs() }
        link(File(sys, "dev/char/240:0"), actual)
        link(File(actual, "device/driver"), driver)
        link(File(driver, "module"), sysModule)
        write(actual, "uevent", "MAJOR=240\nMINOR=0\nDEVNAME=t1000_spi\nSERIAL=personal-serial\n")
        write(actual, "device/uevent", "DRIVER=t1000_spi\nMODALIAS=platform:t1000\nSECRET=personal-value\n")
        write(actual, "device/modalias", "platform:t1000\n")
        write(sysModule, "version", "1.2\n")
        write(sysModule, "srcversion", "ABC123\n")
        write(sysModule, "initstate", "live\n")
        val kernel = write(fixture, "proc/version", "Linux version fixture-kernel\n")
        val code = module(mods, "kernel/drivers/spi/paper_bridge.ko")
        write(mods, "modules.dep", "kernel/drivers/spi/paper_bridge.ko: unrelated_dependency.ko\n")
        write(mods, "modules.builtin", "kernel/drivers/misc/t1000_builtin.ko\n")
        val snapshot = DriverEvidence(listOf(mods), sys, kernel, { 240L to 0L }).capture()
        val device = snapshot.data.getJSONObject("device")
        assertEquals(240L, device.getLong("major"))
        assertEquals("t1000_spi", device.getString("driverName"))
        assertEquals("paper_bridge", device.getString("moduleName"))
        assertEquals("platform:t1000", device.getString("modalias"))
        assertEquals("1.2", snapshot.data.getJSONArray("modules").getJSONObject(0).getString("version"))
        assertEquals(listOf(code.canonicalFile), snapshot.modules)
        assertTrue(snapshot.data.getJSONArray("indexMatches").toString().contains("t1000_builtin.ko"))
        assertFalse(snapshot.data.toString().contains("personal-serial"))
        assertFalse(snapshot.data.toString().contains("personal-value"))
        assertFalse(snapshot.modules.any { it.name.contains("unrelated") })
    }

    @Test fun symlinkAndIndexEscapesNeverSupplyPrivateModulesOrMetadata() {
        val fixture = root()
        val sys = File(fixture, "sys").apply { mkdirs() }
        val mods = File(fixture, "vendor/lib/modules").apply { mkdirs() }
        val privateDir = File(fixture, "personal-files").apply { mkdirs() }
        val privateModule = module(privateDir, "t1000-personal.ko")
        link(File(mods, "t1000_link.ko"), privateModule)
        link(File(sys, "dev/char/240:0"), privateDir)
        link(File(sys, "module/t1000_escape"), privateDir)
        write(mods, "modules.dep", "../personal-files/t1000-personal.ko:\n${privateModule.absolutePath}:\n")
        val good = module(mods, "t1000_real.ko.xz")
        val kernel = write(fixture, "proc/version", "Linux fixture")
        val snapshot = DriverEvidence(listOf(mods), sys, kernel, { 240L to 0L }).capture()
        assertEquals(listOf(good.canonicalFile), snapshot.modules)
        assertEquals(0, snapshot.data.getJSONArray("modules").length())
        assertFalse(snapshot.data.toString().contains(privateDir.canonicalPath))
        assertFalse(snapshot.data.toString().contains("personal-files"))
        assertTrue(snapshot.data.getJSONObject("device").getString("sysfsStatus").contains("unavailable"))
    }

    @Test fun byteNodeAndCandidateBudgetsBoundTheInventory() {
        val fixture = root()
        val mods = File(fixture, "modules").apply { mkdirs() }
        repeat(20) { module(mods, "t1000_$it.ko") }
        val kernel = write(fixture, "version", "K".repeat(5000))
        val sys = File(fixture, "sys")
        val bounded = DriverEvidence(listOf(mods), sys, kernel, { null }, maxMetadataBytes = 64, maxNodes = 5).capture()
        assertEquals(64, bounded.data.getInt("metadataBytes"))
        assertTrue(bounded.data.getInt("directoryEntries") <= 5)
        assertTrue(bounded.data.getJSONArray("moduleFiles").length() <= 5)
        assertEquals(64, bounded.data.getString("kernelVersion").length)
        assertTrue(bounded.data.getBoolean("truncated"))
        val candidates = DriverEvidence(listOf(mods), sys, kernel, { null }).capture()
        assertEquals(8, candidates.modules.size)
        assertTrue(candidates.data.getJSONArray("moduleFiles").length() <= 32)
        assertTrue(candidates.data.getInt("metadataBytes") <= 2 * 1024 * 1024)
    }

    @Test fun unavailableDriverDataStillProducesAnExplicitInventoryWithoutExceptionMessages() {
        val fixture = root()
        val snapshot = DriverEvidence(listOf(File(fixture, "absent-modules")), File(fixture, "absent-sys"),
            File(fixture, "absent-version"), { throw SecurityException("personal exception details") }).capture()
        assertTrue(snapshot.modules.isEmpty())
        assertEquals("device identity unavailable", snapshot.data.getJSONObject("device").getString("status"))
        assertTrue(snapshot.data.isNull("kernelVersion"))
        assertTrue(snapshot.data.getJSONArray("errors").length() > 0)
        assertFalse(snapshot.data.toString().contains("personal exception details"))
        assertTrue(snapshot.data.getString("note").contains("do not prove"))
    }
}
