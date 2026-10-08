# Track analysis: what is believed, how a dump becomes days, and what gets measured

Written 2026-10-07. This covers everything between "the parser produced a list
of fixes" and "here is a GPX file, a day, a distance". Parsing itself — sector
headers, record layout, rollover — is in `gnss-and-device-internals.md` §10.

Every rule here has a threshold, and every threshold has a reason. Where the
reason is a measurement, the measurement is on `data/cdt_v2.bin`: 18 months of
real flash, 126,613 fixes, the same dump the golden-file tests run on. Figures
in this document were measured on 2026-10-07 and are pinned by tests unless
marked otherwise.

---

## The short version

1. **Belief.** Drop what the chip itself says it did not know (NO FIX,
   ESTIMATED, the 90N cold-start sentinel), except a button press a real fix
   nearby vouches for. Then drop elevations that leave their neighbours.
2. **Days.** A gap of 4 h or more ends a day. A night logged straight through
   is cut at the middle of the stop. Only if neither exists does midnight
   decide — *solar* midnight, from the fix's own longitude.
3. **Segments.** Inside a day, a gap over 5 min splits a segment. **Nothing is
   ever measured across a gap.**
4. **Rides.** Sustained vehicle speed, by the logger's own speed field, is a
   ride: its own segment, its own GPX track, never in the walking figures.
5. **Two sets of numbers.** *Raw* is the track exactly as logged. *Cleaned*
   removes camp jitter, spikes and elevation noise. Both are kept, because the
   difference between them is information.

---

## 1. Belief: `Quality.filter`

Applied once, before anything else, by the app's GPX export, the dump summary,
and `DayCarver`. Checks run in this order and the first match decides:

| Check | Rejected as | On cdt_v2 | Why |
|---|---|---|---|
| no latitude/longitude | `NO_POSITION` | 0 | Nothing to place. |
| `Fix.isSentinel` (lat within 1e-6 of 90, lon exactly 0) | `SENTINEL` | 1 | The cold-start record. Its latitude is `89.9999999999996`, so `== 90.0` matches nothing; this is a tolerance test. |
| `VALID == NO_FIX` | `NO_FIX` | 9 | The chip says it had no solution. Believe it — but see §1.1. |
| `VALID == ESTIMATED` | `ESTIMATED` | 8 | Dead-reckoned, not measured. |
| elevation spike (§1.2) | `ELEVATION_SPIKE` | 56 | |

What is **not** a check: `height == 150.0`. That sentinel is real but it only
ever appears on the record that is already caught as `SENTINEL`, and testing
it alone rejects 16 genuine elevations. SPS fixes are **not** dropped either:
they are 1.8% of records and hold most spikes, but rejecting the class would
throw away 2,333 good points to catch 40.

The counts are surfaced, by reason, in the app's dump summary. Nothing is
dropped silently.

### 1.1 Button presses with no fix

A button press is the only data in the log a person chose to make. Seven of
the 1,419 presses on cdt_v2 are flagged NO FIX — pressed before the chip had a
solution, usually first thing in the morning.

**Rule:** a NO FIX press is kept when the nearest believed fix *before or
after* it is within **5 min** and **100 m**. (`Quality.MarkRescue`)

Why both sides, and why not just "close to the last fix": the first press of
a morning carries the chip's **stored** position from the night before. It
matches last night's final fix to the metre, 12–19 h earlier, and is no
evidence at all of where you were. The next real fix tells the truth:

| Press (UTC) | Previous believed fix | Next believed fix | Kept |
|---|---|---|---|
| 2025-03-17 18:10:30 | 19.4 h, 31 m | 32 s, **52 m** | yes |
| 2025-06-19 19:29:50 | 17 s, **5 m** | 21 s, 23 m | yes |
| 2025-06-19 19:29:51 | 18 s, **5 m** | 20 s, 23 m | yes |
| 2025-12-05 21:00:33 | 19.1 h, 0 m | 211 s, 1,010 m | no — stale |
| 2026-08-21 22:52:54 | 99 s, 5,838 km | 68 s, 5,838 km | no — 90N sentinel |
| 2026-08-22 13:52:39 | 11.8 h, 0 m | 43 s, 1,542 m | no — stale |
| 2026-08-24 14:56:44 | 14.1 h, 0 m | 21 s, 1,179 m | no — stale |

The three stale ones would need 5–56 m/s on foot to be right. The press itself
happened; its position is unknown, and a waypoint 1.5 km from where it was
pressed is worse than none.

Kept presses are counted in `Filtered.rescuedMarks` and still say
`<fix>none</fix>` in the GPX.

### 1.2 Elevation spikes

For each fix, the median elevation of a **5-sample window** (2 either side) is
taken; the fix is a spike if it is more than **25 m** from it (DGPS) or **12 m**
(any other quality). Fixes at the edges of the list, or with a missing height
anywhere in the window, are not tested. The window was validated against a
closed loop on the San Juan trip. On cdt_v2: 56 rejected.

---

## 2. Days: `DayCarver`

Before carving, believed fixes are **sorted by time**. Flash is a ring buffer;
after a wrap, parse order is not time order.

Three rules, applied in order. Each later rule only runs where the earlier one
found nothing.

### 2.1 The overnight gap — 4 h

If the logger recorded nothing for **4 h or more**, the day ends there. This
needs no time zone, on a trail that crosses them, and on cdt_v2 it finds 66 of
the 69 night boundaries by itself.

### 2.2 A night logged through — the middle of the stop

Within a stretch with no 4 h gap, find every **stop**: a run of fixes that
stays within **50 m** of where it began, for at least **6 h**. Cut at the
first fix past the middle of each one.

- **6 h, not 4:** a logger left on through a town stop or a storm sits still
  for hours by day. The CDT's logged-through nights were 12–14 h of under 6 m
  of drift.
- **Only with ≥ 1 h logged on both sides of the stop.** A stop at the edge of
  a stretch is camp before switching off or after switching on — not a night
  *between* two days (CDT 2025-07-29, 2025-03-16).
- **All stops in one pass.** Cutting one, then re-measuring the halves, left
  half a night inside the evening and cut it again — a 4.8 h sliver of camp
  became its own day (CDT 2025-07-17).

### 2.3 Last resort — solar midnight

Only if a stretch is longer than **20 h** and has no qualifying stop. It is cut
where the date changes in **solar time**: UTC plus 4 minutes per degree of
longitude east, from each fix's own longitude. At 105° W that is 07:00 UTC.

UTC midnight was used until 2026-10-07; it falls at 18:00 in Colorado, the
middle of the evening on the trail. Solar midnight is within about an hour of
the clock anywhere in the lower 48 and still needs no zone table. cdt_v2 never
reaches this rule.

### 2.4 Numbering

Days are numbered 1, 2, 3… in the order the dump has data. A day with the
logger off gets no number, so "Day 12" here is not trail day 12 if there were
zero days.

---

## 3. Segments and gaps

Inside a day, a time gap of **more than 300 s** between consecutive fixes
splits a segment. The gap is recorded (`Day.gaps`, with its length) and
**never bridged**: no distance, no climb, no moving time is computed across
it. On a 2026-09-25 dump, most of the naive distance on three ride days was
single straight-line steps across the logger being off — one of them 17.5 km.

300 s matches the GPX export's segment split, so a day's segments and the
GPX's `<trkseg>`s agree. On cdt_v2 the 79 GPX segments are power cycles, not
format changes (`gnss-and-device-internals.md` §10.4).

Switching between walking and riding (§4) also starts a new segment, but is
**not** a gap — nothing is missing.

---

## 4. Rides

**Rule:** a fix is *fast* when the logger's own speed field reads **20 km/h or
more**. With no speed field, the speed implied by the step from the previous
fix stands in. A **ride** is a run of fast fixes that lasts at least **60 s**
and holds at least **3 fixes**. Fast runs separated by **3 min or less** —
a stoplight, a junction — merge into one ride, slow fixes included.
(`DayCarver.rideRanges`)

- **The speed field, not the positions.** Speed comes from Doppler, not from
  differencing positions (`gnss-and-device-internals.md` §7), so a GPS spike
  that jumps 400 m while standing still reads ~0 km/h and is not a ride.
- **20 km/h:** nobody walks at 20 km/h for a minute. The slowest ride on cdt_v2,
  into town on 2025-08-20, ran at 31.
- **60 s and 3 fixes:** a single fast step is a spike or a glitch, not a ride.

A ride becomes a `Mode.VEHICLE` segment with its own figures in
`Day.rideStats`. **The day's walking figures never include it.** In GPX it is a
separate track named "*name* (rides)", in both the app's export and
`mtkparse.py`.

On cdt_v2: **5 rides, 227 fixes, 98.9 km** —

| Day | Start (UTC) | Length | Distance | Top speed |
|---|---|---|---|---|
| 20 | 2025-07-08 01:11 | 16 min | 29.1 km | 123 km/h |
| 29 | 2025-07-17 16:06 | 4 min | 6.3 km | 97 km/h |
| 29 | 2025-07-17 18:12 | 3 min | 1.9 km | 57 km/h |
| 41 | 2025-07-29 21:03 | 20 min | 21.4 km | 91 km/h |
| 51 | 2025-08-20 17:13 | 29 min | 40.2 km | 108 km/h |

Before this rule, the 2025-08-20 ride booked 44 km of "walking" in 1.4 h, and
`mtkparse.py`'s teleport filter dropped five of every six ride fixes, turning
rides into straight lines two minutes apart.

---

## 5. What gets measured

Per segment, then summed per day (walking into `stats`, rides into
`rideStats`). Raw and cleaned are both kept: raw is the track exactly as
logged; cleaned answers "how far did I walk".

| Figure | Raw | Cleaned |
|---|---|---|
| Distance | every step, great-circle (R = 6,371,000 m, same as `mtkparse.py`) | only movement **≥ 10 m** from the last *counted* point; on foot, any step faster than **8 m/s** is skipped and counting resumes from where it landed |
| Moving time | — | time between counted points, but if that is over **60 s** only the latest step's interval counts — otherwise the first stride out of camp books the whole night |
| Climb / descent | every up and every down | only once elevation has moved **≥ 5 m** from the last counted level |

Why each:

- **10 m jitter:** a stationary receiver wanders. Camp jitter of 4 m back and
  forth, logged every 10 s, makes kilometres of raw distance and zero of
  walking.
- **8 m/s on foot:** a spike's out-and-back legs are both fast and both
  skipped. On a ride the cap is off — a ride is measured as driven.
- **60 s moving window:** CDT 2025-11-26 read 17.8 h moving in an 18 h day
  that began with eight hours of camp.
- **5 m hysteresis:** ±3 m elevation noise every fix turns into hundreds of
  metres of phantom climb.

On cdt_v2, walking only:

| | Raw | Cleaned |
|---|---|---|
| Distance | 1,578.2 km | 1,543.5 km (959 mi) |
| Moving time | — | 372.0 h |
| Climb | 94,049 m | 75,762 m |
| Descent | — | 68,863 m |

The device's own `DISTANCE` field is never used: it resets on power cycle and
its positive deltas sum to 19,082 mi (`gnss-and-device-internals.md` §11.2).

---

## 6. GPX output

Both writers emit **button presses as `<wpt>`**, then a walking `<trk>`, then a
rides `<trk>` if there were any, with `<trkseg>` split at gaps over 300 s
(`mtkparse.py --gap`).

**The app (`GpxWriter`)**

- Coordinates and elevation at **full precision**, as plain decimals. An
  earlier exporter's `%.1f` / `%.6f` rounding manufactured phantom sentinels.
  Exponent form (`5.0E-5`) is not valid `xsd:decimal`, so numbers are printed
  as the same digits without one.
- `<fix>`, `<sat>`, `<hdop>`, `<vdop>` from the record; speed and the record
  reason (`RCR`) as `btd:` extensions in `urn:thru.taxi.traxi:gpx-extensions:1`.
- A non-finite height omits `<ele>` for that point; a non-finite coordinate
  omits the point.
- Every export is validated against the official GPX 1.1 schema in
  `GpxSchemaTest`, including the full cdt_v2 export.

**`mtkparse.py --gpx`**

- Cleans by default; `--no-clean` writes everything as logged.
- `--from` / `--to` restrict to a UTC window; `--name` sets the track name
  (XML-escaped).
- Writes 6 decimal places of latitude/longitude and 0.1 m of elevation, and
  no extensions.

---

## 7. Where the app and `mtkparse.py` differ

The ride rule and the NO FIX press rule are the same in both, and on cdt_v2
they agree exactly: 5 rides of 227 fixes, 3 presses kept, 1,415 waypoints.
The rest differs, and these are the known differences:

| | App (`Quality`, `DayCarver`, `GpxWriter`) | `mtkparse.py` |
|---|---|---|
| Elevation spikes | 5-sample median, 25 m DGPS / 12 m other → **56** | 11-sample median, 60 m any → **40** |
| Spikes on foot | **kept** in the GPX track; skipped only in cleaned distance | **dropped** from the output: > 8 m/s over a gap < 120 s from the last kept walking fix → **36** |
| Sentinel | its own check | caught as NO FIX (the record is flagged NO FIX) |
| Precision | full | 6 dp / 0.1 m |
| Days | `DayCarver` | none — one walking track, one rides track |

`mtkparse.py` is the reference the parser's golden counts were measured
against; those counts are taken **before** cleaning and are unaffected by any
of the above.

---

## 8. Reference figures on cdt_v2.bin

| | |
|---|---|
| Fixes parsed | 126,613 |
| Believed (app) | 126,539 |
| Rejected (app) | 1 sentinel, 9 NO FIX, 8 ESTIMATED, 56 elevation spikes |
| NO FIX presses kept | 3 of 7 |
| Waypoints | 1,415 of 1,419 presses |
| GPX segments (300 s) | 79 |
| Days | 70 (67 from overnight gaps alone, 3 more from logged-through nights) |
| Segments within days | 92 (87 walking, 5 rides), 12 in-day gaps |
| Longest day, end to end | 23.3 h (2025-07-18 — it carries half a logged-through night's camp; 7 h of it is walking) |
| Most moving time in a day | 9.7 h |

---

## 9. Where it lives

| Piece | Code | Pinned by |
|---|---|---|
| Belief, press rescue, elevation spikes | `core/.../format/Quality.kt` | `QualityTest`, `GoldenFileTest` |
| Days, segments, gaps, rides, figures | `core/.../analysis/DayCarver.kt` | `DayCarverTest`, `CdtDaysTest` |
| GPX | `core/.../export/GpxWriter.kt` | `GpxSchemaTest`, `GpxWriterTest` |
| Python reference | `data/mtkparse.py` | output validated by hand against the vendored schema |

`DayCarver` is not yet shown anywhere in the app. The app's export uses
`Quality` and the ride rule, not the day carving.
