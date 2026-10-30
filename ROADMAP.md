# Fastnotes: priorities and future ideas

Recorded from the user's ideas on 30 October 2026.

## First milestone: get the basics right

Create a handwriting notepad for the **full-size Viwoods AiPaper** with the
lowest practical pen-to-visible-ink latency and a **lined paper background**.
The immediate use case is writing alongside the user's German app.

- Prioritize responsive, reliable handwriting over additional features.
- Support both portrait and landscape orientations, including switching between
  them without losing handwriting.
- Support Android split-screen so the notepad can be used beside the German app.
- Keep lines and handwriting correctly aligned when rotating or resizing the
  window. Ink must stay within the notepad's window.
- Use one vertically scrolling sheet with adjustable line spacing.
- Support the original Viwoods pen and its eraser.
- Provide a palm rejection option to reduce accidental ink and scrolling while
  writing, while retaining a deliberate way to scroll the sheet.
- Provide basic note saving and reopening so practice notes are retained.
- Measure latency and check writing behavior on the actual full-size AiPaper;
  a successful build or results from the Mini do not establish performance.
- Investigate and extend the device/API integration if needed for full-size
  support. Keep the first milestone focused on writing and lined paper.

## Device and workflow details

- Device: full-size Viwoods AiPaper.
- Firmware: **3.15.15**, reported by the user.
- Android version: **Android 13 / API 33**, confirmed by the user from Fastnotes'
  device details. Verify runtime capabilities before depending on vendor-specific
  behavior. The device report identifies **Viwoods AiPaper**, build **3.15.15**,
  display **1920×2560** in natural portrait orientation.
- Pen: original Viwoods pen; the user uses its eraser.
- Writing hand: right-handed; account for this in control placement and palm
  rejection testing.
- Companion app: the user believes the German app is currently a progressive
  web app running in Chrome. Validate split-screen use alongside Chrome in both
  portrait and landscape; the app's specific name and URL are not yet supplied.

No integration beyond side-by-side use has been requested. Do not assume OCR,
translation, or an integration API is required for this first milestone.

## Implementation status: 0.2.0 native-ink test

The first Android implementation includes portrait/landscape resizing, one
scrolling lined sheet, adjustable spacing, pen and hardware-eraser input, palm
guard, undo/redo, named sheets, and automatic local saving with backup recovery.
On 0.1.0, the user reported severe lag with both modes, plus brief static squares
with bitmap-only panel acceleration enabled, while full-screen in portrait.
The user requested acceleration on by default and cited ForestNote as a partial
working reference.

Version 0.2.0 replaces that option with direct ENote pen callbacks, a dedicated
live preview, per-stroke native transactions, FAST mode and explicit canonical
handoff. It is enabled by default when its API is available. The current native
compatibility target remains API 30; this explains the older-Android installer
message on Android 13. Usable handwriting now takes priority over removing that
message. The native path still needs on-device performance and artifact checks.

See [validation evidence](docs/VALIDATION.md) and the
[device test guide](docs/DEVICE_TESTING.md).

## App updates: 0.3.0

At the user's request, in-app updating adds automatic daily checks against GitHub
development releases, a manual check, verified APK downloads, and handoff to
Android's installer. Automatic checks are enabled by default and can be disabled.
Install 0.3.0 once to receive future builds through the app. Notes remain local;
update IO has its own worker. See [update details](docs/UPDATES.md).

## Rapid ink and landscape split-screen: 0.3.1

The user confirmed native callbacks and FAST mode in 0.3.0 but supplied photos of
gaps during rapid strokes that filled after a delay without interaction. Chrome
on the left with Fastnotes on the right in landscape stayed on Android ink, with
slow refresh and a reported exit. ForestNote also demonstrates fast ink in this
layout but is unstable. An actual crash stack/cause has not yet been supplied.

Version 0.3.1 replaces point-count-only refreshes with a paced/coalescing worker,
a separate presentation bitmap, short retained damage coverage and a completed
stroke settling pass. It uses physical display orientation for the firmware,
retries after late pane layout, avoids restarting stable sessions, and clips to
the visible pane. Footer states now distinguish paused native ink from fallback;
Device details includes start bounds, orientation and local crash/exit evidence.
Software tests cannot establish that the physical skips or crashes are resolved.

## Change of approach after 0.3.1

The user tested 0.3.1 and reports similar problems, including in ForestNote. The
current callback/bitmap route is not considered a successful solution. Version
0.3.2-test1 adds an isolated comparison test for a distinct firmware-preview route,
with read-only API inspection and explicit separation between accepted API calls
and observed live ink. It does not replace the note backend with another unverified
default. The stock Notes baseline is still pending. Research, evidence limits and
the next decision are recorded in [the ink investigation](docs/INK_INVESTIGATION.md).

The 0.3.2-test1 device report supplies exact APIs: the rectangle-registration
methods are not exposed to this app, while `setViewAutoDrawEnabled(View, boolean)`
and a contextual factory are exposed. The test's local capability check failed
before vendor initialization, so its result does not measure firmware preview.
0.3.2-test2 corrects the test to enable its owned scratch view, disable that exact
view on cleanup, retain explicit failure stages, and keep UI text from resizing
the active canvas. Physical drawing and split-screen compatibility remain unverified.

The user confirmed that test2 produced ink **only after lifting the pen and
waiting**. The reported view call returned and 1,026 Android samples had maximum
event age 7 ms, but no live preview was observed. This configuration is not a
working ink backend; the unreadable global enable flag remained untouched.
Test3 therefore compares pane-sized and full-display native buffers using the
same existing controller and refresh policy. Stock WiNote's display-sized buffers
motivate the hypothesis; they do not prove it is the cause or solution. The test
keeps refreshes inside the visible pane, captures only quiet completed snapshots,
records a user-selected visual result, and offers Updates directly. The normal
notebook renderer is not replaced pending real-device evidence.

## Later ideas

These are recorded for future work, not requirements to implement immediately.

- **Pressure sensitivity:** vary handwriting appearance with pen pressure.
- **Encrypted notes:** allow notes to be encrypted; define key management,
  unlocking, backup, and recovery before implementing the feature.
- **Easy synchronization:** make notes easy to synchronize to other devices.
  Consider how synchronization interacts with encryption and conflicting edits.
- **Easy organization:** make notes easy to find and organize, with a notebook
  and page experience.
- **Handwriting to text:** convert handwritten notes into editable text.
- **Different backgrounds:** offer notebook-style paper choices, including
  lined paper, to make the app feel like a physical notebook.

## Security is a continuing priority

Security matters to the user even while advanced features are deferred.
Preserve notes reliably and avoid exposing their contents through diagnostics,
build artifacts, or unnecessary network transfers. Plan future encryption,
synchronization, and handwriting recognition around the user's privacy needs;
do not assume notes may be uploaded to a service. Do not describe notes as
encrypted until encryption has actually been implemented and verified.

The order among the later ideas is undecided. The first priority remains
**low-latency handwriting on lined paper alongside the German app**.
