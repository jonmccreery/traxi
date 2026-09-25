# Traxi: engineering record through v1

Started as a handoff for one unsolved USB corruption problem and grew into the
project's record, because the two hardest bugs here were both found the same
way — by reading evidence rather than reasoning about the code — and the
evidence is worth keeping.

Everything here is measured, not inferred, unless explicitly marked as a
hypothesis. Corrections made after the fact are marked inline rather than
edited away; several of them are the most useful paragraphs in the document.

**v1 closed 2026-09-06.** USB transport fixed, erase built and gated, and a
silent capture failure found and closed — see §12, which is the one section to
read if you read nothing else.

**Renamed 2026-09-06, immediately after v1 closed.** The project was `btdroid`
in `ai.moonlite.btdroid` for its whole development; it is now **Traxi** in
`thru.taxi.traxi`. Everything before this point in the git history says
`btdroid`, and commit hashes quoted in this document are from that era — they
are still valid, the name around them changed. The namespace also moved off
`ai.moonlite`, which was never right: this is a personal project, not company
work. See §14 for what the rename touched and the one thing it deliberately
destroyed.

> ## RESOLVED 2026-09-06 11:00 — commit `eadbb81`
>
> **Cause:** `CommonUsbSerialPort.read` branches on its timeout argument.
>
> ```java
> if (timeout != 0) nread = mConnection.bulkTransfer(ep, dest, len, timeout);
> else { mUsbRequest.queue(ByteBuffer.wrap(dest, 0, len), len);
>        mConnection.requestWait(); nread = buf.position(); }
> ```
>
> `bulkTransfer` returns `-1` on timeout and **discards the bytes already
> received into that URB** — the caller cannot even learn how many there were.
> We passed `readTimeout = 200`, so every read took that path.
>
> **Fix:** `readTimeout = 0`, which selects the `UsbRequest` path. It has no
> timeout to expire and so has nothing to discard. Also raised `readBufferSize`
> off the library's one-max-packet default.
>
> **Result, full 86-block read:** 0 retries, 0 malformed chunk lines, 0
> unrecovered gaps, slowest block 1317 ms (was 10,782 ms), 95 s of block time.
> Byte-identical to `data/usb_final.bin` across the entire 5,479,850-byte common
> prefix. Capture and transcript at `data/usb_clean_2026-09-06.{bin,log}`.
>
> **If the logger has stopped recording, go straight to §13** — it has the
> recovery sequence, and the power cycle in step 2 is the part that is easy to
> miss. **If you read one thing here, read §12** — the silent capture failure, which
> is the only bug in this project that can cost data that never existed. After
> that, §4.1 — why the same defect survived a
> full hand-rolled implementation, a reader-thread rewrite and a library swap,
> and what that should have told us three attempts earlier.
>
> Sections 1–5 and 10 below are kept as the record of how it was found. **§6
> (device facts), §7 (environment), §8 (code map) and §9 (traps) are still
> current and still worth reading.** Corrections from the resolving session are
> marked inline.

---

## 1. The problem, stated precisely

Reading the logger's flash over USB from the phone, roughly **1 block in 8
comes back short**, missing whole 2048-byte chunks. The downloader retries and
almost always succeeds, so the final image is usually correct — but:

- retries cost time (152 s instead of ~95 s for a full read);
- if all three attempts on a block fail, the image has a **hole filled with
  `0xFF`, which is byte-identical to erased flash**. The app detects and
  reports this (`Result.damagedRanges`) and refuses to call such a dump
  complete, but the underlying loss is real.

**The same device over the same cable from the laptop shows zero corruption**
(83.5 s, 0 retries, byte-perfect). So this is host-side, on Android.

### Why it matters beyond tidiness

Prep doc §0.2: erase is the bedrock feature — dump, verify, erase, repeat is
the only workflow that covers a thru-hike. A damaged dump must never be the
"verified copy" that licenses erasing the device. Today the damage is detected,
so this is safe; but a transport that loses data is a bad foundation for a
feature whose failure mode is losing a week of trail.

---

## 2. The single best clue

**Corrupted lines are not randomly sized.** From one instrumented run
(02:13:27, 86 blocks):

```
malformed line lengths, excluding the 42-char log prefix:
   9 x exactly 2840 chars
   2 x exactly 6961 chars
   3 x short NMEA fragments (22, 28, 94)
```

A healthy chunk sentence is `$PMTK182,8,AAAAAAAA,` (20) + 4096 hex + `*XX` (3)
= **4119 chars**, plus CRLF = 4121 bytes on the wire.

Nine lines at *precisely* 2840 is not packet loss — random corruption does not
land on the same byte count nine times. **That is a fixed buffer boundary
somewhere in the stack.**

And `6961 = 4119 + 2842`: an over-long line is one good sentence with a
~2840 fragment welded onto it. Same quantum, twice. Note the over-long line
contains **no second `$`**, so it is not two intact sentences concatenated —
the second sentence's header was lost too.

Also observed: a truncated line ends `...C606*5A`, i.e. structurally valid with
a checksum terminator. So framing survives and bytes vanish from the **middle
of the hex payload**.

Arithmetic worth chasing: `4121 - 2840 = 1281`, and `1280 = 20 x 64` (the
endpoint max packet size). One byte off from a clean 20-packet loss.

> **Resolved.** The clue was sound and the reasoning from it was right: a fixed
> quantum meant a buffer boundary, not packet loss. It was a whole number of
> packets discarded by a timing-out `bulkTransfer`. The stray one byte is the
> 42-char log-prefix assumption being off by one, not a real remainder — do not
> read significance into it.

---

## 3. Ranked hypotheses for next session

> **Outcome:** H1 was wrong and cost nothing to disprove — `step()` already
> copies. H2 was the right instinct and the right first move; reading the
> library's `read()` from the AAR found the mechanism *without a device run*.
> H3 was nearly correct: the cure was not to bypass `SerialInputOutputManager`
> but to change the one argument that decides which read path it uses. H4 and
> H5 were never needed.
>
> **The lesson worth carrying is in §4.1.** In short: the answer was thirty
> lines of the dependency's bytecode, after a whole session of parameter changes
> on the near side of the interface.

### H1 — the library reuses its read buffer (start here, one line to test)

> **Disproved, no device needed.** `SerialInputOutputManager.step()` does
> `System.arraycopy` into a fresh `new byte[len]` before calling `onNewData`.

`UsbSerialTransport.onNewData(data: ByteArray)` currently does:

```kotlin
override fun onNewData(data: ByteArray) {
    if (!incoming.offer(data)) note(...)   // <-- stores the reference
}
```

If `SerialInputOutputManager` passes its **internal** buffer rather than a
fresh copy, then queued entries get overwritten by subsequent reads. That
produces exactly the observed signature: corruption of a **fixed size**, in the
middle of payloads, with framing intact.

Test: `incoming.offer(data.copyOf())`. One line. Run and compare malformed
counts.

Caveat that keeps this from being certain: the earlier hand-rolled reader
thread *did* copy (`buffer.copyOf(n)`) and still corrupted — but its failure
mode was whole-chunk loss, which is not the same signature. Worth eliminating
first regardless because it is nearly free.

### H2 — find the 2840 constant

Pull the library source for 3.8.1 and grep for buffer sizes:
`SerialInputOutputManager.DEFAULT_READ_BUFFER_SIZE`, `readBufferSize`,
`CdcAcmSerialPort.read` paths. Then instrument `onNewData` to log
`data.size` for a whole run and look at the distribution:

```kotlin
override fun onNewData(data: ByteArray) {
    sizes.merge(data.size, 1, Int::plus)   // dump the histogram periodically
```

If there is a dominant delivery size and the losses are a whole number of them,
that names the mechanism instead of guessing at it.

### H3 — bypass SerialInputOutputManager

Use `port.read(buf, timeout)` in a dedicated thread instead of the IO manager.
`UsbSerialPort.read` uses `UsbRequest` internally for some drivers and has
different buffer handling. This is a contained experiment — the `Transport`
interface does not change.

### H4 — the phone's USB stack under concurrent load

The phone is also running wireless ADB, the foreground service, and Compose
recomposition during the download. The laptop was doing nothing else.
Test: run a download with the screen off and ADB over USB *disconnected*
(start it, then unplug ADB), and compare retry counts. Cheap, and would
separate "Android USB is fine" from "Android USB is fine when idle".

### H5 — device-side overrun (partially tested, inconclusive)

Requesting 8 KB per `PMTK182,7` instead of 64 KB was tried and made things
**worse**, so the simple version of this is ruled out. But request *pacing*
(a short delay between requests, rather than smaller requests) was never tried.

---

## 4. What has already been tried, and what it bought

| Attempt | Result |
|---|---|
| Floor the bulk read timeout at 250 ms | No change. Wrong mechanism: losses were whole chunks, not truncated transfers. **But see §4.1 — `git log -S` finds no such change ever committed, so it is unclear what this was measured against** |
| Hand-rolled reader thread keeping a read posted | Whole-chunk loss went 5 gaps → 0; retries and malformed lines remained |
| Raise transfer timeout to 10 s | **Worse.** A larger buffer waiting longer accumulates more data before the timeout discards it |
| Split requests into 8 KB for flow control | **Worse.** Reverted |
| Swap to `usb-serial-for-android` 3.8.1 | Unrecovered gaps 5 → 1. Retries and the fixed-size corruption persist |
| Shorten block idle timeout 10 s → 2.5 s | 240 s → 152 s, damage recovered rather than kept. Does not touch the corruption |
| **`readTimeout` 200 → 0, `readBufferSize` 64 → 4096** | **Fixed it.** 0 retries, 0 malformed, 0 gaps, 95 s |

The 10 s row above is not just a failed attempt — it is direct evidence for the
real mechanism, and was already correctly interpreted at the time ("a larger
buffer waiting longer accumulates more data before the timeout discards it").
The sentence names the bug. What was missing was that the timeout could be
turned *off* rather than tuned.

**Lesson from the session:** every change made without a measured mechanism
either did nothing or made it worse. The two that helped came from
instrumentation. Measure first.

---

## 4.1 Why the hand-rolled transport had the same disease

Written after the fix, from the reverted code in `a5e9662`, `348972a` and
`20a4b93`. This is the more useful cautionary tale, because the bug survived a
complete rewrite of the transport and a change of library — which is precisely
the signature of a defect in the *primitive* rather than in the code around it.

### It was never escapable

`UsbDeviceConnection.bulkTransfer` **is** the discard-on-timeout API, and it is
the only read primitive the hand-rolled transport ever used. Android's non-lossy
path — `UsbRequest.queue()` + `connection.requestWait()` — appears nowhere in
those three commits. There was no argument you could pass to get correctness.

### The first version had a feedback loop

`a5e9662` called it straight from `read()`:

```kotlin
val n = conn.bulkTransfer(ep, dest, 0, dest.size, timeoutMillis.toInt().coerceAtLeast(1))
```

and `PmtkClient.awaitSentence` hands `read()` a **shrinking** budget:

```kotlin
val remaining = deadline - clock()
val n = transport.read(readBuffer, remaining)
```

So as a block neared its deadline the URB timeout decayed — 500 ms, 200, 50, 5,
floored at **1 ms** — and every expiry binned whatever that URB had collected.

That is a positive feedback loop. Lose bytes → the block takes longer → the
deadline is nearer → timeouts get shorter → more URBs are discarded per second →
lose more bytes. It accounts for two things measured at the time but never
connected: block times were bimodal (~1 s or blown out to 10 s, with nothing in
between), and failures clustered at roughly 1 in 8 rather than spreading evenly.
A block that began losing data was likely to keep losing it.

### The reader thread fixed a real bug, just not this one

`20a4b93` was correctly diagnosed and genuinely worked: decoupling reads from
parsing closed the unposted-endpoint gap, and whole-chunk loss went 5 → 0. It
kept the lossy primitive, though, and enlarged the wound:

```kotlin
private const val READ_TIMEOUT = 500
private const val READ_BUFFER_BYTES = 16 * 1024
val n = conn.bulkTransfer(ep, buffer, 0, buffer.size, READ_TIMEOUT)
```

A fixed 500 ms stopped the decay, but a 16 KB buffer meant each expiry could
discard up to 16 KB — the largest loss quantum of any version. The
instrumentation counted them and nobody read them as losses:

```
reads=46250  timeouts=90 (0.2%)  slow>250ms=266  avg=267 bytes/read
```

**Those 90 timeouts were 90 discard events.** They were in the transcript the
whole time, labelled as timeouts.

### The library swap moved the quantum, not the bug

`9fac12b` set `readTimeout = 200`, so `SerialInputOutputManager` called the same
`bulkTransfer`. What improved was incidental: SIOM sizes its buffer at one max
packet, so the loss quantum collapsed from 16 KB to 64 bytes and unrecovered
gaps went 5 → 1. Same weapon, smaller wounds.

That is the through-line for the entire table in §4. Timeout floor, buffer size,
request splitting, longer timeout — every one of them moved the *quantum* of the
discard. None of them removed the discard.

### What the library was actually worth

Not the drivers, and not the fix. `READ_TIMEOUT = 0` would have worked in the
hand-rolled version too, since `bulkTransfer` documents 0 as infinite. But an
infinite `bulkTransfer` parks a thread in a native ioctl that `Thread.interrupt`
cannot abort, and `close()` did exactly that:

```kotlin
running = false
reader?.interrupt()
```

On disconnect, that hangs. `UsbRequest` is cancellable — `port.close()` calls
`mUsbRequest.cancel()`, which is what makes `readTimeout = 0` safe to ship.
**The library did not supply the fix; it supplied a read path the fix could be
applied to.**

### The lesson to carry

Measure first is right but insufficient — this session measured constantly. The
sharper rule:

> **When one symptom survives several well-reasoned fixes, stop tuning
> parameters and go read the primitive.**

Four attempts moved numbers around on the near side of `bulkTransfer`. The
answer was thirty lines of bytecode on the far side, reachable in minutes:

```bash
unzip -o ~/.gradle/caches/modules-2/files-2.1/com.github.mik3y/\
usb-serial-for-android/3.8.1/*/usb-serial-for-android-3.8.1.aar classes.jar
unzip -o classes.jar && javap -p -c com/hoho/android/usbserial/driver/CommonUsbSerialPort.class
```

No device, no run, no guessing. When a dependency's behaviour is the suspect,
decompile the dependency.

---

## 5. All measurements to date

### Laptop, Python, direct to `/dev/ttyACM0` — the reference

```
5,505,024 bytes in 83.5 s   64.4 KB/s   0 retries   0 corruption
```

Script: `$CLAUDE_JOB_DIR/tmp/fulldump.py` (see §7 to recreate).

### Phone runs

| Run | Transport | Wall | Retries | Malformed | Unrecovered gaps | Damaged |
|---|---|---|---|---|---|---|
| 00:55 | hand-rolled, synchronous | 283 s | ~14 | — | yes | yes |
| 01:23 | + timeout floor | 274 s | — | 26 | 5 (12,288 B) | yes |
| 01:38 | + reader thread | ~188 s | — | 10 | 0 | no |
| 01:56 | + 8 KB requests | aborted | rising | — | — | yes |
| 02:13 | library, 64 KB | 240 s | 11 | 14 | 1 (2,048 B) | yes |
| **02:22** | **library + 2.5 s idle** | **152 s** | **10** | — | **0** | **no** |
| **11:00** | **+ `readTimeout = 0`** | **~110 s** | **0** | **0** | **0** | **no** |

### Per-block timing, run 02:13 (86 blocks)

```
min 772 ms   median 1072 ms   p90 1210 ms   max 10782 ms
total block time 101 s   against 240 s wall clock
```

**The link is fast.** 1072 ms per 64 KB = 61 KB/s, matching the laptop. The
139 s difference was retry wait, and only *one* block appeared slow because the
timing note logs the final attempt only — a real reporting blind spot to keep
in mind.

### Read accounting, run 01:38 (hand-rolled reader thread)

```
reads=46250  timeouts=90 (0.2%)  slow>250ms=266  avg=267 bytes/read
```

`avg 267 bytes/read` against an 8 KB buffer is itself notable — transfers were
completing far short of the buffer.

### Final verification, run 02:22

```
5,636,096 bytes   0 checksum failures   129,115 records   84 sectors
span 2025-03-16T07:01:52Z -> 2026-09-06T07:24:56Z
format change @ 0x00535FDA -> 0x000A1C3F
first byte differing from the previously verified capture: 0x0053602A
  (exactly the write pointer at that capture, so all differences are new data)
```

---

## 6. Device facts you should not have to rediscover

### Identity

```
Qstarz BT-Q1000XT     USB 0e8d:3329     MediaTek, native CDC-ACM, no bridge chip
firmware AXN_1.30-B_1.3_C01   model id 0008
flash: EON 8 MB, JEDEC 1C 70 17 (from $PMTK182,3,9 — it is an RDID, not a size)
```

### USB descriptor layout — **reversed from the normal CDC arrangement**

```
if0  class=0x0A (CDC DATA)   ep 0x81 IN bulk, 0x01 OUT bulk, max packet 64
if1  class=0x02 (CDC COMM)   ep 0x82 IN interrupt
```

Standard CDC puts COMM at 0 and DATA at 1. Consequences:

- CDC control requests (`SET_LINE_CODING` 0x20, `SET_CONTROL_LINE_STATE` 0x22)
  must use **`wIndex = 1`**. Hardcoding 0 sends them to the data interface,
  which refuses them. That cost an hour: the link opened, writes were accepted,
  and nothing ever came back.
- **The device is mute until DTR is asserted.**

### Response shape

- `PMTK182,7,<addr>,<len>` is answered as `PMTK182,8,<addr>,<hex>` in
  **2048-byte payload chunks** — 4119-char sentences, 4121 bytes with CRLF.
- ~~2048 is an exact multiple of the 64-byte packet size, so **a large bulk read
  has no short packet to terminate it**. This is why big read buffers behave
  badly here.~~

  > **Wrong, and it steered a whole session away from the fix.** Big read
  > buffers behaved badly because a timing-out `bulkTransfer` discarded them,
  > not for want of a short packet. Short packets arrive constantly. The
  > measured delivery histogram on a clean run:
  >
  > ```
  > usb reads=42000 avg=268 sizes: 1x19472 511x19465 537x2288 25x245 20x139
  > ```
  >
  > The device emits in ~512-byte units split as `1 + 511` — the two counts are
  > exactly paired from the first sample on — and each ends short. Deliveries
  > never exceed 537 bytes even behind a 4096-byte buffer, so transfers are
  > bounded by the firmware's write granularity. **Raising `readBufferSize`
  > above 4096 would gain nothing.**
- Read length must be even. Odd lengths are silently ignored.
- A 512-byte read request is ignored *and drops the link*. Only full-block
  reads have ever been reliable.

### Speeds

```
USB   61-64 KB/s      full read ~95 s of transfer
BT    493 B/s         full read ~3 hours (see below — this is a residue,
                      not the bridge's speed)
```

~~**493 B/s is what is left after NMEA, not what the bridge can do.** Measured
2026-09-07 from a timestamped transcript: the idle navigation stream alone is
**415 B/s**, 43% of the ~960 B/s the internal 9600-baud UART carries. Add the
log payload and `415 + 493 = 908 B/s`, **95% of the bridge**. A Bluetooth
download therefore runs the UART pinned near capacity for its entire three
hours, and the download's share is simply the remainder. See §15.~~

> **Wrong, and it was my own addition — corrected 2026-09-08 by the first
> completed Bluetooth download.** The arithmetic assumed NMEA keeps streaming
> at its idle rate while log data flows. It does not. Measured across that
> 14-minute transfer: NMEA runs **9 B/s during the download** against
> **289 B/s idle immediately after it**. The device all but suspends the
> navigation stream and gives the bridge to the log reads.
>
> The original line in the table was right and did not need correcting. The
> real accounting is hex encoding, not contention:
>
> ```
> 396 B/s payload  x2 (hex on the wire)  = 792 B/s
> + sentence framing (~20 chars per 4096) ~ 797 B/s
> against the ~960 B/s the 9600-baud bridge carries = ~83%
> ```
>
> So a transfer does run the bridge near capacity, but on its own account,
> and the remaining headroom goes on inter-chunk gaps and the device's flash
> reads — not on NMEA. The lesson worth keeping is the one about method: that
> paragraph reasoned from two numbers measured in *different* conditions and
> added them, which is how a plausible figure ends up in a document that is
> supposed to contain only measured ones.

### Current logger configuration (changed during the session)

```
log format  0x000A1C3F   (UTC,VALID,LAT,LON,HEIGHT,SPEED,HDOP,VDOP,NSAT,RCR,DISTANCE)
interval    5.0 s        (was 20.0 s)
record size 48 bytes     (was 42)
```

Two consequences: the flash now contains **two record formats** with a
type-`0x02` format-change marker at `0x00535FDA` (the parser handles this), and
flash burn is ~5.5x faster — roughly **5 days** of capacity from the current
write pointer rather than 31.

### Bluetooth, for reference

```
Qstarz 1000XT   00:1C:88:22:15:98   SPP 0x1101, RFCOMM channel 1
LegacyPairing: yes     PIN: 0000  (1111 is rejected)
```

The link key goes stale across a device power cycle while Android keeps hers,
so `bondState` reads `BOND_BONDED` and nothing prompts for a PIN. The app
detects a connect failure on a bonded device, clears the bond and re-pairs.

---

## 7. Getting the environment running

### Toolchain (installed this session, not in the repo)

```bash
export ANDROID_HOME=$HOME/android-sdk        # SDK 35, build-tools 35.0.0
GRADLE=~/tools/gradle-8.11.1/bin/gradle
ADB=/opt/android-sdk/platform-tools/adb
```

```bash
cd /home/taxi/traxi
$GRADLE :core:test              # 69 tests, no device needed
$GRADLE :app:assembleDebug
```

### Phone

```
Samsung SM-G990U1, Android 16 / API 36
package: thru.taxi.traxi.debug
```

Wireless ADB is essential — the USB port is needed for the logger:

```bash
# with the phone on USB first:
$ADB tcpip 5555
$ADB connect <phone-ip>:5555      # was 192.168.86.36:5555
# then unplug and use the port for the logger
```

WiFi network `Maple East`, password in NetworkManager
(`nmcli device wifi show-password`). Get the phone's IP with
`$ADB shell ip -f inet addr show wlan0`.

### Laptop-side access to the logger

`/dev/ttyACM0` is `root:uucp 660`. The user is in `uucp` in `/etc/group` but
sessions predate it, so either re-login or:

```bash
sudo chown taxi /dev/ttyACM0     # resets on replug
```

### Driving the app (1080x2340 screen)

`uiautomator` is far cheaper than screenshots for reading state:

```bash
state() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
          $ADB shell cat /sdcard/ui.xml | grep -oE 'text="[^"]*"' | sed 's/text="//;s/"$//'; }
```

Tap targets, verified this session:

| Target | Coordinates |
|---|---|
| Connect over USB | `539 676` |
| Download flash (Dumps tab) | `539 676` |
| Fetch new (first saved dump) | `199 1292` |
| Device / Dumps / Config / Log tabs | `127` / `401` / `578` / `951` at `2118` |
| Share (Log tab) | `163 644` |

**Always screenshot or `state()` before tapping.** Layout shifts when cards
appear — a parse-summary card once pushed the dump list down and a blind tap
landed on the wrong control. Worse, a stale share sheet was once in front,
where blind taps could have sent a file to a contact.

### Getting the transcript off the phone

The Share button writes the file *before* opening the chooser, so:

```bash
$ADB shell input tap 951 2118          # Log tab
$ADB shell input tap 163 644           # Share (writes cache/exports/)
$ADB shell input keyevent KEYCODE_BACK # dismiss chooser, sends nothing
$ADB exec-out run-as thru.taxi.traxi.debug \
    cat cache/exports/traxi-transcript.txt > transcript.txt
```

### Analysing a transcript

```bash
T=transcript.txt
grep -c 'dropped malformed' "$T"                  # corrupted sentences
grep -cE 'attempt [0-9]+ of' "$T"                 # retries
grep 'missing 0x' "$T"                            # unrecovered gaps
grep 'usb reads=' "$T" | tail -3                  # read accounting
grep 'dropped malformed' "$T" | awk '{print length($0)-42}' | sort -n | uniq -c

# per-block timing distribution
grep -oE 'block 0x[0-9A-F]{8} [0-9]+/65536 bytes, [0-9]+ chunks, [0-9]+ ms' "$T" \
 | awk '{gsub("ms","",$7); print $7}' | sort -n | awk '
  {a[NR]=$1;s+=$1} END{printf "n=%d min=%d med=%d p90=%d max=%d total=%.0fs\n",
  NR,a[1],a[int(NR/2)],a[int(NR*0.9)],a[NR],s/1000}'
```

### Live monitoring during a run

Poll every 15 s; retries are the failure signal and appear in the UI:

```bash
$ADB shell dumpsys activity services thru.taxi.traxi.debug | grep -c DownloadService
# and state() | awk '/^Downloading$/{f=1;next} /^Stop/{f=0} f'
```

---

## 8. Code map

| Path | What to know |
|---|---|
| `app/.../usb/UsbSerialTransport.kt` | The suspect. Library-based; `onNewData` → queue → `read()` |
| `app/.../usb/UsbPermission.kt` | One-time per-device permission; implicitly granted via the attach intent |
| `core/.../protocol/PmtkClient.kt` | `readLogBlock` (gap/timing instrumentation lives here), `awaitSentence`, bulk payloads elided from the transcript |
| `core/.../protocol/FlashDownloader.kt` | Block loop, `ATTEMPTS_PER_BLOCK = 3`, `BLOCK_IDLE_TIMEOUT = 2500`, `Result.damagedRanges` |
| `core/.../protocol/Nmea.kt` | `NmeaLineAssembler` — where a corrupted sentence becomes a dropped chunk |
| `core/.../transport/FileReplayTransport.kt` | `SimulatedLoggerTransport` — **more permissive than the device**, see §9 |
| `app/.../session/SessionController.kt` | `connectUsb`, download orchestration, damage reporting |

The `Transport` interface has held unchanged through five transport rewrites.
Everything above it — `PmtkClient`, `FlashDownloader`, incremental fetch — is
covered by 69 tests and has never needed to change for a transport problem.

---

## 9. Traps

**The simulator is more forgiving than the device.** `SimulatedLoggerTransport`
accepts any read length, answers instantly, and never drops the link. *Every*
download bug this project has had lived in that gap: the 10 s block timeout, the
stale-chunk contamination, the 512-byte wrap probe. Consider tightening it to
reject sub-block reads and to pace deliveries, so this class of bug fails on the
desktop instead of at trail speed.

**Per-block timing logs only the final attempt.** A block that took three tries
shows one fast time. This hid 110 s of retry cost across a 240 s run. If you add
timing, log per attempt.

**`0xFF` is ambiguous.** A missing chunk and erased flash are byte-identical.
Never treat a short block as end-of-flash; erased sectors still *answer*, with
`0xFF` data. `Result.damagedRanges` exists so this can never be silent again.

**Never stop a download at the write pointer.** In `OVERLAP` mode a wrapped log
keeps its oldest records *past* the pointer. Termination is two consecutive
unwritten blocks, which is correct in both states.

**Everything the device says about itself is suspect** — with two exceptions
discovered late: the flash-size query returns a decodable JEDEC RDID, and field
8 is a byte address rather than the record count mtkbabel labels it. Its
*record count* (247,133 vs 126,613 actual) is genuinely wrong.

**Don't reinstall the app over a live Bluetooth connection.** Force-stop
orphans the socket, and the logger serves one SPP client, so the next connect
fails confusingly.

---

## 10. Suggested plan

1. **H1**: `data.copyOf()` in `onNewData`. One line, run, compare malformed
   count. Nearly free and matches the signature.
2. **H2**: instrument `onNewData` sizes; find the constant behind 2840.
3. **H4**: run with ADB detached and screen off — separates "Android USB" from
   "Android USB under load". No code change.
4. **H3**: swap `SerialInputOutputManager` for `port.read()` on a thread.
5. If all fail: capture on the laptop with `usbmon` while the *phone* drives
   the device — not possible directly, but a USB analyser or a passive tap
   would show whether the bytes leave the device.

**Definition of done:** a full read with **zero retries and zero malformed
lines**, in roughly 95 s, verified byte-identical against
`data/usb_final.bin` on the common prefix.

> **Met on the 11:00 run.** 0 retries, 0 malformed chunk lines, 95 s of block
> time, identical across the whole 5,479,850-byte common prefix.
>
> The transcript still reports one malformed line, `0,27,15,18,,,,,,1.44,1.16,
> 0.86*04`. It is transcript line 3, before any block read: the tail of a
> `$GPGSA` already in flight when DTR was asserted. Expect it on every connect;
> it is the live NMEA stream, not a chunk. If a future run needs a truly clean
> count, filter to `PMTK182,8` sentences.
>
> **Not chased, and deliberately so:** the `1 + 511` split doubles the number of
> transfers (42,000 where ~21,000 would do), and per-block throughput is
> 53 KB/s against the laptop's 64 KB/s. It is firmware-side write granularity,
> every unit legitimately ends in a short packet, and no host-side change can
> merge them. Correctness is unaffected.

---

## 11. What is already finished and should not be re-litigated

- **Parser**: 126,613-fix golden file, 0 checksum failures, mixed-format
  handling, GPS week rollover (+1024 weeks), sentinel and quality masking.
- **Bluetooth**: pairing, bonding with stale-key recovery, PMTK, full and
  incremental download — proven byte-for-byte against hardware.
- **Incremental fetch**: wrap-probe safety argument, verified on hardware
  (4 blocks instead of 82; new data reconciled to the exact byte).
- **USB**: clean. 0 retries, 0 corruption, ~110 s, verified byte-identical on
  the common prefix. Do not tune transport parameters again without a measured
  mechanism — that was the failure mode of the whole first session.
- **Erase**: built, gated and tested — commit `94492c1`. All seven of prep doc
  §0.2's clauses are implemented in `core/.../protocol/EraseGate.kt` (the
  decision) and `FlashEraser.kt` (the sequence).
- **Recording audit**: any dump reports whether the logger silently stopped —
  see §12, which is the most important thing this project learned.
- **110 tests**, none requiring hardware.

v1 closed 2026-09-06 with the transport fixed, erase built, and the silent
capture failure in §12 found and closed.

**Still outstanding, in rough order:**

1. **Erase against real hardware.** Verified against the simulator and the
   on-phone UI only. The one path no test can cover is the real device's
   response to `PMTK182,6,1` — how long it actually takes, and whether the 90 s
   `PmtkClient.ERASE_TIMEOUT_MILLIS` is right. Do it with a dump already
   exported off the phone; that is the run where a mistake is real.
2. ~~**A full Bluetooth download.** ~3 hours at 493 B/s against a nearly-full
   chip, which is the point — the flash is deliberately being left full so the
   long path gets exercised for real. Watch for the idle timeout and the
   foreground service surviving a screen-off overnight run.~~

   > **Done 2026-09-08, on an empty chip — so half of it still stands.** A
   > complete Bluetooth read to end-of-data: 5 blocks, 327,680 bytes, 827 s,
   > 396 B/s, **zero retries, zero link rebuilds, zero checksum failures**,
   > stopping correctly on two consecutive unwritten blocks. Parsed to 2,768
   > records, time monotonic. Capture at `data/bt_full_2026-09-08.bin`.
   >
   > What this does **not** yet prove is the long path: the flash had been
   > erased, so this was 14 minutes and 5 blocks, not 3 hours and 84. The
   > nearly-full-chip run is still outstanding, and with it the screen-off
   > overnight behaviour. Nor did it exercise `downloadWithLinkRecovery` —
   > nothing wedged, so the recovery code has still never run against hardware.
3. **Tighten the simulator** (§9). It still accepts sub-block reads and answers
   instantly, and every download bug this project has had lived in that gap.
4. The `1 + 511` throughput curiosity in §10, which is optional.

---

## 12. The silent capture failure — read this before touching config writes

Found 2026-09-06, and the most consequential bug in the project's history.
Nothing else here can cost a user data that never existed in the first place.

### What happened

The logger stopped recording at `2026-09-06T16:16:10Z` and stayed stopped for
about 1h40m, through a power cycle and a re-pair, while looking completely
normal. The user noticed only because an LED was not blinking.

### Why

The device refuses configuration changes while recording, so every config write
is a three-step sequence:

```kotlin
c.writeLoggingEnabled(false)                 // 1. stop
c.writeConfig(TIME_INTERVAL, "50")           // 2. change the setting
c.writeLoggingEnabled(true)                  // 3. start
```

Step 2 threw. In Kotlin an exception exits the function, so **step 3 never
ran**. The disable was deliberate and succeeded; nothing ever undid it. Because
on/off is saved configuration rather than live state, it survived power cycles
indefinitely.

The user was told only that their *setting change* had failed.

### How it was proved

Not from the app — `RingTranscript` is in memory and dies with the process, and
two APK installs destroyed exactly the window that mattered. **The device keeps
its own record.** It writes a `0x07` marker on every logging start and stop,
inline between timestamped fixes.

The fingerprint is a `DISABLE` immediately followed by another `DISABLE` — a
stop nothing answered:

| Period | Unanswered stops |
|---|---|
| 2025-03-16 → 2026-09-04 (17 months) | **0** |
| 2026-09-05, when the app began writing config | **3** |

A trailing `DISABLE` is **not** a fault: powering the device off writes one and
the matching start arrives at the next power-on. An early version of this
analysis got that wrong and its own test caught it.

### What now prevents it

1. `PmtkClient.withLoggingPaused` restores logging in a `finally`. **One place,
   in the tested module** — config writes and the eraser both route through it.
2. A test that fails on the old code: it makes step 2 throw and asserts step 3
   still ran.
3. If the restore itself fails, an `onRestoreFailed` callback fires — a callback
   and not a return value, because it must reach the caller when the body also
   threw, which is when a `return` never runs.
4. The Device tab shows **NOT RECORDING** in error colour, decoded from
   `PMTK182,2,7` bit `0x02`, with a Resume control.
5. `ACTION_USB_DEVICE_DETACHED` is watched, so the app stops believing it is
   connected to a device that has been unplugged.
6. `RecordingAudit` reports unanswered stops from any dump, forever.

### The rule

**A clean parse says nothing about recording.** Explaining on every ordinary
dump why a benign condition is benign is the noise that teaches people to skim,
and skimming is how the serious case gets missed. Silence is the good news.

Any future code that disables logging must go through `withLoggingPaused`. If
you find yourself writing `writeLoggingEnabled(false)` at a call site, that is
the bug being reintroduced.

---

## 13. v2 — the field-testing superpowers

Agreed 2026-09-06, at the point where v1 was closed and field testing was about
to start. All four are wanted; the order below is the order they were proposed,
not a settled sequence. Two constraints cut across them, recorded here so they
are not rediscovered:

- **The watchdog has the strongest claim on going first.** It is the only one of
  the four that attacks the §12 failure class directly, and §12 is the only
  class of bug here that can cost data that never existed.
- **The trail loop must not ship before erase has run against real hardware**
  (§11, outstanding item 1). Wrapping an unverified erase inside a one-button
  flow is how a deliberate step becomes an incidental one.

### 13.1 The trail loop as one button

Dump → verify → export off-phone → erase → confirm, as a single guarded flow
over the existing `EraseGate` clauses rather than five manual steps.

The case for it is the duty cycle, not the convenience: at the current 5 s
interval the flash holds roughly 5 days, so a long trail means running this
loop ~25 times — tired, in a tent, on a low battery. That is the condition
under which a manual multi-step workflow loses a step.

**Precondition:** the real-hardware erase run. Until `PMTK182,6,1` has been
timed against the device, `PmtkClient.ERASE_TIMEOUT_MILLIS = 90 s` is a guess,
and §9's warning stands — the simulator is more forgiving than the device.

### 13.2 Burn-rate forecast

Turn the write pointer, log interval and record size into a real calendar date:
"dump by <date>". Currently the user has to hold 5 days / 5 s / 48 bytes in
their head.

Derivable and honest from what the device reports: field 8 of the status
response is a **byte address** and is trustworthy. Its *record count* is not —
247,133 against 126,613 actual (§9). Build on field 8; ignore the count.

### 13.3 Recording watchdog

A periodic status poll plus a notification when `PMTK182,2,7` bit `0x02` says
NOT RECORDING.

§12 cost 1h40m of trail that was never recorded, and it was caught only because
a human happened to notice an unlit LED. The Device tab already decodes and
displays the bit — the gap is that something has to be looking. This turns "I
happened to look" into "the phone told me."

Subject to §12's rule: alarm on real loss, stay silent otherwise. A watchdog
that reports good news is a watchdog that trains you to swipe it away.

### 13.4 Download that survives the trail

Checkpoint completed blocks to disk so a dropped link resumes rather than
restarts.

Relevant to §11 outstanding item 2: a full Bluetooth read is ~3 hours at
493 B/s against a nearly-full chip. Three hours is long enough that a link drop
is a question of when, and a restart-from-zero costs the whole run.

---

## 14. The rename to Traxi

Done 2026-09-06, in the window between v1 closing and field testing starting.
The timing was the point: `applicationId` cannot be changed without discarding
the app's on-phone state, and that was cheap on this day and expensive on every
day after it.

`btdroid` was a placeholder — a name chosen in no time at all so there would be
a directory to work in. Traxi is the author's trail name (*taxi*, carried 14
years) with the track in it. The namespace `ai.moonlite` was also wrong and is
gone: this is personal work, not company work.

### What changed

| From | To |
|---|---|
| `ai.moonlite.btdroid` | `thru.taxi.traxi` (Kotlin package, `namespace`, **and `applicationId`**) |
| `rootProject.name = "btdroid"` | `"traxi"` |
| `app_name` = `btdroid` | `Traxi` |
| `BtdroidApplication` | `TraxiApplication` |
| `BtdroidTheme` / `Theme.Btdroid` | `TraxiTheme` / `Theme.Traxi` |
| `-Dbtdroid.dataDir` | `-Dtraxi.dataDir` (undocumented test override, set nowhere) |
| GPX `creator="btdroid"` | `creator="Traxi"` |
| `btdroid-transcript.txt` | `traxi-transcript.txt` |

Verified by `:core:test` (all green) and `:app:assembleDebug`. The four
deprecation warnings in the app build predate the rename.

### What the rename destroyed, deliberately

**`applicationId` is install identity, not a label.** Changing it means Android
installs a different app rather than renaming one. Everything keyed to the
package was abandoned in the old sandbox:

- `filesDir/dumps` — every dump previously downloaded to the phone
- DataStore preferences, including saved logger config
- the per-package USB device permission grant
- the Companion Device Manager association

The Bluetooth bond survived: it lives in the phone's Bluetooth stack, keyed to
the logger's MAC, not to the app.

This was safe **only because of when it was done**. Every capture that matters
was already on the laptop in `data/` — including `cdt_v2.bin`, which the
`.gitignore` marks as the only copy of its trip — and the logger's flash was
still deliberately full, so the device itself remained the source of truth. The
phone held nothing that was not reproducible.

> **If `applicationId` is ever changed again, it is not a rename — it is a data
> migration.** Export every dump off the phone first and confirm the exports,
> exactly as §11's erase run requires. On trail, where the phone's dumps are the
> verified copies that license erasing the device, this operation would be a way
> to lose a week of trail to a cosmetic change.

### What was kept

`namespace` and `applicationId` were changed together here, but they are
independent in AGP — compile-time identity and install identity. A future
rebrand that only wants to look different can move `namespace` and leave
`applicationId` alone, and cost nothing.

---

## 13. The logger stopped logging — device facts learned the hard way

2026-09-06, after v1. The device refused to record for several hours while
looking healthy. Most of a session went into it. What follows is the part worth
keeping.

### The recovery sequence

```
1. $PMTK182,6,1        FORMAT LOG ALL      (~22 s; SPI goes unresponsive, then RDY)
2. POWER-CYCLE THE DEVICE                  <-- load-bearing
3. $PMTK182,4          START_LOG           re-arm; a restart comes up disarmed
4. restore config                          the format resets it (see below)
```

**Step 2 is the one that matters and the one we lost hours not knowing.** A
format alone clears `need_format` and gives a clean flash, but the running
firmware keeps its pre-format state: `START_LOG` goes on acking and the engine
goes on dropping it within the second. It has to boot with a clean log area
underneath it.

> **Corrected 2026-09-06, from the account of the person who did the
> recovery.** The sequence above compresses hours into four tidy lines and
> loses the two facts a future reader will need most.
>
> **The fingerprint is an enable that takes and then reverts, not one that is
> refused.** The device streamed NMEA normally the whole time and acked
> `START_LOG`; the auto_log bit (`0x0002`) then cleared itself again — ENABLE →
> DISABLE, every attempt, for hours, and an ordinary power cycle did not clear
> it. The flash journal corroborates this verbatim: the post-recovery image
> holds run after run of paired `LOGSTAT 258 / 256` markers with nothing
> between them (offsets `0x0300`–`0x04B0`), the device's own record of each
> round of that fight. The last marker on the chip is the one enable that
> finally held.
>
> **The format that mattered was at the SPI level, not `$PMTK182,6,1`.** It
> took reading the flash datasheet and formatting with an `spi_`-prefixed
> command. *(The exact command string is not captured here — it should be
> pulled from shell history and pasted in; this is the single most valuable
> missing line in this section.)*
>
> **After the format the device still looks dead until it is power-cycled.**
> Enables went on reverting after the SPI format, the recovery was reasonably
> judged a failure, and there was a full mourning period before a
> just-in-case retry — after a power cycle — worked. Between the format and
> the next boot, a recovered device is indistinguishable from an unrecovered
> one. Do not declare death between steps 1 and 2.

**A format resets configuration.** Observed, not documented:

| Field | After a format |
|---|---|
| `RCD METHOD` | **2 = STP** (was OVP) — restore with `$PMTK182,1,6,1` |
| `BY_SEC` | 10 = 1.0 s |
| `FMT_REG` | `0x0002002F` — not the manual's stated `0x0000000D` |

### The LOG STATUS bitmask, decoded

`PMTK182,2,7` returns a decimal bitmask. Bit meanings are from the BT747
project; the 0-based indexing is confirmed against this device's own behaviour,
and independently by GPSBabel, which tests `log_status & 2` for "enabled".

| Bit | Value | Meaning |
|---|---|---|
| 1 | `0x0002` | auto_log by criteria ON/OFF |
| 2 | `0x0004` | stop_when_full (STOP vs OVERWRITE) |
| 8 | `0x0100` | device is in enable status |
| 9 | `0x0200` | device is in disable status |
| 10 | `0x0400` | **need_format** |
| 11 | `0x0800` | memory_full |

Observed on this device: `0x0102` recording, `0x0100` powered but not
recording, `0x0104` after a format (STOP mode), `0x0504` with `need_format`
asserted.

### Query fields Traxi does not implement

`Pmtk.ConfigField` knows 2–9. The device also answers:

| Field | Query | Returns |
|---|---|---|
| 1 | `$PMTK182,2,1` | **SPI STATUS** — 1 RDY, 2 BSY, 3 FULL |
| 10 | `$PMTK182,2,10` | RCD RCNT (record count; ~2x inflated on this firmware) |
| 11 | `$PMTK182,2,11` | **RCD FSECTOR** — failed-sector register, 16 slots, `FFFF` = empty |
| 12 | `$PMTK182,2,12` | logger library version (`139` = 1.39) |

Traxi could not have reported `need_format` or a failing flash, because it never
asks. Fields 1 and 11 are the two worth adding.

### Commands 9–12 are engineering tooling. Do not use them.

The manual lists `INIT_LOG` (9), `ENABLE_LOG` (10), `DISABLE_LOG` (11) and
`WRITE_LOG <addr> <data>` (12) with **no description of what any of them do**.

`$PMTK182,10` was sent on the reasoning that 4/5 drive the auto-log function
while 10/11 drive the logger itself, and that its worst case was "rejected or a
no-op". Both were guesses. It returned no acknowledgement at all, asserted
`need_format`, and moved `RCD ADDR` to `0x00000002`. A power cycle and a format
cleared it and nothing was lost, but the prediction was wrong in a way that
could have been much worse on hardware that cannot be replaced.

`WRITE_LOG` writes arbitrary bytes to an arbitrary flash address. That is the
company these commands keep. The manual also warns of the adjacent PARTIAL
format that it is "designed for the engineering test only", and elsewhere: *"DO
NOT issue commands except the QUERY_LOG_STATUS, STATUS ($PMTK182,2,1)"*.

### The LED means fix, not recording

Verified over hours: steady blink, 3D DGPS fix on 7–8 satellites, HDOP 1.14,
and **zero bytes written**. There is no local indicator of whether the device is
recording. The app is the only honest signal, which is what §12's indicator is
for.

### Recovery confirmed 2026-09-06 ~23:00Z — the device is logging again

Verified from the laptop with read-only queries and one full-block read,
then decoded with the app's own parser (`MtkLogParser` + `Quality.filter`
against the dumped block, all green):

- `LOG STATUS = 258 (0x102)`, SPI RDY, failed-sector register empty, method
  back to OVP. Write pointer advancing in real time.
- **Fix records resumed at `2026-09-06T22:47:44Z`** (raw timestamps read
  2007-01-21; the +1024-week rollover lands them correctly). 65 fixes in the
  first block, 0 checksum failures, all 65 kept by the quality filter,
  positions coherent at ~41.884N 87.804W on a 10 s cadence.
- The post-format flash opens with **49 marker records** — the journal of the
  whole recovery session. Two of them are unanswered stops, so
  `RecordingAudit` **will report `unansweredStops = 2` on every future dump of
  this flash**. That is the recovery session's scar, not new loss. It ages out
  only when the flash is next erased.
- The journal also bears on the unexplained `BY_SEC 100 vs 50` below: it holds
  one `PERIOD 50` marker followed later by two `PERIOD 100` markers with
  stop/start churn between — so 100 *was* written to the device twice after
  the 50; the mystery is by whom, not whether.

Config then restored per step 4, over `/dev/ttyACM0`, with the stop → write →
unconditional restart discipline of `withLoggingPaused` and each step
acknowledged: `FMT_REG` back to `0x000A1C3F`, interval back to 5.0 s. Verified
after: status 258, and the pointer moved **+144 bytes in 15 s = exactly 3 × 48-
byte records at 5 s** — the arithmetic confirms the 11-field format took.

### What is still not explained

The device logged for 18 months, then stopped at `2026-09-06T16:16:10Z`,
fifteen minutes after a 5.6 MB USB download, around an unplug of a session the
app never closed cleanly. `need_format` turning out to be genuinely asserted
fits the manual's account of losing power mid-sector-init while logging. That is
consistent, not proven.

Also unexplained: after recovery the device reported `BY_SEC` as `100` when it
had been set to `50`, with no write in between.

### Sources

- MTK GPS Logger Library User Manual 1.2 — `https://www.rigacci.org/wiki/lib/exe/fetch.php/doc/appunti/hardware/gps/mtk_logger_library_user_manual_1.2_tsi.pdf`

  **A local copy is committed at `docs/mtk-gps-logger-library-user-manual-1.2.pdf`
  — treat the URL as fragile.** It is a leaked vendor document (MT3301, GPS
  Team, Andy Lee, released 2006-11-29, marked "MTK CONFIDENTIAL / NO
  DISCLOSURE") hosted on a personal wiki, linked from
  `https://www.rigacci.org/wiki/doku.php/doc/appunti/hardware/gps_logger_i_blue_747`.
  Where things are in it: the full command table (FORMAT LOG = `PMTK182,6`
  with 1: ALL / 2: PARTIAL, plus INIT_LOG 9, ENABLE_LOG 10, DISABLE_LOG 11,
  WRITE_LOG 12) is on page 7 of 22; FORMAT LOG behaviour — "FORMAT ALL can
  reset the internal buffer to become all 0xFF" and the NEED_FORMAT_BIT
  explanation — is in Application Notes §(5), around page 9.

  Extraction trap: `pdftotext -layout` is what makes this file readable.
  A Python stream extraction produced garbage on it, which was briefly
  mistaken for the document not containing the command table at all.
- BT747 status bitmask — `https://sourceforge.net/p/bt747/discussion/696105/thread/19a6ddd7/`
- GPSBabel `mtk_logger.cc` — `https://github.com/GPSBabel/gpsbabel/blob/master/mtk_logger.cc`

---

## 15. The Bluetooth link — asking is what breaks it

Found 2026-09-07, across a test ride and a bench session the same evening. The
short version: **listening to this logger is free, and talking to it is not.**
Every unexplained Bluetooth failure in the project so far has been a
consequence of that, and none of them were the radio's fault.

Read this before adding anything that queries the device on a timer.

### What happened

Two failures that looked unrelated and were the same thing seen from
different distances.

On the ride, the app froze after at most ten minutes: every counter stopped,
the Live pane included, while the app still reported a healthy connection.
Disconnecting and reconnecting resumed instantly. On the bench that evening,
after the freeze was fixed, the link began tearing down every two to four
minutes instead.

The second failure was not a regression. It was the first one becoming
visible.

### Why: the app was the only thing keeping the link alive by being broken

A foreground service covered downloads only, on the reasoning that a transfer
is the long-running thing. An idle-connected session is equally long-running
and had no protection at all, so between user actions the app was an ordinary
background process — and this phone's cached-app freezer suspends those within
about ten minutes of leaving the foreground.

What that does to RFCOMM is quiet and total. The read loop stops being
scheduled, the socket's receive buffer fills, flow control tells the logger to
stop sending, and **the link stays up the whole time.** Nothing disconnects,
so nothing is reported. Android's own ACL table, read afterwards from
`dumpsys bluetooth_manager`, shows what the app could not:

| Session | Duration | Teardown reason | Actual cause |
|---|---|---|---|
| 16:45–17:24 | **39 min** | CONNECTION_TIMEOUT | user power-cycled the logger |
| 17:25–17:40 | **15 min** | CONNECTION_TIMEOUT | user power-cycled the logger |
| 17:40–18:00 | **20 min** | TERMINATED_BY_LOCAL_HOST | phone-side close |
| 18:15–18:51 | **36 min** | CONNECTION_TIMEOUT | end-of-ride power-off |

The radio never dropped on its own, not once. A frozen app sends nothing, and
a link nobody talks to survives for as long as you like. Fixing the freeze
removed the accidental protection and exposed what was underneath.

### Why: the write-pointer probe is a denial of service

The recording indicator read `PMTK182,2,8` every 12 seconds. On the bench,
17 probes drew 14 answers, and **all three unanswered probes were followed
within a second by the link going down** — once with the logger itself
terminating it (`REMOTE_USER_TERMINATED_CONNECTION`), 320 ms after the query,
with NMEA flowing normally right up to it.

It is progressive exhaustion, not a random collision with the NMEA stream.
Probe latency climbs monotonically until the device stops answering:

```
132ms → 233ms → 425ms → 485ms → 459ms → 1088ms → 659ms → 1142ms → no reply → link down
```

A random collision gives flat latency with occasional misses. It does not give
a 9x climb ending in death. Something is consumed per request and not fully
released.

No bandwidth is involved, and it is worth being precise about that because it
is the wrong intuition. Idle NMEA uses 43% of the bridge; a probe exchange is
about 40 bytes. There is ample headroom. The resource being exhausted is a
buffer, a queue or a state-machine slot in the logger's Bluetooth firmware —
not the wire.

### How it was proved

Three measurements, all from one timestamped transcript.

1. **The correlation.** Every teardown lands within a second of a probe, and
   the transcript's per-line millisecond stamps are what make that visible at
   all. Before this session the transcript had no timestamps.
2. **The latency climb**, above, which distinguishes exhaustion from a race.
3. **The innocent explanation, ruled out.** If bytes were queueing in the link
   rather than in the device, end-to-end lag would grow too. Each GGA carries
   the receiver's own UTC, so comparing it against the receive stamp measures
   that directly: lag stayed flat and non-monotonic, 162 ms early against
   256 ms late. The command path degrades while the streaming path stays
   healthy. Nothing is backing up in the link.

The control, after the fix, on the same logger the same evening: **7 probes,
7 answered, 21.7 minutes unbroken, latency flat at 80–724 ms with no trend.**
The exhausted resource recovers given quiet.

Evidence is committed, because none of this is reconstructible without it:

```
data/bt_ride_2026-09-07.bin                    the ride dump
data/bt-acl-history-ride-2026-09-07.txt        Android's ACL table
data/bt-probe-teardown-transcript-2026-09-07.log   the failure
data/bt-probe-fix-verified-2026-09-07.log          the control
```

### The device was never at fault

Worth stating plainly, because two evenings were spent suspecting it. The
ride dump holds **782 fixes over 130.6 minutes against ~784 expected**, with
exactly two lost — both at user power cycles, both confirmed by `0x07` markers
in the flash. The logger recorded perfectly through every freeze and every
teardown. No Bluetooth failure in this project has ever cost a fix.

### What now prevents it

1. **The transcript is mirrored to disk as it is written**
   (`TranscriptFile`, attached as `RingTranscript.sink`). §12 was proved from
   the device's own flash markers precisely because the in-memory transcript
   died with the process, and this session repeated that mistake: an
   `adb install` destroyed the ride's transcript before it could be read. An
   in-memory diagnostic cannot survive the events it exists to diagnose. Two
   startup banners with no orderly shutdown between them are now the signature
   of a kill.
2. **`LinkService` holds a `connectedDevice` foreground service for the whole
   session**, not just transfers, and the Device tab says plainly when battery
   optimisation is still allowed to suspend the app.
3. **Probe cadence is a property of the transport**
   (`Transport.writePointerProbeIntervalMillis`), exactly like
   `blockReadIdleTimeoutMillis` and for the same reason: an unmeasured link is
   assumed fragile rather than assumed free. Bluetooth 10 min, USB 12 s. One
   early probe still runs on every link so the recording indicator confirms
   promptly.
4. **The read loop can no longer die silently.** It used to exit on a bare
   `break`; the session went on reporting "Connected" over a stream that would
   never produce another byte. Exits are now loud, and `LinkHealth` reports
   silence measured in bytes off the wire — this logger streams NMEA whenever
   powered, so no bytes at all is unambiguous, while a missing *fix* indoors
   means nothing.
5. **The recording indicator is driven by observation, not by a clock.** Its
   staleness threshold was 30 s, sized for the 12-second cadence; at a
   ten-minute cadence it decayed into shouting NOT RECORDING at a logger that
   was recording. Only a sample that looked and found the pointer unmoved may
   now raise that alarm, and a no-movement verdict is ignored unless it spans
   more than the configured log interval. Not having looked lately renders as
   RECORDING with the age of the fact stated.

Nothing here tears a session down on its own. A quiet link recovers often
enough that killing it automatically was already a regression once.

### The rule

**Every safety check in this app asks "does this mutate device state?" That
question is not sufficient. It also has to ask "does this consume device
attention?"**

`queryWritePointer` passes every existing rail — *Connecting never writes*, the
erase gate, the naming rule in `PmtkClient`. It changes nothing on the device.
It also ends sessions, autonomously, on a timer, from code that models itself
as a passive observer. A read-only query is not automatically a safe one, and
on this hardware the read-only ones are the dangerous ones.

So: **before adding anything that puts bytes on the wire unattended, assume it
costs Bluetooth stability until measured.**

### Still open

- **`eraseBlockers()` reads the write pointer too**, on Dumps-tab composition.
  Same blind spot, deliberately left alone for now; measured at ~3-minute
  spacing during the verified session, which the device tolerated.
- **Is the leak specific to `PMTK182,2,8`, or per-command?** If per-command, a
  full three-hour Bluetooth download issues thousands of them and may not
  survive — and §11 still lists that download as never completed. Climbing
  per-block latency in the transcript would show it. The per-block
  `arrival gaps ms:` line exists to make exactly this visible.
- **The design this points at, agreed and deferred**: treat the logger as a
  remote, resource-constrained embedded device — conserve the channel, mine
  latency and other sideband as signal, and make **no unattended
  interventions** on a fragile link. One probe at connect, then nothing until
  a person asks. The 10-minute interval is a dose reduction, not that design.

### 15.1 The wedge is per-session, and reconnecting too fast inherits it

Found 2026-09-08, a few hours after §15, when Bluetooth stopped connecting
entirely and a working pairing had to be restored by hand. Same root cause,
two new mechanisms, and one of them makes long Bluetooth downloads possible
where they previously were not.

#### A new radio link clears the wedge; a reused one does not

The exhausted resource lives in the **RFCOMM session**, not in the device.
Proof, from one evening's transcript: after the logger had gone silent and the
ACL eventually timed out, a freshly built link answered `PMTK605` in **146 ms**
and every query after it promptly. Nothing was power-cycled. The device was
never broken — the session was.

That is why "disconnect and reconnect resumes instantly" was true from the
first ride, and it is the cure this section is built on.

The trap is that **closing a socket does not drop the ACL.** Android keeps the
radio link up for some seconds and reuses it for the next connection, which is
ordinarily a courtesy. Here it hands the next socket the same wedged session.

| Reconnect | Old ACL actually died | Result |
|---|---|---|
| 23:45, attempted 7 s after disconnect | **23:46:06**, by supervision timeout | stalled ~65 s, then a clean link — answers in 146 ms |
| 23:58, attempted 4 s after disconnect | 23:58:48, ten seconds *later* | opened on the old link — NMEA streaming, every command unanswered |

The first reconnect worked *because it was slow enough to fail first*. The
second was fast enough to succeed at connecting and inherit a dead session.

`AclLink.awaitDown` now blocks a reconnect until the link is genuinely down. If
it will not drop within the timeout the app says so and connects anyway:
refusing to connect over a link that merely refused to drop would turn a slow
reconnect into no reconnect at all.

#### The app deleted a working pairing over it

The stale-link-key recovery — legitimate, and documented in §6 — wrapped the
whole of connect, including the interrogation that runs *after* a successful
socket open. `Bonding.looksStale()` is only "is this device bonded". So the
`PMTK605` timeout above landed in that handler, was read as a bad link key, and
removed the bond.

Re-pairing then failed nine times: `AUTH_FAIL : 4` each time, uniformly ~6.4 s,
**and no PIN prompt ever appeared.** That is not a rejection and not a lockout;
it is the logger never answering the pairing page, because its radio was
unresponsive to everything by then. Recovery needed a power cycle and a PIN
typed by hand.

**A live socket is proof the link key was good.** Only the socket open may now
be blamed on a stale key; the interrogation runs outside that handler, where
nothing it does can touch the pairing. The message for it says what is true —
the link opened, the logger stopped answering, power-cycle it, the pairing is
fine, and USB needs no pairing at all.

This is the auto-remediation rule from §13.3 with teeth on it. The remedy was
destructive and undoing it needed physical access to the device.

#### Sustained reading wedges it too, which is what long downloads run into

The write-pointer probe was never special. During a resumed download that
evening, block `0x00010000` transferred perfectly — 65,536 bytes, 32 chunks,
150 s — and **the very next block returned zero bytes on all three attempts.**
Not short: nothing. The probe went unanswered 30 s later. So the leak is
per-command, not per-query-type, and §15's open question is closed.

Note the load. A download runs the UART at ~95% (§6): 415 B/s of NMEA plus
493 B/s of payload against ~960 B/s. Idle is 43%. Both regimes wedge, the
loaded one far faster.

The arithmetic this implies is the important part. A full image is 84 blocks at
~150 s. §11 has always listed a full Bluetooth download as never completed, and
on this evidence it *could not* complete: it would stall within minutes and save
a partial. This is not a slow path, it is a blocked one.

#### Pace and recover

The resume machinery built for "resume, never restart" — so a dropped link
could not cost 25 minutes — turns out to be exactly the tool for this. Each
recovered segment picks up at the write frontier, so nothing is re-read and
nothing is lost.

`SessionController.downloadWithLinkRecovery` runs a transfer, and each time the
device stops answering it rebuilds the radio link and resumes. Bounded twice:
a cap on cycles, and a hard requirement that **every cycle gain bytes**, so it
cannot spin. Every rebuild is written to the transcript and counted in the
progress card as "Link rebuilds", because a transfer that needed six of them is
a different event from one that needed none, and a recovery the user cannot see
is indistinguishable from the app being mysteriously slow.

`FlashDownloader.Result.stoppedUnanswered` carries the condition, and
`UnansweredBlockTest` pins both directions of it: silence must not read as
end-of-flash (which would truncate a dump and call it complete), and
end-of-flash must not read as silence (which would rebuild the link forever).

#### Why this one is allowed to act on its own

Two earlier automatic remedies in this project were regressions: tearing down a
merely-quiet link, and deleting a bond because a query timed out. Both acted on
a **conjecture** about state, and one was destructive.

This one reacts to an **observed** condition — three consecutive block attempts
each returning zero bytes — with a non-destructive remedy, during a transfer
the user explicitly started, and reports every instance. That is a different
kind of act, and it is the line worth holding when the next automatic recovery
is proposed.

#### Still open

- **USB remains the right transport for a full dump**: 95 seconds against
  3.5 hours, and none of this. Bluetooth's job is incremental fetch. The
  recovery above exists because the phone is sometimes the only computer in the
  field, not because Bluetooth became a good way to move 5.4 MB.
- **Pace-and-recover has not yet been proven against a full 84-block read.**
  Everything above is measured; that specific claim is not, and §11 item 2
  stays open until a full Bluetooth download actually completes.
- **`eraseBlockers()` still probes the write pointer** on Dumps-tab
  composition — the same unattended-intervention blind spot, untouched.

### 15.2 The slide switch beats the firmware, and the status word will lie about it

Found 2026-09-08. The device has a three-position slide switch — **OFF / NAV /
LOG** — and NAV means *fix but deliberately do not record*. Nothing in this
project modelled that. The app had two states, recording and faulted, and
rendered a switch position the user had chosen on purpose as data loss.

Worse, the state it reports cannot be trusted even when it says the reassuring
thing.

#### The measured state table

| Switch | Software | Status word | Write pointer | Actually recording |
|---|---|---|---|---|
| LOG | enabled | `0x0102` | advancing | **yes** |
| LOG | disabled | `0x0100` | frozen | no — the §12 fault |
| NAV | enabled | **`0x0102`** | **frozen** | **no — the switch wins** |
| NAV | disabled | `0x0100` | frozen | no |

Two conclusions, both measured on hardware rather than reasoned about.

**`0x0100` is ambiguous and always will be.** A software disable with the
switch on LOG produces a byte-identical status word to the NAV position. Both
`PMTK182,5` and `PMTK182,4` ack flag 3 either way. There is no bit that
separates "you chose not to log" from "logging is off when you wanted it on" —
bit 9 `0x0200` (*device is in disable status*) has never been observed on this
device in any state.

**`0x0102` is not evidence of recording.** With the switch in NAV, an enable is
accepted, the status flips to `0x0102`, and a status-change marker is written to
flash — and then nothing else is. Measured: pointer moved 32 bytes (two 16-byte
markers, the state change recording itself) and then sat at `0x0001FA20` for
**3 m 24 s with a solid fix**, where a 10-second interval should have written
about twenty records. The firmware says yes; the hardware refuses; the status
word reports the firmware's opinion.

#### The discriminator is behavioural, not a bit

Send an enable, then watch the write pointer for longer than one log interval
**while a fix is present**:

- pointer advances → it was a software disable, and you have just fixed it
- pointer frozen → the switch is in NAV, and no software will change that

This is the §12 lesson arriving from the opposite direction. That section
established the status bit could not be trusted to report a *stopped* logger;
this one establishes it cannot be trusted to report a *running* one either. The
write pointer is the only ground truth in both directions.

#### What the UI now does

The indicator distinguishes three cases instead of two, because a frozen
pointer means nothing without knowing whether there was anything to write:

1. **no fix** — "no fix, nothing to record", stated plainly and *not* styled as
   a fault. Indoors this logger holds no fix for hours and correctly writes
   nothing; the previous wording called that NOT RECORDING and was wrong for a
   whole day before anyone noticed the switch.
2. **fix, pointer advancing** — RECORDING, with the age of the fact.
3. **fix, pointer frozen** — NOT RECORDING, naming the switch *first* because it
   is the likelier cause and the only one the app cannot fix, then the firmware
   disable and its remedy.

The "Reported" row keeps the raw status word but is now explicitly not the
verdict, and when it claims logging while the pointer is frozen under a good
fix, the card says so outright.

#### Also worth knowing

**Moving the slide switch drops the Bluetooth link.** Observed at 20:21:20 that
evening: the link fell over the moment the switch moved, with no other cause. If
a session dies while someone is fiddling with the device, that is why.

#### Still open

- **What left logging disabled on 2026-09-08 morning is unexplained.** The app
  sent no disable that day, and a software enable at 13:18 was followed by the
  pointer advancing — which means the switch was on LOG and something had
  genuinely disabled logging in firmware. Either that, or the switch was in NAV
  and was moved at the same moment. The evidence does not decide it, and this
  section deliberately does not guess.
- The state table above is complete for switch/software combinations, but
  **OFF was never tested**, since testing it means powering the logger down.

### 15.3 The first completed Bluetooth download, and what a healthy block looks like

2026-09-08, 21:04–21:18. The run that §11 item 2 had been waiting for, and the
first hard data on what this link does when nothing is wrong.

```
5 blocks   327,680 bytes   827 s   396 B/s
0 retries   0 link rebuilds   0 checksum failures   0 damaged ranges
stopped correctly on 2 consecutive unwritten blocks at 0x00050000
parsed: 2,768 records, time monotonic
```

Evidence at `data/bt_full_2026-09-08.bin` and
`data/bt-full-download-2026-09-08.log`.

#### Every block has the same internal shape

The per-block arrival gaps are remarkably consistent, and they explain the
"burst then asymptote" that opened this whole line of investigation — a fast
start settling to about `0.3 KB/s`, sector after sector:

| Block | first gap | median | 1st half | 2nd half | ratio |
|---|---|---|---|---|---|
| `0x00000000` | 1346 ms | 7309 ms | 3807 ms | 7160 ms | 1.88 |
| `0x00010000` | 1334 ms | 6821 ms | 3646 ms | 6764 ms | 1.86 |
| `0x00020000` | 1679 ms | 6826 ms | 3428 ms | 6947 ms | 2.03 |
| `0x00030000` | 1087 ms | 6828 ms | 3337 ms | 7069 ms | 2.12 |
| `0x00040000` | 950 ms | 6825 ms | 3441 ms | 6795 ms | 1.97 |

Chunk gaps roughly **double** from the start of a block to its end, and then
**reset completely** at the next block. Block 5 is indistinguishable from block
1, so nothing accumulates across a transfer.

The shape is a buffer draining, not a link degrading. Early chunks arrive at
~3.4 s — `4096` wire bytes per chunk over 3.4 s is ~1200 B/s, comfortably above
what a 9600-baud bridge can carry, so those bytes were already staged. Late
chunks settle at ~6.8 s, about 600 B/s of hex, which is the bridge actually
working. **This is the answer to the original question about the speed burst,
and it is not the same phenomenon as the §15 wedge:** it is bounded, it repeats
identically, and it resets.

Distinguishing the two is the useful part. A healthy block ramps from ~3.4 s to
~6.8 s and starts over. The wedge climbs *across* probes without resetting and
ends in silence.

#### What it does not prove

- **The chip was nearly empty.** This was 14 minutes and 5 blocks; the
  nearly-full case is 84 blocks and hours, and remains untested.
- **`downloadWithLinkRecovery` never ran.** Nothing wedged, so the recovery
  path still has no hardware evidence behind it.
- One clean run is not a stability claim. It is one clean run, after a device
  power cycle and on a freshly built ACL — both of which §15.1 predicts are
  favourable starting conditions.

### 15.4 The fetch that reported "nothing new" about half an hour of riding

2026-09-15, 13:31–13:41. A fetch-new pulled back a 30.5-minute ride and told
the user there was nothing to pull. Nothing was lost — the bytes were fetched,
saved and parsed correctly — but the message was false, and it is the kind of
false that talks someone out of a download.

```
4 blocks   262,144 bytes on the wire   612 s   0.4 KB/s
0 retries   0 link rebuilds   0 checksum failures   0 damaged ranges
stopped correctly on 2 consecutive unwritten blocks at 0x00050000
gained: 8,432 bytes, 170 records — 161 of them the ride, 18:03–18:33Z
reported: "Already up to date — nothing new on the logger"
```

Evidence at `data/bt_fetch_base_2026-09-08.bin` (3,141 fixes) and
`data/bt_fetch_new_2026-09-15.bin` (3,311). `IncrementalGainTest` replays the
transfer between them.

#### Why: the gain was measured from the file's length

```kotlin
val gained = result.image.size - previous.size   // 327680 - 327680 = 0
```

Both dumps are **exactly 327,680 bytes**. New records land at the write
frontier, which sat at `0x00026090` — inside sector 2, with 39 KB still free.
The ride was written into that free space, so the image gained 8,432 bytes of
riding without gaining a single byte of length. The size delta was 0 and the
message fell through to the "nothing new" branch.

This is §12's shape in a new place: a confident negative claim about capture
that is false. §12 was the logger silently not recording; this is the app
silently reporting nothing recorded. The second is cheaper — the data is on
disk either way — but it fails the same way, by sounding certain.

The fix is to measure frontier movement, not length. `frontierOffset` is the
same figure `planIncremental` already notes to the transcript as
`frontier at 0x...`, so the message and the log can now be checked against each
other instead of believed separately.

#### The same disagreement, three more ways

One inconsistency — two phases counting different things — produced four
symptoms. The wrap probe reported against the previous dump's whole length;
the extend that followed began at the frontier *block*.

- **The readout fell backwards at the handover**, 320 KB/sector 5 dropping to
  128 KB/sector 2, then climbing the same ground again. Both phases now report
  against `extendOffset`.
- **"Time left" was blank for the entire extend.** `etaStartBytes` latched at
  327,680 on the probe's first chunk, so `advanced` stayed negative until the
  transfer climbed back past its own starting figure — which happens at the
  end. Same root, same fix.
- **"Sectors" meant three things on screen**, none of them saying which:
  `sizeBytes / 0x10000` in the dumps list (5), `sectorsWithData` in the parse
  card (3), and a download counter (5) that was not a count at all.

That last one deserves its own line. `sectorsRead` was `address / SECTOR_SIZE`
— a **position**, not a tally. It read 5 on a transfer that pulled 4 sectors,
because the two sectors before the frontier were never asked for. Renamed to
`sectorPosition` and `sectorSpan`; `sectorsWithData` was already honest.
`sectorsFetched` is new and is the only figure of the four that measures work.

#### What a fetch-new actually costs

Four sectors are read no matter how little is new:

| sector | why it cannot be skipped |
|---|---|
| wrap probe (0) | the safety check; a whole sector because sub-block reads drop the link |
| the frontier sector | the new records are *inside* it, mixed with old ones |
| two unwritten sectors | the termination rule — see below |

So **~256 KB is a fixed toll per fetch**, not proportional to the gain. At the
measured rate (49.6 bytes/record, 10 s interval, ≈6 sectors/day):

| fetched after | sectors | wire | read per byte kept |
|---|---|---|---|
| one 30-min ride | 4 | 256 KB | 31 : 1 |
| half a day | 7 | 448 KB | 2.3 : 1 |
| one day | 10 | 640 KB | 1.7 : 1 |
| a week | 48 | 3072 KB | 1.1 : 1 |

The 31:1 is not waste discovered in the code; it is a small numerator under a
fixed denominator. **The operational reading is to batch.** Three short rides
fetched separately cost 768 KB; fetched together, 256 KB.

#### Why two unwritten sectors, and why seeing one early does not help

One blank sector is ambiguous. It can mean end of data, a sector whose bytes
never arrived (`0xFF` is indistinguishable from erased flash), or a sector the
device **skipped**. On an append-only log a genuine blank proves nothing
follows it — so the second sector exists solely to defeat the skipped case,
which would now need two adjacent bad sectors. That this device tracks 16
failed sectors in `RCD FSECTOR` is reason to think the case is real.

The app *can* see blankness almost immediately: `SectorHeader.isUnwritten`
needs 16 bytes, and the first chunk carries 2,048, arriving ~1 s into a ~150 s
read. **It gains nothing.** The device does not short-circuit an erased sector:

```
block 0x00020000  65536/65536, 32 chunks, 161755 ms   written
block 0x00030000  65536/65536, 32 chunks, 145733 ms   blank
block 0x00040000  65536/65536, 32 chunks, 151566 ms   blank
```

It transmits 64 KB of `0xFF`, hex-encoded to 128 KB, at the same rate as real
data. The cost is committed when the request goes out, and the granularity of
the request is one sector.

#### The link itself was fine, and that is the other result

Half an hour of Bluetooth with nothing wrong, seven days and a power cycle
after §15.3, and the block shape held:

| block | first gap | median | 1st half | 2nd half | ratio |
|---|---|---|---|---|---|
| `0x00000000` | 1448 ms | 5844 ms | 3300 ms | 6246 ms | 1.89 |
| `0x00020000` | 1216 ms | 5852 ms | 4076 ms | 6033 ms | 1.48 |
| `0x00030000` | 1003 ms | 5364 ms | 3045 ms | 6063 ms | 1.99 |
| `0x00040000` | 978 ms | 5844 ms | 3653 ms | 5819 ms | 1.59 |

Same ramp-and-reset as 2026-09-08 (1.88–2.12), slightly faster medians. Two
link drops at 13:29–13:30, a minute before the transfer, recovered by §15.1's
wait-for-ACL-death rule and did not recur.

#### The rule

**A negative claim about capture must be measured against what was captured,
never against a proxy.** File length is a proxy for content and it is wrong
exactly when the gain is small — which is the common case on the trail, and the
case where a wrong answer costs the most.

And more generally, from how this was found: **read the device or the phone
before diagnosing.** The first two analyses of this bug were written from a
week-old copy in `data/` while the phone sat plugged in, and were wrong about
the base dump, the chip capacity and the sector arithmetic. Repo copies are
history, not state. Two dumps dated the same day were different files.

#### Still open

- **Reading `RCD FSECTOR` (`$PMTK182,2,11`) would justify stopping at one
  blank sector**, halving the termination cost, on evidence rather than
  optimism. §13 already lists fields 1 and 11 as the two worth adding. It is
  one more query on a link where asking is the expensive verb, so it needs
  measuring against §15 first.
- **Is there a read size between 512 and 65,536 that this device honours?** If
  so, most of the 4-sector floor disappears, termination check and wrap probe
  alike. Read-only, so nothing can be written or lost; the known failure mode
  is a dropped RFCOMM link, which §15.1 handles. Untested since the 512-byte
  attempt that dropped the link.
- **§6's logger configuration is stale.** It records a 5.0 s interval and
  48-byte records; the 2026-09-15 data measures **10.0 s** and 49.6 bytes per
  record. §13.2's burn-rate figures inherit the old number.
- The app-side symptoms are covered by tests for the first time — `app/` had no
  test source set at all until this was fixed. The download card's live
  behaviour (`Sectors fetched`, the monotonic readout, the corrected message)
  is still only verified by reading it; it needs a real transfer to watch.

## 16. The audit — seven holes found by reading, not by losing anything

Found 2026-09-15 by going through the whole tree looking for them, after
§15.4. Nothing here cost data yet. That is the only reason this section can be
written calmly, and it is not a reason to rank it below the sections that were
written after a loss.

Every claim below is backed by a test that runs in `:core` or `:app` and that
**passed against the unfixed code** — the finding was put at risk and survived.
Those tests now assert the fixed behaviour instead, so each one is a regression
test for exactly one of these. §16.9 records how that claim was checked, and
what checking it caught.

The two that matter most are §16.1 and §16.3. Both defeat a protection this
project already built, on purpose, after a real incident.

§16.9 records how the fixes were verified against each other, and §16.10 what
happened when they were finally run against the logger — which settled the last
assumption in §16.3 and turned up two things about the hardware that no
simulator could have told us.

### 16.1 A resume launders a holed dump into a verified one

**`FlashDownloader.Result.damagedRanges` describes one `download()` call, not
the image on disk.** The resume path passes the previous image in as `existing`
and starts reading past it, so a hole already in that prefix is copied forward
and counted by nobody. The second result reports `isComplete = true` and
`damagedBytes = 0`.

Everything downstream believes it, because everything downstream was built to:

- `DumpRepository.save(partial = !isComplete)` **deletes the `.partial`
  marker**, so the dump stops being a partial dump;
- `SessionController.pendingEvidence` records `damagedBytes = 0`;
- `EraseGate.blockers` finds nothing to object to, and the erase is permitted;
- the dump list starts offering **Fetch new** on it, which is only offered for
  non-partial dumps — so the hole propagates into every descendant.

That is the whole safety argument of §0.2 clause 1 defeated by a second
download that happened to be clean.

#### The parser cannot catch it, and neither can anything else

A missing range is `0xFF`, which is byte-identical to erased flash. That is
stated in `damagedRanges`' own doc comment as the reason it exists. Inside a
sector, `MtkLogParser` reads `0xFF` as end-of-data fill and jumps to the next
sector, so the records after the hole are dropped **in silence** — no checksum
failure, no bad header, nothing in `Stats`.

Measured on two real sectors of `cdt_v2.bin`, a single lost 2 KB chunk:

| hole | fixes | checksum failures |
|---|---|---|
| none | 3,091 | 0 |
| `0x4000`, 2 KB | 1,921 | **0** |

**1,170 fixes — 38% of the dump — gone, with a clean parse.** Sweeping all 31
chunk positions in that sector: two are completely silent like this one, and
the other 29 raise exactly one checksum failure, from the record that straddles
the hole's leading edge. So the erase gate was being saved, when it was saved
at all, by an accident of record alignment. It is not a protection.

#### What now prevents it

Three things, because the single fix is not enough on its own:

1. **Damage is persisted with the dump.** The `.partial` sidecar already
   carried `resume=<bytes>`; it now also carries the damaged ranges. A dump
   that is missing bytes says so on disk, and says it after a reboot.
2. **A resume re-reads the damaged blocks before it extends.** This is the
   part that recovers data rather than merely reporting its absence: the ranges
   are known, the device serves whole blocks reliably, and re-reading one is
   the same cost as any other block. Only ranges that are *still* missing
   afterwards stay damaged.
3. **Damage that survives is carried into the new `Result`.** So `isComplete`
   stays false, the `.partial` marker stays, the evidence carries a non-zero
   `damagedBytes`, and the gate stays shut.
4. **The dump list says so.** A holed dump now carries a line in red saying how
   many bytes never arrived and that Resume will re-read them. A dump with
   holes looks exactly like a good one, which is the entire hazard, so the
   person choosing what to export or erase against has to be able to see it.

The link recovery gets the repair for free: a rebuilt link is the best chance a
transfer will have of recovering what the wedged one dropped, so each cycle now
hands the earlier segments' holes to the next pass rather than merely carrying
them forward.

#### Still open

Dumps written **before** this fix that were laundered are indistinguishable
from clean ones — there is no marker to read and the holes look like erased
flash. Any dump on the phone older than this change that was ever resumed
should be treated as unverified. There is no way to check it short of
re-downloading and comparing.

### 16.2 A wedge during fetch-new reports "nothing new on the logger"

§15.4 fixed the gain measurement and this survived it, one branch above.

`stoppedUnanswered` is deliberately **not** a `failure` — that distinction is
what lets `downloadWithLinkRecovery` tell a recoverable §15 wedge from a dead
transport. `FetchOutcome.message` never learned it. Its first test is
`failure != null`, which is null for a wedge, so the message falls through to
the byte comparison.

And the comparison is against a **truncated** image. The extend restarts at the
block holding the previous dump's frontier, so a wedge on that first block
leaves an image *shorter* than the dump it was extending. `newDataBytes` goes
negative, `gained > 0` is false, and the last branch — written for a logger
that genuinely had nothing to add — claims exactly that.

So the §12 shape is back, on this device's single most common failure:

> Already up to date — nothing new on the logger

**What now prevents it:** a wedge gets its own branch, and — the general rule —
**"already up to date" is now reachable only from a transfer that completed.**
A negative claim about capture requires a complete measurement, not merely the
absence of a positive one.

### 16.3 Every query leaves a success-ack in the queue, and a write takes it

The worst of the seven, and the one that was hardest to see because the
simulator does not reproduce it.

**The real device acks its queries.** From `data/usb_clean_2026-09-06.log:22`:

```
>> $PMTK182,2,3*38
<< $PMTK001,182,2,3*25        <- the ack
<< $PMTK182,3,3,50*10         <- the value
```

Seventy-six of those in one capture. `queryConfig` waits for the *value*, so
the **ack is never consumed**. `PmtkClient.ingest` queues it, nothing ages it
out, and only `resetStream` — which just the block reader calls — ever clears
the queue.

Now read the write path:

```kotlin
exchange(payload) { Pmtk.Ack.from(it)?.command == "182" }
```

Command only. Never the subcommand, although `Ack` parses one. A leftover
`PMTK001,182,2,3` from *any earlier query* matches that predicate, and
`awaitSentence` consults the queue **before** the wire. So:

> `writeLoggingEnabled(true)` returns success immediately, having put
> `PMTK182,4` on the wire and never waited to see whether the logger obeyed it.

A connect alone runs six queries and parks six of these. No retry, no timeout,
no marginal link required. Every config write and both halves of
`withLoggingPaused` were exposed — which is to say **the §12 guarantee, the one
this project cares most about, could report a restored logger that was still
switched off.**

The same mechanism serves stale *values* as well as stale acks. A retried
query — routine on a link where three probes in seventeen go unanswered — is
answered twice, the second copy is parked, and the next caller gets it. That
reaches the write pointer, which is the only truth about recording (§15.2) and
the freshness the erase gate rests on: a pointer read from minutes ago can
clear the coverage check that a current one would fail.

**What now prevents it:**

- acks are matched on **command and subcommand**, which the device supplies on
  every one;
- `exchange` drops the queue before it sends, so no answer predating a request
  can satisfy it;
- `pump` no longer queues at all. Nothing is waiting during a pump by
  definition, so queuing there only ever parked staleness for a later caller;
- `exchange` also **drains the wire** before sending, which extends the same
  rule one layer down, to bytes that have arrived but not yet been parsed;
- **the simulator now acks queries like the device does**, so this class of bug
  cannot survive the test suite again. That is the §15.4 lesson repeated: a
  simulator more forgiving than the hardware hides exactly the bugs worth
  finding.

#### Still open, and it is a real limit

**An answer still inside the device when the next request goes out cannot be
told from an answer to that request.** PMTK carries no request id, so there is
nothing to correlate on. Clearing the queue and draining the wire cover
everything that has *arrived*; a duplicate that is still in the logger's output
buffer when the next query is sent will be matched by it.

The exposure is now small and bounded — it needs a retry, and then a second
request inside the round trip that follows, where the steady probe interval is
60 s — but it is not zero, and a stale write pointer is exactly the input the
erase gate's coverage rule trusts. The `retry` note in the transcript is what
makes such a reading explicable after the fact. A proper fix needs a
correlating field the protocol does not have, or a rule that treats a repeated
pointer value as inconclusive when a retry preceded it.

### 16.4 A link cycle erases the full-re-read flag

`downloadWithLinkRecovery` rebuilds its result from the continuation:

```kotlin
result = continued.copy(
    retries = result.retries + continued.retries,
    damagedRanges = result.damagedRanges + continued.damagedRanges,
)
```

`continued` comes from a plain `download()`, which never saw a wrap probe, so
`fullReread` reverts to false and `sectorsFetched` drops the earlier segments.
Any one of the sixty permitted link cycles erases the flag — and the flag is
the only thing stopping the app claiming an amount added after a wrap, which
`FetchOutcomeTest` asserts must never happen. **Carried forward explicitly
now**, along with the work counter.

### 16.5 "Clear pairing and re-pair" could never reconnect

`clearPairingAndReconnect` sets `Connecting`, clears the bond, re-bonds, and
calls `connect(address)` — whose first line is:

```kotlin
if (_connection.value is ConnectionState.Connecting) return
```

It is still `Connecting`, set by the caller and never cleared on the success
path, so `connect` returns immediately and nothing connects. The Connecting
card renders a progress bar and no buttons, and the session lives in the
Application scope, so the UI sits there until the process is killed — after
destroying a pairing that then has to be re-entered by hand at the device.

This is the recovery path for a stale link key, which means it fails in exactly
the situation it exists for.

The same guard had a second defect: it is checked *outside* the coroutine that
sets the state, so two taps inside one dispatch both pass it and open two
RFCOMM sockets to a device that serves one SPP client, leaking the first.

**What now prevents it:** the guard is an atomic claim taken synchronously in
`connect` and released when the attempt ends, rather than an inference from UI
state. One flag fixes both halves.

### 16.6 The probe rate limit was advisory

`Transport.writePointerProbeIntervalMillis` is the §15 mitigation: 60 s over
Bluetooth, because 17 probes in three minutes took the link down. Only the
telemetry loop honoured it.

`EraseCard`'s `LaunchedEffect` calls `eraseBlockers()`, which queries the write
pointer, and `when (tab)` disposes the tab — so **every entry to the Config tab
fired one probe**, unlimited. Five tab flips in a minute is one probe per 12 s,
which is the cadence measured to wedge the radio.

**What now prevents it:** rendering the gate uses the pointer the telemetry
loop already keeps at the link's tolerated rate, and reads nothing. The erase
*action* still probes fresh — one query at the moment of an irreversible act is
proportionate, and that was always the intent: the displayed list is a
courtesy, the one inside the action is the decision.

### 16.7 Cancellation was caught and re-reported as the device misbehaving

`kotlinx.coroutines.CancellationException` is an `Exception`, and the telemetry
loop caught it twice:

```kotlin
val read = try { readLock.withLock { c.pump(...) } }
catch (e: Exception) { ...; endStream("the read loop stopped: ..."); break }
```

`pump` holds a 250 ms read and the loop's only other suspension point is a
50 ms delay, so a cancel usually lands inside the `try`. `cycleLink()` cancels
telemetry on every §15 link rebuild. The result:

- `linkHealth.streamEnded` is set **after** `stopTelemetry` has reset it, so
  the Live tab reports a dead stream for the rest of a transfer that is
  recovering normally;
- the transcript gets `telemetry read threw` and `STREAM ENDED`;
- worst, a cancel landing in the probe branch logs *"write-pointer probe went
  unanswered; this logger's Bluetooth link often drops immediately after one"*.

That last line is **fabricated §15 evidence in the one diagnostic that survives
to the field**, and §15's conclusions are drawn by counting those events. A
tool that manufactures its own corroboration is worse than one that says
nothing.

**What now prevents it:** both catches rethrow `CancellationException` before
looking at anything else. Cancellation is not a device symptom and must never
be written down as one.

### 16.8 Smaller things fixed in the same pass

- `startDownload` and `startIncrementalDownload` returned without calling
  `onFinished` when not connected, so `DownloadService` never stopped: an
  ongoing, unswipeable notification holding the process up indefinitely.
- `writeTimeInterval` and `writeLogFormat` still had no busy check — the gap
  the `readLock` comment describes. The mutex keeps them from *corrupting* a
  transfer, but they still queue behind it and then pause logging unattended,
  hours after the tap, on a device that is being ridden. They now refuse while
  the logger is busy, like `setLogging` does.

### 16.9 How these were verified, and what verifying them caught

A test that passed before the fix and passes after it has demonstrated
*something*. It has not necessarily demonstrated that **this** fix is what makes
it pass. So each fix was reverted on its own and the suite re-run ten times,
looking for exactly one test to die.

The first run of that matrix was the useful one:

| fix reverted | tests failing, 10 runs |
|---|---|
| §16.1 `priorDamage` | 3, the same 3 every run |
| §16.3 ack subcommand | **none** |
| §16.3 `exchange` clear + drain | **none** |
| §16.3 `pump` not queuing | **none** |

**The three parts of §16.3 masked each other.** Any one of them alone was enough
to make the scenarios in those tests come out right, so removing a single part
broke nothing — and none of the three was actually verified by anything. The
suite was green for reasons unrelated to the code being correct.

Three tests were added, one per mechanism, each constructed so the other two
cannot rescue it:

- **subcommand matching** — the foreign ack arrives *during* the wait for the
  write's own ack, a window no amount of clearing covers. Only knowing what an
  ack is for closes it.
- **the queue clear** — two answers to the same question, identical in every
  field, so no predicate can separate them, and no `pump` anywhere. Modelled on
  the §15 shape: the device stalls, the client retries, and the logger flushes
  both answers in one burst, which a single `awaitSentence` ingests together.
- **`pump` not queuing** — stated as the property itself, through
  `awaitSentence` rather than `exchange`, because `exchange` clears the queue
  anyway and would hide it.

With those in place, every fix kills exactly one test, on all ten runs:

| fix reverted | test that fails | runs |
|---|---|---|
| §16.1 `priorDamage` | the three resume / repair / gate tests | 10/10 |
| §16.3 ack subcommand | `an ack that arrives mid-wait…` | 10/10 |
| §16.3 `exchange` clear + drain | `a duplicate parked during a stall…` | 10/10 |
| §16.3 `pump` not queuing | `a sentence seen by pump…` | 10/10 |
| §16.2 wedge branch | `a wedge during fetch-new is reported as a wedge` | 10/10 |
| §16.4 `continuedBy` | `a link cycle carries the full-re-read flag…` | 10/10 |

§16.4 needed a change before it could be verified at all. The merge lived inline
in `downloadWithLinkRecovery`, so the test had to transcribe it — which means
the test passed no matter what that method actually did. It is now
`FlashDownloader.Result.continuedBy`, a function over two results, and the test
calls the real rule rather than a copy of it.

#### On determinism

None of this is threaded. Every operation on `PmtkClient` is serialised by the
session's mutex, so §16.3 is a **sequencing** bug rather than a data race: a
sentence parked in a single-threaded queue and then handed to the wrong caller.
The fixtures keep it that way deliberately — time is counted in reads and fed
in through `PmtkClient`'s injectable clock, so the retry lands on the same read
every run and there is nothing left to be flaky about.

Measured rather than asserted: **240/240** passes idle, and **165/165** with
four cores of competing load. The two fixtures that do use the wall clock — the
download tests, where the block reader's idle timeout is real — were given
250 ms of headroom instead of 50 ms, because at 50 ms a garbage-collection
pause mid-block would truncate a healthy read and fail the test for a reason
that has nothing to do with what it checks.

#### The harness mistake, recorded because it nearly passed

The first matrix restored each mutation with `git checkout -- src/main`, which
restores from **HEAD** — and so quietly reverted the `continuedBy` extraction,
which was not yet committed. The tree then did not compile, and the failure
grep filtered out `> Task :app:compileDebugKotlin FAILED` along with the other
task lines. Two rows came back **empty, and empty read as "no failures"**.

A result that is empty because nothing ran looks exactly like a result that is
empty because everything passed, unless the harness is built to tell them
apart. It now checks for compile errors explicitly and restores from a file
snapshot rather than from the last commit. That is the same shape as everything
else in this section: **a measurement that cannot fail is not a measurement.**

### 16.10 Exercising the fixes on the hardware, and what the logger said back

Everything above was proven against a simulator. On 2026-09-15 at 23:33 the
fixed build was run against the logger itself over Bluetooth, for the narrow
purpose of settling §16.3's one remaining assumption — and it settled rather
more than that.

#### The ack shapes, now observed rather than assumed

§16.3 tightened ack matching from the command to the command **and**
subcommand. The captures in `data/` only ever proved the shape for subcommand
2 (queries) and 7 (log reads); the shapes for a config write, an enable and a
disable came from the simulator, which is to say from an assumption. Pausing
and then writing the interval back to its existing value exercises all three:

```
23:33:47.754 >> $PMTK182,5*20
23:33:47.987 << $PMTK001,182,5,3*22        disable
23:34:54.774 >> $PMTK182,1,3,100*26
23:34:54.857 << $PMTK001,182,1,3*26        config write
23:34:54.888 >> $PMTK182,4*21
23:34:54.992 << $PMTK001,182,4,3*23        enable
```

All three carry `<subcommand>,<flag>`, and all three were accepted by the
tightened predicate. Four of four observed write acks now have that shape, so
`mtk-android-prep.md`'s `PMTK001,182,6` for erase is very likely shorthand
written while erase was still unimplemented, rather than a different shape.

**Erase remains the one unobserved ack, and cannot be observed without
erasing.** Worth knowing precisely what happens if it really is three fields:
`Ack.from` takes the *last* field as the flag, so `PMTK001,182,6` parses as
flag 6, `isSuccess` is false, and the erase is reported as rejected — after
the flash has gone. That is true of the code before §16.3 as well as after, so
the tightening neither caused it nor cures it. A relaxation that accepts a
missing subcommand was considered and rejected: it would not help, because the
failure is in the flag parse and not in the match.

The same transcript also shows §16.3 itself, live on the wire:

```
23:34:55.023 >> $PMTK182,2,2*39
23:34:55.378 << $PMTK182,3,2,000A1C3F*62     the value
23:34:55.378 << $PMTK001,182,2,3*25          the ack nothing consumes
```

Note the order is **reversed** from the USB capture in
`data/usb_clean_2026-09-06.log`, where the ack precedes the value. Either way
the ack is left unconsumed, which is the supply §16.3 cuts off.

#### `PMTK182,5` does not stop this logger recording

The write pointer, sampled by the telemetry loop once a minute:

| time | pointer | advance | logging, as commanded |
|---|---|---|---|
| 23:32:26 | 190,960 | — | on |
| 23:33:26 | 191,248 | +288 | on |
| **23:34:26** | **191,568** | **+320** | **paused for 39 s of this minute** |
| 23:35:26 | 191,904 | +336 | restored |

The minute containing the pause advanced *more* than the undisturbed minute
before it. Had recording stopped when the disable was acknowledged, that minute
should have carried about 21 seconds of writing — roughly 96 bytes. It carried
320.

**The slide switch was physically confirmed in LOG**, three times, by the
person holding it. So this is §15.2 from the other side. That section recorded
NAV: the status word claims "logging" while nothing is written. This is LOG:
the status word claims "logging", the firmware's own disable is acknowledged
with success, and the device goes on writing anyway.

Put together, on this unit **`0x0102` is not a state, it is a constant.** The
switch wins in both directions, and the status word is incapable of reporting
either of them. Only the pointer is truth — which §15.2 already said, and which
this makes unconditional.

> **Corrected 2026-09-19 (§16.11).** The last clause holds — only the pointer
> confirms recording — but "`0x0102` is not a state, it is a constant" is false,
> and was false when written: `data/` already held readings of `0x0100`, and the
> word was later watched flipping `0x0100` -> `0x0102` on the wire the instant
> `PMTK182,4` was acknowledged. It tracks the enable flag. What this experiment
> actually showed is narrower: *a software pause does not move it.* Do not cite
> this paragraph for anything wider.

#### What that cost, and what now prevents it

The Config tab drew **one** of "Pause logging" and "Resume logging", chosen by
`status?.isLoggingEnabled != true`. Since the bit never clears, *"Resume
logging" could never be drawn on this hardware* — and that is the control
`withLoggingPaused`'s own failure message sends the user to:

> The logger is NOT recording — re-enabling it failed. **Use Resume logging on
> the Config tab**, or power-cycle the device.

The one escape from §12's failure was unreachable, behind a signal that cannot
move. It was only possible to re-enable logging during this exercise by writing
the log interval, because `withLoggingPaused` ends in `PMTK182,4` whatever the
status word thinks.

So: **both controls are drawn, always.** Enabling logging that is already
enabled costs one command and is harmless; being unable to enable it at all is
the failure this project exists to prevent. The status row now says in words
that it is the device's claim rather than evidence, and points at the Device
tab, where the indicator is computed from the write pointer.

The pause message changed for the same reason. "Logging paused" was a claim
about the device inferred from an acknowledgement; it now reports that the
command was accepted and says that the switch overrides it.

#### Still open

- **Whether a longer pause behaves differently.** The window measured here was
  39 seconds. A several-minute pause would rule out a buffering explanation
  beyond argument, at the cost of genuinely not recording for that long if the
  disable does work after all. Not run.
- ~~**Whether the status word ever moves on this unit**, in any switch position.
  Every reading in every capture to date is `0x0102` or `258`.~~ **Answered, and
  this was simply wrong when written** — `data/` held four readings of `256` at
  the time. See §16.11.
- The erase ack, as above, and for the same reason as always: the only way to
  read it is to destroy the flash.

### 16.11 The status word does move, and what it tracks is the enable flag

Found 2026-09-19, from a user report: the last two connects both showed **"NOT
logging"**, a reading §16.10 had just finished arguing this unit cannot
produce.

This section was written twice in one session. The first version got the
mechanism wrong, and the wrong version is kept below the right one because the
way it went wrong is the more useful lesson.

#### §16.10 was wrong about this, twice

> **Still open** — Whether the status word ever moves on this unit, in any
> switch position. Every reading in every capture to date is `0x0102` or `258`.

and, in the body:

> on this unit **`0x0102` is not a state, it is a constant.**

Both false, and the refutation was in the repository when they were written:

```
$ grep -rhoE "PMTK182,3,7,[0-9]+" data/*.log | sort | uniq -c
      5 PMTK182,3,7,258
      4 PMTK182,3,7,256
```

Two of those four `256` are distinct events —
`bt-probe-teardown-transcript-2026-09-07.log` and
`bt-probe-fix-verified-2026-09-07.log` are two captures of one session and
share their readings to the millisecond — so the honest count is two `0x0100`
against four `0x0102`. Two is not zero. §16.10 generalised a 39-second
experiment to "in any switch position" without grepping for even that.

#### What the word tracks: the enable flag

Read off the phone, live, 2026-09-19 — `files/logs/transcript.log`, the connect
at 13:17:30:

```
13:17:38.379 << $GPRMC,181738.125,V,,,,,0.00,0.00,030207,,,N     no fix
13:17:38.608 << $PMTK182,3,7,256                                 0x0100
13:17:38.849 << $PMTK182,3,8,0004F590                            pointer

13:17:47.373 << $PMTK001,182,4,3        <- Resume logging pressed
13:17:47.372 << $GPRMC,181747.125,V,... still no fix
13:17:47.697 << $PMTK182,3,7,258                                 0x0102
```

**The word flipped with no change in fix state whatsoever.** What changed was
`PMTK182,4`. And the pointer then shows the other half of it:

| time | pointer | advance | fix |
|---|---|---|---|
| 13:17:38 | `0004F590` | — | no |
| 13:17:59 | `0004F5B0` | +32 | no — the two state-change markers, nothing else |
| 13:18–13:22 | `0004F5B0` | **0** | no |
| 13:23:00 | `0004F650` | +160 | fix arrives |
| 13:24:00 → | +288/min | | 6 records/min = the configured 10 s |

So an armed logger with no fix reports **`0x0102`** and writes nothing. Only a
disabled one reports `0x0100`. The bit follows the enable flag, exactly as §13
always said, and the user's logger had genuinely been switched off across at
least two connects.

#### The wrong version, and why it was wrong

The first pass concluded *"what it tracks is the fix"*, from this:

| Capture | GPRMC | Status |
|---|---|---|
| `bt-probe-teardown-…-09-07.log:22` | void | **256** |
| `…:73` | void | **256** |
| `…:360` | active | 258 |
| `bt-probe-fix-verified-…-09-07.log:856` | active | 258 |
| `bt-full-download-…-09-08.log:1621` | active | 258 |
| `usb_clean_2026-09-06.log:28` | active | 258 |

Six readings, perfect correlation, and completely spurious. The 2026-09-07
transcript contains **no `PMTK182,4`** — the logger was disabled at 22:51, and
by 22:52 it had re-enabled itself. §13 records that behaviour explicitly and it
was there to be read: *"in 18 months the device auto-resumed after every single
stop — 417 status changes, 167 of them bracketing multi-hour dead intervals,
every one answered by an ENABLE."* The fix arriving and the auto-resume
happened in the same 85-second window, and the correlation picked the wrong one.

It is the same error as §16.10's, committed while correcting §16.10: a small
sample, a clean-looking pattern, and a conclusion stated more widely than the
evidence carried. The difference is only that this one was caught, by reading
the device instead of the archive — which is the actual rule. **`data/` is
history. The phone is state.**

The cost was not zero. The wrong version shipped UI copy telling the user that
"NOT logging" on an indoor connect was "most likely a logger waiting for the
sky rather than a fault" — advice to ignore precisely the reading that meant
their logger was off. It is reverted.

#### The bits nobody was reading

`PMTK182,3,7` carries more than bit 1, and §13 decoded all of it during the
outage:

> Traxi could not have reported `need_format` or a failing flash, because it
> never asks.

It asks. Every connect sends `PMTK182,2,7`. `need_format` (`0x0400`) and
`memory_full` (`0x0800`) came back in every answer and were dropped by a
decoder that read one bit. `0x0504` — a logger that will not record again until
it is formatted *and* power-cycled — rendered as `NOT logging (0x0504)`, same
grey row, same words as an ordinary connect.

`LogStatus` now decodes the documented bits and exposes `faults` for the two
that mean *recording is over until someone intervenes*. Drawn in error colour
on both tabs, and without waiting for a probe, because the device is asserting
a condition about itself rather than us inferring one.

#### What the app now does with `0x0100`

Says so, loudly, at connect. No probe, no fix required, no waiting.

This was declined in the first pass on the crying-wolf argument, which the
fix-correlation made look sound. With that gone the asymmetry is plain:

- **believe `0x0100`.** It is a real, persisted state. It survives power cycles
  (§12), the device does not reliably clear it, and the only cost of being
  wrong is one harmless button press — §16.10 established that enabling an
  already-enabled logger is a no-op.
- **never believe `0x0102`.** NAV reports it over a frozen pointer (§15.2), a
  software pause does not move it (§16.10), and it is what an armed logger with
  no fix reports. Confirming recording still needs the write pointer.

#### Still open

- **What disabled it.** There is no `PMTK182,5` in either transcript
  generation — 48,149 lines, 2026-09-15 23:36 through 2026-09-19 13:40. So
  nothing this app did in that window turned it off, and the event is older
  than the surviving record.
- **The transcript is drowning in NMEA and losing exactly this evidence.** Of
  22,023 lines in `transcript.log`, 21,621 are `GPGGA`/`GPGSV`/`GPGSA`/`GPRMC`
  — 98.2%. Two 2 MB generations hold **one** connect between them. The file
  that exists to answer "what happened last time" cannot reach back to last
  time. Rotating on non-NMEA lines, or keeping a separate connect-events log,
  would have answered the question this section had to leave open.
- Whether `0x0200` (*device is in disable status*) ever appears. Still never
  observed, which is why `0x0100` cannot distinguish a software disable from
  the NAV switch.


### 16.12 Assurance that it is logging, measured across the ride rather than the connect

Asked for directly, 2026-09-19: *"I only need to have assurance that when I use
this logger, it's logging."*

Every recording signal this project had built answers a different question.

| Signal | Answers | Covers |
|---|---|---|
| status word | whether logging is enabled | the instant; trustworthy only when it says *no* (§16.11) |
| write-pointer probe | did bytes land in the last minute | only while connected |
| `RecordingAudit` | did logging stop mid-dump | only after a full fetch |

The probe is ground truth and it is genuinely good, but it reports on *the
minutes the app was watching* — and the phone spends the ride in a pack with
the link shut down, because asking is what kills the link (§15). The one part
of the day that matters is precisely the part nothing could see.

#### The pointer is a counter the logger keeps for itself

It does not need the app to be connected, or awake, or installed. Note it
before setting off, read it again afterwards, and the difference is the entire
trip — every hour of it, including the ones with Bluetooth off.

That reading already happens: `openWith` queries the write pointer on every
connect. The whole feature is *not throwing the previous one away*. Before
this, `recordingBaseline` was nulled in `stopTelemetry`, so the app could only
ever describe the session it was in.

So: `RecordingMarkStore` persists one `(pointer, timestamp, deviceKey)` across
process death, and `RecordingSince.compare` turns two of them into a sentence.

```
Since the app last looked
1,798 fixes recorded
4 h 59 m of recording, 87.3 KB, in the 4 h 48 m since the app last read
the pointer.
```

Cost: zero extra queries.

#### What it refuses to conclude

**Coverage is not a health metric, and is not shown as one.** The gap counts
every hour since the last connect, including the ones the logger spent in a
drawer. Connect on Friday evening, ride Saturday, connect Saturday night: the
gap is 26 hours and 5 of them were recorded, and that is a *perfectly healthy
logger*. A percentage there would be a fabricated verdict. `Verdict.Wrote`
carries `coverage` for callers that have a reason to want it, and the UI shows
the two durations side by side instead, because only the user knows how long
the thing was switched on.

**"Nothing was written" is not drawn in error colour**, which is a deliberate
reversal of the first draft. A frozen pointer is exactly correct for a logger
that was switched off, and connecting twice in one evening would light it red
every time. It is stated prominently, with the benign reading first and the
serious one second, and the user applies the fact only they have. This is the
same rule as §15.2's `hasFix` guard and §16.11's refusal to alarm on `0x0100`:
the indicator that must never cry wolf cannot also be the one that guesses.

**A pointer the device did not answer leaves the stored mark alone.** This is
the destructive case and it has its own test. Writing "unknown" would close the
open interval without measuring it — so a link that misbehaves at the trailhead
would destroy the baseline the post-ride connect needs, at exactly the moment
it is hardest to notice. Leaving the mark stale is right: the gap stays open
and the next good reading spans all of it. Simulated sessions store nothing at
all, for the same class of reason.

**A pointer that went backwards is `Unusable`, never `WroteNothing`.** In
overlap mode a wrap means the logger wrote *more than the whole chip*, and an
erase means nothing can be concluded; the two are indistinguishable from two
samples. Reporting either as "nothing was recorded" would be the worst false
alarm the app is capable of, and it is one subtraction away at all times.

#### Where the marks are moved forward

At connect, and at every answered probe. Without the second, a session left
open on the handlebars for an hour before setting off would put that hour
inside the ride's window and dilute the answer.

#### Not covered

- **The app-side persistence has no unit test.** `RecordingMarkStore` wraps
  `SharedPreferences`, the `:app` module has no Robolectric, and the build runs
  offline so one could not be added in this pass. The rule worth testing was
  therefore moved *out* of the store and into `RecordingSince.markFor`, which is
  pure and has three tests; what remains untested is the `SharedPreferences`
  round-trip itself — a mark that failed to persist would present as
  `NothingToCompare` forever, which is visible in the UI rather than silent, but
  nothing asserts it.
- **None of this has run against the hardware.** It is arithmetic over two
  numbers and the arithmetic is tested, but the first real answer will come from
  a ride, and the number to sanity-check is `fixes` against what the subsequent
  dump actually parses.
- The record-size estimate assumes every record is the configured format's
  width. A format change mid-gap would skew `fixes`; the device writes a
  `CHANGE_FORMAT` marker when that happens and nothing here reads it.


### 16.13 Proving it on the trail: the mark button

Asked for directly, 2026-09-19, after §16.12: *"I really need to know that when
I switch my unit on, it's logging. How can we prove that it is? Visibly and on
trail."*

#### What cannot answer it

**The device has no *passive* local recording indicator.** §13 settled that by
watching it: steady LED blink, 3D DGPS fix on 7–8 satellites, HDOP 1.14, **zero
bytes written**. The LED means fix. (The mark button's own beep is a separate
question and possibly a better one — see below.) (§12 says the user first noticed the outage
"because an LED was not blinking" — the two observations sit in tension and
§13's is the deliberate one. Worth re-checking on the hardware, because a
genuine log LED would beat everything below.)

**A status-poll watchdog does not answer it either**, which §13.3 proposed
before §15 was understood. `PMTK182,2,7` is a request like any other, and §15's
finding is about requests, not about that one field: *"something is consumed per
request and not fully released"*, latency climbing 132 ms → 1142 ms → dead. At a
minute's spacing it survives — today's session probed 23 times without incident
— but at that rate a poll adds nothing the existing pointer probe does not
already do better, since the pointer proves writing and the status word only
proves the enable flag.

#### What does: make it record something

`RCR` — reason for recording — has a `BUTTON` value, and the reference dump
holds **1,419 records carrying it**. The logger has a physical mark button, and
pressing it writes a record *immediately* rather than at the next interval tick.

So: read the write pointer, press the button, read it again.

An advance of one record proves the whole chain in one move:

| Established | Otherwise needs |
|---|---|
| the slide switch is on LOG | nothing in software can read it (§15.2) |
| logging is enabled in firmware | the status word, trustworthy only when it says no (§16.11) |
| the receiver has a position | the NMEA stream |
| bytes are reaching flash | a probe, one log interval later |

Nothing else in this project establishes all four, and nothing else establishes
any of them in two seconds. It is also *causal* rather than observational —
the user does a physical thing to their own device and is shown the
consequence, which is the only kind of assurance that survives being
disbelieved.

#### Two queries, both behind taps

The obvious implementation polls the pointer until it sees the press land. That
is §15's denial of service rewritten from scratch. The check is therefore
user-paced: one query to take the baseline, a prompt, and one query when the
user says they have pressed it. Standing still at a trailhead is the one place
in this project's life where spending a query is unambiguously affordable, and
where a wedged link costs nothing.

#### The distinction the verdict must hold

`MarkProof` returns three outcomes, and the whole file exists to keep two of
them apart:

- **`Proven`** — the pointer advanced.
- **`NothingWritten`** — the pointer did not move **and the receiver had a
  fix**. The user asked the device to record a point, it had a point to record,
  and nothing landed. This is the one place the app says *do not set off on
  this*.
- **`Inconclusive`** — the pointer did not move and there was no fix, or a
  query went unanswered, or the log wrapped mid-check. Indoors this logger
  holds no fix for hours and correctly writes nothing; reporting that as a
  failed proof would condemn a healthy device for being under a roof, which is
  the false alarm every indicator in this app is built to refuse.

The two frozen-pointer cases are the same arithmetic. `J-proof-needs-fix` in
`tools/mutation-check.py` removes the `hadFix` guard, to keep a test standing
between them.

#### And on the lock screen

`LinkService`'s foreground notification said "Connected to BT-Q1000XT" — the
reassuring half of the sentence, and the half never in doubt. It now carries
"Recording confirmed 2 min ago", which is the part worth seeing without
stopping, unlocking and navigating.

It reports **strictly less** than the Device tab: only whether a probe has ever
confirmed a write, and how long ago. The tab's four-way verdict depends on fix
recency and on whether the window was long enough to judge, and re-deriving
that in a service would be the same logic in two places with nothing holding
them together. Weaker cannot contradict.

#### The beep, and how to find out what it is worth

**The mark button does give local feedback** — a beep and/or an LED flash,
confirmed by the person holding the unit, 2026-09-19. That is the only thing in
this entire problem that works with no phone at all, so what it actually
signifies is worth establishing precisely rather than assuming.

Two possibilities, and they are not close:

- the beep fires when a **record lands** → it is a complete no-phone recording
  check, and the trail problem is solved outright;
- the beep fires when the **button is pressed** → it is worth nothing for this
  purpose, and would be actively dangerous to rely on. §13's LED is the
  precedent: it blinks steadily on a good fix while zero bytes are written.

**The proof ceremony settles it for free, and a failed run settles it fastest.**
Any run where the pointer does not move is a controlled experiment: if the
logger beeped anyway, the beep is acknowledging the press and nothing more. So
the deliberate version is to run the check twice — once normally, once with
logging paused (or the switch in NAV) — and compare. Two minutes, at a bench,
once in the device's life.

The UI now says this at each outcome rather than leaving it to be remembered:
the `Proven` card notes that one run has tied the beep to a real write *once*
and names the run that would confirm the implication; the `NothingWritten` card
says outright, in error colour, that a beep over a failed write means the beep
is not a recording check.

Until that experiment is run, **the beep is unvalidated and the app does not
tell the user to trust it.**

#### Still open

- **The beep experiment itself.** Described above, not performed.
- **Whether a button press with no fix writes anything at all.** Assumed not,
  and the verdict treats that case as inconclusive either way, so the assumption
  is not load-bearing. Worth one measurement indoors.
- The check has not been run against the hardware. The arithmetic is tested;
  the ceremony is not.


### 16.14 The status word, third time: §15.2 was right and I should have left it alone

2026-09-21, from a user report of the app contradicting itself on screen — the
Device tab showing **LOGGING IS SWITCHED OFF** in red while also reporting
bytes written. Both were true. The alarm was mine, added in §16.11, and it was
wrong.

#### The reading that settles it

```
07:19:31.516 << $GPRMC,121931.000,V        no fix
07:19:31.650 << $PMTK182,3,7,256           0x0100
07:19:31.920 << $PMTK182,3,8,0005D2F0
07:19:32.557 << $GPRMC,121932.000,A        fix arrives, one second later
07:19:52     << $PMTK182,3,8,0005D390      +160  (a 16-byte marker + 3 records)
07:20:52     << $PMTK182,3,8,0005D4B0      +288  full rate
07:21:52     << $PMTK182,3,8,0005D5D0      +288
```

**No host command anywhere in that sequence.** Nothing was disabled; the word
went clear because the receiver had nothing to log, and came back on its own.

And with the fix state lined up against every `0x0100` this project has ever
recorded, the pattern is total:

| When | Status | GPRMC |
|---|---|---|
| 2026-09-07 22:50:57 | 256 | `V` |
| 2026-09-07 22:51:22 | 256 | `V` |
| 2026-09-19 13:17:38 | 256 | `V` |
| 2026-09-19 21:38:23 | 256 | `V` |
| 2026-09-21 07:19:31 | 256 | `V` |

Five for five, and every `0x0102` taken with a fix held.

#### What made §16.11 overturn it, and why that was not enough

One observation: at 13:17:47 on 2026-09-19, `PMTK182,4` was acknowledged and
the word went `256` -> `258` with GPRMC void on both sides. That is real, and
it does show the word responds to an explicit enable. What it does **not**
license is the converse — reading a bare `0x0100` as "disabled" — and §16.11
took exactly that step.

Worse, the 21:38 connect on the 19th, which looked like the second confirmed
disarm, does not survive its own timeline either:

```
21:38:23  status 256, GPRMC V, pointer 0x00057650
21:38:44  pointer 0x00057650        frozen -- no fix, nothing to write
21:39:02  GPRMC A                   fix arrives
21:39:30  PMTK182,4 pressed         28 seconds too late to prove anything
```

The fix landed half a minute before the Resume press. The device was very
probably about to record on its own and the press confounded it. That case was
counted as evidence for a fault; it is evidence of nothing.

#### The cost

The unguarded alarm shipped on 2026-09-19 and produced a false alarm on real
hardware within two days, on a logger that was writing at full rate twenty
seconds later. That is precisely the failure the first pass declined to risk
and then talked itself into:

> **No alarm was added for a clear logging bit**, and that is deliberate. It is
> the single most common reading on a connect, it is benign in that context,
> and a red banner on every indoor connect is how the one indicator that must
> never cry wolf stops being believed. *(§16.11, first version — correct, and
> then discarded.)*

It is now guarded by the same condition the NAV warning uses: reported off,
**and** a fix present, **and** a conclusive window, **and** the pointer frozen.
Under that guard it cannot fire on a logger that is writing.

#### And the user's original report may have been this all along

The complaint that opened all of this was *"the last two times I've connected,
it's in 'not logged' mode."* On the build they were running, that is the Config
tab's Status row, rendered straight from `PMTK182,3,7`. Read in the first
second of a connect — which is when it is always read, and always before the
receiver has reacquired — it says `NOT logging` on a perfectly healthy device,
every single time. *Reliably*, which is the word the user used.

No fault has been demonstrated on this device since the 2026-09-06 recovery.
The 32-hour gap with 96 bytes written, the stop at ~15:15 on the 19th — both
are a logger that was switched off, which is what a logger in a drawer does.

#### The row is gone from the live card, which is what should have happened first

The guard above stops the alarm. It does nothing about the row, and the user
said so immediately: *"that logging line never told me when at all. It reads,
like anything else without a time attached, like a live status report."*

Exactly right, and the first repair — stamping it with an age — was the wrong
instinct. **An undated line is not ambiguous about time; it asserts the present
tense.** Annotating a false claim is not the same as withdrawing it, and once
dated the value does not earn its place anyway: this section's own conclusion is
that the word can neither condemn a logger nor clear one, so on the card whose
entire job is that verdict it can only compete with the reading that can.

So the raw bit is no longer drawn on the Device tab. It stays on the Config tab,
dated, where it is a settings readout on the screen used to change settings.
What remains on the live card is the part that is actionable and does not
flicker with fix state: the standing faults, and the NAV warning, both of which
require a measurement before they say anything.

Worth noticing as a pattern rather than three separate mistakes: every pass at
this bit **added** something — decode more of it, alarm on it, date it. None
asked whether it belonged on that screen. The fix was subtraction, and it was
available from the first pass.

#### The device was never the odd one here

Bit 1 is documented as *auto_log by criteria ON/OFF*, and every reading in this
record fits it meaning **"the logging criteria are currently being applied"** —
which requires a fix. No fix, no criteria, bit clear; fix and armed, bit set; an
explicit `PMTK182,4` sets it directly; NAV claims it while the switch blocks the
write. That is coherent firmware behaviour and it never varied. All three
reversals above were about how the app read and presented it.

#### The rule this keeps proving

Three passes on one bit, each one a confident conclusion from a handful of
readings, each one wrong in a different direction, and the position the project
held before any of them — §15.2's *"`0x0100` is ambiguous and always will be"*
— correct throughout.

The discriminator was never in the word. It is the write pointer paired with
the fix state, which is what the Device tab computed all along and what every
one of these detours came back to.

#### Still open

- The flash journal still has not been read; the 2026-09-06 recovery's
  correctness rests on inference. `tools/logstops.py` is ready and validated
  and needs ~217 KB off the device.
- `RECORD_METHOD` (field 6) is still never queried by the app, and a format
  leaves it on STOP. Unrelated to this, still a silent-loss trap.


### 16.15 The flash journal, finally read — and what a fetch costs while it runs

2026-09-24. The 217 KB that had never come off the device since 15 September
were fetched over Bluetooth, with the phone touching the logger this time. It
completed cleanly and the journal answers, in one pass, every question §12
through §16.14 kept deferring.

#### The fetch

```
resuming at 0x00020000, frontier 0x00028180
blocks 0x20000 … 0x70000, each 65536/65536, 32 chunks, ~165 s, 0.4 KB/s
stopping: 2 consecutive unwritten blocks at 0x00080000
524,288 bytes, last real byte 0x05F300
```

No holes, no retries, no link cycles. The contrast with 19 September — three
attempts at block `0x00000000`, `0/65536` every time, then an RFCOMM rebuild
that failed outright — is entirely down to range. Same command, same device.
**Ask where the hardware is before blaming the firmware.**

#### The verdict

```
7,952 fixes · 174 log-status markers · 83 disable / 91 enable
unanswered stops:    0
checksum failures:   0
bad sector headers:  0
```

**Zero unanswered stops.** The §12 fingerprint — a `DISABLE` that nothing
answers before the next one — does not appear anywhere in the device's own
record since the 2026-09-06 recovery. The recovery took. There was never a
second silent failure to find, and §16.14's suspicion that the whole affair was
a display artefact is now supported by the flash rather than by argument.

Recording quality since the 16th, measured against the configured 10 s:

| Session (UTC) | Minutes | Fixes | Density | Worst inner gap |
|---|---|---|---|---|
| 09-16 03:02 → 12:13 | 551 | 3,305 | 100.0% | 25 s |
| 09-19 18:22 → 18:40 | 18 | 111 | 101.6% | 10 s |
| 09-19 19:50 → 21:23 | 93 | 559 | 100.1% | 13 s |
| 09-20 02:39 → 04:01 | 82 | 491 | 99.5% | 45 s |
| 09-21 12:19 → 12:39 | 20 | 120 | 100.8% | 10 s |

A flawless nine-hour overnight on the 16th. The ragged sessions in the image —
densities of 20% to 86%, inner gaps up to 935 s — are all 6 to 9 September, the
recovery and bench-testing days, and are a receiver indoors rather than a logger
at fault.

The interval has been 10 s throughout: `CHANGE_PERIOD 50` then `100` on
2026-09-06, and `100` again at 04:34:48 UTC on the 16th, which is §16.10's
interval write appearing in flash at exactly the second the transcript records
it. Two independent records of the same event agreeing is the strongest
validation the parser has had.

#### A Bluetooth fetch suspends logging while it runs

The new finding, and the expensive one. Fix timestamps across the start of the
download:

```
01:47:02 … 01:53:32   a fix every 10 s, 39 consecutive, metronomic
01:53:37              first block read issued
01:56:21   +169 s
01:59:03   +162 s
02:01:45   +162 s
02:04:32   +167 s
```

Those intervals are the block read durations. **One fix per block instead of one
per ten seconds** — 4 fixes where 114 were due, a 96% loss across the 19 minutes
the fetch ran. The device writes it down itself: a `DISABLE`/`ENABLE` pair, 16
bytes apart with nothing between, at 01:53:32, 01:56:21 and 01:59:03 — the
logger standing its write engine down for each flash read and bringing it back
between blocks.

This was not known, and it changes the transport calculus well beyond patience:

- a full 5.4 MB image over Bluetooth is 3.5 hours (§11) and now also costs
  **essentially all recording for those 3.5 hours**;
- USB does the same job in 95 seconds, so it costs about 95 seconds of logging;
- an incremental fetch is cheap in both senses, which is a second reason to keep
  the frontier close rather than letting a fetch grow into a full re-read.

None of this is a fault. It is a single-threaded flash controller doing the only
thing it can with one bus. But a fetch is no longer free, and **a fetch started
mid-ride is data loss**, which nothing in the app currently says.

#### Still open

- **The app does not warn that fetching costs recording.** The Dumps tab offers
  "Fetch new" with no indication that the logger stops writing while it works.
  That is exactly the shape §12 was about: a deliberate action with a silent
  cost. It should say so, and say roughly how much.
- `RECORD_METHOD` (field 6) is **still never queried**, and a format leaves it on
  STOP. It is not in the flash image either, so this dump could not settle it.
  One query. Last untested silent-loss path.

### 16.16 The download estimate pays off a debt it took on before it started

Asked while the fetch above was running: why does the time-left figure open very
high and fall for the whole run?

`progressBps` is a whole-run average — `advanced / elapsed` — chosen over an EMA
so a burst at a sector boundary cannot talk the estimate down (`932b1fd`). An
average carries its history, so the estimate can only approach the truth from
above, by amortisation. That much is by design.

What is not by design: **`etaStartNanos` is armed at the first chunk of the wrap
probe, and the probe's file position is deliberately pinned.** The transfer
therefore opens with 165 seconds on the clock and zero bytes against them, and
spends the rest of the run paying that off.

| Block | Elapsed | Avg rate | Shown | True | Factor |
|---|---|---|---|---|---|
| 0x20000 | 325.7 s | 201 B/s | 27.1 min | 13.5 min | 2.01× |
| 0x30000 | 489.0 s | 268 B/s | 16.3 min | 10.8 min | 1.51× |
| 0x40000 | 652.1 s | 302 B/s | 10.9 min | 8.1 min | 1.34× |
| 0x50000 | 815.1 s | 322 B/s | 6.8 min | 5.4 min | 1.26× |

The rate column climbs 201 → 322 B/s while every block sustains about 405 B/s,
within 2% of every other. It is not measuring the radio.

**This is `d070013` one scope out.** That commit — *"Do not time the first chunk
against the starting gun"* — fixed the same shape inside a block, where the
request round trip and the flash read produced a rate an order of magnitude low.
The wrap probe reintroduced it around the whole transfer. Re-arming
`etaStartNanos`/`etaStartBytes` at `"resuming download at …"` would have shown
13.4 min against a truth of 13.5, on the first figure rather than the last.

One thing that looked like a second cause is not: the `+ 2` blocks in
`etaPointerTargetBytes` and `unwrittenSectorsToStop = 2` are the same two
blocks. The read stopped at `0x080000`, byte for byte the target the estimate was
aiming at. It is an exact prediction, not padding, and it leaves the estimate
free to count honestly to zero. *(An earlier write-up of this called it padding
and a source of overshoot; watching the fetch finish disproved that.)*

**Fixed.** The arithmetic moved out of `SessionController` into
`TransferEstimate`, which is pure and now has eight tests, because this is the
second time it has been wrong in the same way and neither time could a test
notice. `rebase` moves the baseline forward for as long as nothing has been
appended, and freezes it at the first real byte — so an ordinary mid-transfer
stall is still charged to the rate, which is correct: a link that goes quiet for
a minute really has put the finish a minute further away. `K-eta-rebase` in
`tools/mutation-check.py` restores the frozen baseline, to keep a test standing
between the two.


### 16.17 The setting nobody could see

`RECORD_METHOD` — config field 6, what the logger does when the flash fills —
has been in `Pmtk.ConfigField` since the enum was written and **queried by
nothing, ever**. Through §12, §13 and every section since, the app has connected
to this device hundreds of times without once asking.

It matters because §13 recorded, from observation rather than documentation,
that a format resets it:

| Field | After a format |
|---|---|
| `RCD METHOD` | **2 = STP** (was OVP) — restore with `$PMTK182,1,6,1` |

So the device that went through the 2026-09-06 SPI-level recovery may have been
sitting in STOP ever since, and nothing in this project could have shown it. In
STOP mode a full chip is the end of recording until someone intervenes. Against
21 days of capacity at a 10 s interval and a four-to-six month trail, that is
§12 with a longer fuse: it looks deliberate, it makes no sound, and it is
discovered when the data does not exist.

`data/dump_v2.log` prints `Recording method on memory full: (1) OVERLAP` from a
2026-09-04 mtkbabel run — but that is *before* the format, so it settles
nothing about the state since.

#### What was added

`Pmtk.RecordMethod` decodes the two values, `PmtkClient.queryRecordMethod`
reads them, and the Config tab draws the setting as its own card with both
writes offered explicitly. Three decisions worth keeping:

- **An unreadable value parses to null, never to OVERLAP.** Defaulting would
  invent a reassuring answer about the one setting whose entire hazard is that
  it ends recording quietly. `L-record-method-guess` keeps a test on that.
- **A dropped reply on refresh keeps the last known value**, rather than
  replacing a real reading with an absence and flickering the warning off.
- **Neither mode is applied automatically.** STOP is almost certainly wrong for
  this user, and the app still does not write it for them: quietly correcting
  device configuration on connect is precisely how §12 happened, and "connecting
  never writes" is an invariant with a test behind it.

STOP is drawn as a standing fault in error colour, with what it costs, because
on a logger meant to run for months it is not a preference — it is the recording
ending on a date nobody chose.

#### Still open

- **What it is actually set to on this device.** The card will say the moment
  the app next connects. The dump cannot answer it; this setting is not in the
  flash image.

### 16.18 The connect budget, enforced instead of remembered

§16.17 added a seventh query to `openWith` to read the record method. It was
described in a summary as "queried at connect" and never raised as what it
actually was: a request added to the path that runs before every ride, against
the section that says requests are what this logger's radio runs out of. The
session it shipped in lost its link twenty-five seconds after connecting. One
sample settles nothing about that particular drop — the same failure is in the
log from 2026-09-16, on a build predating all of this — but the request was not
worth making, and it is gone.

The part worth keeping is why nothing objected. The existing safety rail (prep
doc §8, "connecting never writes") **hand-rolls its own query list** and has
never read `openWith`. It could not have noticed a query being added, only a
write.

`ConnectBudgetTest` reads the real function. It pins the six requests a connect
may make, names what each one earns, and fails the build with the reason when
the set changes. Verified by putting the seventh query back: it fails, naming
it. It is a source-level check because `openWith` needs a `Context` and this
module has no Robolectric, and a guard that cannot run is not a guard.

The record method is now read on demand from the Config tab — one request,
behind a tap, on a screen someone is looking at. It only ever changes when this
app changes it, so there was never a reason to ask on every connect.

**And it answered: `PMTK182,3,6,1` — OVERLAP.** The STOP trap §16.17 was built
to catch is not live on this device and may never have been.

> The lesson is not "be careful with queries". That was already written down,
> three times, in sections finished the same evening. It is that a rule which
> depends on the person editing remembering it is not a rule, and the fix is a
> test that says the reason out loud.

### The rule

Three, and they are all the same rule seen from different angles.

**A property of the data must be stored with the data.** Damage was a property
of a function call, so it evaporated at the end of the call. Freshness was a
property of nothing at all, so a five-minute-old answer and a current one were
the same object.

**A protection is only as good as the thing that can bypass it.** The probe
interval, the erase gate, the §12 restore guarantee — each was built carefully
and then reached around by a code path that did not know it existed.

**A simulator that is kinder than the device hides the bugs worth finding.**
Twice now: the 512-byte read in §15.4, and the query ack here. The simulator's
job is to be *accurate*, and where it is not, it should be treated as a known
gap rather than as a pass.
