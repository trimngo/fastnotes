# Draft technical request for Viwoods

Prepared from the user's device evidence; **not sent**. Built-in Notes is smooth;
test3's two buffer layouts both produced no live ink. Test4 is pending.

We are developing Fastnotes (`dev.fastnotes`), an offline Android handwriting
notepad for full-size Viwoods AiPaper. Device: Android 13/API 33, build/firmware
3.15.15, natural display 1920×2560. Current sideloaded compatibility target is
API 30. The desired workflow is portrait and landscape, including Chrome left /
Fastnotes right in Android split-screen, with the original stylus and eraser.

Rapid continuous handwriting through ENote pen callbacks and app-rendered ARGB
bitmaps shows missing live sections that fill later. Native callbacks and FAST
mode 4 are confirmed in device reports. The user observes similar problems in
ForestNote. Our paced bitmap changes did not resolve the physical behavior.
The user confirmed the built-in Viwoods Notes app draws the same pattern smoothly.

An isolated comparison also produced **No live ink** in both layouts:

| Buffer | Native callbacks | Accepted samples | Refreshes | Mode | Longest refresh call |
| --- | ---: | ---: | ---: | ---: | ---: |
| Pane 1920×1851 at (0,549) | 5,179 | 4,220 | 2,105 | FAST 4 | 129,467 µs |
| Display 1920×2560 at (0,0) | 3,008 | 2,149 | 1,081 | FAST 4 | 105,470 µs |

Those calls ran on a presentation worker. We are separately testing the inspected
community reference's sequence: render/end on the pen-callback thread, writing
enabled at startup without re-enabling on each DOWN. Its full-size result is
pending. The timings above are call durations, not visible panel latency.

On this firmware the app can reflect `ENoteSetting.setViewAutoDrawEnabled(View,
boolean)` and `getInstance(Context)`. Both returned without exceptions in an
isolated scratch test, but the user observed no live ink: only the deliberately
delayed Android fallback. The app received 1,026 Android samples with maximum
event age 7 ms; this does not measure visible panel latency. No corresponding
T1000 AutoDraw enable getter is exposed, so that flag was left unchanged.
`getAutoDrawToolType()` returned -1, whose meaning we have not established.
`addAutoDrawRect` / `removeAutoDrawRect` are not exposed by app-side reflection;
the ROM `IENoteSetting` interface exposes no declared methods to this app.

Could you provide the supported handwriting integration or clarify:

1. Which API, permissions/app optimization and lifecycle should an ordinary
   third-party application use for low-latency ink on this full-size firmware?
2. Does view AutoDraw require other flags or an app allowlist? What do its scoped
   enable/disable calls and the -1 tool result mean?
3. For `setWritingJavaBitmap` and its background equivalent, what buffer size,
   pixel layout, screen origin and orientation are required in full-screen and
   right-hand landscape split-screen windows? Are full-display buffers required?
4. Are any thread-affinity, enable-transition or stroke transaction requirements
   imposed on bitmap registration, `renderWriting` or `onWritingEnd`?
5. How should foreground/background handoff, erasing, scrolling and focus changes
   be handled, and is there a firmware update addressing incomplete live strokes?

The reproducible scratch comparison and source are at
https://github.com/trimngo/fastnotes. Test4 compares callback and worker execution
with full-display buffers and visible-pane clipping. It records thread IDs and
the user's actual visual result separately from call/counter success. No saved
notes or handwriting content need be shared to reproduce the issue.
