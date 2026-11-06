# Installed Settings pressure-test findings

The supplied Settings export contains the pressure-test renderer and its pen
response formula. The inspected Rj implementation draws pressure-sensitive ink
into Android bitmaps from ENote callbacks, then submits bitmap regions through
`ENoteSetting.renderWriting`. Its code is useful evidence for later pressure
matching; it does not establish that a missing firmware curve caused the
reported first-stroke hesitation.

This is static analysis only. No OEM code was executed, and no OEM binaries,
smali, resources or copied implementation are included in this repository.

## Exact evidence identity

The user supplied `fastnotes-settings-code.zip` from the tablet's installed
system Settings package. The archive SHA-256 is
`344e1273acc57c127a6d0785e8277b7fbc3d9a3093771cd61e683a16fbad3c43`.

| Export metadata | Value |
| --- | --- |
| Device | Viwoods AiPaper, Android 13/API 33, build 3.15.15 |
| Package | `com.wisky.setting.se01` |
| Activity | `com.wisky.modulesettingmain.activity.SettingMainActivity` |
| Version | 2.8.0, versionCode 233 |
| Installed APK | `/product/app/setting_SE03_release_2.8.0_2026_09_04_12_04/setting_SE03_release_2.8.0_2026_09_04_12_04.apk` |
| APK size recorded by exporter | 76,159,838 bytes |
| APK SHA-256 recorded by exporter | `b1d9dad9b6e6e5ad78ed309ec295b38e4e976f0d58755ad45dc0aa18549461ad` |

The export contains 22,219,968 bytes of manifest/DEX. All four files were hashed
locally and matched the supplied inventory. The whole installed APK was not
included, so its recorded source hash cannot be independently recomputed here.

| Included file | Bytes | Verified SHA-256 |
| --- | ---: | --- |
| `AndroidManifest.xml` | 56,200 | `1547cf047a568200c90f9fcbce09dcfbedec62bdfc6d3f183a890b4889bf4fa7` |
| `classes.dex` | 9,194,280 | `a5c627b040abb47f0b376e025a81204f63b67c051ddb5ec472e278431e4c078c` |
| `classes2.dex` | 8,569,460 | `8c629f6f95e430256696c8ea5ff319b4353e800ddc656512606beadfa3a7a268` |
| `classes3.dex` | 4,400,028 | `9a790e02d9d25f067029b3c49243e441058713cffe38a1365fc0e59e591b6a96` |

Class/method references below identify the supplied DEX and the original source
line annotations preserved in its disassembly. They do not depend on committing
the disassembly.

## Backend selection is conditional

`classes3`, `manager.WiskyWriteManager.Companion.getManager` (source lines
30–43) chooses `com.wisky.manager.RjWriteManager` when
`manager.SysProApi.getModel()` returns `SE03` or `SE05`; otherwise it selects
`com.wisky.manager.WriteManager`. `SysProApi.getModel` (source line 61) reads
`ro.eink.model`.

The export records the friendly device model, not this property. Its installed
APK path includes `SE03`, which is consistent with the Rj branch but does not
prove the runtime property value. The Rj trace below is therefore conditional on
that selection. The alternative manager uses the bundled `NoteJNI` handwriting
interface; API presence alone does not prove that branch was active.

## Settings startup lifecycle

`classes2`, `com.wisky.modulepen.fragment.PenEffectsFragment` contains the
following policy:

1. `initHandWrite` (source lines 129–137) initializes the writer in the test
   `FrameLayout`, selects `STEEL` and width preset `M`, and disables geometry,
   history, translation and automatic pressure-settings loading before applying
   the currently selected pen settings.
2. `onResume` (lines 74–82) and becoming visible in `onHiddenChanged` (lines
   110–124) initialize that view, remove an older enable callback, then post
   `runnableEnableWriteNote` after **600 ms**.
3. That runnable calls `enableWriteNote(true)`. `enableWriteNote` (lines
   144–156) cancels the pending runnable, then calls `startWriteEngine` followed
   by `startWrite`. On the Rj branch, `RjWriteManager.startWriteEngine` is a
   no-op; `startWrite` (lines 127–149) calls
   `ENoteSetting.setWritingEnabled(true)` and clears the view's `dontWrite` flag.
4. `onWindowFocusChanged` (lines 102–104) calls `enableWriteNote` directly with
   the new focus value. Thus the 600 ms timer is not an unconditional delay on
   every entry path. Pause/hide paths disable writing and destroy the writer.

`RjWriteManager.bindWriteLifecycle` is a no-op in this DEX. The fragment's own
lifecycle and focus methods provide the observed control policy.

`NoteView.initRjHandWriting` initializes its background and native callback,
and requests jump count zero. A separate 500 ms retry in `NoteView.initView`
exists only when display dimensions are invalid. These are reference behaviors,
not recommendations to copy delays or change sampling. Neither timer measures
native surface readiness or visible panel latency.

## Input and rendering in the Rj branch

`classes2`, `com.wisky.rjwrite.NoteView.setNativeInputListener` registers an
`ENoteWritingInputListener` with `ENoteSetting`. Its callback
`setNativeInputListener$lambda-19` constructs `NdMotionEvent` from callback
coordinates, integer pressure, tilt, tool type, action and buttons. It subtracts
the writing area's screen offset, adjusts coordinates, applies its pressure
threshold and boundary checks, and dispatches the event. This path does not use
`MotionEvent.getPressure()` for its pressure input.

`RjHandWriting.onNativeTouchEvent` (source lines 1250–1268) updates the selected
pen width from each move's pressure and draws with that pen into its bitmap
canvas. `SteelPen.writeLine` smooths nearby positions with quadratic segments;
larger width changes receive interpolated intermediate lines. These are
application-side rendering operations.

`NoteView.setWritingJavaBitmap` registers the live bitmap with ENote. Its
`sendRectBitmapToNative` accumulates callback damage rectangles before
`renderWriting`; the pending final region has a separate flush path. Stroke
start and completion paths also use the ENote writing lifecycle and completed
background bitmap. This is a bitmap callback renderer, rather than proof that
the Settings canvas uses the previously observed unrestricted System preview.

The framework itself adds asynchronous boundaries: listener assignment and
input delivery use the ENoteWriting handler thread. A synchronous
`setWritingEnabled` wrapper return does not confirm that a first frame reached
the panel. See [SoftSolution findings](SOFTSOLUTION_FINDINGS.md) for the native
surface evidence and its limits.

## Pressure response and width quantization

`classes2`, `com.wisky.writebasemodle.pen.Utils.fixPressure` (source lines
15–16) normalizes integer pressure as follows, where `s` is the start pressure
and `r` is the pressure ratio:

`q = clamp(((pressure − s) / (4096 − s)) × r, 0, 1)`

`com.wisky.rjwrite.pen.SteelPen.isFilterByPress` (source lines 266–281) then
computes the width from the chosen preset's minimum `a` and maximum `b`:

`width = a + (b − a) × ln(1 + 3q) / ln(4)`

`SteelPen.generateTopDownLevelList` and `fixWidth` (lines 203–228) quantize
that width to levels starting at `a`, increasing by 0.4, and ending at `b`.
A level is selected below its level-plus-0.2 threshold; the last level is the
fallback. Paint widths then apply the pen's width factor and viewport scale.
Although its method is named `isFilterByPress`, the Steel override performs the
width update and returns false, bypassing the base pen's alternative
pressure/tilt mapping.

`Utils` has defaults `s=360`, `r=1`; these are not the final test-canvas settings.
`NoteView` initializes start pressure to 410, with a 200–600 range, and ratio to
1, with a 0.33–3 range. `changeStartOfPress` and `changePressRatio` map slider
values 0–50 from minimum to default and 50–100 from default to maximum.
`PenEffectsFragment.changeCurrentWriteViewEffects` applies its selected saved
Viwoods/other-pen settings through those methods. `NoteView` also handles events
at or below the start-pressure threshold before the width function runs.

The `openPressEffects(false)` call in the test fragment disables automatic
loading/observation of stored effects; it does not remove the Steel pen's
pressure calculation. The fragment updates its test parameters explicitly.

Preset bounds are not recoverable from this code-only export:
`BasePen.initPaintWidth` reads comma-separated minimum/maximum pairs from an
Android string-array resource; `SteelPen.getPenWidthRes` selects
`rj_steel_pen_width`. Resources were intentionally excluded. The user's current
slider values are likewise not part of the installed-code export. The formula
and smoothing steps therefore support later pressure matching, but do not yet
establish an exact pixel-for-pixel reproduction of their current Settings ink,
or the pressure curve of the earlier global firmware System preview.

## Scope of the next comparison

The user clarified that the occasional delayed stroke is complete and the
hesitation occurs after selecting a mode. This points the next comparison at
startup ordering; it does not establish dropped input or an idle wake defect.

Test14 isolates dismissal/drawing of the mode-selection UI and the editor's
initial frame before native startup. It does not change pressure rendering,
jump counts, firmware power policy or add the Settings app's fixed 600 ms
delay. Pressure response can be compared separately once startup behavior is
established.

Related evidence: [Settings export procedure](SETTINGS_CODE_EVIDENCE.md),
[Ink layers findings](INK_LAYERS_FINDINGS.md), and
[frame handoff and latency](FRAME_HANDOFF_AND_LATENCY.md).
