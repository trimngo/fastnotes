# Fastnotes

A small Android handwriting notepad for the **full-size Viwoods AiPaper** that
keeps notes offline. **0.3.2-test4 is an investigation build**, not a claimed ink
fix. The user reports no live ink in either test3 buffer layout, while built-in
Viwoods Notes draws smoothly. Test4 compares **Worker refresh** and **Reference
callback**, both with full-display buffers. The latter presents and ends strokes
on the pen-callback thread and follows the inspected reference transaction order.
Actual full-size performance remains unverified.
See [the investigation and test procedure](docs/INK_INVESTIGATION.md).

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
- Viwoods direct pen callbacks enabled by default when the required firmware
  interfaces are present. Input immediately draws a preview; a separate worker
  coalesces presentation into at most one pending wakeup with 8 ms pacing. Recent
  dirty regions remain covered briefly so a skipped partial update can catch up.
- A separate presentation bitmap keeps native refresh calls off the pen-input
  lock. Pen-up rechecks the completed stroke before ending its transaction.
- Canonical stroke handoff after pen-up, explicit native writing transactions,
  clipped partial refreshes and temporary FAST display mode restored on exit.
- Android front-buffer input/rendering as a fallback. A silent native listener
  falls back after a short watchdog interval and replays held Android input.
- Native input is suspended when obscured, paused, unfocused, or using the Move
  tool. View geometry is captured on the UI thread and frozen for each session.
- Native startup retries when pane geometry becomes usable. Stable geometry does
  not restart the controller on every focus/layout callback. Firmware orientation
  follows the physical display, including when a narrow pane reports portrait.
- Device details includes native startup/visibility/timing diagnostics and private
  crash/Android process-exit evidence without note contents or exception messages.
- **Settings → Ink comparison test** opens a temporary scratch canvas in a separate
  process. It compares Android pen, Worker refresh and Reference callback, with native
  refresh requests clipped to the visible scratchpad. App-drawn native snapshots
  wait one second after pen-up. Choose **Smooth**, **Gaps / lag** or **No live ink**
  before Copy report. **Updates** is directly accessible from this screen.
  The additional **Fastnotes Ink Test** launcher icon opens that test directly,
  so the existing note renderer need not run first.
- Automatic checks for GitHub app updates, including development releases, with
  downloads and Android installation available inside the app.

Tap the sheet title to rename it. Use **Sheets** to create or reopen a sheet.
**Erase** removes whole strokes; **Undo** restores them. Line spacing changes the
paper background and does not reposition handwriting. Resizing fits the same
document width to the available window; it does not reflow written content.

## Install and test

Use the signed development APK attached to the project's GitHub development
release. Download it in Chrome on the AiPaper and open the APK to install it.
Android may ask you to allow APK installation from Chrome or the file manager.
Future APKs must use the same application ID and signing key to update in place.
Install over an earlier version to preserve saved sheets; uninstalling is not needed.

After installing 0.3.0 once, Fastnotes checks for new versions when opened, at
most once a day. An **Update** button appears when a version is available. Tap
**Download & install**, allow installation from Fastnotes if Android asks, then
confirm the installation. **Settings → App updates** offers **Check now** and
an option to disable automatic checks. Downloads use the network; writing and
local saving work offline. See [update behavior and publishing](docs/UPDATES.md).

Open **Settings → Device details** to see the actual Android version, model and
system build. Input timing shown there measures delivery to the application,
**not** pen-to-visible-ink latency.

The footer identifies **Viwoods native**, **Fast ink paused**, or **Android ink**.
When Chrome has focus, tap Fastnotes to resume its native route. Settings includes
**Retry fast ink** to retry a failed native start after closing the dialog.
Device details shows
native callback/sample/refresh counts and any fallback reason. Check these values
after a short writing test to establish which route actually ran.

Follow [the device test guide](docs/DEVICE_TESTING.md), particularly Chrome
split-screen, both landscape rotations, palm rejection, erasing and reopening.

## Data

Notes are stored in the application's private internal storage. The INTERNET
permission is used only to check GitHub releases and download app updates.
The REQUEST_INSTALL_PACKAGES permission lets Android offer installation with
the user's approval. There is no analytics, recognition service, synchronization,
or shared storage permission. Notes are never sent with update requests or logged.
Failure reports stay private on the device and include exception types/stack
locations, not exception messages. Copying Device details is an explicit user action.
Automatic backup and device
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

For release delivery, a signed APK can be staged on a separate builds branch
and attached by the Publish signed development APK workflow. That workflow
checks its SHA-256 and does not build, re-sign, or receive a signing key.

This is a sideloaded device app with target SDK 30 for the documented Viwoods
ENote compatibility context, not a Play Store submission. See
[ink backend details](docs/INK_BACKENDS.md) for the evidence and support boundary.
Android 13 can report that the APK targets an older Android version: API 30 is
Android 11. This compatibility target is intentional for the current native-ink
test and is separate from the tablet's actual Android 13/API 33 version.

## Verification

The test suite covers document transforms, sparse eraser sweeps, editing history,
save corruption and interruption recovery, multi-pointer stylus input, cancellation,
window boundaries, palm scrolling, activity recreation, and rasterized layouts.
Robolectric tests exercise Android 13 APIs and Skia rendering; they cannot establish
real e-ink latency, GPU front-buffer behavior, or vendor firmware compatibility.
