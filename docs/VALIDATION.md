# Version 0.3.0 app-update validation

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
