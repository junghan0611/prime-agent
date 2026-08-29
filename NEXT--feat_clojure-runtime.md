# feat/clojure-runtime — Phase A boot sector

이 브랜치는 production parity 작업이 아니다. Prime Agent의 persistent programmable workspace를 Lisp으로 먼저 바꾸고, 실제 사용에서 다음 설계를 발견하는 번역 실험이다.

# RAIL — 현재 좌표

- [x] **1. 연구 질문과 경계 복원** — Entwurf #88 원문, Prime Agent 실측, parity review를 대조했다.
- [x] **2. Phase A 설계 다시 쓰기** — GraalVM native-image + SCI vertical slice로 `docs/clojure-runtime.md`를 재구성했다.
- [x] **3. Lisp runtime vertical slice** — native process가 persistent form과 `(rlm ...)` host bridge를 관통한다. 16 tests / 76 assertions, JVM·native 양쪽 green.
- [ ] **3b. 실행 경로 분리와 workspace 촉감** ← CURRENT: native를 기본 SUT로 승격하고, GLG가 form을 직접 칠 자리를 연다.

현재 좌표: 1–3 완료 → 3b 진행 → 4–5 대기

# NOW — 실행 경로 분리와 workspace 촉감

- Stem: native executable이 이미 관통했다. 이제 **JVM 경로와 native 경로가 섞이지 않게** 못을 박고, GLG가 실제 form을 쳐볼 자리를 연다.
- Problem: `test/rlm/harness.clj:10-14` 가 `RLM_REPL_BIN` 없으면 `clojure -M -m rlm.repl` 로 떨어진다. 즉 맨손 `clojure -M:test` 의 green은 **JVM 위 런타임**을 검증한 것이고, native는 env를 붙였을 때만 검증된다. 이 애매함이 나중에 "green인데 native가 깨져 있다"를 만든다.
- Next: SUT 경로를 이름으로 갈라 native를 정본으로 세우고, native binary에 직접 form을 치는 driver를 연다.
- Include: `:test-native`/`:test-jvm` alias 분리, 러너가 어떤 SUT를 물었는지 출력, native binary 부재·stale 시 실패, `bin/rlm` 대화 driver.
- Defer: TypeScript host, Python source, snapshot/restore, raw fd parity, interrupt 취소 보장, bash, MCP, Emmy.
- Preserve: `docs/clojure-runtime--review.md` 는 production parity gap audit. Phase A 착수 gate로 쓰지 않는다.
- Verify: `RLM_REPL_BIN` 없이 `:test-native` 를 치면 **실패**해야 한다(조용한 JVM fallback 금지). binary를 지우거나 소스보다 오래되면 실패해야 한다. `bin/rlm` 로 두 form을 쳐서 binding이 살아 있음을 눈으로 본다.
- Blocker: 없음.

## 관통 완료 — 측정 (2026-08-29)

- `ldd target/rlm-repl` → glibc 뿐. `libjvm.so` 없음. process table 단일 `rlm-repl`, java 없음.
- `file target/rlm-repl` → ELF 64-bit LSB pie executable, x86-64, stripped, 34.2 MB.
- native SUT: `RLM_REPL_BIN=$PWD/target/rlm-repl clojure -M:test` → 16 tests / 76 assertions, 0 failures.
- 부정 대조: `RLM_REPL_BIN=/nonexistent` → 14 errors. 스위치가 실제로 SUT를 바꾼다.
- JVM SUT: `clojure -M:test` → 같은 16/76 green.
- `nix develop .#node --command npm run check` → exit 0 (938 files, installer, browser-smoke).
- `git status --porcelain` → `?? prime-agent-runtime-clj/` 한 줄. 공유파일·docs·Python oracle 무손상.
- LOC: src 287 + test ~300 + native-image 124. 계약 경보기(구현 480–1,050) 안쪽.

## 알려진 편차 — parity라고 부르지 않는다

- output이 cell 끝에 batch flush된다. oracle은 writing thread에서 동기적으로 흘린다 (`src/rlm/eval.clj:23`).
- `error.ename` 이 SCI wrapper class이고 traceback에 runtime frame이 실린다 (`src/rlm/repl.clj:45`).
- interrupt 는 파싱만 하고 취소를 보장하지 않는다.

# SCALE — 날짜 대신 코드량과 개입 홉

- Phase A 예상 전체 변경량: 구현 약 1,000–1,900 LOC + 테스트·설정 약 800–1,400 LOC.
- 숫자는 목표치가 아니라 scope 경보기다. 범위를 넘으면 parity/hardening이 stem으로 섞였는지 먼저 본다.
- GLG 개입: 3–4홉만 연다 — workspace 촉감 → `(rlm ...)` 방향타 → Emmy probe → Phase A 갈림길.
- JSON codec, native-image 설정, framing, 단위 테스트 같은 기계 판단으로 GLG 홉을 소비하지 않는다.

# ACCEPTANCE — Phase A

1. 모델이 Python 흉내가 아니라 persistent Lisp form으로 실제 작업을 조직한다.
2. 셀 사이 binding이 살아 있고 문서를 값으로 붙잡아 필요한 부분만 계산한다.
3. `(rlm ...)`이 citizen 내부 child를 열고 host reply를 값으로 돌려준다.
4. GLG가 form을 읽고 고칠 수 있으며, 다음 설계 판단에 쓸 실제 흔적이 남는다.
5. 실패는 `semantics-gap`, `model-fumble`, `harness-gap`으로 나눠 기록한다.

다음은 Phase A의 선행조건이 아니다: raw fd 완전 격리, pure infinite-loop interrupt parity, 함수 snapshot roundtrip, CPython 92 tests 전체 green.

# GLG HOPS

- Hop 1 — workspace: 실제 persistent forms를 보고 Clojure/SCI의 촉감과 계속 진행 여부를 판단한다.
- Hop 2 — direction: 실제 child call을 보고 `(rlm ...)`의 동기값/handle/future 표면을 고른다.
- Hop 3 — coexistence: 작은 Emmy adapter로 form → symbolic → numeric/render 경로를 만져본다.
- Hop 4 — fork: runtime hardening, Emmy 확장, evaluator/language 변경, 중단 중 하나를 고른다.

# READ FIRST

1. `/home/junghan/sync/org/llmlog/20260827T222209--prime-agent-rlm을-lisp-emacs-공존언어로-옮기기-entwurf-88-원문과-선행조사-임시-합본__agent_emacs_entwurf_lisp_llmlog_metaprogramming_primeagent_repl_research.org`
2. `docs/clojure-runtime.md`
3. `docs/clojure-runtime--review.md`

Protocol reference: `prime-agent-runtime/src/rlm/repl.md`. Phase A가 가져갈 vocabulary와 나중 parity backlog를 가르는 데만 쓴다.

# RECENT

- [2026-08-29] 첫 설계는 CPython protocol v2 production parity를 전제로 작성됐다.
- [2026-08-29] review는 interrupt·raw fd·function snapshot의 parity gap을 확인했다. 이 gap은 Phase A 착수 blocker가 아니다.
- [2026-08-29] runtime 방향을 JVM-hosted `clojure.core/eval`에서 GraalVM native-image + SCI로 바로잡았다.
- [2026-08-29] 일정 표현을 날짜에서 코드량과 GLG 개입 홉으로 바꿨다.
- [2026-08-29] `docs/clojure-runtime.md`를 production replacement 설계에서 Phase A 실험 명세로 전면 재작성했다.
- [2026-08-29] 기존 GLG Clojure/GraalVM flake 계보를 따라 devShell 3개와 FHS native-image build surface를 세우고 `nix flake check`를 통과했다.
- [2026-08-29] `prime-agent-runtime-clj/` vertical slice가 native executable로 관통했다. 실행 시 JVM 없음을 `ldd`와 process table로 확인했다.
- [2026-08-29] 기본 테스트 경로가 조용히 JVM 런타임으로 떨어진다는 것을 발견했다. native를 정본 SUT로 승격하는 일이 3b가 됐다.
