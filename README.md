# Fastnotes

A small, offline Android handwriting notepad for the **full-size Viwoods AiPaper**.
Version 0.1.0 is a first device-testing build. Full-size panel latency and firmware
3.15.15 compatibility still require testing on the actual tablet.

## In this version

- Portrait, landscape, and resizable Android split-screen windows.
- A vertically scrolling lined sheet, adjustable line spacing and pen width.
- Stylus-only writing, original-pen eraser input, and an on-screen stroke eraser.
- Undo and redo. Each pen or eraser gesture is one undo step.
- Palm guard: two fingers scroll; fingers never draw. With palm guard off, one
  finger scrolls. The **Move** tool lets the pen or a finger scroll, and the bottom
  arrows work without touch gestures.
- Multiple named sheets, automatic local saving, and reopening the last sheet.
- Atomic saves, checksums, bounded decoding, and recovery of the previous saved
  copy if the latest file is damaged.
- Android front-buffer rendering by default, unbuffered stylus dispatch and
  historical sample processing. A compatibility renderer is available in Settings.
- An opt-in, experimental Viwoods bitmap/panel adapter. It is disabled in
  split-screen and when obscured, paused, or unfocused. It has not been validated
  on the full-size device.

Tap the sheet title to rename it. Use **Sheets** to create or reopen a sheet.
**Erase** removes whole strokes; **Undo** restores them. Line spacing changes the
paper background and does not reposition handwriting. Resizing fits the same
document width to the available window; it does not reflow written content.

## Install and test

Use the signed development APK attached to the project's GitHub development
release. Download it in Chrome on the AiPaper and open the APK to install it.
Android may ask you to allow APK installation from Chrome or the file manager.
Future APKs must use the same application ID and signing key to update in place.

Open **Settings → Device details** to see the actual Android version, model and
system build. Input timing shown there measures delivery to the application,
**not** pen-to-visible-ink latency.

Follow [the device test guide](docs/DEVICE_TESTING.md), particularly Chrome
split-screen, both landscape rotations, palm rejection, erasing and reopening.

## Data

Notes are stored in the application's private internal storage. There is no
INTERNET permission, analytics, recognition service, synchronization, or shared
storage permission. Note contents are never logged. Automatic backup and device
transfer are excluded. This version does not implement note encryption or export:
**uninstalling or clearing app data removes your notes**. Keep important work in
your existing notes app until device testing is complete.

## Build

JDK 17, Android SDK 36 and build tools 36.0.0 are required. The project pins Gradle
8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21 and AndroidX Graphics 1.0.4.
The runtime minimum is Android 10 (API 29).

```sh
# In the prepared cloud environment:
source /workspace/tooling/env.sh

# Elsewhere, set JAVA_HOME and ANDROID_HOME for your local toolchain.
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Preserve the development
signing key outside the repository. Keys, APKs, local settings and build output
are ignored by Git.

This is a sideloaded device app with target SDK 30 for the documented Viwoods
ENote compatibility context, not a Play Store submission. See
[ink backend details](docs/INK_BACKENDS.md) for the evidence and support boundary.

## Verification

The test suite covers document transforms, sparse eraser sweeps, editing history,
save corruption and interruption recovery, multi-pointer stylus input, cancellation,
window boundaries, palm scrolling, activity recreation, and rasterized layouts.
Robolectric tests exercise Android 13 APIs and Skia rendering; they cannot establish
real e-ink latency, GPU front-buffer behavior, or vendor firmware compatibility.
