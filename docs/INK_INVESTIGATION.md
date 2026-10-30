# Reconsidering the ink backend after 0.3.1

30 October 2026. Full-size Viwoods AiPaper, Android 13/API 33, firmware 3.15.15.

The user reports that 0.3.1 still has similar gaps/latency and that ForestNote
also has similar problems. Passing application tests and observing successful
`renderWriting` calls did not establish usable physical handwriting. Further
timing changes to that path are not currently justified by device evidence.

## What the primary sources establish

1. **ForestNote's inspected route is still app-rendered native bitmap refresh.**
   ENote supplies raw samples; the app draws a preview bitmap and sends dirty
   rectangles through `renderWriting`. The documented validation is on the Mini,
   firmware 3.14.5, not this full-size 3.15.15 device.
   [Inspected backend](https://github.com/jdkruzr/ForestNote/blob/e14cf45a9ae89b5e0d50823d55254425ad53cf76/core/ink/src/main/kotlin/com/forestnote/core/ink/ViwoodsBackend.kt),
   [August investigation](https://github.com/jdkruzr/ForestNote/blob/e14cf45a9ae89b5e0d50823d55254425ad53cf76/docs/research/viwoods-native-ink-2026-08.md).
   [Issue 10](https://github.com/jdkruzr/ForestNote/issues/10) also records lag on a
   full-size AiPaper; its later fix announcement does not establish this user's
   firmware/performance result.

2. **There is a distinct system-service preview API to investigate.** The
   unofficial SDK exposes `addAutoDrawRect` / `removeAutoDrawRect` and describes
   firmware/MIPI preview separate from bitmap refresh. Rectangles are described as
   owned by the calling PID. Availability, registration success and physical
   drawing are three different claims; none establishes the next.
   [Reflection bridge](https://github.com/jdkruzr/vw_ink_sdk_unofficial/blob/f6060bde8e3cd6f090e1df760733ecd5a843f5cc/viwoods-ink/src/main/java/io/github/vwunofficial/ink/ViwoodsHiddenEnote.java),
   [SDK description](https://github.com/jdkruzr/vw_ink_sdk_unofficial/blob/f6060bde8e3cd6f090e1df760733ecd5a843f5cc/README.md).

3. **The older public notes are internally inconsistent.** Their "solved"
   section describes an accessible initialization path, while older sections
   describe native initialization crashes and a broken AutoDraw region update.
   These are not a verified full-size compatibility contract. Another developer
   explicitly records that uncertainty and has drafted questions for Viwoods.
   [Public API research](https://github.com/jdkruzr/ViwoodsAppDev/blob/b3230a39ae659ef84023ebf2035e5e35e8736daf/VIWOODS_APP_DEV.md),
   [Independent provisional decision](https://github.com/FoxesRCool1/Margin-Eink-Launcher/blob/e1fcb697e3978cc95d11c20cefc94abde9ac4210/docs/decisions/0007-viwoods-ink-and-refresh.md).

4. **The stock application does not simply prove another public SDK exists.**
   The inspected WiNote patch project contains the stock `RjHandWriting` class.
   It allocates display-metric-sized ARGB buffers, uses a device-specific Rj
   renderer, and registers ENote bitmaps. This suggests full-display buffer
   geometry is a meaningful later experiment, but does not establish the cause
   of the missing live segments. The project patches a system application and
   depends on its existing permissions/resources; it is not a reusable supported
   pen library for a normal APK.
   [Stock-app project](https://github.com/Anemone95/viwoods-notes/blob/0a62248c959788c776a8d1b4cffb4d1e99949789/README.md),
   [Inspected class](https://github.com/Anemone95/viwoods-notes/blob/0a62248c959788c776a8d1b4cffb4d1e99949789/overlay/smali_classes2/com/wisky/rjwrite/RjHandWriting.smali),
   [Architecture findings](https://github.com/Anemone95/viwoods-notes/blob/0a62248c959788c776a8d1b4cffb4d1e99949789/src/feature3_endless_page/architecture.md).

Viwoods' [official V3.15 release page](https://viwoods.com/pages/system-update-3-15)
describes application/system improvements but supplies no handwriting API contract
or confirmation that this third-party issue is fixed. Public web search was
unavailable in this session; primary GitHub repositories and the vendor's actual
pages were read directly. No newer exact firmware fix or official SDK was verified.

## Candidate approaches

| Approach | Current evidence | Next useful action |
| --- | --- | --- |
| ENote callbacks plus Java bitmap blits | Active on the user's tablet; 0.3.1 remains unsatisfactory | Keep as historical baseline; stop adjusting timing without new evidence |
| System-drawn preview plus separately captured Android strokes | Test2's view API returned on 3.15.15; user observed no live ink | Record the negative result for this configuration; global enable remains unreadable/unchanged |
| Full-display native buffers, motivated by WiNote's geometry | Test3 produced No live ink with both layouts | Geometry alone did not enable presentation in this test |
| Reference callback/transaction sequence | Inspected SDK presents/ends on the callback thread without a per-DOWN enable call | Test4 compares that sequence with the paced worker, using full-display buffers |
| Vendor-supported API or per-app optimization | Community research describes vendor app-specific optimization; no contract verified for Fastnotes | Prepare a precise vendor request using device/API/test evidence |
| New ordinary Canvas/GPU/C++ renderer | Does not by itself establish access to a different e-ink presentation path | Do not present a language/library replacement as a solution to unmeasured panel behavior |
| Privileged/system-app or firmware changes | Some stock-app modification projects use this access | Not part of the current diagnostic experiment |

## 0.3.2-test1: controlled comparison

This is an investigation build, **not a claimed latency or crash fix**. It retains
the current note backend and adds **Settings → Ink comparison test** in a private
`:inktest` process. An additional **Fastnotes Ink Test** launcher alias opens the
same screen directly, bypassing startup of the current note-rendering path. Its
initial action is read-only inspection; firmware drawing requires selecting the
test explicitly. The test opens no notes and stores no stroke coordinates.
Scratch strokes exist only in memory. A vendor-native failure is kept outside the
normal note-editor process as far as Android's process isolation permits.

- **Android pen:** ordinary stylus-only Canvas drawing, with sample count and
  maximum Android event age. This is a delivery statistic, not panel latency.
- **Firmware preview:** inspect exact public method signatures, then request only
  the visible canvas rectangle through the scoped AutoDraw API. There is no call
  to `initWriting`, no Java bitmap registration, no `renderWriting`, no direct
  ENote input listener, and no hardcoded Binder transaction number. The app
  captures Android pen samples independently.
  The connection first uses the ROM's own `IENoteSetting.Stub.asInterface` and
  service lookup, invoking methods declared on that ROM's interface. This avoids
  initializing the app-side writing library on a supported service route. The
  wrapper is a fallback only when it exposes the scoped registration methods.
- While a firmware test is armed, Android draws no live or newly completed ink
  until **one second of quiet after pen-up**. Thus ink visible during motion must
  come from outside the scratch view's own ink renderer. Delayed ink alone is not
  proof that firmware preview works. A new stroke postpones that redraw.
- Registration is always described as **unverified**. A void/success return from
  the method never promotes the test to a measured working state.
- Only settings whose previous value can be read are temporarily changed/restored.
  An AutoDraw enable setter without a readable old value is left untouched, and
  the log states this. This means a negative result may require vendor/firmware
  investigation; it is not proof that every possible setup is unavailable.
  Global exclusion rectangles, system properties, pen-width settings with no
  getter, root commands and persistent firmware configuration are not changed.
- Focus loss, layout changes, pause and destruction remove the exact registered
  rectangle and restore captured values. The test does not automatically restart
  after rotation or a crash. Native call entry/return stages are atomically saved
  before/after calls; method names/bounds/counters contain no handwriting content.
- **Copy report** includes model/build, display/window geometry, inspection-only
  signatures, stage results, Android delivery counters and crash/exit evidence.
  The same last-stage evidence is available from normal Device details if the
  test closes. Nothing is uploaded automatically.

## Exact 3.15.15 report and the corrected 0.3.2-test2 experiment

The user's test1 report identifies **Viwoods AiPaper**, Android 13/API 33, build
3.15.15, target 30, display 1920×2560 at rotation 0, full-screen canvas
`[0,325][1920,2448]`. It exposes these wrapper methods:

- `setViewAutoDrawEnabled(View, boolean)`
- `getInstance(Context)` and `getInstance()`
- `getPictureMode()` / `setPictureMode(int)`
- `getAutoDrawToolType()` / `setAutoDrawToolType(int)`
- `setT1000AutoDrawEnabled(boolean)`, without a corresponding exposed getter

It does **not expose** `addAutoDrawRect` / `removeAutoDrawRect` through that
reflection report. The inspected typed interface also supplies no matching
region methods. This does not determine whether those methods exist internally
or are hidden from the application.

Test1 required those rectangle methods and threw its own `IllegalStateException`
at the capability check, **before `getInstance` or any drawing request**. Its
failure is a test implementation assumption, not a measurement of firmware ink
speed or proof that direct preview is impossible. The Android sample event ages
(43 ms and 110 ms maximum) exclude visible panel latency. The pasted exit entries
are classified as user/system requests with status 0, not crash or low-memory
evidence; they do not establish the cause of the earlier reported disappearances.

Test2 prefers the exposed **view API** when an attached scratch view is supplied.
It uses the contextual factory when available, then requests
`setViewAutoDrawEnabled(ownedScratchView, true)`. Cleanup calls the same method
with **the same view and `false`**, including if enabling throws after taking
effect. That view is newly created and owned entirely by the test; no other
application's region is changed. Failed cleanup remains pending and blocks
enabling another view. The older typed/rectangle route remains available only on
firmware that actually exposes it.

The test still does not explicitly call `initWriting`, register bitmaps, invoke
`renderWriting`, attach a vendor input callback, guess Binder codes, or change
global exclusion settings. Unreadable global enable/width settings are left
untouched. Factory initialization can run vendor internals, which is why the
scratch activity remains in its separate process. The exact semantics and
physical behavior of the view API are **unverified**; neither its name nor a
successful call establishes visible low-latency drawing. A blank result may still
depend on global firmware configuration that the test has not changed.

Failure reports now identify the last exact stage, root exception types and
bounded code locations without exception messages. Capability inspection adds
method counts and Binder/factory signatures. Instructions remain fixed and the
status area reserves its height, so reporting progress cannot move the registered
canvas and immediately cancel the test. Real pane/focus changes still stop it.
The same one-second Android ink holdback and user-observed live-ink test apply.

## Test2 hardware result and the test3 buffer comparison

The user's pasted test2 report records full-screen portrait, canvas
`[0,358][1920,2400]`, a completed `getInstance(Context)` and
`setViewAutoDrawEnabled` request, and 1,026 Android samples with maximum event age
7 ms. The user then explicitly reported: **"Ink appeared only after lifting the
pen and waiting."** That is the intentionally delayed Android fallback. No live
firmware preview was observed, so this view configuration cannot be integrated
as a working backend. Successful calls and fast sample delivery did not establish
visible ink. The earlier test1 failure in "Previous run" is historical.

The tool getter returned `-1`; its meaning is undocumented here. Global AutoDraw
enable remained unreadable and unchanged. This negative trial does not prove
every possible firmware-preview configuration is unavailable. It does establish
that this exact tested setup did not work. The shared exit entries continue to
report user/system stops with status 0, without a crash/low-memory classification.
Landscape/split-screen and the stock Notes baseline are still unmeasured.

Test3 switches the scratchpad to a controlled **Native pane / Native display**
comparison. Both use the **same existing `ViwoodsPanel` controller**, pen width,
callback handling and paced refresh policy. Native pane registers a canvas-sized
buffer at its screen origin. Native display registers a real-display-sized buffer
at `(0,0)`. Both reject input outside the same visible canvas rectangle and clip
every `renderWriting` request there. No other application's pixels are captured;
the buffers are generated paper and temporary scratch ink. Display dimensions
come from the full rotated display, rather than split-window resource metrics.

This tests a layout/stride/offset hypothesis suggested by the inspected stock
buffers. It **does not copy WiNote's Rj renderer**, establish the vendor's buffer
contract, or guarantee that a larger bitmap fixes gaps. The normal notebook
backend remains the earlier implementation. The failed view route is no longer
offered as a promising comparison button; its probe/inspection code is retained
for reference. No global AutoDraw flag/exclusion, root setting or firmware image
is changed.

App rendering in native modes uses a **frozen completed snapshot**, captured only
after a full second of pen-up/quiet under the controller's input lock. Android
redraws during a later stroke cannot expose its current bitmap as supposed live
ink. Native stroke completion schedules this without requiring duplicate Android
pen events. Scratch native edits are drained and acknowledged in memory, never
stored. Fresh controller identity rejects queued callbacks from a stopped test.
Focus loss, resize/rotation, Updates, pause and exit stop the native controller.
Buffer allocation is limited to eight million pixels per bitmap.

The UI exposes all controls in rows rather than hiding them horizontally. A
direct **Updates** button opens the existing updater. The user can explicitly
choose **Smooth**, **Gaps / lag** or **No live ink**; the default is **Not recorded**.
Copy report includes that observation per comparison, buffer dimensions/origin,
clipped screen bounds, raw/accepted native counts, refresh counts/mode and call
timing. These counters still exclude visible panel latency.

## Test3 result and the reference-sequence audit

The report identifies full-screen portrait, display 1920×2560 and visible canvas
`[0,549][1920,2400]`. Both comparisons ended with the user's **No live ink** result:

| Buffer layout | Native callbacks | Accepted samples | Refresh calls | Mode | Longest refresh call |
| --- | ---: | ---: | ---: | ---: | ---: |
| Pane 1920×1851 at (0,549) | 5,179 | 4,220 | 2,105 | FAST 4 | 129,467 µs |
| Display 1920×2560 at (0,0) | 3,008 | 2,149 | 1,081 | FAST 4 | 105,470 µs |

Android maximum event ages were 31 ms and 10 ms. The timings are delivery/call
measurements, not visible panel latency. The stage log includes intermediate
radio choices; the final result/earlier-run summary both say No live ink. Shared
exit entries still show user/system requests with status 0 rather than a
crash/low-memory classification.

The user separately confirmed that **built-in Viwoods Notes draws smoothly** for
the same rapid pattern. The device can draw smoothly through its built-in route.
This narrows the issue to third-party integration/behavior without establishing
a specific cause or a firmware defect. Buffer geometry alone did not enable live
presentation. The stock Notes baseline and test3 result are no longer pending;
the desired landscape split-screen behavior still needs physical verification.

The audit found a concrete mismatch with the inspected
[`ViwoodsInkController.onNativeInput`](https://github.com/jdkruzr/vw_ink_sdk_unofficial/blob/f6060bde8e3cd6f090e1df760733ecd5a843f5cc/viwoods-ink/src/main/java/io/github/vwunofficial/ink/ViwoodsInkController.java):

- The reference calls `onWritingStart`, registers its bitmap, paints/presents
  from the input callback and calls `onWritingEnd` in that callback on UP/cancel.
- It enables writing at startup, without re-enabling writing on every DOWN.
- Fastnotes' current worker policy presents on a separate thread, re-enables on
  DOWN and waits for UI acknowledgement before ending the transaction.
- The inspected reference has no explicit `initWriting` call. Method presence
  alone does not establish that an extra initializer is needed.

These differences are hypotheses, not a verified thread-affinity contract. The
older synchronous 0.3.0 route also had problems, so callback rendering alone is
not a proven solution; that code also re-enabled on DOWN and batched differently.

## Test4: callback sequence versus worker

The scratchpad now offers **Worker refresh** and **Reference callback**, both
using full-display buffers at (0,0), the same visible-pane clipping and quiet
frozen Android snapshots. Worker is the previous paced control. Reference
presents every accepted dirty region on the callback thread, creates no
presentation worker, omits the per-DOWN enable transition and ends the transaction
in the callback before notifying the UI. UI acknowledgement cannot end it again.

This compares a bundle of reference execution/transaction choices; it does not
isolate thread, batching and enable order as individual causes. The notebook
default remains Worker. The independently written implementation bundles no
third-party/vendor binary. Reports identify policy and callback/render/end thread
IDs to verify actual execution on the ROM, while retaining the user's explicit
visual result. Method success still does not establish visible low-latency ink.

## Device experiment and decision

1. Built-in Notes is confirmed smooth for the rapid pattern. Compare its desired
   landscape split-screen layout as needed when validating a working candidate.
2. Open **Fastnotes Ink Test**. Select **Worker refresh**, draw the rapid pattern,
   then choose the observed result. Select **Reference callback**, repeat and choose
   its result, then **Copy report**. Observe ink while the pen moves. A complete
   image appearing only after the one-second quiet snapshot is not live ink.
3. Repeat with Chrome left/test right in landscape, selecting the native test
   again after focus/layout changes. Use **Updates** for subsequent app updates.
4. If the reference policy improves live ink, verify eraser/scroll/resize/handoff
   before integrating it. If it also produces no live ink, obtain the exact
   full-size firmware implementation or vendor-supported integration to resolve
   the missing presentation contract. More timing or geometry variations alone
   are not justified by this evidence.

A [technical vendor request](VIWOODS_SUPPORT_REQUEST.md) is prepared from the
confirmed evidence and unresolved API contracts. It has not been sent.

No existing external application's code, vendor APK, proprietary binary or SDK
was copied into Fastnotes. Only interface facts were used for this independently
written experiment. No tablet, logcat or native crash stack is available here.
