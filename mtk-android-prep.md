# Prep: Android app for the MTK GPS logger

Working document for the btdroid project. Goal is to replace the vendor Android
app that stopped working roughly a decade ago, for a MediaTek-chipset GPS data
logger.

**Revision 2 (2026-09-05).** Section 1 was rewritten against the raw flash rather
than the exported GPX; three claims in revision 1 did not survive and are called
out in §1.4. Section 2's open questions are now answered. Project decisions
recorded in §0.

---

## 0. Project decisions

**The phone is the only computer.** This is the motivating constraint, not a
detail. On trail there is no laptop to fall back on, which means:

- Every operation must be resumable. A dump that dies at 60% must restart from
  60%, not from zero.
- The app must be useful with the logger absent. Re-parsing a partial `.bin`
  already on disk is a **user-facing feature**, not just a test seam — see
  `FileReplay` in §6.
- Diagnostics have to be readable on the device. The PMTK transcript log (§8) is
  the only debugging tool available at a trailhead.
- No step may depend on a desktop to complete.

| Decision | Value | Rationale |
|---|---|---|
| Transport, v1 | **Bluetooth SPP** | Chosen despite being the harder path; USB is a later addition, not a prerequisite |
| Transport, v2 | USB serial | Device has a USB port; `usb-serial-for-android` behind the same `Transport` interface |
| v1 scope | Dump + parse + view + export + **config writes** | Includes doc phase 5 |
| Erase | **Out of scope** | Deferred indefinitely; see §8 |
| minSdk | **31** (Android 12) | Target phone is API 36; no legacy permission branch needed |
| Language | Kotlin + Compose | |

Config writes are in v1 for a specific reason beyond convenience: the current log
format omits `NSAT`/`HDOP`/`VDOP`, and setting the format register is the only
way to obtain them. See §1.3.

---

## 1. What the device actually told us

### 1.1 Verified against the raw flash

Measured by parsing `data/cdt_v2.bin` (5,376,000 bytes) directly, not the GPX
export. These are load-bearing enough to use as parser assertions.

| Property | Observed | Implication |
|---|---|---|
| Fixes | **126,613** | Golden-file count; parser must reproduce exactly |
| Checksum failures | **0** across all 5.4 MB | Flash is entirely healthy; any failure in a new parser is a parser bug, not bad flash |
| Sectors with data | 82 (+1 unwritten) | |
| Span | 2025-03-16 → 2026-09-04 | 18 months |
| Sample interval | 20 s, median and p95 identical | Time-based criterion, 200 in 0.1 s units |
| `FMT_REG` | `0x000A003F`, **identical in all 82 sectors** | Never changed mid-log |
| Log criteria | time=200, distance=0, speed=0, in every sector | Pure time-based logging |
| Record size | 42 bytes (40 payload + `*` + checksum) | Matches device's own report |
| Timestamps | UTC epoch seconds, **+1024 week rollover correction required** | See §1.5 |
| Segments | 79 at a 300 s gap threshold | Cause identified — see §1.4 |
| Elevation quantization | 0.10 m | Fix engine emits decimetres; `HEIGHT` is a 4-byte float |

**Dump lineage.** `trip.bin` and `cdt_full.bin` are byte-identical (same MD5),
and both are an exact 4 MB prefix of `cdt_v2.bin`. The log has **not wrapped**.
`cdt_v2.bin` is a strict superset and is the only dump worth keeping;
`trip.*` and `cdt_full.*` are redundant.

### 1.2 Log format register

`0x000A003F` decodes to eight fields:

```
UTC, VALID, LATITUDE, LONGITUDE, HEIGHT, SPEED, RCR, DISTANCE
```

The GPX export currently carries only time / lat / lon / elevation. **Three
logged fields are being discarded**, and one of them is the most useful field in
the record.

**`VALID` — fix quality. Currently thrown away.**

| Value | Meaning | Count | Share |
|---|---|---|---|
| 4 | DGPS (SBAS-corrected) | 124,259 | 98.1% |
| 2 | SPS (uncorrected) | 2,333 | 1.8% |
| 1 | **No fix** | 13 | 0.01% |
| 64 | **Estimated / dead reckoning** | 8 | 0.006% |

This settles the open question from revision 1 about whether the vertical channel
is barometric. **It is not** — it is SBAS-corrected GPS. 98% of fixes carry
differential corrections, which is a sufficient explanation for the
vertical/horizontal noise ratio of 0.72 sitting below the GPS-only norm of
1.5–2×. `NSAT` and `VDOP` were never required to answer this; `VALID` was in the
record the whole time and the exporter dropped it.

**`RCR` — reason for recording.**

| Value | Meaning | Count |
|---|---|---|
| 1 | Time criterion | 125,194 |
| 8 | **Button press** | 1,419 |

The 1,419 button presses are user-marked waypoints. This is why `mtkbabel` emits
a separate `_wpt.gpx`. Preserve the distinction; do not flatten it.

**`SPEED`** — usable. Range 0–122.7 km/h, mean 2.105 km/h. Integrates to roughly
920 mi against the 1,042 mi haversine total, which is the right order.

**`DISTANCE`** — **not usable as an odometer.** It is not monotonic, it resets on
power cycle, and its positive deltas sum to 19,082 mi against an actual 1,042 mi.
Parse it, store it, do not trust it, and never surface it as a trip total.

### 1.3 The remaining gap

`NSAT`, `HDOP`, `VDOP`, `PDOP` are absent from `0x000A003F` and therefore absent
from the existing flash — they cannot be recovered from any dump. Obtaining them
requires **writing** a new format register, which is why config writes are in v1
scope. A useful target mask adds `NSAT`, `HDOP`, `VDOP`:

```
0x000A003F | 0x00001000 | 0x00000400 | 0x00000800  =  0x000A1C3F
```

This grows the record from 42 to 48 bytes (+14%), a proportional reduction in
flash-hours. Not free — make it a deliberate user choice, and note the tradeoff
in the UI rather than defaulting it on.

### 1.4 Corrections to revision 1

Three claims from revision 1 did not survive verification.

**(a) The sentinel table was a GPX rounding artifact, and neither magic number
survives exact comparison.** Revision 1 listed 30 × `ele == 150.0` and 1 ×
`lat 90 / lon 0` as separate sentinels. In the raw flash there is **exactly one**
bad record, carrying all three at once:

```
lat = 89.9999999999996   (bits 40567FFFFFFFFFE4)
lon = 0.0                (exact)
ele = 150.0              (exact)
VALID = 1 (NO_FIX)       2026-08-21T22:52:54Z
```

Both proposed masks fail, in opposite directions:

- **`lat == 90.0` matches nothing.** The stored latitude is four ulp below the
  pole. An exact-equality mask on latitude is not merely fragile, it is a silent
  no-op — which is exactly how it would behave in production, catching nothing
  while appearing correct.
- **`ele == 150.0` over-matches.** It hits this record, but 16 genuine elevations
  in 149.95–150.04 also render as `150.0` at one decimal place, so any mask
  applied to formatted output discards 16 good fixes.

**Mask on `VALID` instead.** It is an explicit device-reported quality field
rather than a coincidence of floating-point values, and it catches this record
along with the other 20 unusable ones. Keep a *tolerance-based* pole test as a
secondary path for dumps whose format register omits `VALID`, but never as the
primary. This is implemented as `Fix.isSentinel` and pinned by test.

**(b) `FMT_REG` never changed, so it did not cause the segment boundaries.**
Revision 1 speculated that mid-log format changes explained some of the 79
segments. All 82 sector headers carry `0x000A003F`, and there are **zero**
type-`0x02` (change-bitmask) dynamic markers in the entire flash. The 580 markers
break down as:

| Type | Arg | Count | Meaning |
|---|---|---|---|
| `0x07` | `0x0100` | 200 | Log status change |
| `0x07` | `0x0102` | 200 | Log status change |
| `0x04` | 0 | 90 | Distance criterion write (no-op) |
| `0x05` | 0 | 90 | Speed criterion write (no-op) |

200 log enable/disable pairs — **the segments are power cycles.** Re-reading
`FMT_REG` per sector remains correct defensive practice, but it is not the
explanation, and the app should not present format changes as a likely cause of
track breaks.

**(c) The spike count was 42, not 26.** Applying revision 1's own filter (reject
> 25 m from a 5-sample rolling median) to the raw flash flags 42 points. The
useful finding is the correlation: **40 of the 42 are `VALID == 2`**, a class
that is only 1.8% of the data. That is roughly a 90× enrichment. `VALID` does not
replace the rolling median — most `VALID == 2` fixes are fine, and blanket-
rejecting them would discard 2,333 points to catch 40 — but it is a strong prior.
Gate the filter's aggressiveness on fix quality rather than applying one
threshold uniformly.

### 1.5 GPS week rollover

The device's `UTC` field requires **+1024 weeks** (≈19.6 years) added to land in
the correct era. Firmware `AXN_1.30-B` predates the April 2019 GPS week
rollover and never received a fix.

This is verified, not assumed: 1024 weeks reproduces a known Chief Mountain fix at
48.993 / −113.658 on 2025-06-16, while 2048 lands in 2044.

**Do not hardcode this as a bare constant.** It is a per-firmware property that
will be wrong for any other device and will need revisiting after the next
rollover epoch. Store it as configuration keyed on the firmware string, with the
verified value as the default, and expose it — a user staring at timestamps 19
years in the past needs to be able to find and fix that on a phone.

---

## 2. Device identification — resolved

| Question | Answer | Source |
|---|---|---|
| Firmware | `AXN_1.30-B_1.3_C01` | `$PMTK605` via `dump_v2.log` |
| Model ID | `0008` | same |
| Holux variant? | **No** | lat/lon parse cleanly as f64 doubles; the `holux245_init` f32 path is not needed |
| Raw dump exists? | **Yes** | `data/cdt_v2.bin`, 126,613 fixes, 0 checksum failures |
| `mtkparse.py` input | Raw flash binary | Ports near-directly to Kotlin |
| Transport | Bluetooth SPP first, USB second | §0 |

**Every device-reported quantity is untrustworthy.** This is the single most
important operational finding, and it should shape how the app talks to the
logger:

| Query | Device says | Reality |
|---|---|---|
| Number of records | 247,133 | 126,613 |
| Flash size | *query failed* | had to be forced to 16 MB via `MTK_FLASH_BYTES` |
| Memory health mask | all `FF` | not meaningfully reported |
| `$PMTK704` | `packet_wait()` failed | unsupported |

Consequences for the app: never use the reported record count as a progress
denominator or a parser assertion, and never trust a reported flash size. **Size
the download empirically** by reading until sectors come back as `0xFF` fill, and
detect wrap from sector-header timestamps rather than from a write pointer.

---

## 3. Protocol layer: PMTK over NMEA

Commands are NMEA sentences: `$` + payload + `*` + two-hex-digit XOR checksum of
everything between `$` and `*`, then CRLF. Responses arrive the same way.

| Command | Purpose | Response |
|---|---|---|
| `$PMTK182,2,2` | Query log format register | `PMTK182,3,2,<hex mask>` |
| `$PMTK182,2,7` | Query log status | `PMTK182,3,7,<status>` |
| `$PMTK182,2,9` | Query flash size | **unreliable on this device** |
| `$PMTK182,7,<addr>,<len>` | Read log block, hex args | `$PMTK182,8,<addr>,<hexdata>` |
| `$PMTK182,1,<id>,<value>` | Set a config field | `PMTK001,182,1,3` on success |
| `$PMTK182,4` | Enable logging | `PMTK001,182,4,3` |
| `$PMTK182,5` | Disable logging | `PMTK001,182,5,3` |
| `$PMTK182,6,1` | **Erase flash** | `PMTK001,182,6` — *not implemented, see §8* |
| `$PMTK605` | Firmware release | `PMTK705,...` |
| `$PMTK704` | — | **unsupported on this firmware**, do not send |

Notes that will cost time if missed:

- **Read length must be even.** Odd lengths are rejected.
- Baud is **115200** for this device. (38400 is the Holux M-241 case, which this
  is not.)
- Block reads of `0x10000` at a time are typical. Expect to retry; the transport
  is a raw byte stream with no framing guarantees.
- A checksum error on the very first packet appears in `dump_v2.log` and the
  session recovered fine. **Do not abort a session on a single bad packet** —
  retry the block.
- Logging should be disabled before any config write and restored afterward.

---

## 4. Binary log format — verified

### 4.1 Sector header

Confirmed by inspection of `cdt_v2.bin` sector 0. This resolves revision 1's
open "56-byte vs 512-byte, verify against source" question: **both are right.**
Fields occupy the first 20 bytes; records begin at 512.

```
0x000  u16  record count       0xFFFF in the active sector — see below
0x002  u32  FMT_REG            0x000A003F
0x006  u16  mode               0x0100 / 0x0102
0x008  u32  time criterion     200 = 20.0 s
0x00C  u32  distance criterion 0
0x010  u32  speed criterion    0
0x014..0x1F9   0xFF fill
0x1FA  '*' + checksum + BB BB BB BB
0x200  first record
```

**The record count is `0xFFFF` in the sector currently being written.** It is
only finalized when the sector fills. In `cdt_v2.bin` the finalized 81 sectors
sum to 125,152, and the active sector holds a further 1,461 fixes. A parser that
trusts the header count **silently drops the most recent 1,461 fixes** — which
are exactly the ones a user just walked. Parse until `0xFF` fill; treat the count
as a cross-check, not as an authority.

### 4.2 Records

Field order follows ascending `FMT_REG` bit position:

```
0  UTC          u32     8  DAGE      u32    16  SNR
1  VALID        u16     9  PDOP      u16    17  RCR          u16
2  LATITUDE     f64    10  HDOP      u16    18  MILLISECOND  u16
3  LONGITUDE    f64    11  VDOP      u16    19  DISTANCE     f64
4  HEIGHT       f32    12  NSAT      u16
5  SPEED        f32    13  SID       variable
6  HEADING      f32    14  ELEVATION  (satellite, not altitude)
7  DSTA         u16    15  AZIMUTH
```

Two traps: bit 14 `ELEVATION` is satellite elevation angle, unrelated to
altitude, which is bit 4 `HEIGHT`. And the satellite block (bits 13–16) is
**variable length**, so record size isn't a pure function of bitmask popcount if
those bits are set. Not currently an issue — this device's mask sets none of
them — but a config write could, so the parser should reject rather than
misparse.

Each record ends with `*` (`0x2A`) then an XOR checksum byte over the payload.
Validate and count failures rather than aborting.

### 4.3 Dynamic markers

16 bytes: `AA AA AA AA AA AA AA <type> <arg:i32> BB BB BB BB`. Observed types are
tabulated in §1.4(b). Type `0x02` changes the format register mid-log and must be
honoured even though this flash contains none.

### 4.4 Circular buffer

Flash is a circular buffer in `OVERLAP` mode — on wrap it overwrites the oldest
data and the write pointer goes small. **This flash has not wrapped yet**, which
is a temporary state of affairs, not a property to rely on. Always download the
entire flash rather than up to the pointer.

---

## 5. Android platform

The vendor app died to a stack of platform changes rather than one:

- **API 23** — runtime permissions; Bluetooth discovery began requiring location.
- **API 29/30** — scoped storage broke apps writing GPX to arbitrary paths.
- **API 31** — Bluetooth permission model replaced entirely with
  `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`.
- **API 34** — long-running work requires a declared foreground service type; a
  multi-megabyte flash dump needs `connectedDevice`.

### Bluetooth SPP — the v1 path

- Classic RFCOMM via `BluetoothSocket`, SPP UUID
  `00001101-0000-1000-8000-00805F9B34FB`.
- **The device must be bonded before an RFCOMM socket can open.** Unlike BLE,
  Android has no unpaired-connect path for Classic.
- **Use Companion Device Manager** (API 26+). It presents a system pairing UI on
  the app's behalf and requires no location permission at all. For a single known
  companion device this is strictly better than custom discovery, and on a phone
  that is also the only computer, fewer permission prompts is a real usability
  win.
- API 31+: request `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` at runtime, with
  `android:usesPermissionFlags="neverForLocation"` on the scan permission.
- Below API 31 additionally needs `BLUETOOTH`, `BLUETOOTH_ADMIN` and
  `ACCESS_FINE_LOCATION`; declare the legacy ones with
  `android:maxSdkVersion="30"`.
- **Run the dump in a foreground service** with type `connectedDevice`. 5.4 MB at
  115200 baud, hex-encoded on the wire (2×) plus protocol overhead, is on the
  order of 20–25 minutes. It will not survive backgrounding otherwise. Given §0,
  it also must survive the screen going off in a pocket.

### USB — deferred to v2

`usb-serial-for-android` handles PL2303, CP210x, CH34x, FTDI and CDC-ACM.
Permission is per-device via intent filter plus a one-time prompt: no runtime
permission dance, no location involvement. The bridge chip is not yet identified.
Both transports sit behind the same `Transport` interface (§6), so this is an
additive change.

### Target device

| | |
|---|---|
| Model | Samsung SM-G990U1 (Galaxy S21 FE 5G) |
| Android | 16, **API 36** |
| ADB serial | `R5CT61AD22T` |

**minSdk 31, compileSdk 35.** Since the phone is API 36, the entire pre-31
permission branch is dead code and is not written. Only `BLUETOOTH_CONNECT` and
`BLUETOOTH_SCAN` are declared; no `ACCESS_FINE_LOCATION`, no
`android:maxSdkVersion` shims.

---

## 6. Architecture

```
transport/          interface: open, read, write, close
  BluetoothSpp      RFCOMM socket                      v1
  UsbSerial         usb-serial-for-android             v2
  FileReplay        replays a captured session         v1 — user-facing, see below
protocol/
  Nmea              frame, checksum, parse sentences
  PmtkClient        typed commands, ack matching, timeouts, retry
  FlashDownloader   block reads, progress, resume, integrity
format/
  SectorHeader      count, FMT_REG, mode, criteria
  RecordParser      bitmask-driven, pure fn (bytes, fmt) -> Fix
  Quality           VALID/RCR masking, spike reject gated on fix quality
export/
  Gpx, Csv, RawBin
ui/                 Compose: connect, status, download, config, export
```

**Design rules that matter more than the rest:**

1. **Persist the raw `.bin` before parsing anything.** The flash is the only copy
   of that data. Every parse runs against a file on disk, never against a live
   stream, so a bad parser release cannot destroy anything.
2. **`FileReplay` is a shipped feature, not a test seam.** Per §0, the user must
   be able to re-parse and re-export an existing `.bin` with no logger present
   and no network. It happens to also make the parser unit-testable, which is a
   bonus rather than the point.
3. **`cdt_v2.bin` + 126,613 is the golden pair.** If the Kotlin parser, fed
   `cdt_v2.bin`, produces exactly 126,613 fixes with 0 checksum failures and a
   matching first/last fix, it is correct. That is a far stronger test than
   anything hand-written.
4. **Resume everywhere.** Dumps resume from the last good block; parses are
   incremental. Nothing restarts from zero.

---

## 7. Session order

| Phase | Work | Needs device? | Status |
|---|---|---|---|
| 0 | Answer §2. Port `mtkparse.py`. | No | **done** |
| 1 | `RecordParser` + `SectorHeader`, golden-file test | No | **done** |
| 2 | Transport + `PmtkClient`, **read-only** | Yes | next |
| 3 | Full-flash download to `.bin`, progress + resume | Yes | |
| 4 | Export pipeline, then UI | No | |
| 5 | Config writes: interval, format mask, enable/disable | Yes | |
| 6 | ~~Erase~~ | — | **out of scope** |

### Phase 1 result

`:core` is a pure-JVM Kotlin module with no Android dependencies, so the golden
test runs without a device or emulator. All 20 assertions pass against
`cdt_v2.bin`:

```
126,613 fixes · 0 checksum failures · 82 sectors · 580 markers
0 format changes · 79 segments · first/last fix and rollover era verified
```

Several assertions exist specifically to pin down mistakes that would otherwise
pass unnoticed: that the declared sector counts sum to 125,152 and therefore
under-report by 1,461; that `height == 150.0` is not a usable sentinel test; and
that the sentinel latitude is *not* exactly 90.0.

Build and run:

```
ANDROID_HOME=$HOME/android-sdk ~/tools/gradle-8.11.1/bin/gradle :core:test
```

---

## 8. Safety rails

- **Erase is not being implemented.** The flash is the only copy of 18 months of
  data, the erase is irreversible, and the device is demonstrably unreliable about
  its own state (§2). There is no version of this that is worth the risk on a
  phone at a trailhead. If it is ever added, it needs a typed confirmation, a
  verified full dump within the same session, and must never be reachable from a
  download-complete callback.
- **Never write config as a side effect of connecting.** Reading is safe; writing
  is a deliberate, explicit user action. This applies to the format-register
  change in §1.3 as much as to anything else.
- **Log every PMTK exchange to a rolling file.** Per §0 this is the only
  diagnostic available in the field, so it must be readable and exportable from
  within the app.
- **Provide a strictly read-only mode** in which the app cannot send a write
  command at all. Given that config writes are in v1 scope, this gate matters
  more, not less.
- **Verify before destroying.** No operation that reduces data on the device
  proceeds without a verified local copy first.

---

## 9. Reference implementations

- **GPSBabel** `mtk_logger.cc` — most complete open implementation, including the
  Holux float variant and erase/re-enable sequencing.
- **MTKBabel** (Perl) — smaller, easier to read end to end. `mtkparse.py`'s field
  layout follows mtkbabel 0.8.4.
- **BT747** — the original desktop manager; most thorough config coverage.
- **bt747cli** — modern CLI, read-only by design, handles circular-buffer wrap
  and per-sector `FMT_REG` correctly.
- MTK Logger Library user manual — vendor document for the sector pattern and the
  `$PMTK182` family.

## 10. Data inventory

| File | Keep? | Notes |
|---|---|---|
| `cdt_v2.bin` | **yes — golden** | 5,376,000 B, 126,613 fixes, 0 checksum failures |
| `cdt_v2_corrected.gpx` | yes | 126,613 trkpt, full-fidelity export |
| `dump_v2.log` | yes | Only record of the device's own self-report |
| `mtkparse.py` | yes | Reference parser; port source |
| `trip.bin`, `cdt_full.bin` | redundant | Byte-identical to each other, exact prefix of `cdt_v2.bin` |
| `trip_*.gpx`, `cdt_full_*.gpx` | redundant | Derived from the prefix dump |
| `descent_*.html`, `geojson_io_link.txt` | incidental | Ad-hoc visualizations |
| `gps_recovery_backup/` | **keep, read-only** | Independent copies of the two dumps |
