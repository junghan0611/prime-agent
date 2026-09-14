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
#  - **셀이 띄운 daemon 은 셀이 끝나도 산다.** 클라이언트가 빠지는 것과 daemon 이
#    서는 것은 다른 일이다(packages/coding-agent/docs/daemon.md 「Resident Workers」:
#    "Closing the TUI detaches the client; it does not stop the worker."). 2026-09-08
#    런이 그래서 supervisor+worker 16 프로세스를 6 일간 남겼고, 같은 날의 smoke 까지
#    합쳐 30 프로세스 · RSS 4.6GiB / PSS 3.1GiB 였다(2026-09-15 oracle 측정).
#    그래서 셀이 자기 daemon 을 자기가 거둔다 — 아래 stop_cell_daemon.
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

# 이 소켓 하나만 겨냥해 daemon 을 멈춘다.
#
# 왜 `prime-agent shutdown` 을 안 쓰는가: 그것은 runShutdownAll 이라 **이 머신의 모든**
# daemon 을 가져간다(daemon-ps.ts 의 planShutdownAll). 여기 셀들은 한 rep 안에서 병렬로
# 도니까 형제 셀의 팔까지 죽는다. `doctor --fix` 도 같은 이유로 넓다 — 다른 worktree 의
# idle daemon 까지 거둔다. 소켓 하나만 겨냥하는 공개 동사는 없다(public-command.ts 의
# rejectRemovedCommand 가 `daemon` 하위명령을 막았다). 그래서 소켓 경로로 listener 를
# 찾아 그 프로세스에만 TERM 을 보낸다 — 소켓 경로가 셀마다 달라 남의 것에 닿지 않는다.
#
# TERM 하나로 worker 까지 같이 내려가고 소켓 파일도 supervisor 가 스스로 지운다
# (2026-09-15 oracle 측정: supervisor 3562230 + worker 3562269 가 0.4 초 안에 사라지고
# d2.sock 도 없어졌다). 그래서 KILL 은 그 뒤 6 초를 기다린 예외 경로다.
stop_cell_daemon() {
	local sock=$1 pid i
	[ -S "$sock" ] || return 0
	pid=$(ss -xlp 2>/dev/null | awk -v s="$sock" '$0 ~ s && match($0, /pid=[0-9]+/) { print substr($0, RSTART + 4, RLENGTH - 4); exit }')
	if [ -z "$pid" ]; then
		# 소켓은 있는데 listener 가 없다 = 죽은 daemon 이 남긴 파일. 조용히 지우지 말고
		# 찍는다 — 누수를 침묵으로 덮으면 다음 사람이 다시 6 일을 잃는다.
		echo "  teardown: $sock 에 listener 가 없다 (stale socket) — 파일만 지운다" >&2
		rm -f "$sock"
		return 0
	fi
	kill -TERM "$pid" 2>/dev/null || return 0
	for i in $(seq 1 60); do
		kill -0 "$pid" 2>/dev/null || break
		sleep 0.1
	done
	if kill -0 "$pid" 2>/dev/null; then
		echo "  teardown: pid $pid 가 TERM 6s 를 안 받는다 — KILL" >&2
		kill -KILL "$pid" 2>/dev/null
	fi
	rm -f "$sock"
}

# 런이 중간에 끊겨도(Ctrl-C, timeout, 셸 종료) 이 런이 띄운 것은 이 런이 거둔다.
# $OUT 아래만 훑으므로 다른 런·다른 리포의 daemon 은 범위 밖이다.
sweep_run_daemons() {
	local sock
	for sock in "$OUT"/*/daemon.sock; do
		[ -S "$sock" ] && stop_cell_daemon "$sock"
	done
	return 0
}
trap sweep_run_daemons EXIT INT TERM

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
	# 마지막 턴이 끝났으면 이 셀의 팔은 할 일이 없다. 여기서 안 거두면 PPID 1 로 남는다.
	stop_cell_daemon "$dir/daemon.sock"
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
