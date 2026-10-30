package dev.fastnotes

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Surface
import android.widget.FrameLayout
import dev.fastnotes.ink.InkView
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.ink.panelOrientation
import dev.fastnotes.model.Note
import dev.fastnotes.model.NoteEditor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Window lifecycle and screen-coordinate contract tests; not a physical multi-window benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1600dp-h1000dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeWindowTest {
    private val lifecycle = Robolectric.buildActivity(Activity::class.java)
    private lateinit var root: FrameLayout
    private lateinit var paper: InkView
    private lateinit var panel: ViwoodsPanel
    private lateinit var fake: FakePanel
    private lateinit var scheduler: ManualNativeInkScheduler
    private val editor = NoteEditor(Note())

    @Before fun setup() {
        fake = FakePanel.getInstance().apply { reset() }
        val activity = lifecycle.setup().visible().windowFocusChanged(true).get()
        panel = ViwoodsPanel(FakePanel::class.java.name) { ManualNativeInkScheduler().also { scheduler = it } }
        root = FrameLayout(activity)
        paper = InkView(activity, panel).apply { setRendering(false, true); bind(editor) }
        root.addView(paper, FrameLayout.LayoutParams(800, 800).apply { leftMargin = 800; topMargin = 50 })
        activity.setContentView(root)
        layout(800)
        paper.setEnvironment(true, false, true)
        paper.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun layout(left: Int) {
        (paper.layoutParams as FrameLayout.LayoutParams).leftMargin = left
        root.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1600, 900)
        paper.layout(left, 50, left + 800, 850)
        paper.viewTreeObserver.dispatchOnPreDraw()
    }

    @After fun cleanup() { lifecycle.pause().stop().destroy(); panel.stop() }

    @Test fun focusedRightPaneUsesNativeInkAndRepeatedLifecycleCallsDoNotRestartIt() {
        assertTrue(paper.engineDescription + "\n" + paper.nativeDiagnostics, panel.active)
        val listener = fake.input
        val bindings = fake.listenerBindings
        repeat(10) {
            paper.setEnvironment(true, false, true)
            paper.viewTreeObserver.dispatchOnPreDraw()
        }
        assertSame(listener, fake.input)
        assertEquals(bindings, fake.listenerBindings)
        lifecycle.windowFocusChanged(false)
        assertFalse(panel.active)
        assertEquals("Fast ink paused", paper.engineLabel)
        lifecycle.windowFocusChanged(true)
        assertTrue(panel.active)
        assertEquals("Viwoods native", paper.engineLabel)
    }

    @Test fun initiallyInvisiblePaneRetriesAfterItsPositionChangesWithoutAResize() {
        layout(1700)
        assertFalse(panel.active)
        val oldWidth = paper.width
        layout(800)
        assertEquals(oldWidth, paper.width)
        assertTrue(paper.engineDescription + "\n" + paper.nativeDiagnostics, panel.active)
    }

    @Test fun partialPaneStaysNativeAndRefreshesStayInsideItsVisibleScreenRectangle() {
        layout(1000)
        assertTrue(paper.engineDescription + "\n" + paper.nativeDiagnostics, panel.active)
        val origin = IntArray(2).also { paper.getLocationOnScreen(it) }
        val visible = Rect()
        assertTrue(paper.getLocalVisibleRect(visible))
        assertTrue(visible.width() < paper.width)
        fake.emit(MotionEvent.ACTION_DOWN, origin[0] + 700, origin[1] + 100)
        assertFalse(panel.penDown)
        fake.emit(MotionEvent.ACTION_DOWN, origin[0] + 100, origin[1] + 100)
        fake.emit(MotionEvent.ACTION_MOVE, origin[0] + 590, origin[1] + 600)
        fake.emit(MotionEvent.ACTION_UP, origin[0] + 590, origin[1] + 600)
        scheduler.runUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        val screenClip = Rect(visible).apply { offset(origin[0], origin[1]) }
        assertTrue(fake.rendered.isNotEmpty())
        assertTrue(fake.rendered.all { screenClip.contains(it) })
        assertEquals(1, editor.note.strokes.size)
    }

    @Test fun portraitShapedPaneDoesNotChangeTheLandscapePanelOrientation() {
        // The full-size AiPaper's natural panel is portrait; rotating it sideways is landscape
        // even when Android gives the narrow application pane a portrait Configuration.
        for (rotation in listOf(Surface.ROTATION_90, Surface.ROTATION_270)) {
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, panelOrientation(1872, 2560, rotation))
        }
        for (rotation in listOf(Surface.ROTATION_0, Surface.ROTATION_180)) {
            assertEquals(Configuration.ORIENTATION_PORTRAIT, panelOrientation(1872, 2560, rotation))
        }
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, panelOrientation(2560, 1600, Surface.ROTATION_0))
    }
}
