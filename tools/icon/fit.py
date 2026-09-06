"""Find the largest print scale that still clears the safe circle.

The earlier check used bounding-box corners, which a rounded sole never
reaches. This samples the actual curves instead, so the headroom it reports
is real rather than conservative.
"""
import math
import re

FORE = ("M6.5,23.2 C4.8,18.5 3.6,13 4.0,8.8 C4.5,3.2 7.2,0 11,0 "
        "C15.2,0 18.8,3.4 18.9,8.6 C19.0,13.4 18.1,17.6 17.2,20.9 Z")
HEEL = ("M7.6,27.6 L18.1,25.2 C19.4,28.4 19.2,32.4 17.4,34.6 "
        "C15.4,37.1 10.2,37.4 7.8,35.2 C6.0,33.6 6.2,30.0 7.6,27.6 Z")

PIVOT = (11.5, 18.7)
MIRROR = 23.0
CENTRE = (54.0, 54.0)
SAFE = 33.0


def flatten(d, steps=24):
    """On-curve sample points for an absolute M/L/C/Z path."""
    toks = re.findall(r"([MLCZ])|(-?\d+\.?\d*)", d)
    pts, nums, cmd, cur, start = [], [], None, None, None

    def emit():
        nonlocal cur, start
        if cmd == "M":
            cur = (nums[0], nums[1]); start = cur; pts.append(cur)
        elif cmd == "L":
            cur = (nums[0], nums[1]); pts.append(cur)
        elif cmd == "C":
            for i in range(0, len(nums), 6):
                p0 = cur
                p1, p2, p3 = (nums[i], nums[i+1]), (nums[i+2], nums[i+3]), (nums[i+4], nums[i+5])
                for k in range(1, steps + 1):
                    t = k / steps
                    u = 1 - t
                    x = u**3*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t**3*p3[0]
                    y = u**3*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t**3*p3[1]
                    pts.append((x, y))
                cur = p3
        elif cmd == "Z":
            pts.append(start)

    for c, n in toks:
        if c:
            if cmd:
                emit()
            nums, cmd = [], c
        else:
            nums.append(float(n))
    if cmd:
        emit()
    return pts


LOCAL = flatten(FORE) + flatten(HEEL)


def worst(scale, offset):
    """Max distance from icon centre, including half the stroke."""
    half = 2.2 * scale / 2
    lcx = sum(p[0] for p in LOCAL) / len(LOCAL)   # unused, kept explicit below
    worst_d = 0.0
    for mirror, (cx, cy) in ((True, (54 - offset, 54 + offset)),
                             (False, (54 + offset, 54 - offset))):
        rot = math.radians(-8 if mirror else 8)
        for x, y in LOCAL:
            if mirror:
                x = MIRROR - x
            dx, dy = x - PIVOT[0], y - PIVOT[1]
            rx = dx * math.cos(rot) - dy * math.sin(rot)
            ry = dx * math.sin(rot) + dy * math.cos(rot)
            # place the print's own centroid box centre at (cx, cy)
            px = cx + (rx - 0.0) * scale
            py = cy + (ry - 0.0) * scale
            worst_d = max(worst_d, math.hypot(px - CENTRE[0], py - CENTRE[1]))
    return worst_d + half


print("offset  scale   worst   headroom to 33")
for offset in (12, 11, 10):
    lo, hi = 0.5, 1.6
    for _ in range(60):
        mid = (lo + hi) / 2
        if worst(mid, offset) > SAFE:
            hi = mid
        else:
            lo = mid
    print("  %2d    %.3f   %.2f    (current 0.750 -> %.0f%% larger)"
          % (offset, lo, worst(lo, offset), (lo / 0.75 - 1) * 100))
