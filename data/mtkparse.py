#!/usr/bin/env python3
"""
Standalone parser for MTK GPS logger binary dumps (Transystem / Qstarz / Holux).

Walks 64 KiB sectors, reads each sector's own log-format bitmask, handles the
AA*7 + type + arg32 + BB*4 dynamic markers (including 0x02 format-change),
validates the per-record XOR checksum, and applies GPS week-rollover
correction.

Field layout and semantics follow mtkbabel 0.8.4 (Niccolo Rigacci, GPL-2).

Usage:
    mtkparse.py DUMP.bin [--rollover N] [--gpx OUT.gpx] [--gap SECONDS]
    mtkparse.py DUMP.bin --compare OTHER.bin

    --rollover N   Add N weeks to every timestamp. This device needs 1024
                   (one 1024-week rollover) -- verified against trip.bin:
                   1024 reproduces the Chief Mountain fix at 48.993/-113.658
                   on 2025-06-16, while 2048 lands in 2044. Default 1024.
    --gpx OUT      Also write a GPX track file.
    --gap SECONDS  Split GPX track segments on gaps this long. Default 300.
    --compare OTH  Byte-compare the common prefix with another dump and exit.
"""

import argparse
import datetime as dt
import struct
import sys

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


def write_gpx(fixes, path, gap_seconds):
    segments = []
    current = []
    prev = None
    for f in fixes:
        if "lat" not in f or "lon" not in f:
            continue
        if prev is not None and (f["time"] - prev).total_seconds() > gap_seconds:
            if current:
                segments.append(current)
            current = []
        current.append(f)
        prev = f["time"]
    if current:
        segments.append(current)

    with open(path, "w") as fh:
        fh.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        fh.write('<gpx version="1.1" creator="mtkparse.py" '
                 'xmlns="http://www.topografix.com/GPX/1/1">\n')
        fh.write("<trk><name>MTK log</name>\n")
        for seg in segments:
            fh.write("<trkseg>\n")
            for f in seg:
                fh.write(f'<trkpt lat="{f["lat"]:.6f}" lon="{f["lon"]:.6f}">')
                if "height" in f:
                    fh.write(f"<ele>{f['height']:.1f}</ele>")
                fh.write(f"<time>{f['time'].strftime('%Y-%m-%dT%H:%M:%SZ')}</time>")
                fh.write("</trkpt>\n")
            fh.write("</trkseg>\n")
        fh.write("</trk>\n</gpx>\n")
    print(f"Wrote {path}: {sum(len(s) for s in segments)} fixes, "
          f"{len(segments)} segments")


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
    args = ap.parse_args()

    if args.compare:
        sys.exit(compare(args.compare, args.dump))

    st, fixes = parse(args.dump, args.rollover)
    summarize(args.dump, st, fixes)
    if args.gpx:
        write_gpx(fixes, args.gpx, args.gap)


if __name__ == "__main__":
    main()
