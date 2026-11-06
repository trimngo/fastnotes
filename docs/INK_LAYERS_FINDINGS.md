# Reserved native pixel values and the Ink layers comparison

**Device follow-up:** the user reports test12 Ink layers works, without pressure
sensitivity, but feels slower and sometimes disappears/reappears. This is the
working pixel-format baseline. Test13 preserves its worker presentation policy,
replaces the diagnostic quiet-delay completion with a frame handoff, and adds an
optional direct presentation comparison. See [current work](FRAME_HANDOFF_AND_LATENCY.md).

5 November 2026. Full-size AiPaper, Android 13/API 33, firmware 3.15.15,
`ro.eink.type=SoftSolution`. This is a controlled pixel-format comparison, not a
claimed recovery of the earlier System preview or a verified notebook fix.

## Latest USB surface capture

The original test6 was installed. Both 15-second windows completed. During
Fastnotes System ink, system_server PID 908 emitted 928 policy callbacks and one
SoftSolution surface-init entry. No filtered lock/dequeue/queue failures appeared.
WritingProducer's service-owner PID remained 528. Client queries were unavailable
with exit 0; that does not mean there were no clients.

The Notes window recorded surface events from PID 6283: two init entries, one
exit, and one sendBuffer state with isInit=0/shouldInit=0. The latter branch skips
queuing intentionally and is not an error. Init's connected_to_cpu=0 value is
logged on entry, before connection/locking, not as a failed return result.
Connection and initial queue return codes are not both logged. These counters
therefore show attempted setup, not successful visible presentation.

The user clarified they drew **new strokes on an existing note page**. Existing
handwriting does not invalidate this service/ownership capture. No note content
or pen coordinates are in the archive. Its new-stroke visual timing is a separate
observation; the counts cannot establish that. A blank-page retake is not needed
solely because the Notes page already contained writing.

## A separate, verified image-format hazard

The selected native compositor scans the highest-role foreground bitmap over
the updated rectangle for exact opaque gray **0xFFFEFEFE**. Finding any such pixel
switches that rectangle into its mask/erase branch. Ordinary foreground pixels
different from the sentinel—including black live ink—then become transparent
zero; sentinel pixels instead fall through to background. Without this sentinel,
normal rendering uses foreground role 1 and ignores ordinary role 0 background.
Opaque gray **0xFFFDFDFD** also has reserved output behavior: it becomes the FE
command. These are not interchangeable with ordinary antialiased gray pixels.

Verified independently in both supplied ELF ABIs:

| Behavior | ARM64 | ARM32 |
| --- | --- | --- |
| Whole-rectangle FE scan | 0x134cc–0x13548 | 0xe934–0xe976 |
| Mask branch suppressing ordinary foreground | 0x13638–0x13654 | 0xe9d0–0xe9e4 |
| Normal foreground and FD command mapping | 0x13674–0x136bc | 0xe9f6–0xea2a |

Fastnotes' opaque native page combines antialiased black ink with white paper.
A software fixture using the real PaperRasterizer, integer endpoints and a
3-pixel line naturally produced **9 FE and 12 FD pixels**. No sentinel was
injected. The same line on transparent black ink produced **zero reserved
pixels**. A test model of the external compositor rule shows how ordinary black
can disappear in the opaque case while remaining available in the alpha case.
This establishes a real encoding hazard, not its frequency in the user's strokes
or proof that it caused all reported physical gaps.

## Stock reference and limits

The public stock overlay creates full-display ARGB_8888 writing/preview bitmaps
cleared to transparent zero. NoteView registers the preview bitmap from
RjHandWriting.getmBitmap2, while BasePen writes its live line into that canvas.
This differs from Fastnotes' full opaque paper image. Sources:

- [NoteView registration](https://github.com/Anemone95/viwoods-notes/blob/0a62248c959788c776a8d1b4cffb4d1e99949789/overlay/smali_classes2/com/wisky/rjwrite/NoteView.smali#L6328)
- [RjHandWriting initialization](https://github.com/Anemone95/viwoods-notes/blob/0a62248c959788c776a8d1b4cffb4d1e99949789/overlay/smali_classes2/com/wisky/rjwrite/RjHandWriting.smali#L14240)

That overlay is not verified as the user's exact installed Notes version.
ImageProcessor.transformImage and BasePen.writeLine02 are not fully inspected;
they can affect later buffer contents, especially existing pages. Do not claim
that all stock buffers always remain transparent, that the precise stock colour
contract is recovered, or that its pressure curve has been extracted.

## Test12 implementation and device check

The explicit **Ink layers** comparison uses the same worker scheduling,
full-display placement, clipping and distinct native foreground/background roles
as Worker refresh. Its bitmaps contain transparent black ink without native
paper/ruling. Clear/rebuild uses CLEAR, and dirty/background copies use SRC so
removed alpha pixels and antialiased edges do not accumulate. Android draws the
paper underneath when producing the existing completed quiet snapshot.

The normal notebook, Worker refresh, Reference callback and System request remain
available with their existing defaults. This is a pen-tip scratch comparison;
live eraser behavior and full notebook integration are not validated by this
change. No OEM code is copied/executed, no native timing/global flag/driver call
is added, and no new note-storage format or permission is introduced.

After updating in place, open **Fastnotes Ink Test → Ink layers** and use the pen
tip for rapid continuous strokes. Judge ink **while moving**. The Android quiet
snapshot still waits one second after pen-up, so completed ink alone is not a
positive live result. Compare Worker refresh on the same device/orientation,
select the visual outcome, and Copy report. Only a positive physical result
justifies integrating this layer scheme into the notebook/eraser workflow.
