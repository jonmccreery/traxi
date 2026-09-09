# Bond management in Traxi: how it worked, and why it kept deleting your pairing

Written 2026-09-08, after the second time the app destroyed a working Bluetooth
pairing and cost a manual re-pair at the device.

This is the history of one small piece of code, written out because the
behaviour it produced was surprising every single time it happened, and because
the reason it existed was never actually written down anywhere.

---

## The short version

The app deleted your pairing whenever a Bluetooth connect failed. It did that
because a genuine, well-understood failure mode exists on this hardware — a
stale link key — and clearing the bond is the correct cure for it.

The problem was never the cure. It was that **the test for "is this the stale
key case?" was written to be narrow and implemented to be universal**, so every
connect failure of any kind reached a destructive remedy designed for one
specific cause.

---

## 1. Why bonding exists here at all

Classic Bluetooth, unlike BLE, has no unpaired-connect path. An RFCOMM socket
needs a link key, so the device must be **bonded** before a socket can open.

This is not the same as being *associated*. Picking the logger in Android's
companion-device chooser creates an association — a remembered relationship —
and exchanges no keys at all. The first real pairing attempt (`418f692`,
2026-09-05) went straight to the socket on the assumption that choosing the
device was enough, and failed with:

```
read failed, socket might closed or timeout, read ret: -1
```

which reads like a dead device and sends you looking in entirely the wrong
place. That commit added `Bonding.ensureBonded`, which runs before the socket
opens and waits on `ACTION_BOND_STATE_CHANGED`.

So far, so ordinary: **bond first, then connect.** Nothing is torn down.

## 2. The real failure this hardware has

The BT-Q1000XT uses **legacy pairing** — Bluetooth 2.0 PIN pairing rather than
Secure Simple Pairing — with PIN `0000` (`382a6bf`). Legacy pairing brings a
specific and genuinely nasty failure:

> **The logger forgets its link key when it loses power. The phone does not.**

The result is a contradictory state that nothing surfaces. Android still reports
`BOND_BONDED`, so `ensureBonded` short-circuits and no PIN is ever requested,
while the device refuses the key it no longer has. The phone's own stack shows
the contradiction plainly:

```
bredr_linkkey_known:T   bredr_authenticated:F   bredr_encrypted:F
```

Bonded, but neither authenticated nor encrypted. The connect fails with no
prompt, no explanation, and a socket error identical to the device being
switched off.

**Clearing the bond genuinely is the fix**, because it forces the PIN exchange
to happen again. This part of the story is sound, and the capability is worth
keeping.

## 3. Where it went wrong

The remedy arrived in `c13ebf5` (2026-09-05) — a commit whose message is
entirely about reverting a read-path rewrite and about not discarding partial
downloads. **The bond-deletion behaviour is not mentioned in it at all.** A
destructive, automatic action on the user's pairing entered the codebase as an
undocumented passenger on a commit about something else. That is most of the
answer to "why did we do this in the first place?" — nobody wrote it down, so
there was nothing to disagree with later.

The code itself carried the right intent. Here is the doc comment it shipped
with:

> True when the phone holds a bond that the device is evidently not honouring —
> **bonded, but with no authenticated or encrypted link.**

And here is the implementation, in full:

```kotlin
fun looksStale(device: BluetoothDevice): Boolean =
    device.bondState == BluetoothDevice.BOND_BONDED
```

**The comment describes a two-part test. The body tests one part.** It never
looks at authentication or encryption — the very evidence that distinguishes a
stale key from a healthy pairing — and `BOND_BONDED` is true of every correctly
paired device in the world.

So the guard was vacuous. In `connect()`, the effective logic became:

```
if (the connect failed at all) and (we are paired):
    delete the pairing
```

A stale link key, a wedged radio, a device out of range, a device switched off,
a logger whose Bluetooth module has stopped answering — all identical, all
answered by deleting the credential.

## 4. What that cost

**2026-09-08, first incident.** The logger accepted an RFCOMM connection and
streamed NMEA normally while refusing to answer `PMTK605` — the command-path
wedge of engineering record §15. The query timeout propagated into the
stale-key handler, which deleted the bond. Re-pairing then failed nine times
(`AUTH_FAIL : 4`, no PIN prompt ever shown) because the device's radio, not the
key, was the problem. Recovery needed a power cycle and a PIN typed by hand.

The fix at the time narrowed the *scope*: only a failure of `open()` itself
could reach the remedy, since a live socket proves the key was good. That was
correct and insufficient.

**2026-09-08, second incident, ~14 minutes of downloads later.** A fetch-new
wedged the device. The new link-recovery code fired correctly, tried to rebuild
the radio link, and the rebuild's `open()` failed — a genuine open failure, so
the narrowed guard passed it straight through:

```
22:05:10  the device has stopped answering reads
22:05:10  cycling the radio link to clear a wedged read session
22:05:22  the old radio link did not drop within 12000ms; connecting anyway
22:06:38  could not rebuild the radio link: RFCOMM connect failed
22:06:38  connect failed while bonded; clearing a probably-stale link key
   ... 13 minutes of manual recovery ...
22:20:03  bonded
```

Seconds after a *confirmed* wedge — the moment a stale key is least likely to be
the explanation — the app deleted the pairing anyway.

## 5. What it does now

**The app never deletes a pairing on its own.** When a connect fails while
bonded, it says so and offers the remedy:

> If the logger is powered on and in range, the pairing may have gone stale —
> this logger forgets its key when it loses power, while the phone keeps hers.
> Re-pairing fixes that, and will ask for PIN 0000.
>
> `[ Clear pairing and re-pair ]`

The sequence behind that button is byte-for-byte what the app used to run on its
own guess. Nothing about the remedy changed. What changed is **who decides** —
and the person deciding can see the logger, knows whether they just power-cycled
it, knows whether they are in range, and is holding the phone that will ask for
the PIN.

Alongside it, `AclLink.awaitDown` now waits 30 s instead of 12 s, because the
rebuild above timed out twice and connected onto the same wedged link it was
trying to escape.

## 6. The rule this belongs to

The project already had the principle, from an earlier regression where a
merely-quiet link was torn down automatically:

> **Auto-remediation on conjecture is categorically different from reaction to
> an observed state.**

Both bond deletions were conjecture. A failed connect does not observe a stale
key; it observes a failure with many possible causes. Compare the download
recovery, which acts automatically and is fine: it fires on three consecutive
block attempts each returning zero bytes — a measurement, not an inference — and
its remedy destroys nothing.

The sharper test this history suggests:

**How expensive is being wrong, and who pays?** A wrong link rebuild costs
fifteen seconds and the app pays it. A wrong bond deletion costs a trip to the
device, a PIN, and thirteen minutes — and the user pays it, in the field,
possibly at a trailhead. The second one needed a human in the loop no matter how
good the inference was.

## 7. If you want the automatic path back

It could be made honest rather than removed, by implementing `looksStale` as its
comment always claimed: check that the link is bonded **and** unauthenticated
**and** unencrypted, which is the contradictory state a stale key actually
produces. Android exposes no public API for it, so it would need reflection over
the same fields `dumpsys` prints, and it would need testing against a genuinely
stale key rather than against theory.

Until someone does that and proves it on hardware, the offer is the right shape.
