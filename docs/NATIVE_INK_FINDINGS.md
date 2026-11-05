# Native ink findings from the second device export

**Correction, 5 November 2026:** the user confirmed `ro.eink.type=SoftSolution`.
The T1000-specific pressure/clear analysis below describes an inactive branch on
this tablet and must not be treated as its driver contract. The USB comparison
also revealed a concrete foreground/background registration bug in Fastnotes.
See [SoftSolution findings and test10](SOFTSOLUTION_FINDINGS.md) for the current
backend, evidence and implementation. Exact pressure matching remains unresolved;
retrieving a T1000 driver is no longer the next investigation for this device.

5 November 2026. Full-size Viwoods AiPaper, Android 13/API 33, build 3.15.15.
The user's test7 ZIP supplied both native libraries, plus the same framework and
service DEX units as the first export. Each artifact matched its manifest hash.
Inspection was static: ELF headers, symbols, compressed debug symbols and ARM
instructions were read as data. No OEM code was executed, bundled or committed.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `/system/lib64/libpaintworker.so` | 170,576 | `b1cbc592d94aedbc9976a2156e4e740cce59f5ff68647c5ba4df3c6555ea99fb` |
| `/system/lib/libpaintworker.so` | 121,756 | `ee04132e8bfe124fd6e8e14c53622ce3a460696015b638e0cc4f8463203229da` |

## Pressure: a driver boundary, not a recovered brush curve

Both AArch64 and 32-bit ARM agree:

1. Linux ABS_PRESSURE values are copied into the native pen event unchanged.
2. JNI `native_auto_draw` copies the six incoming integers without normalizing
   pressure or calculating a width.
3. `T1000SpiBufTransfer::autoDraw` reads raw pressure from event slot 3. When it
   changes, the vtable resolves to `setAutoDrawPenPress`.
4. That setter applies a lower clamp of 2 and passes pressure to the T1000 device
   interface. Maximum and minimum pen widths are sent separately, each with its
   own lower clamp of 2. No pressure-to-width interpolation occurs in this path.

Relevant AArch64 function addresses are JNI auto-draw `0x16e20`, T1000 auto-draw
`0x14300`, pressure setter `0x14700`, width-range setter `0x144f0` and its max/min
setters `0x14580`/`0x14640`. The constructor's vtable at `0x26390` resolves the
pressure call to the named setter. ARM Thumb auto-draw `0xf328` and pressure
setter `0xf5fc` confirm the same behavior independently.

The library opens `/dev/t1000_spi`. Pressure and width behavior is therefore
implemented downstream of the inspected userspace boundary. These files cannot
distinguish kernel-driver math from controller firmware. Exact replay needs that
implementation/contract or measured calibration, including pressure normalization,
smoothing and raster geometry. The existing Android replay remains an estimate.
No guessed formula or direct device call was added to Fastnotes.

Public GitHub searches for the exact pressure command and Viwoods kernel source
found no matching implementation. A public Huawei T1000 driver was inspected as
a comparison, but it targets another platform/interface and is not evidence for
this firmware's pressure curve. Other projects sharing the library basename are
also not proof of a matching implementation.

## Latency: real motion filtering, no scoped tuning API found

`InputWorker::sync`, AArch64 `0x12a30`–`0x12a68`, suppresses MOVE/HOVER reports
according to its jump count. The system policy constructor sets 4: at that value,
four candidate reports are skipped and the fifth is forwarded. DOWN and UP bypass
the test. The counter is not reset by DOWN/UP, so initial movement can encounter
zero to four skipped reports. This is a configured filtering behavior, not a
measurement of the current event rate or pen-to-panel latency in milliseconds.

The exposed wrapper setter changes the calling process's native InputWorker.
It does not change the system server's separate worker, and the inspected Binder
contract supplies no corresponding scoped count setter. Setting zero in Fastnotes
would not tune the working System ink path. Native input already requests nice
priority -8; the 500 ms poll argument is a readiness timeout that wakes for input,
not a fixed drawing delay. The Java callback path adds two Handler queues.

Lower filtering is a vendor optimization candidate, but can increase callback work
up to fivefold and may increase queue backlog. No faster default is claimed and
no system-wide input/priority or firmware patch is applied.

## First stroke after a pause

The user confirms test7 completed ink stays visible after pen-up, but sometimes
writing hesitates after an idle pause with unchanged focus. Built-in Viwoods
Notes starts immediately under the same comparison. This is separate from the
focus-resumption behavior and from the earlier completed-ink disappearance.

At pen-up + 800 ms the firmware's T1000 handler calls native clear. The native
`clearScreen` at `0x14210` sends the handwrite-state command with zero; it contains
no userspace sleep or explicit reinitialization. Its downstream wake/rearm effect
is not visible in these libraries. An asynchronous registration/button-reset race
could affect strokes immediately after focus/start, but does not establish the
cause of the user's unchanged-focus idle symptom.

Test8 adds an optional **prepare after idle on hover** experiment. After at least
one second since pen-up/cancel, the first returning stylus hover requests one
Android frame containing only completed paper/ink. It does not synthesize pen
input, change native settings or draw a moving stroke. The request is gated by
an accepted active System ink registration and can be disabled for comparison.
Whether it wakes/prepares the display sooner is a physical-device hypothesis.

Bounded in-memory idle-start diagnostics distinguish time since the last stroke,
age of registration, Android DOWN event age, first MOVE timing and a preceding
hover request. No stroke coordinates or pressure traces enter those records, and
no file I/O is introduced on DOWN/MOVE. These input timings do not measure visible
panel latency. A pen-tip video is needed to quantify any improvement.


## Retrieving driver details

At the user's request, test8 extends the explicit ZIP export with a bounded
read-only driver collector. It identifies `/dev/t1000_spi` through file metadata,
resolves its sysfs driver/module links where accessible, and records filtered
kernel/board/module information without serial fields or memory addresses. It
searches fixed system/vendor/ODM module directories for T1000-family or identified
module names and includes at most eight matching readable module candidates,
with an 8 MiB per-module limit under the existing code/ZIP budgets.

The collector never opens the device for I/O, issues a device ioctl, reads boot
partitions, loads a module or requests root. A driver may be compiled into the
kernel, stored elsewhere or inaccessible to a normal app; missing files alone do
not distinguish those cases. `driver-details.json` remains useful for matching a
vendor/source request even if no module bytes can be exported. The official
support-center/page sitemap search did not reveal a matching source download.


## Experiment withdrawn after live-ink regression

The user reports test8 shows only completed Android replay, regardless of the
hover checkbox state. Test9 removes the hover experiment/input instrumentation
and restores the exact test7 writing Activity/view/layout. These files did not
change the service's Binder protocol, but layout and frame-presentation changes
were not physically validated. No causal conclusion or latency win is drawn.
The read-only driver collector remains available through explicit export.

## Third export and continued failure

The third export's four code artifacts are the same two libpaintworker ABIs and
framework/services DEX units. No kernel driver was captured. Driver metadata
reports `mt8788`, but kernelVersion is null, metadataBytes is zero and modules,
moduleFiles and indexMatches are empty. Failed reads/stat record exception types
only, so absence cannot be distinguished from access denial. A future collector
can use public Os.uname() and wrapped numeric errno for better identity/errors;
these APIs cannot retrieve a pressure curve by themselves.

The user reports no live System ink even after a tablet restart. Completed
pen-up replay remains functional. Test9's source rollback is not a confirmed
recovery. The next observation is the existing system-server policy callback
marker during System ink versus stock Notes, using the
[read-only USB diagnostic](USB_INK_DIAGNOSTICS.md). No new firmware enable,
global tool/exclusion reset or pressure formula is introduced without evidence.
