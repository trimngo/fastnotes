# USB live whiteboard (experimental)

This experiment **reuses the proven Fastnotes Ink layers mode** on the AiPaper
and shows strokes on the Mac in a clean browser window. The Mac draws from
stylus coordinates instead of mirroring the E Ink display.

The AiPaper side remains a small native Android component; a pure browser
canvas **cannot** access the private Viwoods fast-ink render path. The browser
app is the Mac viewer.

## Mac setup

1. Install Node.js 18+ if necessary.
2. From this folder run: \`node server.js\`
3. Plug the AiPaper into the Mac via a USB-C **data** cable.
4. With Android USB debugging enabled, run on the Mac:

   \`adb devices\`
   \`adb -d reverse tcp:17172 tcp:17172\`

5. Open \`http://127.0.0.1:17171\` in the Mac browser, then share that browser window in class.
6. On the AiPaper, open the separate **Fastnotes Ink Test** launcher icon and tap
   **USB whiteboard**. That starts the existing **Ink layers** mode and sends pen
   coordinates through the cable. The Mac viewer should report **AiPaper connected via USB-C**.

If \`adb reverse\` says \`not support command\`, the firmware's restricted ADB
implementation prevents this USB method. On the full-size AiPaper,
\`adb shell\` has previously been observed to fail, so **reverse is not yet verified**.
Do not assume that installing this APK alone proves USB support.

## Scope

- No ads, cloud service, login, mirroring, or screen capture.
- Keeps the existing ink rendering modes and notebook storage intact.
- The viewer starts empty for each connection; it is a projection tool,
  not a note archive.
- Designed to run only over ADB's localhost USB reverse tunnel. Both Mac ports
  are bound to localhost, not the LAN.
- Eraser is shown as a broad white stroke in the viewer, so its result may not
  exactly match Fastnotes' stroke-object eraser. This is a diagnostic first pass.
- Tests on the actual full-size device are still required for live latency,
  pressure width and USB transport behavior.

## Safe installation

The experimental `debug` variant uses app ID `dev.fastnotes.usbpreview`, so
it installs alongside the original `dev.fastnotes` app without replacing or
clearing the original saved notebook. GitHub Actions uploads the test APK
named `fastnotes-usb-preview-debug` for this branch's pull request.
Do not uninstall the original Fastnotes.

If the USB debugging prompt appears, approve the Mac before running
`adb -d reverse tcp:17172 tcp:17172`. Verify the mapping with
`adb -d reverse --list`. This requires Android USB debugging; a USB cable
alone is not enough to create the app-to-Mac connection.

## White erasing and pen proximity cursor

**USB whiteboard mode only:** the eraser paints opaque white into the native
transparent ink buffer, matching the Mac's white eraser stroke. This avoids
relying on transparent pixels to remove previously presented E Ink pixels.
It is **visual white overpainting**, not a vector-object deletion. It is
appropriate for the plain white projection canvas; do not use it with patterned
or non-white backgrounds.

When the firmware supplies stylus `HOVER_MOVE` or `HOVER_ENTER` without pen
contact, the Mac browser draws a thin cursor ring at that position. Flipping
to the eraser (or using a stylus eraser button) shows a wider dashed circle.
The cursor never becomes permanent ink and is hidden while writing, on
hover exit, on reset, and on USB disconnect. Actual hover delivery on the
full-size AiPaper must be confirmed with device testing; some firmware may
suppress hover callbacks.
