# Version 0.3.1 rapid-ink and split-screen validation

Software checks performed on 30 October 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Result: BUILD SUCCESSFUL. **58 tests passed**, no failures, errors or skips.
Lint has no errors; debug and release variants compile. The existing 47 tests
remain green with asynchronous presentation fixtures. Eleven new tests cover:

- A 300-sample burst uses one pending wakeup and presents its tail without more
  input; a last-request-wins consumer still sees recent segments before pen-up.
- Pen-up settles the entire gesture before ending writing; a blocked native
  refresh does not hold up MOVE callbacks or expose their live drawing canvas.
- Stopping cancels old-session work before a replacement session can render.
- A focused right-hand landscape pane starts native ink; repeated environment
  callbacks retain its listener, and focus loss/regain pauses/resumes it.
- Late visibility/position changes retry without requiring a size change; partial
  panes remain native with rendering/input constrained to their visible bounds.
- Physical display rotation determines firmware orientation independently of a
  portrait-shaped split-screen pane, including both landscape rotations.
- Bounded crash reports survive reopening and omit exception messages/private text.

The panel and multi-window tests use Skia/Robolectric and a firmware contract fake.
They verify app behavior, not actual panel queueing, performance, or native crashes.

## Device evidence and remaining check

The 0.3.0 photo shows Viwoods AiPaper, Android 13/API 33, system build 3.15.15,
16,232 native callbacks, 11,950 accepted samples, 5,978 updates, and FAST mode 4.
The user observed gaps during rapid handwriting that filled after a delay without
interaction. Landscape with Chrome left/Fastnotes right stayed on Android ink
after tapping Fastnotes. App exits were reported; ForestNote was also identified
as able to use fast ink there but unstable. No crash stack establishes a cause.

The 0.3.1 changes target unpaced partial-update traffic and transient window/
orientation handling. Re-test the same rapid pattern and landscape split-screen
on the physical tablet; do not describe the hardware skips or exits as resolved
until this is confirmed. Device details now includes startup bounds, panel/pane
orientation, refresh-call timing, a private Java failure report and Android exit
reasons where available. No tablet is attached to the build environment.

## Current APK identity

- Application ID: `dev.fastnotes`; version `0.3.1` (code 4)
- Minimum API 29; compatibility target 30; note format 1, all unchanged
- Same signing certificate as previous releases; APK signature verified
- APK SHA-256: `8e277394acf1aa20a9d57f26c2b49c5a4a01c1cea21eeee411060d3f0df0250c`
- Signing certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`
- Update through Settings → App updates → Check now, or install over the existing app

---

## Earlier 0.3.0 app-update validation

Software checks performed on 30 October 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Result: BUILD SUCCESSFUL. **47 tests passed**, no failures, errors or skips.
Lint reports no errors. Debug and release variants compile. The 33 existing
handwriting/storage/layout tests remain green; 14 new update tests cover:

- Development-release discovery, draft/incomplete release filtering, version-code
  comparison and preventing metadata/download URLs from other repositories.
- Wrong application metadata, unsupported schema/API, size limits and malformed
  hashes, interrupted/oversized/corrupt downloads and partial-file cleanup.
- Automatic check intervals, disabling automatic checks, manual retries, offline
  saving, restored notifications/downloads and main-thread state delivery.
- APK identity/version/signing-certificate checks, read-only installer grants,
  excluding note directories, permission denial/retry/grant and avoiding repeated
  installer launches after cancellation.

Updater HTTP and installer lifecycle tests use contract fakes/Robolectric. A real
Android permission prompt and a complete in-place update still need a tablet test.
No hardware ink performance claim is added by this release.

## Current APK identity

- Application ID: `dev.fastnotes`; version `0.3.0` (code 3)
- Minimum API: 29; compatibility target: 30 (unchanged for native ink)
- Same signing certificate as 0.1.0 and 0.2.0; APK signature verified
- Android permissions: INTERNET and REQUEST_INSTALL_PACKAGES; AndroidX also
  defines/uses an app-local signature permission for nonexported receivers
- APK SHA-256: `a51460dc2f255308d179bcd7315296d4d1aaa4dc01e760b1413223a5848f6b57`
- Signing certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`
- Note format: version 1, unchanged. Install over the previous app.

See [update flow and publishing](UPDATES.md) and [device testing](DEVICE_TESTING.md).

---

## Earlier 0.2.0 native-input validation

Software checks performed on 30 October 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Result: BUILD SUCCESSFUL. **33 tests passed**, none failed, errored or skipped.
Lint reports no errors. The signed debug APK and unsigned release variant compile.

| Test group | Passed |
| --- | ---: |
| Document transforms, erase/undo and native-to-Android ID continuity | 5 |
| Storage, corruption and interrupted-save recovery | 6 |
| Android stylus, eraser, cancellation and palm scrolling | 9 |
| Native callback thread, preview ownership, clipping, handoff, cancellation, erasing and cleanup | 8 |
| Native/Android duplicate prevention and silent-listener recovery | 2 |
| Cold start, activity recreation and Skia UI rendering | 3 |

The new native tests use a firmware API contract fake. They verify application
behavior, including immediate preview drawing without UI delivery, preserving a
newer stroke during an earlier stroke's handoff, restoring the previous picture
mode, and ignoring stale callbacks. They do not validate the real e-ink panel.

## Device evidence

The user confirmed Android 13/API 33 on full-size AiPaper firmware 3.15.15.
Version 0.1.0 was very laggy and its bitmap-only panel option produced transient
static squares in full-screen portrait. ForestNote somewhat worked on that device.

Version 0.2.0 implements the direct firmware callback topology inspected in
ForestNote's Viwoods backend, defaults to native input when its API is present,
and provides route/callback/accepted-sample/refresh diagnostics. It still needs
an on-device comparison to confirm latency and whether the squares are gone.
Start full-screen in portrait before testing rotation and Chrome split-screen.

## APK identity and update

- Application ID: `dev.fastnotes`
- Version: `0.2.0` (code 2)
- Minimum API: 29; compatibility target: 30
- APK signature verified; same certificate as 0.1.0
- Packaged permissions: none
- APK SHA-256: `c7aea826e472865733ae0dec6afd71bec520640fdaa78bbbc4beaeec11adfba9`
- Signing certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

Install over 0.1.0 to retain app-private notes. The note format remains version 1.
The older-Android message may remain because the native firmware test retains
API 30, the compatibility context documented by the community implementation.
This target is Android 11; the tablet itself is Android 13/API 33.

Use Settings → Device details → Copy after writing to capture the active-route
history and counts without note contents. Real pen-to-visible-ink timing remains
a physical high-speed-video measurement.
