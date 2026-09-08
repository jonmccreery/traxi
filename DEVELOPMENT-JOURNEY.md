# Traxi: the four-session journey

Reconstructed 2026-09-07 from the four exported transcripts (`session1.txt`
through `session4.txt`), the git history (48 commits, 2026-09-05 11:14 →
2026-09-06 23:12), and `ENGINEERING-RECORD.md`. This is the narrative
companion to that record: the linear sequence of events, the design decisions,
the times things went sideways, and how each was found and closed.

**A note on ordering.** The transcript file numbers do not match chronology.
Session 4's flash evidence shows fix records resuming at `2026-09-06T22:47:44Z`
(17:47 local, UTC−5) and its commits run 18:43–21:38; session 3's commits are
the five newest in the log (22:05–23:12), and it opens by calling the project
"stable... on the management and telemetry side" — features session 4 built.
So the true order is:

| Order | Transcript | When (local) | Theme |
|---|---|---|---|
| I | `session1.txt` | Sep 5, 11:00 → Sep 6 ~10:30 (+ epilogue) | Zero to hardware-verified; the USB corruption saga |
| II | `session2.txt` | Sep 6 morning → ~17:15 | The one-shot USB fix, erase, the silent capture failure, v1 |
| — | *(not exported)* | Sep 6 ~14:50–15:35 | Parallel session: rename to Traxi (`b9744e1`), telemetry (`3a2286e`), op serialisation (`aafd862`) |
| — | *(off-session)* | Sep 6 afternoon | The user's solo device recovery: SPI-level format, despair, power cycle |
| III | `session4.txt` | Sep 6 ~17:45–21:40 | Recovery verified; the Bluetooth regression; ground-truth recording |
| IV | `session3.txt` | Sep 6 ~21:45–23:15 | The wrap probe, the detector that backfired, the spiral, the last recovery |

Two standing facts frame everything below, both stated by the user and both
earned the hard way:

- **The device is the irreplaceable thing.** The BT-Q1000XT (Qstarz, MediaTek
  MTK chipset, discontinued) outranks the data — "the data -does- not matter.
  there are 15 copies... this device, as i've said 60 times, is the
  irreplacable piece."
- **Silent data loss is the worst outcome.** "silent lack of logging is the
  absolute worst possible outcome of a logging run. ----worst----"

---

## Part I — session1.txt: zero to hardware-verified

**Goal.** "relatively complete application development against a portable gps
tracker" — an Android app to download and manage the logger from the trail,
because "when i'm on trail, my phone -is- my computer." Bluetooth first (the
user's call), USB later.

### 1. Survey and corrections

The prep doc and the existing Python parser (`data/mtkparse.py`) were run
against the raw flash dumps. This established the golden pair used by every
test since: **`cdt_v2.bin` (5,376,000 bytes) → 126,613 fixes, 0 checksum
failures, 82 sectors, spanning 2025-03-16 → 2026-09-04**. `trip.bin` and
`cdt_full.bin` turned out to be byte-identical 4 MB prefixes of it — the log
had never wrapped.

Three prep-doc claims died on contact with the raw bytes:

- The "sentinel table" (31 bad records at lat 90.0 / ele 150.0) was a GPX
  `%.1f` rounding artifact — there was **one** bad record.
- The format register never changed mid-log; the "segments" were power cycles.
- The spike count was 42, not 26 — and 40 of the 42 carried `VALID == 2`
  (estimated fix), so masking on VALID, not on float sentinels, became the
  quality rule.

Device format at this point: `FMT_REG 0x000A003F` (UTC, VALID, LAT, LON,
HEIGHT, SPEED, RCR, DISTANCE — 42-byte records), firmware
`AXN_1.30-B_1.3_C01`, and all timestamps needing the **+1024-week GPS
rollover correction** (raw dates read 2005–2007).

### 2. The Kotlin parser, and the sentinel that wasn't 90.0

`:core` was built as a pure-JVM module (no device, no emulator) with a
golden-file test asserting the exact fix count. Two test failures immediately
exposed that the sentinel latitude is **`89.9999999999996`** — raw double bits
`40567FFFFFFFFFE4` — not `90.0`. An exact-equality mask would have compiled,
passed a casual review, and silently masked nothing. `Fix.isSentinel` became a
tolerance test. 20 tests green.

A side deliverable landed here too: the GNSS technical document
(`gnss-and-device-internals.{md,html}`), whose analysis overturned another doc
claim — elevation is *not* quantized to 0.1 m (125,140 of 126,592 heights are
distinct; the decimetres were GPX formatting) — and measured ~2 m 1σ vertical
accuracy across 103 stationary stops.

### 3. The protocol layer, and a bug caught before hardware

`PmtkClient`, `FlashDownloader`, `GpxWriter`, and a simulated logger transport
were built next. The round-trip test caught a real defect in `awaitSentence`:
it returned as soon as it matched a sentence and **discarded every sentence
already parsed after the match in the same read batch**. Over a real RFCOMM
link — where a 64 KB block arrives as 32 chunk sentences of the form
`$PMTK182,8,<addr>,<4096 hex chars>*XX` — that would have dropped most chunks
of every block. Fixed before any hardware saw it. 52 tests.

### 4. The Android app, and Bluetooth four failures deep

The `:app` module (minSdk 31, Compose UI with Device/Dumps/Config/Log tabs,
foreground `DownloadService`) parsed the real 5.4 MB image on the phone to an
exact golden-file match. Then Bluetooth failed four different ways, each
presenting as nearly the same opaque socket error:

1. **Crash on pair** — `CompanionDeviceManager.associate()` throws
   `IllegalStateException` *synchronously* (bypassing its failure callback)
   when the manifest lacks `<uses-feature
   android.software.companion_device_setup>`.
2. **Association ≠ bonding** — CDM association creates no link key; RFCOMM
   needs one. `Bonding.ensureBonded()` was added (waits on
   `ACTION_BOND_STATE_CHANGED`, 60 s budget).
3. **A filter that silently dropped devices** — `setNamePattern(".*")` only
   tests devices *after* their names resolve; unresolved devices never
   appeared. Replaced with an empty filter plus a "Likely loggers" /
   "Show all paired" UI split.
4. **The PIN.** The user was prompted and typed 1111 — rejected with a bare
   `AuthenticationFailed`, indistinguishable from out-of-range. **0000 paired
   immediately.** The device uses `LegacyPairing: yes` (BT 2.0 PIN, not SSP);
   `Bonding.KNOWN_PIN = "0000"` went into code and UI copy.

A USB detour along the way (the user: "how about taking a look at the device
over usb?") identified the hardware — `0e8d:3329 MediaTek`, **native CDC-ACM,
no bridge chip** — and overturned two "device lies" that were actually
host-side decoding failures: `$PMTK182,2,9` returns a **JEDEC RDID**
(`1C 70 17` = EON, capacity code 0x17 → 8 MB), not a broken byte count; and
status field 8 is a **write-pointer byte address** (`0x0051F3C2` = 5,370,818
bytes), not a record count. A full USB dump from the laptop set the reference:
**5,505,024 bytes in 83.5 s, 64.4 KB/s, zero retries, zero corruption.**

### 5. First Bluetooth download: three transfer bugs at once

The first real BT download died after one 64 KB block. The transcript showed
three independent defects:

- **A fixed 10 s block timeout.** Over RFCOMM only 20,480 of 65,536 bytes had
  arrived when it expired. Replaced with an *idle* timeout (progress resets
  the clock; 240 s hard cap).
- **No stream reset between requests.** Chunks from the abandoned block were
  still in flight and poisoned the next block's assembly. `resetStream()`
  before each block.
- **`filled == 0` treated as end-of-flash.** Wrong: erased sectors still
  *answer*, with 0xFF data — 0xFF is byte-identical to erased flash.
  Termination became "two consecutive unwritten blocks";
  `ATTEMPTS_PER_BLOCK = 3`.

The regression tests use an injectable `TickingClock` and assert that more
than 30 s of *simulated* time elapsed, so the test can't degenerate into one
that passes without exercising the timeout.

### 6. The stale link key

After a device power cycle, connects failed again with no PIN prompt. The
user's observation cracked it: "the one time we successfully connected from
this device, i was prompted for a pin. this isn't happening now. why?"
`dumpsys bluetooth_manager` showed `bredr_linkkey_known:T ...
bredr_authenticated:F bredr_encrypted:F` — the legacy device forgets its link
key across a power cycle while Android keeps hers as `BOND_TYPE_PERSISTENT`,
so `bondState` reads `BOND_BONDED`, `ensureBonded()` short-circuits, and
authentication fails with no prompt. Fix: on connect failure while "bonded,"
`removeBond()` (via reflection), delay, re-pair, retry. The PIN prompt
returned.

### 7. 493 B/s, a wrong fix, and a rule that held forever

The download ran but at **~493 B/s**. A rewrite of the read path (blocking
reader thread, on the theory that polling `available()` throttled it) measured
*identical* — 493 B/s. The real cause is arithmetic: 493 B/s of decoded
payload is ~986 hex chars/s on the wire, which is a **9600-baud UART** — the
BT-Q1000XT bridges its Bluetooth module to the GPS chip through an internal
9600-baud serial link. A full 5.5 MB read is ~3 hours and no host software can
change it.

Worse, the user caught a regression: "we've regressed. check the log. it
failed this time instead of slowed." The rewrite had introduced a new failure
("transport closed while awaiting response") *and* its failure path returned
**zero bytes** where the previous run had at least kept a 64 KB partial.
Reverted (`c13ebf5`), with the rule that held for the rest of the project:
**never discard a partial download.** `FlashDownloader` catches, records the
failure, and returns the bytes read; the session saves partials
unconditionally; `Result.isComplete` distinguishes outcomes.

### 8. Incremental download

`planIncremental`/`downloadIncremental` (`7eb6e0b`) made the 9600-baud bridge
irrelevant for daily use: a **wrap probe** reads records at
`PROBE_OFFSET = 512` — the oldest data, first overwritten in OVERLAP mode —
and compares them against the previous dump (records, not sector headers,
because headers look alike across a wrap). On mismatch it falls back to a full
read. Misaligned dumps are trimmed to whole blocks; the final block is always
re-read because its sector header was mid-write (count `0xFFFF`).

First hardware run found two more bugs: the probe requested **512 bytes and
the real device ignored it and dropped the RFCOMM link** (Broken pipe) — the
probe had been sized against the permissive simulator — and a zero-byte
partial was saved and then parsed into a "0 fixes" card. Both fixed; the
second run: **10 m 32 s, 4 blocks instead of 82**, result byte-identical to
the USB dump through the write pointer, 126,644 fixes (+31 real ones, the
newest at 41.88530, −87.80312). Verified over Bluetooth (`0e0298d`).

### 9. The erase reversal

The user revealed the bedrock use case: a thru-hike fills the flash in weeks —
"the -critical- feature on a thru hike... the bedrock goal was dump, erase,
repeat until the end of the trail." Erase went from deliberately-out-of-scope
(the assistant's safety-driven call) to the app's reason to exist. The
assistant named its own error: "my safety argument was correct, but I used it
to justify not building the feature instead of building it carefully." §0.2
was written with a seven-clause gate: verified same-session dump; coverage
past the write pointer (read fresh at erase time, not cached — the logger
keeps recording after the dump); typed confirmation naming the exact fix
count; never reachable from a download callback; disable → erase → re-enable
with a long timeout; read back sector 0 to confirm 0xFF; never delete the
pre-erase dump.

### 10. The USB corruption saga

USB in the app was hand-rolled CDC-ACM (~150 lines, chosen over adding a
JitPack dependency whose only needed driver was CDC-ACM). First contact with
the device: writes accepted, **zero bytes back** — because this device's
**CDC interfaces are reversed** (if0 = DATA with the bulk endpoints, if1 =
COMM), so the hardcoded `wIndex = 0` sent `SET_CONTROL_LINE_STATE` to the
wrong interface and **DTR was never asserted; the device is mute until it
is.** Fix: `wIndex = 1`.

Then the corruption: on the phone, roughly **1 block in 8 came back short**,
missing whole 2048-byte chunks — while the same device over the same cable
from the laptop showed zero corruption. The user redirected the investigation
toward measurement ("adding instrumentation... at a minimum time of arrival of
each sector.. would show you"), which exposed something worse: `readLogBlock`
pre-filled its buffer with 0xFF, so lost chunks left **holes byte-identical to
erased flash** — proven by a diff at `0x002C17FC`. Damage detection
(`Result.damagedRanges`, "DOWNLOAD DAMAGED" in the UI) made the loss loud
before anything tried to fix it.

Then five fixes in a row failed or made it worse:

| Attempt | Result |
|---|---|
| Floor the bulk read timeout at 250 ms | No change |
| Hand-rolled reader thread keeping a read posted | Whole-chunk gaps 5 → 0; corruption remained |
| Raise the transfer timeout to 10 s | **Worse** |
| Split requests into 8 KB for flow control | **Worse**; reverted |
| Swap to `usb-serial-for-android` 3.8.1 | Gaps 5 → 1; same corruption character |

The one clean win was fixing the **retry idle timeout**: chunks arrive ~33 ms
apart, so the 10 s wait before retrying was 300× any legitimate gap — 58% of a
240 s run was retry idle waste. At 2.5 s: **152 s, 0 unrecovered damage,
129,115 records, first diff exactly at the old write pointer.** The corruption
itself was survived, not fixed.

The session closed with `USB-TRANSFER-HANDOFF.md` (462 lines), preserving the
key clue for the next session: malformed sentences were **quantized** — 9 ×
exactly 2,840 chars, 2 × exactly 6,961, where a healthy chunk sentence is
4,119 chars (`$PMTK182,8,AAAAAAAA,` = 20, + 4,096 hex, + `*XX` = 3). Nine
lines landing on the same byte count is not packet loss; it is a fixed buffer
boundary. And `6961 = 4119 + 2842` — one good sentence with a ~2,840 fragment
welded on, the second header lost. The missing bytes were one off from
`1280 = 20 × 64` — a whole number of max-size USB packets.

### Sideways in Part I, and what it taught

- **Blind coordinate taps** connected to the user's Denon amp
  (`C4:30:18:C9:61:76`), wandered into Android's USB settings, and nearly
  touched a share sheet listing contacts. Automation halted each time;
  "screenshot or dump UI state before tapping" became a standing rule.
- **Abort drive.** The hand-rolled transport was defended through hours of
  failed fixes. The user: "you've been defending your hand rolled
  communications protocol that you argued for because of a single
  dependency... you're running into abort drive... let's just fix it." The
  swap didn't fix the corruption either — which was itself the diagnostic
  signal Part II ran on.
- **The battery scare.** No charge LED; a differential-wattmeter test produced
  a physically impossible negative delta; the assistant proposed draining the
  cell to make the test conclusive. The user: "i'm not going to waste
  potentially the last power this thing ever gets on a test. that would be
  crazy." Resolution: reseating the battery. ("hah! i just reseated the
  battery... have a light.")
- **Reinstalling over a live connection** orphans the SPP socket — the logger
  serves exactly one SPP client — so the next connect fails confusingly. Read
  by the user as a regression until disentangled.
- The meta-lesson, written into the handoff: separate signal from
  interpretation. **"Signal keeps. Frames rot."**

Commits: `6093e1b` parser → `3f7fc9b` protocol → `b2e542e` app → `382a6bf`
PIN → `161d60b` BT verified → `c13ebf5` keep partials → `7eb6e0b`/`0e0298d`
incremental → `a5e9662`…`023e9b3` the USB saga → `c525787` handoff.

---

## Part II — session2.txt: the one-shot fix, erase, and the silent capture failure

**Goal.** "let's get back into the transport bug... this one's got my interest
piqued :)" — then erase, then v1.

### 1. The mic-drop fix

Instead of testing the handoff's hypotheses on hardware, the session
**decompiled the dependency** — `unzip` the cached AAR, `javap -p -c
CommonUsbSerialPort.class` — and read the mechanism in thirty lines of
bytecode:

```java
if (timeout != 0) nread = mConnection.bulkTransfer(ep, dest, len, timeout);
else { mUsbRequest.queue(ByteBuffer.wrap(dest, 0, len), len);
       mConnection.requestWait(); nread = buf.position(); }
```

`bulkTransfer` returns −1 on timeout and **discards the bytes already
received into that URB** — the caller can't even learn how many there were.
The app passed `readTimeout = 200`, so every read took the lossy path. Fix:
**`readTimeout = 0`**, selecting the `UsbRequest` path, which has no timeout
to expire and nothing to discard; plus `readBufferSize` 64 → 4096 (the library
defaults to one max-packet).

First hardware run: **0 retries, 0 malformed chunk lines, 0 gaps, slowest
block 1,317 ms (was 10,782), 95 s of block time (~110 s wall)**, byte-identical
to the previous capture across the entire 5,479,850-byte common prefix
(`eadbb81`). The user: "decompiling the library to find it with immediate
problem resolution as a result is a mic drop."

Two footnotes with teeth:

- `readTimeout = 0` would have worked in the hand-rolled version too — but an
  infinite `bulkTransfer` parks a thread in a native ioctl that
  `Thread.interrupt` cannot abort, so `close()` would hang on disconnect.
  `UsbRequest` is cancellable via `port.close()`. **The library did not supply
  the fix; it supplied a read path the fix could be applied to.**
- The new delivery histogram showed the firmware emits in ~512-byte units
  split `1 + 511`, every delivery ≤ 537 bytes — so raising the buffer further
  gains nothing, and the earlier "no short packet terminates the read" theory
  (which had steered a whole session) was struck through as wrong.

### 2. The post-mortem (§4.1)

Why did the bug survive a complete transport rewrite *and* a library swap?
Because `UsbDeviceConnection.bulkTransfer` **is** the discard-on-timeout API,
and it was the only read primitive any version ever used. The original version
was worst: `awaitSentence` handed a *shrinking* deadline down as the URB
timeout — 500 ms, 200, 50, 5, floored at **1 ms** — a positive feedback loop
(lose bytes → block takes longer → shorter timeouts → more discards) that
explains the bimodal block times and the ~1-in-8 clustering. The reader thread
fixed a real bug (unposted endpoint) but enlarged the loss quantum to 16 KB.
The library swap shrank it to 64 bytes. Every attempt moved the *quantum* of
the discard; none removed the discard. And the old instrumentation had
recorded the answer all along: `reads=46250 timeouts=90 (0.2%)` — **those 90
timeouts were 90 discard events**, logged and read as noise.

The lesson, verbatim: **"When one symptom survives several well-reasoned
fixes, stop tuning parameters and go read the primitive."**

### 3. Erase, built carefully

`Pmtk.WRITE_ERASE_FLASH = "PMTK182,6,1"` reversed the "deliberately absent"
decision. The pieces: a pure `EraseGate` implementing all seven §0.2 clauses;
`FlashEraser` running disable → erase → verify → restore; a 90 s ack budget
with **no retries** (a timeout probably means the erase is still running, and
a re-send could interrupt it); verification that reads back a full block where
a *short read counts as failure* (missing bytes read as 0xFF — the exact value
being verified); typed confirmation of the exact fix count (129186), not the
word "ERASE"; the confirmation deliberately not `rememberSaveable`. Erase
evidence exists only when a download completed *and* that exact file parsed
cleanly, and is invalidated by any new download or disconnect. The user caught
hardcoded capacity text ("three weeks at five-second intervals" — wrong three
ways); it was replaced by `continuousLoggingDays()` derived from the live
flash ID, interval, and record size. A self-introduced bug — the erase card's
`LaunchedEffect` would have injected a `PMTK182,2,8` query mid-block-read —
was caught in transcript review and fixed with a busy guard. Tests 69 → 88.

### 4. The silent capture failure (§12)

The user noticed the GPS light not blinking: "did we disable logging along the
way?" The investigation found the latent bug first: every config write is a
three-step sequence, because the device refuses config changes while
recording —

```kotlin
c.writeLoggingEnabled(false)          // 1. stop
c.writeConfig(TIME_INTERVAL, "50")    // 2. change the setting
c.writeLoggingEnabled(true)           // 3. start
```

— and there was **no `finally`**. If step 2 threw, step 3 never ran. The
disable was deliberate and succeeded; nothing ever undid it; and because
on/off is saved configuration, it survived power cycles indefinitely. The user
was told only that their *setting change* had failed.

Then the harder part — proving what actually happened:

- The assistant claimed "the app didn't do it in this session," resting on an
  in-memory transcript that its own two APK installs had destroyed. The user
  demanded rigor ("it's unexpected state change that could lead to real world,
  irevocable, silent failure of data capture... i want to be -extremely-
  careful in the analysis of why this happened"), and the claim was withdrawn:
  "That was wrong of me and I withdraw it."
- **The flash is its own black box.** The device writes a type-`0x07` marker
  on every logging start and stop, inline between fixes.
  `MtkLogParser.StatusChange` was built to recover them; the laptop read
  sector `0x00530000` and spliced it into the baseline. Finding: one new
  marker — `DISABLE after 2026-09-06T16:16:10Z` at `0x0053CBCA`,
  **unanswered**. Population analysis across 417 status changes in 18 months:
  the double-DISABLE fingerprint (a stop nothing answered) appears **0 times
  in 17 months, 3 times starting 2026-09-05** — the day the app began writing
  config. The logger had been off for ~1h40m of real time, through a power
  cycle and a re-pair, looking completely normal.
- A wrong invariant was caught by the golden file itself: "a healthy history
  never ends disabled" fails on `cdt_v2.bin`, because powering off writes a
  trailing DISABLE answered at next power-on. The invariant was narrowed to
  "no *unanswered* stop."

Mitigations, all landed in `4096466`/`b63301e`/`bcc4502`:
`PmtkClient.withLoggingPaused` restores logging in a `finally` — one place, in
the tested module, with an `onRestoreFailed` *callback* (not a return value,
because it must reach the caller when the body also threw); a decoded
`LogStatus` (bit `0x0002` = logging, confirmed against 200×`0x0100`/`0x0102`
marker pairs and GPSBabel's `log_status & 2`) with **NOT RECORDING** in error
colour and a Resume control; an `ACTION_USB_DEVICE_DETACHED` watcher;
`RecordingAudit` reporting unanswered stops from any dump, forever. And the
alarm-hygiene rule, at the user's insistence: **silence is the good news** —
`explanation()` returns null on healthy dumps, encoded in core so no future UI
can reintroduce reassurance.

v1 closed: fast-forward to main, tag `v1`, the handoff renamed to
`ENGINEERING-RECORD.md` (§12 written), the branch deleted.

### 5. The bug "resurfaces" — and the device exonerates the app

A parallel session (the one that renamed the app to **Traxi** /
`thru.taxi.traxi`, added telemetry, and serialised device operations behind
`launchExclusive`) reported the disable bug twice more. Reopening the root
cause found a genuinely gnarly hole in the v1 fix: **`finally` runs on
cancellation, but suspending calls inside it throw `CancellationException`
before sending a byte.** `withLoggingPaused`'s restore called
`withContext(Dispatchers.IO)` — on a cancelled coroutine, that throws
immediately; the disable had gone out, the enable never would. The v1 fix had
closed the exception path and left the cancellation path open. The simulator
masked it because its `write` never suspends. Probe output:
`PROBE sent: [PMTK182,5]` — disable out, enable never sent. ("whoooooaaa!!!
that is a -gnarly- failure mode.") Fixed with `withContext(NonCancellable)`
around the restore, plus a regression test using a transport wrapper that
actually suspends — verified to fail on the old code (`cb193c7`).

But the controlled experiment cut deeper. `$PMTK182,4` (start logging) sent
from **raw Python over `/dev/ttyACM0`**, no app attached: the device acks
`PMTK001,182,4,3` (success), writes ENABLE and DISABLE markers itself, and the
write pointer stays frozen. **The device accepts the command, acknowledges it
as successful, and turns itself back off.** The v1 attribution was retracted —
the double-disable signature is also produced by the device's own power-on
config restatement. "I picked the explanation that matched the bug I'd just
found... exactly the reasoning error I warned about in §4.1, and I made it
anyway."

### 6. Recovery, act one

Research found the leaked **MTK GPS Logger Library User Manual 1.2** (Andy
Lee, 2006, "MTK CONFIDENTIAL," hosted on rigacci.org), which documents the
`NEED_FORMAT_BIT` and the undocumented engineering commands. Then the misstep:
`$PMTK182,10` (ENABLE_LOG) was sent on the reasoning that its worst case was
"rejected or a no-op." Both guesses were wrong: **no acknowledgement at all,
status `0x0100` → `0x0504`, write pointer moved to `0x00000002`.** Decoded via
the BT747 project's bitmask table (bit `0x0400` = **need_format**, `0x0100` =
enabled, `0x0004` = stop-when-full), the misstep had *revealed* the true
state: the flash log area was genuinely unformatted. "I mispredicted the
command, and it happened to produce the diagnosis anyway. That's luck, not
judgement." Sector 0 was verified byte-identical — nothing lost — and
commands 9–12 (`INIT_LOG`, `ENABLE_LOG`, `DISABLE_LOG`,
`WRITE_LOG <addr> <data>`) were documented as engineering tooling, never to be
used.

`$PMTK182,6,1` (FORMAT LOG ALL) ran — SPI busy ~22 s, then RDY, `need_format`
cleared, 18 months of already-multiply-copied track erased. **Logging still
refused to hold.** A format also silently resets configuration (RCD METHOD →
STP, BY_SEC → 1.0 s, FMT_REG → `0x0002002F` — not the manual's stated value);
OVP was restored with `$PMTK182,1,6,1`. Still refusing. Tension peaked
("what!@!!!!!!!! did you just tell me that my logger, that used to log, is
great for everything except logging now!!!!!!") — until the untested step:
**a power cycle after the format.** The firmware keeps its pre-format state in
RAM; it has to boot onto the clean log area. "oh. my. fucking. god... that
worked!!!!!"

Two more truths landed here: **the LED means GPS fix, not recording** —
verified over hours of steady blink, 3D DGPS fix, and zero bytes written
("that LED has misled both of us all day") — and the user's priority
correction that became persistent memory: the device outranks the data.

### Between sessions: the solo recovery

After session II ended, the device stopped holding its enable again.
Off-transcript, the user fought it for hours — the fingerprint is an enable
that **takes and then reverts** (`START_LOG` acked, the auto_log bit `0x0002`
clearing itself within the second, "ENABLE->DIABLE every time for hours"), and
an ordinary power cycle did not clear it. The recovery was an **SPI-level
flash format** (from the EON datasheet, an `spi_`-prefixed command — the exact
string was never captured, flagged in §13 as "the single most valuable missing
line"), then "a full despair and mourning period," then a just-in-case retry
**after a power cycle** — which held. The flash journal corroborates it
verbatim: run after run of paired `LOGSTAT 258/256` markers at offsets
`0x0300`–`0x04B0`, the device's own record of each round of the fight, ending
in the one enable that stuck. Between the format and the next boot, a
recovered device is indistinguishable from a dead one: **"Do not declare death
between steps 1 and 2."**

Commits: `eadbb81` fix → `da1645d`/`f0d2231` record → `94492c1`/`e227696`/
`45c676e` erase → `4096466` withLoggingPaused → `b63301e` RecordingAudit →
`bcc4502` silence → `91f2d35` v1 → `cb193c7` NonCancellable + §13.

---

## Part III — session4.txt: verification, the Bluetooth regression, ground truth

**Goal.** "this device used ot log but has not since so -very- terrible things
caused it to need a reformat, over spi... i need you help me get it back."

### 1. Recovery verified, read-only first

Block 0 was dumped from the laptop and decoded with the app's own parser
(`MtkLogParser` + `Quality.filter` in a throwaway test, deleted after):
**fix records resumed at `2026-09-06T22:47:44Z`** (raw timestamps read
2007-01-21; the +1024-week rollover lands them correctly), 65 fixes, 0
checksum failures, positions coherent at ~41.884N 87.804W. Status 258
(`0x102`), SPI RDY, failed-sector register empty, write pointer advancing in
real time. The post-format flash opens with **49 marker records** — the
journal of the recovery fight — including **2 unanswered stops**, so
`RecordingAudit` will report `unansweredStops = 2` on every dump until the
next erase: "the recovery session's scar, not new loss." Config was then
restored through `withLoggingPaused` (FMT_REG back to `0x000A1C3F`, interval
5.0 s) and verified by pointer arithmetic: **+144 bytes in 15 s = exactly
3 × 48-byte records at 5 s.**

The user's earlier "failure to download anything" was also resolved: the
17:35 dump legitimately parsed to 0 fixes because the flash then held only the
49 recovery markers. Download and parse had both worked; there was nothing to
find.

### 2. §13 corrected by the person who lived it

The assistant summarized the recovery as "just needed a reboot." The user:
"ohhhhh..... oh buddy... you have to understand that i have a ee background
and the -first- thing i tried was power cycling. of course. checked for magic
smoke. you're off by an order." A 27-line inline correction went into §13 —
the take-then-revert fingerprint, the SPI-level format, the mourning period —
and the leaked MTK manual PDF was committed to `docs/` with provenance, page
pointers (command table p.7/22), and the extraction trap: `pdftotext -layout`
is what makes it readable; a Python stream extraction produced garbage that
was briefly mistaken for the command table not existing (`4b5bb00`).

### 3. The Bluetooth regression, root-caused in git

"download over bluetooth fails now." The link was healthy — every config
query succeeded — but the 64 KB block read came back short: 18,432 bytes, then
2,048, then nothing. The arithmetic convicts the timeout: a 2048-byte payload
chunk is a 4,121-byte sentence on the wire; at 493 B/s that is **~8.4 s per
chunk**, against `FlashDownloader.BLOCK_IDLE_TIMEOUT = 2_500` — the USB-tuned
constant from `023e9b3`. Bluetooth had been verified (`161d60b`, `0e0298d`)
against the earlier 10 s default; the later commit was "correct for USB and
quietly fatal for Bluetooth."

The fix (`d346a7e`): the idle timeout became **a property of the transport** —
`Transport.blockReadIdleTimeoutMillis`, default 10 s (slow-link-safe by
design: "fast transports opt down"), `UsbSerialTransport` overriding to 2.5 s,
the constant deleted. Guarded by a regression test (`GappyTransport` inserting
a 4 s gap every 5th read, with a `SteppedClock`) that was falsified by
reintroducing the bug. The commit was **held until hardware proved it**: a
full 3-block BT download — `block 0x00000000 65536/65536 bytes, 32 chunks,
161979 ms` (~2.7 min/block), clean stop on two unwritten blocks, 306 fixes, 0
checksum failures. Mid-flow, the assistant noticed the phone was in an active
call and backed off entirely ("No taps landed; I backed off as soon as I saw
it").

### 4. Flow, not just totals

"could i get better granularity... i'd like to know that things are flowing
rather than received." Built bottom-up (`ba09b54`): an `onChunk` callback in
`PmtkClient.readLogBlock`; `Progress.blockBytes/blockSizeBytes` in the
downloader; a per-sector progress bar with KB and a rate figure — no
percent-of-flash, because the device's totals are untrustworthy, but the 64 KB
block size is "a denominator we can stand behind" — and a Live-tab heartbeat
(pulse, "last update Xs ago", rate). Two bugs caught on hardware during the
build:

- **584 sentences/second.** The first rate used an EMA of inter-arrival gaps;
  RFCOMM delivers a second's worth of sentences in one burst, so
  sub-millisecond intra-burst gaps inflated the rate ~80×. Real rate ~7/s.
  Fixed with a sliding wall-clock window.
- **"-0.0 s ago"** flicker when a sentence lands just after the UI samples its
  clock. Clamped.

Then two cleanups (`c449fad`, `6b6a524`): the Live tab counts **fixes, not
sentences** — only a GGA with a position and fix quality > 0 counts, so a
receiver that is alive but fixless correctly reads stale — and **cancel became
immediate**. Cancel was first polled per-chunk (which broke three tests whose
block-count limiters were counting polls; fixed to count block boundaries),
but hardware still showed ~8 s residual because the BT transport blocks its
whole idle timeout during gaps. `shouldContinue` was pushed into
`awaitSentence` with `READ_SLICE_MILLIS = 400` read slicing; the partial block
is discarded (resume re-reads it cleanly). Final verification was blocked by a
**wedged SPP slot** from repeated reinstall/reconnect cycles — the §9 hazard —
and the session *stopped* rather than keep hammering the irreplaceable device,
committing with an honest gap declared. After the user's power cycle, cancel
measured effectively instant.

### 5. Ground truth over the status bit

A reproducible bug: after any disconnect/reconnect over Bluetooth, the app
showed NOT RECORDING; using the Config pane "restored" it. Traced read-only
first: neither connect nor disconnect writes to the device, so an explicit
disconnect provably cannot stop the recorder — the indicator was a
**connect-time snapshot** (`DeviceInfo.logStatus` set once in `openWith`,
refreshed only by `refreshConfig()` — which is why config actions "fixed" it).
The device's status bit simply misreports after reconnect.

The user named the real requirement: "what i need most is a way to know if
tracks are being logged in the display of the app somewhere. my only way is to
dump a log and see id the counters increased." The fix inverted the
epistemology (`95be843`): the telemetry loop polls the **write pointer**
(`PMTK182,2,8`) every 12 s; the Device tab shows "checking…" → **RECORDING**
with a live "N B written to flash since connecting" counter → NOT RECORDING
only after 30 s of a frozen pointer. Hardware-verified (96 → 192 → 240 B
climbing). The flaky status bit was demoted to a secondary "Reported" row
rather than deleted. Caveat recorded: distance/speed-triggered logging modes
would legitimately show NOT RECORDING when stationary.

Commits: `3ac7232` sizes + a `.format` binding fix → `4b5bb00` §13 + manual →
`d346a7e` transport timeout → `ba09b54` flow → `6b6a524`/`c449fad` cancel +
fixes → `95be843` write-pointer indicator.

---

## Part IV — session3.txt: the wrap probe, the backfire, the spiral, the last recovery

**Goal.** Recall the v2 roadmap — "i'm comfortable calling this mtk device
project stable" — and then: "whoops... i just found a bug we need to address
first."

### 1. The v2 roadmap, recalled and deferred

From §13 of the record: the trail loop as one button (dump → verify → export →
erase → confirm), the burn-rate forecast ("dump by <date>", built on the
trustworthy write pointer, not the ~2× inflated record count), the recording
watchdog, and a download that checkpoints blocks so a 3-hour BT read survives
a link drop. Two constraints recorded: the watchdog goes first (it attacks the
§12 failure class directly), and the trail loop must not ship before erase has
run against real hardware. None were built — the session had other plans.

### 2. Fetch-new's frozen bar (`e8790d0`)

The wrap probe in `planIncremental` (FlashDownloader.kt:147) still called
`readLogBlock(0, blockSize)` bare — no `onChunk`, no `shouldContinue`, no
link-sized idle timeout. Over Bluetooth the probe *is* the first ~2 minutes of
every fetch-new, so the flow opened with a frozen bar and a cancel that
silently waited the whole block out. Fixed with full parity; during the probe
`bytesDownloaded` holds constant and only the per-sector bar moves, because
nothing new is being fetched ("same honesty rule"). Cancel messages in
`SessionController` were rewritten too — "Cancelled — nothing had been
fetched; <source> is untouched" instead of "the logger did not respond" —
because "blaming the logger for a stop the user asked for is a lie."

Also noted here, unresolved: `git remote -v` returns nothing. **One copy of
the code, on the laptop that stays home.**

### 3. Growth misread as wrap — and the bug that didn't fire (`f0e105d`)

"umm... i hit a retry... and the download failed." The transcript showed a
chain: fetch-new ran from a dump whose last real byte was `0x73BF` (sector 0
count still `0xFFFF`, mid-write); the probe found a difference at `0x73C0` and
declared "the log has wrapped or been erased," forcing a full download —
during which the BT link genuinely decayed and died (26,624 → 2,048 → 0
bytes), after which the write-pointer poller hammered the dead-but-open socket
every 3 s: **99 failed retries and counting**, with no ACL broadcast because
Android still considered it connected.

The deeper find was the bug that *didn't* fire. Had the probe matched, the old
extend rule — re-read only the **last block** — would have copied the stale
mid-write block 0 forward, silently dropped every record since 21:39, and
reported "Added 64 KB of new tracking," eligible as erase evidence. "Tonight
the wrong wrap verdict accidentally protected you by forcing the full path."

Three fixes:

- **A three-case byte comparison.** A byte that was 0xFF in the previous dump
  proves nothing (growth); only real bytes convict — changed to other data =
  wrapped; changed to 0xFF = erased.
- **Extend from the write frontier.** Scan the previous dump for its last real
  byte and re-read from the block containing it (degrades to the old rule for
  prefix-shaped dumps).
- **A dead-link detector** — two consecutive failed write-pointer probes with
  zero bytes between them (~30 s of silence from a 1 Hz NMEA streamer) tears
  the link down. *(This is the one that backfired.)*

Tests included a byte-exact reconstruction of that night's dump shape
(block 0 real to `0x73C0` + two 0xFF blocks). 145 core tests green.

### 4. The backfire (`10ff393`)

"got something more important.. that last update kills bluetooth
connection... but sometimes... looks like a race condition..." The phone
transcript separated two drops: one was a genuine ACL radio disconnect; the
other was **the new detector**, killing a link that had merely gone quiet
after connect-time flash-ID/write-pointer queries — queries this device is
documented to answer unreliably — timed out. Before the detector, that stall
was invisible and self-healing; after it, a recoverable stall became a hard
disconnect and reconnect is fragile. "I made a flaky-but-recoverable link into
a flapping one."

The revert kept one condition that fixes both the zombie poller and the
teardown: **probe only a link that is talking** (`bytesSincePump > 0`), wait a
full interval before the first probe, never tear down on silence, and trust
the `ACTION_ACL_DISCONNECTED` broadcast as the real signal.
`DEAD_LINK_SILENT_PROBES` deleted. The lesson entered persistent memory as
**surface, don't auto-remediate**. Two smaller items rode along: 51 lines of
standing explainer text removed from Device/Dumps/Config ("alarms, not
disclaimers" — the conditional RECORDING WAS LOST and checksum warnings
stayed), and the download notification made to leave when the download does
(cancel the collector scope *before* `stopForeground`, so a post-mortem
`notify()` can't re-post an orphan) (`54db183`, `aad2fc9`).

### 5. The spiral, and the stop

The device showed NOT RECORDING again — pointer frozen at `0x0000AEA0` with a
valid moving fix, status flipping 258 ↔ 256. In roughly an hour the assistant
produced three confident, mutually contradictory diagnoses:

1. **Post-erase §13 state, prescribe a power cycle** — built on an erase that
   appears in no captured transcript ("an erase I never actually saw").
2. **Vibration-sensor sleep**, declared "decisive" from a movement
   correlation (pointer frozen at 0.02–0.31 knots, advancing at 0.59–1.34) —
   despite the manual saying the feature is off by default and the user never
   having enabled it: "you've halucinated a sleep timer that isn't enabled."
3. **Back to §13** — escalating toward grepping shell history for the
   never-captured SPI format command to fire at the irreplaceable device.

The user interrupted the tool call: **"stop. do you see what you're doing,
because it's clear to me. you've come to an unstable place. tell me how to
help. read the transcripts. i'm on your side. but that looks like spiraling,
and we need to not do that now."** The assistant halted and named the pattern
— "I was accelerating toward an irreversible action on the one thing that must
not break, to resolve my own uncertainty" — separated known from unknown, and
committed to sending nothing mutating.

### 6. The recovery, done right

After a model switch ("fable... i've switched models, and i need to recover a
device that is in a familiar failure mode. i think we need to reformat flash,
but i'm not sure. are you with me?"), the next move was three **read-only**
queries over `/dev/ttyACM0`:

| Query | Answer | Meaning |
|---|---|---|
| `$PMTK182,2,7` | 258 (`0x102`) | auto_log ON; **need_format NOT set** |
| `$PMTK182,2,1` | 1 | SPI status: RDY |
| `$PMTK182,2,11` | all `FFFF` | failed-sector register: clean |

Verdict: **the flash is healthy — do not reformat.** "There is nothing wrong
with the flash to reformat — doing so would destroy a clean chip and wouldn't
touch the actual problem." The enable-revert needed a firmware reboot, not
destruction: a plain power cycle (§13's load-bearing step) re-armed it.
"whew... okay... that might have worked... i see device status as
incrementing." Recovery verified two ways: the write pointer advancing
43 KB → 51 KB under the green RECORDING indicator, and the installed
`base.apk` pulled from the phone to confirm the reverted teardown string
appears zero times, git HEAD `aad2fc9`, tree clean.

Along the way the session also pinned the vibration-sensor facts (Qstarz
manual §5-5: 10-minute stationary threshold, "Under sleep mode the log will be
disabled," **off by default**, toggleable only from Windows QTravel — left for
a passive desk test).

Commits: `e8790d0` probe parity → `f0e105d` frontier + three-case probe →
`54db183` trim → `10ff393` revert teardown → `aad2fc9` notification.

---

## The design decisions that held

- **Never discard a partial download.** Established in Part I after a rewrite
  revealed failures were throwing bytes away; enforced everywhere since —
  partials saved unconditionally, damage enumerated as byte ranges
  (`Result.damagedRanges`), never silent, never called a verified copy.
- **The idle timeout belongs to the transport.** Slow-safe default (10 s),
  fast transports opt down (USB: 2.5 s). The regression it fixed came from
  tuning a shared constant for one link.
- **Ground truth over device claims.** The record count is ~2× inflated, the
  status bit misreports after reconnect, the LED means fix — but the write
  pointer is bytes actually in flash. Recording status, capacity math, and
  erase evidence all build on measured quantities.
- **Silence is the good news.** Alarms only for real loss; `explanation()`
  returns null on healthy dumps; standing explainer text trimmed. Encoded in
  core so the UI can't drift back. "Explaining on every ordinary dump why a
  benign condition is benign is the noise that teaches people to skim."
- **Surface, don't auto-remediate.** The dead-link detector proved that
  auto-fixing ambiguous state destroys recoverable state. Probe only a talking
  link; trust the ACL broadcast; let the human decide.
- **All mutations through `withLoggingPaused`** — restore in a
  `withContext(NonCancellable)` `finally`, in the tested module, with
  `onRestoreFailed` as a callback. "If you find yourself writing
  `writeLoggingEnabled(false)` at a call site, that is the bug being
  reintroduced."
- **Read the primitive.** The two hardest bugs — the `bulkTransfer` discard
  and the cancellation hole — were closed by reading what the code sits on
  (decompiled bytecode; coroutine cancellation semantics), not by tuning
  parameters above it. Four parameter changes moved numbers on the near side
  of `bulkTransfer`; the answer was thirty lines of bytecode on the far side.
- **The simulator must be as strict as the device.** Every download bug lived
  in the gap where it wasn't: the 512-byte probe (link-dropping on hardware),
  the 10 s timeout, the non-suspending `write`, the hardcoded status 256.
- **Corrections are marked inline, never edited away.** §13 corrected twice by
  the person who lived it; the misread `timeouts=90` instrumentation and the
  ENABLE_LOG misjudgement recorded under their own names. "Several of them are
  the most useful paragraphs in the document."

## The scoreboard

- 48 commits in ~36 hours; tests 20 → 145, none requiring hardware.
- USB: 283 s with silent corruption → **~110 s, byte-perfect, zero retries**
  (laptop reference: 83.5 s).
- Bluetooth: broken → verified end to end; incremental fetch (4 blocks instead
  of 82, ~10 min instead of ~3 h); link-sized timeouts; effectively instant
  cancel; live sub-sector flow display.
- Erase: built behind the seven-clause `EraseGate` — deliberately still
  awaiting its first real-hardware run before the trail loop ships.
- One silent capture failure (~1h40m of unrecorded trail) found, proved from
  the flash's own `0x07` markers, and made structurally unrepeatable.
- One irreplaceable device brought back from the brink twice — once by
  format-then-power-cycle, once by *refusing* to format on read-only evidence.
- Still open: erase against real hardware; a full 3-hour BT download; a
  stricter simulator; the `1+511` throughput curiosity; and a git remote —
  the repo is still a single copy.
