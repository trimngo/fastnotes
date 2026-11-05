# Read-only USB ink comparison (Mac/Linux)

Use this after System ink shows only completed strokes at pen-up. The app's
accepted-registration message cannot show whether the firmware input callback
ran. This comparison counts an existing firmware log marker during Fastnotes
and built-in Notes, and collects readable kernel/driver identity. It does not
change the app or firmware.

1. Install Google's [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools).
   Make `adb` available in your terminal, or use the `ADB` path override below.
2. Enable **USB debugging** in the tablet's Android Developer options, connect a
   USB data cable, and accept the tablet's USB-debugging authorization prompt.
   Run `adb -d get-state`; it should print `device`. If the Developer options or
   authorization prompt are unavailable, report that step rather than continuing.
3. Download the collector from this repository:

   ```sh
   curl -fL -o fastnotes-ink-diagnostic.sh https://raw.githubusercontent.com/trimngo/fastnotes/main/tools/collect-viwoods-ink.sh
   bash fastnotes-ink-diagnostic.sh --output fastnotes-usb-evidence.tar.gz
   ```

   If `adb` is inside an extracted Platform Tools folder instead of your PATH:

   ```sh
   ADB="$HOME/Downloads/platform-tools/adb" bash fastnotes-ink-diagnostic.sh --output fastnotes-usb-evidence.tar.gz
   ```

4. Follow the terminal's two prompts. First open the separate **Fastnotes Ink
   Test** icon. Press Enter in the terminal, then tap **System ink** (again if
   already selected) and draw fast loops/up-down strokes for 15 seconds. This
   order also captures region-registration errors. Next open a blank built-in Notes
   page, press Enter and draw the same pattern for its 15-second comparison.
   Keep both tests full-screen in portrait initially. Note whether ink was visible
   during motion or appeared only after lifting.
5. Attach `fastnotes-usb-evidence.tar.gz` to the conversation and report the two
   visual results. It is created locally; the script does not upload anything.

The collector uses `adb -d` to select one USB device. Disconnect any additional
USB Android devices if selection is ambiguous. Existing output is not replaced.
No root, bootloader unlock, firmware-property change, log clearing, app install,
raw input capture or note-file read is used. Raw log lines are filtered in memory
and are never written to the archive. It retains marker counts/timestamps/PIDs,
fixed kernel/board metadata and filtered driver/module identity. Device serials,
hostnames, handwriting coordinates and pressure samples are excluded.

A positive `test_dirty onInput: finish` count establishes that the inspected
system callback forwarded input to the policy, not that the queued native draw
completed. Zero counts alone do not prove the service is inactive: Android log
access, logging configuration and firmware differences can suppress visibility.
Native error counts, when present, identify fixed command categories; their raw
messages and numeric pen values are discarded. Comparing with working built-in
Notes narrows the next investigation without claiming measured panel latency.

Host validation: `bash tools/tests/collect-viwoods-ink-test.sh` passes with a
fake USB ADB endpoint, covering callback/error filtering, private-data exclusion,
stale log-tail removal, authorization and metadata failures, missing tools and
output protection. The script has not yet been run against the user's tablet.
