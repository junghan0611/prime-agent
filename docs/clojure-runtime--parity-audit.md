# Clojure RLM runtime — parity audit

Author: gpt-5.6-sol (pi, 2026-08-29) — not GLG direct.

> **STATUS: REFERENCE-ONLY — NOT A ROADMAP, BACKLOG, OR ACCEPTANCE GATE.**
>
> 이 문서는 **폐기된** production-parity framing을 감사한 기록이다. 여기 적힌 gap은
> 작업 권한이 아니다. 항목을 RAIL/NOW로 올리려면 **측정된 Phase A workload failure**와
> **GLG의 명시적 승격**이 함께 있어야 한다. test parity 자체는 승격 근거가 아니다.
>
> 이 문서를 여는 때는 하나뿐이다 — 측정된 workload gap이 parity 문제인지 판독할 때.

## 판정

현재 설계는 그대로 구현하면 안 된다. JSONL의 요청/이벤트 **이름**은 옮길 수 있지만, protocol v2의 실행·interrupt·출력 격리·snapshot 의미 계약까지 유지한다는 주장은 아직 성립하지 않는다.

막는 항목은 세 개다.

1. JVM `Thread.interrupt()`는 순수 동기 계산 루프를 중단하지 못한다. Linux CPython v2가 보장하는 동기 셀 interrupt를 `best-effort`로 낮추면 호환 구현이 아니다 (`repl.md:87-123`).
2. `*out*`/`*err*` 바인딩만으로는 `System.out`, native library, subprocess, raw fd 출력이 JSONL stdout을 오염하지 않는다는 계약과 `done` 전 drain fence를 충족하지 못한다 (`repl.md:9-24,71-76`; `repl.py:1109-1129`).
3. 데이터 전용 snapshot은 함수까지 되살리는 현재 snapshot 의미와 oracle을 깨뜨린다. `ReplTest.test_snapshot_restore_roundtrip`은 `bump` 함수를 저장·복구해 호출한다 (`test_repl.py:564-605`; `repl.md:141-160`).

이 셋을 먼저 설계 결정으로 닫아야 한다.

## 틀린 계약 문장

### 1. `docs/clojure-runtime.md:68`

> 프레임 스키마는 그대로.

틀렸다. 이름 목록만 같다고 스키마가 같은 것이 아니다.

- `ready`에는 protocol v2 기준 `python` 문자열이 있다 (`repl.md:45-46`). Clojure runtime이 무엇을 보낼지 없다.
- `result.text`는 trailing expression의 `repr`이며 값은 `_`에 다시 바인딩된다 (`repl.md:53-55`). Clojure의 multi-form 판독, 마지막 form, `nil`, `pr-str`, `_` Var 규칙이 없다.
- `error`의 `ename`, `evalue`, `traceback` 형식과 runtime frame 제거 규칙이 없다 (`repl.md:63`, `78-85`).
- snapshot manifest에는 `pythonVersion`이 있다 (`repl.md:151-154`). JVM/Clojure manifest에서 이 필드를 유지할지 새 필드를 더할지 결정되지 않았다.

수정 방향: “요청/이벤트 vocabulary와 JSONL framing은 유지한다”로 좁히고, 위 네 항목의 Clojure 대응 스키마를 별도 표로 고정해야 한다.

### 2. `docs/clojure-runtime.md:114`

> 데이터 바인딩만 edn 또는 nippy. 살아 있는 fn·async 태스크는 복구 안 함 (Python dill도 다수 skip)

호환 계약으로는 틀렸다. Python이 일부 값을 skip한다는 사실은 함수 복구를 포기할 근거가 아니다. 현재 oracle은 일반 함수 `bump` 복구를 요구하고, host는 namespace가 resume 뒤 되살아난다고 전제한다 (`state-snapshot.ts:1-8`).

수정 방향은 둘 중 하나다.

- protocol v2 호환을 주장하려면 최소한 top-level 함수/정의 복구의 범위와 검증법을 제시한다.
- 데이터 전용으로 제한하려면 이를 의도적 비호환으로 분류하고 protocol/runtime capability 및 host 안내 문구를 바꾼다. 같은 v2라고 부르면 안 된다.

`edn 또는 nippy`도 설계 결정이 아니다. serializer 하나, 지원 값 집합, per-variable byte 산정, aggregate cap, format version, 이전 snapshot 처리 방식을 고정해야 한다.

### 3. `docs/clojure-runtime.md:115`

> `*out*` / `*err*` 바인딩 + 프로토콜 `id`. raw fd는 `id: null` (계약 그대로)

앞 절만으로 뒤 절이 성립하지 않는다. protocol v2는 원래 fd 1을 private dup으로 보존하고, 실제 fd 1/2를 pipe로 바꾸고, pump가 raw bytes를 `id:null`로 보낸다. 셀 종료 때 marker fence도 기다린다 (`repl.py:1109-1129`; `repl.md:11-24,71-76`).

수정 방향: JVM에서 protocol 전용 writer, OS-level fd 1/2 redirect, UTF-8 incremental decode, stream별 pump, frame write lock, drain marker를 어떻게 만들지 명시한다. 공개 JDK API만으로 할지 JNA/native shim을 둘지도 여기서 결정해야 한다.

### 4. `docs/clojure-runtime.md:116`

> Thread interrupt + SIGINT 핸들러. 동기 루프 중단은 Python Windows 경로와 같이 best-effort

Linux 1차에서 틀렸다. v2의 Linux/POSIX 경로는 동기 bytecode와 interruptible blocking syscall을 중단하고 runtime이 계속 serving한다. best-effort는 Windows fallback의 제한이다 (`repl.md:99-123`). JVM thread interrupt는 `(loop [] (recur))` 같은 계산을 끝내지 않는다.

수정 방향: 다음 중 하나를 먼저 선택해야 한다.

- cooperative check가 들어가는 evaluator/instrumentation,
- 셀별 격리 프로세스와 namespace 재구성,
- interrupt 시 kernel 전체 재시작이라는 명시적 비호환,
- 동기 루프 interrupt 포기와 protocol capability/version 변경.

`eval` + `Thread.interrupt()`만으로 protocol v2 호환은 불가능하다.

### 5. `docs/clojure-runtime.md:128`

> 같은 픽스처를 Clojure 테스트로 다시 쓴다.

“같은 wire assertion”은 맞고 “같은 code fixture”는 틀리다. Python source와 Clojure form은 공유할 수 없다. 공통 conformance case를 `{request sequence, language-specific code, expected event predicates}`로 나눠야 한다.

## 일곱 질문

### 1. pin과 `#1839`

`bc0fa7606`은 **protocol v2 oracle prototype**의 pin으로는 일관된다. production cutover의 장기 기준으로는 부적절하다.

관측한 git 이력에서 main은 이미 `#1836`으로 protocol 3과 nested host-reply envelope를 도입했고, `#1839`는 그 커밋을 parent로 둔다. 따라서 `#1839`를 “프레임 수리만” 가져오는 것은 독립 선택이 아니다. host lifecycle/repair 변경도 크고 v3 계약이 따라온다 (`git show af14f066`, `git show d60fab8a`).

`#1839`를 당길 이유는 강하다. malformed/non-object/unknown/id-less frame을 무시해 active request가 영원히 남는 일을 막고, 손상 runtime을 snapshot restore + bootstrap으로 교체한다. 새 JVM runtime을 붙이는 시점에 가장 필요한 host 방패다.

권고:

- 단계 A를 “bc0의 v2 conformance prototype, production cutover 금지”로 명명한다.
- 실제 host 통합 전 main으로 rebase하고 v3 host-reply envelope까지 맞춘다.
- v2 유지가 절대 조건이면 `#1839` 전체를 끌지 말고 frame validation의 최소 backport를 별도 host 변경으로 설계한다.

`#1838`도 “나중”에 두면 안 된다. phase 3에서 old `bash.py`를 번역하지 말고 ordered completion sentinel의 동작을 처음부터 Clojure `bash()` 계약에 넣어야 한다 (`git show 80902713`).

### 2. 디렉터리

`prime-agent-runtime-clj/` 병렬 배치가 맞다. Python oracle을 실행 가능한 상태로 유지하고 package/dependency/launcher를 분리할 수 있다. `prime-agent-runtime/` 안에서 즉시 언어를 교체하면 비교 기준과 배포 단위가 섞인다.

추가로 정할 것:

- `deps.edn` 개발 실행과 production uberjar 중 host가 실제로 보장할 artifact,
- CWD와 classpath/dependency 정책,
- snapshot payload 확장자가 계속 `.dill`인지 runtime별 basename을 쓰는지,
- cutover 완료 뒤 Python oracle의 보존 위치와 종료 조건.

### 3. `clojure.core/eval` 대 SCI

1차는 `clojure.core/eval`이 맞다. 설계가 요구하는 것은 임의 namespace, Java interop, classpath library를 쓰는 열린 실행 환경이다 (`clojure-runtime.md:78-80`). SCI는 이 범위를 제한하는 별도 제품 결정이다.

단, `eval` 선택은 다음 문제를 해결하지 않는다.

- interrupt 불가능한 순수 계산,
- protocol stdout 격리,
- background task ownership,
- source filename/line이 있는 traceback,
- snapshot 가능한 정의의 범위.

실행 규칙도 고정해야 한다: 모든 form을 끝까지 읽고 순서대로 eval할지, 마지막 non-`nil` 값만 `result`로 낼지, `_`를 언제 갱신할지, reader error 뒤 namespace를 어떻게 유지할지.

### 4. 데이터 전용 snapshot과 16 MiB prune

깨진다. 정확히는 16 MiB 숫자 자체보다 snapshot의 의미가 먼저 깨진다.

Host는 `max_variable_bytes`를 보내고 `pruned`/`skipped`/`bytes`를 소비한다 (`repl-manager.ts:1003-1035`). compaction 뒤에는 `list_names`를 읽어 “remaining variables, imports, and helpers are still available”라고 모델에게 알린다 (`agent-session.ts:7222-7249`). 데이터 serializer가 함수/Var/import를 단순 skip하면:

- 같은 프로세스의 compaction 직후에는 값이 남아 있어 보이지만,
- restart/resume 뒤에는 helper가 사라지고,
- oracle의 함수 roundtrip은 실패한다.

최소 계약은 유지해야 한다.

- per-variable cap 초과만 `pruned`, aggregate cap 초과는 `skipped`지만 live namespace에는 유지,
- payload + manifest staging/commit, manifest 실패 시 prune 금지,
- restore apply의 all-or-nothing 구간,
- interrupt가 commit 뒤 성공을 실패로 오보하지 않음,
- 실제 최종 payload bytes가 aggregate cap 이하.

이 동작은 `repl.md:143-160`, `repl.py:608-894`, snapshot shield classes가 고정한다.

### 5. `await` 문법

가장 실수가 적은 API는 후보 셋이 아니라 **동기 `(rlm "task")`** 다. 실행 셀은 원래 host reply를 기다리며 막혀도 되고, reader가 `host_reply`를 request FIFO 밖에서 resolve하면 deadlock이 없다 (`repl.md:131-139`; `repl.py:973-1016`). 병렬 호출이 필요하면 사용자가 `(future (rlm "task"))`로 감싸면 된다.

반드시 awaitable을 반환해야 한다면 `@(rlm "task")`를 택한다. Clojure 관용구이며 별도 macro vocabulary가 없다. `(await ...)` macro는 새 문법과 error surface를 만들고, 명시 `(deref ...)`는 정확하지만 모델 출력이 길어진다.

어느 쪽이든 반환 타입, cancellation, timeout, detached future에서의 cell-id 상속을 테스트로 고정해야 한다.

### 6. 92 tests의 포함/제외

관측한 class별 수는 `ReplTest` 56, `FinishRequestTest` 9, `SnapshotPruneShieldTest` 2, `RestoreApplyShieldTest` 6, `SnapshotTempCleanupTest` 2, `SnapshotPairConsistencyTest` 14, `OwnerWatchdogTest` 3이다.

**phase 1에서 빼되 이후 동작 계약으로 남길 것:** snapshot/restore shield 33개와 `ReplTest`의 snapshot 7개 안팎은 phase 2, bash integration은 phase 3, MCP import shutdown은 MCP phase로 보낸다.

**Python 메커니즘이라 삭제하고 JVM 동등 테스트로 바꿀 것:**

- `pthread_kill` 부재 seam,
- `sys.stdout.buffer`의 int/short-write API,
- `sys.stdout` close/rebind와 fd-number reclaim의 Python 객체 동작,
- `PyCF_ALLOW_TOP_LEVEL_AWAIT`, `linecache`, Python `SyntaxError` formatting,
- broken Python exception `__str__`, non-string `globals()` key,
- Windows process-handle watchdog.

**Python 이름이 들어가도 행동은 빼면 안 될 것:**

- sync blocking과 queued/back-to-back/targeted interrupt,
- raw output이 protocol을 오염하지 않고 `done` 전에 drain됨,
- malformed/deep JSON 뒤 계속 serving,
- duplicate in-flight id 거부,
- `host_reply` bypass와 unknown/late reply drop,
- pending host request가 shutdown/EOF에서 풀림,
- exactly one `done`와 error 뒤 다음 execute,
- POSIX owner watchdog이 busy runtime도 종료함.

특히 `FinishRequestTest`와 snapshot shield는 CPython private 함수 그대로 이식할 대상은 아니지만, parked interrupt와 commit 경계의 상태 전이는 language-neutral contract다. Clojure state-machine unit test 또는 black-box race test로 다시 표현해야 한다.

JVM cold start에 CPython의 `<500 ms` assertion을 그대로 적용하지 말고, production artifact 기준 예산을 별도로 정한다.

### 7. 과한 곳과 빠진 곳

과한 곳:

- 첫 설계에서 `future / CompletableFuture / virtual thread` 셋을 동시에 후보로 둔 것. 실행 모델 하나만 선택해야 한다.
- phase 1 전에 GraalVM/native-image를 논의한 것. 현재처럼 보류가 맞다.
- `harness`까지 runtime 완료 조건에 묶으면서 core wire conformance의 종료 기준을 흐린 것. protocol core와 library surface를 별도 gate로 둬야 한다.

빠진 곳:

1. **Reader/dispatcher 구조:** ordered FIFO와 `interrupt`/`host_reply` bypass, duplicate id, malformed line backstop, exactly-one-`done` 상태기계.
2. **Protocol output 격리:** private protocol channel, fd redirect, pump, JSON frame lock, strict JSON/non-finite payload validation, drain fence.
3. **Interrupt feasibility:** pure loop, blocking Java call, queued interrupt, finishing `pr-str`, output drain, snapshot commit 각각의 취소 정책.
4. **Execution semantics:** multi-form reader, persistent ns, `_`, `nil`, `pr-str`, source line와 sanitized stacktrace, namespace alias/import/listing 규칙.
5. **Task lifecycle:** background future가 cell id를 유지하는 범위, raw Thread는 `null`, uncaught exception 처리, shutdown에서 executor/future 정리.
6. **Host bridge teardown:** runtime-minted unique id, unknown/late reply drop, cell cancellation cleanup, shutdown/EOF가 pending bridge를 먼저 실패시켜 queue deadlock을 푸는 규칙.
7. **Snapshot format:** serializer 단일 선택, 지원 타입, Var metadata, payload version, atomic pair commit, old `.dill` 발견 시 처리, manifest의 `pythonVersion` 대체.
8. **Owner watchdog:** `_winjob.py`를 제외해도 POSIX `PRIME_AGENT_KERNEL_OWNER_PID` watchdog은 제외하면 안 된다. busy eval thread와 무관하게 process를 끝내야 한다 (`OwnerWatchdogTest.test_owner_watchdog_exits_busy_runtime`).
9. **Bash 최신 계약:** process group/orphan journal 외에 `#1838` completion sentinel과 shutdown kill 순서가 필요하다.
10. **통합 기준:** v2 prototype에서 v3 main으로 넘어가는 명시적 gate와 host-reply envelope adapter.

## 설계에 추가할 최소 결정표

구현 전에 아래가 한 값으로 정해져야 한다.

| 항목 | 필요한 결정 |
|---|---|
| integration protocol | v2 prototype만인지, main v3 cutover인지 |
| evaluator | JVM `eval`; 실행 thread 모델 하나 |
| interrupt | pure sync loop를 포함한 보장 또는 명시적 비호환 |
| protocol channel | raw fd를 격리할 구체 수단 |
| result | multi-form + `nil` + `pr-str` + `_` 규칙 |
| ready | `python` 필드 처리와 Clojure/JVM version metadata |
| snapshot | serializer, 지원 타입, format version, 함수 복구 범위 |
| await API | 동기 `(rlm ...)`; 불가하면 `@(rlm ...)` |
| lifecycle | EOF, pending host requests, futures, bash, owner death |
| conformance | 공통 wire assertions + 언어별 code fixture |

이 표가 닫히기 전에는 `prime-agent-runtime-clj/` scaffold 외 구현 범위를 확정할 수 없다.
