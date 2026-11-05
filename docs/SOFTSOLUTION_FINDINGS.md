# SoftSolution backend and bitmap registration correction

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
implementations. System registration and input callbacks can therefore succeed
without a native drawing operation on this backend. Its actual bitmap rendering
uses WritingSurface and the firmware's `WritingProducer` service. This is a
native surface route, separate from ordinary Android View drawing.

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

## Test10 implementation

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

After updating in place, use **Fastnotes Ink Test → Worker refresh** first.
Record ink during a rapid continuous stroke, completion and the first stroke
after idle. Then compare the normal notebook, both landscape rotations and
Chrome split-screen. System ink is intentionally unavailable on SoftSolution.
Exact stock pressure/brush matching and measured latency remain open.
