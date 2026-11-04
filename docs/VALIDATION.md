# 0.3.2-test6 system ink service validation

Software checks performed on 4 November 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **102 tests passed**, no failures, errors or skips. Lint has no
errors (43 warnings and one hint); debug/release variants compile. Nine new cases
verify the inspected system-service protocol and its lifecycle:

- Exact token, typed Rect, transaction codes and synchronous flags; immutable
  copies preserve registration/removal equality after caller mutation.
- Unknown model/build/API, empty/invalid area, missing service and wrong interface
  descriptor fail before registration. Unhandled transactions cannot succeed.
- A failed add reply rolls back a possible server effect. Failed removal retains
  the same binder/rectangle and prevents another registration until retry works.
  Exception messages do not enter reports.
- Activity tests register the visible right-hand pane, preserve geometry during
  status changes, record the observation, and remove on focus loss, resize and
  Updates without opening notes or starting an app-native bitmap listener.
- Unconfirmed cleanup blocks Android/worker/reference/system tests and Updates/
  API export. Retained Application ownership survives Activity recreation in the
  same PID and prevents duplicate registration until removal succeeds.

Independent review checked the Binder schema against the user's exact firmware
and reviewed ownership retention without Activity references. The view API from
test2 is confirmed empty; System ink uses the implemented per-PID server route.
Vendor code remains outside this repository and APK. Removal replies acknowledge
service acceptance; native cleanup runs asynchronously. Platform access, visible
ink quality, clipping, native cleanup timing and landscape/split-screen behavior
remain unverified on the physical tablet. No automatic crash-cleanup claim is made.

The notebook and two earlier bitmap policies remain unchanged. Original-pen eraser
integration and durable notebook handoff for this route are not part of the probe.
No new permissions, native binaries, app-side ENoteWriting initialization, global
AutoDraw/tool/width/exclusion/refresh-mode settings or platform policy changes.

## Investigation APK

- Application ID `dev.fastnotes`; version `0.3.2-test6` (code 10)
- Minimum API 29; target API 30; note format 1; same signing certificate
- System protocol accepts only Viwoods AiPaper / API 33 / build 3.15.15
- APK bytes: `7991768`
- APK SHA-256: `2aedb518b1f89d3740107bd11bb7c385c203dcedd373344ae13817484c2ff82b`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [exact-device findings](EXACT_FIRMWARE_FINDINGS.md) and
[the physical test procedure](DEVICE_TESTING.md).

---

# 0.3.2-test5 firmware API evidence validation

Software checks performed on 4 November 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **93 tests passed**, no failures, errors or skips. Lint has no
errors (40 warnings and one hint); debug/release variants compile. Eight export
cases verify:

- Actual DEX class definitions select implementation units; mere references,
  malformed tables and unrelated units do not. Known service/AutoDraw definitions
  are recognized alongside ENote classes.
- Canonical system-root filtering rejects personal-file candidates and symlinks
  resolving outside those roots. Exported code has SHA-256 metadata.
- Code-entry/export limits yield a usable inventory/report ZIP when code cannot
  be included. Stripped framework archives can use readable known VDEX evidence.
- The destination is opened only after the user's document-picker result.
  Rotation cannot launch duplicate pickers, and finalized cache recovery after
  process death requires matching firmware/API/app versions.
- The private export activity stays in the ink-test process; it does not open
  saved sheets or initialize the editor. Stale-firmware cache is rejected before
  opening the chosen destination.

Independent code review found no release blocker. One non-blocking hardening
limit remains: scan accounting trusts declared ZIP entry sizes before reading;
malformed system archives can cause an attempted read beyond the remaining scan
budget, while each read and all exported code retain their own size limits.

The user confirmed test4 Worker refresh and Reference callback looked essentially
identical, both with gaps/lag. The notebook renderer and native policies are
unchanged. This update collects evidence for the next investigation; it does not
claim a handwriting-latency or split-screen fix. Exact-ROM file readability and
the physical document-picker flow remain unverified without the tablet.

## Evidence APK

- Application ID `dev.fastnotes`; version `0.3.2-test5` (code 9)
- Minimum API 29; target API 30; note format 1; same signing certificate
- Ink Test → API files → Save ZIP uses Android's document picker
- No new permissions; no automatic upload; no notes or scratch coordinates
- APK bytes: `8057332`
- APK SHA-256: `88ad35dd7f3e10f146dafbd1328d516b26faef4f4caf3d3b03510683fe26c8da`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [export contents and procedure](FIRMWARE_API_EVIDENCE.md) and
[negative hardware comparisons](INK_INVESTIGATION.md).

---

# 0.3.2-test4 reference callback sequence validation

Software checks performed on 30 October 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **85 tests passed**, no failures, errors or skips. Lint has no
errors; debug/release variants compile. Two new reference-policy tests and the
updated buffer/activity cases verify:

- No presentation scheduler is created for the callback policy. Start, bitmap
  binding, each dirty-region render and transaction end occur on the actual
  injected input thread, with no per-DOWN enable transition.
- Stroke end precedes UI notification; draining/acknowledging cannot end it again.
- Immediate bitmap presentation, visible-region clipping, outside-input rejection
  and cancellation restoration remain correct under that policy.
- Existing quiet frozen-snapshot, buffer placement, stale-callback, observation,
  focus/resize and direct Updates cases cover the new comparison as well.

The user confirmed no live ink in **both** test3 buffer layouts, while built-in
Viwoods Notes draws the same pattern smoothly. Test4 addresses concrete execution/
transaction differences from the inspected reference, rather than treating bigger
buffers or successful refresh calls as a solution. Its actual visual performance
is unverified. This is an investigation build, not a claimed latency/crash fix.

## Investigation APK

- Application ID `dev.fastnotes`; version `0.3.2-test4` (code 8)
- Minimum API 29; target API 30; note format 1; same signing certificate
- Separate scratchpad offers Worker refresh and Reference callback with full-
  display buffers, explicit visual results and callback/render/end thread IDs
- Notebook default stays Worker; scratch edits never reach NoteStore
- APK bytes: `7840176`
- APK SHA-256: `2cac21a25a5be99d2b72ffcc15d4b5454b572cc2fcb9795ab0c105dadf7df764`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [negative hardware results and the audited reference sequence](INK_INVESTIGATION.md).

---

## Earlier 0.3.2-test3 native buffer comparison validation

Software checks performed on 30 October 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **83 tests passed**, no failures, errors or skips. Lint has no
errors; debug/release variants compile. Added/updated regressions verify:

- Pane and full-display native buffers use their intended dimensions/origins,
  map the same screen samples to the scratchpad and keep all requested refreshes
  inside a partially visible right-hand canvas.
- Native completion works without Android events; queued completion from an old
  controller cannot affect a replacement.
- Invalid/oversized geometry is rejected before native initialization, and stop
  clears listener/bitmap registrations while retaining only diagnostic counters.
- Snapshot capture requires pen-up and a full quiet interval under the input
  lock. Android redraws keep a frozen image during a later native stroke, even
  if a duplicate Android DOWN never receives an UP.
- Activity status changes preserve buffer geometry; focus/resize/Updates stop
  native drawing, user observations enter Copy report and reset for a new test,
  and opening Updates does not open saved notes.

The user's test2 result is now confirmed negative: only the delayed Android
fallback appeared after pen-up. That result is recorded in the investigation.
Test3 tests a buffer-placement hypothesis motivated by inspected stock WiNote
code, using the same native callback/refresh implementation for both modes. It
does **not** establish real-device latency, a working alternative backend or
landscape split-screen reliability. No tablet is attached to this environment.

## Investigation APK

- Application ID `dev.fastnotes`; version `0.3.2-test3` (code 7)
- Minimum API 29; target API 30; note format 1; same signing certificate
- Scratch test remains in `:inktest`; normal notebook rendering uses its previous
  default behavior. The controller's quiet-snapshot guard is opt-in for diagnostics.
- No saved notes or scratch coordinates are written into diagnostic reports
- APK bytes: `7928956`
- APK SHA-256: `6038eff6eeee57a750b2cbecd3a859447c4ba1f8337ab218a42f34ad855a889c`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [hardware evidence and the comparison procedure](INK_INVESTIGATION.md).

---

## Earlier 0.3.2-test2 actual-firmware view API validation

Software checks performed on 30 October 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **76 tests passed**, no failures, errors or skips. Lint has no
errors; debug/release variants compile. Seven added regressions cover:

- The reported 3.15.15 view-only surface, contextual factory selection, enabling
  the exact owned view and restoring readable settings without changing an
  unreadable global enable flag.
- Rejecting a detached view or missing view before vendor initialization.
- Cleaning up a request rejected after taking effect, exact failure-call/root-type
  evidence without exception messages, and retrying failed cleanup without
  enabling a second view.
- A right-hand-pane activity contract: status updates preserve active canvas
  geometry; focus loss and real pane resize disable the same view; focus return
  alone does not restart an experiment.

The user's test1 report demonstrates that its local rectangle-capability check
failed before any drawing request. Test2 corrects that assumption using
`setViewAutoDrawEnabled(View, boolean)`, exposed in the actual 3.15.15 report.
These software checks do **not** establish that this API produces visible ink or
resolves physical lag/gaps/exits. That hardware comparison is still pending.

## Investigation APK

- Application ID `dev.fastnotes`; version `0.3.2-test2` (code 6)
- Minimum API 29; target API 30; note format 1; same signing certificate
- Separate scratch test and delayed Android-ink comparison; normal note backend
  remains the previous implementation
- API/failure evidence stays local until Copy report; no saved notes opened by
  the test and no stroke coordinates written into reports
- APK bytes: `7888228`
- APK SHA-256: `53bfc054ec2319c497b029629f4cfc0e408704a039f355d0e5bf90cc388e2111`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [actual-device evidence and the corrected experiment](INK_INVESTIGATION.md).

---

## Earlier 0.3.2-test1 firmware-preview investigation validation

Software checks performed on 30 October 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **69 tests passed**, no failures, errors or skips. Lint has no
errors; debug/release variants compile. Eleven new tests cover:

- Read-only method inspection without vendor calls, exact scoped rectangles,
  restoring readable settings, cleanup after an indeterminate rejected request,
  missing APIs, leaving unreadable global toggles untouched, bounded registrations
  on release failure, and preserving settings changed elsewhere.
- Calling a non-public service proxy through its public typed interface without
  invoking the app-side native initializer.
- No Android-drawn ink before the documented one-second quiet interval in firmware
  test mode, versus immediate Android-mode drawing.
- Private diagnostic activity and isolated-process launcher metadata, preserved
  stored notes/no editor startup, and bounded persistent call-stage evidence.

These are contract/Skia tests. They do not establish that AutoDraw works on a real
full-size device or that Android process isolation prevents every possible vendor
or system-server failure. The release is an investigation build, not a new claim
that ink latency/skipping or crashes are fixed. The user's 0.3.1 feedback remains
negative, including similar problems in ForestNote. The stock Notes baseline and
actual 3.15.15 firmware-preview visual result are still pending.

See [primary-source findings and the device experiment](INK_INVESTIGATION.md).

## Investigation APK

- Application ID `dev.fastnotes`; version `0.3.2-test1` (code 5)
- Minimum API 29; target API 30; note format 1; same certificate as prior builds
- Existing normal note backend retained. New launcher **Fastnotes Ink Test** and
  Settings → Ink comparison test enter a temporary scratch test in `:inktest`.
- No saved-sheet data opened in that test; no stroke content written to reports
- APK SHA-256: `ec11fafd0d0fa487fce912e8bfac6cfe41e0c47050f6fac750ca745deab3a3f9`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

---

## Earlier 0.3.1 rapid-ink and split-screen validation

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
