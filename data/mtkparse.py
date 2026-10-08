#!/usr/bin/env python3
"""
Standalone parser for MTK GPS logger binary dumps (Transystem / Qstarz / Holux).

Walks 64 KiB sectors, reads each sector's own log-format bitmask, handles the
AA*7 + type + arg32 + BB*4 dynamic markers (including 0x02 format-change),
validates the per-record XOR checksum, and applies GPS week-rollover
correction.

Field layout and semantics follow mtkbabel 0.8.4 (Niccolo Rigacci, GPL-2).

Fixes are cleaned by default: records the chip flagged NO FIX or ESTIMATED,
cold-start altitude sentinels, and position teleports while on foot are
dropped before anything is written. A NO FIX button press is kept when a real
fix within 5 minutes either side is within 100 m of it. Sustained vehicle
speed is a ride, written as its own GPX track rather than dropped. These
rules match the app (Quality.kt, DayCarver.kt). Pass --no-clean to skip all
of it.

Usage:
    mtkparse.py DUMP.bin [--rollover N] [--gpx OUT.gpx] [--gap SECONDS]
                         [--from DATE] [--to DATE] [--name NAME] [--no-clean]
    mtkparse.py DUMP.bin --compare OTHER.bin

    --rollover N   Add N weeks to every timestamp. This device needs 1024
                   (one 1024-week rollover) -- verified against trip.bin:
                   1024 reproduces the Chief Mountain fix at 48.993/-113.658
                   on 2025-06-16, while 2048 lands in 2044. Default 1024.
    --gpx OUT      Also write a GPX track file.
    --gap SECONDS  Split GPX track segments on gaps this long. Default 300.
    --compare OTH  Byte-compare the common prefix with another dump and exit.
    --from DATE    Drop fixes before this UTC date (YYYY-MM-DD or ISO stamp).
    --to DATE      Drop fixes at or after this UTC date.
    --name NAME    Track name written into the GPX.
    --no-clean     Disable sentinel/teleport filtering.
"""

import argparse
import datetime as dt
import math
import statistics
import struct
import sys
from xml.sax.saxutils import escape

SECTOR = 0x10000
SECTOR_HEADER = 0x200
SEPARATOR = 0x10
SECONDS_IN_WEEK = 7 * 24 * 3600

# Log format bitmask -> (name, byte width, struct code or None)
FIELDS = [
    (0x00000001, "utc",         4, "<I"),
    (0x00000002, "valid",       2, "<H"),
    (0x00000004, "lat",         8, "<d"),
    (0x00000008, "lon",         8, "<d"),
    (0x00000010, "height",      4, "<f"),
    (0x00000020, "speed",       4, "<f"),
    (0x00000040, "heading",     4, "<f"),
    (0x00000080, "dsta",        2, "<H"),
    (0x00000100, "dage",        4, "<I"),
    (0x00000200, "pdop",        2, "<H"),
    (0x00000400, "hdop",        2, "<H"),
    (0x00000800, "vdop",        2, "<H"),
    (0x00001000, "nsat",        2, None),
    (0x00002000, "sid",         0, None),   # variable length, unsupported
    (0x00004000, "elevation",   2, "<H"),
    (0x00008000, "azimuth",     2, "<H"),
    (0x00010000, "snr",         2, "<H"),
    (0x00020000, "rcr",         2, "<H"),
    (0x00040000, "millisecond", 2, "<H"),
    (0x00080000, "distance",    8, "<d"),
]

FIELD_NAMES = {bit: name for bit, name, _, _ in
               ((b, n, w, c) for b, n, w, c in FIELDS)}


# Fix quality, as reported in the two-byte "valid" field.
VALID_NAMES = {
    0x0001: "NO FIX", 0x0002: "SPS",     0x0004: "DGPS",  0x0008: "PPS",
    0x0010: "RTK",    0x0020: "FRTK",    0x0040: "ESTIM", 0x0080: "MANUAL",
    0x0100: "SIM",
}
VALID_NO_FIX = 0x0001
VALID_ESTIMATED = 0x0040
RCR_BUTTON = 0x0008

# Cold-start sentinels. Before the chip has a solution it emits 90N/0E as its
# position and ~150 m as its altitude, and flags the record NO FIX or SPS. That
# flag was never read, so the sentinels reached the GPX as real trackpoints:
# 90N/0E sits 5838 km from Colorado, and each altitude sentinel invents roughly
# 3 km of ascent at every power-on.
ALT_MEDIAN_WINDOW = 11      # samples, centred
ALT_MEDIAN_TOLERANCE = 60   # metres from the local median before a point is bogus
MAX_GROUND_SPEED = 8.0      # m/s between consecutive fixes (28.8 km/h)
MAX_SPEED_GAP = 120         # only speed-gate across gaps shorter than this

# A NO FIX press is kept when a believed fix this close in time vouches for it.
# Either side: the first press of a morning precedes the first fix, and its
# position is the chip's stored one from last night (Quality.MarkRescue).
MARK_WITNESS_METRES = 100.0
MARK_WITNESS_SECONDS = 300

# Rides (DayCarver.Config). The logger's own speed field, in km/h, is the
# witness; a lone fast step is a spike, not a ride.
RIDE_MIN_KMH = 20.0
RIDE_MIN_SECONDS = 60
RIDE_MIN_FIXES = 3
RIDE_BRIDGE_SECONDS = 180


def haversine(lat1, lon1, lat2, lon2):
    """Great-circle distance in metres."""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def describe_format(mask):
    return ",".join(name.upper() for bit, name, _, _ in FIELDS if mask & bit)


def record_size(mask):
    """Payload width in bytes for a given format bitmask, excluding '*' + cksum."""
    total = 0
    for bit, name, width, _ in FIELDS:
        if mask & bit:
            if name == "sid":
                raise ValueError("SID/satellite records are not supported")
            total += width
    return total


def parse_sector_header(buf):
    """Return (record_count, log_format) or (None, None) if the header is invalid."""
    if len(buf) < SECTOR_HEADER:
        return None, None
    if buf[-6:-5] != b"*" or buf[-4:] != b"\xbb" * 4:
        return None, None
    count = struct.unpack_from("<H", buf, 0)[0]
    fmt = struct.unpack_from("<I", buf, 2)[0]
    return count, fmt


def xor(data):
    c = 0
    for b in data:
        c ^= b
    return c


class Stats:
    def __init__(self):
        self.records = 0
        self.checksum_failures = 0
        self.sectors = 0
        self.format_changes = []
        self.separators = 0
        self.bad_sector_headers = []
        self.unwritten_sectors = []


def parse(path, rollover_weeks=2048):
    with open(path, "rb") as fh:
        data = fh.read()

    st = Stats()
    fixes = []
    offset = 0
    shift = rollover_weeks * SECONDS_IN_WEEK
    log_format = None
    expected_in_sector = 0
    seen_in_sector = 0

    while offset < len(data):
        # --- sector boundary -------------------------------------------------
        if offset % SECTOR == 0:
            sec_index = offset // SECTOR
            header = data[offset:offset + SECTOR_HEADER]
            if len(header) < SECTOR_HEADER:
                break
            if header[:SEPARATOR] == b"\xff" * SEPARATOR:
                st.unwritten_sectors.append(sec_index)
                offset += SECTOR
                continue
            count, fmt = parse_sector_header(header)
            if count is None:
                st.bad_sector_headers.append(sec_index)
                offset += SECTOR
                continue
            st.sectors += 1
            log_format = fmt
            expected_in_sector = count
            seen_in_sector = 0
            offset += SECTOR_HEADER
            continue

        if log_format is None:
            offset += 1
            continue

        remaining_in_sector = SECTOR - (offset % SECTOR)

        # --- markers and padding --------------------------------------------
        if remaining_in_sector >= SEPARATOR and offset + SEPARATOR <= len(data):
            chunk = data[offset:offset + SEPARATOR]
            if chunk[:7] == b"\xaa" * 7 and chunk[-4:] == b"\xbb" * 4:
                sep_type = chunk[7]
                sep_arg = struct.unpack_from("<i", chunk, 8)[0]
                st.separators += 1
                if sep_type == 0x02:  # change log bitmask
                    log_format = sep_arg & 0xFFFFFFFF
                    st.format_changes.append((offset, log_format))
                offset += SEPARATOR
                continue
            if chunk == b"\xff" * SEPARATOR:
                # Unwritten space: skip to the next sector.
                offset = SECTOR * (offset // SECTOR + 1)
                continue

        # --- a log record ----------------------------------------------------
        try:
            payload_len = record_size(log_format)
        except ValueError as exc:
            print(f"  ! {exc} at offset 0x{offset:08X}", file=sys.stderr)
            offset = SECTOR * (offset // SECTOR + 1)
            continue

        if payload_len == 0 or offset + payload_len + 2 > len(data):
            break
        if payload_len + 2 > remaining_in_sector:
            offset = SECTOR * (offset // SECTOR + 1)
            continue

        payload = data[offset:offset + payload_len]
        star = data[offset + payload_len]
        cksum = data[offset + payload_len + 1]

        rec = {}
        pos = 0
        for bit, name, width, code in FIELDS:
            if not (log_format & bit):
                continue
            raw = payload[pos:pos + width]
            if code:
                rec[name] = struct.unpack(code, raw)[0]
            else:
                rec[name] = raw
            pos += width

        ok = (star == 0x2A) and (xor(payload) == cksum)
        if not ok:
            st.checksum_failures += 1

        st.records += 1
        seen_in_sector += 1

        if ok and "utc" in rec:
            rec["time"] = dt.datetime.fromtimestamp(rec["utc"] + shift, dt.timezone.utc)
            fixes.append(rec)

        offset += payload_len + 2

    return st, fixes


def summarize(path, st, fixes):
    print(f"File:                {path}")
    print(f"Sectors with data:   {st.sectors}")
    if st.unwritten_sectors:
        print(f"Unwritten sectors:   {len(st.unwritten_sectors)} "
              f"(first: {st.unwritten_sectors[0]})")
    if st.bad_sector_headers:
        print(f"Bad sector headers:  {st.bad_sector_headers}")
    print(f"Records parsed:      {st.records}")
    print(f"Checksum failures:   {st.checksum_failures}")
    print(f"Dynamic markers:     {st.separators}")
    for off, fmt in st.format_changes:
        print(f"  format change @ 0x{off:08X} -> 0x{fmt:08X} ({describe_format(fmt)})")

    if not fixes:
        print("No valid fixes decoded.")
        return

    times = [f["time"] for f in fixes]
    monotonic = all(b >= a for a, b in zip(times, times[1:]))
    print(f"Valid fixes:         {len(fixes)}")
    print(f"Time monotonic:      {monotonic}")
    print(f"Span start:          {times[0].isoformat()}")
    print(f"Span end:            {times[-1].isoformat()}")

    if not monotonic:
        backsteps = sum(1 for a, b in zip(times, times[1:]) if b < a)
        print(f"  ! {backsteps} backward time steps "
              f"(expected if the log wrapped in OVERLAP mode)")

    first = fixes[0]
    if "lat" in first:
        print(f"First fix position:  {first['lat']:.5f}, {first['lon']:.5f}")
        print(f"Last fix position:   {fixes[-1]['lat']:.5f}, {fixes[-1]['lon']:.5f}")


def is_mark(f):
    return bool((f.get("rcr") or 0) & RCR_BUTTON)


def believed(f):
    return "lat" in f and f.get("valid") not in (VALID_NO_FIX, VALID_ESTIMATED)


def witnessed(fixes, i):
    """Whether the nearest believed fix on either side vouches for fixes[i]."""
    mark = fixes[i]
    if "lat" not in mark:
        return False
    before = next((fixes[j] for j in range(i - 1, -1, -1) if believed(fixes[j])), None)
    after = next((fixes[j] for j in range(i + 1, len(fixes)) if believed(fixes[j])), None)
    for w in (before, after):
        if w is None:
            continue
        if (abs((w["time"] - mark["time"]).total_seconds()) <= MARK_WITNESS_SECONDS
                and haversine(w["lat"], w["lon"], mark["lat"], mark["lon"])
                <= MARK_WITNESS_METRES):
            return True
    return False


def split_on_gaps(fixes, gap_seconds):
    pieces, current = [], []
    for f in fixes:
        if current and (f["time"] - current[-1]["time"]).total_seconds() > gap_seconds:
            pieces.append(current)
            current = []
        current.append(f)
    if current:
        pieces.append(current)
    return pieces


def ride_ranges(fixes):
    """(first, last) index pairs of sustained vehicle speed, as DayCarver."""
    def fast(i):
        speed = fixes[i].get("speed")
        if speed is not None:
            return speed >= RIDE_MIN_KMH
        if i == 0:
            return False
        a, b = fixes[i - 1], fixes[i]
        dt = max((b["time"] - a["time"]).total_seconds(), 1)
        return haversine(a["lat"], a["lon"], b["lat"], b["lon"]) / dt * 3.6 >= RIDE_MIN_KMH

    runs = []
    i = 0
    while i < len(fixes):
        if not fast(i):
            i += 1
            continue
        j = i
        while j + 1 < len(fixes) and fast(j + 1):
            j += 1
        if runs and (fixes[i]["time"] - fixes[runs[-1][1]]["time"]).total_seconds() \
                <= RIDE_BRIDGE_SECONDS:
            runs[-1] = (runs[-1][0], j)
        else:
            runs.append((i, j))
        i = j + 1
    return [(a, b) for a, b in runs
            if b - a + 1 >= RIDE_MIN_FIXES
            and (fixes[b]["time"] - fixes[a]["time"]).total_seconds() >= RIDE_MIN_SECONDS]


def clean_fixes(fixes, gap_seconds=300):
    """Drop what is not believed; tag the rest foot or ride. Returns (kept, report)."""
    report = {"no_fix": 0, "estimated": 0, "rescued_marks": 0,
              "alt_sentinel": 0, "teleport": 0, "rides": 0, "ride_fixes": 0}

    # 1. The chip says it had no solution, or only an estimate. Believe it --
    #    except for a button press a real fix nearby vouches for.
    stage = []
    for i, f in enumerate(fixes):
        if f.get("valid") == VALID_NO_FIX:
            if is_mark(f) and witnessed(fixes, i):
                report["rescued_marks"] += 1
                stage.append(f)
            else:
                report["no_fix"] += 1
        elif f.get("valid") == VALID_ESTIMATED:
            report["estimated"] += 1
        else:
            stage.append(f)

    # 2. Altitude far from its local neighbourhood is a sentinel, not terrain.
    heights = [f.get("height") for f in stage]
    half = ALT_MEDIAN_WINDOW // 2
    kept = []
    for i, f in enumerate(stage):
        h = f.get("height")
        if h is not None:
            window = [x for x in heights[max(0, i - half):i + half + 1]
                      if x is not None]
            if window and abs(h - statistics.median(window)) > ALT_MEDIAN_TOLERANCE:
                report["alt_sentinel"] += 1
                continue
        kept.append(f)

    # 3. Sustained vehicle speed is a ride: tagged, never dropped.
    positioned = [f for f in kept if "lat" in f]
    for f in positioned:
        f["mode"] = "foot"
    for piece in split_on_gaps(positioned, gap_seconds):
        for a, b in ride_ranges(piece):
            report["rides"] += 1
            report["ride_fixes"] += b - a + 1
            for f in piece[a:b + 1]:
                f["mode"] = "ride"

    # 4. On foot, a step at an impossible speed over a short gap is a spike.
    #    The anchor resets at every ride, so the walk after one is not
    #    measured against the walk before it.
    out = []
    prev = None
    for f in kept:
        if "lat" not in f:
            out.append(f)
            continue
        if f["mode"] == "ride":
            prev = None
            out.append(f)
            continue
        if prev is not None:
            gap = (f["time"] - prev["time"]).total_seconds()
            if 0 < gap < MAX_SPEED_GAP:
                d = haversine(prev["lat"], prev["lon"], f["lat"], f["lon"])
                if d / gap > MAX_GROUND_SPEED:
                    report["teleport"] += 1
                    continue
        prev = f
        out.append(f)

    return out, report


def window_fixes(fixes, start, end):
    """Keep fixes in [start, end). Either bound may be None."""
    out = fixes
    if start:
        out = [f for f in out if f["time"] >= start]
    if end:
        out = [f for f in out if f["time"] < end]
    return out


def parse_date(text):
    """Accept YYYY-MM-DD or a full ISO timestamp; result is UTC."""
    try:
        stamp = dt.datetime.fromisoformat(text)
    except ValueError:
        raise argparse.ArgumentTypeError(
            f"{text!r} is not YYYY-MM-DD or an ISO timestamp")
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=dt.timezone.utc)
    return stamp


def quality_report(fixes):
    counts = {}
    for f in fixes:
        counts[f.get("valid")] = counts.get(f.get("valid"), 0) + 1
    total = len(fixes) or 1
    parts = []
    for value, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        parts.append(f"{VALID_NAMES.get(value, hex(value or 0))} {100 * n / total:.1f}%")
    return "  ".join(parts)


def write_gpx(fixes, path, gap_seconds, name="MTK log"):
    """Walking and rides as two tracks; button presses as waypoints."""
    tracks = {"foot": [], "ride": []}
    current = []
    for f in fixes:
        if "lat" not in f or "lon" not in f:
            continue
        mode = f.get("mode", "foot")
        if current and ((f["time"] - current[-1]["time"]).total_seconds() > gap_seconds
                        or current[-1].get("mode", "foot") != mode):
            tracks[current[-1].get("mode", "foot")].append(current)
            current = []
        current.append(f)
    if current:
        tracks[current[-1].get("mode", "foot")].append(current)
    marks = [f for f in fixes if "lat" in f and is_mark(f)]

    def point(fh, tag, f):
        fh.write(f'<{tag} lat="{f["lat"]:.6f}" lon="{f["lon"]:.6f}">')
        if "height" in f:
            fh.write(f"<ele>{f['height']:.1f}</ele>")
        fh.write(f"<time>{f['time'].strftime('%Y-%m-%dT%H:%M:%SZ')}</time>")
        if tag == "wpt":
            fh.write("<sym>Flag</sym>")
        if f.get("valid") == VALID_NO_FIX:
            fh.write("<fix>none</fix>")
        fh.write(f"</{tag}>\n")

    with open(path, "w") as fh:
        fh.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        fh.write('<gpx version="1.1" creator="mtkparse.py" '
                 'xmlns="http://www.topografix.com/GPX/1/1">\n')
        for f in marks:
            point(fh, "wpt", f)
        for mode, title in (("foot", name), ("ride", f"{name} (rides)")):
            if not tracks[mode]:
                continue
            fh.write(f"<trk><name>{escape(title)}</name>\n")
            for seg in tracks[mode]:
                fh.write("<trkseg>\n")
                for f in seg:
                    point(fh, "trkpt", f)
                fh.write("</trkseg>\n")
            fh.write("</trk>\n")
        fh.write("</gpx>\n")
    print(f"Wrote {path}: {sum(len(s) for s in tracks['foot'])} walking fixes in "
          f"{len(tracks['foot'])} segments, {sum(len(s) for s in tracks['ride'])} "
          f"ride fixes in {len(tracks['ride'])}, {len(marks)} waypoints")


def compare(a_path, b_path):
    with open(a_path, "rb") as fh:
        a = fh.read()
    with open(b_path, "rb") as fh:
        b = fh.read()
    n = min(len(a), len(b))
    print(f"{a_path}: {len(a)} bytes")
    print(f"{b_path}: {len(b)} bytes")
    print(f"Common prefix:       {n} bytes ({n // SECTOR} full sectors)")
    if a[:n] == b[:n]:
        print("PREFIX MATCHES - the older dump is intact inside the newer one.")
        return 0
    for i in range(n):
        if a[i] != b[i]:
            print(f"MISMATCH at byte {i} (0x{i:08X}), sector {i // SECTOR}")
            print("  This means the log wrapped and overwrote already-recovered data.")
            return 1
    return 1


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dump")
    ap.add_argument("--rollover", type=int, default=1024,
                    help="weeks to add to timestamps (default 1024)")
    ap.add_argument("--gpx", help="also write this GPX file")
    ap.add_argument("--gap", type=int, default=300,
                    help="GPX segment split gap in seconds (default 300)")
    ap.add_argument("--compare", help="byte-compare against another dump and exit")
    ap.add_argument("--from", dest="start", type=parse_date, metavar="DATE",
                    help="drop fixes before this UTC date/timestamp")
    ap.add_argument("--to", dest="end", type=parse_date, metavar="DATE",
                    help="drop fixes at or after this UTC date/timestamp")
    ap.add_argument("--no-clean", dest="clean", action="store_false",
                    help="keep cold-start sentinels and teleports (old behaviour)")
    ap.add_argument("--name", default="MTK log", help="GPX track name")
    args = ap.parse_args()

    if args.compare:
        sys.exit(compare(args.compare, args.dump))

    st, fixes = parse(args.dump, args.rollover)
    summarize(args.dump, st, fixes)

    if args.start or args.end:
        before = len(fixes)
        fixes = window_fixes(fixes, args.start, args.end)
        print(f"Time window:         {before} -> {len(fixes)} fixes")

    if args.clean:
        kept, report = clean_fixes(fixes, args.gap)
        dropped = len(fixes) - len(kept)
        print(f"Cleaned:             {len(fixes)} -> {len(kept)} fixes "
              f"({dropped} dropped, {100 * dropped / (len(fixes) or 1):.2f}%)")
        print(f"  no-fix records:    {report['no_fix']}")
        print(f"  estimated:         {report['estimated']}")
        print(f"  altitude sentinel: {report['alt_sentinel']}")
        print(f"  teleports on foot: {report['teleport']}")
        print(f"Kept no-fix presses: {report['rescued_marks']} (a real fix within "
              f"{MARK_WITNESS_METRES:.0f} m vouches for each)")
        print(f"Rides:               {report['rides']} ({report['ride_fixes']} fixes, "
              "own GPX track)")
        fixes = kept
    else:
        print("Cleaned:             no (--no-clean); sentinels will reach the GPX")

    if fixes:
        print(f"Fix quality:         {quality_report(fixes)}")

    if args.gpx:
        write_gpx(fixes, args.gpx, args.gap, args.name)


if __name__ == "__main__":
    main()
