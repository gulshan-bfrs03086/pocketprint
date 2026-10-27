# TSC's library (optional)

PocketPrint can drive a TSC printer through TSC's own Android library, `tscsdk.jar`
(class `com.example.tscdll.TSCActivity`), instead of its own Bluetooth code. It is
off unless the build includes the jar and a printer is set to use it.

## Why it is optional, and not in this repository

The library comes with TSC's SDK download and ships **no licence terms**. Until
whoever distributes PocketPrint has checked that TSC allows redistributing it,
the jar is not committed (`app/libs/*.jar` is gitignored) and a build without it
works as before, apart from the one install-time permission line below. If you distribute an APK that contains it, that is your
decision to have checked.

## Building with it

Put the jar at `app/libs/tscsdk.jar`, or point at it:

    ./gradlew assembleDebug -PtscSdkJar=/path/to/tscsdk.jar

With the jar, `src/tscsdk` is compiled and the TSC option appears in the app.
Without it, `src/notscsdk` is compiled instead and nothing offers the option. A printer
that was saved to use it can still be freed from *Printer settings*, and says so when a
job is sent. `BuildConfig.TSC_SDK_BUNDLED`
tells you which you have.

## Using it

- **Setting up a printer:** on the setup card, *Connection* → *TSC library*, then
  *Set up*. Setup connects, reads the model, checks the status, sends the test
  label and confirms completion, all through the library.
- **An existing printer:** *Printer settings* → *Connect through the TSC library*.

Re-running setup keeps the choice (it is part of the printer's saved connection).

## Permissions

TSC's library calls `BluetoothAdapter.cancelDiscovery()` before it connects, and
does not catch the refusal if Android says no. On **Android 11 and older** that
needs `BLUETOOTH_ADMIN`: an install-time line capped at API 30, prompting for
nothing, listed in `scripts/expected-permissions.txt`. It is declared in every
build. It is not only for the library: the app's own transports call
`cancelDiscovery()` and `createBond()` too, inside `runCatching`, so until the line
existed those calls failed silently on Android 11 and older.

On **Android 12 and newer** the same call needs `BLUETOOTH_SCAN`, a runtime
permission this app deliberately does not hold. A printer set to the TSC library
there fails with a message saying so and to set it back to Automatic. Supporting it
means deciding to ask for scan permission, which is a promise to change on purpose.

The first hardware print through the library failed exactly this way: the job
ended in an instant with "Need BLUETOOTH ADMIN permission" and no Bluetooth traffic
at all.

## What it is, and what it is not

The library is a thin, blocking wrapper over **one secure classic-Bluetooth (RFCOMM)
socket**. So, compared with PocketPrint's own Bluetooth code:

- no Bluetooth LE: the library has no GATT path;
- it reports only `"1"` or `"-1"`, never why, so errors are less specific;
- it cannot read a printer's replies, only ask the two questions it has calls for.
  `TscSdkTransport` answers the two the app asks - the status byte (`ESC ! ?`) and
  the model (`~!T`) - through the library's `status` and `printername`, and gives
  no reply to anything else.

Everything *above* the transport is unchanged: the TSPL we build, the trimming of
blank space on continuous paper, compressed bitmaps and the wait for the printer
to say it has finished all apply, because the library is only the pipe.

## Status

The library's `status(ms)` asks `ESC ! S` and decodes the six-byte answer
(`02 b1 b2 b3 b4 03`) to a word. `TscStatusWords` maps those words onto the
one-byte status the completion logic reads:

| Library word | Status byte | Meaning to the app |
|---|---|---|
| Ready | `00` | idle |
| Printing Batch, Cutting | `20` | busy |
| Pause, Waiting to Press Print Key, Waiting to Take Label | `10` | needs attention |
| Head Open | `01` | fault |
| Paper Jam | `02` | fault |
| No Paper, Paper Empty | `04` | fault |
| Ribbon Empty, Ribbon Jam | `08` | ignored (direct-thermal noise) |
| empty, or a word not in this table | *none* | silence |
| `-1` | *none* | **the connection is gone**: an error, never silence |

Silence stays silence on purpose: a printer taking in a job answers nothing, and
the completion logic reads that as "busy". Mapping it to a status would read it
as "ready".

## Three things the library gets wrong, and what is done about them

- **Unread replies pile up.** The library writes a question and reads whatever is
  waiting, and never clears the socket first, so a reply that arrived late for one
  question is returned as the answer to the next. The plain Bluetooth transport
  drains before every question; here `TscLibrary.discardUnread()` does, by reaching
  the library's private input stream by reflection (R8 is told to keep it). If the
  field is not there - a different jar - it quietly does nothing.
- **`-1` is a dead link, not silence.** It comes back at once when the library's
  streams are gone or a write or read threw. `TscSdkTransport` throws on it, which
  ends the job as a lost link rather than waiting out a printer that is not there.
- **`openport` cannot be interrupted.** A job cancelled while it connects still gets
  a connected socket back. The library object is stored *before* dialling, so the
  `close()` the engine always makes reaches it.

## Two library calls that are not used, and why

- `printerstatus_byte(ms)` returns the first byte of a *static, shared* buffer and
  does not clear it, so a silent printer reads back as a stale `00` ("ready").
- `printer_completestatus()` waits for a reply in a loop with **no timeout**; a
  printer that never answers hangs the thread.

## Threading

`TSCActivity` is an `Activity`. Constructing one builds a `Handler`, which throws
off a thread with no Looper, so `TscSdkTransport.open()` constructs it on the main
thread. Every other call blocks and runs on a background thread. The library keeps
some state in static fields, so two TSC printers should not be driven at the same
moment.
