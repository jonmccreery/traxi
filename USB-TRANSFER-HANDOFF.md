# Handoff: finishing the USB transfer problem

Written 2026-09-06 at the end of a long session. Everything here is measured,
not inferred, unless explicitly marked as a hypothesis.

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
> **If you read one thing here, read §4.1** — why the same defect survived a
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
BT    493 B/s         full read ~3 hours (internal 9600-baud UART bridge)
```

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
cd /home/taxi/btdroid
$GRADLE :core:test              # 69 tests, no device needed
$GRADLE :app:assembleDebug
```

### Phone

```
Samsung SM-G990U1, Android 16 / API 36
package: ai.moonlite.btdroid.debug
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
$ADB exec-out run-as ai.moonlite.btdroid.debug \
    cat cache/exports/btdroid-transcript.txt > transcript.txt
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
$ADB shell dumpsys activity services ai.moonlite.btdroid.debug | grep -c DownloadService
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
- **69 tests**, none requiring hardware.

Erase (prep doc §0.2) is designed and gated but **not built** — that is the
other outstanding piece of work, and it is the one the whole app exists for.
