#!/usr/bin/env python3
"""Check that each fix is what makes its test pass.

A test that passed before a fix and passes after it has demonstrated
*something*. It has not necessarily demonstrated that **this** fix is what
makes it pass. This removes one fix at a time and asks whether anything
notices.

The first time it was run, four of the seven mutations killed nothing at all:
the three parts of the §16.3 cure masked each other, so any one of them alone
made the tests come out right and none of them was verified by anything. The
suite was green for reasons unrelated to the code being correct. That is the
result this tool exists to produce -- **MUTATION SURVIVED** is the interesting
line, not the failures.

Four things it does that a first attempt will not, each of them learned by
being fooled (see ENGINEERING-RECORD.md §16.9):

  * **Restores from a file snapshot, never from git.** The first version used
    `git checkout -- src/main`, which restores from HEAD and silently reverted
    uncommitted work. The tree then did not compile and two rows came back
    empty.
  * **Detects compile errors explicitly.** An empty failure list because
    nothing ran looks exactly like an empty failure list because everything
    passed. That is what made the above survivable for as long as it was.
  * **Passes `--rerun` after *every* task.** It is a task-specific option, so
    written once at the end of the command line it attaches only to the last
    task and the rest come back from Gradle's cache, reporting no test lines at
    all. A failing task is never cached, so the mutation rows survive that
    mistake -- but the baseline and the restore check silently stop meaning
    anything, which is the same shape as the bug above wearing different
    clothes.
  * **Refuses to proceed on an implausibly small baseline.** The count is the
    cheapest possible check that the suite really ran.

It also checks the baseline is green before mutating anything, because results
measured against a broken tree mean nothing, and re-runs the suite at the end
to prove it put the tree back.

Usage:
    python3 tools/mutation-check.py                  # every mutation, 10 runs each
    python3 tools/mutation-check.py --iterations 3   # quicker, still catches masking
    python3 tools/mutation-check.py --only A-ack-subcommand
    python3 tools/mutation-check.py --list

Environment:
    GRADLE        path to the gradle binary (default: ~/tools/gradle-8.11.1/bin/gradle)
    ANDROID_HOME  needed for the :app tests (default: ~/android-sdk)

Adding a mutation: put an entry in MUTATIONS below. Each one names the file and
a list of (old, new) replacements that *undo* a fix. Every `old` must appear in
the file or the tool refuses to run -- a pattern that has gone stale through
refactoring is reported loudly rather than skipped silently, because a mutation
that cannot be applied is indistinguishable from one nothing catches.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CLIENT = "core/src/main/kotlin/thru/taxi/traxi/core/protocol/PmtkClient.kt"
DOWNLOADER = "core/src/main/kotlin/thru/taxi/traxi/core/protocol/FlashDownloader.kt"
OUTCOME = "app/src/main/kotlin/thru/taxi/traxi/session/FetchOutcome.kt"

# name -> (file, [(old, new), ...]). Each entry reverts exactly one fix.
MUTATIONS = {
    # §16.3, part one: match the ack's command and never its subcommand.
    "A-ack-subcommand": (CLIENT, [
        ('Pmtk.Ack.from(it)?.acknowledges("182", "1") == true',
         'Pmtk.Ack.from(it)?.command == "182"'),
        ('Pmtk.Ack.from(it)?.acknowledges("182", subcommand) == true',
         'Pmtk.Ack.from(it)?.command == "182"'),
        ('Pmtk.Ack.from(it)?.acknowledges("182", "6") == true',
         'Pmtk.Ack.from(it)?.command == "182"'),
    ]),

    # §16.3, part two: let answers predating a request satisfy it.
    "B-exchange-clear-drain": (CLIENT, [
        ("        queued.clear()\n        drainInput()\n        repeat(retries)",
         "        repeat(retries)"),
    ]),

    # §16.3, part three: let pump park sentences nothing is waiting for.
    "C-pump-no-queue": (CLIENT, [
        ("if (n > 0) ingest(n, queue = false)", "if (n > 0) ingest(n)"),
    ]),

    # §16.1: forget the damage a previous transfer recorded.
    "D-prior-damage": (DOWNLOADER, [
        ("        if (priorDamage.isNotEmpty()) {",
         "        if (false && priorDamage.isNotEmpty()) {"),
    ]),

    # §16.2: let a wedged transfer fall through to the content comparison.
    "E-wedge-branch": (OUTCOME, [
        ('            result.stoppedUnanswered ->\n'
         '                "The logger stopped answering — $size saved and resumable"\n',
         ''),
        ('            result.isComplete -> "Already up to date — nothing new on the logger"\n'
         '            else -> "Stopped early — $size saved and resumable"',
         '            else -> "Already up to date — nothing new on the logger"'),
    ]),

    # §16.4: drop what the earlier segments established.
    "F-continued-by": (DOWNLOADER, [
        ("""        fun continuedBy(continued: Result): Result = continued.copy(
            retries = retries + continued.retries,
            fullReread = fullReread || continued.fullReread,
            sectorsFetched = sectorsFetched + continued.sectorsFetched,
        )""",
         """        fun continuedBy(continued: Result): Result = continued.copy(
            retries = retries + continued.retries,
        )"""),
    ]),
}

GRADLE = os.environ.get("GRADLE", str(Path.home() / "tools/gradle-8.11.1/bin/gradle"))

# `--rerun` is a *task-specific* option, so it has to follow every task it
# applies to. Written once at the end it attaches only to the last one, and the
# other task is served from Gradle's cache -- which reports no test lines at
# all, so a cached green is indistinguishable from a green that ran. A failing
# task is never cached, so mutation rows stay honest either way; it is the
# baseline and the restore check that quietly stop meaning anything.
TASKS = [":core:test", "--rerun", ":app:testDebugUnitTest", "--rerun"]

# A floor, not a total: if far fewer than this run, something is being cached
# and the whole report is worthless. Raise it as the suite grows.
MIN_EXPECTED_TESTS = 150

FAILED_TEST = re.compile(r"^\s*(\S+Test > .+?) FAILED\s*$")
COMPILE_ERROR = re.compile(r"^e: |Compilation error|Task :\S+:compile\S* FAILED")


class Result:
    def __init__(self, failures, compiled, passed):
        self.failures = failures      # set of failing test names
        self.compiled = compiled      # False if the tree did not build
        self.passed = passed          # count of PASSED lines


def run_tests():
    """Run the whole suite once, forcing both test tasks to actually execute."""
    env = dict(os.environ)
    env.setdefault("ANDROID_HOME", str(Path.home() / "android-sdk"))
    proc = subprocess.run(
        [GRADLE, *TASKS, "--offline", "--console=plain"],
        cwd=ROOT, env=env, capture_output=True, text=True, timeout=1800,
    )
    out = proc.stdout + proc.stderr
    failures, passed, compiled = set(), 0, True
    for line in out.splitlines():
        if COMPILE_ERROR.search(line):
            compiled = False
        m = FAILED_TEST.match(line)
        if m:
            failures.add(m.group(1).strip())
        elif line.rstrip().endswith(" PASSED"):
            passed += 1
    return Result(failures, compiled, passed)


def check_patterns():
    """Every `old` must be present, or the report would be a lie."""
    stale = []
    for name, (relpath, edits) in MUTATIONS.items():
        text = (ROOT / relpath).read_text()
        for old, _ in edits:
            if old not in text:
                stale.append(f"{name}: pattern not found in {relpath}:\n    {old[:70]!r}")
    return stale


def apply_mutation(name, snapshot_dir):
    relpath, edits = MUTATIONS[name]
    path = ROOT / relpath
    shutil.copy2(path, snapshot_dir / Path(relpath).name)
    text = path.read_text()
    for old, new in edits:
        text = text.replace(old, new, 1)
    path.write_text(text)


def restore(name, snapshot_dir):
    relpath, _ = MUTATIONS[name]
    shutil.copy2(snapshot_dir / Path(relpath).name, ROOT / relpath)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--iterations", type=int, default=10)
    ap.add_argument("--only", action="append", default=None)
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    if args.list:
        for name, (relpath, _) in MUTATIONS.items():
            print(f"{name:<24} {relpath}")
        return 0

    if not Path(GRADLE).exists():
        print(f"gradle not found at {GRADLE}; set GRADLE=/path/to/gradle", file=sys.stderr)
        return 2

    stale = check_patterns()
    if stale:
        print("Stale mutation patterns -- the code moved underneath them:\n", file=sys.stderr)
        for s in stale:
            print("  " + s, file=sys.stderr)
        print("\nFix the patterns before trusting any result from this tool.", file=sys.stderr)
        return 2

    names = args.only or list(MUTATIONS)

    print("baseline (unmutated) ...", end=" ", flush=True)
    base = run_tests()
    if not base.compiled or base.failures:
        print("NOT GREEN")
        print("  compiled:", base.compiled, " failures:", sorted(base.failures))
        print("  A mutation run against a broken baseline measures nothing.")
        return 1
    print(f"green, {base.passed} tests")
    if base.passed < MIN_EXPECTED_TESTS:
        print(f"  only {base.passed} tests ran, expected at least {MIN_EXPECTED_TESTS}.")
        print("  Gradle is probably serving a cached result; every row below would")
        print("  then be measured against a suite that never executed.")
        return 1

    survived = []
    with tempfile.TemporaryDirectory(prefix="mutation-snap-") as tmp:
        snap = Path(tmp)
        for name in names:
            print(f"\n=== {name} ===")
            killed_by = {}
            broke_build = 0
            try:
                apply_mutation(name, snap)
                for i in range(args.iterations):
                    r = run_tests()
                    if not r.compiled:
                        broke_build += 1
                        continue
                    for f in r.failures:
                        killed_by[f] = killed_by.get(f, 0) + 1
            finally:
                restore(name, snap)

            if broke_build:
                print(f"  did not compile in {broke_build}/{args.iterations} runs "
                      f"-- the mutation is malformed, not undetected")
            if not killed_by:
                survived.append(name)
                print("  MUTATION SURVIVED -- no test failed. Nothing verifies this fix.")
            else:
                for test, count in sorted(killed_by.items(), key=lambda kv: -kv[1]):
                    flaky = "" if count == args.iterations else "   <-- INTERMITTENT"
                    print(f"  {count}/{args.iterations}  {test}{flaky}")

    print("\nverifying the tree was restored ...", end=" ", flush=True)
    after = run_tests()
    print("green" if after.compiled and not after.failures else "NOT GREEN -- check `git diff`")

    if survived:
        print(f"\n{len(survived)} mutation(s) survived: {', '.join(survived)}")
        print("Each names a fix that no test distinguishes from its absence.")
        return 1
    print("\nEvery fix is killed by at least one test.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
