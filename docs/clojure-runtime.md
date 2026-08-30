# Prime Agent Lisp runtime — Phase A

Author: grok-4.6 (pi, 2026-08-30) — not GLG direct.
Yesterday's three docs (sol design, opus devenv, sol parity-audit) collapsed here.
Work coordinate is `NEXT--feat_clojure-runtime.md`. This file is the contract.

Fork `junghan0611/prime-agent` · branch `feat/clojure-runtime`.

Production 교체 명세가 아니다. persistent programmable workspace를 Lisp으로 먼저 바꾸고, 모델과 GLG가 그 안에서 만드는 form을 다음 설계의 입력으로 삼는 실험이다.

---

## Pin

| 항목 | 값 |
|---|---|
| fork pin | `bc0fa7606` — protocol v2 oracle. main의 v3 / `#1839` / `#1836` / `#1838`은 통합 전에 다시 고른다 |
| host protocol | JSONL protocol v2 names. 완전 호환이라고 부르지 않는다 |
| Python oracle | `prime-agent-runtime/src/rlm/repl.py` + `repl.md` |
| delivery artifact | `prime-agent-runtime-clj/target/rlm-repl` |
| how to build/run | `prime-agent-runtime-clj/README.md`. env는 `flake.nix` |

Python runtime은 oracle로 남긴다. 삭제하지 않는다. 관측 카피 `~/repos/3rd/pi/prime-agent`는 작업면이 아니다.

두 실행면을 섞지 않는다.

| 면 | 무엇 |
|---|---|
| TypeScript host + CPython | 지금 손으로 쳐보는 기존 `prime` |
| `bin/rlm` / `target/rlm-repl` | native + SCI. 호스트 없음. RAIL 4 전 |

---

## 질문

Prime Agent에서 Python은 계산 tool이 아니다. workspace 안에서 context를 값으로 붙잡고, 함수를 만들고, `rlm()`으로 citizen 내부 child를 여는 orchestration language다.

> 이 workspace를 Lisp으로 바꿨을 때 모델과 GLG가 어떤 form을 만들며, Python 번역을 넘어선 다음 길이 보이는가?

성능, 범용 agent language, Entwurf core 통합을 증명하지 않는다.

```text
Entwurf = citizen 사이 address / delivery / receipt
RLM     = 한 citizen 안의 programmable orchestration
Lisp    = 그 workspace에서 살아 움직이는 form의 언어
Emacs   = 이후 인간과 agent가 그 form을 함께 보는 작업면
```

지금은 REPL 언어축만 연다. steering과 Emacs 공존면을 미리 합치지 않는다.

---

## 결정 — Clojure source, GraalVM native, SCI

```text
Clojure source
  → GraalVM native-image
  → native executable
      └─ SCI가 model-generated Clojure form을 평가
```

- 실행 시 JVM을 띄우지 않는다. JVM이 가운데 있으면 이 브랜치의 도구가 아니다.
- `clojure.core/eval`을 native-image의 열린 evaluator로 쓰지 않는다.
- Babashka는 선행 구현이자 API/패키징 참고선이다.
- SCI subset이 최종 언어라는 결정은 아니다. 범위가 부족하면 `semantics-gap`으로 남기고 evaluator를 다시 고른다.

테스트 SUT는 native만. `:test` / `:test-native`가 `target/rlm-repl`을 띄운다. 테스트 러너 프로세스의 Clojure CLI는 하니스이지 runtime이 아니다. 빌드타임 JDK(native-image·AOT·clj-kondo)도 마찬가지다.

---

## 있는 것

```text
prime-agent-runtime-clj/
  deps.edn
  bin/rlm                     ; Hop 1 손잡이. JSONL을 가리고 form을 친다
  native-image/build.sh
  src/rlm/repl.clj            ; JSONL reader, queue, event writer, lifecycle
  src/rlm/eval.clj            ; persistent SCI context
  src/rlm/core.clj            ; host-request, rlm
  src/rlm/io.clj              ; H3 bounded read-text
  src/rlm/process.clj         ; H4 process lifecycle + registry
  test/rlm/*.clj
```

`emmy.clj`는 없다. H8 뒤에만 생긴다.
한 폴더에 Python과 Clojure를 섞지 않는다.

---

## Protocol slice — 구현된 것

첫 slice는 vocabulary를 host adapter로 재사용한다. protocol v2 완전 구현이 아니다.

| request | 동작 |
|---|---|
| `execute` | persistent SCI context에서 forms를 순서대로 평가 |
| `host_reply` | FIFO를 우회해 같은 id의 pending host request를 resolve |
| `interrupt` | 파싱한다. 취소를 보장하지 않는다 |
| `shutdown` | pending bridge를 실패시키고 process 종료 |

`list_names`, `snapshot`, `restore`는 없다. Host 실험에서는 snapshot을 끈다.

| event | 동작 |
|---|---|
| `ready` | 첫 JSONL frame. `protocol` 2, `python` = `"clojure-native"` (v2 host gate placeholder), additive `runtime` field |
| `stdout` / `stderr` | 셀 끝 batch flush. cell id attribution은 맞다. mid-cell streaming은 아니다 |
| `result` | 마지막 form의 non-`nil` 값을 `pr-str` |
| `host_request` | runtime-minted id + typed data |
| `error` | `ename`은 SCI wrapper class, traceback에 runtime frame이 실린다 (OPEN) |
| `done` | id가 있는 request마다 정확히 하나 |

### evaluation

- 한 cell의 모든 form을 읽고 순서대로 평가한다.
- context는 process lifetime 동안 유지한다.
- 마지막 값이 `nil`이 아니면 `result`를 보낸다. `_`에 바인딩한다.
- `(def x 41)`의 마지막 값은 nil이 아니라 SCI var다. 첫 셀에 `result`가 나온다. Python `x = 5`와 다르다.
- reader/eval error는 해당 request만 실패시키고 다음 request를 받는다.
- public binding은 `rlm`, `host-request`, `read-text`(H3), `process-*`(H4)만. Java interop·classpath loading은 열지 않는다.

### `(rlm ...)`

```clojure
(rlm "child task")
```

동기 호출. 반환값은 child의 최종 답이 아니라 admission/spawn handle.
`future` / `@` / `deref` 후보는 동시에 열지 않는다. Hop 2에서 실제 form을 보고 고른다. 지금 `(future 1)`은 닫혀 있다 — 보안 경계가 아니라 Hop 2 안건.

---

## Capability 경계 — native delivery

어제 같은 소스를 JVM SCI로 돌리면 인스턴스 interop이 열렸다 (`(.toUpperCase "ab")` → `"AB"`). 그 SUT는 지웠다. 두꺼워서다.

지금 고정하는 닫힘 (native `target/rlm-repl`):

| form | native |
|---|---|
| `(.toUpperCase "ab")` / `(.length "abc")` | error |
| `(slurp …)` / `(System/getProperty …)` / `(future 1)` / `(Thread/sleep 1)` | error |

`native-image/`에 reflect-config가 없다. 닫힘은 명시적 allow-list가 아니라 reflection metadata 부재로 보인다(인과는 추정, 닫힘 자체는 테스트가 고정). reflect-config를 넣으면 경계가 조용히 열린다.

Framing은 SCI allow-list가 raw Java/native output을 닫고 있어서 지킨다. capability를 열 때 output boundary를 다시 설계한다. 우연한 native closure를 보안 계약이라고 부르지 않는다.

### H4가 바꾼 신뢰 모델 — 읽고 넘어가지 말 것

`(process-start "cmd")`는 shell을 연다. `(read-text)`의 workspace 루트 제한도, `slurp`/`spit` 닫힘도 그 shell 안에서는 성립하지 않는다. `cat /etc/passwd`도 `echo x > /tmp/y`도 process 경유로 된다.

이건 우회가 아니라 H4가 연 capability 그 자체다. NEXT의 결정대로 여기서부터 경계는 **OS permission**이지 runtime allow-list가 아니다. H3 symlink deviation을 보안 경계로 승격하지 않는 이유도 같다.

H4는 write **경로**를 열었을 뿐, H5의 write receipt는 구현하지 않았다. 둘은 합치지 않는다.

---

## Process lifecycle — H4

Python oracle의 `bash()` handle(`prime-agent-runtime/src/rlm/bash.py`)을 참고선으로 삼되 parity는 아니다.

| form | 반환 |
|---|---|
| `(process-start "cmd")` | handle snapshot map. 기다리지 않는다 |
| `(process-poll id)` | 같은 shape의 새 snapshot |
| `(process-tail id)` / `(process-tail id n)` | 마지막 n줄 (기본 50, 최대 2000) |
| `(process-kill id)` | tree 종료 후 snapshot |
| `(process-list)` | registry 전체 snapshot vector |

snapshot은 plain data만 담는다:

```clojure
{:process-id "p1" :command "…" :pid 1706302 :status :exited
 :exit-code 3 :killed false :output-bytes 9 :output-truncated false}
```

- **SCI는 live process를 절대 받지 않는다.** `java.lang.Process`는 runtime map의 `:processes` registry에만 산다. workspace가 쥐는 것은 `:process-id` 문자열뿐이고, interop이 닫혀 있으니 그 map에서 객체로 돌아가는 길이 없다.
- **stdout/stderr는 pipe로 합쳐 bounded buffer에 담는다.** head 128 KiB + tail 128 KiB, 가운데는 버리고 `... [N bytes dropped] ...`를 남긴다. child가 protocol fd를 상속하지 않으므로 위조 frame이 프레임 스트림에 뜰 수 없다. stdin은 spawn 직후 닫는다 (child가 protocol 입력을 훔치지 못한다).
- **cleanup은 세 경로 모두 덮는다.** `shutdown` 요청, stdin EOF, 그리고 runtime 자신에 대한 SIGTERM(shutdown hook). 세 개 다 native 테스트가 pid로 확인한다.
- **descendant 정리는 `ProcessHandle.descendants()`다.** leader를 죽이기 전에 스냅샷을 뜨고(죽이면 자식이 reparent 되어 목록에서 사라진다), TERM → 2s → KILL. sweep 중간에 태어난 손자는 한 번 더 훑지만, 완전히 detach 한 double-fork daemon은 놓칠 수 있다.
- **cap:** live 16개, registry 64개(초과 시 끝난 것부터 정리).

H4가 하지 않은 것 (oracle과의 차이):

1. **process group / session 격리 없음.** oracle은 POSIX에서 `start_new_session` + orphan journal로 트리를 가둔다. 여기는 `setsid`도 journal도 없다. `descendants()` 스냅샷이 유일한 회수 수단이다.
2. **SIGKILL 받은 runtime은 정리하지 못한다.** shutdown hook이 안 돈다. oracle의 host reaper에 해당하는 장치가 없다.
3. **`wait`가 없다.** `Thread/sleep`이 닫혀 있어 한 셀 안에서 완료를 기다릴 방법이 없다. 모델은 다음 셀에서 `process-poll` 해야 한다. 한 셀 안 busy-loop는 runtime을 붙잡는다 — `interrupt`가 취소를 보장하지 않으므로 실제 위험이다. bounded `wait`를 열지 말지는 H4 게이트 밖이라 열지 않았다.
4. **mid-cell streaming 없음.** 출력은 `process-tail`을 부를 때만 보인다.

---

## 코드를 읽어야만 알던 것

1. protocol writer에 `*out*`을 쓰면 안 된다. `-main`이 `*out*`/`*err*`를 stderr로 재바인딩한다. 디버그 `println` 하나가 프레임을 찢는다. **테스트가 없다.**
2. 테스트 러너는 네임스페이스를 명시 require 한다. 파일만 추가하면 조용히 안 돈다.
3. FHS 안에서 빌드해도 바이너리 interpreter는 nix store glibc다. ubuntu에서 빌드하면 `/lib64`. GitHub에서 이미지는 만들지 않는다.
4. AOT는 `target/classes`로 간다. `.gitignore`가 `target/`만 덮기 때문이다.
5. `deps.edn`의 `:test`는 **native**다. 바이너리가 없으면 실패하는 게 맞다.
6. `build.sh`의 `--initialize-at-build-time`(인자 없음)을 패키지 목록으로 "고치면" SCI가 런타임에 `core__init`을 못 찾는다.
7. `bin/rlm`은 Hop 1 손잡이다. TypeScript host가 아니고 제품 REPL도 아니다.
8. **reflective interop은 링크는 되고 native에서 죽는다.** `(:import [java.lang ProcessBuilder])` 뒤의 생성자, 그리고 리터럴에 직접 붙인 `^java.util.List` 힌트는 둘 다 `RT.classForName`으로 컴파일되고 image에는 그 클래스가 없다 (`ClassNotFoundException`). 힌트는 **local에** 붙인다. `build.sh`가 AOT를 `*warn-on-reflection* true`로 감싸는 이유다 — reflect-config는 계약상 닫혀 있으므로 이 경고가 유일한 게이트다.
9. `ProcessHandle.descendants()`는 native-image에서 **돈다** (실측: `sh -c "sleep & sleep & wait"`의 자식 2개 회수). `/proc/<pid>/stat`을 `slurp`하는 쪽은 안 된다.

---

## Host 연결 — 아직 없음

Runtime은 독립 driver로 관통했다. TypeScript host에 붙이는 일은 RAIL 4다. 새 capability는 열지 않는다. spawn·bootstrap·prompt·snapshot-off만.

붙는 날의 범위만 적어 둔다. 견적: 구현 220–300 + 테스트 180–250.

1. native executable spawn (`target/rlm-repl`). argv/ready를 테스트로 고정. ready는 protocol 숫자만이 아니라 `runtime.language=clojure`.
2. runtime별 bootstrap — Python 문법 잔류 금지
3. runtime별 orchestration prompt — Python 문법 잔류 금지
4. snapshot disabled. compaction의 `list_names` 경로 **전체 skip**. `list_names`를 구현하지 않는다.
5. Python oracle와 Lisp runtime을 같은 workload에서 고르는 선택면. child `AgentSession`은 같은 runtime을 상속한다.

`ipython` 표시 이름을 Phase A에서 전부 개명하지 않는다.
`(rlm ...)` 반환값은 admission handle이다. child 최종 답의 fan-in(`agent_message` / 파일)이 이 slice에 없으면 harness-gap으로 기록하고 열지 않는다.

---

## Workload와 실패 라벨

Python source와 Clojure form을 같은 문자열 fixture로 만들지 않는다.

Entwurf #88 실측을 Lisp으로 다시 칠 네 개:

1. public bindings와 child registry를 Lisp 값으로 조사한다
2. 문서를 workspace 값으로 붙잡고 필요한 heading만 계산한다
3. child 둘을 열고 반환 handle을 관찰한다
4. 파일과 줄을 값에 바인딩하고 Entwurf/RLM 경계를 계산한다

실패 라벨:

- `semantics-gap` — SCI/Clojure가 의미를 표현하지 못함
- `model-fumble` — 가능한 form인데 모델이 잘못 씀
- `harness-gap` — host/bootstrap/prompt/protocol adapter가 기능을 전달하지 못함

모델이 만든 form이 Clojure로 바뀌지 않았는데 통과한 것은 성공이 아니라 harness-gap이다.

---

## Hops · acceptance

기계 판단으로 GLG 홉을 소비하지 않는다.

1. workspace — persistent forms의 촉감
2. direction — `(rlm ...)`의 동기값 / handle / future 중 하나
3. coexistence — 작은 Emmy adapter, form → symbolic → numeric/render
4. fork — hardening / Emmy 확장 / evaluator 변경 / 중단

Acceptance:

1. 모델이 persistent Lisp form으로 실제 작업을 조직한다
2. 문서를 값으로 붙잡고 필요한 조각만 계산한다
3. `(rlm ...)`이 child를 열고 handle을 돌려준다
4. GLG가 같은 form을 읽고 고친다
5. 다음 설계에 쓸 성공/실패 흔적이 남는다

아닌 것: CPython보다 빠름, 92 tests green, snapshot 호환, 모든 library 노출, steering/Emacs 완성.

코드량 경보기: 구현 1,000–1,900 + 테스트·설정 800–1,400. 상단을 넘으면 parity/hardening이 stem에 섞였는지 본다. 지금 src 664 + test 883 + native-image 21 (H4 기준).

---

## 금지선

- Python oracle 삭제
- Phase A를 protocol v2 완전 호환이라고 부르기
- 새 steering DSL을 미리 설계하기
- Entwurf core에 runtime semantics 넣기
- main의 host repair를 무계획 cherry-pick
- 날짜 단위 일정
- GLG 승인 없이 commit/push
- JVM을 작업면으로 쓰기
- convenence로 capability 열기 (`id` · `mode` · `install/retract` · native +/- 테스트가 함께 오지 않으면 거부)
- reflect-config / 광범위 Java interop (경계 변경, 별도 GLG gate)
- Host adapter에 Python API/이름/await 의미를 주입하기
