# Draft technical request for Viwoods: SoftSolution handwriting

Prepared from the user's exact-device evidence; **not sent**. This replaces the
earlier request based on the T1000 branch, which is not selected on this device.

Fastnotes (`dev.fastnotes`) is an offline handwriting notepad for full-size
Viwoods AiPaper, Android 13/API 33, build 3.15.15, 1920×2560. The user's USB
report identifies hardware mt8788, board platform mt6771, kernel
4.19.191-g1add12f05208. They separately confirmed `ro.eink.type=SoftSolution`.
Target API 30 is currently retained for ENote compatibility. Workflow includes
portrait, both landscape rotations and Chrome-left/Fastnotes-right split-screen.

Built-in Notes draws smoothly. The ENote bitmap route previously showed rapid
stroke gaps that filled later. A scoped System auto-draw comparison produced
982 system-server policy callbacks in 15 seconds but no live drawing; built-in
Notes produced none of that marker. The selected SoftSolution native backend
inherits empty auto-draw/region methods, despite the DEX containing a T1000 policy.
The T1000-specific pressure/device-interface analysis does not describe the
selected backend and does not establish a need to retrieve a T1000 driver.
However, scoped region registration also initializes WritingSurface via native
handwriting enable and WritingProducer. Empty auto-draw methods do not establish
that the whole scoped mode has no useful effect. The user preferred an earlier
System comparison; test11 restores it after test10 blocked it too broadly.

Static inspection of exact-device libpaintworker revealed a concrete Fastnotes
bug: native registration deduplicates by bitmap identity OR layer role, while we
registered one object as foreground and background. Test10 uses separate owned
bitmaps, preserves both roles and updates completed background at handoff. This
contract correction is software-verified; the user still reports Worker gaps.
No OEM code is executed, bundled or attached to this public request.

Could you provide the supported SoftSolution contract for:

1. Ordinary third-party use of ENote bitmaps and WritingProducer, including any
   permissions, ownership and supported initialization/release sequence?
2. Foreground/background pixel format, alpha and `0xFFFEFEFE` sentinel semantics,
   buffer lifetime, synchronization and partial-damage requirements?
3. Correct onWritingStart/onWritingEnd and setWritingEnabled behavior during a
   stroke, idle, focus loss, rotation and resizable split-screen windows?
4. Pressure normalization and stock brush geometry/smoothing on SoftSolution:
   is this implemented in the Notes pen renderer, and is a supported brush API
   available to third-party applications?
5. Supported observation of native surface readiness/ownership, failed queued
   presentation and input-to-visible-ink timing without global setting changes?

Source and independent findings: https://github.com/trimngo/fastnotes
See docs/SOFTSOLUTION_FINDINGS.md for evidence and limits. No notes, raw pen
coordinates, OEM binaries or device serials are needed in the initial request.
This draft remains **unsent**.
