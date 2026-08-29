# feat/clojure-runtime — Phase A boot sector

이 브랜치는 production parity 작업이 아니다. Prime Agent의 persistent programmable workspace를 Lisp으로 먼저 바꾸고, 실제 사용에서 다음 설계를 발견하는 번역 실험이다.

# RAIL — 현재 좌표

- [x] **1. 연구 질문과 경계 복원** — Entwurf #88 원문, Prime Agent 실측, parity review를 대조했다.
- [x] **2. Phase A 설계 다시 쓰기** — GraalVM native-image + SCI vertical slice로 `docs/clojure-runtime.md`를 재구성했다.
- [x] **3. Lisp runtime vertical slice** — native process가 persistent form과 `(rlm ...)` host bridge를 관통한다. 16 tests / 76 assertions, JVM·native 양쪽 green.
- [x] **3b. 실행 경로 분리와 workspace 촉감** — native가 정본 SUT가 됐고 `bin/rlm`으로 form을 직접 친다. CI가 native gate를 잡는다.
- [ ] **4. Prime Agent 실제 접속** ← CURRENT: TypeScript host가 native runtime을 띄우고 실제 RLM workload를 통과시킨다.
- [ ] **5. 공존언어 probe와 갈림길** — Emmy/SICM 계보를 작은 adapter로 열고 계속 확장할 축을 GLG가 고른다.

현재 좌표: 1–3b 완료 → 4 진행 → 5 대기

# NOW — Prime Agent 실제 접속

- Stem: runtime이 native로 서고 CI가 그것을 지킨다. 이제 host가 이 프로세스를 실제로 띄워 모델이 그 안에서 form을 만들게 한다.
- Next: TypeScript host에 runtime 선택면을 연다 — native executable spawn, runtime별 bootstrap/prompt, snapshot 비활성 구성, Python oracle과 번갈아 띄울 스위치.
- Include: host diff 최소 범위, 실제 workload 4개, 실패의 3분류 기록.
- Defer: snapshot/restore, raw fd parity, interrupt 취소 보장, Emmy, 92 tests, `ipython` 개명.
- Verify: 모델이 Prime Agent 세션 안에서 persistent Clojure form으로 작업을 조직하고, 그 흔적이 남는다.
- Blocker: 아래 `## 열기 전에 정할 것` 두 건. 파일 IO 없이는 workload 2번(문서를 값으로 붙잡기)이 성립하지 않는다.

## 열기 전에 정할 것 — RAIL 4 안건

1. **파일 IO를 어떻게 여는가.** 지금 SCI에 `slurp`/`spit`이 없어 acceptance 2번(문서를 값으로 붙잡아 필요한 부분만 계산)을 만족할 수단이 없다. 그런데 `docs/clojure-runtime.md`의 framing 보증이 "Java/native 출력 경로가 닫혀 있음"에 기대고 있다. 함수 추가가 아니라 **출력 경계 재설계**다. 표류로 열지 말고 결정으로 연다.
2. **여는 방식은 install/retract 쌍으로.** 새 코드를 로딩하는 게 아니라 이미 컴파일된 Var를 SCI 네임스페이스에 열고 닫는 모양. native-image의 닫힌 세계 안에서 가능하고, 열린 능력을 되돌릴 수 있게 한다.
3. **verifier 격리.** IO를 여는 순간 "workspace 코드가 자기 테스트를 고칠 수 있는가"가 살아있는 질문이 된다. 지금은 IO가 없어 공짜로 만족된다. 열 때 짝으로 정한다.

## 관통 완료 — 측정 (2026-08-29)

- `ldd target/rlm-repl` → glibc 뿐. `libjvm.so` 없음. process table 단일 `rlm-repl`, java 없음.
- `file target/rlm-repl` → ELF 64-bit LSB pie executable, x86-64, stripped, 34.2 MB.
- native SUT: `RLM_REPL_BIN=$PWD/target/rlm-repl clojure -M:test` → 16 tests / 76 assertions, 0 failures.
- 부정 대조: `RLM_REPL_BIN=/nonexistent` → 14 errors. 스위치가 실제로 SUT를 바꾼다.
- JVM SUT: `clojure -M:test` → 같은 16/76 green.
- `nix develop .#node --command npm run check` → exit 0 (938 files, installer, browser-smoke).
- `git status --porcelain` → `?? prime-agent-runtime-clj/` 한 줄. 공유파일·docs·Python oracle 무손상.
- LOC: src 287 + test ~300 + native-image 124. 계약 경보기(구현 480–1,050) 안쪽.

## 측정된 발견 — 두 rail의 capability 경계가 다르다

같은 소스인데 SUT에 따라 Java interop 경계가 갈린다 (2026-08-29 실측, 두 rail에 같은 form을 보냄):

| form | native | JVM |
|---|---|---|
| `(.toUpperCase "ab")` | error | **ok → "AB"** |
| `(.length "abc")` | error | **ok → 3** |
| `(.getBytes "a")` | error | **ok → byte[]** |
| `(slurp "deps.edn")` | error | error |
| `(System/getProperty …)` | error | error |
| `(future 1)` | error | error |

즉 `docs/clojure-runtime.md`의 "Java 경로가 닫혀 있다"는 **native delivery에서만 참이다.** 그리고 `native-image/`에 reflect-config가 없다 — 인스턴스 interop이 닫힌 것은 명시적 allow-list 결정이 아니라 **reflection metadata 부재의 결과**로 보인다(이 인과는 추정, 경계 차이 자체는 실측). 나중에 라이브러리 때문에 reflect-config를 추가하면 경계가 조용히 열린다.

테스트는 native에서만 interop-closed를 고정하고 JVM의 열림을 성공으로 못 박지 않는다 — 나중에 닫아도 테스트가 막지 않게.

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
- [2026-08-29] push + CI 첫 실행이 green. native job이 `ldd`로 libjvm 부재를 확인하고 native 바이너리로 wire suite를 돌린다.
- [2026-08-29] 회귀를 16/76 → 32/158로 늘렸다. 셀을 가로지르는 fn·macro·atom·closure, capability 경계, 프레임 위조 방어, 유니코드 왕복, `rlm` 오류 3분기, protocol error 3종을 고정했다.
- [2026-08-29] 두 rail의 Java interop 경계가 다르다는 것을 측정했다. framing 보증은 native delivery에서만 참이다.
