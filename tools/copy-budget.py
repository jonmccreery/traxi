"""Measure the prose the app puts on screen, and flag where it is growing back.

Written after a pass that found 1,574 words across the UI, 290 of them (a
fifth) restating the LOG/NAV slide-switch instruction in twelve different
wordings -- two of which landed on the same card, fifty-four words apart.

Length is not the fault in itself. The app talks to two readers: someone at a
trailhead in gloves who needs the verdict and the remedy, and someone who
disbelieves the app and wants to know how it knows. The second reader is
already served, very well, by the // comments and ENGINEERING-RECORD.md. When
that argument is also on the phone, the first reader pays for it.

So this reports rather than enforces. Three numbers matter:

  total      how much prose the screen carries at all
  over       strings past --max words, which are usually the app explaining
             itself rather than the user's situation
  echoes     phrases repeated across strings, which is the same instruction
             written twice and free to drift apart

    python3 tools/copy-budget.py
    python3 tools/copy-budget.py --max 25 --file app/.../ui/AppScreen.kt
"""
import argparse
import collections
import pathlib
import re
import sys

UI = "app/src/main/kotlin/thru/taxi/traxi/ui"
# NavIcons.kt is SVG path data, not prose, and scores 26 "words" a glyph.
SKIP = {"NavIcons.kt"}
ECHO_NGRAM = 5          # words; shorter than this is ordinary English
ECHO_MIN_HITS = 2


def strings(src):
    """On-screen copy only: comments stripped, concatenation runs joined."""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'^\s*//.*$', '', src, flags=re.M)

    out, spans = [], []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"(?:\s*\+\s*"((?:[^"\\]|\\.)*)")+', src):
        out.append("".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(0))))
        spans.append((m.start(), m.end()))
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', src):
        if any(a <= m.start() < b for a, b in spans):
            continue
        if len(m.group(1).split()) >= 8:
            out.append(m.group(1))
    return out


def words(text):
    """Word count with interpolations counted as the one value they render."""
    return len(re.sub(r'\$\{[^}]*\}|\$\w+|%[-\d.,]*[a-zA-Z]', 'X', text).split())


def normalise(text):
    text = re.sub(r'\$\{[^}]*\}|\$\w+|%[-\d.,]*[a-zA-Z]', ' ', text)
    return re.sub(r'[^a-z ]', ' ', text.lower()).split()


def echoes(copy):
    """Maximal phrases that appear in more than one string.

    Sliding windows over a shared sentence all report the same thing, so
    windows carrying the identical set of strings are chained back into the
    longest phrase they cover -- otherwise one repeated clause is reported
    eight times and buries the rest.

    An echo is a prompt to look, not a defect. Two mutually exclusive branches
    of the same `when` may well deserve parallel wording; this cannot tell
    them apart, and should not try.
    """
    seen = collections.defaultdict(set)
    for i, c in enumerate(copy):
        w = normalise(c)
        for j in range(len(w) - ECHO_NGRAM + 1):
            seen[(i, j)] = " ".join(w[j:j + ECHO_NGRAM])

    where = collections.defaultdict(set)
    for (i, _), phrase in seen.items():
        where[phrase].add(i)

    shared = {p: s for p, s in where.items() if len(s) >= ECHO_MIN_HITS}
    # Chain windows that step through the same strings: consecutive j offsets
    # within one string, carrying an identical hit-set, are one phrase.
    merged, used = {}, set()
    for (i, j), phrase in sorted(seen.items()):
        if phrase not in shared or (i, j) in used:
            continue
        words_out, k = normalise(copy[i])[j:j + ECHO_NGRAM], j + 1
        while True:
            nxt = seen.get((i, k))
            if nxt is None or nxt not in shared or shared[nxt] != shared[phrase]:
                break
            words_out.append(normalise(copy[i])[k + ECHO_NGRAM - 1])
            used.add((i, k)); k += 1
        merged[" ".join(words_out)] = shared[phrase]

    return sorted(merged, key=lambda p: (-len(merged[p]), -len(p.split()))), merged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=int, default=25, help="words per string")
    ap.add_argument("--file", action="append", help="default: the whole ui/ package")
    args = ap.parse_args()

    root = pathlib.Path(__file__).resolve().parents[1]
    files = [pathlib.Path(f) for f in args.file] if args.file else \
        sorted(f for f in (root / UI).glob("*.kt") if f.name not in SKIP)

    copy = []
    for f in files:
        copy += strings(f.read_text(encoding="utf-8"))

    total = sum(words(c) for c in copy)
    over = sorted(((words(c), c) for c in copy if words(c) > args.max), reverse=True)

    print(f"{len(copy)} strings, {total} words on screen "
          f"(longest {max((words(c) for c in copy), default=0)}w, "
          f"budget {args.max}w)")

    if over:
        print(f"\n{len(over)} over budget, {sum(n for n, _ in over)} words:")
        for nwords, c in over:
            print(f"  [{nwords}w] {c[:96]}{'…' if len(c) > 96 else ''}")

    phrases, hits = echoes(copy)
    if phrases:
        print(f"\n{len(phrases)} phrases said more than once:")
        for p in phrases[:12]:
            print(f"  ×{len(hits[p])}  \"{p}\"")

    # Reporting tool: the exit status says whether anything was flagged, so it
    # can gate a commit if that is ever wanted, without deciding that here.
    return 1 if over or phrases else 0


if __name__ == "__main__":
    sys.exit(main())
