package dev.fastnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.fastnotes.diagnostics.InkTestActivity
import dev.fastnotes.diagnostics.InkTestView
import dev.fastnotes.diagnostics.NativeBufferMode
import dev.fastnotes.diagnostics.NativeBufferProbe
import dev.fastnotes.ink.ViwoodsPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Model ViewRoot's capture/commit boundary without claiming a physical GPU/panel result. */
internal fun takeInkTestFrameCallbacks(view: View): List<Runnable> {
    if (!view.isHardwareAccelerated) return emptyList()
    val method = android.view.ViewTreeObserver::class.java.getDeclaredMethod("captureFrameCommitCallbacks")
        .apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return method.invoke(view.viewTreeObserver) as? List<Runnable> ?: emptyList()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeStartupActivityTest {
    private class Screen(private val failStart: Boolean = false) : AutoCloseable {
        val lifecycle = Robolectric.buildActivity(InkTestActivity::class.java).setup().visible().windowFocusChanged(true)
        val activity = lifecycle.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val paper = descendants(root).filterIsInstance<InkTestView>().single()
        val calls = ArrayList<Pair<NativeBufferMode, Boolean>>()
        private val frameCallbacks = ArrayList<Runnable>()
        private var paused = false
        val fake = FakePanel.getInstance().apply { reset() }
        val probe = NativeBufferProbe(activity, {}) { mode ->
            calls.add(mode to paper.firmwareTest)
            ViwoodsPanel(if (failStart) "missing.NativeApi" else FakePanel::class.java.name, mode.refreshPolicy) {
                ManualNativeInkScheduler()
            }
        }.apply { onStrokeFinished = { paper.nativeStrokeFinished() } }
        init {
            InkTestActivity::class.java.getDeclaredField("nativeProbe").apply { isAccessible = true }.set(activity, probe)
            layout()
        }
        fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text == label }
        fun layout(width: Int = 600) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(600, 0, 600 + width, 800)
            paper.viewTreeObserver.dispatchOnPreDraw()
        }
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
        fun pause() { lifecycle.pause(); paused = true }
        override fun close() {
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

    @Test fun transparentModesPrepareThePaperBeforeAnyNativeInitialization() {
        for ((label, mode) in listOf("Ink layers" to NativeBufferMode.INK_LAYERS, "Ink direct" to NativeBufferMode.INK_DIRECT)) {
            Screen().use { screen ->
                screen.button(label).performClick()
                assertTrue(screen.paper.firmwareTest)
                assertTrue(screen.calls.isEmpty())
                assertFalse(screen.probe.active)
                screen.drawPreparation()
                assertTrue(screen.calls.isEmpty()) // Drawing alone cannot enable the native overlay.
                screen.submitFrame()
                assertEquals(listOf(mode to true), screen.calls)
                assertTrue(screen.probe.active)
                val bindings = screen.fake.listenerBindings
                screen.commitPreparation()
                assertEquals(bindings, screen.fake.listenerBindings)
            }
        }
    }

    @Test fun anotherModeSelectionRejectsTheQueuedOldPreparation() {
        Screen().use { screen ->
            screen.button("Ink direct").performClick()
            screen.drawPreparation()
            screen.button("Ink layers").performClick()
            screen.submitFrame()
            assertTrue(screen.calls.isEmpty())
            screen.commitPreparation()
            assertEquals(listOf(NativeBufferMode.INK_LAYERS to true), screen.calls)
        }
        Screen().use { screen ->
            screen.button("Ink layers").performClick()
            screen.drawPreparation()
            screen.button("Android pen").performClick()
            screen.submitFrame()
            assertTrue(screen.calls.isEmpty())
            assertFalse(screen.paper.firmwareTest)
        }
    }

    @Test fun focusLossPauseAndResizeCancelPreparationBeforeNativeInitialization() {
        for (change in listOf("focus", "pause", "resize")) {
            Screen().use { screen ->
                screen.button("Ink layers").performClick()
                screen.drawPreparation()
                when (change) {
                    "focus" -> screen.lifecycle.windowFocusChanged(false)
                    "pause" -> screen.pause()
                    "resize" -> screen.layout(360)
                }
                screen.submitFrame()
                assertTrue(change, screen.calls.isEmpty())
                assertFalse(screen.probe.active)
                assertNull(screen.fake.input)
                assertFalse(screen.paper.firmwareTest)
            }
        }
    }

    @Test fun failedPreparedStartReturnsToUsableAndroidMode() {
        Screen(failStart = true).use { screen ->
            screen.button("Ink direct").performClick()
            screen.commitPreparation()
            assertEquals(1, screen.calls.size)
            assertFalse(screen.probe.active)
            assertFalse(screen.paper.firmwareTest)
            assertNull(screen.fake.input)
        }
    }

    @Test fun opaqueControlKeepsItsExistingImmediateStartOrdering() {
        Screen().use { screen ->
            screen.button("Worker refresh").performClick()
            assertEquals(listOf(NativeBufferMode.DISPLAY to false), screen.calls)
            assertTrue(screen.probe.active)
            assertTrue(screen.paper.firmwareTest)
        }
    }
}
