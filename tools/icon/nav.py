"""Render the navigation-bar icons so they can be looked at before shipping.

These five live as stroke paths in app/src/main/kotlin/thru/taxi/traxi/ui/
NavIcons.kt. Unlike the launcher icon, whose geometry bake.py computes, these
are small enough to author by hand -- but not to *judge* by hand: at 24 px the
difference between a stack of plates and a grey smear is about half a pixel of
gap, and the first draft of Dumps lost it.

So this is a viewer, not a generator. Paths are copied from NavIcons.kt;
running it renders each icon at true size and at 4x, light and dark, into
build/navicons/ for inspection.

    python3 tools/icon/nav.py && xdg-open build/navicons/strip-dark.png

Requires rsvg-convert (librsvg) and ImageMagick, both of which the SVG
documentation targets already need.
"""
import pathlib
import subprocess
import sys

STROKE = 2.0
GRID = 24

# Keep in step with NavIcons.kt. Order is the order of the tabs.
ICONS = {
    "Device": [
        "M12,1.5 V4",
        "M8,4 h8 a2,2 0 0 1 2,2 v12 a2,2 0 0 1 -2,2 h-8 a2,2 0 0 1 -2,-2 v-12 a2,2 0 0 1 2,-2 z",
        "M9.5,8.5 h5",
        "M9.5,12 h5",
    ],
    "Live": [
        "M17,12 A5,5 0 1 1 7,12 A5,5 0 1 1 17,12",
        "M12,2 V5", "M12,19 V22", "M2,12 H5", "M19,12 H22",
        "M13.4,12 A1.4,1.4 0 1 1 10.6,12 A1.4,1.4 0 1 1 13.4,12",
    ],
    "Dumps": [
        "M5.5,4.5 h13 a1.25,1.25 0 0 1 0,2.5 h-13 a1.25,1.25 0 0 1 0,-2.5 z",
        "M5.5,10.75 h13 a1.25,1.25 0 0 1 0,2.5 h-13 a1.25,1.25 0 0 1 0,-2.5 z",
        "M5.5,17 h13 a1.25,1.25 0 0 1 0,2.5 h-13 a1.25,1.25 0 0 1 0,-2.5 z",
    ],
    "Config": [
        "M4,8 H20", "M4,16 H20",
        "M11,8 A2,2 0 1 1 7,8 A2,2 0 1 1 11,8",
        "M17,16 A2,2 0 1 1 13,16 A2,2 0 1 1 17,16",
    ],
    "Log": [
        "M5,4 h14 a2,2 0 0 1 2,2 v12 a2,2 0 0 1 -2,2 h-14 a2,2 0 0 1 -2,-2 v-12 a2,2 0 0 1 2,-2 z",
        "M7.5,9.5 L10,12 L7.5,14.5",
        "M12.5,15 h4",
    ],
}

# Theme.kt: onSurface / surface, dark then light.
THEMES = {"dark": ("#EDE8DE", "#1E1B15"), "light": ("#221E1A", "#F2EFE9")}


def svg(paths, fg, bg):
    body = "".join(
        f'<path d="{d}" fill="none" stroke="{fg}" stroke-width="{STROKE}"'
        f' stroke-linecap="round" stroke-linejoin="round"/>' for d in paths
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {GRID} {GRID}">'
        f'<rect width="{GRID}" height="{GRID}" fill="{bg}"/>{body}</svg>'
    )


def run(cmd):
    if subprocess.run(cmd).returncode != 0:
        sys.exit(f"failed: {' '.join(str(c) for c in cmd)}")


def main():
    out = pathlib.Path(__file__).resolve().parents[2] / "build" / "navicons"
    out.mkdir(parents=True, exist_ok=True)

    for theme, (fg, bg) in THEMES.items():
        tiles = []
        for name, paths in ICONS.items():
            src = out / f"{name}-{theme}.svg"
            src.write_text(svg(paths, fg, bg))
            # True size is the only size that settles whether it reads.
            png = out / f"{name}-{theme}.png"
            run(["rsvg-convert", "-w", "24", "-h", "24", str(src), "-o", str(png)])
            tiles.append(str(png))

        strip = out / f"strip-{theme}.png"
        run(["magick", "montage", "-background", bg,
             "-geometry", "24x24+6+6", "-tile", f"{len(tiles)}x1",
             *tiles, str(strip)])
        # Nearest-neighbour: blur would flatter the gaps this is meant to test.
        run(["magick", str(strip), "-filter", "point", "-resize", "400%",
             str(out / f"strip-{theme}-4x.png")])

    print(f"wrote {out}")


if __name__ == "__main__":
    main()
