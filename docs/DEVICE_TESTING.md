# AiPaper device testing

Target: full-size Viwoods AiPaper, firmware **3.15.15**, original pen and eraser,
right-handed use. The manufacturer lists Android 13; use **Settings → Device
details** in Fastnotes to record the actual installed Android version and build.

No tablet is attached to the build environment. All hardware checks below remain
pending until performed on the real device.

## First pass

1. Install the development APK and write a few short practice lines. Test dots,
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

Start with the default low-latency renderer. If ink is blank, flickers, or leaves
incorrect content, turn **Low-latency rendering** off to test the compatibility
renderer. Record whether the artifact appears in an Android screenshot, on the
physical display only, or both.

Viwoods panel acceleration is experimental and off by default. Only try it in a
full-screen window after confirming normal writing and saving work. It is
automatically suspended in split-screen and under dialogs or while unfocused.
If it behaves incorrectly, turn it off. No firmware modification, rooting, or
global device setting change is part of this application.

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
