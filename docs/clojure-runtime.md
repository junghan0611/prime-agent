# Prime Agent Lisp runtime — Phase A 설계

Author: gpt-5.6-sol (pi, 2026-08-29) — not GLG direct.

Fork `junghan0611/prime-agent` · branch `feat/clojure-runtime`.

이 문서는 production 교체 명세가 아니다. Prime Agent의 persistent programmable workspace를 Lisp으로 먼저 바꾸고, 실제 모델과 GLG가 그 안에서 만드는 form을 다음 설계의 입력으로 삼는 Phase A 실험 명세다.

---

## Pin과 출처

| 항목 | 값 |
|---|---|
| branch HEAD | `bc0fa7606` |
| host protocol | JSONL protocol v2 |
| Python oracle | `prime-agent-runtime/src/rlm/repl.py` |
| protocol reference | `prime-agent-runtime/src/rlm/repl.md` |
| 연구 원문 | Entwurf #88 임시 합본 |
| branch boot sector | `NEXT--feat_clojure-runtime.md` |

연구 원문:

```text
/home/junghan/sync/org/llmlog/
20260827T222209--prime-agent-rlm을-lisp-emacs-공존언어로-옮기기-entwurf-88-원문과-선행조사-임시-합본__agent_emacs_entwurf_lisp_llmlog_metaprogramming_primeagent_repl_research.org
```

`bc0fa7606`은 protocol v2의 작은 실험면으로만 쓴다. main의 protocol v3, `#1839` frame repair, `#1836` host-reply envelope, `#1838` bash sentinel은 production 통합 전에 다시 고른다. Phase A의 첫 vertical slice에 섞지 않는다.

관측 카피 `~/repos/3rd/pi/prime-agent`는 건드리지 않는다. Python runtime은 oracle로 남긴다.

---

## Phase A가 묻는 것

Prime Agent에서 Python은 단순 계산 tool이 아니다. persistent workspace 안에서 context를 값으로 붙잡고, 함수를 만들고, shell과 file을 조합하고, `rlm()`으로 citizen 내부 child를 여는 orchestration language다.

Phase A의 질문은 하나다.

> 이 workspace를 Lisp으로 실제 교체했을 때 모델과 GLG가 어떤 form을 만들며, Python 번역을 넘어선 공존언어의 다음 길이 보이는가?

성능 우월성, 범용 agent language, Entwurf core 통합을 증명하지 않는다.

```text
Entwurf = citizen 사이 address / delivery / receipt
RLM     = 한 citizen 안의 programmable orchestration
Lisp    = 그 workspace에서 살아 움직이는 form의 언어
Emacs   = 이후 인간과 agent가 그 form을 함께 보는 작업면
```

Phase A는 REPL 언어축만 먼저 연다. steering form과 Emacs 공존면을 미리 하나로 합치지 않는다.

---

## 결정 — Clojure source, GraalVM native runtime, SCI evaluator

Runtime process는 JVM 위에서 돌리지 않는다.

```text
Clojure source
  → GraalVM native-image
  → native executable
      └─ SCI가 model-generated Clojure form을 평가
```

- Runtime 자체는 Clojure로 작성하고 AOT/native-image로 묶는다.
- 실행 시 JVM을 띄우지 않는다.
- `clojure.core/eval`을 native-image의 열린 runtime evaluator로 쓰지 않는다.
- model-generated form은 persistent SCI context에서 평가한다.
- Babashka는 이 조합의 선행 구현이자 API/패키징 참고선이다.

SCI subset이 최종 언어라는 결정은 아니다. 실제 workload에서 범위가 부족하면 그 사실을 `semantics-gap`으로 남기고 evaluator 또는 Lisp 계열을 다시 고른다.

### 왜 이 조합인가

1. Clojure form과 immutable data가 같은 표현 세계에 있다.
2. GraalVM native process는 GLG의 기존 운영 경험과 맞는다.
3. SCI는 native-image 안에서 persistent dynamic evaluation을 제공한다.
4. capability를 namespace/Var 노출면으로 제한할 수 있다.
5. 이후 Emmy/SICMUtils 계보를 AOT 포함하고 선택한 Var를 SCI에 열 수 있다.

---

## 디렉터리

Python oracle과 병렬로 둔다.

```text
prime-agent-runtime-clj/
  deps.edn
  src/rlm/repl.clj          ; JSONL reader, queue, event writer, lifecycle
  src/rlm/eval.clj          ; persistent SCI context, result/error/output
  src/rlm/core.clj          ; host-request, rlm callable, public bindings
  src/rlm/process.clj       ; bash/process capability — 첫 slice 뒤
  src/rlm/emmy.clj          ; Emmy adapter — Hop 3에서만
  test/rlm/repl_test.clj
  test/rlm/host_bridge_test.clj
  native-image/             ; reflection/resource/build metadata
```

한 폴더에 Python과 Clojure source를 섞지 않는다. Python 삭제는 Phase A 범위 밖이다.

---

## 첫 vertical slice

### 프로세스 구조

```text
stdin reader
  ├─ host_reply ─────────────→ pending host promise resolve
  ├─ interrupt ──────────────→ active evaluation cancel request
  └─ ordered requests ───────→ one-at-a-time request queue
                                  │
                                  ▼
                           persistent SCI context
                                  │
                                  ▼
protocol event writer ─────────→ stdout JSONL
```

첫 slice에서 protocol vocabulary를 host adapter로 재사용한다. protocol v2 완전 구현이라고 부르지 않는다.

### 구현하는 request

| request | Phase A 동작 |
|---|---|
| `execute` | persistent SCI context에서 forms를 순서대로 평가 |
| `host_reply` | FIFO를 우회해 같은 id의 pending host request를 resolve |
| `interrupt` | active evaluator에 cooperative cancellation 요청 |
| `shutdown` | pending bridge를 실패시키고 process 종료 |

`list_names`, `snapshot`, `restore`는 첫 slice 뒤에 판단한다. Host 실험 구성에서는 snapshot을 끈다.

### 구현하는 event

| event | Phase A 동작 |
|---|---|
| `ready` | banner 없이 첫 JSONL frame |
| `stdout` / `stderr` | SCI `*out*` / `*err*`로 잡힌 text와 cell id |
| `result` | 마지막 form의 non-`nil` 값을 `pr-str`한 text |
| `host_request` | runtime-minted id + typed data |
| `error` | class/name, message, 읽을 수 있는 stack data |
| `done` | id가 있는 request마다 정확히 하나 |

Pinned host가 protocol number만 검사하므로 `ready.protocol`은 2를 유지한다. Python field는 host compatibility placeholder로 두고 Clojure/JVM metadata를 별도 additive field에 싣는다. 정확한 frame은 구현 직전 test fixture에서 고정한다.

### evaluation 규칙

- 한 cell의 source에서 모든 form을 읽고 순서대로 평가한다.
- namespace/context는 process lifetime 동안 유지한다.
- 마지막 값이 `nil`이 아니면 `result`를 보낸다.
- 마지막 값을 `_`에 바인딩한다.
- reader/eval error는 해당 request만 실패시키고 다음 request를 받는다.
- runtime public binding은 `rlm`, `host-request`, process/file helper부터 최소로 연다.
- Java interop와 임의 classpath loading은 첫 slice에서 열지 않는다.

---

## `(rlm ...)` — 먼저 단순하게

첫 API는 동기 호출이다.

```clojure
(rlm "child task")
```

- 호출한 cell은 host reply가 올 때까지 기다린다.
- stdin reader는 별도 rail에서 `host_reply`를 pending promise에 전달한다.
- 반환값은 child의 최종 답이 아니라 admission/spawn handle이다.
- 병렬성이 필요해질 때 model이 실제로 만드는 form을 보고 future/handle 표면을 고른다.

처음부터 `@`, `deref`, macro, CompletableFuture 후보를 동시에 제공하지 않는다. Hop 2에서 실제 사용 흔적을 보고 하나를 고른다.

---

## 출력과 interrupt — Phase A 경계

### 첫 slice에서 보장

- SCI를 통해 발생한 `*out*`/`*err*`는 JSONL event로 감싼다.
- protocol writer는 한 frame을 lock된 한 쓰기로 보낸다.
- malformed request는 protocol error를 내고 reader가 계속 산다.
- cooperative blocking/evaluation은 interrupt 후 error + done으로 끝낸다.

### 아직 보장하지 않음

- native library, subprocess, raw fd 1/2의 완전 capture
- raw output의 `done` 전 marker fence
- pure infinite loop의 CPython/POSIX 수준 강제 interrupt
- finishing `pr-str`와 output drain 사이의 모든 interrupt race

SCI allow-list가 raw Java/native output 경로를 처음에는 닫으므로 vertical slice의 protocol framing을 지킬 수 있다. capability를 열 때 output boundary를 다시 설계한다.

이 항목들의 production parity gap은 `docs/clojure-runtime--parity-audit.md`에 남아 있다 — reference-only이며 gate가 아니다.

---

## Snapshot은 첫 질문이 아니다

첫 slice는 snapshot 없이 process lifetime의 persistence만 본다.

이후 data snapshot을 열 때 지킬 최소 의미:

- binding별 serialize/skip 이유
- per-variable cap과 aggregate cap 구분
- explicit prune는 per-variable oversized 값에만 적용
- payload/manifest staging과 commit
- corrupt/missing payload의 명시적 결과

함수·closure·live process의 복구는 미리 약속하지 않는다. Python dill parity와 다르다면 protocol-compatible이라고 부르지 않고 capability/profile 차이로 기록한다.

Phase A의 질문은 먼저 이것이다.

> 살아 있는 한 process 안에서 Lisp workspace가 agent의 orchestration을 바꾸는가?

---

## Emmy/SICM 공존언어 probe

Emmy 전체를 SCI에 즉시 노출하지 않는다. Hop 3에서 작은 adapter namespace를 AOT 포함한다.

후보 흐름:

```clojure
(def expr ...)
(simplify expr)
(evaluate expr environment)
(->tex expr)
```

확인할 것은 library coverage가 아니다.

```text
자연어 탐색
→ Clojure form
→ symbolic expression
→ numerical evaluation
→ Emacs에서 읽고 고칠 표현
```

이 한 경로가 실제로 이어지는지 본다. SCI가 Emmy macro/protocol/type를 직접 다루기 어렵다면 runtime adapter가 Clojure/JVM-side AOT code를 호출하고 SCI에는 작은 함수 surface만 연다.

---

## Host 연결

Runtime core가 독립 driver에서 통과한 뒤 TypeScript host에 선택면을 연다.

첫 host diff의 범위:

1. native executable spawn
2. runtime별 bootstrap code 선택
3. runtime별 orchestration prompt 선택
4. snapshot disabled configuration
5. Python oracle와 Lisp runtime을 같은 workload에서 번갈아 띄울 선택면

`ipython`이라는 기존 tool/display 이름을 Phase A에서 전부 개명하지 않는다. 모델이 Clojure를 쓰도록 만드는 prompt와 bootstrap만 먼저 바꾼다. 이름 정리는 실제 사용 뒤 한다.

---

## Conformance와 실제 workload

### 공통 wire tests

최소 case:

1. `ready`가 첫 frame
2. expression result
3. persistent binding
4. `nil`은 result 없음
5. stdout/stderr attribution
6. reader/eval error 뒤 다음 execute
7. malformed JSON 뒤 계속 serving
8. host request/reply bypass
9. `(rlm ...)`이 handle 반환
10. shutdown/EOF

Python source와 Clojure form을 같은 문자열 fixture로 만들지 않는다. 공통 case에 language-specific code와 공통 event assertion을 둔다.

### Prime Agent 실제 workload

Entwurf #88 합본의 사용자 실측을 Lisp으로 다시 친다.

1. **Tool이 아니라 world인지** — public runtime bindings와 child registry를 Lisp 값으로 조사한다.
2. **Prompt-as-variable** — 문서를 workspace 값으로 붙잡고 필요한 heading만 계산한다.
3. **`rlm()`은 답이 아님** — child 둘을 열고 반환 handle을 그대로 관찰한다.
4. **inside harness boundary** — 파일과 줄을 값에 바인딩하고 Entwurf/RLM 경계를 계산한다.

실패는 셋 중 하나로 라벨링한다.

- `semantics-gap` — SCI/Clojure runtime이 workload 의미를 표현하지 못함
- `model-fumble` — 가능한 form인데 모델이 Clojure surface를 잘못 사용함
- `harness-gap` — host/bootstrap/prompt/protocol adapter가 필요한 기능을 전달하지 못함

---

## 코드량

날짜 일정은 쓰지 않는다. handwritten diff의 order만 본다.

| 묶음 | 구현 | 테스트·설정 |
|---|---:|---:|
| JSONL reader/dispatcher | 200–350 LOC | 150–250 LOC |
| persistent SCI eval | 150–250 | 100–200 |
| result/error/output | 100–200 | 100–180 |
| host bridge + `rlm` | 150–250 | 120–220 |
| lifecycle | 80–150 | 80–150 |
| native-image | 50–150 | 30–80 |
| TypeScript host 선택면 | 150–300 | 150–250 |
| Emmy probe | 100–250 | 80–150 |

Phase A 전체 경보기:

- 구현 약 1,000–1,900 LOC
- 테스트·설정 약 800–1,400 LOC
- 전체 약 1,800–3,300 LOC

상단을 넘으면 full parity, broad package migration, premature abstraction이 stem에 섞였는지 먼저 본다.

---

## GLG 개입 홉

기계적인 구현 결정으로 GLG 홉을 소비하지 않는다.

### Hop 1 — workspace

Persistent forms로 실제 문서·값·함수를 다뤄보고 Clojure/SCI의 촉감을 판단한다.

### Hop 2 — direction

실제 child call을 보고 `(rlm ...)`의 동기값, handle, future 표면 중 다음 하나를 고른다.

### Hop 3 — coexistence

작은 Emmy adapter로 form → symbolic → numeric/render 경로를 직접 만진다.

### Hop 4 — fork

다음 중 하나를 고른다.

- runtime hardening
- Emmy surface 확장
- evaluator 또는 Lisp 변경
- protocol parity 단계 진입
- 실험 중단

---

## Phase A acceptance

1. Model이 persistent Lisp form으로 실제 작업을 조직한다.
2. 문서를 값으로 붙잡고 필요한 조각만 계산한다.
3. `(rlm ...)`이 citizen 내부 child를 열고 handle을 돌려준다.
4. GLG가 같은 form을 읽고 고칠 수 있다.
5. 다음 설계 판단에 쓸 성공/실패 흔적이 남는다.

다음은 acceptance가 아니다.

- CPython보다 빠름
- 92 tests 전체 green
- Python snapshot 완전 호환
- 모든 Java/Clojure library 노출
- Entwurf steering form 완성
- Emacs live world 통합 완성

---

## 순서

1. `prime-agent-runtime-clj` scaffold와 native executable
2. persistent SCI execute/result/error/output
3. host-request와 동기 `(rlm ...)`
4. 공통 wire cases
5. TypeScript host 선택면
6. 네 개 실제 workload
7. GLG Hop 1·2
8. 작은 Emmy adapter
9. GLG Hop 3·4

첫 구현 단위는 1–3을 관통하는 vertical slice다. 파일별 완성 순서로 쌓지 않는다.

---

## 금지선

- Python oracle 삭제 금지
- `~/repos/3rd/pi/prime-agent` 수정 금지
- Phase A를 protocol v2 완전 호환이라고 부르지 않기
- 새 steering DSL을 미리 설계하지 않기
- Entwurf core에 runtime semantics 넣지 않기
- main의 host repair/bash 변경을 무계획 cherry-pick하지 않기
- 날짜 단위 일정 제시하지 않기
- GLG 승인 없이 commit/push하지 않기
