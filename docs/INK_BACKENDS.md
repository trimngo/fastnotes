# Ink implementation and evidence

## Viwoods direct input (0.2.0 default on supported firmware)

The 0.1.0 bitmap-only adapter was slow on the user's full-size AiPaper and produced
brief static squares in full-screen portrait. Its Android input/redraw path did
not implement the firmware callback topology used by ForestNote.

The new implementation registers ENoteSetting.setWritingInputlistener and
processes pen samples directly on the firmware callback thread. It draws into a
separate ARGB preview bitmap. A dedicated refresh worker copies changed areas into
a presentation bitmap and calls renderWriting without holding the input lock.
Pressurized native hover events are normalized as pen input.

The original 0.2.0–0.3.0 two-sample batching was unpaced and discarded submitted
damage immediately. The user supplied 0.3.0 photos showing gaps in rapid handwriting
which filled after a delay without interaction. The device reported 16,232 native
callbacks, 11,950 accepted samples, 5,978 refreshes and FAST mode 4. This establishes
that the native route was active, not that every refresh was displayed promptly.
There is no firmware presentation acknowledgement available through the inspected API.

Version 0.3.1 uses 8 ms pacing, one pending wakeup and unions all unsent damage.
Each refresh also covers the preceding 32 ms of damage to tolerate intermediate
requests being coalesced by a slower consumer. A stopped pen cannot strand the last
sample waiting for another input callback. At pen-up, a 24 ms settling pass covers
the complete gesture before handoff can end writing. These are implementation
parameters, not measured physical latency or a guarantee about the firmware queue.

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

Geometry is captured on the UI thread and frozen for a native session. Pre-draw
checks retry startup after late layout/position changes, while unchanged geometry
is idempotent. A clipped editor uses its local visible rectangle; pen-down and dirty
regions are bounded by the corresponding screen rectangle. Physical display mode
and rotation determine ENote orientation; a portrait-shaped landscape split pane
must not override it. Resize, modal UI, loss
of focus, Move mode and pause stop input and release native references. Pending
accepted edits are drained before replacing a note or viewport. Stale callbacks
from an earlier session are ignored.

A listener that starts but produces no in-window samples falls back to Android
input after a short watchdog interval; held Android events are replayed so the
first stroke is not swallowed. Reflection/render failures expose a fallback
reason. Device details reports callback, accepted-sample and panel-update counts
without logging note contents or raw stroke coordinates. The footer distinguishes
a paused native route from an Android fallback. Settings can retry native startup.

Device details also reads Android's recent process exit reasons (API 30+) and a
bounded app-private Java exception report. The report omits exception messages;
native crashes, ANRs and low-memory exits are distinguished when Android supplies
that evidence. Existing crashes cannot be attributed to a cause from photos alone.

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
library behavior. User feedback confirms native input/FAST mode in 0.3.0 portrait,
but reports Android fallback and an exit in landscape split-screen with Chrome.
ForestNote can also use fast ink in that layout but has stability problems. The
0.3.1 changes still require a fresh physical-device check; no hardware is connected
to the build environment and no crash cause has been established from a stack trace.

The APK deliberately retains target SDK 30 / the documented untrusted_app_30
compatibility context for this vendor API test. API 30 is Android 11, so Android
13 may display an older-version warning. Raising the target without verifying
the private firmware interface could prevent the native path from operating.
That compatibility message is separate from actual installed Android version.
