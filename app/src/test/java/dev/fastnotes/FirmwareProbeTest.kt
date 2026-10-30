package dev.fastnotes

import android.graphics.Rect
import dev.fastnotes.diagnostics.FirmwarePreviewProbe
import dev.fastnotes.diagnostics.FirmwareEndpoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class ScopedFirmwareFake {
    var pictureMode = 3
    var tool = 4
    var enabled = false
    var rejectAdd = false
    var rejectRemove = false
    var registered: Rect? = null
    var instances = 0
    val calls = ArrayList<String>()
    fun getT1000AutoDrawEnabled() = enabled
    fun setT1000AutoDrawEnabled(value: Boolean) { calls.add("enable=$value"); enabled = value }
    fun getAutoDrawToolType() = tool
    fun setAutoDrawToolType(value: Int) { tool = value }
    fun addAutoDrawRect(rect: Rect): Boolean { calls.add("add"); registered = Rect(rect); return !rejectAdd }
    fun removeAutoDrawRect(rect: Rect) { calls.add("remove"); check(!rejectRemove); assertEquals(registered, rect); registered = null }
    fun initWriting(): Unit = error("The native bitmap initializer must never run")
    fun renderWriting(rect: Rect): Unit = error("There must be no Java bitmap refresh")
    fun setAllRegionUnAutoDraw(value: Boolean): Unit = error("Global exclusions must never be changed")
    fun setAutoDrawPenWidthRange(min: Int, max: Int): Unit = error("Width cannot be changed without a readable old value")
    companion object {
        val state = ScopedFirmwareFake()
        @JvmStatic fun getInstance(): ScopedFirmwareFake = state.also { it.instances++ }
    }
}

class WriteOnlyEnableFake {
    var enables = 0
    var registered: Rect? = null
    fun setT1000AutoDrawEnabled(value: Boolean) { enables++ }
    fun addAutoDrawRect(rect: Rect) { registered = Rect(rect) }
    fun removeAutoDrawRect(rect: Rect) { assertEquals(registered, rect); registered = null }
    companion object {
        val state = WriteOnlyEnableFake()
        @JvmStatic fun getInstance() = state
    }
}

interface FirmwareServiceContract {
    fun addAutoDrawRect(rect: Rect)
    fun removeAutoDrawRect(rect: Rect)
}

private class NonPublicServiceProxy : FirmwareServiceContract {
    var region: Rect? = null
    override fun addAutoDrawRect(rect: Rect) { region = Rect(rect) }
    override fun removeAutoDrawRect(rect: Rect) { assertEquals(region, rect); region = null }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FirmwareProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var fake: ScopedFirmwareFake
    private val rect = Rect(900, 140, 1600, 980)

    @Before fun reset() {
        fake = ScopedFirmwareFake.state.apply {
            pictureMode = 3; tool = 4; enabled = false; rejectAdd = false; rejectRemove = false
            registered = null; instances = 0; calls.clear()
        }
    }

    @Test fun readOnlyCapabilitiesDoNotGetAnInstanceOrCallTheNativeInitializer() {
        val report = FirmwarePreviewProbe.capabilities(listOf(ScopedFirmwareFake::class.java.name))
        assertTrue(report.contains("addAutoDrawRect(Rect)"))
        assertTrue(report.contains("initWriting()"))
        assertEquals(0, fake.instances)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun scopedRequestsUseTheExactPaneAndRestoreReadableSettings() {
        val log = ArrayList<String>()
        val probe = FirmwarePreviewProbe(context, ScopedFirmwareFake::class.java.name, log::add)
        val original = Rect(rect)
        assertTrue(probe.start(rect))
        rect.offset(20, 20)
        assertEquals(original, fake.registered)
        assertEquals(4, fake.pictureMode)
        assertEquals(2, fake.tool)
        assertTrue(fake.enabled)
        assertTrue(probe.status.contains("unverified"))
        assertTrue(log.contains("Calling addAutoDrawRect"))
        assertTrue(probe.stop())
        assertNull(fake.registered)
        assertEquals(3, fake.pictureMode)
        assertEquals(4, fake.tool)
        assertFalse(fake.enabled)
        assertEquals(listOf("enable=true", "add", "remove", "enable=false"), fake.calls)
    }

    @Test fun aRejectedRequestIsReleasedEvenIfTheServiceRegisteredBeforeReturningFalse() {
        fake.rejectAdd = true
        val probe = FirmwarePreviewProbe(context, ScopedFirmwareFake::class.java.name)
        assertFalse(probe.start(rect))
        assertFalse(probe.hasRegistration)
        assertNull(fake.registered)
        assertEquals(3, fake.pictureMode)
        assertFalse(fake.enabled)
    }

    @Test fun missingOrIncompleteApisFailWithoutCallingAnyVendorMethod() {
        val absent = FirmwarePreviewProbe(context, "dev.fastnotes.NoFirmwareApi")
        assertFalse(absent.start(rect))
        val incomplete = FirmwarePreviewProbe(context, String::class.java.name)
        assertFalse(incomplete.start(rect))
        assertEquals(0, fake.instances)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun unknownGlobalEnableAndWidthAreLeftUntouched() {
        val fake = WriteOnlyEnableFake.state.apply { enables = 0; registered = null }
        val probe = FirmwarePreviewProbe(context, WriteOnlyEnableFake::class.java.name)
        assertTrue(probe.start(rect))
        assertTrue(probe.stop())
        assertEquals(0, fake.enables)
    }

    @Test fun releaseFailureIsReportedAndCannotAccumulateAnotherRegion() {
        val probe = FirmwarePreviewProbe(context, ScopedFirmwareFake::class.java.name)
        assertTrue(probe.start(rect))
        fake.rejectRemove = true
        assertFalse(probe.stop())
        assertTrue(probe.hasRegistration)
        assertFalse(probe.start(Rect(1, 2, 3, 4)))
        assertEquals(1, fake.calls.count { it == "add" })
        assertTrue(probe.status.contains("could not be confirmed"))
        fake.rejectRemove = false
        assertTrue(probe.stop())
    }

    @Test fun restoreDoesNotOverwriteASettingChangedElsewhereDuringTheTest() {
        val probe = FirmwarePreviewProbe(context, ScopedFirmwareFake::class.java.name)
        assertTrue(probe.start(rect))
        fake.pictureMode = 17; fake.tool = 5; fake.enabled = false
        assertTrue(probe.stop())
        assertEquals(17, fake.pictureMode)
        assertEquals(5, fake.tool)
        assertFalse(fake.enabled)
    }

    @Test fun romInterfaceMethodsCanCallANonPublicBinderProxyWithoutAnyNativeInitializer() {
        val service = NonPublicServiceProxy()
        val probe = FirmwarePreviewProbe(context, {}) {
            FirmwareEndpoint(service, FirmwareServiceContract::class.java, "typed service test")
        }
        assertTrue(probe.start(rect))
        assertEquals(rect, service.region)
        assertTrue(probe.status.contains("typed service test"))
        assertEquals(0, fake.instances)
        assertTrue(probe.stop())
        assertNull(service.region)
    }
}
