#!/usr/bin/env bash
# prime-agent (fork) — 하나의 진입점.
#
# 이 포크는 Prime Agent 의 RLM workspace 를 Clojure/SCI 로도 세워 Python 과
# 비교하는 실험이다. 매일 쓰는 것은 "두 팔을 각각 띄우는 법"과 "그 팔을 재는
# 법" 둘뿐이다. 명령 문자열을 문서에 적어두면 낡으므로 여기가 SSOT 다.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"

RUNTIME_CLJ="$ROOT/prime-agent-runtime-clj"
NATIVE_BIN="$RUNTIME_CLJ/target/rlm-repl"
# 체크아웃마다 + arm 마다 따로. 상류 기본값은 체크아웃과 무관한 단일
# daemon.sock 이라(defaultDaemonSocketPath) 다른 worktree 의 daemon 이 잡는다.
# 경로에 체크아웃을 넣지 않으면 두 worktree 의 같은 arm 이 같은 daemon 을
# 공유하고, 양-arm 영수증의 격리가 조용히 깨진다.
SOCK_DIR="/tmp/prime-agent-${UID}/$(basename "$ROOT")-$(printf %s "$ROOT" | cksum | cut -d" " -f1)"

die() { echo "✗ $*" >&2; exit 1; }

need_native() {
	[ -x "$NATIVE_BIN" ] || die "네이티브 런타임이 없다: $NATIVE_BIN
  → ./run.sh build   (GraalVM native-image, 로컬에서만 — CI 에는 없다)
  이 포크는 기본이 clojure 이고 python 으로 fallback 하지 않는다."
}

# 한 arm 을 띄운다. --model 을 뒤에 주면 그게 이긴다.
launch() {
	local kind=$1; shift
	mkdir -p "$SOCK_DIR"
	# 격리 fixture (BASELINE.md 「Baseline isolation」). 인터뷰·벤치 한 런은
	# 자기만의 빈 global harness store 와 빈 session dir 을 받는다. agent dir
	# 통째로는 못 옮긴다 — 자격증명과 설정이 거기 같이 산다.
	# 이미 환경에 박혀 있으면 그것을 존중한다: 한 세션의 여러 턴(--continue)이
	# 같은 store 를 이어써야 하고, 그 상속은 "다른 런과 공유"가 아니다.
	local run_root store_state="fresh" session_state="fresh"
	run_root="$SOCK_DIR/runs/$kind/$(date +%Y%m%dT%H%M%S)-$$"
	local store="${PRIME_AGENT_GLOBAL_HARNESS_STATE_DIR:-}"
	local sessions="${PRIME_AGENT_SESSION_DIR:-}"
	[ -n "$store" ] && store_state="inherited" || store="$run_root/global-harness"
	[ -n "$sessions" ] && session_state="inherited" || sessions="$run_root/sessions"
	# 세션-로컬 store 는 session dir 옆의 session-artifacts 아래 산다
	# (session-manager.ts 의 getSessionArtifactsRoot). session dir 만 찍으면
	# 영수증이 한 칸 빗나간다.
	local local_root; local_root="$(dirname "$sessions")/session-artifacts"
	mkdir -p "$store" "$sessions" "$local_root" "$run_root"
	if [ "$store_state" = "fresh" ] && [ -n "$(ls -A "$store" 2>/dev/null)" ]; then
		die "격리 실패 — 새로 딴 global store 가 비어 있지 않다: $store"
	fi
	export PRIME_AGENT_GLOBAL_HARNESS_STATE_DIR="$store"
	export PRIME_AGENT_SESSION_DIR="$sessions"
	# **새로 딴 store 에는 새 daemon socket 을 준다.** daemon 은 자기를 처음 띄운
	# 클라이언트의 env 만 물려받고(daemon-protocol.ts 의 collectDaemonLaunchEnv),
	# 그 뒤 클라이언트의 store env 는 allowlist(collectDaemonClientEnv) 밖이라
	# 안 넘어간다. 소켓을 arm 마다 고정하면 두 번째 런이 "store B" 를 찍고
	# 실제로는 첫 런의 store A 를 쓰는 daemon 에 붙는다 — 영수증이 거짓말을 한다.
	# 이어가는 런(store 를 상속받은 런)은 반대로 같은 daemon 에 붙어야 한다.
	local sock
	if [ "$store_state" = "fresh" ]; then
		sock="$run_root/daemon.sock"
	else
		sock="$SOCK_DIR/$kind.sock"
	fi
	# 호출자가 뒤에 자기 --daemon-socket 을 주면 CLI 에서 그게 이긴다 — 여럿이면
	# **마지막 것**이 이긴다. 그러면
	# 여기서 고른 경로는 더 이상 실제로 쓰이는 소켓이 아니다 — 찍기 전에 넘겨받은
	# 인자를 훑어 실효 값으로 바꾼다. 안 그러면 영수증만 옛 경로를 가리킨다
	# (2026-09-08 BENCH-1 첫 런에서 8 셀 전부가 그랬다: 셀은 자기 소켓에 붙었는데
	# 찍힌 줄은 arm 공용 소켓이었다).
	local prev="" arg
	for arg in "$@"; do
		case "$prev" in --daemon-socket) sock="$arg" ;; esac
		case "$arg" in --daemon-socket=*) sock="${arg#--daemon-socket=}" ;; esac
		prev="$arg"
	done
	# 인터뷰 영수증에 어느 daemon·어느 store 였는지 남도록 찍는다. 내보낸 변수를
	# 그대로 읽어 찍는다 — 찍은 경로와 세션이 쓰는 경로가 갈리지 않게.
	echo "→ $kind arm · daemon socket: $sock" >&2
	echo "→ $kind arm · global harness store ($store_state): $PRIME_AGENT_GLOBAL_HARNESS_STATE_DIR" >&2
	echo "→ $kind arm · local harness store root ($session_state): $local_root" >&2
	echo "→ $kind arm · session dir ($session_state): $PRIME_AGENT_SESSION_DIR" >&2
	# 넘길 인자를 **한 번만** 짓는다. dry 덤프도 exec 도 이 배열 하나를 쓴다 —
	# 찍는 자리와 넘기는 자리를 따로 적으면 둘이 갈릴 수 있고, 그게 방금 고친
	# 결함의 종류다(찍은 소켓과 쓴 소켓이 달랐다). 갈릴 자리를 없앤다.
	#
	# 모델 rail. DeepSeek 계정이 잔액 밑으로 내려가 양 팔 모두 402 를 받았고
	# (2026-09-08 측정), GLG 가 Copilot rail 을 대신 열었다. --no-env 는
	# COPILOT_GITHUB_TOKEN/GH_TOKEN/GITHUB_TOKEN 을 지우지만 Copilot 자격은
	# agent-dir 의 auth store 에서 오므로 살아남는다 — 실측했다.
	# **두 팔은 같은 모델이어야 한다.** 여기서 갈리면 비교가 성립하지 않는다.
	local -a launch_args=(
		--no-env --no-context-files --no-skills
		--daemon-socket "$sock"
		--model github-copilot/gemini-3.7-flash
		"$@"
	)
	# 영수증만 찍고 서지 않는 모드 — launcher 계약을 무는 테스트가 쓴다.
	# 띄울 프로세스가 실제로 받을 env 와 argv 를 그대로 덤프한다: 찍은 것과
	# 넘기는 것이 갈리면 그 자리에서 드러난다.
	if [ -n "${PRIME_AGENT_RUN_SH_DRY:-}" ]; then
		env | grep -E "^PRIME_AGENT_(GLOBAL_HARNESS_STATE_DIR|SESSION_DIR)=" | sed "s/^/receipt-env: /" >&2
		printf 'receipt-argv: %s\n' "${launch_args[@]}" >&2
		return 0
	fi
	# clojure 실행파일은 clojure arm 에만 건다. python arm 에서는 읽히지도
	# 않지만(resolveKernelRuntimeCommand), 상속시키면 격리 설명이 흐려진다.
	if [ "$kind" = "clojure" ]; then
		export PRIME_AGENT_CLOJURE_RUNTIME="$NATIVE_BIN"
	fi
	PRIME_AGENT_KERNEL_RUNTIME="$kind" \
	DO_NOT_TRACK=1 \
	exec "$ROOT/prime-agent.sh" "${launch_args[@]}"
}

usage() {
	cat <<'USAGE'
prime-agent fork — ./run.sh <cmd> [args]

  두 팔 — 운영자 인터뷰(BASELINE)와 양-arm 평가가 도는 자리
    clj  [args]   이 포크의 팔. Clojure/SCI native runtime (기본)
    py   [args]   oracle 팔. 상류 CPython. 지우지 않는다
                  둘 다 같은 모델(Copilot rail) · skills/context 없이 뜬다. --model 을
                  뒤에 주면 그게 이긴다. 소켓은 체크아웃+arm 마다 갈리고
                  띄울 때 경로를 찍는다 — 그게 격리 영수증이다.
                  skills-off 인터뷰 결과는 skills-on 실사용의 근거가 아니다

  재는 법
    build         rlm-repl 네이티브 이미지를 만든다 (GraalVM, 로컬 전용)
    test <name>   TS 계약 한 파일. 예: ./run.sh test repl-kernel-clojure-runtime
                  전체 실행은 금지다 — AGENTS.md Hard Rule 9
    test-native   native SUT (clojure -M:test). 바이너리가 있어야 한다
    lint          clj-kondo — CI 가 clojure 축에서 도는 유일한 것
    check         npm run check. 코드 변경 뒤 의무

USAGE
}

CMD="${1:-help}"; shift 2>/dev/null || true

case "$CMD" in
	clj)  need_native; launch clojure "$@" ;;
	py)   launch python "$@" ;;

	build)       exec "$RUNTIME_CLJ/native-image/build.sh" ;;
	test)
		[ $# -gt 0 ] || die "테스트 파일 이름이 필요하다 — ./run.sh help"
		f="${1%.test.ts}"; shift
		cd packages/coding-agent
		exec npx tsx ../../node_modules/vitest/dist/cli.js --run "test/${f}.test.ts" "$@" ;;
	test-native) need_native; cd "$RUNTIME_CLJ"; exec clojure -M:test ;;
	lint)        cd "$RUNTIME_CLJ"; exec clj-kondo --fail-level warning --lint src test ;;
	check)       exec npm run check ;;

	help|-h|--help) usage ;;
	*) die "모르는 명령: $CMD   (./run.sh help)" ;;
esac
