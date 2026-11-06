# Ink modes, pressure and System preview experiments

The user confirms test14's hesitation after selecting a mode has disappeared.
They request pressure sensitivity and authorize exploratory System preview,
including drawing over Chrome. Ink layers remains the working baseline.

## The three drawing paths

| Mode | Who makes the live pixels | How updates reach the panel | Current device evidence |
| --- | --- | --- | --- |
| System ink | Fastnotes requests a firmware-owned preview area; it does not paint moving strokes in Android | ENoteSetting system-service registration and the firmware's preview machinery | Earlier live, immediate, unbounded preview was observed; current tests, including the original APK, no longer reproduce it |
| Ink layers | Fastnotes draws black ink into transparent native bitmaps | A worker presents dirty regions, with recent-region coverage and a final flush | Working live ink; test14 startup hesitation is reported gone |
| Ink direct | Fastnotes uses the same transparent ink format | Presentation runs on the pen callback thread; native calls can delay later input | Also works; user finds no clear latency advantage over Ink layers |

Both transparent modes still use native ENote input and presentation. Ink direct
does not bypass Fastnotes' stroke drawing or become a firmware-only renderer.
Its policy also differs in damage coverage, re-enabling and final worker retry;
this is not solely an 8 ms timer comparison.

## Recording does not require a slow live preview

Live display and document recording are separate jobs. A firmware preview can
show a stroke immediately while the app records its coordinates and pressure.
The app can later save/redraw those samples. The need for buffers or saved
strokes does not make firmware preview impossible; the issue is obtaining the
correct live preview behavior and matching its appearance during handoff.

Fastnotes' current System test receives Android samples and shows completed
strokes at pen-up, but the firmware live preview is absent. Accepted region
registration proves the service processed the request, not that pixels reached
the panel. The exact reason the earlier preview stopped is not established.
An original APK also failing now rules out app source changes as a complete
explanation; retained app/system state and firmware ownership are still possible.

In full-display tests, strokes outside Fastnotes' canvas may be previewed over
Chrome or system areas. Fastnotes receives Android canvas samples only inside
its own view, so outside-canvas ink cannot be recorded by this scratchpad.

## Pressure restoration in test15

Pressure is enabled by default in Ink layers and Ink direct. The Pressure
checkbox lets the user compare fixed-width ink; changing it restarts the active
transparent scratch comparison. No additional buffering or delayed smoothing is
introduced. The existing prepared startup and completed-frame handoff remain.

The response follows the installed Settings steel-pen formula: normalize raw
integer pressure using start 410, maximum 4096 and ratio 1, then use a logarithmic
width response. Fastnotes chooses minimum/maximum widths of 0.5–2 times nominal
(1.5–6 px for this scratchpad). Low pressure still makes visible ink; a zero
pressure pen-up retains the preceding width. These bounds are app choices,
not recovered OEM presets. No global pressure preferences are modified.

Committed strokes retain immutable per-point widths inside the scratch renderer.
Live and completed drawing use the same tapered segment primitive and sample
order. Wider caps are clipped to the owned pane, damage includes the maximum
endpoint width, exits split the stroke, and cancellation/eraser rebuilds replay
the same geometry. Pressure changes at a stationary point are retained.
The transparent-then-composited live image and direct opaque replay can differ
by up to 2/255 per RGB channel from antialiasing arithmetic in the test fixture;
the pressure geometry does not change. The other rebuild checks remain exact.

The pressure lists are confined to the temporary comparison controller. Note
models, saved-note format and normal notebook defaults are unchanged. This build
does not claim the precise brush of the earlier System preview or the missing
Settings resource presets. See [installed Settings findings](SETTINGS_PRESSURE_FINDINGS.md).

## Separate System comparisons

The System test selector provides three requests on the same layout:

| Selection | Request area | Startup order |
| --- | --- | --- |
| Original | Visible Fastnotes canvas | Existing register-before-paper order |
| Prepared screen | Same visible canvas | Prepare paper/prompt and submit its frame before registration |
| Full display | Current rotated physical display | Same startup order as Original |

Prepared screen changes ordering only. Full display changes the requested
rectangle only. This keeps the effect of each comparison interpretable. The full
request is explicitly authorized even when its preview crosses into Chrome.
No new global auto-draw flag is required for either comparison.

Every request retains the exact rectangle before IPC and removes that same
rectangle on stop, focus loss, pause, resize or mode change. Failed cleanup blocks
replacement; it is not treated as success. Pending prepared requests are canceled
or rejected when lifecycle/geometry changes. Original remains the default.
Back and navigation also wait for accepted removal; system destruction still
performs a best-effort cleanup using the process-owned probe.
These are experiments, not a claim that System preview has recovered.

## Other available firmware changes

Static inspection explains why arbitrary exposed flags are unlikely to help:

- View auto-draw wrapper methods are empty in the captured framework.
- Auto-draw region/tool/width native operations on the selected SoftSolution
  class inherit empty base methods; some setters only retain Java state.
- The T1000-named policy enable field has no consumer in the inspected policy.
- The global all-region gate has an effect, but its current state is not readable
  and its return value is not a previous-state token. It is separate from an
  owned region registration, rather than a useful first toggle to combine here.

A further candidate is an exclusive app-owned overlay-marker probe:
SoftSolution `onWritingStart`/`onWritingEnd` queue special crop markers on the
WritingSurface. Their effect is real, but what the unexported consumer does with
those markers is not known. Such a probe would stop service/bitmap ownership,
enable an app-owned session, send only start/end markers while collecting pen
samples, and avoid live Java bitmap presentation. This is a distinct hypothesis,
not an implemented or proven direct-ink API in test15.

No OEM binaries or implementation are executed or bundled. The current useful
work is restoring pressure on the working path and comparing the two controlled
System requests; any further marker/global experiments should remain separately
identifiable.
