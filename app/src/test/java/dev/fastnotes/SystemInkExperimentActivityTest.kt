package dev.fastnotes

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import dev.fastnotes.diagnostics.InkTestActivity
import dev.fastnotes.diagnostics.InkTestView
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.diagnostics.SystemInkDevice
import dev.fastnotes.diagnostics.SystemInkProbe
import dev.fastnotes.ink.ViwoodsPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class ExperimentSystemBinder : Binder() {
    data class Call(val code: Int, val area: Rect)
    val calls = ArrayList<Call>()
    var region: Rect? = null
    var failRemove = false
    var beforeAdd: (() -> Unit)? = null
    val adds get() = calls.count { it.code == 0x18 }
    init { attachInterface(null, SystemInkProbe.DESCRIPTOR) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(SystemInkProbe.DESCRIPTOR)
        val area = data.readTypedObject(Rect.CREATOR)!!
        assertEquals(0, data.dataAvail())
        calls.add(Call(code, Rect(area)))
        when (code) {
            0x18 -> { assertNull(region); beforeAdd?.invoke(); region = Rect(area) }
            0x19 -> { assertEquals(region, area); check(!failRemove); region = null }
            else -> error("Unexpected system mutation: $code")
        }
        reply!!.writeNoException()
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemInkExperimentActivityTest {
    private class Screen : AutoCloseable {
        val service = ExperimentSystemBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33, "SoftSolution") }, { service })
        private val app = RuntimeEnvironment.getApplication() as FastnotesApp
        init {
            FastnotesApp::class.java.getDeclaredField("systemInk\$delegate").apply { isAccessible = true }.set(app, lazyOf(probe))
        }
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity get() = lifecycle.get()
        val root get() = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val paper get() = descendants(root).filterIsInstance<InkTestView>().single()
        val options get() = descendants(root).filterIsInstance<Spinner>().single()
        val pressure get() = descendants(root).filterIsInstance<CheckBox>().single()
        val addedAfterPreparation = ArrayList<Boolean>()
        private val frameCallbacks = ArrayList<Runnable>()
        private var paused = false
        init {
            service.beforeAdd = { addedAfterPreparation.add(paper.firmwareTest) }
            layout(); layout()
        }
        fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text == label }
        fun select(index: Int) {
            options.setSelection(index)
            options.onItemSelectedListener!!.onItemSelected(options, null, index, index.toLong())
        }
        fun layout(width: Int = 1200) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            // Match this Activity's real window. A fake half-width root would be
            // corrected by Spinner's queued layout and invalidate the prepared frame.
            root.layout(0, 0, width, 800)
            paper.viewTreeObserver.dispatchOnPreDraw()
        }
        fun visibleArea(): Rect = Rect().also { area ->
            assertTrue(paper.getLocalVisibleRect(area))
            val origin = IntArray(2).also { paper.getLocationOnScreen(it) }
            area.offset(origin[0], origin[1])
        }
        @Suppress("DEPRECATION")
        fun displayArea(): Rect = Point().also { paper.display.getRealSize(it) }.let { Rect(0, 0, it.x, it.y) }
        fun drawPreparation() {
            layout(root.width)
            val image = Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888)
            try { paper.draw(Canvas(image)) } finally { image.recycle() }
            frameCallbacks.addAll(takeInkTestFrameCallbacks(paper))
        }
        fun submitFrame() {
            val callbacks = frameCallbacks.toList()
            frameCallbacks.clear()
            callbacks.forEach(Runnable::run)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun commitPreparation() { drawPreparation(); submitFrame() }
        fun report(): String {
            button("Copy report").performClick()
            return activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        }
        fun diagnostics(): String = InkTestActivity::class.java.getDeclaredMethod("report").apply { isAccessible = true }
            .invoke(activity)?.toString().orEmpty()
        fun pause() { lifecycle.pause(); paused = true }
        override fun close() {
            service.failRemove = false
            if (!paused) lifecycle.pause()
            lifecycle.stop().destroy()
        }
        companion object {
            private fun descendants(view: View): Sequence<View> = sequence {
                yield(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
            }
        }
    }

    @Test fun originalIsDefaultAndRegistersVisibleBoundsBeforePreparingPaper() {
        Screen().use { screen ->
            assertEquals("Original", screen.options.selectedItem)
            assertTrue(screen.pressure.isChecked)
            val area = screen.visibleArea()
            screen.button("System ink").performClick()
            assertEquals(area, screen.service.region)
            assertEquals(listOf(false), screen.addedAfterPreparation)
            assertTrue(screen.paper.firmwareTest)
            assertTrue(screen.report().contains("System experiment: Original"))
        }
    }

    @Test fun preparedVariantRegistersOnlyAfterMatchingDrawAndFrameSubmission() {
        Screen().use { screen ->
            screen.select(1)
            screen.button("System ink").performClick()
            assertTrue(screen.paper.firmwareTest)
            assertEquals(0, screen.service.adds)
            repeat(3) { screen.paper.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(0, screen.service.adds) // Resume/pre-draw cannot duplicate the pending start.
            screen.drawPreparation()
            assertEquals(0, screen.service.adds)
            screen.submitFrame()
            assertEquals(screen.diagnostics(), screen.visibleArea(), screen.service.region)
            assertEquals(listOf(true), screen.addedAfterPreparation)
            assertTrue(screen.report().contains("System ink (prepared): prepared frame submitted"))
            screen.commitPreparation()
            assertEquals(screen.diagnostics(), 1, screen.service.adds)
        }
    }

    @Test fun fullDisplayUsesCurrentRotatedDisplayAndRemovesTheExactOwnedRectangle() {
        Screen().use { screen ->
            val display = shadowOf(screen.paper.display)
            display.setRotation(Surface.ROTATION_90)
            display.setRealWidth(1600)
            display.setRealHeight(1000)
            screen.layout(); screen.layout()
            screen.select(2)
            screen.button("System ink").performClick()
            val full = screen.displayArea()
            assertEquals(Rect(0, 0, 1600, 1000), full)
            assertEquals(full, screen.service.region)
            assertEquals(listOf(false), screen.addedAfterPreparation)
            assertNotEquals(screen.visibleArea(), screen.service.region)
            screen.lifecycle.windowFocusChanged(false)
            assertNull(screen.service.region)
            assertEquals(listOf(ExperimentSystemBinder.Call(0x18, full), ExperimentSystemBinder.Call(0x19, full)), screen.service.calls)
            assertTrue(screen.report().contains("System experiment: Full display; last requested area: ${full.toShortString()}"))
        }
    }

    @Test fun preparedStartIsCancelledByAnotherModeFocusLossPauseOrGeometryChange() {
        for (change in listOf("mode", "focus", "pause", "resize")) {
            Screen().use { screen ->
                screen.select(1)
                screen.button("System ink").performClick()
                screen.drawPreparation()
                when (change) {
                    "mode" -> screen.button("Android pen").performClick()
                    "focus" -> screen.lifecycle.windowFocusChanged(false)
                    "pause" -> screen.pause()
                    "resize" -> screen.layout(360)
                }
                screen.submitFrame()
                assertEquals(change, 0, screen.service.adds)
                assertFalse(screen.probe.hasRegistration)
            }
        }
    }

    @Test fun preparedFocusResumeKeepsCompletedInkAndRequiresANewPreparedFrame() {
        Screen().use { screen ->
            screen.select(1)
            screen.button("System ink").performClick()
            screen.commitPreparation()
            stroke(screen.paper)
            assertEquals(1, screen.paper.completedStrokes)
            screen.lifecycle.windowFocusChanged(false)
            assertNull(screen.service.region)
            screen.lifecycle.windowFocusChanged(true)
            screen.layout(); screen.layout()
            assertEquals(1, screen.service.adds)
            assertEquals(1, screen.paper.completedStrokes)
            screen.commitPreparation()
            assertEquals(screen.diagnostics(), 2, screen.service.adds)
            assertEquals(1, screen.paper.completedStrokes)
        }
    }

    @Test fun recreationPreservesSystemVariantAndPressureChoiceAndRejectsOldPreparation() {
        Screen().use { screen ->
            screen.select(1)
            screen.pressure.isChecked = false
            screen.button("System ink").performClick()
            screen.drawPreparation()
            screen.lifecycle.recreate().visible().windowFocusChanged(true)
            assertEquals("Prepared screen", screen.options.selectedItem)
            assertFalse(screen.pressure.isChecked)
            screen.layout(); screen.layout()
            screen.submitFrame() // Callback captured by the destroyed Activity.
            assertEquals(0, screen.service.adds)
            screen.commitPreparation()
            assertEquals(1, screen.service.adds)
            assertTrue(screen.report().contains("Native pressure enabled: false"))
        }
        Screen().use { screen ->
            screen.select(2)
            screen.button("System ink").performClick()
            val full = screen.displayArea()
            screen.lifecycle.recreate().visible().windowFocusChanged(true)
            screen.layout(); screen.layout()
            assertEquals("Full display", screen.options.selectedItem)
            assertEquals(full, screen.service.region)
            assertTrue(screen.service.adds >= 2)
        }
    }

    @Test fun failedFullDisplayCleanupRejectsVariantChangeAndOtherNativeModes() {
        Screen().use { screen ->
            screen.select(2)
            screen.button("System ink").performClick()
            val full = Rect(screen.service.region!!)
            screen.service.failRemove = true
            screen.select(1)
            assertEquals("Full display", screen.options.selectedItem)
            assertEquals(full, screen.service.region)
            assertTrue(screen.probe.hasRegistration)
            screen.button("Ink layers").performClick()
            screen.commitPreparation()
            assertEquals(1, screen.service.adds)
            assertEquals(full, screen.service.region)
            assertTrue(screen.report().contains("System experiment: Full display"))
            screen.button("Back").performClick()
            assertFalse(screen.activity.isFinishing)
            @Suppress("DEPRECATION")
            screen.activity.onBackPressed()
            assertFalse(screen.activity.isFinishing)
            assertEquals(full, screen.service.region)
            assertTrue(screen.probe.hasRegistration)
            screen.service.failRemove = false
            screen.select(1)
            assertNull(screen.service.region)
            assertEquals("Prepared screen", screen.options.selectedItem)
            screen.button("System ink").performClick()
            screen.commitPreparation()
            assertEquals(2, screen.service.adds)
            assertEquals(screen.visibleArea(), screen.service.region)
        }
    }

    @Test fun pressureChangesRestartOnlyTransparentNativeTestsWithTheNewChoice() {
        Screen().use { screen ->
            val fake = FakePanel.getInstance().apply { reset() }
            var starts = 0
            val native = NativeBufferProbe(screen.activity, {}) { mode ->
                starts++
                ViwoodsPanel(FakePanel::class.java.name, mode.refreshPolicy) { ManualNativeInkScheduler() }
            }.apply { onStrokeFinished = { screen.paper.nativeStrokeFinished() } }
            InkTestActivity::class.java.getDeclaredField("nativeProbe").apply { isAccessible = true }.set(screen.activity, native)
            screen.button("Ink layers").performClick()
            screen.pressure.isChecked = false // Replace a still-pending preparation.
            screen.commitPreparation()
            assertEquals(1, starts)
            assertFalse(native.pressureEnabled)
            assertTrue(native.active)
            screen.pressure.isChecked = true // Active native test is stopped and prepared again.
            assertNull(fake.input)
            assertFalse(native.active)
            screen.commitPreparation()
            assertEquals(2, starts)
            assertTrue(native.pressureEnabled)
            assertTrue(native.active)
            screen.button("System ink").performClick()
            val systemAdds = screen.service.adds
            screen.pressure.isChecked = false
            assertEquals(systemAdds, screen.service.adds)
            assertNotNull(screen.service.region)
        }
    }

    private fun stroke(view: InkTestView) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 100f, MotionEvent.ACTION_MOVE to 200f, MotionEvent.ACTION_UP to 220f)) {
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 80f; pressure = 0.5f })
            MotionEvent.obtain(now, now, action, 1, properties, coordinates, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0).let { view.onTouchEvent(it); it.recycle() }
        }
    }
}
