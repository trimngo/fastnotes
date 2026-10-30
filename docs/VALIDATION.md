# Version 0.1.0 validation

Validated in the prepared Linux build environment on 30 October 2026.

## Software checks

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease \
  -PscreenshotDir=/workspace/work/fastnotes-ui
```

Result: **BUILD SUCCESSFUL**.

| Test group | Passed |
| --- | ---: |
| Document geometry, erasing and editing history | 4 |
| Binary format, corruption and interrupted-save recovery | 6 |
| Android stylus, eraser, cancellation, palm and scroll input | 9 |
| Experimental panel API contract and clipped dirty rectangles | 2 |
| Cold start, activity recreation and native-Skia UI rendering | 3 |
| **Total** | **24** |

Zero failed, errored or skipped tests. Lint: zero errors, 17 warnings concerning
the deliberate experimental private API use, English UI strings, and newer
available dependency versions. Target SDK 30 and its Play Store-policy lint
exemption are deliberate for this sideloaded device app; see INK_BACKENDS.md.

Rasterized portrait (1200×1600), landscape (1600×1000) and narrow-window
(600×1000) previews were inspected. Ink/paper pixel checks pass. These previews
use the compatibility canvas under Android 13 Robolectric/Skia; they are not
photographs or screenshots from an AiPaper.

## Development APK

- Application ID: `dev.fastnotes`
- Version: `0.1.0` (code 1)
- Minimum Android API: 29; target API: 30
- APK v2 signature verification: passed
- Declared permissions in the packaged manifest: none
- SHA-256: `d5275e0ea6af955fa773f76f130604e539575aa028c425eee62423dbf51e7137`
- Development signing certificate SHA-256:
  `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

The development signing key stays outside the repository. The release variant
was compiled as a separate unsigned build; the installable download is the
signed development APK.

## Pending hardware evidence

No AiPaper is attached, and no physical stylus, e-ink panel, firmware interface,
or Chrome split-screen session was exercised. In particular:

- The Android front-buffer GPU/surface path still needs an on-device check.
- The experimental Viwoods display adapter is off by default. The API contract
  fake proves only application-side clipping and cleanup logic, not real firmware
  behavior or native library compatibility.
- Actual end-to-end handwriting latency requires a physical display measurement.
- Rotation, original-pen erasing, palm contacts and reopening after a device
  restart need real-device confirmation.

Follow DEVICE_TESTING.md and record the actual Android/build details shown in
the app. The expected Android 13 version and full-size 3.15.15 firmware integration
must not be inferred from Mini results.
