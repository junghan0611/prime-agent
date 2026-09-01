#!/usr/bin/env bash
# Coverage denominator -- the receipt for the set of contracts the audit table
# quantifies over.  Command strings live here, not in prose: the repo root
# ./run.sh is the SSOT for running the two arms and measuring them, and this
# directory is the SSOT for running its own gate.
set -euo pipefail
cd "$(dirname "$0")"

usage() {
	cat <<'USAGE'
coverage denominator — ./run.sh <cmd>

  extract   list every oracle test id the AST finds, with its skipTest guard
  check     the gate. 0 = all terminate · 1 = open debt · 2 = hard failure
  table     render the issue-comment tables from the data
            (cards | rows | verdicts | all) -- so a count is copied, never retyped

  Both are pure reads: no imports of the oracle modules, no deps, no network.
  Data lives beside this script — manifest.tsv (one line per oracle test) and
  registry.tsv (row and card ids, each pointing at the issue comment it was
  published in).  Nothing here is a progress document; progress goes to issue #1.
USAGE
}

case "${1:-help}" in
	extract) exec python3 extract.py ;;
	check)   exec python3 check.py ;;
	table)   shift; exec python3 table.py "$@" ;;
	help|-h|--help) usage ;;
	*) echo "✗ unknown command: $1   (./run.sh help)" >&2; exit 1 ;;
esac
