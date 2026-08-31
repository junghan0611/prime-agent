# AGENTS.md — 이 포크를 맡는 에이전트에게

> **이것은 fork 다.** 상류는 [PrimeIntellect-ai/prime-agent](https://github.com/PrimeIntellect-ai/prime-agent).
> 상류 유지보수 규칙은 [AGENTS.upstream.md](./AGENTS.upstream.md) 에 그대로 보존돼 있고 **여전히 유효하다** —
> 코드 품질, 커밋 규율, provider 추가 절차, daemon 프로토콜 규칙은 거기가 SSOT 다.
> 이 문서는 그 위에 **이 포크가 무엇을 하려는지**를 얹는다.
> 이 문서는 **명시적으로 이름 붙인 fork-specific divergence 에서만** 상류를 우선한다.
> 그 밖의 코드 품질, 커밋·릴리즈, provider, daemon protocol, 병렬 git 규칙은 `AGENTS.upstream.md` 가 SSOT 다.

> **방향.** 이 포크는 Prime Agent 의 persistent RLM workspace 를 Clojure/SCI 로 **실제로 선택·실행 가능한 팔**로 만들어,
> Python 과 같은 일을 **어디까지 할 수 있고 어디서 달라지는지를 측정하는 실험**이다.
> 이는 CPython 기능 복제가 아니고 Prime Agent 일반 개선도 아니다.
> H1–H8 은 그 실험이 **말할 자격**을 얻는 이식·비교 레일이다.
> RLM = Prime Agent 의 **persistent REPL workspace 루프** — 모델이 셀을 실행하고 그 상태가 대화를 가로질러 남는다.
> 작업은 전부 `feat/clojure-runtime` 에서 이어간다 — **새 브랜치로 자르지 않는다.** H1–H8 은 rollback checkpoint 다.
> 무엇을 어떻게 돌리는지는 `./run.sh help` — 명령은 문서가 아니라 거기 산다.
> 지금 위치와 다음 한 걸음은 [NEXT--feat_clojure-runtime.md](./NEXT--feat_clojure-runtime.md),
> 판은 [issue #1](https://github.com/junghan0611/prime-agent/issues/1).

## North Star — 뚫고, 재는 것

이 포크는 Prime Agent 를 더 좋게 만드는 프로젝트가 아니다. **Lisp workspace 로 원저자의 RLM 루프가 서는지를 보는 것**이다.

- **CPython 을 복제하지 않는다.** 목표는 Python kernel 과 같아지는 것이 아니라, Lisp workspace 에서 같은 일이 되는지, 어디서 안 되는지를 정직하게 아는 것이다.
- **먼저 뚫고, 그다음 잰다.** 이식이 서기 전에는 커버리지를 미룰 수 있다. 서고 나면 **반드시** 잰다. 순서를 바꾸는 것은 되지만 재는 단계를 건너뛰는 것은 안 된다.
- **커버리지는 절차가 아니라 말할 자격이다.** *"커버리지가 되면서 가능성이 있을 때 가능성은 진짜 가능성이 된다. 잘못하면 그냥 사기가 되어버린다."* (GLG) 커버리지 없이 성능이나 우위를 말하지 않는다.
- **자연어는 탐색, Lisp 는 공개 상태·계약·form.** 모델이 무엇을 했는지는 workspace 의 form 으로 남아야 하고, 서술로 대신하지 않는다.
- **최종 성공은 GLG 가 workspace form 을 읽고 다음 설계를 판단할 수 있는가**다. **현재 단계의 통과 조건은** Python 계약 커버리지를 Clojure 에서 테스트로 대응해 **그 말을 할 자격을 얻는 것**이고, 그다음 양-arm 평가는 모델 행동을 재는 **별도 증거**다. 테스트 개수나 속도 하나만으로 성공을 선언하지 않는다.
- **새 capability 는 게이트를 통과한 뒤에만.** 얼개가 있다고 기능을 늘리지 않는다.
- **Python oracle 을 지운다는 선택지는 없다.** 두 팔이 같이 있어야 비교가 성립한다.
- **실패는 결론이 아니라 분류할 관측이다.** Clojure arm 의 FAIL 은 `semantics-gap` / `model-fumble` / `harness-gap` 중 **영수증이 지지하는 것**으로만 기록한다. prose-only 통과나 Python fallback 은 Lisp 성공이 아니다.
- **이 포크는 한 citizen 내부 computation 실험이다.** Entwurf 의 address·delivery·receipt 를 바꾸지 않고, **형제 사이 메시지를 실행 가능한 form 으로 승격하지 않는다.** coordination 과 computation 의 경계는 [entwurf#88](https://github.com/junghan0611/entwurf/issues/88) 의 전제이며, Emmy/steering 은 이 레일의 측정 뒤 **별도 GLG 게이트**다.

## Architecture — 이 포크가 건드리는 곳

- **커널 런타임이 둘이다.** `python`(상류 CPython/IPython)과 `clojure`(GraalVM native-image 위 SCI). 선택은 `PRIME_AGENT_KERNEL_RUNTIME`, **기본값은 `clojure`**(H8, `edc3a3e8`). **fallback 하지 않는다** — 바이너리가 없으면 teaching error 로 뜬다.
- **`prime-agent-runtime-clj/`** — Clojure workspace. `rlm-repl` 네이티브 바이너리, verb 는 `host-request`/`rlm`/`rlm-children`/`rlm-delete-child`/`read-text`/`write-text`/`edit-text`/`process-{start,poll,tail,kill,list}` (`rlm.eval` 의 `sci/init` 바인딩 맵). SCI 샌드박스라 `spit`/`slurp`/interop 은 닫혀 있다.
- **`prime-agent-runtime/`** — Python workspace. **oracle 이다.** 이 포크에서 지우거나 개명하지 않는다.
- **호스트(`packages/coding-agent`)는 런타임 중립을 지향한다.** `agent_message` 경로에서는 host verb 가 런타임과 무관하다고 코드가 명시한다 (`agent-session.ts` 의 `agentMessageAnnouncedToModel` 게이트 주석). **다른 verb 로 일반화하지 않는다** — 표의 named test 와 실행 결과가 나오기 전에는 각 행에서 따로 확인한다. 런타임 분기는 필요한 곳에만 두고, Python 분기는 건드리지 않는 것이 기본이다.
- **실행파일 주입이 비대칭이다.** python 은 `python` **옵션 인자**로 들어가지만, clojure 는 팩토리 인자가 없다 — 오버라이드 경로가 `PRIME_AGENT_CLOJURE_RUNTIME` env 뿐이다 (`runtime.ts` 의 `resolveClojureRuntimeExecutable()`). **env 가 없으면 candidate 경로에서 찾고**, 없으면 빌드 힌트를 담은 teaching error 를 던진다. 테스트를 두 arm 으로 돌릴 때 이 비대칭을 갈라야 한다.
- **바이너리를 먼저 확보한다.** `prime-agent-runtime-clj/target/rlm-repl` 은 **gitignore 된 빌드 산출물**이다. 없으면 `./run.sh build`, 또는 `PRIME_AGENT_CLOJURE_RUNTIME` 로 경로를 준다. **clean checkout 에서 첫 손이 여기서 막힌다.**
- **현재 CI 는 clojure 축에서 lint 전용이다** (`.github/workflows/clojure-runtime.yml` — GitHub 에 GraalVM 이 없다). native receipt 가 필요한 변경은 로컬 native SUT 로 남기며, TS 는 바이너리 없이도 초록이어야 한다. **CI 가정을 바꾸기 전에는 workflow 를 다시 측정한다.**

## Hard Rules

1. **H1–H8 재감사의 한 행은 커버리지 대응과 kill receipt 를 둘 다 갖는다.** **커버리지 열** = Python 이 지키던 계약이 Clojure 에 대응되는가. **kill receipt 열** = 그 계약을 고의로 깨면 그 테스트가 죽는가. 둘 중 하나만 고르자는 논쟁을 다시 열면 **둘 다 놓친다** — 판정은 이슈 #1 에 박혀 있다.
   (H2 formal receive 행은 `BASELINE.md` Q-R3 의 PASS 조건 — explicit capability · child identity · notice/transcript/observe 0 · 양 arm 동일 계약 — 을 named test 로 고정하는 **무료 정확성 행**이며, 유료 양-arm 평가와는 별개다.)
2. **행의 단위는 하나의 observable scenario 다.** 파일도, grep 개수도, test 함수도 아니다. 한 Python 파일은 여러 행을 낼 수 있다. 하나의 named test 가 여러 행을 덮을 때는 각 행이 그 테스트의 **서로 다른 assertion 또는 receipt fragment** 를 가리켜야 한다. **하나의 green 결과를 근거 없이 여러 계약의 PASS 로 재사용하지 않는다.** 같은 이유로 pytest 개수 대 deftest 개수의 비(`261:65`)는 **경보**지 커버리지 근거가 아니다.
3. **"없다고 적는 것"은 커버리지가 아니다.** 제품 구멍을 계약서에 선언하는 것은 거짓 parity 를 막는 scope 선언일 뿐 PASS 칸을 만들지 못한다. `DECISION REQUIRED` 는 미완료이고, explicit exclusion 은 negative-contract 가 PASS 여도 supported coverage PASS 가 아니다.
4. **비용 상한을 에이전트가 정하지 않는다.** 비용이 드는 작업은 **목적 · model · arm · run 수와 예상 비용을 먼저 GLG 에게 알린다.** 돈을 이유로 범위를 줄이지 않는다. 비용·provider 의 **현재 승인 상태는 이슈 스레드나 GLG 의 현재 지시에서만 읽고, 이 문서에 고정하지 않는다.**
5. **문서를 늘리지 않는다.** 진행·표·영수증·결정은 **이슈에 공개로** 쌓는다. 그래야 서로 봐주면서 돕는다. `docs/` 아래 새 문서는 deprecated 되기 쉬우니 만들지 않는다 — 그래서 `BASELINE.md` 는 루트에 있다.
   재현 가능한 평가의 `evals/<name>/` probe·runner·analyzer 와 그 commit-pinned 결과는 **실행 artifact 이지 금지 대상이 아니다.** 다만 새 진행표·결정·영수증 문서로 자라지 않으며, 진행 상태와 결정은 이슈 #1 에 남긴다.
   이슈 댓글의 authorship 표기, temp body-file, preview 규칙은 `AGENTS.upstream.md` 의 GitHub Workflow 를 따른다.
6. **영수증 없는 문장은 사실이 아니라 가설이다.** **세션·이슈·형제 handoff 를 건너는** 사실 문장에 증거 상태를 단다 (자기 턴 안의 작업 메모는 해당 없음): 여기서 측정함 / `file:line` 에서 읽음 / 외부 산출물에서 읽음(경로) / 상속했고 미확인(출처). 호스트 로컬 경로의 영수증은 건너가지 못한다 — 결정적 줄을 건너가는 산출물에 붙여 넣는다.
   **줄번호로 가리키지 않는다 — 문서든 소스든.** 문서는 제목·규칙 이름으로, 소스는 **심볼 이름**(함수·상수·테스트 이름)으로 앵커한다. 줄번호는 다음 커밋에 낡고 인용한 쪽은 그것을 모른다 (2026-08-31 에 실제로 끊겼다). 같은 이유로 **명령 문자열도 문서에 적지 않는다** — `./run.sh` 가 SSOT 다.
7. **주장을 은퇴시키지 사람을 은퇴시키지 않는다.** 영수증 없는 주장의 답은 "틀렸다"가 아니라 "영수증이 없어서 내가 쟀다"이다.
8. **`git stash` 를 쓰지 않는다.** 여러 형제가 같은 트리에서 일한다 — stash 는 남의 변경까지 함께 보관한다. 격리가 필요하면 임시 worktree 를 쓰고 지운다.
9. **테스트는 GLG 가 지시할 때만, 특정 파일만.** `npm test` 전체는 금지다 (`AGENTS.upstream.md` 의 Commands 절). **어떻게 돌리는지는 `./run.sh help` 가 SSOT 다** — `test` / `test-native` / `lint` / `check`. 코드 변경 뒤 `npm run check` 의무도 upstream 을 따른다.
10. **에이전트는 요청된 활성 commit workflow 안에서만 커밋을 실행할 수 있다.** push 시점과 실행은 **GLG 의 현재 세션 지시**가 있어야 한다. 커밋 요청이 푸시를 함의하지 않는다.
11. **Do not touch:** Python oracle 삭제, `ipython` 개명, `list_names`, snapshot/restore, `spit`/`slurp`, Emmy. 열려면 GLG 승인이 먼저다.

## 검증 축 — 무엇이 무엇을 잡는가

| 축 | 무엇을 비교 | 비용 | 잡는 것 |
|---|---|---|---|
| **무료 로컬 vitest** | 두 arm 의 기계 계약 | 0 | 프로토콜 게이트, 부트스트랩, 레이스·와치독·abort 타이밍, 상태기계, 회귀 |
| **native SUT** (`./run.sh test-native`) | clojure 동작 vs 적어둔 스펙 | 0 | verb 단위 회귀 |
| **kill receipt** | 변경 있음 vs 없음 | 0 | **"이 테스트가 실제로 무는가"** |
| **양-arm 평가** (`evals/`) | 모델 행동 전체 | $ | 프롬프트가 실제로 가르치는가, Python 유출, notice 를 답으로 착각하는 부류 |
| **운영자 인터뷰** (`BASELINE.md`) | 루프 전체, 사람이 판정 | $ + 사람 턴 | 단위테스트가 액자에 안 넣은 파손 |

**무료 축과 유료 축은 겹치지 않는다.** 무료 축은 기계 계약, 유료 축은 모델 행동이다. 그래서 무료 축이 늘었다고 유료 축을 줄이지 않는다.

`deps.edn` 의 `:test` 와 `:test-native` 는 둘 다 native SUT 다 (`prime-agent-runtime-clj/test/rlm/sut.clj`). **JVM SUT 는 없다** — 경위는 `docs/clojure-runtime.md`.

### 이미 알려진 함정

- `grep -c 'deftest '` 는 require 의 `[deftest is]` 를 세어 부풀린다. `^(deftest` 로 앵커한다.
- naive `it(` 는 `it.skipIf(` 를 놓치거나 `toEmit(` 를 잡는다. `^\s*it(\.skipIf)?\(` 를 쓴다.
- **테스트 러너가 네임스페이스를 명시 require 한다** (`docs/clojure-runtime.md` 「코드를 읽어야만 알던 것」 2). 파일만 추가하면 **조용히 안 돈다.** 새 테스트에는 manifest / run receipt 가 따라야 한다.
- **protocol writer 에 `*out*` 을 쓰면 프레임이 찢어진다** (`docs/clojure-runtime.md` 「코드를 읽어야만 알던 것」 1). 디버그 `println` 하나로 깨지는데 **계약서 스스로 "테스트가 없다"고 적어두었다.**
- `handler({ ...data, cellSourceCode })` (`repl-manager.ts` 의 host-request 디스패치) 는 **양 팔 공통의 의도된 provenance 태깅**이다. 이음새로 오해하지 않는다.

## Repository Map — 이 포크가 더한 것

| 경로 | 무엇 |
|---|---|
| `run.sh` | **하나의 진입점** — 두 팔 띄우기(`clj`/`py`)와 재는 법(`test`/`test-native`/`lint`/`build`/`check`). 명령 문자열의 SSOT |
| `prime-agent-runtime-clj/` | Clojure/SCI workspace + native-image 빌드 + native SUT |
| `prime-agent-runtime/` | Python workspace — **oracle**, 유지 |
| `packages/coding-agent/src/core/kernel/runtime.ts` | 런타임 선택과 실행파일 해석 |
| `packages/coding-agent/src/core/kernel/repl-manager.ts` | 커널 프로토콜, host-request 디스패치 |
| `packages/coding-agent/src/core/agent-session.ts` | host verb 등록 게이트, child/notice 회계 |
| `packages/coding-agent/src/core/prompts/rlm.ts` | 런타임별 RLM 프롬프트 |
| `packages/coding-agent/test/repl-kernel-clojure-runtime.test.ts` | clojure 팔 TS 계약 |
| `evals/h7-functional-ab/` | 양-arm 기능 A/B 장치 (probe · runner · analyzer) |
| `BASELINE.md` | 운영자 인터뷰 (Q-R0…Q-R4) |
| `ROADMAP.md` | 제품 홉 H1–H8 |
| `docs/clojure-runtime.md` | **Clojure 런타임 계약서** — 알려진 편차, "코드를 읽어야만 알던 것" |
| `NEXT--feat_clojure-runtime.md` | 브랜치 좌표와 다음 한 걸음 |
| `.github/workflows/clojure-runtime.yml` | clojure 축 CI (clj-kondo lint 전용) |
| `prime-agent-runtime-clj/test/rlm/sut.clj` | native SUT 게이트 (native 아니면 throw) |

## Working Style

- **수술적 변경, 한 번에 한 계약.** 이 변경이 clojure 팔인지, 호스트 중립층인지, Python 팔인지 먼저 정한다. Python 팔은 기본적으로 건드리지 않는다.
- **코드를 읽는 것은 계약과 테스트 위치를 찾을 때까지다.** 품질 평가는 이 포크의 일이 아니다.
- **탭을 쓴다** — TS 는 `biome.json` 이 탭이다. **`.clj` 는 예외로 공백**이고 clj-kondo 는 들여쓰기를 잡지 않는다. 그 밖에는 파일/린터의 기존 스타일을 따른다.
- **형제가 여럿이다.** 여러 모델의 세션이 각자 몇 턴씩만 받는다. 그러니 요약이 아니라 **원본을 가리켜** 브리핑한다. 이슈 본문과 스레드가 다르면 스레드가 이긴다.
- **검수는 협업이지 판정이 아니다.** 리뷰어는 오래 도는 구현자가 남길 수밖에 없는 빈틈을 덮으려 있다. 빈틈이 나온 것은 루프가 도는 것이다.
- **보고는 상태 변화·진단·행동으로 연다.** 자기평가로 열지 않는다. 정정은 짧게 하고 다음으로 간다.
- **한 방향으로 함께 틀릴 수 있다.** 검수자가 여럿이어도 같은 프레임을 공유하면 같이 놓친다. 새 검수자에게는 **"앞선 결론 재확인에 턴 쓰지 마라"** 를 명시하고, 앞 사람들이 **함께** 틀렸을 지점을 묻는다.

### 레인 규율

- **관측된 병목 앞에서 일반적 미래를 미리 짓지 않는다.** orchestrator, watcher, manager, backlog 는 아프기 전에 만들지 않는다.
- **리뷰 발견을 셋으로 나눈다.** *Blocker*(거짓 성공, 데이터 손실, 권한 위반) 는 지금 레인에서. *Defect*(현재 계약과의 실제 불일치) 는 수정 묶음에서. *Observation*(미래 위험) 은 기록만 — 지금 레인을 열지 않는다.
- **증거가 제품보다 커지면 멈추고 GLG 에게 보고한다.**
- **영수증이 지탱하는 것만 주장한다.** 더 강한 문장은 증명 의무를 만들고, 그 의무가 서브시스템을 만든다.

## Next and References

- [NEXT--feat_clojure-runtime.md](./NEXT--feat_clojure-runtime.md) — 지금 좌표와 다음 한 걸음. 브랜치 작업은 일회용 `NEXT--<branch>.md`.
- [issue #1](https://github.com/junghan0611/prime-agent/issues/1) — **판.** 표·영수증·결정 카드가 공개로 쌓인다. 홉 하나가 댓글 하나. **현재 첫 산출은 H1 댓글**(H1.1 default · H1.2 ready gate · H1.3 bootstrap/state-op-off · H1.4 framing)이며 **H1 이 닫히기 전에는 TS 17파일을 열지 않는다.** NEXT 의 현재 행을 먼저 읽는다.
- [ROADMAP.md](./ROADMAP.md) — 제품 홉 H1–H8 과 그다음(Emmy/SICM).
- [BASELINE.md](./BASELINE.md) — 운영자 인터뷰 프로토콜과 기록된 런.
- [docs/clojure-runtime.md](./docs/clojure-runtime.md) — Clojure 런타임 계약서. 알려진 편차와 "코드를 읽어야만 알던 것".
- [evals/h7-functional-ab/PROBE-SHEET.md](./evals/h7-functional-ab/PROBE-SHEET.md) — 양-arm 프로브와 기계 판정 기준.
- [AGENTS.upstream.md](./AGENTS.upstream.md) — **상류 유지보수 규칙.** 코드 품질, 커밋, provider 추가, daemon 프로토콜, 병렬 에이전트 git 규칙의 SSOT.
