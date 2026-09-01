#!/usr/bin/env python3
"""The denominator gate: every oracle test terminates somewhere, loudly.

Why this exists.  A kill receipt proves a row that EXISTS actually bites.  It
proves nothing about rows that were never written -- which is how
test_mcp + test_mcp_base (49) and test_harness (35) sat at zero for a whole
rail without any discipline noticing.  The completion condition says "every
supported contract", and until this file there was no receipt for the
denominator that sentence quantifies over.  This is the dual of the runner
manifest: that one proves no clj test file is silently not running, this one
proves no oracle contract is silently unaccounted for.

This gate answers "is everything accounted for".  It never answers "is
everything green" -- read the `row status:` line for that, and the row's own
entry in the issue for why.

Verdicts, one per oracle test:

  row   terminates at a table row (id must resolve in registry.tsv)
  a     Python is the subject; the clj arm owes nothing.  May name an
        anchoring row that PROVES the exclusion is enforced rather than merely
        asserted (the state ops name H1.3b, whose kill receipt shows the host
        mints no state-op frame for clojure).
  b     the python-specific mechanism is an accidental vehicle and the contract
        is already accounted for by another entry, which must be named.
  c     symmetric feature, no row yet -- OPEN DEBT.  Not an exclusion, not a
        "not applicable".  Hard Rule 3: writing a hole down never makes a PASS.
  D     capability absent in clj; terminates only at a decision card id.

Exit codes:

  0  every test terminates at row / a / b / a card GLG has decided
  1  open debt: `c` entries, or `D` entries pointing at a card GLG has not
     chosen yet.  Never silent, always a number.
  2  hard failure: an unmapped test, a stale manifest entry, a bad verdict, or
     an unresolvable row/card id.

The stale check is ONE-DIRECTIONAL, on purpose.  manifest -> no such test is a
hard failure.  registry row -> no manifest entry referencing it is NORMAL: rows
also come from the second source, `docs/clojure-runtime.md` "코드를 읽어야만
알던 것", where a contract is found by reading the contract document rather than
by reading an oracle test.  H1.4 is exactly that kind of row.  Do not "clean up"
an unreferenced row.
"""

import csv
import pathlib
import sys

import extract

HERE = pathlib.Path(__file__).resolve().parent
VERDICTS = {"row", "a", "b", "c", "D"}


def read_tsv(name):
    with open(HERE / name, newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def main():
    manifest = read_tsv("manifest.tsv")
    registry = read_tsv("registry.tsv")
    rows = {r["id"] for r in registry if r["kind"] == "row"}
    row_status = {r["id"]: r["status"] for r in registry if r["kind"] == "row"}
    cards = {r["id"] for r in registry if r["kind"] == "card"}
    live_cards = [r for r in registry if r["kind"] == "card" and not r["status"].startswith("retired")]
    # A card only stops being debt once GLG has CHOSEN. Drafting the card into
    # an issue comment is not the choice: NEXT is explicit that before the
    # choice a card is `DECISION REQUIRED`, and that is incomplete -- an
    # explicit exclusion is not a supported-coverage PASS (Hard Rule 3).
    chosen = {"support", "exclude", "future"}
    undecided = {r["id"] for r in registry if r["kind"] == "card" and r["status"] not in chosen}

    actual = extract.test_ids()
    mapped = {m["test_id"]: m for m in manifest}
    hard = []

    for test_id in actual:
        if test_id not in mapped:
            hard.append(f"unmapped oracle test (no manifest entry): {test_id}")
    for test_id in mapped:
        if test_id not in set(actual):
            hard.append(f"stale manifest entry (no such oracle test): {test_id}")
    if len(manifest) != len(mapped):
        hard.append(f"duplicate manifest entries: {len(manifest)} lines, {len(mapped)} distinct ids")

    for m in manifest:
        verdict, target, test_id = m["verdict"], m["target"], m["test_id"]
        if verdict not in VERDICTS:
            hard.append(f"unknown verdict {verdict!r}: {test_id}")
            continue
        if verdict == "row" and target not in rows:
            hard.append(f"row id {target!r} not in registry.tsv: {test_id}")
        if verdict == "D" and target not in cards:
            hard.append(f"card id {target!r} not in registry.tsv: {test_id}")
        if verdict == "a" and target != "-" and target not in rows:
            hard.append(f"anchoring row id {target!r} not in registry.tsv: {test_id}")
        if verdict == "b" and target not in set(actual) | rows:
            hard.append(f"(b) must name the entry or row that accounts for it, got {target!r}: {test_id}")
        if not m["evidence"].strip() or m["evidence"] == "-":
            hard.append(f"no evidence recorded: {test_id}")

    # Every row that still owes a kill sits in exactly one bucket: A (a
    # source-grounded expression with a predictable closure), B (an expression
    # that exists but whose kill is near-tautological, so it earns killed/weak
    # and never PASS), or C (no valid mutant).  Counting these by hand drifted
    # -- a row fell out of the partition and two were counted twice -- so the
    # partition is data now and the gate refuses a set that does not add up.
    buckets = {"A": [], "B": [], "C": []}
    owing = [r for r in registry if r["kind"] == "row" and r["status"] == "green/no-kill"]
    for r in owing:
        b = r.get("kill_bucket", "-")
        if b in buckets:
            buckets[b].append(r["id"])
        else:
            hard.append(f"row {r['id']} owes a kill but sits in no bucket (kill_bucket={b!r})")
    for r in registry:
        if r["kind"] == "row" and r["status"] != "green/no-kill" and r.get("kill_bucket", "-") != "-":
            hard.append(f"row {r['id']} is {r['status']} and owes no kill, but carries kill_bucket={r['kill_bucket']!r}")
    total = sum(len(v) for v in buckets.values())
    if total != len(owing):
        hard.append(f"kill buckets do not partition the rows that owe one: {total} bucketed, {len(owing)} owing")
    if owing:
        print(f"kill buckets: {len(owing)} rows owe a kill -- A(strong)={len(buckets['A'])} "
              f"B(weak, earns killed/weak not PASS)={len(buckets['B'])} C(no valid mutant)={len(buckets['C'])}")
        print(f"              B: {' '.join(sorted(buckets['B']))}")
        print(f"              C: {' '.join(sorted(buckets['C']))}")

    debt_c = [m["test_id"] for m in manifest if m["verdict"] == "c"]
    debt_card = sorted({m["target"] for m in manifest if m["verdict"] == "D" and m["target"] in undecided})
    skips = extract.host_skips()

    print(f"denominator: {len(actual)} oracle tests (unittest, AST-extracted)")
    tally = {v: sum(1 for m in manifest if m["verdict"] == v) for v in sorted(VERDICTS)}
    print("verdicts:    " + "  ".join(f"{k}={v}" for k, v in tally.items()))
    print(f"host_skip:   {len(skips)} tests carry a runtime skipTest guard (a contract this host may never watch Python keep)")

    # Report-only, and deliberately so: this gate answers "is everything
    # accounted for", never "is everything green".  A `row` verdict says a row
    # OWNS the contract, not that the row passes -- H1.4 is a registered row
    # with no test at all.  Printing the row statuses keeps "terminates at a
    # row" from being misread as "covered".
    # (a) anchors are not all worth the same.  An (a) that points at a PASS row
    # is backed by a kill receipt showing the exclusion is ENFORCED; one that
    # points at a green/no-kill row is only backed by a green.  Print the split,
    # or "89 of them are (a)" reads as one uniform kind of certainty.
    anchored = [m for m in manifest if m["verdict"] == "a" and m["target"] != "-"]
    total_a = sum(1 for m in manifest if m["verdict"] == "a")
    if anchored:
        by = {}
        for m in anchored:
            by.setdefault(row_status.get(m["target"], "?"), []).append(m["target"])
        detail = ", ".join(f"{len(v)} via {st} ({'/'.join(sorted(set(v)))})" for st, v in sorted(by.items()))
        print(f"(a) anchors:  {len(anchored)} of {total_a} (a) entries name a row that shows the exclusion is enforced -- {detail}")

    referenced = sorted({m["target"] for m in manifest if m["verdict"] == "row"})
    unfinished = [(r, row_status.get(r, "?")) for r in referenced if row_status.get(r) != "PASS"]
    if unfinished:
        print(f"row status:  {len(unfinished)} of {len(referenced)} REFERENCED rows are not PASS -- "
              + ", ".join(f"{r}={s}" for r, s in unfinished))

    # Referenced rows are not all the rows.  Read literally, "36 of 36" is a
    # statement about the 36 rows some oracle test terminates at -- and stays
    # silent about the rest, which include the original H1 rows and every
    # weakened or declared-divergence row.  If the referenced set all went PASS
    # this line is the only thing standing between that and "everything is
    # done".  Unreferenced is legitimate (a row can come from the contract doc
    # instead of an oracle test); unexamined is not.
    # A kill receipt does not make every row equal.  A row whose green is also
    # green under a weaker implementation earns `killed/weak`, never `PASS`:
    # otherwise the kill column launders a false PASS the same way the coverage
    # column would have.  Whether `killed/weak` counts as closed is J1's
    # question and GLG's to answer; the gate only refuses to launder it.
    all_rows = [r for r in registry if r["kind"] == "row"]
    unref = [r for r in all_rows if r["id"] not in set(referenced)]
    if unref:
        by = {}
        for r in unref:
            by.setdefault(r["status"], []).append(r["id"])
        print(f"unreferenced: {len(unref)} of {len(all_rows)} rows have no manifest entry terminating at them -- "
              + "; ".join(f"{st}: {' '.join(sorted(ids))}" for st, ids in sorted(by.items())))

    # A `future` recommendation is only honest if its reopen trigger can fire.
    # An observation-trigger needs a named observer; without one it never fires
    # and `future` quietly becomes `never`.  Report only -- the choice is GLG's.
    dead = [r for r in live_cards if r.get("trigger_note", "").startswith("DEAD")]
    if dead:
        print(f"dead triggers: {len(dead)} of {len(live_cards)} cards carry a reopen trigger that cannot fire -- "
              + ", ".join(r["id"] for r in dead))

    # What this gate does NOT do.  A second reviewer (sol, non-Claude, 2026-09-01)
    # drew a stratified sample and found terminal misclassifications this gate
    # cannot see: it verifies that every test has a verdict and that every id
    # resolves, never that the verdict is the RIGHT one.  Printing the sample
    # keeps "261 accounted for" from being read as "261 verified".
    print("semantic:    stratified sample n=55 (sol, 2026-09-01) -- 4 terminal "
          "misclassifications found (7.3%), all 4 fixed. This gate checks structure, "
          "not meaning; the sample implies more remain.")

    if hard:
        print(f"\nHARD FAILURE -- {len(hard)}:")
        for line in hard:
            print(f"  {line}")
        return 2

    if debt_c or debt_card:
        print(f"\nOPEN DEBT -- {len(debt_c)} (c) entries owe a row, {len(debt_card)} cards await a GLG choice:")
        for t in debt_c:
            print(f"  c  {t}")
        for c in debt_card:
            n = sum(1 for m in manifest if m["target"] == c)
            status = next(r["status"] for r in registry if r["id"] == c)
            print(f"  D  {c} ({n} tests) -- {status}")
        return 1

    print("\nevery oracle test terminates at a row, (a), (b), or a decided card.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
