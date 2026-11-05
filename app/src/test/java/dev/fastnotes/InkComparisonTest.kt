package dev.fastnotes

import android.content.ComponentName
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.diagnostics.InkProbeLog
import dev.fastnotes.diagnostics.InkTestActivity
import dev.fastnotes.diagnostics.InkTestView
import dev.fastnotes.diagnostics.SystemInkDevice
import dev.fastnotes.diagnostics.SystemInkProbe
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.ink.NativeRefreshPolicy
import dev.fastnotes.model.Note
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

private class ActivitySystemInkBinder : Binder() {
    var region: Rect? = null
    var failRemove = false
    var adds = 0
    var removes = 0
    init { attachInterface(null, SystemInkProbe.DESCRIPTOR) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(SystemInkProbe.DESCRIPTOR)
        val rect = data.readTypedObject(Rect.CREATOR)!!
        when (code) {
            0x18 -> { assertNull(region); region = Rect(rect); adds++ }
            0x19 -> { removes++; check(!failRemove); assertEquals(region, rect); region = null }
            else -> error("Unexpected transaction")
        }
        reply!!.writeNoException()
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkComparisonTest {
    private val app get() = RuntimeEnvironment.getApplication() as FastnotesApp
    private fun stroke(view: InkTestView, complete: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val actions = if (complete) listOf(MotionEvent.ACTION_DOWN to 100f, MotionEvent.ACTION_MOVE to 200f, MotionEvent.ACTION_UP to 220f)
            else listOf(MotionEvent.ACTION_DOWN to 100f)
        for ((action, x) in actions) {
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 100f; pressure = 1f })
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            view.onTouchEvent(event); event.recycle()
        }
    }
    private fun pixel(view: InkTestView): Int {
        val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        return image.getPixel(150, 100)
    }

    @Test fun softSolutionSystemSelectionRegistersAndCommitsCompletedInkAtPenUp() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        val paper = descendants(root).filterIsInstance<InkTestView>().single()
        var connections = 0
        val service = ActivitySystemInkBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33, "SoftSolution") },
            {
                // Test6's manual request registers before resetting the scratchpad to System mode.
                assertFalse(paper.firmwareTest)
                connections++; service
            })
        InkTestActivity::class.java.getDeclaredField("systemProbe").apply { isAccessible = true }.set(activity, probe)
        descendants(root).filterIsInstance<Button>().single { it.text == "System ink" }.performClick()
        assertEquals(1, connections)
        assertTrue(descendants(root).filterIsInstance<TextView>().any {
            it.text.contains("System ink requested")
        })
        assertNotNull(service.region)
        paper.layout(0, 0, 400, 400)
        assertTrue(paper.firmwareTest)
        stroke(paper, complete = true)
        assertTrue(Color.red(pixel(paper)) < 80)
        lifecycle.pause().stop().destroy()
        assertNull(service.region)
    }

    @Test fun firmwareTestHasNoAppDrawnInkUntilTheDocumentedQuietDelay() {
        val lifecycle = Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val view = InkTestView(lifecycle.get())
        lifecycle.get().setContentView(view)
        view.layout(0, 0, 400, 400)
        view.mode(true)
        stroke(view)
        assertEquals(3, view.samples)
        assertEquals(Color.WHITE, pixel(view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(Color.WHITE, pixel(view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(Color.red(pixel(view)) < 80)
        view.mode(false); stroke(view)
        assertTrue(Color.red(pixel(view)) < 80)
        lifecycle.pause().stop().destroy()
    }

    @Test fun diagnosticActivityIsPrivateAndInAnIsolatedProcessAndDoesNotOpenNotes() {
        val note = Note(title = "Saved sheet")
        app.store.save(note)
        val original = app.store.load(note.id).note
        assertNull(app.editor)
        val info = app.packageManager.getActivityInfo(ComponentName(app, InkTestActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals(app.packageName + ":inktest", info.processName)
        val launcher = app.packageManager.getActivityInfo(ComponentName(app, app.packageName + ".InkTestLauncher"), 0)
        assertEquals(app.packageName + ":inktest", launcher.processName)
        assertEquals(InkTestActivity::class.java.name, launcher.targetActivity)
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible()
        assertNull(app.editor)
        lifecycle.pause().stop().destroy()
        assertEquals(original, app.store.load(note.id).note)
    }

    @Test fun nativeCallStagesSurviveReopeningAndRemainBounded() {
        val log = InkProbeLog(app)
        repeat(100) { log.event("Stage $it " + "x".repeat(1000)) }
        val report = InkProbeLog.read(app)
        assertTrue(report.contains("Stage 99"))
        assertFalse(report.contains("Stage 0 "))
        assertTrue(report.length < 25_000)
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun systemInkUsesVisiblePaneWithoutAppNativeAndReleasesOnFocusResizeAndUpdates() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val paper = descendants(root).filterIsInstance<InkTestView>().single()
        fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text == label }
        fun layout(width: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(600, 0, 600 + width, 800)
            paper.viewTreeObserver.dispatchOnPreDraw()
        }
        val service = ActivitySystemInkBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33) }, { service })
        InkTestActivity::class.java.getDeclaredField("systemProbe").apply { isAccessible = true }.set(activity, probe)
        val fake = FakePanel.getInstance().apply { reset() }
        try {
            layout(600)
            button("System ink").performClick()
            val origin = IntArray(2).also { paper.getLocationOnScreen(it) }
            assertEquals(Rect(origin[0], origin[1], origin[0] + paper.width, origin[1] + paper.height), service.region)
            assertTrue(paper.firmwareTest)
            assertTrue(paper.handoffDescription.contains("at pen-up"))
            assertNull(fake.input); assertNull(fake.foreground)
            descendants(root).filterIsInstance<RadioButton>().single { it.text == "Smooth" }.performClick()
            button("Copy report").performClick()
            val report = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("System ink: System writing area accepted"))
            assertTrue(report.contains("User observed ink while moving: Smooth"))
            assertTrue(report.contains("Scratch handoff: Completed strokes at pen-up"))
            layout(600)
            assertTrue(probe.hasRegistration)
            lifecycle.windowFocusChanged(false)
            assertNull(service.region)
            lifecycle.windowFocusChanged(true)
            assertTrue(probe.hasRegistration) // A stable focused pane resumes the selected route.
            layout(360)
            assertNull(service.region)
            layout(360) // The new pane must be seen unchanged twice before automatic registration.
            assertTrue(probe.hasRegistration)
            assertEquals(360, service.region!!.width())
            button("Updates").performClick()
            assertNull(service.region)
            assertEquals("dev.fastnotes.updates.UpdateActivity", shadowOf(activity).nextStartedActivity.component!!.className)
            assertNull(app.editor)
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun unconfirmedSystemCleanupBlocksSwitchingToOtherInkOrOpeningUpdates() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text == label }
        val paper = descendants(root).filterIsInstance<InkTestView>().single()
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(600, 0, 1200, 800)
        val service = ActivitySystemInkBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33) }, { service })
        InkTestActivity::class.java.getDeclaredField("systemProbe").apply { isAccessible = true }.set(activity, probe)
        try {
            button("System ink").performClick()
            assertTrue(probe.hasRegistration)
            service.failRemove = true
            lifecycle.windowFocusChanged(false)
            val failedRemovals = service.removes
            repeat(4) { paper.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(failedRemovals, service.removes)
            lifecycle.windowFocusChanged(true)
            assertEquals(failedRemovals + 1, service.removes)
            repeat(4) { paper.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(failedRemovals + 1, service.removes)
            for (label in listOf("Android pen", "Worker refresh", "Reference callback", "Updates", "API files", "System ink")) {
                button(label).performClick()
                assertTrue(probe.hasRegistration)
                assertTrue(paper.firmwareTest)
                assertEquals(1, service.adds)
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            service.failRemove = false
            button("Android pen").performClick()
            assertNull(service.region)
            assertFalse(paper.firmwareTest)
        } finally { service.failRemove = false; lifecycle.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun selectedSystemInkPreservesCompletedPressureInkAndWaitsForResumedFocus() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val paper = descendants(root).filterIsInstance<InkTestView>().single()
        fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text == label }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(600, 0, 1200, 800)
            paper.viewTreeObserver.dispatchOnPreDraw()
        }
        val service = ActivitySystemInkBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33) }, { service })
        InkTestActivity::class.java.getDeclaredField("systemProbe").apply { isAccessible = true }.set(activity, probe)
        try {
            layout(); layout()
            button("System ink").performClick()
            stroke(paper)
            assertEquals(1, paper.completedStrokes)
            assertTrue(Color.red(pixel(paper)) < 80)
            stroke(paper, complete = false)
            lifecycle.windowFocusChanged(false)
            assertNull(service.region)
            assertEquals(1, paper.completedStrokes)
            assertTrue(paper.handoffDescription.contains("at pen-up"))
            assertTrue(Color.red(pixel(paper)) < 80)
            lifecycle.windowFocusChanged(true)
            assertEquals(2, service.adds)
            assertTrue(probe.hasRegistration)
            assertEquals(1, paper.completedStrokes)
            lifecycle.pause()
            assertNull(service.region)
            lifecycle.windowFocusChanged(true)
            repeat(3) { paper.viewTreeObserver.dispatchOnPreDraw() }
            assertNull(service.region)
            assertEquals(2, service.adds)
            lifecycle.resume()
            layout(); layout()
            assertEquals(3, service.adds)
            assertTrue(probe.hasRegistration)
            assertEquals(1, paper.completedStrokes)
            assertTrue(Color.red(pixel(paper)) < 80)
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun explicitAndroidModeAndNavigationCancelSystemAutoResume() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val paper = descendants(root).filterIsInstance<InkTestView>().single()
        fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text == label }
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(600, 0, 1200, 800)
        repeat(2) { paper.viewTreeObserver.dispatchOnPreDraw() }
        val service = ActivitySystemInkBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33) }, { service })
        InkTestActivity::class.java.getDeclaredField("systemProbe").apply { isAccessible = true }.set(activity, probe)
        try {
            for (choice in listOf("Android pen", "Updates", "API files", "Back")) {
                button("System ink").performClick()
                val adds = service.adds
                button(choice).performClick()
                assertNull(service.region)
                lifecycle.windowFocusChanged(false).windowFocusChanged(true)
                repeat(3) { paper.viewTreeObserver.dispatchOnPreDraw() }
                assertEquals(adds, service.adds)
                assertFalse(probe.hasRegistration)
                if (choice == "Android pen") assertFalse(paper.firmwareTest)
                if (choice == "Updates" || choice == "API files") assertNotNull(shadowOf(activity).nextStartedActivity)
            }
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun failedSystemConnectionRequiresManualRetryInsteadOfEveryDrawOrFocusChange() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val paper = descendants(root).filterIsInstance<InkTestView>().single()
        val select = descendants(root).filterIsInstance<Button>().single { it.text == "System ink" }
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(600, 0, 1200, 800)
        repeat(2) { paper.viewTreeObserver.dispatchOnPreDraw() }
        var connections = 0
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33) }, { connections++; null })
        InkTestActivity::class.java.getDeclaredField("systemProbe").apply { isAccessible = true }.set(activity, probe)
        try {
            select.performClick()
            assertEquals(1, connections)
            repeat(5) { paper.viewTreeObserver.dispatchOnPreDraw() }
            lifecycle.windowFocusChanged(false).windowFocusChanged(true)
            repeat(5) { paper.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals(1, connections)
            assertTrue(descendants(root).filterIsInstance<TextView>().any {
                it.text.contains("Select System ink to retry") && it.text.contains("System ink failed")
            })
            select.performClick()
            assertEquals(2, connections)
            assertFalse(probe.hasRegistration)
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun systemSelectionSurvivesActivityRecreationAndResumesAfterStableLayout() {
        val service = ActivitySystemInkBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33) }, { service })
        FastnotesApp::class.java.getDeclaredField("systemInk\$delegate").apply { isAccessible = true }.set(app, lazyOf(probe))
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        fun layout(width: Int = 600) {
            val root = lifecycle.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(600, 0, 600 + width, 800)
            descendants(root).filterIsInstance<InkTestView>().single().viewTreeObserver.dispatchOnPreDraw()
        }
        try {
            layout(); layout()
            descendants(lifecycle.get().findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.text == "System ink" }.performClick()
            assertEquals(1, service.adds)
            lifecycle.recreate().visible().windowFocusChanged(true)
            val restoredPaper = descendants(lifecycle.get().findViewById(android.R.id.content)).filterIsInstance<InkTestView>().single()
            // visible() can already have completed Android's normal layout/draw passes.
            repeat(2) { restoredPaper.viewTreeObserver.dispatchOnPreDraw() }
            val currentVisible = Rect()
            assertTrue(restoredPaper.getLocalVisibleRect(currentVisible))
            val origin = IntArray(2).also { restoredPaper.getLocationOnScreen(it) }
            currentVisible.offset(origin[0], origin[1])
            assertEquals(currentVisible, service.region)
            assertTrue(service.adds >= 2)
            val beforeResize = service.adds
            layout(360)
            assertNull(service.region)
            layout(360)
            assertEquals(beforeResize + 1, service.adds)
            assertEquals(360, service.region!!.width())
            assertTrue(probe.hasRegistration)
            assertTrue(descendants(lifecycle.get().findViewById(android.R.id.content)).filterIsInstance<InkTestView>().single()
                .handoffDescription.contains("at pen-up"))
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun pendingSystemRegionSurvivesActivityRecreationInTheSameProcess() {
        val service = ActivitySystemInkBinder()
        val probe = SystemInkProbe({}, { SystemInkDevice("Viwoods AiPaper", "3.15.15", 33) }, { service })
        FastnotesApp::class.java.getDeclaredField("systemInk\$delegate").apply { isAccessible = true }.set(app, lazyOf(probe))
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        fun button(label: String) = descendants(lifecycle.get().findViewById(android.R.id.content))
            .filterIsInstance<Button>().single { it.text == label }
        fun layout() {
            val root = lifecycle.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(600, 0, 1200, 800)
        }
        try {
            layout()
            button("System ink").performClick()
            val original = Rect(service.region!!)
            service.failRemove = true
            lifecycle.recreate().visible().windowFocusChanged(true)
            layout()
            assertSame(probe, app.systemInk)
            assertEquals(original, service.region)
            assertTrue(probe.hasRegistration)
            assertTrue(descendants(lifecycle.get().findViewById(android.R.id.content)).filterIsInstance<InkTestView>().single().firmwareTest)
            button("System ink").performClick()
            assertEquals(1, service.adds)
            service.failRemove = false
            button("System ink").performClick()
            assertEquals(2, service.adds)
            assertTrue(probe.hasRegistration)
        } finally { service.failRemove = false; lifecycle.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun nativeDisplaySurvivesStatusUpdatesRecordsVisualResultsAndStopsOnFocusLossAndResize() {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val view = descendants(root).filterIsInstance<InkTestView>().single()
        val start = descendants(root).filterIsInstance<Button>().single { it.text == "Reference callback" }
        val noInk = descendants(root).filterIsInstance<RadioButton>().single { it.text == "No live ink" }
        val copy = descendants(root).filterIsInstance<Button>().single { it.text == "Copy report" }
        fun layout(width: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(600, 0, 600 + width, 800)
            view.viewTreeObserver.dispatchOnPreDraw()
        }
        val fake = FakePanel.getInstance().apply { reset() }
        val probe = NativeBufferProbe(activity, {}) { mode ->
            val policy = if (mode == dev.fastnotes.diagnostics.NativeBufferMode.REFERENCE_CALLBACK) NativeRefreshPolicy.REFERENCE_CALLBACK else NativeRefreshPolicy.WORKER
            ViwoodsPanel(FakePanel::class.java.name, policy) { ManualNativeInkScheduler() }
        }.apply { onStrokeFinished = { view.nativeStrokeFinished() } }
        InkTestActivity::class.java.getDeclaredField("nativeProbe").apply { isAccessible = true }.set(activity, probe)
        try {
            layout(600)
            val before = android.graphics.Rect(view.left, view.top, view.right, view.bottom)
            start.performClick()
            assertTrue(probe.status, probe.active)
            assertEquals(android.graphics.Rect(0, 0, 1200, 800), fake.foregroundBounds)
            assertTrue(view.firmwareTest)
            stroke(view)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            layout(600)
            assertEquals(before, android.graphics.Rect(view.left, view.top, view.right, view.bottom))
            assertTrue(probe.active)
            noInk.performClick()
            copy.performClick()
            val report = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("User observed ink while moving: No live ink"))
            assertTrue(report.contains("Reference callback: 1200x800 at 0,0"))

            lifecycle.windowFocusChanged(false)
            assertFalse(probe.active)
            assertNull(fake.input)
            lifecycle.windowFocusChanged(true)
            assertFalse(probe.active)
            start.performClick()
            assertTrue(probe.active)
            assertFalse(noInk.isChecked)
            layout(360)
            assertFalse(probe.active)
            assertNull(fake.input)
            assertFalse(view.firmwareTest)
            start.performClick()
            assertTrue(probe.active)
            descendants(root).filterIsInstance<Button>().single { it.text == "Updates" }.performClick()
            assertFalse(probe.active)
            assertNull(fake.input)
            assertEquals("dev.fastnotes.updates.UpdateActivity", shadowOf(activity).nextStartedActivity.component!!.className)
            assertNull(app.editor)
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun nativeInkSnapshotsStayFrozenUntilQuietEvenWithoutAndroidPenEvents() {
        val lifecycle = Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val view = InkTestView(lifecycle.get())
        lifecycle.get().setContentView(view)
        view.layout(0, 0, 400, 400)
        var newInk = false
        var down = false
        view.nativeMode({ canvas ->
            canvas.drawColor(Color.WHITE)
            val paint = android.graphics.Paint().apply { color = Color.BLACK }
            canvas.drawRect(100f, 90f, 200f, 110f, paint)
            if (newInk) canvas.drawRect(250f, 90f, 300f, 110f, paint)
            true
        }, { down })
        fun extraPixel(): Int {
            val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image)); return image.getPixel(270, 100)
        }
        view.nativeStrokeFinished()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(Color.WHITE, pixel(view))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(Color.BLACK, pixel(view))
        assertEquals(0, view.samples)
        stroke(view, complete = false) // a duplicate Android DOWN with its UP suppressed
        newInk = true; down = true
        view.nativeStrokeFinished()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(Color.WHITE, extraPixel())
        down = false
        view.nativeStrokeFinished()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(Color.BLACK, extraPixel())
        lifecycle.pause().stop().destroy()
    }
}
