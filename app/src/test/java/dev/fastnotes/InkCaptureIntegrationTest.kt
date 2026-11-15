package dev.fastnotes

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import dev.fastnotes.ink.CaptureFrameCommitter
import dev.fastnotes.ink.CaptureFrameHandoff
import dev.fastnotes.ink.InkView
import dev.fastnotes.ink.NativeCompletionToken
import dev.fastnotes.ink.Tool
import dev.fastnotes.ink.ViwoodsPanel
import dev.fastnotes.model.Note
import dev.fastnotes.model.NoteEditor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w500dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkCaptureIntegrationTest {
    @Before fun avoidRealUpdateRequests() {
        (RuntimeEnvironment.getApplication() as FastnotesApp).updates.automatic = false
    }

    private class Screen(nativeWanted: Boolean = true) : AutoCloseable {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true)
        val fake = FakePanel.getInstance().apply { reset() }
        lateinit var scheduler: ManualNativeInkScheduler
        val panel = ViwoodsPanel(FakePanel::class.java.name) { ManualNativeInkScheduler().also { scheduler = it } }
        val editor = NoteEditor(Note(pageWidth = 500f, penWidth = 3f))
        var edits = 0
        val paper = InkView(lifecycle.get(), panel).apply {
            setRendering(true, nativeWanted, true)
            bind(editor)
            onEdited = { edits++ }
        }
        val classic get() = paper.getChildAt(0)
        val front get() = paper.getChildAt(1)
        init {
            lifecycle.get().setContentView(paper, ViewGroup.LayoutParams(500, 600))
            layout()
            paper.setEnvironment(true, false, false)
            paper.viewTreeObserver.dispatchOnPreDraw()
        }
        fun layout() {
            paper.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            paper.layout(0, 0, 500, 600)
        }
        fun emit(action: Int, x: Int, y: Int) {
            val origin = IntArray(2).also { paper.getLocationOnScreen(it) }
            fake.emit(action, origin[0] + x, origin[1] + y)
        }
        fun android(action: Int, x: Float, y: Float, down: Long = SystemClock.uptimeMillis()) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            paper.onTouchEvent(event)
            event.recycle()
        }
        fun draw(): Bitmap = Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888).also {
            paper.draw(Canvas(it))
        }
        fun frameCallbacks(): List<Runnable> = takeInkTestFrameCallbacks(classic)
        fun submit(callbacks: List<Runnable> = frameCallbacks()) {
            callbacks.forEach(Runnable::run)
            shadowOf(Looper.getMainLooper()).idle()
        }
        override fun close() {
            paper.setEnvironment(false, false, false)
            panel.stop()
            lifecycle.pause().stop().destroy()
        }
    }

    @Test fun liveNativeStrokeIsVisibleInOrdinaryAndroidCanvasBeforePenUp() {
        Screen().use { screen ->
            assertTrue(screen.paper.nativeDiagnostics, screen.panel.active)
            assertTrue(screen.paper.screenSharingEnabled)
            assertEquals(View.GONE, screen.front.visibility)
            assertEquals(View.VISIBLE, screen.classic.visibility)
            screen.emit(MotionEvent.ACTION_DOWN, 40, 53)
            screen.emit(MotionEvent.ACTION_MOVE, 220, 53)
            assertTrue(screen.editor.note.strokes.isEmpty())
            val image = screen.draw()
            try {
                assertEquals(Color.BLACK, image.getPixel(120, 53))
                assertEquals(Color.WHITE, image.getPixel(350, 53))
            } finally { image.recycle() }
            assertTrue(screen.fake.foreground!!.getPixel(350, 53) == Color.TRANSPARENT)
        }
    }

    @Test fun aNativeBurstRequestsOneFrameUntilDrawAndItsLatestTailIsCaptured() {
        Screen().use { screen ->
            screen.draw().recycle()
            val requests = screen.paper.captureRequestCount
            screen.emit(MotionEvent.ACTION_DOWN, 40, 53)
            for (x in 41..220) screen.emit(MotionEvent.ACTION_MOVE, x, 53)
            assertEquals(requests + 1, screen.paper.captureRequestCount)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(screen.paper.captureFramePending)
            screen.draw().let {
                assertEquals(Color.BLACK, it.getPixel(219, 53))
                it.recycle()
            }
            assertFalse(screen.paper.captureFramePending)
            screen.emit(MotionEvent.ACTION_MOVE, 240, 53)
            assertEquals(requests + 2, screen.paper.captureRequestCount)
            screen.draw().let {
                assertEquals(Color.BLACK, it.getPixel(239, 53))
                it.recycle()
            }
        }
    }

    @Test fun completionWaitsForItsMatchingCapturedFrameAndKeepsExactlyOneEdit() {
        Screen().use { screen ->
            val ends = screen.fake.ends
            val down = SystemClock.uptimeMillis()
            screen.emit(MotionEvent.ACTION_DOWN, 40, 53)
            screen.android(MotionEvent.ACTION_DOWN, 40f, 53f, down)
            screen.emit(MotionEvent.ACTION_MOVE, 220, 53)
            screen.android(MotionEvent.ACTION_MOVE, 220f, 53f, down)
            screen.draw().recycle() // A pre-UP frame must not acknowledge the later completion.
            screen.emit(MotionEvent.ACTION_UP, 240, 53)
            screen.android(MotionEvent.ACTION_UP, 240f, 53f, down)
            screen.scheduler.runUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, screen.editor.note.strokes.size)
            assertEquals(1, screen.edits)
            assertEquals(ends, screen.fake.ends)
            screen.draw().let {
                assertEquals(Color.BLACK, it.getPixel(239, 53))
                it.recycle()
            }
            assertEquals(ends, screen.fake.ends)
            screen.submit()
            assertEquals(ends + 1, screen.fake.ends)
            assertEquals(1, screen.editor.note.strokes.size)
        }
    }

    @Test fun modeFocusAndDetachDiscardQueuedFrameAcknowledgements() {
        for (change in listOf("mode", "focus", "detach")) {
            Screen().use { screen ->
                screen.emit(MotionEvent.ACTION_DOWN, 40, 53)
                screen.emit(MotionEvent.ACTION_UP, 220, 53)
                screen.scheduler.runUntilIdle()
                shadowOf(Looper.getMainLooper()).idle()
                screen.draw().recycle()
                val stale = screen.frameCallbacks()
                when (change) {
                    "mode" -> screen.paper.setRendering(true, true, false)
                    "focus" -> screen.lifecycle.windowFocusChanged(false)
                    "detach" -> screen.lifecycle.get().setContentView(View(screen.lifecycle.get()))
                }
                val endsAfterStop = screen.fake.ends
                screen.submit(stale)
                assertEquals(change, endsAfterStop, screen.fake.ends)
                assertFalse(change, screen.paper.captureFramePending)
                if (change != "mode") assertFalse(screen.panel.active)
                else assertFalse(screen.paper.screenSharingEnabled)
            }
        }
    }

    @Test fun sharingFallbackUsesClassicViewAndKeepsTheChoiceAcrossTwoArgumentRenderingChanges() {
        Screen(nativeWanted = false).use { screen ->
            assertFalse(screen.panel.active)
            assertEquals(View.GONE, screen.front.visibility)
            repeat(10) {
                screen.paper.viewTreeObserver.dispatchOnPreDraw()
                screen.paper.setEnvironment(true, false, false)
            }
            screen.paper.setRendering(true, false)
            assertTrue(screen.paper.screenSharingEnabled)
            assertEquals(View.GONE, screen.front.visibility)
            val down = SystemClock.uptimeMillis()
            screen.android(MotionEvent.ACTION_DOWN, 40f, 53f, down)
            screen.android(MotionEvent.ACTION_MOVE, 220f, 53f, down)
            screen.draw().let {
                assertEquals(Color.BLACK, it.getPixel(120, 53))
                it.recycle()
            }
            assertTrue(screen.editor.note.strokes.isEmpty())
            screen.android(MotionEvent.ACTION_UP, 240f, 53f, down)
            assertEquals(1, screen.editor.note.strokes.size)
            assertEquals(0, screen.fake.listenerBindings)
        }
    }

    @Test fun settingsPersistSharingAndOtherRenderingSwitchesDoNotResetItAfterRecreation() {
        val app = RuntimeEnvironment.getApplication() as FastnotesApp
        app.select(Note())
        app.preferences.edit().putBoolean("viwoodsNativeInk", false)
            .putBoolean("frontBuffer", false).remove("screenSharing").commit()
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            assertFalse(lifecycle.get().paper.screenSharingEnabled)
            descendants(lifecycle.get().window.decorView).filterIsInstance<Button>()
                .single { it.text == "Settings" }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val controls = descendants(dialog.window!!.decorView).filterIsInstance<Switch>().toList()
            controls.single { it.text == "Screen sharing (scrcpy)" }.performClick()
            assertTrue(app.preferences.getBoolean("screenSharing", false))
            assertTrue(lifecycle.get().paper.screenSharingEnabled)
            controls.single { it.text == "Low-latency rendering" }.performClick()
            assertTrue(lifecycle.get().paper.screenSharingEnabled)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            lifecycle.recreate().visible()
            assertTrue(lifecycle.get().paper.screenSharingEnabled)
            assertEquals(View.GONE, lifecycle.get().paper.getChildAt(1).visibility)
            assertTrue(app.preferences.getBoolean("frontBuffer", false))
        } finally {
            lifecycle.pause().stop().destroy()
            app.io.submit {}.get(20, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}

/** Token/frame contract tests. Controlled callbacks do not claim GPU or physical-panel latency. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureFrameHandoffTest {
    private class Frame(val callback: Runnable) { var cancelled = false }
    private class ControlledFrames : CaptureFrameCommitter {
        val frames = ArrayList<Frame>()
        override fun register(view: View, callback: Runnable): (() -> Unit) {
            val frame = Frame(callback)
            frames.add(frame)
            return { frame.cancelled = true }
        }
    }

    @Test fun matchingCaptureMustDrawAndCommitBeforeUiAcknowledgement() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = View(lifecycle.get())
            lifecycle.get().setContentView(view)
            val frames = ControlledFrames()
            val acknowledgements = ArrayList<NativeCompletionToken>()
            val token = NativeCompletionToken(7, 2)
            val gate = CaptureFrameHandoff(view, { acknowledgements.add(it) }, frames)
            gate.onDrawn(token) // An older draw cannot acknowledge a new request.
            gate.request(token)
            assertEquals(1, frames.frames.size)
            assertTrue(acknowledgements.isEmpty())
            gate.onDrawn(token)
            assertTrue(acknowledgements.isEmpty())
            val render = Thread { frames.frames.single().callback.run() }
            render.start(); render.join(3000)
            assertFalse(render.isAlive)
            assertTrue(acknowledgements.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(token), acknowledgements)
            gate.cancel()
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun commitBeforeDrawCannotBeValidatedByALaterUiDraw() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = View(lifecycle.get())
            lifecycle.get().setContentView(view)
            val frames = ControlledFrames()
            val acknowledgements = ArrayList<NativeCompletionToken>()
            val token = NativeCompletionToken(7, 2)
            val gate = CaptureFrameHandoff(view, { acknowledgements.add(it) }, frames)
            gate.request(token)
            frames.frames.single().callback.run()
            gate.onDrawn(token)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(acknowledgements.isEmpty())
            assertEquals(2, frames.frames.size)
            gate.onDrawn(token)
            frames.frames.last().callback.run()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(token), acknowledgements)
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun replacedTokensAndCancellationRejectInFlightCallbacks() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = View(lifecycle.get())
            lifecycle.get().setContentView(view)
            val frames = ControlledFrames()
            val acknowledgements = ArrayList<NativeCompletionToken>()
            val old = NativeCompletionToken(7, 2)
            val current = NativeCompletionToken(7, 3)
            val gate = CaptureFrameHandoff(view, { acknowledgements.add(it) }, frames)
            gate.request(old)
            gate.onDrawn(old)
            val stale = frames.frames.last()
            gate.request(current)
            assertTrue(stale.cancelled)
            stale.callback.run()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(acknowledgements.isEmpty())
            gate.onDrawn(current)
            frames.frames.last().callback.run()
            gate.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(acknowledgements.isEmpty())
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun wrongSnapshotAndNullFallbackNeverAcknowledgeCompletion() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = View(lifecycle.get())
            lifecycle.get().setContentView(view)
            val frames = ControlledFrames()
            val acknowledgements = ArrayList<NativeCompletionToken>()
            val token = NativeCompletionToken(7, 2)
            val gate = CaptureFrameHandoff(view, { acknowledgements.add(it) }, frames)
            gate.request(token)
            gate.onDrawn(NativeCompletionToken(7, 1))
            frames.frames.last().callback.run()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(acknowledgements.isEmpty())
            gate.onDrawn(null)
            frames.frames.last().callback.run()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(acknowledgements.isEmpty())
            gate.cancel()
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun softwareFallbackAcknowledgesAfterDrawAndSupportsCancellation() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view = View(lifecycle.get())
            lifecycle.get().setContentView(view)
            val acknowledgements = ArrayList<NativeCompletionToken>()
            val token = NativeCompletionToken(7, 2)
            val gate = CaptureFrameHandoff(view, { acknowledgements.add(it) }, CaptureFrameCommitter { _, _ -> null })
            gate.request(token)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(acknowledgements.isEmpty())
            gate.onDrawn(token)
            assertTrue(acknowledgements.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(token), acknowledgements)
            val next = NativeCompletionToken(7, 3)
            gate.request(next)
            gate.onDrawn(next)
            gate.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(token), acknowledgements)
        } finally { lifecycle.pause().stop().destroy() }
    }
}
