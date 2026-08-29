# feat/clojure-runtime — Phase A boot sector

이 브랜치는 production parity 작업이 아니다. Prime Agent의 persistent programmable workspace를 Lisp으로 먼저 바꾸고, 실제 사용에서 다음 설계를 발견하는 번역 실험이다.

# RAIL — 현재 좌표

- [x] **1. 연구 질문과 경계 복원** — Entwurf #88 원문, Prime Agent 실측, parity review를 대조했다.
- [x] **2. Phase A 설계 다시 쓰기** — GraalVM native-image + SCI vertical slice로 `docs/clojure-runtime.md`를 재구성했다.
- [ ] **3. Lisp runtime vertical slice** ← CURRENT: native process가 persistent form과 `(rlm ...)` host bridge를 관통하게 만든다.
- [ ] **4. Prime Agent 실제 접속** — TypeScript host가 Lisp runtime을 띄우고 실제 RLM workload를 통과시킨다.
- [ ] **5. 공존언어 probe와 갈림길** — Emmy/SICM 계보를 작은 adapter로 열고 계속 확장할 축을 GLG가 고른다.

현재 좌표: 1–2 완료 → 3 진행 → 4–5 대기

# NOW — Lisp runtime vertical slice

- Stem: Lisp가 더 낫다고 선결하지 않고, 동작하는 Lisp workspace를 먼저 Prime Agent 안에 심어 실제 form과 실패를 관측한다.
- Next: `prime-agent-runtime-clj/` scaffold를 만들고 `ready → execute → persistent result → host_request/reply → done → shutdown` 한 줄을 관통한다.
- Include: Clojure source, SCI context, JSONL driver, native-image build surface, 해당 wire tests.
- Defer: snapshot/restore, raw fd capture, pure-loop interrupt parity, bash, Emmy adapter, host prompt 개명.
- Preserve: `docs/clojure-runtime--review.md`는 production parity gap audit로 남기되 Phase A 착수 gate로 사용하지 않는다.
- Verify: native process에 두 cell을 보내 첫 binding이 둘째 cell에서 계산되고, mock host reply를 받은 `(rlm ...)`이 answer가 아닌 handle을 result로 돌려준다.
- Blocker: 없음.
- Environment: `flake.nix` devShell과 FHS native-image build surface 검증 완료. `docs/clojure-runtime--devenv.md` 참조.
- Read: 아래 `READ FIRST` 문서와 `docs/clojure-runtime.md`의 `첫 vertical slice`, `docs/clojure-runtime--devenv.md`.
- Do not touch: Python oracle source, `~/repos/3rd/pi/prime-agent`, TypeScript host, main merge, commit/push, full 92-test parity.

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
