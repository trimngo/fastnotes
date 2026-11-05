# SoftSolution backend and bitmap registration correction

**Latest device result:** the user reports test11 still shows only completed ink
after lifting, with no live System preview. It is not a recovered baseline. An
[actual test6 APK comparison](EXACT_TEST6_COMPARISON.md) is prepared to distinguish
an APK difference from shared firmware/runtime state. The earlier USB collector
also omitted the selected backend's `SoftSolutionBufTransfer` log tag; its zero
native-error count did not cover surface initialization/queue failures.

The original test6 has now been retested and also shows no live ink. The report
confirms matching canvas geometry, accepted registration and 413 Android samples
at maximum age 4 ms. Its current process requested no native bitmap comparison;
those entries belong to previous-run history. The earlier live result remains
unreproduced, without an established cause. Use the corrected surface capture
on test6, including the transition into built-in Notes, before further app
timing or global-state changes.

**Test11 correction:** the test10 System ink block was unjustified by the
captured no-op methods. Scoped registration also initializes WritingSurface via
WritingProducer. The user reports Worker refresh still skips and asks to return
to test6's preferred live-writing behavior. Test11 restores that option and the
earlier manual startup/layout, retaining immediate completed ink and moving
pressure-outline work to UP. The complete runtime effect is still unverified.

5 November 2026. Full-size Viwoods AiPaper, Android 13/API 33, build 3.15.15.
The USB report confirms installed Fastnotes 0.3.2-test9, code 13, kernel
`4.19.191-g1add12f05208`, hardware `mt8788`, board platform `mt6771`.
The user separately ran `adb -d shell getprop ro.eink.type` and reported
**`SoftSolution`**. No OEM binary was executed or bundled.

## What the USB comparison establishes

Both requested 15-second captures lasted 15 seconds; host logcat exit 143 is the
collector ending its own host capture at the deadline.

| Capture | Recognized system-policy callbacks | Recognized native failures |
| --- | ---: | ---: |
| Fastnotes System ink | 982, all from the recorded system_server PID | 0 |
| Built-in Notes | 0 | 0 |

Fastnotes input reaches HandWritePolicy and is forwarded to the policy. The
marker occurs before the queued native operation completes, so it does not prove
visible ink. Built-in Notes' zero count is consistent with its separate
app-rendered route; it does not mean Notes receives no input. The collector
whitelists four native failure categories, so zero is not evidence that every
possible error is absent. Its device-node and module-index reads still failed;
no kernel module was retrieved.

## The earlier T1000 assumption was wrong for this device

The exact exported native library selects its backend using `ro.eink.type`.
`SoftSolution` selects SoftSolutionBufTransfer; other values select the T1000
implementation. ARM64 selection is in native_init_usb around `0x15520–0x155d8`.
The active backend is a runtime choice, not established by class/method names
present in framework/services DEX.

SoftSolution's virtual autoDraw and auto-draw region methods inherit empty base
implementations in the captured ELF. This does **not** mean scoped registration
has no effect. AutoDrawPolicy enables the callback for nonempty effective
regions; ENoteWriting.setWritingInputCallBackable calls native handwriting
enable. On SoftSolution that invokes Init/exit as well as toggling InputWorker.
Init calls WritingSurface::init and connects/queues the firmware WritingProducer
surface. These effects are confirmed in both ABIs: native handwriting enable
around ARM64 `0x15cf4` and ARM32 `0x10592`, with ARM64 SoftSolution Init at
`0x12d90`. The full producer/runtime presentation effect and exact library loaded
by the live process have not been established. Therefore the test10 hard block
was stronger than the evidence and is removed. Successful registration still
does not prove physical drawing, but the earlier positive device result remains
relevant evidence for this explicitly requested probe.

Earlier claims that this tablet's pressure path reaches `/dev/t1000_spi`, that
the pressure curve must be downstream of that driver, and that the 800 ms T1000
clear explains this tablet's behavior were overgeneralized from an inactive
branch. The T1000 disassembly remains valid for that implementation, but is not
the active-device contract. The earlier positive live-ink observations remain
user observations; they did not independently establish which renderer was
responsible. Recovering a T1000 driver is no longer the next step for this tablet.

## Concrete bug: one bitmap registered in both roles

Native registration replaces an existing bitmap entry when **either** its JNI
object identity matches **or** its foreground/background role matches.
Foreground role is 1; background role is 0. Fastnotes supplied the same
`displayFrame` object to both registrations.

That violates the two-role contract: background registration removes foreground;
the next DOWN's foreground registration then removes background. Startup and
completed handoff could therefore alternate the remaining role instead of
retaining both. SoftSolution's compositor distinguishes these roles, including a
special `0xFFFEFEFE` sentinel branch. Ordinary antialiased pixels can contain that
value, so losing one role can change the rendered result. No sentinel injection
or guessed pressure formula is needed to correct the registration bug.

The identity/role replacement and compositor were verified in both exported ABIs:

| Operation | ARM64 address | ARM32 address |
| --- | --- | --- |
| Foreground role assignment | `0x10d90` | `0xcc1e` |
| Background role assignment | `0x10ed0` | `0xccf6` |
| Identity-or-role replacement | `0x1147c–0x114bc` | `0xd0c0–0xd0ec` |
| SoftSolution compositor | `0x13460` | `0xe8f0` |

This is a verified contract violation and a plausible contributor to gaps. A
physical handwriting fix still requires device testing.

## Test10 implementation (historical; System block withdrawn)

- Keep three distinct owned bitmaps: mutable preview, native foreground
  presentation, and completed background. Both native roles survive registration.
- Freeze background during an in-progress stroke. Copy completed ink at handoff
  with SRC blending under the existing firmware lock, before refreshing its
  background registration. No full background copy is added to MOVE processing.
- Apply this to the normal notebook and both native comparison policies. Retain
  scheduling, pen/eraser behavior, orientation handling and saved-note format.
- Keep the diagnostic's 96 MB transient bitmap ceiling by allowing at most 6M
  pixels for three owned buffers plus the temporary source. The device's
  1920×2560 display fits this limit.
- Read/report `ro.eink.type`. Refuse the no-op System auto-draw path when the
  property is SoftSolution, explain which native comparison to use, and retain
  Android fallback instead of hiding all live app ink in an unsupported mode.

The native-contract test double now implements identity-or-role replacement.
Regression cases check both registrations survive startup/completion, live black
pixels remain available before UP, completed background stays unchanged during
the next stroke, and failure/cleanup remain handled. These software checks do
not emulate physical e-ink presentation.

The user reports test10 is worse for their workflow: Worker refresh still skips,
and the System option they preferred is blocked. The native bitmap registration
correction is software-verified, but did not establish the desired physical
improvement. Do not use it as evidence that the preferred System route was
unavailable or that the user should keep switching to Worker refresh.

## Test11: restore the preferred System comparison

- Re-enable the exact scoped add/remove request on SoftSolution; keep backend
  information and accepted-but-unverified wording. No global flags are added.
- Restore test6's manual cleanup → registration → view-preparation order and
  original instruction paragraph, correcting only its completed-ink timing.
- Retain immediate completed ink at UP to address the earlier disappearance.
  During System DOWN/MOVE, collect bounded primitive coordinates/pressure;
  construct the independent pressure outline only at UP. No moving app path or
  pressure-outline geometry is drawn/built during System live motion.
- Keep focus/resize ownership cleanup, completed ink and automatic focus return
  for the requested split-screen workflow. This is a combination of test6
  startup with later handoff/lifecycle improvements, not an exact source rollback.

Use the separate **Fastnotes Ink Test** launcher icon and select **System ink**
first. Check the live feel the user liked, completed-ink continuity, the first
stroke after idle and then landscape split-screen. Physical recovery, exact
stock pressure matching and measured latency remain open.
