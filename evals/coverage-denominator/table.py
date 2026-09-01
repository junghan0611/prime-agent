#!/usr/bin/env python3
"""Render the tables that go in issue comments, from the data instead of by hand.

Every count that has drifted in this lane drifted because a person counted it:
the card total, the bucket partition, "36 of 36" standing in for 52 rows. The
rule that came out of that was "copy numbers from the gate output, never count
them" -- this makes the tables themselves a copy rather than a transcription.
"""

import collections
import csv
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def read(name):
    with open(HERE / name, newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def cards(manifest, registry):
    n = collections.Counter(m["target"] for m in manifest if m["verdict"] == "D")
    out = ["| card | tests | status | reopen trigger |", "|---|---|---|---|"]
    live = [r for r in registry if r["kind"] == "card" and not r["status"].startswith("retired")]
    for r in sorted(live, key=lambda r: (-n[r["id"]], r["id"])):
        trig = r.get("trigger_type", "-")
        note = r.get("trigger_note", "")
        if note.startswith("DEAD"):
            trig = f"**{trig}, DEAD**"
        out.append(f"| `{r['id']}` | {n[r['id']]} | {r['status']} | {trig} |")
    out.append(f"| **total** | **{sum(n[r['id']] for r in live)}** | {len(live)} cards | |")
    return out


def rows(registry):
    by = collections.Counter(
        (r.get("kill_bucket", "-"), r["status"]) for r in registry if r["kind"] == "row"
    )
    out = ["| kill bucket | status | rows |", "|---|---|---|"]
    for (bucket, status), count in sorted(by.items()):
        out.append(f"| {bucket} | `{status}` | {count} |")
    out.append(f"| | **total** | **{sum(by.values())}** |")
    return out


def verdicts(manifest):
    by = collections.Counter(m["verdict"] for m in manifest)
    out = ["| verdict | oracle tests |", "|---|---|"]
    for v in ("row", "a", "b", "c", "D"):
        out.append(f"| `{v}` | {by[v]} |")
    out.append(f"| **total** | **{sum(by.values())}** |")
    return out


def main():
    manifest, registry = read("manifest.tsv"), read("registry.tsv")
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    blocks = {"cards": cards(manifest, registry), "rows": rows(registry), "verdicts": verdicts(manifest)}
    for name, lines in blocks.items():
        if which in (name, "all"):
            print(f"### {name}\n")
            print("\n".join(lines))
            print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
