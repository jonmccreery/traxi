#!/usr/bin/env python3
"""When did this logger stop recording?

Walks the flash the way the device wrote it -- sector headers, record sizes,
inline markers -- and reports every logging start/stop with the wall-clock time
of the fix that preceded it. A naive 2-byte scan for the AA..BB signature finds
the markers but gives garbage timestamps, because it latches onto payload bytes
instead of walking real records; this reuses mtkparse's record walk, which is
the same one that produced the golden-file GPX.

The fingerprint of the fault (record section 12) is a DISABLE that nothing
answers.
"""
import sys, struct, datetime as dt
sys.path.insert(0, "/home/taxi/btdroid/data")
import mtkparse as M

LOG_STATUS = 0x07
LOGGING_ENABLED = 0x02


def walk(path, rollover_weeks=1024):  # 1024 is verified against a known fix; mtkparse.parse() defaults to 2048, which lands in 2044
    data = open(path, "rb").read()
    shift = rollover_weeks * M.SECONDS_IN_WEEK
    events, offset, log_format = [], 0, None
    last_time = None
    fixes = 0

    while offset < len(data):
        if offset % M.SECTOR == 0:
            header = data[offset:offset + M.SECTOR_HEADER]
            if len(header) < M.SECTOR_HEADER:
                break
            if header[:M.SEPARATOR] == b"\xff" * M.SEPARATOR:
                offset += M.SECTOR
                continue
            count, fmt = M.parse_sector_header(header)
            if count is None:
                offset += M.SECTOR
                continue
            log_format = fmt
            offset += M.SECTOR_HEADER
            continue

        if log_format is None:
            offset += 1
            continue

        remaining = M.SECTOR - (offset % M.SECTOR)

        if remaining >= M.SEPARATOR and offset + M.SEPARATOR <= len(data):
            chunk = data[offset:offset + M.SEPARATOR]
            if chunk[:7] == b"\xaa" * 7 and chunk[-4:] == b"\xbb" * 4:
                t = chunk[7]
                arg = struct.unpack_from("<i", chunk, 8)[0]
                if t == 0x02:
                    log_format = arg & 0xFFFFFFFF
                if t == LOG_STATUS:
                    events.append({
                        "offset": offset,
                        "arg": arg,
                        "enable": bool(arg & LOGGING_ENABLED),
                        "after_fix": last_time,
                        "fix_index": fixes,
                    })
                offset += M.SEPARATOR
                continue
            if chunk == b"\xff" * M.SEPARATOR:
                offset = M.SECTOR * (offset // M.SECTOR + 1)
                continue

        try:
            payload_len = M.record_size(log_format)
        except ValueError:
            offset = M.SECTOR * (offset // M.SECTOR + 1)
            continue
        if payload_len == 0 or offset + payload_len + 2 > len(data):
            break
        if payload_len + 2 > remaining:
            offset = M.SECTOR * (offset // M.SECTOR + 1)
            continue

        payload = data[offset:offset + payload_len]
        star = data[offset + payload_len]
        cksum = data[offset + payload_len + 1]
        if star == 0x2A and M.xor(payload) == cksum:
            pos = 0
            for bit, name, width, code in M.FIELDS:
                if not (log_format & bit):
                    continue
                if name == "utc" and code:
                    utc = struct.unpack(code, payload[pos:pos + width])[0]
                    last_time = dt.datetime.fromtimestamp(utc + shift, dt.timezone.utc)
                pos += width
            fixes += 1
        offset += payload_len + 2

    return events, fixes, last_time


def report(path):
    events, fixes, last_time = walk(path)
    enables = sum(1 for e in events if e["enable"])
    disables = len(events) - enables
    print(f"== {path}")
    print(f"   {fixes:,} fixes, {len(events)} log-status markers "
          f"({disables} disable / {enables} enable)")
    if last_time:
        print(f"   last fix in image: {last_time:%Y-%m-%d %H:%M:%S} UTC")

    # A stop nothing answered *before the next stop* is the section 12
    # fingerprint. The trailing stop is reported separately and is NOT counted:
    # a dump captured while the device is powered down ends with a stop, and
    # that is completely normal -- treating it as a fault cries wolf on every
    # healthy image. Same rule as RecordingAudit.
    unanswered = []
    for i, e in enumerate(events):
        if e["enable"] or i + 1 >= len(events):
            continue
        if not events[i + 1]["enable"]:
            unanswered.append((e, events[i + 1]))
    print(f"   unanswered stops: {len(unanswered)}   <-- the section 12 fault")
    for e, nxt in unanswered:
        when = f"{e['after_fix']:%Y-%m-%d %H:%M:%S} UTC" if e["after_fix"] else "before any fix"
        print(f"     STOP at 0x{e['offset']:08X}, after a fix at {when} -- "
              f"next marker is another DISABLE at 0x{nxt['offset']:08X}")

    if events and not events[-1]["enable"]:
        when = (f"{events[-1]['after_fix']:%Y-%m-%d %H:%M:%S} UTC"
                if events[-1]["after_fix"] else "before any fix")
        print(f"   image ends on a STOP, after a fix at {when}")
        print("     (normal if the device was powered down; the matching start is")
        print("      written at the next power-on and is not in this image)")

    print("   last 10 markers:")
    for e in events[-10:]:
        when = f"{e['after_fix']:%Y-%m-%d %H:%M:%S}" if e["after_fix"] else "-"
        print(f"     0x{e['offset']:08X}  {'ENABLE ' if e['enable'] else 'DISABLE'}"
              f"  arg=0x{e['arg']:04X}  after fix {when}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        report(p)
        print()
