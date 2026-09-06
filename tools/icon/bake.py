"""Bake the SVG preview transforms into flat VectorDrawable path data.

The preview nests transforms on SVG groups. VectorDrawable <group> supports the
same operations but composes them in its own order and is inconsistent about
negative scale across renderers, so nothing here relies on it: every point is
transformed in Python and emitted as absolute coordinates in the 108 viewport.
"""
import math
import re

FORE = ("M6.5,23.2 C4.8,18.5 3.6,13 4.0,8.8 C4.5,3.2 7.2,0 11,0 "
        "C15.2,0 18.8,3.4 18.9,8.6 C19.0,13.4 18.1,17.6 17.2,20.9 Z")
HEEL = ("M7.6,27.6 L18.1,25.2 C19.4,28.4 19.2,32.4 17.4,34.6 "
        "C15.4,37.1 10.2,37.4 7.8,35.2 C6.0,33.6 6.2,30.0 7.6,27.6 Z")

CELL = 6.5
TREAD = [(1.5, -1), (14.5, -1), (8, 5.5), (1.5, 12), (14.5, 12),
         (8, 18.5), (1.5, 25), (14.5, 25), (8, 31.5)]

# Scale and offset come from fit.py, which samples the actual curves rather
# than the bounding box: a rounded sole never reaches its box corners, so the
# box measurement left 27% of the safe circle unused. Offset 11 with scale
# 0.94 puts the worst real point at 32.4 against a 33 safe circle.
SCALE = 0.94
PIVOT = (11.5, 18.7)
MIRROR_AXIS = 23.0          # mirror about x = 11.5

# ascending: left print low at (43,65), right print high at (65,43)
LEFT = dict(translate=(32.19, 47.42), rot=-8, mirror=True)
RIGHT = dict(translate=(54.19, 25.42), rot=8, mirror=False)


def make(spec):
    """Return a point transformer matching the SVG transform list exactly:
    translate . scale . rotate(pivot) [. translate(23,0) . scale(-1,1)]"""
    tx, ty = spec["translate"]
    a = math.radians(spec["rot"])
    px, py = PIVOT

    def f(x, y):
        if spec["mirror"]:
            x = MIRROR_AXIS - x
        dx, dy = x - px, y - py
        x = px + dx * math.cos(a) - dy * math.sin(a)
        y = py + dx * math.sin(a) + dy * math.cos(a)
        return tx + x * SCALE, ty + y * SCALE

    return f


TOKEN = re.compile(r"([MLCZ])|(-?\d+\.?\d*)")


def transform_path(d, f):
    """Rewrite an absolute M/L/C/Z path through f. Every numeric pair in these
    commands is a point, control points included, so a cubic stays a cubic."""
    toks = [(c, n) for c, n in TOKEN.findall(d)]
    out, nums, cmd = [], [], None

    def flush():
        for i in range(0, len(nums), 2):
            x, y = f(nums[i], nums[i + 1])
            out.append(f"{x:.2f},{y:.2f}")

    for c, n in toks:
        if c:
            if cmd:
                flush()
            nums = []
            cmd = c
            out.append(c)
        else:
            nums.append(float(n))
    if cmd:
        flush()
    # "M a,b C c,d e,f g,h" -> tokens already interleaved correctly
    s = ""
    for t in out:
        s += t if t in "MLCZ" else (" " + t)
    return s.replace("M ", "M").replace("L ", "L").replace("C ", "C").strip()


def tread_paths(f):
    out = []
    for x, y in TREAD:
        pts = [(x, y), (x + CELL, y), (x + CELL, y + CELL), (x, y + CELL)]
        pts = [f(px, py) for px, py in pts]
        out.append("M{:.2f},{:.2f} L{:.2f},{:.2f} L{:.2f},{:.2f} L{:.2f},{:.2f} Z"
                   .format(*[v for p in pts for v in p]))
    return out


blocks = {}
for name, spec in (("left", LEFT), ("right", RIGHT)):
    f = make(spec)
    blocks[name] = {
        "fore": transform_path(FORE, f),
        "heel": transform_path(HEEL, f),
        "tread": tread_paths(f),
    }

STROKE = 2.2 * SCALE

xml = ['<?xml version="1.0" encoding="utf-8"?>',
       '<!--',
       '  Two boot prints climbing, tread checkered: the trail and the trail name',
       '  in one shape. Geometry is generated, not hand-authored: regenerate with',
       '  tools/icon/bake.py rather than editing coordinates here by hand.',
       '',
       '  The tread is a real checkerboard clipped to the sole, not squares drawn',
       '  to fit it. VectorDrawable does not antialias a clip-path, so the sole',
       '  outline is stroked on top of the clip seam at 2.07 wide, which covers',
       '  the jagged edge with margin either side. Do not remove the outline',
       '  without replacing the clip.',
       '',
       '  The forefoot/heel gap is 4.4 in sole units against two 2.2 strokes: the',
       '  strokes already eat half of it. A heavier outline closes the sole into a',
       '  bare footprint and loses the boot.',
       '-->',
       '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
       '    android:width="108dp"',
       '    android:height="108dp"',
       '    android:viewportWidth="108"',
       '    android:viewportHeight="108">',
       '']

for name in ("left", "right"):
    b = blocks[name]
    label = "low print, left foot" if name == "left" else "high print, right foot"
    xml.append(f'    <!-- {label}: tread, clipped to the sole -->')
    xml.append('    <group>')
    xml.append(f'        <clip-path android:pathData="{b["fore"]} {b["heel"]}" />')
    for t in b["tread"]:
        xml.append(f'        <path android:pathData="{t}" android:fillColor="#F2E4C9" />')
    xml.append('    </group>')
    xml.append('')

xml.append('    <!-- sole outlines, drawn last so they cover the clip seams -->')
for name in ("left", "right"):
    b = blocks[name]
    for part in ("fore", "heel"):
        xml.append(f'    <path')
        xml.append(f'        android:pathData="{b[part]}"')
        xml.append(f'        android:strokeColor="#7FD4C1"')
        xml.append(f'        android:strokeWidth="{STROKE:.2f}"')
        xml.append(f'        android:strokeLineJoin="round"')
        xml.append(f'        android:fillColor="#00000000" />')
xml.append('</vector>')

print("\n".join(xml))
