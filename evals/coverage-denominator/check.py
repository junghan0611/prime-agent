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

Two gates, not one.  GLG: "terminal 은 회계가 끝났다는 뜻이지 Python 대체
기준을 충족했다는 뜻은 아니다."  Accounting closure and parity are different
claims and used to share one exit code, so closing the books read as meeting
the bar.  They are now separate lines with separate outcomes:

  gate (1)  re-audit closure  -- is every oracle contract accounted for
  gate (2)  parity baseline   -- is every in-scope capability actually built

Exit codes:

  0  both gates closed
  1  gate (1) open: `c` entries, or `D` entries pointing at a card GLG has not
     chosen yet.  Never silent, always a number.
  2  hard failure: an unmapped test, a stale manifest entry, a bad verdict, an
     unresolvable row/card id, a card status with no decision receipt, or an
     exclusion that tried to earn coverage.
  3  gate (1) closed, gate (2) not reached: the books balance and cards still
     hold in-scope capabilities nobody has built.  This is the state the single
     exit code could not say.

The stale check is ONE-DIRECTIONAL, on purpose.  manifest -> no such test is a
hard failure.  registry row -> no manifest entry referencing it is NORMAL: rows
also come from the second source, `docs/clojure-runtime.md` "코드를 읽어야만
알던 것", where a contract is found by reading the contract document rather than
by reading an oracle test.  H1.4 is exactly that kind of row.  Do not "clean up"
an unreferenced row.
"""

import collections
import csv
import pathlib
import re
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
    hard = []
    rows = {r["id"] for r in registry if r["kind"] == "row"}
    row_status = {r["id"]: r["status"] for r in registry if r["kind"] == "row"}
    cards = {r["id"] for r in registry if r["kind"] == "card"}
    live_cards = [r for r in registry if r["kind"] == "card" and not r["status"].startswith("retired")]

    # A card only stops being debt once GLG has CHOSEN. Drafting the card into
    # an issue comment is not the choice: NEXT is explicit that before the
    # choice a card is `DECISION REQUIRED`, and that is incomplete -- an
    # explicit exclusion is not a supported-coverage PASS (Hard Rule 3).
    #
    # GLG's scope decision (issue #2) replaced the placeholder verbs with two
    # that mean different things, and the difference is the whole point:
    #
    #   parity-target(H<n>)           in scope, unbuilt, assigned to a hop.
    #                                 Owed work -- it is what gate (2) counts.
    #   out-of-scope(GLG,YYYY-MM-DD)  GLG removed it from the comparison.  A
    #                                 subtraction from the denominator, never
    #                                 an addition to the covered set.
    #   declared-divergence           the contract doc states the difference.
    #   DECISION REQUIRED             still waiting on GLG -- open debt.
    #
    # A scope status is worth exactly the receipt behind it, and an agent can
    # type any of these words into a cell.  So both decided forms REQUIRE a
    # decision_url naming the published comment that made the call: the status
    # alone is reachable by one edit, the status plus its receipt is not.
    CARD_STATUS = re.compile(
        r"^(?:parity-target\(H\d+\)"
        r"|out-of-scope\(GLG,\d{4}-\d{2}-\d{2}\)"
        r"|declared-divergence"
        r"|DECISION REQUIRED)$"
    )
    DECISION_URL = re.compile(r"^https://github\.com/junghan0611/prime-agent/issues/\d+#issuecomment-\d+$")
    for r in live_cards:
        if not CARD_STATUS.match(r["status"]):
            hard.append(
                f"card {r['id']} carries an unknown status {r['status']!r}; allowed: "
                "parity-target(H<n>), out-of-scope(GLG,YYYY-MM-DD), declared-divergence, DECISION REQUIRED"
            )
            continue
        if r["status"].startswith(("parity-target(", "out-of-scope(")) and not DECISION_URL.match(
            r.get("decision_url", "-")
        ):
            hard.append(
                f"card {r['id']} claims scope status {r['status']!r} with no decision receipt "
                f"(decision_url={r.get('decision_url', '-')!r}) -- a scope call an agent can reach "
                "by editing one cell is not a decision"
            )

    parity_cards = [r for r in live_cards if r["status"].startswith("parity-target(")]
    out_cards = {r["id"] for r in live_cards if r["status"].startswith("out-of-scope(")}
    divergent_cards = {r["id"] for r in live_cards if r["status"] == "declared-divergence"}
    undecided = {r["id"] for r in live_cards if r["status"] == "DECISION REQUIRED"}

    # Hard Rule 3, mechanised.  "Writing a hole down never makes a PASS" was a
    # sentence a reader had to honour; these are the three doors it could walk
    # back in through, each shut.  An exclusion earns no verdict other than D,
    # holds no kill bucket, and -- the third door, opened by the interrupt
    # negative contract -- a row that only says what the runtime REFUSES to do
    # is not a terminal any oracle test may claim coverage from.
    for m in manifest:
        if m["target"] in out_cards and m["verdict"] != "D":
            hard.append(
                f"out-of-scope card {m['target']} is named by a ({m['verdict']}) verdict: {m['test_id']} "
                "-- an exclusion is a subtraction from the denominator, not a coverage credit"
            )
    for r in live_cards:
        if r["id"] in out_cards and r.get("kill_bucket", "-") != "-":
            hard.append(
                f"out-of-scope card {r['id']} carries kill_bucket={r['kill_bucket']!r} "
                "-- nothing was built, so there is nothing a mutant could break"
            )
    # A hop's card is a bag until the ledger says what is inside it.  D-INTERRUPT
    # was summarised as "branch (5) covers 17/21" -- and 17 was the size of a
    # DIFFERENT subset than the one that branch's mechanism explains, with the
    # gap never named.  Nothing in the data could contradict the sentence,
    # because the data had no split.  Bundles are that split: which tests the
    # proposed mechanism names, and which are a reinterpretation it does not
    # supply.  The vocabulary is closed -- a typo would mint a fifth bundle in
    # silence and the sum would stop meaning anything.
    BUNDLES = {"thread-interrupt", "parking", "process-ownership", "not-interrupt"}
    card_bundles = {}
    for m in manifest:
        b = m.get("bundle", "-")
        if m["verdict"] != "D":
            if b != "-":
                hard.append(
                    f"{m['test_id']} carries bundle {b!r} on a ({m['verdict']}) verdict "
                    "-- a bundle names what a capability card holds, so only D entries take one"
                )
            continue
        if b != "-" and b not in BUNDLES:
            hard.append(
                f"{m['test_id']} carries an unknown bundle {b!r}; allowed: {', '.join(sorted(BUNDLES))}"
            )
        card_bundles.setdefault(m["target"], []).append(b)
    # All or none, per card.  A half-bundled card is exactly the state that let
    # 17/21 stand: the placed tests look accounted for and the unplaced ones are
    # spoken for by a summary nobody can check.
    for card, bs in sorted(card_bundles.items()):
        named = [b for b in bs if b != "-"]
        if named and len(named) != len(bs):
            hard.append(
                f"card {card} is partially bundled: {len(named)} of {len(bs)} D entries carry a bundle "
                "-- a partial split lets a summary speak for the tests nobody placed"
            )

    negative_rows = {r["id"] for r in registry if r["kind"] == "row" and r["status"] == "negative-contract"}
    for m in manifest:
        if m["verdict"] == "row" and m["target"] in negative_rows:
            hard.append(
                f"negative-contract row {m['target']} is the terminal for {m['test_id']} "
                "-- refusing out loud is not keeping the contract; it earns no coverage credit"
            )

    if not extract.TEST_DIR.is_dir():
        hard.append(
            f"oracle test dir not found at {extract.TEST_DIR} -- the gate resolves it relative to "
            "its own file, so a directory copy reads the wrong tree and every manifest entry goes "
            "stale; isolate with `git worktree add --detach` instead"
        )
        print(f"\nHARD FAILURE -- {len(hard)}:")
        for line in hard:
            print(f"  {line}")
        return 2

    actual = extract.test_ids()
    mapped = {m["test_id"]: m for m in manifest}

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

    # Every row that still owes a kill sits in exactly one bucket, and the
    # bucket is a STABLE classification -- it stays after a kill lands.  An
    # earlier version failed a non-green row that carried a bucket, which meant
    # the bucket had to be erased at exactly the moment a kill arrived, and the
    # `killed/weak` rule evaporated with it.  Checking the current state is not
    # checking the transition.
    #
    #   A  strong kill available        -> green/no-kill | PASS
    #   B  weak kill only               -> green/no-kill | killed/weak   (never PASS)
    #   C  no valid mutant              -> green/no-valid-mutant
    #
    # C is not "not done yet".  An equivalent mutant is a documented limit of
    # the method, which is why it has its own status word rather than sharing
    # one with rows still waiting for work.
    ALLOWED = {
        "A": {"green/no-kill", "PASS"},
        "B": {"green/no-kill", "killed/weak"},
        "C": {"green/no-valid-mutant"},
    }
    OWING = {"green/no-kill", "green/no-valid-mutant"}
    buckets = {"A": [], "B": [], "C": []}
    owing = [r for r in registry if r["kind"] == "row" and r["status"] in OWING]
    for r in registry:
        if r["kind"] != "row":
            continue
        b = r.get("kill_bucket", "-")
        if r["status"] in OWING and b not in ALLOWED:
            hard.append(f"row {r['id']} owes a kill but sits in no bucket (kill_bucket={b!r})")
        elif b in ALLOWED:
            buckets[b].append(r["id"])
            if r["status"] not in ALLOWED[b]:
                hard.append(
                    f"row {r['id']} is bucket {b} but status {r['status']!r}; "
                    f"allowed: {', '.join(sorted(ALLOWED[b]))}"
                    + (" -- a weak kill never earns PASS" if b == "B" else "")
                )
    total = sum(len(v) for v in buckets.values())
    if total != len(owing):
        hard.append(f"kill buckets do not partition the rows that owe one: {total} bucketed, {len(owing)} owing")

    # Duplicated ids let one row sit in two buckets while the totals still add
    # up -- the exact double-count a hand tally produced before this was data.
    seen = {}
    for r in registry:
        key = (r["kind"], r["id"])
        seen[key] = seen.get(key, 0) + 1
    for (kind, rid), n in sorted(seen.items()):
        if n > 1:
            hard.append(f"duplicate {kind} id in registry.tsv: {rid} appears {n} times")

    if owing:
        print(f"kill buckets: {len(owing)} rows owe a kill -- A(strong)={len(buckets['A'])} "
              f"B(weak, earns killed/weak not PASS)={len(buckets['B'])} "
              f"C(equivalent mutant, documented limit)={len(buckets['C'])}")
        print(f"              B: {' '.join(sorted(buckets['B']))}")
        print(f"              C: {' '.join(sorted(buckets['C']))}")
    # Deleting an unreferenced row shrinks owing and the bucket total together,
    # so the partition check stays happy.  Printing the census makes that show
    # up in a diff; a real completeness receipt is still an open proposal.
    print(f"registry:     {sum(1 for r in registry if r['kind'] == 'row')} rows, "
          f"{sum(1 for r in registry if r['kind'] == 'card')} cards")

    debt_c = [m["test_id"] for m in manifest if m["verdict"] == "c"]
    debt_card = sorted({m["target"] for m in manifest if m["verdict"] == "D" and m["target"] in undecided})
    skips = extract.host_skips()

    print(f"denominator: {len(actual)} oracle tests (unittest, AST-extracted)")
    print(f"source:      read from {extract.TEST_DIR} -- this gate resolves the oracle "
          "relative to its own file, so isolate with `git worktree add --detach`, never a directory copy")
    tally = {v: sum(1 for m in manifest if m["verdict"] == v) for v in sorted(VERDICTS)}
    print("verdicts:    " + "  ".join(f"{k}={v}" for k, v in tally.items()))

    # The parity denominator is 70 because 50 tests were EXCLUDED, and those
    # two numbers are one fact.  Print 70 alone and the next reader has no way
    # to tell a small comparison from a nearly-finished one -- so the exclusion
    # is rendered on the same lines, from the same data, every time.  Nothing
    # here is a literal: the counts come from registry statuses, because every
    # count in this lane that drifted drifted because a person typed it.
    per_card = collections.Counter(m["target"] for m in manifest if m["verdict"] == "D")
    total_D = sum(1 for m in manifest if m["verdict"] == "D")
    # parity_tests is defined by the CLASS, not by the hop label: a card that
    # got into this class without a readable hop must still be counted here, or
    # the partition check below would balance while the ledger lies.  The label
    # parse is its own failure, and it is a diagnosis rather than a traceback --
    # a gate that dies with a stack trace has not told anyone anything.
    HOP = re.compile(r"^parity-target\((H\d+)\)$")
    parity_tests = sum(per_card[r["id"]] for r in parity_cards)
    hops = collections.Counter()
    for r in parity_cards:
        label = HOP.match(r["status"])
        if not label:
            hard.append(
                f"card {r['id']} is counted as a parity target but its status {r['status']!r} names no hop"
            )
            continue
        hops[label.group(1)] += per_card[r["id"]]
    out_tests = sum(per_card[c] for c in out_cards)
    div_tests = sum(per_card[c] for c in divergent_cards)
    und_tests = sum(per_card[c] for c in undecided)
    out_label = "/".join(sorted({r["status"] for r in live_cards if r["id"] in out_cards})) or "out-of-scope"
    # Four disjoint classes over the same cards.  Fold one into another -- the
    # exact move that turns an exclusion into coverage -- and this stops adding
    # up, because the classes are counted separately and summed against a D
    # total taken from the manifest.
    if parity_tests + out_tests + div_tests + und_tests != total_D:
        hard.append(
            f"scope classes do not partition the D verdicts: parity-target {parity_tests} + "
            f"out-of-scope {out_tests} + declared-divergence {div_tests} + undecided {und_tests} "
            f"!= D {total_D}"
        )
    hop_detail = " ".join(f"{h}={n}" for h, n in sorted(hops.items(), key=lambda kv: int(kv[0][1:])))
    print(
        f"scope:       D={total_D} split by card status -- parity-target {parity_tests} ({hop_detail}) · "
        f"{out_label} {out_tests} · declared-divergence {div_tests} · undecided {und_tests}"
    )
    print(
        f"parity:      denominator {parity_tests} = D {total_D} minus {out_label} {out_tests} "
        "-- the exclusion is printed with it, never behind it"
    )
    # What a hop actually holds, printed instead of asserted.  The mechanism a
    # branch proposes covers some of these bundles and not others; the ledger
    # shows the split so nobody has to trust a ratio in a comment.
    for card, bs in sorted(card_bundles.items()):
        named = [b for b in bs if b != "-"]
        if not named:
            continue
        split = collections.Counter(named)
        print(f"bundles:     {card} {len(bs)} -- "
              + " · ".join(f"{k} {v}" for k, v in sorted(split.items())))
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

    # Gate (1) asks whether the books balance.  A card GLG has PLACED is a loud
    # terminal even though nothing is built yet -- "in scope, hop 9, unbuilt" is
    # an answer.  What leaves gate (1) open is a contract with no terminal at
    # all: a `c` entry owing a row, or a card still awaiting the call.
    reaudit_open = bool(debt_c or debt_card)
    if reaudit_open:
        print(f"\ngate ①  re-audit closure: OPEN -- {len(debt_c)} (c) entries owe a row, "
              f"{len(debt_card)} cards await a GLG choice:")
        for t in debt_c:
            print(f"  c  {t}")
        for c in debt_card:
            n = per_card[c]
            status = next(r["status"] for r in registry if r["id"] == c)
            print(f"  D  {c} ({n} tests) -- {status}")
    else:
        print("\ngate ①  re-audit closure: CLOSED -- every oracle test terminates at a row, "
              "(a), (b), or a decided card.")

    # Gate (2) asks the other question, and it is the one the single exit code
    # could not ask.  Every card here is a capability GLG kept IN scope that
    # nobody has built.  Closing gate (1) does not move this line at all.
    if parity_cards:
        print(f"gate ②  parity baseline: NOT REACHED -- {len(parity_cards)} cards hold {parity_tests} "
              f"in-scope oracle tests ({hop_detail}); {out_label} {out_tests} is excluded from that "
              "denominator and stays in the ledger")
    else:
        print(f"gate ②  parity baseline: REACHED -- no card still holds an in-scope capability; "
              f"denominator was {parity_tests} = D {total_D} minus {out_label} {out_tests}")

    if reaudit_open:
        return 1
    if parity_cards:
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
