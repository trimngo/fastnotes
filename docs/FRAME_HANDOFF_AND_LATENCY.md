# Completed-frame handoff and direct ink comparison

## Test13 device result and test14 startup ordering

The user confirms both transparent modes draw live and feel broadly similar;
Ink layers may feel faster. System ink still has no live preview. The remaining
first-stroke hesitation eventually shows the complete stroke and occurs **after
selecting a mode**, rather than after a pause without changing modes. This does
not support treating dropped orphan MOVE events or idle wake-up as the cause.

The diagnostic previously enabled native ink inside `nativeProbe.start`, then
called `paper.nativeMode` (clear/invalidate) and changed the success prompt.
Consequently its startup scratch reset was guaranteed to remain queued after
native enablement. Test14 prepares that paper and prompt first. Both transparent
modes wait for a fresh paper draw and corresponding Android frame submission
before native initialization. There is no fixed delay. Opaque controls and the
System probe retain their previous ordering.

A distinct one-shot preparation serial rejects old render-thread/UI callbacks.
Replacement, cancellation, clear/mode change and detach invalidate the token;
the Activity also cancels on focus loss, pause or geometry change. Before starting
it rechecks resumed state, focus, attachment, visibility and exact geometry. It
does not reset the paper or change a success prompt after enabling native ink.
Reports include frame-preparation and native-start call durations, explicitly
excluding physical panel readiness and visible ink latency.

This removes a concrete app ordering hazard. Button ripples and unrelated window
updates remain possible; submission is not proof that the e-ink panel has settled.
The cause of the user's delay and any physical improvement remain unverified.
No orphan-MOVE recovery, jump-count change, keep-awake flag or idle timer is added.

The [exact installed Settings export](SETTINGS_PRESSURE_FINDINGS.md) contains a
bitmap callback renderer, pressure normalization and a logarithmic steel-pen
width formula. Its fragment normally posts enabling 600 ms after setup, with
other direct focus paths. That timer is reference behavior, not a timer to copy.
Width preset resources and actual selected settings are absent. Test14 keeps
the working fixed-width raster to isolate startup ordering.

## Test12 baseline and test13 completion change

6 November 2026. The user reports **test12 Ink layers draws live**, without
pressure-sensitive width. They notice steady extra latency and occasional
disappearance/reappearance. Fixed width is intentional in this comparison;
pressure tuning is deferred while continuity and latency are assessed.

## Completion in test12

The diagnostic deliberately waited one second of quiet before creating an
Android snapshot. Native completion could occur before that snapshot, so a gap
at pen-up was possible. This is a concrete handoff weakness; it does not prove
every disappearance was at pen-up or explain steady moving-pen latency.

Test13 changes only the transparent comparison modes to a completed-frame
handoff. The renderer snapshots immutable completed Note/Viewport state under a
short lock and rasterizes it outside the live pen lock. A current next stroke is
excluded. The View prepares the completed image immediately and registers a
hardware frame-commit callback **before** invalidating; registration from onDraw
can miss the frame. Native completion waits until that image has been drawn and
the Android frame submitted. Software rendering posts acknowledgement after
onDraw returns. Submission is not a measured physical e-ink update.

Session/revision tokens prevent an old acknowledgement ending a new stroke,
newer completion or restarted controller. Replacement, clear, mode changes and
detach cancel stale View callbacks. Delayed duplicate Android UP/CANCEL events
do not restart the old one-second capture. Displayed bitmaps are left for Android
to retire instead of eagerly recycling pixels still referenced by display lists.

The Android replacement uses canonical fixed-width vectors, so minor antialiasing
edge differences from incrementally painted native pixels remain possible. It
is not an extracted OEM pressure curve. No extra per-MOVE file I/O or completed
rasterization is introduced. Opaque controls and notebook defaults retain their
previous completion behavior.

## Ink layers versus Ink direct

Both use the working transparent bitmap format, distinct native roles, the same
input sampling/jump count, clipping and fixed pen width, plus the new handoff.
Ink layers keeps the existing worker policy. Ink direct uses the existing
callback presentation policy:

| Policy | Ink layers | Ink direct |
| --- | --- | --- |
| App presentation worker/cadence | One worker, up to next 8 ms slot | None; render on pen callback thread |
| Damage | Dirty union plus 32 ms recent coverage | Current segment rectangle |
| Re-enable on DOWN | Existing worker behavior | Enabled at session start |
| Final worker retry | Existing 24 ms pen-up flush | No worker retry |
| End native overlay | After frame acknowledgement and pending work | After frame acknowledgement |

This tests the complete presentation policy, not only an 8 ms delay. The first
worker render is due immediately, rather than always waiting 8 ms. Recent
coverage increases the native scan/copy area. Native render/dequeue/queue is
synchronous, however, so direct calls can block the firmware's callback Handler
and create backlog. Direct is an opt-in comparison, not a proven latency win.

The bitmap callback route has one verified OEM per-event Java Handler queue,
followed by Fastnotes' worker in worker mode. The separate System policy has its
own topology. Do not attribute a generic second OEM event queue to Ink layers.
Sampling is not set to zero and no thread-priority/global display tuning is added.

Use **Ink Test → Ink layers** first to check pen-up continuity. Then compare
**Ink direct** during fast continuous writing in the same orientation. Copy
report after each if useful; call durations and thread IDs are not visible-panel
latency measurements. Keep the better mode if direct presentation feels worse.

## Earlier unbounded preview and Settings reference

The user recalls earlier instantaneous System ink drawing over the Chrome half
of split screen. This is consistent with a global preview or incorrect native
coordinate/region handling, not proof of a specific hardware API. The captured
SoftSolution region methods do not establish a working native region boundary.
Removing app clipping would not recover a missing rendering implementation and
can increase pixel work. No unbounded/global drawing toggle is introduced.

HandWritePolicy's stop/interception timer only clears drawing state; an inverse
activation API has not been identified. WritingProducer's consumer is not fully
represented in the current exports. The tablet's Pen and Gesture pressure test
is a valuable additional reference. Its [installed code is now supplied and
inspected](SETTINGS_PRESSURE_FINDINGS.md); the [exporter](SETTINGS_CODE_EVIDENCE.md)
remains available for reproducing that evidence.
