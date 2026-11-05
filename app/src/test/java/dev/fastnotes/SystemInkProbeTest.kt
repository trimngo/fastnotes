package dev.fastnotes

import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Parcel
import dev.fastnotes.diagnostics.SystemInkDevice
import dev.fastnotes.diagnostics.SystemInkProbe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSystemProperties
import org.robolectric.util.ReflectionHelpers

private class SystemInkBinder(descriptor: String = SystemInkProbe.DESCRIPTOR) : Binder() {
    data class Call(val code: Int, val rect: Rect, val flags: Int)
    val calls = ArrayList<Call>()
    var region: Rect? = null
    var failAddReply = false
    var failRemoveReply = false
    var handled = true
    init { attachInterface(null, descriptor) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(SystemInkProbe.DESCRIPTOR)
        val rect = data.readTypedObject(Rect.CREATOR) ?: error("No writing area")
        assertEquals(0, data.dataAvail())
        calls.add(Call(code, Rect(rect), flags))
        if (!handled) return false
        when (code) {
            0x18 -> {
                region = Rect(rect)
                if (failAddReply) throw SecurityException("private data must not enter diagnostic report")
            }
            0x19 -> {
                if (failRemoveReply) throw SecurityException("private data must not enter diagnostic report")
                assertEquals(region, rect)
                region = null
            }
            else -> error("Unexpected mutation: $code")
        }
        reply!!.writeNoException()
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemInkProbeTest {
    private val supported = SystemInkDevice("Viwoods AiPaper", "3.15.15", 33)

    @Test fun productionProbeReadsTheFirmwareBackendBeforeConnecting() {
        ShadowBuild.setModel("Viwoods AiPaper")
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "3.15.15")
        ShadowSystemProperties.override("ro.eink.type", "SoftSolution")
        val probe = SystemInkProbe()
        assertFalse(probe.start(Rect(0, 0, 100, 100)))
        assertTrue(probe.unsupportedBackend)
        assertFalse(probe.hasRegistration)
        assertTrue(probe.status.contains("SoftSolution"))
    }

    @Test fun softSolutionNeverRegistersTheNoOpSystemDrawingPath() {
        var connections = 0
        val stages = ArrayList<String>()
        val probe = SystemInkProbe(stages::add, { supported.copy(einkType = "SoftSolution") },
            { connections++; SystemInkBinder() })
        assertFalse(probe.start(Rect(0, 0, 100, 100)))
        assertEquals(0, connections)
        assertFalse(probe.hasRegistration)
        assertTrue(probe.unsupportedBackend)
        assertTrue(probe.status.contains("SoftSolution"))
        assertTrue(probe.status.contains("Worker refresh"))
    }

    @Test fun t1000BackendStillUsesTheVerifiedScopedProtocol() {
        val service = SystemInkBinder()
        val probe = SystemInkProbe({}, { supported.copy(einkType = "T1000") }, { service })
        assertTrue(probe.start(Rect(0, 0, 100, 100)))
        assertFalse(probe.unsupportedBackend)
        assertTrue(probe.stop())
        assertEquals(listOf(0x18, 0x19), service.calls.map { it.code })
    }

    @Test fun exactProtocolRegistersCopiedRectAndReleasesOnlyThatRect() {
        val service = SystemInkBinder()
        val stages = ArrayList<String>()
        val probe = SystemInkProbe(stages::add, { supported }, { service })
        val area = Rect(1000, 300, 1920, 2200)
        assertTrue(probe.start(area))
        area.setEmpty()
        assertTrue(probe.hasRegistration)
        assertEquals(Rect(1000, 300, 1920, 2200), service.region)
        assertTrue(probe.status.contains("unverified"))
        assertTrue(probe.stop())
        assertFalse(probe.hasRegistration)
        assertNull(service.region)
        assertEquals(listOf(0x18, 0x19), service.calls.map { it.code })
        assertTrue(service.calls.all { it.flags == 0 && it.rect == Rect(1000, 300, 1920, 2200) })
        assertTrue(stages.any { it.startsWith("Calling system addAutoDrawRect") })
        assertTrue(stages.any { it == "Returned from system removeAutoDrawRect" })
        assertTrue(probe.stop())
        assertEquals(2, service.calls.size)
    }

    @Test fun unknownDeviceOrEmptyAreaCannotEvenConnect() {
        val cases = listOf(supported.copy(model = "Viwoods AiPaper Mini"),
            supported.copy(build = "3.15.16"), supported.copy(api = 34))
        for (device in cases) {
            var connections = 0
            val probe = SystemInkProbe({}, { device }, { connections++; SystemInkBinder() })
            assertFalse(probe.start(Rect(0, 0, 100, 100)))
            assertEquals(0, connections)
            assertFalse(probe.hasRegistration)
        }
        var connections = 0
        val probe = SystemInkProbe({}, { supported }, { connections++; SystemInkBinder() })
        assertFalse(probe.start(Rect()))
        assertFalse(probe.start(Rect(-1, 0, 100, 100)))
        assertEquals(0, connections)
    }

    @Test fun wrongDescriptorAndUnavailableServiceDoNotMutateAnything() {
        val service = SystemInkBinder("different.service")
        val probe = SystemInkProbe({}, { supported }, { service })
        assertFalse(probe.start(Rect(0, 0, 100, 100)))
        assertFalse(probe.hasRegistration)
        assertTrue(service.calls.isEmpty())
        val missing = SystemInkProbe({}, { supported }, { null })
        assertFalse(missing.start(Rect(0, 0, 100, 100)))
        assertFalse(missing.hasRegistration)
    }

    @Test fun failedRegistrationReplyRollsBackPossibleServerEffectWithoutPrivateMessages() {
        val service = SystemInkBinder().apply { failAddReply = true }
        val stages = ArrayList<String>()
        val probe = SystemInkProbe(stages::add, { supported }, { service })
        assertFalse(probe.start(Rect(0, 0, 100, 100)))
        assertEquals(listOf(0x18, 0x19), service.calls.map { it.code })
        assertNull(service.region)
        assertFalse(probe.hasRegistration)
        assertTrue(probe.status.contains("SecurityException"))
        assertFalse(stages.joinToString().contains("private data"))
    }

    @Test fun failedCleanupRetainsOwnershipAndBlocksNewRegistrationUntilRemovalSucceeds() {
        val service = SystemInkBinder()
        var connections = 0
        val probe = SystemInkProbe({}, { supported }, { connections++; service })
        val area = Rect(10, 20, 100, 200)
        assertTrue(probe.start(area))
        service.failRemoveReply = true
        assertFalse(probe.stop())
        assertTrue(probe.hasRegistration)
        assertFalse(probe.start(Rect(200, 200, 300, 300)))
        assertEquals(1, connections)
        assertEquals(1, service.calls.count { it.code == 0x18 })
        assertEquals(area, service.region)
        service.failRemoveReply = false
        assertTrue(probe.stop())
        assertNull(service.region)
        assertFalse(probe.hasRegistration)
        assertTrue(probe.start(Rect(200, 200, 300, 300)))
        assertEquals(2, connections)
        assertTrue(probe.stop())
    }

    @Test fun unhandledTransactionCannotBeReportedAsSuccess() {
        val service = SystemInkBinder().apply { handled = false }
        val probe = SystemInkProbe({}, { supported }, { service })
        assertFalse(probe.start(Rect(0, 0, 100, 100)))
        assertTrue(probe.hasRegistration)
        assertTrue(probe.status.contains("Cleanup is unconfirmed"))
        assertFalse(probe.start(Rect(0, 0, 100, 100)))
        assertEquals(1, service.calls.count { it.code == 0x18 })
    }
}
