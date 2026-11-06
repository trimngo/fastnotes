# 0.3.2-test14 prepared startup validation

6 November 2026. Full verification succeeded:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

**159 tests passed**, no failures, errors or skips. Lint has no errors (53 warnings
and 1 hint); debug/release compile. Thirteen new cases verify fresh drawing and
frame submission before native initialization, render-thread/UI ordering,
replacement/explicit cancellation, clear/mode/detach, software fallback,
offscreen preparation, focus/pause/resize cancellation, failed-start fallback
and preserved opaque control ordering. Activity fixtures explicitly capture
and fire framework frame-commit callbacks; manually drawing a software Canvas
alone does not complete a hardware-accelerated View's preparation.

The user's test13 result confirms both transparent comparisons draw live and
feel similar, with Ink layers perhaps faster. Their occasional first stroke
appears completely after a delay after selecting a mode. Test14 removes the
unconditional scratchpad reset/redraw after enabling native ink. It establishes
app draw/submission ordering, not physical panel readiness or a proven latency
improvement. Completion, sampling, pressure width and native firmware calls remain
unchanged. Independent review found no blocking lifecycle/cancellation issue.
See [startup findings](FRAME_HANDOFF_AND_LATENCY.md).

The corrected Settings exporter passed hosted checks on macOS and Ubuntu. The
user supplied its successful 8,397,077-byte archive; all manifest/DEX inventory
hashes matched. Static inspection recovered the conditional Rj bitmap callback
renderer and steel-pen pressure formula, with preset resources/current slider
values absent. No OEM code was executed or bundled. See
[installed Settings evidence](SETTINGS_PRESSURE_FINDINGS.md).

APK identity:

- `dev.fastnotes`, version `0.3.2-test14`, code 18; minimum API 29, target API 30
- Bytes: `7987672`
- SHA-256: `178005a60f32dd79b5bef9c0025345bcbeab11f4185b35346dd6fea9b7245a9a`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`
- Same package, permissions and note format; install over the existing app

# Settings exporter macOS compatibility correction

6 November 2026. The user's Mac stopped at the installed-APK path check with
`awk: nonterminated character class`, before any APK read. The regex literal
contained an unescaped slash in a bracket expression: mawk accepted it, while
One True Awk 20220912 reproduced the exact failure. A string-valued ERE now keeps
the same allowed locations/characters without that delimiter ambiguity.

The full exporter host fixture passes with mawk and One True Awk, including
allowed install paths, rejected private/traversal/metacharacter paths, foreground
fallback, read budgets, code-only output and failed reads. Bash syntax and diff
checks pass. A successful physical-device export remains pending. No Android
application source or APK changed; test13's app checks below remain applicable.

# 0.3.2-test13 completed-frame handoff validation

6 November 2026. Full verification succeeded:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

**146 tests passed**, no failures, errors or skips. Lint has no errors (51 warnings
and 1 hint); debug/release compile. Fifteen new cases verify completed-only
snapshots, native flush/frame acknowledgement ordering, stale session/revision
tokens, cancellation, rapid completions, early hardware callbacks, detach/reset,
software draw ordering, duplicate Android UP/CANCEL and preserved legacy capture.
The completed image is rasterized outside the live pen lock. Ink direct presents
on the callback thread, with completion deferred until the Android frame handoff.

The user's positive test12 result establishes live Ink layers on this tablet.
It does not establish test13 continuity or improved physical latency. Test13
addresses the one-second diagnostic snapshot gap and adds an optional direct
presentation comparison. Android frame submission is not a physical panel
completion measurement. Pressure width remains fixed in these comparisons;
notebook defaults, saved-note format and app permissions are unchanged. See
[handoff and latency findings](FRAME_HANDOFF_AND_LATENCY.md).

APK identity:

- `dev.fastnotes`, version `0.3.2-test13`, code 17; minimum API 29, target API 30
- Bytes: `7987672`
- SHA-256: `c5e1bd3f8d20d784c6199e79d646c6be3ef4f8b0b057d937829de74fc62a6340`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`
- Same note format and package; installs over test6 or later without uninstalling

The separate read-only Settings code exporter has host fixtures for foreground
identification, code-only extraction, system-package/path validation, denied
reads, size limits and output protection. No physical USB export was performed
by this environment. It exports no notes or settings data and makes no device
changes; [usage and limits](SETTINGS_CODE_EVIDENCE.md).

# 0.3.2-test12 Ink layers comparison validation

5 November 2026. Full verification succeeded:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

**131 tests passed**, no failures, errors or skips. Lint has no errors (51 warnings
and 1 hint); debug/release compile. Independent review found no blocking layer
ownership, clearing, lock-order, clipping or default-renderer change.

The real PaperRasterizer fixture with integer endpoints and 3-pixel width
generated **9 opaque FE and 12 opaque FD pixels**, without injecting a sentinel.
Its transparent black counterpart generated zero reserved pixels. A model of the
external native rule verifies that the opaque sentinel can suppress an ordinary
black foreground pixel across an updated rectangle. Tests also verify distinct
transparent layers, completed-background stability, SRC cancellation clearing,
existing stroke rebuilding, and paper/ink capture only after the quiet interval.
Existing enum-loop geometry checks include the new comparison mode.

Ink layers uses the same worker scheduling as the opaque control and remains an
explicit scratch comparison. The normal notebook defaults are unchanged. Actual
device gaps/latency, live eraser behavior and the full stock ImageProcessor
contract remain unverified. See [findings and capture interpretation](INK_LAYERS_FINDINGS.md).

APK identity:

- `dev.fastnotes`, version `0.3.2-test12`, code 16; minimum API 29, target API 30
- Bytes: `7971292`
- SHA-256: `0cd7e8a4b92d6ae3f2b34c54b553a30a2710e50e214a018e05201f93b7323a18`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`
- Same note format and package; installs over test6 or later without uninstalling

# Test11 negative device result and original-APK audit

The user has now tested the original test6 and reports no live ink. The pasted
report verifies test6, portrait canvas `[0,596][1920,2400]`, accepted registration,
413 current Android samples and maximum event age 4 ms. It explicitly says no
native bitmap test was requested in this run; mixed Worker/Reference records are
under Previous run. This is a negative physical baseline result, not a working
rollback. Updated surface capture is next; its built-in Notes prompt now starts
capture before the app switch so initialization is included. Only instructions
and durable evidence changed; the Android APK/source remain unchanged.

5 November 2026. The user reports test11 still shows completed ink only after
lifting, without live System preview. The software checks below therefore do
not establish a recovered physical baseline.

The original test6 APK hash/version/debuggability were checked; its critical
compiled System methods match the source behavior, with no accidental Android
moving preview found. Manifest/startup/scoped IPC and unchanged note-storage
sources were compared against test11. An [in-place USB comparison](EXACT_TEST6_COMPARISON.md)
is prepared, with no tablet installation performed by the build environment.

The selected backend's `SoftSolutionBufTransfer` log tag was missing from the
first collector. Updated host fixtures pass for surface-state/error filtering,
numeric producer metadata, permission-failure handling, raw-data exclusion and
existing collector behavior. Bash syntax and diff checks pass. No Android app
source changed in this audit/collector update, so no new APK is published and
the 127-test app result below is retained as software evidence only.

# 0.3.2-test11 System ink restoration validation

5 November 2026. Full verification completed successfully:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

**127 tests passed**, no failures, errors or skips. Lint has no errors (51 warnings
and 1 hint), and debug/release compile. Independent review found no blocking
ownership, input bounds, cancellation or focus-resumption issue. Relevant cases
verify SoftSolution can make the explicit scoped request, production backend
reading is diagnostic, manual registration precedes System view preparation,
completed ink appears at UP and persists through later motion, pressure history
and clamps survive deferred construction, outside-page reentry does not bridge
segments, and focus pause discards only unfinished samples. The earlier native
snapshot-delay, storage, updater and cleanup tests continue passing.

The user reports test10 still skips in Worker mode and misses the preferred
earlier System feel. Test11 removes the overbroad block, restores test6's manual
order/instruction layout, moves pressure geometry to UP and retains completion/
focus improvements. This is not an exact source rollback and is not a measured
physical recovery. No new native/global-setting/driver tweak is applied. See
[corrected findings and device checks](SOFTSOLUTION_FINDINGS.md).

APK identity:

- `dev.fastnotes`, `0.3.2-test11`, code 15; minimum API 29, target API 30
- Bytes: `7954904`
- SHA-256: `5e8d22309d4667439f326de769c20b14ae856f21560a9db1b9438204d6dfec91`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`
- Same saved-note format; install over the existing app

# 0.3.2-test10 SoftSolution buffer validation

5 November 2026. The full Android verification command completed successfully:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The full run passed 124 tests. One additional production backend-reader test was
then added and the complete unit suite rerun: **125 tests passed**, no failures,
errors or skips. Lint reports no errors (50 warnings and 1 hint); debug/release
compile. No production source changed between these runs. USB collector fixture
and diff checks also pass. Independent review found no blocking ownership,
locking, cleanup or unsupported-mode fallback issue.

Coverage includes the native identity-or-role replacement contract, retaining
both native layers through startup/completion, live foreground pixels before UP,
background stability during a new stroke, completed snapshot/failure handling,
and production reading of `ro.eink.type=SoftSolution` before service connection.
Unsupported System selection keeps Android live fallback usable. Existing input,
eraser, rotation/focus, storage and updater cases continue passing.

USB confirms test9 and 982 system policy callbacks while its System ink fails.
The user confirms SoftSolution. Cross-ABI static inspection establishes the
native buffer contract violation, not measured physical improvement. The next
tablet check is **Worker refresh**, then the normal notebook and landscape
split-screen. Exact pressure matching and physical latency remain open. See
[current findings](SOFTSOLUTION_FINDINGS.md).

APK identity:

- `dev.fastnotes`, version `0.3.2-test10`, code 14; minimum API 29, target API 30
- Same note format and signing certificate; update installs in place
- Bytes: `7954904`
- SHA-256: `dc2c059ac88c09901a7011ffe5ea089c2896f384057d0604338b5668d48baa5e`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

# Read-only USB collector validation

5 November 2026. Host-only fixture and Bash syntax checks passed for
`tools/collect-viwoods-ink.sh`. The fixture verifies USB-only selection,
callback/native-error counts, stale-tail removal, coordinate/pressure/serial/note
sentinel exclusion, authorization failures, partial metadata status, existing
output protection and prompt return without timer-induced waits. No attached
tablet was used. See [USB instructions](USB_INK_DIAGNOSTICS.md).

The user reports System ink still shows only pen-up completion, including after
a tablet restart. This is not a successful live-ink result. Latest exported
report is from test8; the collector also checks the installed version. No Android
source or APK changes are part of this diagnostic-tool update; the software
checks below do not establish physical recovery.

# 0.3.2-test9 live-ink baseline recovery validation

Software checks performed on 5 November 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **119 tests passed**, no failures, errors or skips. Lint has no
errors (50 warnings and 1 hint); debug/release
variants compile. The four test8-only idle/hover experiment cases are removed
with that withdrawn feature; the working completion/focus and driver-export cases
continue passing.

Verified byte-for-byte identity with test7 source commit
`6c6be6c153a83a7443e7905e984a127e11510bcc` for:

- InkTestActivity.kt (including its layout, registration and focus behavior)
- InkTestView.kt (including pressure-aware completed ink at pen-up)
- InkComparisonTest.kt (matching integration/lifecycle coverage)

The SystemInkProbe Binder controller/protocol was unchanged in test8. Driver
metadata/module export is retained from test8 and runs only on explicit Save ZIP.
No new native, global setting, geometry-normalization or timing experiment is
introduced in this recovery build. Note storage/default notebook remain unchanged.

The user reports no live drawing in test8 with either hover-checkbox state. Its
failing report confirms accepted registration, prompt Android samples, zero hover
requests for the current two strokes and a changed canvas top of 731 versus
596 in the working test7 report. The exact display failure is not proven by these
facts. Restoring source/layout does not establish physical recovery: live ink,
pen-up continuity and landscape split-screen focus return require tablet testing.
The intermittent idle-start hesitation and exact native pressure curve remain open.

## Recovery APK

- Application ID `dev.fastnotes`; version `0.3.2-test9` (code 13)
- Minimum API 29; target API 30; note format 1; same signing certificate
- Version code increases so the source rollback installs over test8 without uninstalling
- APK bytes: `7954904`
- APK SHA-256: `103b6f7c4b20eb4e00aea736978f434c67b1e78a203bec2670018a6810a554c6`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [recovery checks](DEVICE_TESTING.md) and [regression evidence](INK_INVESTIGATION.md).

---

# 0.3.2-test8 idle-start and driver retrieval validation

Software checks performed on 5 November 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **123 tests passed**, no failures, errors or skips.
Lint has no errors (51 warnings and 1 hint);
debug/release variants compile. Added/updated checks verify:

- Hover preparation requires accepted System registration, a stylus, no current
  stroke, at least one second since pen-up/cancel and the enabled checkbox. Only
  one request occurs per idle period; mode reset/pause/unregistration gate it.
- Preparation redraws only unchanged completed ink and cannot expose a moving
  path. Its checkbox choice survives Activity recreation.
- Idle input history uses the earliest batched MOVE, retains six bounded records
  without coordinates/pressure traces, and labels timings as excluding visible
  ink latency. No file I/O or logging callback is added to DOWN/MOVE.
- Driver identity follows permitted character-device/sysfs/module links, filters
  serial fields, finds matching indexed/deep modules and rejects symlink/index
  escapes. Metadata/node/candidate budgets and unavailable-data reporting work.
- Matching driver modules precede native/DEX code under export budgets; gzip
  modules remain compressed, metadata enters driver-details.json, and private
  candidates cannot supply module bytes.
- Existing completion/pressure, native comparison, focus/resize/cleanup ownership,
  saved-note isolation, updater and evidence-picker cases continue passing.

The user confirms test7 ink stays visible, but reports first-stroke hesitation
after idle with unchanged focus while built-in Notes starts immediately. This
build preserves the working service and adds a reversible completed-frame hover
experiment. A draw request is not a native-ready acknowledgement or a measured
first-mark improvement. Physical A/B testing and pen-tip video remain necessary.

Both OEM library ABIs were inspected statically outside the repository. They
forward raw pressure and width limits to /dev/t1000_spi; no exact pressure curve
was recovered. Driver details are requested through explicit read-only export,
with metadata plus matching readable modules. Actual module availability is
unverified; missing files do not prove the driver is built in. No OEM execution,
module loading, device ioctl, boot-image read, root or new permission is added.

The notebook renderer and saved-note format are unchanged. This is an in-memory
scratchpad investigation, not durable notebook/eraser integration.

## Investigation APK

- Application ID `dev.fastnotes`; version `0.3.2-test8` (code 12)
- Minimum API 29; target API 30; note format 1; same signing certificate
- System protocol remains pinned to Viwoods AiPaper / API 33 / build 3.15.15
- APK bytes: `7971288`
- APK SHA-256: `a6b273eb22f3f6c1f072f8b27e057270e685e54e839ab58fb194cd4645608fdf`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [device checks](DEVICE_TESTING.md), [native findings](NATIVE_INK_FINDINGS.md)
and [driver/API export](FIRMWARE_API_EVIDENCE.md).

---

# 0.3.2-test7 completion, focus and native export validation

Software checks performed on 4 November 2026:

```sh
source /workspace/tooling/env.sh
./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

BUILD SUCCESSFUL. **113 tests passed**, no failures, errors or skips.
Lint has no errors (50 warnings and 1 hint);
debug/release variants compile. Added/updated checks cover:

- System-only pen-up completion is drawable immediately, remains available across
  the firmware's 800 ms clear and the old 1,000 ms boundary, and never exposes
  current moving ink during a forced redraw.
- Rapid successive strokes and cancellation retain prior completed ink. Batched
  historical pressure affects the completed outline; later strokes cannot change
  it. Nonfinite/out-of-range pressure cannot produce unbounded widths.
- System selection survives temporary focus/pause and geometry changes, without
  registering while unfocused/paused or before stable bounds. Explicit alternate
  modes/navigation clear automatic restoration. Failed cleanup still blocks new
  registration and remains process-owned across Activity recreation.
- Native evidence is prioritized over DEX and candidate limits; ABI labels and
  hashes identify artifacts. ELF export cannot suppress needed VDEX fallback.
  Actual bytes stay charged to the shared scan budget after failed/oversized reads.
- Earlier one-second native quiet snapshots, scoped Parcel protocol, Updates,
  saved-note isolation and evidence document-picker recovery continue passing.

The user reports test6 live writing works quite well, with disappearance roughly
one second after pen-up and fixed-width replay. The firmware clear is scheduled
at 800 ms, while test6's app replay waited 1,000 ms. Test7 removes that artificial
System ink delay and preserves Android pressure variation. It also addresses the
reported landscape split-screen focus-selection loss. Physical continuity, exact
brush appearance, first-stroke recovery and split-screen performance still require
the tablet. Software drawability is not a panel-completion measurement.

The pressure brush uses an estimated 2–8 px range; JNI normalization, curve and
smoothing remain unknown. The revised explicit export prioritizes native libraries
and allows 22 MiB code/24 MiB ZIP without new permissions, private-data access or
automatic upload. Static native inspection may reveal the curve or show pressure
is delegated elsewhere. No OEM binary is executed or bundled.

The notebook renderer, note format/default backend and original-pen eraser
integration are unchanged. This remains an in-memory System ink scratchpad.

## Investigation APK

- Application ID `dev.fastnotes`; version `0.3.2-test7` (code 11)
- Minimum API 29; target API 30; note format 1; same signing certificate
- System protocol accepts only Viwoods AiPaper / API 33 / build 3.15.15
- APK bytes: `8094904`
- APK SHA-256: `71c9d042e5efd90f8ec6ba9433c4c19f7094b13aa3ea8f0c723e6589d5b4aef3`
- Certificate SHA-256: `2c2d3546e1c01cf97899eb656c762f52bfa5f4c93626004eaf1deb26fd5ba601`

See [device checks](DEVICE_TESTING.md), [exact-firmware findings](EXACT_FIRMWARE_FINDINGS.md)
and [native evidence export](FIRMWARE_API_EVIDENCE.md).

---

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
