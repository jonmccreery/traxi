# How This Thing Knows Where You Are

A technical companion to the Traxi project: what the MTK logger actually
measures, how well, and how it writes it down.

Every number attributed to "this device" was measured from `data/cdt_v2.bin` —
126,613 fixes over 18 months, 5.4 MB of raw flash — rather than taken from a
datasheet. Where the measurement contradicts the conventional figure, both are
given.

---

# Part I — Positioning

## 1. The only thing a GPS receiver actually measures

A GPS receiver does not measure distance, direction, or position. It measures
**one number per satellite: arrival time**.

Each satellite broadcasts a continuous pseudo-random code on L1 (1575.42 MHz),
timestamped by an onboard atomic clock, along with a navigation message
describing where the satellite is (*ephemeris*) and how its clock is behaving.
The receiver generates the same code locally, slides its copy until it
correlates with the incoming signal, and reads off the time shift.

Multiply that shift by the speed of light and you get a **pseudorange**:

```
ρ = c × (t_received − t_transmitted)
```

It is called *pseudo* because it is wrong, in a specific and exploitable way.
The satellite's clock is atomic and disciplined to a few nanoseconds. The
receiver's clock is a cheap quartz crystal that may be off by a millisecond —
and a millisecond of clock error is **300 kilometres** of range error.

## 2. Four unknowns, four satellites

The receiver's clock offset `δt` is unknown, but it is the *same* unknown in
every simultaneous pseudorange. That turns a seemingly fatal problem into an
ordinary one. For each satellite `i`:

```
ρᵢ = √((xᵢ−x)² + (yᵢ−y)² + (zᵢ−z)²) + c·δt
```

Four unknowns — `x`, `y`, `z`, `δt` — so four satellites give four equations.
The system is non-linear, so it is solved iteratively (linearize about a guess,
least-squares correction, repeat; three or four passes converge). Any satellites
beyond the fourth make the system overdetermined, and the extra redundancy is
what buys accuracy.

A useful consequence, and the reason GPS disciplines so much of the world's
timing infrastructure: **a GPS receiver is a clock that happens to also report
position.** Solving for position requires solving for time, to nanoseconds, for
free.

## 3. Where the error comes from

Solving those equations perfectly would still give a wrong answer, because the
pseudoranges themselves are contaminated. The standard accounting is the **User
Equivalent Range Error** (UERE), the per-satellite range error after the
receiver has applied whatever corrections it has.

| Source | Uncorrected (1σ) | What it is |
|---|---|---|
| **Ionosphere** | **~4–7 m** | Free electrons 50–1000 km up delay the signal. Varies with solar activity, time of day, and elevation angle. **The dominant error.** |
| Satellite clock | ~2 m | Residual drift between ground-segment clock updates |
| Ephemeris | ~2 m | The broadcast orbit differs slightly from the true orbit |
| Troposphere | ~0.5 m | Water vapour and pressure in the lower atmosphere. Modelled, not measured |
| Multipath | ~0.5–2 m | Signal arriving via reflection off rock, buildings, vehicles. **Highly site-dependent** |
| Receiver noise | ~0.5 m | Thermal noise in the correlator |
| **Total UERE** | **~5–7 m** | Root-sum-square |

Two properties matter for what follows. The ionosphere dominates, and it is
**spatially correlated** — two receivers 100 km apart see nearly the same
ionospheric delay. That correlation is the entire basis of differential GPS.
Multipath, by contrast, is *not* correlated between sites, which is why no
differential technique can remove it and why a slot canyon degrades any
receiver.

## 4. Geometry: why vertical is structurally worse

UERE is a per-satellite range error. How much of it lands in your *position*
depends on where the satellites are in the sky. That amplification factor is
**Dilution of Precision**:

```
σ_position = DOP × UERE
```

Satellites spread widely across the sky intersect at sharp angles and pin the
solution down. Satellites clustered together intersect at shallow angles, and
small range errors smear into large position errors. DOP is reported by
component — `HDOP` horizontal, `VDOP` vertical, `PDOP` position, `TDOP` time,
`GDOP` everything.

**The vertical channel is handicapped by geometry that cannot be fixed.**
Horizontally, satellites surround you: some north, some south, some east, some
west, and their errors partially cancel. Vertically they are all *above* you.
There is no satellite beneath your feet, because the Earth is in the way. Every
vertical measurement is therefore one-sided, and the vertical component of the
solution is less well constrained than either horizontal component.

The standard consequence is `VDOP ≈ 1.5–2 × HDOP`, and hence vertical error
roughly 1.5–2× horizontal error. This is not an implementation weakness. It is
a property of standing on top of an opaque sphere.

## 5. SPS vs DGPS vs SBAS

**SPS — Standard Positioning Service.** The receiver alone, using only the
broadcast navigation message. It applies a *modelled* ionospheric correction
(the Klobuchar model, whose coefficients the satellites broadcast) that removes
roughly half the ionospheric error on a good day. UERE ~5–7 m.

**DGPS — Differential GPS.** A reference receiver at a precisely surveyed
location computes what the pseudoranges *should* be, compares them to what it
*measures*, and broadcasts the difference. A nearby rover applies those
corrections and cancels everything spatially correlated: ionosphere, satellite
clock, ephemeris. What survives is multipath and receiver noise, both local.

**SBAS — Satellite-Based Augmentation System.** DGPS delivered from orbit,
without a local radio link. A network of reference stations feeds a master
station, which uplinks corrections to **geostationary** satellites that
rebroadcast them on the same L1 frequency the receiver is already listening to.
No extra hardware, no subscription, no base station.

Regional systems: **WAAS** (North America), **EGNOS** (Europe), **MSAS**
(Japan), **GAGAN** (India). SBAS transmits three distinct products:

- **Fast corrections** — rapidly changing satellite clock error
- **Long-term corrections** — slowly changing ephemeris and clock drift
- **Ionospheric grid** — vertical delay at fixed geographic grid points, which
  the receiver interpolates along each satellite's line of sight

That third product is the important one, and it is why SBAS helps the vertical
channel disproportionately: it replaces a *model* of the largest error source
with a *measurement* of it.

Typical SBAS-corrected UERE is **~1–2 m**, roughly a 4× improvement.

**One field caveat that matters on trail.** SBAS satellites are geostationary,
so they sit over the equator — low in the *southern* sky from North America,
and lower the further north you go. A ridge, canyon wall, or dense canopy to
your south can block the correction stream while the GPS constellation itself
remains perfectly visible. The receiver silently drops from DGPS to SPS. This
is exactly what the data shows, and it is the mechanism behind most of this
device's degraded fixes.

## 6. What this device's data actually shows

The logger records a `VALID` field with every fix. Across 126,613 fixes:

| `VALID` | Meaning | Count | Share |
|---|---|---|---|
| 4 | **DGPS** (SBAS-corrected) | 124,259 | 98.14% |
| 2 | SPS (uncorrected) | 2,333 | 1.84% |
| 1 | No fix | 13 | 0.010% |
| 64 | Estimated / dead reckoning | 8 | 0.006% |

**98% of the entire 18-month history is WAAS-corrected.** This is a well-sited
receiver that holds its correction stream almost all the time.

### 6.1 Measured accuracy

Isolating 103 stationary periods longer than 30 minutes — where the device was
demonstrably not moving, so all scatter is measurement error — and computing
dispersion in local north/east/up coordinates:

| Statistic | Median across 103 stops |
|---|---|
| σ north | 1.43 m |
| σ east | 1.18 m |
| **σ horizontal** (DRMS, the `HDOP`-matched statistic) | **1.91 m** |
| **σ vertical** (the `VDOP`-matched statistic) | **1.94 m** |

Roughly **2 m 1σ in both channels** — squarely in SBAS-corrected territory and
about 3× better than the ~5–7 m an uncorrected receiver would manage.

### 6.2 The vertical/horizontal ratio is not one number

Section 4 predicts vertical error 1.5–2× horizontal. Earlier analysis of this
device's exported GPX reported **0.72**, which appeared to falsify that and
raised the question of whether the vertical channel came from a barometer
instead.

Both figures are real. They measure different timescales:

| Timescale | V/H ratio | What it reflects |
|---|---|---|
| 20 s consecutive differences | **0.71** | Receiver's tracking filter |
| 30–90 min dispersion | **1.14** (IQR 0.57–1.66) | Actual error budget |

At 20 seconds you are not measuring GNSS accuracy. You are measuring how hard
the receiver's Kalman filter is smoothing, and it smooths the vertical channel
harder precisely *because* vertical is noisier — a well-known design choice that
makes consecutive altitudes look far more stable than they are. Differencing
adjacent samples of a smoothed series measures the smoother.

At 30–90 minutes the filter's memory has long since washed out and the true
error budget shows: a ratio of **1.14**, below the textbook 1.5–2× but with an
interquartile range that spans it. The residual suppression is consistent with
SBAS correcting the ionosphere, the error source that hurts vertical most.

**The original 0.72 was not wrong. It was an answer to a different question.**

### 6.3 Proof the vertical channel is GNSS, not barometric

The timescale argument explains the anomaly but does not by itself rule out a
barometer. This does. Splitting the *same* stationary-period analysis by fix
quality:

| Fix quality | n | Stationary vertical step, mean | p95 |
|---|---|---|---|
| DGPS (SBAS) | 124,259 | **0.182 m** | 0.356 m |
| SPS (uncorrected) | 2,333 | **1.366 m** | 6.964 m |

Losing the SBAS correction degrades the vertical channel by **7.5× in the mean
and 20× at p95**.

A barometric altimeter does not know or care whether the GPS receiver is
receiving WAAS corrections. If altitude came from a pressure sensor, these two
rows would be identical. They differ by nearly an order of magnitude.

**`HEIGHT` is GNSS-derived. There is no barometer in this device.** The question
is settled, and settled from data that was in the flash the whole time — the
GPX exporter simply discarded the `VALID` field needed to ask it. No `NSAT` or
`VDOP` was required.

### 6.4 A correction: elevation is not quantized to 0.1 m

Earlier analysis reported 0.10 m elevation quantization and inferred that "the
fix engine emits decimetres". It does not:

- 125,140 **distinct** height values across 126,592 fixes (98.9%)
- Smallest gaps between distinct values: **0.000031 m**
- f32 resolution at 1500 m altitude: 0.000122 m

The heights are continuous at f32 precision. The apparent decimetre
quantization was an artifact of the GPX exporter writing `<ele>` with `%.1f`
formatting — the same class of mistake that inflated the sentinel counts. **This
is the third distinct conclusion that changed once the raw flash was read
instead of the export.** Measure the binary, not the derived file.

### 6.5 Open question: ellipsoidal or mean sea level?

GNSS natively computes height above the **WGS84 reference ellipsoid**, a smooth
mathematical figure. Maps use height above the **geoid** (mean sea level), a
lumpy equipotential surface. The difference — *geoid separation* — is about
−34 m near the Great Lakes and −8 to −27 m across the mountain west. Consumer
receivers usually apply an EGM96 correction; loggers often do not.

The evidence here is suggestive but not conclusive. Fixes near 41.883 / −87.804
read a median **156.0 m**, against roughly 204 m MSL for that area and roughly
170 m ellipsoidal. Closer to ellipsoidal, but off by enough that the
comparison point is doing too much work.

**Decisive test:** record a fix at a surveyed benchmark of known orthometric
height, in a location where geoid separation is large, and compare. Repeating at
two sites with very different separations distinguishes a constant offset from a
geoid-shaped one. Until then, treat absolute elevations as carrying a possible
20–35 m systematic offset. *Relative* elevation — gain, loss, profile shape — is
unaffected, which is what most trail use actually depends on.

## 7. Speed comes from Doppler, not from positions

There are two ways to produce a speed, and they are not equally good.

**Position differencing** subtracts consecutive positions and divides by
elapsed time. It inherits all the position error, doubled, and it is only ever
an *average* over the interval.

**Doppler** measures the carrier frequency shift caused by relative motion
along each satellite's line of sight. Each satellite gives one range-rate
measurement; four or more solve for the 3D velocity vector plus receiver clock
*drift*. This is an independent measurement path that never touches the position
solution, and it is far more precise — typically **0.05 m/s**, because carrier
frequency can be measured extremely accurately.

The logged data shows this device uses Doppler. Across 25,584 samples taken
during confirmed stationary periods:

| | Mean | Median | Fraction < 0.01 km/h |
|---|---|---|---|
| Position-differenced over 20 s | 0.0314 km/h | 0.0155 | 32.0% |
| **Device-reported `SPEED`** | 0.0510 km/h | 0.0382 | 5.3% |

The reported speed is **noisier at rest than differencing the logged positions
would be** — 1.63× the mean. That rules out the device deriving speed from those
positions: if it did, the two rows would match exactly.

It is the expected signature of Doppler. Position differencing over a 20-second
baseline averages noise down by the length of the interval, while Doppler
reports an *instantaneous* velocity at the fix epoch with no averaging at all.
Instantaneous-and-precise looks noisier than averaged-and-crude when the target
is not moving, and is dramatically better when it is.

**Practical consequence:** logged `SPEED` is a snapshot at the fix instant, not
an average over the preceding 20 seconds. Integrating it does not give distance.
Measured against a 1,042 mi haversine total, integrating logged speed yields
~920 mi — a 12% shortfall, because the samples miss whatever happened between
them.

## 8. Time, and why this device thinks it is 2005

GPS time began at **1980-01-06 00:00:00 UTC** and has run continuously since,
with **no leap seconds**. UTC has absorbed 18 leap seconds in that span, so GPS
time currently runs 18 s ahead of UTC. Receivers subtract the offset, which is
broadcast in the navigation message.

Time is transmitted as **week number + time of week**. In the legacy navigation
message the week number field is **10 bits** — 0 to 1023. It rolls over every
1024 weeks, or about **19.6 years**:

| Rollover | Date |
|---|---|
| Epoch 0 begins | 1980-01-06 |
| Rollover 1 | 1999-08-22 |
| Rollover 2 | **2019-04-06** |
| Rollover 3 | 2038-11-21 |

A receiver only sees the low 10 bits. To recover the true date it must add the
right number of 1024-week epochs, and that number is **not transmitted** — it is
baked into firmware, typically as a build-date-derived assumption.

This device's firmware is `AXN_1.30-B_1.3_C01`, built well before 2019. It
assumes the 1999–2019 window. Since 2019-04-06 the broadcast week number has
wrapped to 0, and the firmware maps it back into the previous window — producing
timestamps **exactly 1024 weeks (7,168 days, ~19.62 years) early**.

Hence the parser's correction:

```
corrected_utc = raw_utc + 1024 × 7 × 86400
```

Verified rather than assumed: +1024 weeks places a known Chief Mountain fix at
48.993 / −113.658 on 2025-06-16, while +2048 lands it in 2044.

Two cautions. This is a **per-firmware** property, wrong for any other device,
and it must be stored as configuration rather than compiled in as a constant.
And it is **not permanent**: at rollover 3 in November 2038, firmware of this
vintage will need +2048 weeks. Any hardcoded 1024 becomes silently wrong on that
date.

---

# Part II — Device Internals

## 9. Identification

| Property | Value | How known |
|---|---|---|
| Firmware | `AXN_1.30-B_1.3_C01` | `$PMTK605` response |
| Model ID | `0008` | same |
| Chipset family | MediaTek MT3329 / MT3339 class | AXN firmware naming |
| Coordinate width | f64 doubles | Parses cleanly; **not** the Holux f32 variant |
| Record size | 42 bytes | Device-reported and confirmed byte-exact |
| Log interval | 20.0 s, time-based | Sector headers, all 82 |
| Fill mode | `OVERLAP` (circular) | Log status |

### 9.1 The device is more truthful than the tooling was

An earlier draft of this document claimed "everything the device says about
itself is wrong." Talking to the hardware directly disproved half of that. Two
of the four apparent lies were **decoding failures in the tooling**, not faults
in the device:

| Query | Believed | Actually |
|---|---|---|
| `$PMTK182,2,9` flash size | query fails | **Works.** Returns a **JEDEC RDID**, not a byte count. `1C70171C` = manufacturer `0x1C` (EON), type `0x70`, capacity `0x17` → 2²³ = **8 MB**. Nothing decoded it, so it read as garbage |
| `$PMTK182,2,8` record count | 247,133 vs 126,613 actual | **Not a record count.** A **byte address**: `0051F3C2` = 5,370,818, the next write pointer. The label was wrong, not the device |
| Memory health mask | all `FF` | still not meaningfully implemented |
| `$PMTK704` | `packet_wait()` timeout | still unsupported — do not send |

The flash decode is self-checking: 8 MB holds the 5.37 MB written region, and
4 MB could not.

**What survives the correction.** A client still must not *size* a download from
the write pointer, and must not *stop* there. In `OVERLAP` mode a wrapped log
keeps its oldest records past the pointer, so stopping there silently discards
them. The pointer is a progress hint; reading until sectors return `0xFF` fill
remains the correct termination.

The broader lesson is the one from §6.4 arriving from the other direction:
**a value that looks like nonsense is often a value nobody decoded.** The GPX
exporter manufactured phantom sentinels by rounding; the flash-size query looked
broken because it was read as an integer instead of an identifier. Both times
the data was fine and the reader was wrong.

## 10. Flash geography

The log is a flat array of **64 KiB (0x10000) sectors**. Each is
self-describing: a sector carries its own format register, so the log remains
parseable from any sector boundary even if earlier sectors have been overwritten
by a wrap.

```
┌──────────────── sector N (0x10000 bytes) ─────────────────┐
│ 0x000  header (20 bytes of fields)                        │
│ 0x014  0xFF fill                                          │
│ 0x1FA  '*' <cksum> BB BB BB BB   ← header terminator      │
│ 0x200  first record                                       │
│        record, record, record, ...                        │
│        [16-byte dynamic marker]                           │
│        record, record, ...                                │
│        0xFF fill to end of sector                         │
└───────────────────────────────────────────────────────────┘
```

### 10.1 Sector header

Verified byte-for-byte against sector 0. This resolves a long-standing ambiguity
in the community documentation — the MTK manual describes a 56-byte "sector
pattern" while BT747 and derivatives reserve 512 bytes. **Both are correct.**
The fields occupy the first 20 bytes; records begin at 0x200.

```
offset  type  field                observed
──────  ────  ───────────────────  ──────────────────────────
0x000   u16   record count         0xFFFF while still writing
0x002   u32   FMT_REG              0x000A003F
0x006   u16   mode                 0x0100 / 0x0102
0x008   u32   time criterion       200  (units of 0.1 s → 20.0 s)
0x00C   u32   distance criterion   0    (disabled)
0x010   u32   speed criterion      0    (disabled)
0x014   ...   0xFF fill
0x1FA   —     '*' <cksum> BB BB BB BB
```

**The record count is a trap.** The device writes it only when a sector
*fills*; the sector currently being written reads `0xFFFF`. On this dump the 81
finalized sectors declare 125,152 records while the flash actually holds
126,613. A parser that trusts the header count silently discards **1,461
fixes** — and they are the most recent ones, the trip the user just finished.

Parse until `0xFF` fill. Treat the count as a cross-check, never as a bound.

### 10.2 Record layout

Records are a packed struct whose members are selected by the format register,
in ascending bit order, little-endian, terminated by `'*'` and an XOR checksum
over the payload.

For this device's `FMT_REG = 0x000A003F`:

```
offset  width  field       type    notes
──────  ─────  ──────────  ──────  ─────────────────────────────────
  0       4    UTC         u32     epoch seconds, needs +1024 weeks
  4       2    VALID       u16     fix quality bitmask
  6       8    LATITUDE    f64     degrees
 14       8    LONGITUDE   f64     degrees
 22       4    HEIGHT      f32     metres (datum unconfirmed, §6.5)
 26       4    SPEED       f32     km/h, Doppler, instantaneous
 30       2    RCR         u16     reason for recording
 32       8    DISTANCE    f64     odometer — BROKEN, see §11.2
 40       1    '*'         u8      0x2A separator
 41       1    checksum    u8      XOR of bytes 0..39
──────────────────────────────────────────────────────────────────
 42 bytes total
```

Across all 5.4 MB, **zero records fail checksum**. The flash is entirely
healthy, which means any checksum failure in a new parser is a parser defect,
not bad hardware — a useful thing to be able to assert.

### 10.3 The format register

`FMT_REG` is a 32-bit mask. Field order follows ascending bit position, and each
set bit contributes a fixed width — with one exception.

```
bit  field         width   bit  field         width
───  ────────────  ─────   ───  ────────────  ─────
 0   UTC             4      10  HDOP            2
 1   VALID           2      11  VDOP            2
 2   LATITUDE        8      12  NSAT            2
 3   LONGITUDE       8      13  SID          VARIABLE
 4   HEIGHT          4      14  ELEVATION       2
 5   SPEED           4      15  AZIMUTH         2
 6   HEADING         4      16  SNR             2
 7   DSTA            2      17  RCR             2
 8   DAGE            4      18  MILLISECOND     2
 9   PDOP            2      19  DISTANCE        8
```

Two traps worth stating plainly:

- **Bit 14 `ELEVATION` is satellite elevation *angle*, not altitude.** Altitude
  is bit 4, `HEIGHT`. The names are a decades-old inheritance and have misled
  many implementations.
- **Bits 13–16 are the per-satellite block and it is variable length.** When
  `SID` is set, record size is no longer a function of bitmask popcount and
  records must be parsed forward. This device sets none of them, so the parser
  rejects such formats rather than silently misinterpreting every subsequent
  byte in the sector.

`FMT_REG` can change mid-log via a dynamic marker, so it must be re-read per
sector rather than latched once. **On this device it never has** — all 82 sectors
carry `0x000A003F` and there are zero format-change markers in 5.4 MB.

### 10.4 Dynamic markers

A 16-byte in-band control record, distinguishable from log data by a run of
seven `0xAA` bytes:

```
AA AA AA AA AA AA AA | type | arg (i32 LE) | BB BB BB BB
```

All 580 markers in the reference dump:

| Type | Arg | Count | Meaning |
|---|---|---|---|
| `0x07` | `0x0100` | 200 | Log status change (disable) |
| `0x07` | `0x0102` | 200 | Log status change (enable) |
| `0x04` | 0 | 90 | Distance criterion write (no-op) |
| `0x05` | 0 | 90 | Speed criterion write (no-op) |
| `0x02` | — | **0** | Format change — never occurred |

200 enable/disable pairs, and the exported track breaks into 79 segments at a
300-second gap threshold. **The segments are power cycles**, not format changes
and not signal loss. Earlier speculation that mid-log format changes explained
them is ruled out by the marker census: type `0x02` never appears.

### 10.5 Circular buffer

In `OVERLAP` mode the log wraps and overwrites the oldest sectors, and the write
pointer jumps backwards. Two consequences:

1. **Always download the entire flash**, never up to the write pointer.
   Everything before the wrap point is otherwise silently lost.
2. **Detect wrap from sector-header timestamps**, not from the pointer, since
   the reported pointer and size are both untrustworthy (§9.1).

This dump has **not** wrapped: 82 written sectors, sector 82 unwritten, and
timestamps monotonic across all 126,613 fixes with zero backward steps. That is a
current state, not a guarantee.

## 11. What is logged, and what the export threw away

### 11.1 The three discarded fields

The GPX export carried time, latitude, longitude and elevation. The flash also
holds:

- **`VALID`** — fix quality. The single most useful field in the record, and the
  one that answered the barometric question (§6.3). Dropped entirely.
- **`RCR`** — reason for recording. 1,419 records carry `BUTTON`, meaning the
  user physically pressed the mark button. These are waypoints and should never
  be flattened into the track.
- **`SPEED`** — Doppler velocity (§7). Usable, with the instantaneous-sampling
  caveat.

### 11.2 `DISTANCE` is broken

The `DISTANCE` field is an odometer, and it does not work:

- **Not monotonic** — it resets on power cycle
- Positive deltas sum to **19,082 mi** against an actual **1,042 mi**, an 18×
  overcount, consistent with it accumulating while stationary

Parse it and store it for fidelity. Never surface it as a trip distance. Compute
distance from positions instead.

### 11.3 What cannot be recovered

`NSAT`, `HDOP`, `VDOP` and `PDOP` are absent from `0x000A003F` and therefore
absent from every byte of existing flash. They cannot be recovered from any
dump — only obtained going forward, by **writing** a new format register:

```
0x000A003F | NSAT | HDOP | VDOP  =  0x000A1C3F
```

That grows records from 42 to 48 bytes, a 14% cost in flash-hours. It is worth
noting that the barometric question this data would have answered has already
been settled by `VALID` (§6.3), so the case for paying that cost is weaker than
it first appears.

## 12. Talking to it: the PMTK protocol

Commands are NMEA-0183 sentences: `$`, payload, `*`, then a two-hex-digit XOR
checksum of everything between `$` and `*`, then CRLF.

```
$PMTK182,2,2*39\r\n
 └──────────┘ └┘
   payload    XOR of payload bytes
```

| Command | Purpose | Response |
|---|---|---|
| `$PMTK605` | Firmware release | `PMTK705,...` |
| `$PMTK182,2,2` | Query format register | `PMTK182,3,2,<hex>` |
| `$PMTK182,2,7` | Query log status | `PMTK182,3,7,<status>` |
| `$PMTK182,2,9` | Query flash size | **unreliable here** |
| `$PMTK182,7,<addr>,<len>` | Read log block | `$PMTK182,8,<addr>,<hex>` |
| `$PMTK182,1,<id>,<val>` | Set config field | `PMTK001,182,1,3` |
| `$PMTK182,4` / `,5` | Enable / disable logging | `PMTK001,182,4,3` |
| `$PMTK182,6,1` | **Erase flash** | irreversible |
| `$PMTK704` | — | **unsupported**, do not send |

Practical notes, each of which costs an afternoon if missed:

- **Read length must be even.** Odd lengths are rejected outright.
- **Baud is 115200** for this device. (38400 is the Holux M-241 case.)
- Data returns **hex-encoded**, so a 5.4 MB flash is ~10.8 MB on the wire.
  At 115200 baud with protocol overhead that is **20–25 minutes**.
- Block reads of `0x10000` are typical. **Expect to retry** — the transport is a
  raw byte stream with no framing guarantees, and the reference session shows a
  checksum error on the very first packet followed by a clean 5.4 MB transfer.
  A single bad packet is normal; abort on repeated failure, not on one.
- Erase takes seconds to tens of seconds and needs a much longer ack timeout
  than normal commands.

---

## 13. Summary of corrections

Four conclusions changed once the raw flash was parsed instead of the GPX
export. All four errors share a cause: **the export was treated as the data.**

| Claim | Revised |
|---|---|
| 30 × `ele == 150.0` sentinels, 1 × `lat 90/lon 0` | **One** bad record carrying all three. Latitude is `89.9999999999996`, so `lat == 90.0` matches *nothing*; `ele == 150.0` over-matches 16 genuine elevations rounded by `%.1f` |
| Elevation quantized to 0.10 m | Continuous at f32 precision; 98.9% of values distinct. The decimetres were `%.1f` output formatting |
| V/H noise ratio 0.72 contradicts GPS theory | Timescale-dependent: 0.71 at 20 s (measures the tracking filter), 1.14 over 30–90 min (measures the error budget) |
| Segment boundaries may be mid-log format changes | Zero format-change markers exist. 200 log enable/disable pairs — they are power cycles |

And one question the original data could answer all along:

> **Is the vertical channel barometric?** No. Losing SBAS degrades vertical
> dispersion 7.5×; a pressure sensor would be unaffected. `HEIGHT` is
> GNSS-derived. `VALID` held the answer and the exporter discarded it.
