# AiPaper device testing

Target: full-size Viwoods AiPaper, firmware **3.15.15**, original pen and eraser,
right-handed use. The user confirmed Android 13/API 33. Use **Settings → Device
details → Copy** in Fastnotes to copy the model, build and native-route counters.

No tablet is attached to the build environment. Version 0.1.0 was tested by the
user: writing was very laggy and the bitmap-only acceleration option produced
brief static squares in full-screen portrait. Version 0.2.0 changes that path
to direct native input. The user's 0.3.0 photos confirmed native callbacks and FAST
mode, but showed rapid strokes with live gaps that filled after a delay, plus
Android fallback/slow refresh and a reported exit in landscape split-screen.
Version 0.3.1 changed refresh pacing and pane startup; the user reports similar
problems remain. The 0.3.2-test1 report identifies an unsupported API assumption
in that diagnostic, before it could start firmware preview.

## First pass

1. Install the development APK over the existing app, without uninstalling.
   Start full-screen in portrait and check that the footer says **Viwoods native**.
   Write a few short practice lines. Test dots,
   quick loops, long diagonal strokes and rapid pen-down/pen-up movements.
2. Flip the original pen to its eraser, or use its eraser button if that is the
   pen's mechanism. Verify that it removes whole strokes and that Undo restores
   them. Return to the nib and write again.
3. Rest your right palm on the sheet while writing. With palm guard on, your palm
   should neither add ink nor move the sheet. Move the pen away, use two fingers
   to scroll, then test the arrows and the Move tool.
4. Change line spacing in Settings. Existing handwriting should stay where it is.
5. Rotate through portrait and both landscape directions, including while a note
   is open. Resize repeatedly. Lines and existing ink should move together.
6. Put Chrome and Fastnotes in Android split-screen, with the German PWA open in
   Chrome. Test different window sizes, keyboard appearance in Chrome, switching
   focus, dragging the divider and rotating. Ink must stay inside Fastnotes.
7. Scroll down, write, and return to the top. The earlier content must remain.
8. Wait for **Saved on this device**. Background the app, reopen it, and check the
   note. Then force-stop and reopen it; repeat after a tablet restart.
9. Create a second sheet using Sheets, rename it, switch between sheets, and
   confirm each one's content and scroll position survive reopening.
10. Repeat the continuous rapid up/down stroke from the supplied photos. Observe
    whether segments appear while the pen is moving, after the pen stops, or only
    after a delay. Check the last few millimeters of a stroke without moving again.
11. In landscape, place Chrome on the left and Fastnotes on the right. Tap Chrome,
    then tap Fastnotes: **Fast ink paused** should return to **Viwoods native**.
    Move the divider and repeat with a narrow pane. If it stays on Android ink,
    use **Settings → Retry fast ink**, close Settings, and copy Device details.
    Include last native start, pane/panel orientation, visible bounds and fallback.

## Rendering

Viwoods fast ink is on by default when its native callback API is present. After
a short writing test, open **Settings → Device details → Copy**. Native callbacks,
accepted samples and panel updates should be nonzero. Last native picture mode
should be 4 (FAST). The app pauses native input under this dialog, so its status
may say paused even when the writing test used the native route.

In 0.3.1, diagnostics also show the paced refresh policy and longest native refresh
call. That duration measures the Java/native call, not visible panel completion.
If the app closes, reopen it and copy Device details to capture Android's exit
reason and any recorded Java stack locations. Reports stay private until copied;
neither exception messages nor note content are recorded.

Compare the delay and static squares against 0.1.0 and ForestNote. If native
samples stay at zero, copy the API/fallback reason rather than assuming the
native route is working. A silent listener is automatically disabled and held
Android input is replayed so it does not swallow the first stroke.

Try **Viwoods fast ink** off to compare Android input, and the **Low-latency
rendering** switch to compare Android front-buffer and compatibility rendering.
Record whether an artifact is visible in a screenshot, on the physical display
only, or both. No firmware modification or rooting is involved. The app restores
the previous display mode when native ink stops.

## Measure actual latency

Use high-speed video that shows both the pen tip and the e-ink display. Count
frames between physical contact/movement and the corresponding visible ink.
Record the camera frame rate, firmware, orientation, window size, renderer and
rough range over multiple strokes. Compare against the native Viwoods notes app
under the same conditions.

The app's input event age is useful for finding delivery delays but does not
include panel refresh latency. A successful build, JVM test, screenshot or Mini
benchmark does not establish full-size handwriting performance.

## Report a problem

Include Android/model/build from Device details, selected renderer, orientation,
whether Chrome split-screen was active, pen/eraser/palm actions, and a short set
of reproduction steps. Use a fresh test sheet so any shared screenshots or video
do not contain private notes.

## App updates (0.3.0 onward)

Install 0.3.0 manually once. Open **Settings → App updates → Check now**. After a
newer build is published, the main screen should show **Update**. Select
**Download & install**, deny installation permission once, retry and allow it,
then confirm the Android installer. Cancel/retry the installer as well. Check
rotation and leaving/reopening during a download, and that existing sheets still
open after updating. Disable automatic checks and verify offline writing/saving.

## Current step: Ink layers comparison (0.3.2-test12)

The new surface capture is usable: the user drew new strokes on an existing
Notes page. Fastnotes produced 928 system-policy callbacks and an init attempt;
no filtered queue errors establish a cause. Separate static inspection found
reserved native FE/FD colours, and real integer-coordinate raster tests reproduce
them on opaque white while transparent black avoids them. See
[the findings and limits](INK_LAYERS_FINDINGS.md).

1. Update through **Ink Test → Updates → Check now**, including from test6.
2. Open **Fastnotes Ink Test → Ink layers**. Use the pen tip and draw rapid
   continuous strokes in portrait. Judge only ink visible **while moving**.
3. Lift and wait. This mode deliberately retains the one-second quiet Android
   snapshot, so completed ink alone does not demonstrate native live drawing.
4. Compare **Worker refresh** under the same conditions. Select the observed
   result and **Copy report**. The report identifies the transparent pixel mode.
5. If live continuity improves, then check landscape placement and clipping.
   Reselect Ink layers after a native comparison stops on rotation/focus change.
   Notebook/eraser integration requires separate validation after this pen test.

No extra USB capture is required just because the Notes page was nonempty. The
new mode is isolated from notebook defaults and introduces no firmware setting
or refresh-timing change.

## Completed step: updated USB surface capture

The original test6 also fails to show live ink. Its report confirms accepted
registration, the earlier canvas geometry and prompt input. Keep test6 installed
and run the [updated surface capture](USB_INK_DIAGNOSTICS.md). At each prompt,
start capture before selecting System ink or switching into built-in Notes, so
initialization and ownership handoff are included. The source rollback result
does not by itself establish which external state or drawing contract is missing.

## Completed step: actual test6 APK comparison

Test11 still shows only pen-up completion according to the user. Use the
[exact test6 comparison](EXACT_TEST6_COMPARISON.md) through the existing Mac/Linux
USB connection. Both packages are debug builds with unchanged note storage, so
an in-place debug downgrade can be attempted without uninstalling. Reinstall
test11 afterward and compare under the same process-reset condition. The
updated [USB collector](USB_INK_DIAGNOSTICS.md) also captures filtered errors
from the selected surface backend. No new handwriting APK is claimed as a fix
from this report alone.

## Earlier restoration attempt (0.3.2-test11)

The user reports Worker refresh still skips and prefers the earlier System ink
live feel, despite its pen-up disappearance. Test10's backend block was based on
an incomplete analysis: scoped registration also initializes the firmware
writing surface. Test11 removes that block and restores test6's manual request
order/layout, combining it with immediate completed replay and later focus cleanup.

1. Update in place through **Ink Test → Updates → Check now**. Open the separate
   **Fastnotes Ink Test** launcher icon directly and select **System ink**.
2. In portrait, check rapid continuous strokes **while the pen moves**, then
   lift and wait a few seconds without touching anything. Completed ink should
   remain available. Record any disappearance, shape change or first-stroke delay.
3. Select the observed result and **Copy report**. The backend remains diagnostic;
   an accepted request still says visible ink is unverified.
4. If the preferred live feel has returned, repeat landscape and Chrome split-
   screen, switching focus and resizing. Check completed ink and automatic return.

System DOWN/MOVE now capture bounded primitive x/y/pressure and segment breaks;
the estimated completed pressure outline is built only at UP. The app does not
draw its moving System path. This preserves a useful live-preview comparison.
It is not an exact test6 source rollback, because completion/focus improvements
are retained. No physical recovery or pressure-curve equivalence is claimed.

## Earlier SoftSolution buffer fix (0.3.2-test10)

USB captured 982 System policy callbacks in Fastnotes and none of that marker
in built-in Notes. The user confirmed `ro.eink.type=SoftSolution`; this backend's
System auto-draw implementation is empty. Inspecting the correct native bitmap
route identified a foreground/background registration bug, corrected in test10.
See [the exact findings](SOFTSOLUTION_FINDINGS.md).

1. Update in place using **Ink Test → Updates → Check now**. Do not uninstall.
2. Select **Worker refresh**, which uses the native bitmap/surface path. Draw
   rapid continuous up/down strokes in portrait. Judge the ink **while moving**;
   the scratchpad's completed Android snapshot still waits one second after UP.
   Select the observed result and Copy report. Backend should say SoftSolution.
3. Check dots, pen-up continuity and the first stroke after a short pause. Then
   compare **Reference callback**, which uses the same corrected distinct layers
   while executing presentation on the callback thread.
4. Test the normal notebook, which receives the same buffer fix. Check the
   original pen/eraser, undo/redo, saving and reopening. Repeat both landscape
   rotations and Chrome-left/Fastnotes-right split-screen/focus return.

System ink now explains that it is unavailable on SoftSolution and keeps Android
fallback usable rather than accepting a drawing path that performs no draw.
This release corrects a proven native buffer contract; physical latency/gaps are
not established by software checks. No extra USB capture is required before
testing this change. Existing [USB instructions](USB_INK_DIAGNOSTICS.md) remain
available for further diagnosis if the actual native renderer still fails.

## Source rollback attempted (0.3.2-test9)

The user reports test8 shows ink only after pen-up, with hover preparation both
on and off. This is a live-ink regression; the completed Android replay does not
establish working firmware presentation. Test9 restores the exact test7 writing
Activity/view/layout and keeps test8's driver export. No new rendering or timing
variation is introduced in this recovery build.

1. Update through **Ink Test → Updates → Check now** and select **System ink**.
2. Begin in portrait and check for complete ink **while the pen is moving**.
   Then lift and check that completed ink remains visible. Record the result and
   **Copy report**, including a failed/paused status if live ink is still absent.
3. If live drawing has returned, repeat landscape and Chrome-left/Fastnotes-right
   split-screen. Check focus return as in test7. First-stroke idle hesitation is
   still an open issue; do not infer a fix from this rollback.
4. **API files → Save ZIP** still collects driver metadata and readable matching
   modules for exact pressure/driver analysis. It runs only on explicit export.

The writing source files are identical to test7. That is software evidence, not
proof of physical recovery: native service state, queued region application and
the actual panel remain device-side. A report from the failing test8 session is
useful for distinguishing paused registration from failed live presentation.

## Earlier idle-start experiment (0.3.2-test8, live-ink regression)

The user confirms test7 completed ink stays visible. They now report a first-
stroke hesitation after a pause without changing focus; built-in Notes starts
immediately. The native-file investigation found downstream driver pressure
handling and a system motion filter, not a safe per-app filter control.
See [native findings and limits](NATIVE_INK_FINDINGS.md).

1. Update through **Ink Test → Updates → Check now** and select **System ink**.
2. With **prepare after idle on hover** checked, write, lift the pen, pause for
   one or two seconds, then bring the pen back over the sheet and write. Repeat
   several times. Observe the first visible mark while the pen moves.
3. Uncheck the option and repeat under the same conditions. Keep focus unchanged
   for this comparison. It requests one completed-ink frame per idle period,
   not a change to the native engine or a continuous refresh loop.
4. **Copy report** after each comparison. Include whether a first-stroke delay
   remains. The new idle history shows Android event intervals and registration/
   hover-request ages; these are not visible panel latency measurements.
5. For driver inspection, open **API files → Save ZIP**, choose Downloads and
   Save, then attach the new ZIP. It now includes driver metadata and any matching
   readable module, without rooting or boot-image access. Availability is not
   guaranteed; an inventory-only driver result is still useful.
6. Repeat in landscape split-screen after the single-pane check. System ink
   should still resume on focus return, and hover preparation must remain off
   while Fastnotes is unfocused or cleanup is pending. Also test pen-up continuity.

High-speed video showing the pen tip and first visible ink is the useful way to
measure an improvement. No faster result is inferred from successful redraws or
shorter app input timings. This remains a temporary scratchpad; the normal note
renderer, saved-note format and native drawing engine are unchanged.

## Earlier System ink completion, pressure and focus (0.3.2-test7)

The user reports test6's live writing works quite well. Ink disappears roughly one
second after pen-up, then returns without the original pressure variation. The
firmware schedules native overlay clearing after 800 ms; test6 deliberately held
app replay until one second of quiet and used a fixed 3 px path. Those are concrete
handoff/appearance problems in that diagnostic, not evidence that live ink failed.

1. Update through **Fastnotes Ink Test → Updates → Check now**, then select
   **System ink**. Write a fast continuous stroke and lift the pen.
2. Observe whether completed ink now stays visible through the next second.
   Write short strokes rapidly in succession, then pause. Existing completed
   strokes should remain while new strokes are provided by the firmware.
3. Draw a light-to-firm-to-light stroke. Check whether width variation remains
   after pen-up, and report any change in position, thickness, shape or a flash.
   App replay uses Android pressure with an estimated 2–8 px range. The native
   curve/units are not recovered, so exact brush matching is not claimed.
4. Select the observed live-ink result, **Copy report**, and include what happened
   after pen-up. The report now distinguishes immediate completion from older
   delayed modes and includes only pressure counts/range, not stroke coordinates.
5. Repeat in landscape and Chrome-left/Fastnotes-right split-screen. Tap Chrome,
   then return to Fastnotes. System ink should pause while unfocused, keep its
   selected mode and completed scratch ink, and resume automatically once the
   pane has focus and stable bounds. Resize the divider and repeat. Explicitly
   selecting Android pen or another test/navigation clears that resume intent.
6. To investigate the exact native brush, open **API files → Save ZIP**, choose
   Downloads and Save, then attach the new ZIP. The prior export omitted native
   libraries because its code budget was consumed by DEX. Test7 prioritizes those
   fixed library names and increases the combined code budget to 22 MiB; the
   final ZIP remains limited to 24 MiB. Native files may expose the curve or may
   delegate pressure handling elsewhere; exact recovery is not guaranteed.

System ink now asks Android to prepare completed ink at pen-up; the firmware may
still defer physical presentation. No current in-progress stroke is app drawn.
Worker/reference comparisons retain their one-second quiet snapshots. The new
handoff is still an in-memory scratchpad: saved notes and their format are
unchanged. Original-pen eraser/notebook integration remains separate.

## Earlier system-owned ink probe (0.3.2-test6)

The uploaded firmware ZIP supplied real framework and service implementations.
The view API tested in test2 is a no-op. Test6 uses the actual system-service
region protocol on the inspected full-size AiPaper/API 33/build 3.15.15 only.
See [the exact findings and protocol limits](EXACT_FIRMWARE_FINDINGS.md).

1. Update through **Fastnotes Ink Test → Updates → Check now**.
2. Start full-screen in portrait. Select **System ink** and draw the rapid
   continuous up/down pattern. Observe ink **while the pen moves**. App-drawn
   fallback waits one second after pen-up; the firmware can clear its temporary
   overlay after 800 ms, so a brief gap after pen-up is not the live-ink test.
3. Select **Smooth**, **Gaps / lag** or **No live ink**, then **Copy report**.
   Include a failed connection/registration report if no test starts. A returned
   service call is reported as an accepted request, never measured smoothness.
4. If full-screen works, repeat in landscape, then landscape split-screen with
   Chrome on the left. Tap Fastnotes and select **System ink** after each focus,
   rotation or divider change; this test deliberately does not restart itself.
   Check that ink stays inside the scratch area and does not appear over Chrome
   or controls. Stop if it draws outside the requested area and report that.
5. Open Updates or return from the scratchpad and check that system ink stops.
   No saved sheets are opened or changed. Original-pen eraser integration and
   notebook promotion remain separate from this first live-ink probe.

Cleanup requests removal of the same rectangle on focus loss, pause, resize and
exit. Native cleanup runs asynchronously after the service accepts removal. Failed
cleanup ownership survives Activity recreation in the same process. It remains
visible and blocks another test, Android ink, Updates and export
until removal succeeds. The ROM contains an app-death cleanup hook, but its
upstream invocation is not established by the captured classes; crash cleanup is
not guaranteed by these software checks. No global drawing, pen-width, tool,
refresh-mode or exclusion settings are changed.

## Earlier step: firmware API evidence (0.3.2-test5)

Both test4 modes remain Gaps / lag and the user confirmed they look essentially
the same. The reference's thread IDs match the intended execution, but it has not
improved perceived writing. Keep the renderer unchanged while inspecting the
exact full-size firmware implementation.

Update through **Ink Test → Updates → Check now**. Open **API files → Save ZIP**,
choose **Downloads** in Android's picker, and Save. Attach the resulting
**fastnotes-api-evidence.zip** in the investigation conversation. The ZIP contains
readable system API code/inventory and the ink report, without note files or
scratch coordinates. An inventory-only result is explicitly identified and is
still useful to share. There is no automatic upload or new storage/root permission.

See [collection details](FIRMWARE_API_EVIDENCE.md).

## Earlier reference-sequence comparison (0.3.2-test4)

0.3.1 remains unsatisfactory on the physical device and in ForestNote. Test2's
view request returned, but the user observed ink only after lifting the pen and
waiting: only the Android fallback was seen. See [actual evidence and the buffer
experiment](INK_INVESTIGATION.md). Test3 also produced no live ink with either
buffer layout. Built-in Viwoods Notes draws smoothly. Test4 compares the reference
callback sequence with the existing worker policy, both with display buffers.

1. Open **Fastnotes Ink Test** directly. Use **Worker refresh** for the same rapid
   up/down pattern. After writing, choose **Smooth**, **Gaps / lag**, or **No live
   ink** according to what appeared while the pen moved.
2. Select **Reference callback**, repeat the same pattern and choose its result.
   App snapshots are intentionally held until one second after pen-up in both
   modes. Delayed completion alone is not live native drawing.
3. Tap **Copy report**. Both comparisons, geometry, native counts and selected
   observations are included. No scratch handwriting is written into the report.
4. Repeat in landscape with Chrome left/test right. Select a mode again after
   changing focus or geometry. Native refresh requests must stay within the
   scratchpad. Compare the stock Viwoods Notes app under the same conditions.
5. The test now has a direct **Updates** button. It stops the native comparison
   and opens the normal updater without requiring access to Settings.
