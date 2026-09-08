#!/usr/bin/env bash
# BENCH-1 — BASELINE.md 「BENCH-1」 의 실행 장치.
#
#   ./run.sh <output-dir>
#
# 2 probes (P-A 인터뷰 · P-B 터널 과제) × 2 arms × 2 repeats = 8 sessions.
# 한 session = 여러 턴이고, 턴마다 프로세스가 새로 뜬다 — 그 프로세스 교체가
# T-1 이 요구하는 커널 재시작이다(양 팔 동일).
#
# 한 번 데면 다시 배우는 것들:
#  - **셀마다 daemon socket 을 따로 준다.** daemon 은 자기를 처음 띄운 클라이언트의
#    env 를 통째로 물려받고(daemon-protocol.ts 의 collectDaemonLaunchEnv), 그 뒤
#    클라이언트의 env 는 allowlist(collectDaemonClientEnv) 밖이면 넘어가지 않는다.
#    소켓을 공유하면 두 셀이 한 harness store 를 쓰고 격리가 조용히 깨진다.
#  - **rail 이 갈렸다.** DeepSeek 계정이 잔액 밑으로 내려가(2026-09-08) 양 팔이
#    402 를 받았고, GLG 가 Copilot rail 을 열었다. 그래서 이 런의 수치는
#    BASELINE HISTORY(DeepSeek) 와 **직접 비교되지 않는다** — declared divergence.
#    양 팔은 여전히 같은 모델이다; 그것이 비교의 전제다.
#  - 팔은 `./run.sh clj|py` 로만 띄운다(BASELINE 고정 인자). 격리 store 는 그
#    launcher 가 인쇄하고, 여기서 셀마다 미리 박아 한 세션의 여러 턴이 같은
#    store 를 잇게 한다.
#  - probe 가 docs/clojure-runtime.md 를 읽으므로 셀마다 자기 cwd 에 복사하고
#    `--cwd` 로 못박는다. launcher 는 자기 리포 루트로 cd 하므로(run.sh 의 `cd`)
#    셸에서 cd 만 해서는 세션 cwd 가 리포가 되고, 셀이 리포에 파일을 쓴다.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="${1:?usage: run.sh <output-dir>}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
MODEL="${BENCH1_MODEL:-github-copilot/gemini-3.7-flash}"
REPEATS="${BENCH1_REPEATS:-2}"
TURN_TIMEOUT="${BENCH1_TURN_TIMEOUT:-600}"

cell() {
	local probe=$1 arm=$2 rep=$3
	local id="${probe}-${arm}-r${rep}"
	local dir="$OUT/$id"
	local armcmd=py; [ "$arm" = clojure ] && armcmd=clj
	if [ -s "$dir/turn1.jsonl" ]; then echo "skip $id"; return 0; fi
	rm -rf "$dir"; mkdir -p "$dir/cwd/docs" "$dir/global-harness" "$dir/sessions"
	cp "$REPO/docs/clojure-runtime.md" "$dir/cwd/docs/"
	local turn=0
	for probefile in "$HERE"/probes/${probe}-*.txt; do
		turn=$((turn + 1))
		local extra=()
		[ "$turn" -gt 1 ] && extra=(--continue)
		echo "=== $id turn$turn ==="
		(
			cd "$dir/cwd" &&
				PRIME_AGENT_GLOBAL_HARNESS_STATE_DIR="$dir/global-harness" \
					PRIME_AGENT_SESSION_DIR="$dir/sessions" \
					timeout "$TURN_TIMEOUT" "$REPO/run.sh" "$armcmd" \
					--cwd "$dir/cwd" \
					--daemon-socket "$dir/daemon.sock" \
					--model "$MODEL" \
					--mode json ${extra[@]+"${extra[@]}"} \
					-p "$(cat "$probefile")"
		) >"$dir/turn${turn}.jsonl" 2>"$dir/turn${turn}.err"
		echo "  exit=$? lines=$(wc -l <"$dir/turn${turn}.jsonl")"
	done
}

for rep in $(seq 1 "$REPEATS"); do
	for probe in pa pb; do
		for arm in python clojure; do
			cell "$probe" "$arm" "$rep" &
		done
	done
	wait
done
echo "done -> $OUT"
