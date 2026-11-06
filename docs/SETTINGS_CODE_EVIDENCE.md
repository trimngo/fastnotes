# Read-only Settings pressure-test code export

The user reports the tablet's **Settings → Pen and Gesture** pressure/sensitivity
test is fast. Its implementation is not in the framework/services exports.
Exact firmware identifies the OEM Settings package as `com.wisky.setting.se01`.
The following tool exports installed APK **code**, not the user's settings data.

On the Mac/Linux computer with working USB debugging:

```sh
curl -fL -o fastnotes-settings-export.sh https://raw.githubusercontent.com/trimngo/fastnotes/main/tools/export-viwoods-settings-code.sh
bash fastnotes-settings-export.sh --output fastnotes-settings-code.zip
```

Follow its prompt: open that pen-test canvas full-screen and keep it in front,
then press Enter on the computer. Attach `fastnotes-settings-code.zip` for static
analysis. The tool does not upload it itself. If adb is not on PATH, prefix the
bash command with `ADB="$HOME/Downloads/platform-tools/adb"` or the appropriate path.

The exporter resolves the foreground component from activity metadata, falling
back to window focus when the firmware omits resumed-activity fields. It confirms
the known system Settings package (`com.wisky.setting.se01` or `com.android.settings`). It
reads installed APKs, retaining only AndroidManifest.xml and top-level
classes*.dex. It excludes notes/app data, assets, resources and native libraries.
Raw activity/package dumps and device serials are not retained. It does not
install, enable root, modify pressure settings, or execute the exported code.

Bounds: at most eight APKs/256 MiB read, 80 MiB code, 24 MiB per DEX and final
ZIP. Private/traversal paths and unexpected foreground packages stop the export.
Existing output is not overwritten. A failed/denied read stops without escalating
access. Component/version metadata and hashes are included for matching.

The user confirmed this canvas is hosted by
`com.wisky.setting.se01/com.wisky.modulesettingmain.activity.SettingMainActivity`.
Their first Mac export stopped before reading any APK: macOS awk rejects an
unescaped slash in a regex literal's bracket expression. The path check now uses
a string-valued ERE with the same allowed installed-code locations. The old
failure was reproduced with One True Awk 20220912; the fixed full export fixture
passes with both that parser and mawk. Re-download the helper before retrying.

Host fixtures cover missing activity fields, window-focus fallback, multi-DEX
including classes10.dex, updated system APK paths, package/path validation,
denied reads, size limits, private-data exclusion and output protection.
The corrected exporter succeeded on the user's Mac. Its uploaded archive
contains Settings 2.8.0 (code 233), all three DEX units and the manifest; all four
inventory hashes match. See [installed-code findings](SETTINGS_PRESSURE_FINDINGS.md).
If a different system app hosts this canvas, paste its component/error rather
than changing the package restrictions blindly.
