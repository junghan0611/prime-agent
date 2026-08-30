#!/usr/bin/env bash
# H7 functional A/B. Two arms of the same four probes; the only difference
# between them is PRIME_AGENT_KERNEL_RUNTIME.
#
#   ./run.sh <output-dir>
#
# Notes that cost a run if you skip them:
#  - Use a DEDICATED --daemon-socket. The default socket may be held by a daemon
#    from another checkout, which then launches a worker from *its* dist and the
#    create command times out.
#  - Point PRIME_AGENT_CLOJURE_RUNTIME at the built binary. cwd is the probe dir,
#    so the runtime's cwd-relative lookup does not find it.
#  - Probe 2 reads docs/clojure-runtime.md, so each arm gets its own probe cwd
#    with that file copied in. read-text is rooted at the process working directory.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="${1:?usage: run.sh <output-dir>}"
CLI="$REPO/packages/coding-agent/dist/bundle/cli.js"
CLJ_BIN="$REPO/prime-agent-runtime-clj/target/rlm-repl"
MODEL="${H7_MODEL:-deepseek/deepseek-v4-flash}"
mkdir -p "$OUT"
set -a; . ~/.env.local 2>/dev/null; set +a

for arm in python clojure; do
	for probe in "$HERE"/probes/p*.txt; do
		id="$(basename "$probe" .txt)"
		out="$OUT/${id}-${arm}.jsonl"
		[ -s "$out" ] && { echo "skip ${id}/${arm}"; continue; }
		cwd="$OUT/cwd-${arm}"
		rm -rf "$cwd"; mkdir -p "$cwd/docs"
		cp "$REPO/docs/clojure-runtime.md" "$cwd/docs/"
		echo "=== ${id} / ${arm} ==="
		( cd "$cwd" && timeout 420 env \
			PRIME_AGENT_KERNEL_RUNTIME="$arm" \
			PRIME_AGENT_CLOJURE_RUNTIME="$CLJ_BIN" \
			node "$CLI" --mode json --no-session \
				--daemon-socket "/tmp/prime-agent-$(id -u)/h7-${arm}.sock" \
				--model "$MODEL" -p "$(cat "$probe")" ) > "$out" 2>"$OUT/${id}-${arm}.err"
		echo "  exit=$? lines=$(wc -l < "$out")"
	done
done
echo "done -> $OUT"
