package dev.fastnotes

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import dev.fastnotes.diagnostics.FirmwarePreviewProbe
import dev.fastnotes.diagnostics.FirmwareEndpoint
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

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

/** Matches the exposed 3.15.15 surface: a view API and no add/remove rectangle methods. */
class ViewFirmwareFake {
    var pictureMode = 3
    var tool = 4
    var enabledView: View? = null
    var rejectEnable = false
    var throwEnable = false
    var rejectDisable = false
    var instances = 0
    var factoryContext: Context? = null
    var globalEnableCalls = 0
    val calls = ArrayList<Pair<View, Boolean>>()
    fun getAutoDrawToolType() = tool
    fun setAutoDrawToolType(value: Int) { tool = value }
    fun setT1000AutoDrawEnabled(value: Boolean) { globalEnableCalls++ }
    fun setViewAutoDrawEnabled(view: View, enabled: Boolean): Boolean {
        calls.add(view to enabled)
        if (enabled) {
            enabledView = view
            if (throwEnable) throw SecurityException("private handwriting must not enter reports")
            return !rejectEnable
        }
        if (rejectDisable) return false
        assertSame(enabledView, view)
        enabledView = null
        return true
    }
    fun initWriting(): Unit = error("The native bitmap initializer must never run")
    companion object {
        val state = ViewFirmwareFake()
        @JvmStatic fun getInstance(): ViewFirmwareFake = error("Use the reported contextual factory when available")
        @JvmStatic fun getInstance(context: Context) = state.also { it.instances++; it.factoryContext = context }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FirmwareProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var fake: ScopedFirmwareFake
    private lateinit var viewFake: ViewFirmwareFake
    private val hosts = ArrayList<ActivityController<Activity>>()
    private val rect = Rect(900, 140, 1600, 980)

    @Before fun reset() {
        fake = ScopedFirmwareFake.state.apply {
            pictureMode = 3; tool = 4; enabled = false; rejectAdd = false; rejectRemove = false
            registered = null; instances = 0; calls.clear()
        }
        viewFake = ViewFirmwareFake.state.apply {
            pictureMode = 3; tool = 4; enabledView = null; instances = 0; factoryContext = null
            rejectEnable = false; throwEnable = false; rejectDisable = false; globalEnableCalls = 0; calls.clear()
        }
    }

    private fun scratchView(): View {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        hosts.add(lifecycle)
        return View(lifecycle.get()).also { lifecycle.get().setContentView(it) }
    }

    @After fun destroyHosts() { hosts.forEach { it.pause().stop().destroy() }; hosts.clear() }

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
        val probe = FirmwarePreviewProbe(context, {}) { _, _ ->
            FirmwareEndpoint(service, FirmwareServiceContract::class.java, "typed service test")
        }
        assertTrue(probe.start(rect))
        assertEquals(rect, service.region)
        assertTrue(probe.status.contains("typed service test"))
        assertEquals(0, fake.instances)
        assertTrue(probe.stop())
        assertNull(service.region)
    }

    @Test fun reportedViewOnlyApiUsesContextFactoryAndReleasesTheSameOwnedView() {
        val view = scratchView()
        val log = ArrayList<String>()
        val probe = FirmwarePreviewProbe(context, ViewFirmwareFake::class.java.name, log::add)
        assertTrue(probe.start(rect, view))
        assertTrue(probe.hasRegistration)
        assertSame(view, viewFake.enabledView)
        assertSame(context.applicationContext, viewFake.factoryContext)
        assertEquals(1, viewFake.instances)
        assertTrue(log.contains("Calling getInstance(Context)"))
        assertTrue(log.contains("Calling setViewAutoDrawEnabled"))
        assertTrue(probe.status.contains("framework view API"))
        assertTrue(probe.status.contains("unverified"))
        assertEquals(0, viewFake.globalEnableCalls)
        assertEquals(4, viewFake.pictureMode)
        assertEquals(2, viewFake.tool)
        assertTrue(probe.stop())
        assertFalse(probe.hasRegistration)
        assertNull(viewFake.enabledView)
        assertEquals(listOf(view to true, view to false), viewFake.calls)
        assertEquals(3, viewFake.pictureMode)
        assertEquals(4, viewFake.tool)
    }

    @Test fun detachedViewIsRejectedBeforeInitializingFirmware() {
        val probe = FirmwarePreviewProbe(context, ViewFirmwareFake::class.java.name)
        assertFalse(probe.start(rect, View(context)))
        assertTrue(probe.status.contains("not attached"))
        assertEquals(0, viewFake.instances)
        assertTrue(viewFake.calls.isEmpty())
    }

    @Test fun viewOnlyApiWithoutAViewReportsTheMissingScopeInsteadOfInitializingFirmware() {
        val log = ArrayList<String>()
        val probe = FirmwarePreviewProbe(context, ViewFirmwareFake::class.java.name, log::add)
        assertFalse(probe.start(rect))
        assertTrue(probe.status.contains("Framework scope unavailable"))
        assertTrue(log.any { it.contains("view API=true; attached view supplied=false") })
        assertEquals(0, viewFake.instances)
    }

    @Test fun rejectedViewRequestReleasesAPossiblyEnabledView() {
        val view = scratchView()
        viewFake.rejectEnable = true
        val probe = FirmwarePreviewProbe(context, ViewFirmwareFake::class.java.name)
        assertFalse(probe.start(rect, view))
        assertFalse(probe.hasRegistration)
        assertNull(viewFake.enabledView)
        assertEquals(listOf(view to true, view to false), viewFake.calls)
        assertEquals(3, viewFake.pictureMode)
        assertEquals(4, viewFake.tool)
    }

    @Test fun failureReportKeepsTheActualCallAndRootTypeWithoutExceptionMessages() {
        val view = scratchView()
        viewFake.throwEnable = true
        val log = ArrayList<String>()
        val probe = FirmwarePreviewProbe(context, ViewFirmwareFake::class.java.name, log::add)
        assertFalse(probe.start(rect, view))
        assertTrue(probe.status.contains("Calling setViewAutoDrawEnabled (SecurityException)"))
        assertTrue(log.any { it == "Failure at: Calling setViewAutoDrawEnabled" })
        assertTrue(log.any { it.contains("Failure types: InvocationTargetException -> SecurityException") })
        assertTrue(log.any { it.startsWith("Failure frame: dev.fastnotes.ViewFirmwareFake.setViewAutoDrawEnabled") })
        assertFalse(log.joinToString().contains("private handwriting"))
        assertNull(viewFake.enabledView)
    }

    @Test fun failedViewCleanupCannotEnableASecondViewAndCanBeRetried() {
        val view = scratchView()
        val other = scratchView()
        val probe = FirmwarePreviewProbe(context, ViewFirmwareFake::class.java.name)
        assertTrue(probe.start(rect, view))
        viewFake.rejectDisable = true
        assertFalse(probe.stop())
        assertTrue(probe.hasRegistration)
        assertFalse(probe.start(rect, other))
        assertEquals(1, viewFake.calls.count { it.second })
        assertSame(view, viewFake.enabledView)
        viewFake.rejectDisable = false
        assertTrue(probe.stop())
        assertFalse(probe.hasRegistration)
        assertNull(viewFake.enabledView)
    }
}
