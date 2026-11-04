# Full-size AiPaper 3.15.15 implementation findings

4 November 2026. Source: the user's explicit test5 API evidence export, model
Viwoods AiPaper, Android 13/API 33, system build 3.15.15. ZIP integrity and each
selected artifact's SHA-256 were verified before static inspection. No vendor
code was executed, copied into the APK or committed to this repository.

| Source unit | Bytes | SHA-256 |
| --- | ---: | --- |
| framework.jar / classes2.dex | 9,153,224 | `8fc052110acc0268d69fc2224cd1f077c35b318e7be66693b8ce721e964431dc` |
| services.jar / classes.dex | 11,752,460 | `0a66141dd1871e1a39e50ac996ff34447f4a868f2e325d4ea0a159d26040ebfb` |

## Why the view experiment did not work

`android.os.enote.ENoteSetting.setViewAutoDrawEnabled(View, boolean)` contains
only a no-op and return. `addViewUnAutoDrawRect` and `clearViewUnAutoDrawRects`
are likewise empty. Both `addUnAutoDrawRect(Rect)` and `removeUnAutoDrawRect(Rect)`
invoke the service's **remove** method. Their names do not provide a functioning
add-region wrapper. The earlier view experiment's accepted call therefore cannot
be promoted to a working firmware route.

The actual IENoteSetting interface contains add/remove region methods, marked
blacklist in its DEX. They were hidden from app reflection. Absence in the runtime
method report did not mean the Binder service lacked them.

## A different drawing path

`com.android.server.ENoteSettingService` eagerly creates `HandWritePolicy`.
`AutoDrawPolicy.addAutoDrawRect` associates rectangles with Binder calling PID,
builds their region union minus exclusions, and toggles native input for an empty
or nonempty region. `T1000AutoDrawPolicy.updateAutoDrawRegion` clears and then
**sets the nonempty native rectangle list**. This implementation differs from
the broken policy described in older community notes.

T1000's callback posts an event containing action/x/y/pressure to
`ENoteWriting.sendAutoDrawData` → native auto-draw, without app-rendered Java
bitmap blits. Its UP schedules a temporary-overlay clear after 800 ms. This is a
concrete alternate presentation route; physical access, latency and clipping for
Fastnotes still need device verification.

The Java `setT1000AutoDrawEnable` Boolean has no observed consumer in the captured
policy. Tool -1 is the Java initial value while the T1000 constructor independently
sets native pen type 10. Neither value justifies global setting changes. The
all-region-disable logic can preserve a stale rectangle list; it is avoided.

## Inspected scoped Binder contract

The independent test adapter uses only these recovered interface facts:

- Published service name: `ENoteSetting`.
- Required interface descriptor: `android.os.enote.IENoteSetting`.
- Add transaction: `0x18`; remove transaction: `0x19`.
- Each request writes the interface token and a typed `Rect` with parcel flags 0.
  Calls are synchronous with transaction flags 0, require a handled transaction,
  then consume the reply exception header.
- The server validates the token and remaining arguments. The inspected methods
  contain no additional permission/UID check. Platform service discovery/access
  can still reject the app; failure is reported without changing platform policy.
- Removal matches both calling PID and rectangle equality. The app retains its
  exact copied rectangle at process scope, including Activity recreation, until
  removal is accepted. Native region updates execute asynchronously after the
  service reply; that reply does not confirm physical panel clearing.

The adapter refuses other model/build/API combinations and checks the descriptor
before mutation. Codes are version-pinned facts, not a portable public SDK.
Cleanup on focus loss, resize, pause and exit is mandatory; generic app focus is
not automatic region ownership. A server app-death cleanup hook exists, but the
captured classes do not establish all upstream calls into it.

## Limits of the bitmap findings

ENoteWriting initializes itself in its constructor; an extra explicit init is not
a proven omission. Input callbacks are posted onto a firmware HandlerThread, so
synchronous rendering can hold up that queue. Bitmap registration and rendering
wrappers discard native return codes. `renderWriting` also sends a system-service
notification; replacing it with direct `renderBrush` would omit that behavior.
Jump/delay counts forward directly to JNI and their native semantics remain
unknown. The export budget omitted libpaintworker, so buffer aliasing, native
return values and point-filter behavior cannot be resolved from these DEX units.
No extra export is needed for the first scoped-service experiment.
