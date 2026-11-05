# Exact-device firmware API evidence

Test4 executed its intended reference callback sequence, but the user confirmed
that it and the worker look essentially the same with gaps/lag. Built-in Viwoods
Notes is smooth. Lower maximum Java/native call duration is not a measurement of
visible ink latency. The next step is inspecting the actual 3.15.15 implementation
or obtaining the supported vendor integration contract.

## Save the ZIP

1. Update to **0.3.2-test9** through Ink Test → Updates → Check now.
2. Open **Fastnotes Ink Test → API files → Save ZIP**.
3. Choose **Downloads** in Android's file picker and tap **Save**.
4. Attach **fastnotes-api-evidence.zip** in the investigation conversation.

The export is separate from the pen test. Test7 supplied both native libraries;
inspection found pressure is forwarded into the driver. Test8 additionally
collects driver identity and any matching readable module files. If API code is unavailable, the ZIP still contains the report and readable-file inventory;
the export screen explicitly identifies that case.

## What is collected

- `manifest.json`: model, Android/build/app versions, inspected public system
  paths, existence/readability/size, DEX-entry/definition counts, selected artifact
  names and SHA-256 values.
- `driver-details.json`: kernel version, board/hardware, T1000 character-device
  identity, filtered sysfs driver/module metadata, matching module index entries,
  file readability and bounded failure types. Serial fields and memory addresses
  are not collected. Missing files do not prove the driver is built into the kernel.
- Matching readable `.ko` or compressed `.ko.gz`/`.ko.xz`/`.ko.zst` modules from
  fixed system/vendor/ODM module directories. Files remain compressed; they are
  never loaded or executed. Each module is limited to 8 MiB.
- `ink-report.txt`: the same bounded device/API/counter/observation report available
  through Copy report. It includes viewport bounds, not handwriting coordinates.
- Readable standard DEX units containing actual ENote, AutoDraw or known ENote/
  focus-monitor service definitions, including matching code in `services.jar`.
  A DEX that merely references the API is not selected.
- Readable known ENote/paintworker native libraries. If framework JARs are stripped
  of DEX, known readable framework VDEX files can supply preoptimized code evidence.

Candidates are confined to explicit `framework`, `lib` and `lib64` roots on the
read-only system, system_ext, vendor and product partitions. Driver modules
add fixed `lib/modules` directories under system, vendor, vendor_dlkm, system_dlkm,
odm, odm_dlkm and `/lib/modules`. Driver metadata is confined to selected sysfs
fields and `/proc/version`; there is no device ioctl or boot-partition read. Boot-classpath JARs
are considered only when their canonical path falls within those roots. Symlinks
resolving outside them are rejected. Known library basenames are fixed; there is
no scan of app data, user storage or private notes. Collected code is read as data:
the exporter does not load it or invoke firmware writing methods.

The ZIP contains no saved sheets, temporary scratch paths, screenshots, clipboard
contents or browser data. Nothing is automatically uploaded. Android grants write
access only to the destination explicitly selected in its document picker; no
new storage/root permission is requested. A user-selected cloud document provider
may synchronize that destination according to its own settings.

## Bounds and lifecycle

The scan considers at most 64 canonical candidates and 4,096 entries per JAR,
reads at most 64 MiB of actual code bytes (including failed reads), limits each
code artifact to 20 MiB, and limits combined code to 22 MiB. The final ZIP must
be at most 24 MiB. DEX tables/string reads and report
sizes are bounded; ZIP member names are generated rather than extracted paths.
Oversized/unreadable/nonstandard files are recorded as unavailable or skipped.
The first export successfully included framework/service DEX, and the second
included both native-library ABIs. Driver module availability remains unverified
until the on-device driver export.

Driver metadata reads are limited to 2 MiB total, 2,048 directory entries, a
small depth limit, 20,000 index lines and at most eight readable module candidates.
Only T1000-family or identified module names are considered. Preparation runs on
a separate worker after native ink is stopped. A private
`.part` file is renamed only after the ZIP closes successfully. The controller
survives activity rotation. If Android kills the process while the file picker
is open, the finalized cache can be recovered only when its schema, firmware,
Android API and app version match. The user-selected destination is opened only
when Android returns that choice. Failed or canceled saves can be retried.

Inspect user-supplied firmware archives in the research workspace. Do not commit
them or extracted vendor implementation files to the public app repository or
bundle them in an APK. Publish independently written integration changes and
source-contract findings as appropriate.


Native files are inspected statically for the pressure implementation. Their
presence does not guarantee the exact curve is recoverable: libpaintworker may
forward pressure into another dependency, kernel driver or controller firmware.
No OEM library is loaded into the app or executed by the investigation tools.


Test9 restores test7's writing screen after a reported live-ink regression in the
test8 hover experiment. This driver/API exporter is retained unchanged and runs
only when Save ZIP is selected.

The third export captured the userspace libraries/DEX again, but no kernel
driver/module. Its hardware field is `mt8788`; kernel/device identity reads
failed without numeric error codes. Another unchanged app export cannot be
assumed to gain additional access. With live System ink still failing after a
restart, the next step is a [read-only USB comparison](USB_INK_DIAGNOSTICS.md).
