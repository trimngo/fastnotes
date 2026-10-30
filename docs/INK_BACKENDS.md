# Ink implementation and evidence

## Viwoods direct input (0.2.0 default on supported firmware)

The 0.1.0 bitmap-only adapter was slow on the user's full-size AiPaper and produced
brief static squares in full-screen portrait. Its Android input/redraw path did
not implement the firmware callback topology used by ForestNote.

The new implementation registers ENoteSetting.setWritingInputlistener and
processes pen samples directly on the firmware callback thread. It draws into a
separate ARGB preview bitmap and sends padded, clipped dirty rectangles to
renderWriting. It begins a writing transaction on pen-down, batches two changed
regions, flushes on pen-up, and ends the transaction after the UI ingests the
completed edit. Pressurized native hover events are normalized as pen input.

The controller temporarily selects FAST picture mode when the previous mode can
be read, and restores it when leaving the editor. No firmware or security
settings, auto-draw rectangles, shell commands or proprietary binaries are used.
The user explicitly requested native acceleration by default.

The Android front-buffer view is hidden while native input is active. Android
copies of stylus events do not create duplicate strokes. Ordinary View draws
read the current native preview, and no per-sample View invalidation competes
with firmware ink. Completed immutable strokes/erase operations are posted to
the UI for the canonical document, undo history and background saving. The same
paper/stroke rasterizer is used for preview restoration and the canonical page.

Geometry is captured on the UI thread and frozen for a native session. Native
pen-down outside the current editor rectangle is ignored, and dirty rectangles
are intersected with that exact screen-space rectangle. Resize, modal UI, loss
of focus, Move mode and pause stop input and release native references. Pending
accepted edits are drained before replacing a note or viewport. Stale callbacks
from an earlier session are ignored.

A listener that starts but produces no in-window samples falls back to Android
input after a short watchdog interval; held Android events are replayed so the
first stroke is not swallowed. Reflection/render failures expose a fallback
reason. Device details reports callback, accepted-sample and panel-update counts
without logging note contents or raw stroke coordinates.

## ForestNote comparison

The public ForestNote source was inspected at commit
`e14cf45a9ae89b5e0d50823d55254425ad53cf76`:

- [ViwoodsBackend.kt](https://github.com/jdkruzr/ForestNote/blob/e14cf45a9ae89b5e0d50823d55254425ad53cf76/core/ink/src/main/kotlin/com/forestnote/core/ink/ViwoodsBackend.kt): directInputCallbacks, a separate worker preview, no per-sample invalidation, writing transactions, FAST mode, and canonical reconciliation.
- [DrawView.kt](https://github.com/jdkruzr/ForestNote/blob/e14cf45a9ae89b5e0d50823d55254425ad53cf76/app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt): Android/native input ownership and committed-page redraws.
- [Native ink investigation](https://github.com/jdkruzr/ForestNote/blob/e14cf45a9ae89b5e0d50823d55254425ad53cf76/docs/research/viwoods-native-ink-2026-08.md).

Fastnotes uses independently written code implementing the documented protocol,
not ForestNote's brushes, application code, logging, sync or bundled libraries.
The unofficial SDK source was also inspected for API signatures, but is not
copied or bundled; its inspected repository did not contain a license, and the
referenced 0.2.0 dependency was not available from Maven Central.

## Android fallback

Android input tracks pointers by ID, retains historical samples, requests
unbuffered dispatch, and never accepts finger ink. AndroidX Graphics supplies
front-buffer rendering, with an ordinary Canvas compatibility option. Canceled
streams discard their previews. Canonical coordinates do not depend on window
size or rotation. Disk writes run on a single background executor and coalesce
by sheet; scrolling has a short debounce.

- [Android advanced stylus input](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)
- [AndroidX LowLatencyCanvasView](https://developer.android.com/reference/androidx/graphics/lowlatency/LowLatencyCanvasView)

## Support boundary and installer warning

The user confirmed Android 13/API 33 and firmware 3.15.15 on the full-size tablet.
The direct callback protocol was primarily documented on Mini firmware 3.14.5;
passing fake-API tests cannot establish real full-size latency, clipping or native
library behavior. The 0.2.0 build requires a fresh physical-device check.

The APK deliberately retains target SDK 30 / the documented untrusted_app_30
compatibility context for this vendor API test. API 30 is Android 11, so Android
13 may display an older-version warning. Raising the target without verifying
the private firmware interface could prevent the native path from operating.
That compatibility message is separate from actual installed Android version.
