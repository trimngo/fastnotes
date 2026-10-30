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
  behavior; the exact full-size model/build string has not yet been supplied.
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
