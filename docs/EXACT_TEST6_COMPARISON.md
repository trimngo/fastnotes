# Compare the original test6 APK

The user reports test11 still has no live System ink: completed strokes appear
only after UP. Test11 therefore did not restore the earlier live result. The
next comparison uses the actual archived test6 APK, rather than another partial
source rollback. This is a diagnostic comparison, not a new claimed ink fix.

## What was verified

- The original APK matches its published SHA-256:
  `2aedb518b1f89d3740107bd11bb7c385c203dcedd373344ae13817484c2ff82b`.
- It is `dev.fastnotes`, version `0.3.2-test6`, code 10, target API 30, debuggable.
  Test11 is also debuggable with the same signing certificate.
- Note model/storage, app startup, manifest and updater sources are unchanged
  between test6 and test11, apart from the APK's version metadata.
- Actual APK disassembly confirms System selection suppresses Android moving
  ink and commits after its original 1,000 ms pen-up timer. Its scoped IPC schema
  matches test11. No accidental Android live preview or stale build mismatch was
  found. Historical reports show accepted System registrations.

No tablet was installed or controlled from the build environment. Android's
debug downgrade option can be attempted through the user's USB connection;
the tablet's package policy may still reject it.

## Run the original APK on Mac/Linux

Use the computer with working USB debugging and the same connected tablet.
Save any open notebook before replacing the app. These commands reinstall the
same signed package with `-r` to retain app data; do not uninstall or clear data.

```sh
curl -fL -o fastnotes-test6.apk https://github.com/trimngo/fastnotes/releases/download/v0.3.2-test.6/fastnotes-v0.3.2-test.6.apk
adb -d install -r -d fastnotes-test6.apk
```

If installation prints an error, stop and report that text. Do not use an
uninstall, root or bootloader workaround.

After `Success`, open the separate **Fastnotes Ink Test** launcher icon directly.
Select **System ink** and draw the same continuous strokes in portrait. Judge
only ink **while the pen moves**. The old delayed/disappearing pen-up behavior is
expected in this reference; its presence is not the question being tested.
Copy the report if useful, and record whether live ink is present.

## Return to test11 and compare the same way

```sh
curl -fL -o fastnotes-test11.apk https://github.com/trimngo/fastnotes/releases/download/v0.3.2-test.11/fastnotes-v0.3.2-test.11.apk
adb -d install -r fastnotes-test11.apk
```

Open the same Ink Test launcher directly, select System ink and repeat. This
also restarts the app just as installing test6 did. Record both visual results.
Installation changes process state, so a single successful old-version run is
not sufficient to attribute the effect solely to source differences.

| Test6 | Test11 after reinstall | Implication |
| --- | --- | --- |
| Live ink | No live ink | A reproducible version difference to isolate |
| No live ink | No live ink | Source rollback alone does not reproduce the earlier device state |
| Live ink | Live ink | Restart/reinstall or shared firmware state may be relevant |

These observations narrow the investigation; none by itself identifies a driver
bug or verifies pen-to-panel latency. Do not change global firmware drawing flags
to force a result.

## USB surface evidence

The previous collector subscribed to `HandWritePolicy` and `libpaintworker`.
The selected backend's surface initialization/queue errors are instead tagged
`SoftSolutionBufTransfer`, so the earlier zero native-error count did not inspect
those failures. The updated collector includes filtered counts from this tag and
read-only producer ownership queries. See [USB instructions](USB_INK_DIAGNOSTICS.md).
It remains read-only and does not install either APK itself.
