# USB-C whiteboard with saved Fastnotes sheets (experimental)

This projects the **main Fastnotes notebook** on a Mac browser over a USB-C
data cable. It does not mirror tablet pixels and needs no Wi-Fi, cloud service,
login, account or advertisements. The tablet continues using native fast
E Ink drawing; the Mac receives stylus input and authoritative saved notes.

## Start on the Mac

You need Node.js 18+, Android platform-tools (`adb`) and USB debugging
authorized for your Mac. For example:

```sh
cd fastnotes/live-whiteboard
node server.js
```

The Mac will serve `http://127.0.0.1:17171`. Project that browser tab.
The server manages `adb -d reverse tcp:17172 tcp:17172` automatically
while running. You normally **do not need to enter adb commands again**
after unplugging and reconnecting the cable. Check that the tablet still
appears in `adb devices` if authorization or USB data mode changes.

## Write on the AiPaper

1. Install the separate **Fastnotes USB preview** test APK. Open
   **Fastnotes**, not the separate **Fastnotes Ink Test** launcher.
2. Open a saved sheet (or make a new one). Its pen, Erase, Undo, Redo,
   and Sheets controls work without USB.
3. Tap **USB OFF** in the main screen header to turn it **USB ON**.
   You can switch it back off without clearing or deleting the sheet.
4. Write normally. Strokes appear live on the Mac. The document remains
   stored on the tablet independently of the cable.
5. To resume a disconnected session, reconnect USB-C. The Mac server restores
   ADB reverse and the Android relay reconnects using heartbeats and
   resends the entire current saved sheet.

**There is no Clear USB page command.** If a button changes the active sheet,
use **Sheets** to return to the prior sheet; the prior sheet remains saved.

## Persistence and recovery

- **On the tablet:** each completed pen/eraser gesture, undo and redo is saved
  through Fastnotes' existing per-sheet atomic store. USB disconnects do not
  clear the document, restart the notebook or invalidate the saved sheet.
- **On the Mac:** snapshots are cached in
  `~/.fastnotes-usb/whiteboard-latest.json`. A separate JSON copy of every
  mirrored sheet is also kept in `~/.fastnotes-usb/sheets/`, identified by its
  document UUID. The browser continues to show the last received sheet after
  unplugging or restarting the server. Reconnection restores the active sheet.
  These JSON files are recovery copies; automatic importing into Android is
  not implemented yet.
- **Live pen samples:** are temporary overlays between completed-document
  snapshots. They aren't the saved document.
- **Eraser:** the USB native renderer provides a white-painted live preview and
  commits whole-stroke erasures to the saved vector sheet. The Mac view
  redraws from the canonical note after each completed gesture.
- **Hover:** a cursor ring appears on the Mac if the firmware or Android view
  delivers proximity input. This doesn't draw a permanent stroke.
- **No networking outside the cable:** all receiver ports are bound to
  127.0.0.1. The Mac launches only the local ADB command.
- **Data privacy:** saved whiteboard cache is readable by the Mac user. No
  telemetry or remote upload. Delete that cache file to remove the local mirror.

## Important test-build caveat

This branch's GitHub Actions APK uses the default runner's temporary debug
signing key. A later downloaded APK may not install over the old USB preview.
Uninstalling `dev.fastnotes.usbpreview` **deletes that test app's saved sheets**.
Before installing any update over a version that contains valuable notes,
export/back up the data or use a stable local signing key. Do NOT uninstall
the original `dev.fastnotes`; it is a different package. Stable app signing
and safe migration remain prerequisites before this becomes lecture-ready.

This has been compiled and unit-tested in CI, but actual physical E Ink erasing,
pressure and tablet/USB recovery require an on-device test. The normal
signed Fastnotes application remains unchanged.
