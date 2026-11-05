# Draft technical request for Viwoods

Prepared from the user's device evidence; **not sent**. Built-in Notes is smooth;
test3's two buffer layouts both produced no live ink. Test4's modes both remain
Gaps / lag and the user says they look essentially the same.

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

Those calls ran on a presentation worker. We then tested the inspected community
reference's sequence: render/end on the pen-callback thread, writing enabled at
startup without re-enabling on each DOWN. The report confirms callback/render/end
all on thread 65, with 1,112 accepted/presented samples and maximum call 11,748 µs;
the user still selected Gaps / lag. They explicitly said the worker and reference
look essentially the same. The timings above are call durations, not visible
panel latency. We are collecting readable exact-device API implementation files
to inspect the missing presentation contract; none are bundled in the app.

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


## Exact-device evidence added 4 November 2026

The user exported framework/services implementation from full-size 3.15.15.
Static inspection confirms setViewAutoDrawEnabled is empty, both public
unwrite-rectangle wrappers remove a region, while the actual service add-region
protocol is implemented. An opt-in, build-pinned per-PID region test is prepared
without app-side ENoteWriting initialization or global setting changes. Physical
service access and visible ink remain unverified. Please confirm the supported
third-party scoped API and cleanup/rotation/split-screen contract. Vendor code
and the user's ZIP are not included in this public draft. This request remains
**unsent**.


## Positive System ink observation and follow-up

The user now reports test6 System ink works quite well during live writing.
They observe disappearance around one second after pen-up and a fixed-width
replacement without the native pressure variation. The diagnostic delayed replay
until 1,000 ms while the native policy clears after 800 ms; replay was fixed-width.
Test7 prepares completed Android ink immediately and preserves pressure variation
with an explicitly estimated brush. Exact native pressure normalization/curve,
smoothing and width units remain questions for the supported API contract.

The user also reports loss of System ink selection when switching to Chrome in
landscape split-screen. The manual diagnostic stopped on focus loss; test7 retains
selection, removes the region while inactive, and resumes after focused stable
geometry. Native libraries omitted by the first export are prioritized in a new
explicit export for static inspection. This draft remains **unsent**.


## Native pressure and idle-start questions (5 November 2026)

Both exact-device libpaintworker ABIs forward raw pressure to the T1000 device
interface; width limits are separate. Could you supply the pressure normalization,
curve and smoothing contract for exact completed-stroke replay? The server's
InputWorker jump count is configured to 4, forwarding one of five MOVE/HOVER
candidates; is there a supported scoped control for that worker's sampling?

The user now confirms completed ink stays visible, but sometimes first ink
hesitates after idle with focus unchanged. Built-in Notes starts immediately.
The T1000 policy clears its temporary drawing state 800 ms after UP. What is the
supported idle-to-writing readiness or hover preparation contract for third-party
AutoDraw? No OEM binaries are attached to this public draft. It remains **unsent**.
