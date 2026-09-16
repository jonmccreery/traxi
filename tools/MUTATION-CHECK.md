# mutation-check

Asks whether each fix is what makes its test pass.

A green suite says the tests pass. It does not say *why*. Remove a fix and run
the suite: if nothing fails, nothing was verifying that fix, and the green was
coming from somewhere else. That is not hypothetical here — the first run found
four such fixes out of seven, including three that were masking each other
(ENGINEERING-RECORD.md §16.9).

## Running it

    python3 tools/mutation-check.py                 # all mutations, 10 runs each
    python3 tools/mutation-check.py --iterations 3  # quicker; still catches masking
    python3 tools/mutation-check.py --only D-prior-damage
    python3 tools/mutation-check.py --list

It needs `GRADLE` (default `~/tools/gradle-8.11.1/bin/gradle`) and
`ANDROID_HOME` (default `~/android-sdk`). Exit status is 0 when every mutation
is caught, 1 when any survives.

A full pass takes roughly a minute per run, so ten iterations across six
mutations is around an hour. Three iterations is enough to find a masked fix;
ten is for when you want to see whether a test is intermittent.

## Reading it

    === D-prior-damage ===
      10/10  ResumedHolesTest > a resume repairs the holes it is told about

Good. Removing that fix reliably breaks that test.

      === A-ack-subcommand ===
        MUTATION SURVIVED -- no test failed. Nothing verifies this fix.

**This is the interesting line.** The code may still be correct; what is certain
is that no test distinguishes it from its absence, so nothing will notice when
it is next refactored away.

      5/10  SomeTest > ...   <-- INTERMITTENT

Worse than a survivor in one way: the test is timing-dependent, so its verdict
is a coin flip. Fixtures in this repo take their clock from
`PmtkClient(clock = ...)` specifically so they do not have to be.

## Adding a mutation

Add an entry to `MUTATIONS` in the script: a file, and a list of `(old, new)`
replacements that *undo* one fix. Keep each entry to a single fix — the whole
point is lost if one mutation removes two things, since either test failing
then looks like success.

Every `old` must still appear in the file. When one goes stale through
refactoring the tool refuses to run and names it, rather than skipping it — a
mutation that cannot be applied looks exactly like one that nothing catches,
and the two must never be confused.

## Why it restores from a snapshot rather than from git

The first version used `git checkout -- src/main`, which restores from **HEAD**
and so reverted uncommitted work in the middle of a run. The tree stopped
compiling and two rows came back empty, which read as "no failures". Empty
because nothing ran and empty because everything passed are the same output.
Hence the file snapshot, the explicit compile-error detection, and the baseline
size check.
