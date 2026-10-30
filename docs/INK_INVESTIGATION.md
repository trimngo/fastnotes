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
| System-drawn preview plus separately captured Android strokes | Exposed by the community SDK; full-size behavior unverified | Run an isolated scoped-region experiment and inspect actual 3.15.15 API signatures |
| Full-display native buffers matching WiNote's geometry | Observed in inspected stock-app code | Test separately if the scoped preview route fails; first obtain exact firmware evidence |
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

## Device experiment and decision

1. Compare the same rapid continuous up/down pattern in stock Notes, full-screen
   and in the desired landscape layout. This baseline question is still pending.
2. Open **Fastnotes Ink Test** from the launcher (or Settings → Ink comparison
   test). Write once with **Android pen**, then select **Firmware
   preview** and repeat. Observe ink **while the pen is down**. A blank interval
   followed by ink one second after pen-up means only the Android fallback was
   observed. Do not count that as accelerated firmware drawing.
3. Repeat with Chrome left/test right in landscape, selecting Firmware preview
   again after focus/layout changes. Copy the report, plus the visual result.
4. If firmware preview is complete and quick, integrate that path into real notes
   with a verified handoff/eraser/resize lifecycle. If registration is unsupported,
   fails, or produces no immediate ink, use the report to inspect exact full-size
   framework behavior or seek Viwoods' supported integration. Do not release more
   purported fixes based solely on method presence or unit-test counts.

No existing external application's code, vendor APK, proprietary binary or SDK
was copied into Fastnotes. Only interface facts were used for this independently
written experiment. No tablet, logcat or native crash stack is available here.
