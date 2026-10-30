# AiPaper device testing

Target: full-size Viwoods AiPaper, firmware **3.15.15**, original pen and eraser,
right-handed use. The user confirmed Android 13/API 33. Use **Settings → Device
details → Copy** in Fastnotes to copy the model, build and native-route counters.

No tablet is attached to the build environment. Version 0.1.0 was tested by the
user: writing was very laggy and the bitmap-only acceleration option produced
brief static squares in full-screen portrait. Version 0.2.0 changes that path
to direct native input and needs a fresh hardware check.

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

## Rendering

Viwoods fast ink is on by default when its native callback API is present. After
a short writing test, open **Settings → Device details → Copy**. Native callbacks,
accepted samples and panel updates should be nonzero. Last native picture mode
should be 4 (FAST). The app pauses native input under this dialog, so its status
may say paused even when the writing test used the native route.

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
