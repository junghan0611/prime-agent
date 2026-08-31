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
	local sock="$SOCK_DIR/$kind.sock"
	mkdir -p "$SOCK_DIR"
	# 인터뷰 영수증에 어느 daemon 이었는지 남도록 찍는다.
	echo "→ $kind arm · daemon socket: $sock" >&2
	# clojure 실행파일은 clojure arm 에만 건다. python arm 에서는 읽히지도
	# 않지만(resolveKernelRuntimeCommand), 상속시키면 격리 설명이 흐려진다.
	if [ "$kind" = "clojure" ]; then
		export PRIME_AGENT_CLOJURE_RUNTIME="$NATIVE_BIN"
	fi
	PRIME_AGENT_KERNEL_RUNTIME="$kind" \
	DO_NOT_TRACK=1 \
	exec "$ROOT/prime-agent.sh" \
		--no-env --no-context-files --no-skills \
		--daemon-socket "$sock" \
		--model deepseek/deepseek-v4-pro \
		"$@"
}

usage() {
	cat <<'USAGE'
prime-agent fork — ./run.sh <cmd> [args]

  두 팔 — 운영자 인터뷰(BASELINE)와 양-arm 평가가 도는 자리
    clj  [args]   이 포크의 팔. Clojure/SCI native runtime (기본)
    py   [args]   oracle 팔. 상류 CPython. 지우지 않는다
                  둘 다 DeepSeek v4-pro · skills/context 없이 뜬다. --model 을
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
