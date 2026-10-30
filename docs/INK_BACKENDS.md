# Ink implementation and evidence

## Android path (default)

The application processes stylus and eraser pointers by ID, includes MotionEvent
history, requests unbuffered input, and keeps disk writes off the UI thread.
Fingers cannot add ink. Canceled streams discard their preview, including strokes
that leave and re-enter the window. A gesture freezes its document transform;
resizing ends that gesture before replacing the viewport.

The canonical model is a list of immutable strokes in a fixed-width, vertically
scrolling document. Resizing changes a viewport transform, not stroke coordinates.
Only completed gestures enter the bounded undo history and persistence stream.

AndroidX Graphics' LowLatencyCanvasView provides front-buffered live segments
and commits the cached paper/strokes at gesture boundaries. Its renderer thread
only reads the synchronized bitmap and consumes synchronized segment deltas; it
does not access View geometry or mutate the note model. Compatibility rendering
uses the same bitmap through the normal View canvas. Only visible strokes are
rasterized on viewport rebuilds.

Saving is performed by a single background executor. Pending writes coalesce by
sheet, and scrolling uses a short save debounce. The Saved status appears after a
successful sync/rename. A previous-good backup remains available; malformed files
are preserved and reported rather than replaced with an empty note.

References:

- [Android advanced stylus input](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)
- [AndroidX LowLatencyCanvasView](https://developer.android.com/reference/androidx/graphics/lowlatency/LowLatencyCanvasView)
- [Android multi-window support](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode)

## Viwoods path (experimental)

Independent reflection code probes the documented ENoteSetting bitmap, rendering
and release entry points. It passes the application's own view-sized bitmap and
screen offset, clips dirty rectangles to that view, and releases references on
pause, loss of focus, resize or modal UI. Partial failures release any acquired
references and return to Android rendering. Multi-window mode always uses Android
rendering.

The adapter does not register a raw digitizer listener or firmware auto-draw
regions, change global waveform modes, run shell commands, or bundle vendor
classes/libraries. It retains Android input ownership and the ordinary canvas
as the canonical display. It is a display accelerator, not a claim of native
digitizer latency.

Community research documents a faster direct callback route and an Android target
SDK 30 / untrusted_app_30 compatibility point. It was validated primarily on the
**AiPaper Mini, firmware 3.14.5**, not this user's full-size AiPaper on 3.15.15.
The app targets 30 to retain that compatibility context, while restricting itself
to app-private local files and requesting no permissions.

The upstream API research and SDK source were inspected. No SDK source was copied
or bundled (the inspected SDK repository did not provide a license). The adapter
uses independently written code based on API signatures and behavior described
in those sources:

- [ForestNote native ink investigation](https://github.com/jdkruzr/ForestNote/blob/main/docs/research/viwoods-native-ink-2026-08.md)
- [Unofficial Viwoods SDK](https://github.com/jdkruzr/vw_ink_sdk_unofficial)
- [Viwoods community API reference](https://github.com/jdkruzr/ViwoodsAppDev/blob/main/VIWOODS_APP_DEV.md)

Reflection presence and a passing fake-API contract test cannot establish panel
clipping, native library stability or compatibility on real firmware. The
full-size 3.15.15 path needs physical-device validation before it can become the
default, or be enabled in split-screen. True pen-to-visible-ink latency remains
a hardware measurement.
