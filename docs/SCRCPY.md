# Live ink in scrcpy

The user reports scrcpy shows Fastnotes ink only after lifting the pen.
Screen sharing mode makes native pen updates visible in Android's captured
composition while preserving the Viwoods worker-based fast-ink path.

## Enable capture

In the notebook, open **Settings → Screen sharing (scrcpy)**. Leave
**Viwoods fast ink** enabled when available. In the temporary **Fastnotes Ink
Test**, select **Scrcpy**; its existing Pressure checkbox remains available.

The notebook retains its existing fixed-width saved-stroke format. The temporary
Scrcpy comparison supports the same pressure response as Ink layers. No per-point
pressure is silently added to notebook storage.

## Start scrcpy over USB

With USB debugging enabled and the tablet connected:

```sh
scrcpy -d --video-codec=h264 --video-bit-rate=16M --max-fps=60 --video-buffer=0 --no-audio --no-control
```

This starts at the device resolution to preserve thin-line detail. H.264 is the
recommended first codec for latency. The 60 FPS option is a ceiling; it does not
force Android or the tablet's encoder to deliver 60 frames per second.
Zero video buffer removes an additional client playback queue, not encoding,
USB, decoding or display delay. The tablet's actual encoder throughput is
unmeasured. If the computer view accumulates delay or the encoder cannot keep
up, add `-m1920`, then `-m1600` if needed, trading resolution for performance.
Inspect scrcpy's encoder messages when choosing a size.

`--no-control` prevents mouse/keyboard input from the computer; the tablet pen
continues to work. It can be omitted if computer control is wanted. The command
uses flags documented by scrcpy v5.0.1; check `scrcpy --version` and update
scrcpy if an older installation rejects them.

## What changes in Fastnotes

Viwoods ink can be presented outside Android's ordinary screen composition.
scrcpy cannot capture pixels that the app never draws into that composition.
The previous diagnostic deliberately hid moving Android ink, and the notebook
requested native redraws only after stroke completion.

Sharing mode uses native-derived coordinates and computed widths to draw stable
vector snapshots in the ordinary Android View during movement. It does not draw
an independent copy from duplicate Android pen events. Snapshot rendering runs
outside the native pen lock and never uploads the mutable callback-owned bitmap.
Notifications are coalesced to Android frames rather than copying a display-sized
bitmap on every pen sample. A snapshot still copies current-stroke sample
references under the lock; very long held strokes need device performance checks.

The tablet's native worker, transparent layers, clipping and recent-region
coverage remain active. Android front-buffer rendering is bypassed for this
mode so captured ink has a normal composed surface. Completed-frame
acknowledgement preserves the matching controller/revision and rejects stale
callbacks after mode, pane, note or lifecycle changes.

## Verify the recording

Add a recording path:

```sh
scrcpy -d --video-codec=h264 --video-bit-rate=16M --max-fps=60 --video-buffer=0 --no-audio --no-control --record=fastnotes-scrcpy.mkv
```

Use a temporary sheet. Draw light/heavy strokes in the test, fast loops, dots and
rapid successive strokes. Confirm ink appears while the tip moves and keeps its
shape after lifting. Test erasing/canceling, both landscape rotations, portrait,
scrolling and Chrome split-screen. Compare against Screen sharing disabled.

Inspect the encoded recording for missing/doubled strokes, stale trails and
pen-up changes. A high-speed camera showing the pen and tablet is needed to
measure physical pen-to-panel latency. Host rendering tests cannot establish
the tablet overlay's behavior, USB latency or hardware encoder quality.
H.264 is lossy; compression/resampling detail is separate from incorrect
application stroke geometry.

## References

- [scrcpy video options](https://github.com/Genymobile/scrcpy/blob/v5.0.1/doc/video.md)
- [USB selection](https://github.com/Genymobile/scrcpy/blob/v5.0.1/doc/connection.md)
- [Recording timing](https://github.com/Genymobile/scrcpy/blob/v5.0.1/doc/recording.md)
- [Android capture implementation](https://github.com/Genymobile/scrcpy/blob/v5.0.1/server/src/main/java/com/genymobile/scrcpy/video/ScreenCapture.java)
