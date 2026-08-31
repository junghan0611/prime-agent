# feat/clojure-runtime — 8홉 레일 닫힘

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H8은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = **H1–H8 재감사 — 커버리지 대응 + kill receipt** (좌표 2026-08-31, 이슈 #1). 착수 1 = **이슈 #1 에 H1 댓글**로 H1.1–H1.4 를 채운다. **H1 이 닫히기 전에는 TS 17파일을 열지 않는다.** Emmy 는 성능평가 다음.

계약: `docs/clojure-runtime.md`. 판: issue #1.

# COMPASS — Entwurf #88

1. CPython 복제가 아니다. Lisp workspace로 원저자 RLM 루프가 서는지를 본다.
2. REPL 언어축만. steering / Emacs / Emmy는 ROADMAP.
3. Entwurf는 transport, local REPL은 computation.
4. 자연어는 탐색, Lisp는 공개 상태·계약·form.
5. 새 capability는 게이트를 통과한 뒤만. H3 읽기는 key-shape 고정 다음.
6. 성공은 GLG가 form을 읽고 판단하는가. 테스트 개수·속도 아님.

# RAIL — 현재 좌표

같은 브랜치 `feat/clojure-runtime`. 새 브랜치 없음.

- [x] **H0 runtime** — native-image + SCI. 32/158. GitHub 린트만.
- [x] **H1 host** — checkpoint `7d509e75`. 기본 python. `PRIME_AGENT_KERNEL_RUNTIME=clojure`.
- [x] **H2 DeepSeek 4실험 + fan-in** — checkpoint. 수선 `9d8f69f5`. 재측정 s6. **branch close 아님.**
- [x] **H3 bounded read / context** — `f0b5183e` → `10fde370` → `13e88738`. keywordize + `(read-text)`.
- [x] **H4 process lifecycle** — `4c42dbb4` → `9229aa77`. id registry + setsid group. native 48/313.
- [x] **H5 edit / write receipts** — `2e5753a2`. write-text / edit-text. native 57/443. GLM `abff01` PASS.
- [x] **H6 compaction / restart continuity** — `2ea1b170`. registry 회수 verb + runtime별 통지. native 65/497.
- [x] **H7 기능 A/B (DeepSeek)** — `b5e9e424`. 8런 both arms, $0.00576. 테라 `55b3ea` raw JSONL 재분석 일치.
- [x] **H8 default switch + soak** — `edc3a3e8`. default = clojure, fallback 없음. 테라 `55b3ea` native 65/497 PASS.
- [x] **H2 leftover receive** — `ed702304`. 2차 `primeclj` Q-R3 PASS (notice 0). H8 재오픈 아님.
- [ ] **H1–H8 재검수** — **진행 중. 홉을 빼지 말고 전부.** 방법은 아래 NOW. 새 기능 아님. Emmy 아님.
- [ ] **Emmy / SICM** — 재검수 다음. 지금 열지 않음.

현재 좌표: H0–H8 checkpoint · leftover receive 닫힘 → **H1–H8 재검수 (방법 확정, 착수 1번부터)** → (그다음 Emmy).

# NOW — H1–H8 재감사 = **커버리지 대응 + kill receipt**, 둘 다

좌표: 이슈 [#1 comment](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5475775026) (2026-08-31).
4인의 읽기 전용 입력을 반영했다 (Sonnet `ecd946` 측량 · glm-5.3 `75ccad` 2회 · Fable(bbot) oracle 검독 · terra `842aef` 방법론).
**표 작성 · specific-test 실행 · kill receipt 는 아직 시작하지 않았다.**

## 왜 지금 커버리지인가 (GLG)

어제는 Lisp 이식을 뚫는 게 먼저라 커버리지를 미뤘다. JVM 버리고 GraalVM/SCI로 간 결정이 더 중요했고, 뚫렸다 — DeepSeek 인터뷰에서 RLM이 실제로 뭔가 되고 "그냥 pi면 안 되는데"가 보인다. **오늘은 Python이 지키던 구조·행동 커버리지를 Clojure에서 실제 테스트로 대응시킨다.** 그 다음이 저비용 양-arm 성능평가, 그 위가 Emmy. **커버리지는 절차가 아니라 GLG가 단단하게 말할 수 있는 근거다.**

## ⚠ 다시 열지 말 것

- **"재감사냐 커버리지냐"는 양자택일이 아니다.** 같은 표의 다른 열. 이 논쟁을 다시 열면 **둘 다 놓친다.**
- `261:65` 는 **경보**지 판단 근거가 아니다. 근거는 contract row 수.
- (D)를 계약서에 적는 것은 **scope 선언**일 뿐 PASS 칸이 아니다.
- TS 17파일 뒤집기는 **4번**이지 1번이 아니다.
- H7의 기능·성능 **결과**는 receive 수선 전 commit 이므로 수선 후 성능 기준선으로 재사용하지 않는다. **비용 상한을 스스로 정하지 않는다** — 아래 「비용」 참조.
- JVM SUT 문구 정정 — 고칠 살아있는 문서 없음.

## 표의 형태 — 한 행

`hop / Python 계약(oracle) / Clojure 대응 또는 선언된 divergence / named test / 실행 영수증 / **kill receipt** / native SUT 필요 / status`

- **커버리지 열** = Python이 지키던 계약이 Clojure에 대응되는가
- **kill receipt 열** = 그 계약을 고의로 깨면 그 테스트가 죽는가

kill receipt는 새 개념이 아니다 — H2 receive 랜딩 때 게이트를 되돌려 4건 중 2건이 실패함을 이미 확인했다. 표의 **상시 열로 승격**하는 것.

**행의 단위는 파일·grep 개수·test 함수가 아니라 하나의 observable scenario 다.** 한 Python 파일은 여러 행을 낼 수 있고, 한 named test 도 여러 행의 영수증이 될 수 없다. source 는 계약과 test 위치를 찾을 때만 읽고 **품질 평가는 하지 않는다.**

## 착수 순서

1. **Python 계약 → Clojure 대응표 — hop 단위, evidence-first. 산출은 이슈 #1 의 H1 댓글.** 파일 순서도 261:65 전수도 아니다. 한 세션의 단위는 **한 홉의 atomic observable contract row 묶음**이고, 첫 세션은 **H1만** 연다:
   - **H1.1** default=clojure + explicit python
   - **H1.2** native ready language/protocol gate
   - **H1.3** clojure bootstrap / public bindings / state-op-off
   - **H1.4** native JSONL stdout framing ← **이미 '테스트 없음'이 확인된 첫 빈 행** (`docs/clojure-runtime.md` 「코드를 읽어야만 알던 것」 1)

   각 행의 작성 순서: (a) Python oracle/acceptance 를 한 문장으로 고정 → (b) 대응 Clojure observable 또는 divergence → (c) 현재 named test 를 **역방향으로** 붙임 → (d) specific test Green receipt / SUT 표기 → (e) kill mode 결정 → (f) 비어 있으면 **그제서야** (c)/(D) 분류.
   H1 다음 H2(formal-receive 포함), 그다음 H3…H8. TS 17파일은 H1–H8 표에서 안 잡힌 Python-host row 만 남은 뒤 4번으로 연다.

   반드시 행으로 포함할 것:
   - **`docs/clojure-runtime.md` 「코드를 읽어야만 알던 것」 1** — protocol writer `*out*` 오염이 프레임을 찢는다. 계약서가 스스로 **"테스트가 없다"**고 적었다. H1급 프레이밍 회귀인데 261:65 표도 TS 17파일도 못 잡는다.
   - **`docs/clojure-runtime.md` 「코드를 읽어야만 알던 것」 2** — 테스트 러너가 네임스페이스를 명시 require. **파일만 추가하면 조용히 안 돈다** → runner manifest / run receipt 필수.
   - **H2 formal-receive 행** (새 probe ID 가 필요하면 P5 로 배정 — `PROBE-SHEET.md` 에는 P1–P4 만 있다): Q-R3 의 여섯 PASS 조건 — explicit capability · child identity · notice/transcript/observe 0 · 양 arm 동일 계약 — 을 named test 로 고정한다. **이것은 성능평가가 아니다.**
   - **(D) 항목별 결정** — `49`/`35` 라는 수는 **결정 정보가 아니다.** MCP 에는 auth credential resolution · host refresh · tool list/call · structured/error result 가 섞여 있고 (`prime-agent-runtime/test/test_mcp_base.py:72-186,208-263`), harness 에는 persistent local/global state · memory/skill CRUD · Python reference enforcement · external-write reload 가 섞여 있다 (`test_harness.py:24-26,198-247,413-476`). 따라서 **capability family 아래 contract bundle 단위로** 카드를 만들어 **이슈 #1 댓글로 올려** GLG 가 고르게 한다:
     `Decision ID | Python user-visible job(한 문장) | Python tests/observable contract | Clojure 현재 surface + 실제 unavailable evidence | RLM loop 에서 잃는 것 / 대체 없음 | dependency·security boundary | smallest paired acceptance test | support 구현 범위 | explicit-exclusion meaning + negative test | future 면 reopen trigger | GLG 선택(support/exclude/future) | owner`
     GLG 선택 전에는 status = **`DECISION REQUIRED`**, (D)/PASS/exclusion 어느 것으로도 쓰지 않는다.
2. **env 이음새 비대칭 기록** — runtime 종류는 팩토리 인자지만 실행파일 주입이 비대칭. python은 `python` 옵션, clojure는 **`PRIME_AGENT_CLOJURE_RUNTIME` env로만** (`src/core/kernel/runtime.ts:66-77`). `describe.each` 가능하되 arm별 주입을 갈라야 한다.
3. **receive 재판정 기계화** — `src/core/agent-session.ts:10502` 캡처(`parentReplyCountBeforeRun`) → `10514-10518` 비교. notice는 `child._parentReplyCount === parentReplyCountBeforeRun` 일 때만 발화 → Q-R3 재판정은 A/B 재실행 없이 **인터뷰 + 카운트로 $0**.
4. **TS 17파일 뒤집기** — `kernel-agent-message-skill`(7) → `kernel-agent-observe-skill`(2) → `acp-kernel-features`(4).

## 지도 — 폐기 없음, 1번의 재료

| 축 | python | clojure |
|---|---|---|
| 런타임 층 (**경보용**) | 261 pytest | 65 deftest |
| TS 호스트 pin | occurrence 79 / 20파일 / it ~105 | 대칭 it 14 / **순수 비대칭 17파일** |

```bash
grep -c 'def test_' prime-agent-runtime/test/*.py                        # 261
grep -h '^(deftest' prime-agent-runtime-clj/test/rlm/*_test.clj | wc -l  # 65  ← 앵커 필수
grep -ro 'runtime: *"python"\|kernelRuntime: *"python"' packages/coding-agent/test/ | wc -l  # 79 occurrences
grep -rl 'runtime: *"python"\|kernelRuntime: *"python"' packages/coding-agent/test/ | wc -l  # 20 files
grep -rl 'kernelRuntime: *"clojure"\|runtime: *"clojure"' packages/coding-agent/test/       # 4 → 20-3=17
grep -cE '^\s*it(\.skipIf)?\(' packages/coding-agent/test/repl-kernel-clojure-runtime.test.ts # 14
```
grep 함정 둘: `deftest ` 는 require의 `[deftest is]` 를 잡아 **73**으로 부풀고, naive `it(` 는 `it.skipIf(` 를 놓쳐 13 또는 `toEmit(` 를 잡아 17로 샌다.

**최대 구멍:** `test_mcp`+`test_mcp_base` **49 ↔ 0** — clj src 0파일, `docs/clojure-runtime.md` 0건. **제외 선언조차 없이 없다.** `test_harness` 35 ↔ 0 동일. `test_winjob` 22 는 정당한 (a).
**거의 1:1:** `test_subagent_registry` 10 ↔ `host_bridge` 10.

## 분류 4분기 — 정적 징후로 가른다

- **(a)** python이 주제 — `pythonSkills:[...]` 넘김 · venv 요구 · snapshot/restore 단언
- **(b)** 우연한 pin — `python:"/nonexistent…"` · mock child 주입
- **(c)** 대칭 기능, 테스트만 없음 — 진짜 커널 프로토콜 단언
- **(D)** 기능 자체 없음(제품 구멍) — clj src에 개념 부재

**판정법:** `expect` 대상이 `buildX()` 반환값이면 정적, `manager.execute(...)` 반환값이면 라이브.
파일 단위 3분법은 큰 파일에서 깨진다 — `acp-kernel-features`·`kernel-goal-skill`·`kernel-rlm-heartbeat-skill` 은 한 파일에 (a)/(b) 혼재. 확정 오분류 2건: `attach-image` (b)→**(D)**, `ipython-provisioner` (a)→**(b)**.
커버리지 축((a)(b)(c)(D))과 원인 축(`harness-gap`/`surface-seam`/`semantics-gap`/`model-fumble`)은 상보. FAIL에 원인 태그를 함께 단다.

## 환경 사실 — 모르면 첫 시도에서 막힌다

- 실행: **`npx tsx ../../node_modules/vitest/dist/cli.js --run test/<file>.test.ts`**, `packages/coding-agent` 에서. **`npm test` 전체 금지** — `AGENTS.md` Hard Rule 9, 원본은 `AGENTS.upstream.md` 의 Commands 절. (줄번호로 인용하지 않는다 — 재작성이 좌표를 끊는다.)
- 네이티브 바이너리 `prime-agent-runtime-clj/target/rlm-repl` (없으면 `native-image/build.sh`). 게이팅 `it.skipIf(!existsSync(nativeRuntime))`.
- **CI는 lint 전용** (`clojure-runtime.yml`, GraalVM 없음). flip 결과는 로컬에서만 돈다.
- **경로:** `agent-session.ts` / `repl-manager.ts` / `runtime.ts` 는 전부 `packages/coding-agent/src/core/` 아래.
- **shape 함정:** `src/core/kernel/repl-manager.ts:841` `handler({ ...data, cellSourceCode })` — 양 팔 공통의 의도된 provenance 태깅.
- 인프라는 이미 양쪽 다 돈다: `repl-kernel-clojure-runtime` 14 pass 4.3s(네이티브 스폰) · `repl-kernel-execute` 6 pass 2.9s(실 python 커널).
- `agent_message` 행에서는 네이티브 라이브 프로브가 `list_agents`·`send`·receipt·2회 독립성 **5/6 PASS** 를 보였다 (`src/core/agent-session.ts:9148` *"The host verbs themselves are runtime-neutral."*). **이 관측은 `agent_message` 행에만 적용한다.** 다른 빈 행은 표의 named test 와 실행 결과 전에는 '테스트 부재'도 '능력 부재'도 단정하지 않는다.

## 어디에 쓰는가 — **새 문서를 만들지 않는다**

- **NEXT** = 할 것과 좌표만.
- **GitHub 이슈 #1** = 표·영수증·결정을 **공개로 진행한다.** 홉 하나가 댓글 하나. 그래야 서로 봐주면서 돕는다.
- **`docs/` 아래 새 문서를 만들지 않는다.** docs 아래 문서는 deprecated 되기 쉽다 (그래서 `BASELINE.md` 는 루트로 올라왔다). 리포에 문서를 늘리지 않는다.

실무자는 자기 홉의 **이슈 댓글 하나**와 해당 named test 만 만진다. 머지 충돌이 없고, 진행이 공개된다.

## kill receipt — 값싸게, 반복 가능하게

**`git stash` 금지** (타인 변경을 함께 보관한다).

1. **우선 production source 무변경 fault seam** — fixture·env·fake-host 입력으로 깬다: fake runtime 의 bad ready / extra stdout, env 의 bad executable, no-controller, host reply error. H1 에는 이미 bad-ready fake test 가 있다 (`repl-kernel-clojure-runtime.test.ts:122-145`).
2. **그런 seam 이 없는 행만** isolated temporary worktree 에서 single causal expression 을 뒤집는다. 공유 worktree 는 건드리지 않는다 — pristine HEAD worktree 에 apply → named specific test **Red** → receipt 저장 → worktree 제거.

receipt 10필드: `row / HEAD / mode / fault semantic / patch digest / green command+result / kill command+expected failure / observed decisive line / native-or-fake / cleaned`.
**negative-contract test**(unsupported 가 정직하게 실패하는가)는 kill receipt 와 **별개로** 적는다. H2 의 "게이트 되돌리기 → 4 중 2 fail"(`ed702304`)이 이 형식의 선례다.

## 완료 조건

supported 계약이 전부 (1) 양 arm 또는 **선언된 one-arm oracle**, (2) named test 실제 PASS, (3) **kill receipt**, (4) native SUT 필요 행은 native 실행 영수증, (5) known deviation은 intentional FAIL/NO-CREDIT + owner 를 가질 때. runner manifest 로 "조용히 안 도는 파일" 없음을 함께 증명한다.

**`DECISION REQUIRED` 는 미완료다. exclusion 은 negative-contract 가 PASS 여도 supported coverage PASS 가 아니다.** (이 문장이 없으면 '명시적 unavailable' 이 다시 coverage PASS 로 세어진다.)

## 다음 (재감사 뒤) — 성능평가는 기준선을 새로 잡는다

측정: **`b5e9e424`(H7) 는 `ed702304`(receive) 의 조상**(`git merge-base --is-ancestor` 확인). H7 A/B는 **receive 수선 전 코드**에서 돌았고 `evals/h7-functional-ab/RESULTS.md` 의 `harness-gap` 항목이 clj arm `agent_message` 부재를 기록한다. 이후 PASS는 `BASELINE.md` 의 Q-R3 행 (사람 인터뷰 1회) 뿐 — 루트로 이동됐다.
→ **양-arm 성능평가**를 수선 후 코드 기준으로 새로 잡는다. 그 뒤가 Emmy.

### 비용 — 스스로 상한을 정하지 않는다 (GLG)

> "숫자 정하지 마라. 나한테 알려주면 내가 돈 있으면 지원할 테니까. **돈 때문에 못 하면 안 돼.** 빌려서라도 지원한다."

- 비용이 드는 작업이면 **먼저 GLG에게 예상 비용을 알리고 상의한다.** 에이전트가 임의로 범위를 줄이지 않는다.
- DeepSeek 는 API 여유가 있다 — **v4 pro / flash 둘 다 쓸 만하다.** 평가 arm 선택 시 첫 후보.
- H7 의 `$0.00576` 은 **참고 실적**이지 상한이 아니다.

## 누적 leftover (blocker 아님, 표로 흡수)

H4 (a)no-setsid (b)re-group (c)SIGKILL orphan · H5 symlink 거부/diff event 없음/delete·rename·mkdir 없음 · H6 busy-kernel 대화상자 Python 어휘 · H7 `process-tail`·`Integer/parseInt` 문구 · spawn handle `name` vs registry `session_name` · child doctrine (B) 미분리 · 봉투 문구 · SCI 닫힌 이름.

- 기준점: stable = v0.8.1, oracle pin = v0.8.1 + `bc0fa7606`. Entwurf #88.
- Do not touch: Python oracle 삭제, `ipython` 개명, `list_names`, snapshot/restore, `spit`/`slurp`, Emmy.

# RECENT

- [2026-08-30] H3 닫힘. 푸시 `ba2bba55`.
- [2026-08-30] H4 `4c42dbb4` → `9229aa77`. 푸시 `a114668f`.
- [2026-08-30] H5 `2e5753a2`. GLM PASS. H5 close 권고.
- [2026-08-30] H6 `2ea1b170`. GLM PASS, 코디가 닫음. 측정된 defect: `(rlm …)` 는 `:rlm-child-id`,
  raw `host-request` 는 `:rlm_child_id` — 회수한 handle 만 어긋났다. 키 정규화로 닫힘.
- [2026-08-30] H8 `edc3a3e8`. default = clojure. flip 델타 +49 → 수선 후 0. 레일 8홉 끝.
- [2026-08-30] H7 `b5e9e424`. GLG 가 논문 eval 대신 **기능 A/B** 로 돌림. DeepSeek 8런 $0.00576.
  P4 에서 `(rlm-children)` 이 live 로 동작 — H6 이 만든 dashed key 가 트레이스에 보인다.
- [2026-08-31] 재검수 방향 확정 — 테스트 비교 검출. 분모 정정 261:65. mcp 49↔0 발견.
  이슈 #1 댓글. 측량 Sonnet `ecd946`, 검수 zai/glm-5.3 `75ccad` 2회.
- [2026-08-31] 첫독자 시험 — 맥락 없는 Opus 에게 `AGENTS.md` 만 읽혔다. **좌표는 안 바뀌었다.**
  판정: 방향은 서고 첫 손이 안 움직인다. 바이너리 획득·브랜치 규율·RLM 풀이가 없었고,
  사실 오류 4건(env 로만 / 9148 스코프 / verb 누락 / clj 탭). 수선 `acdd0f92` → `0a60913f`.
  줄번호로 문서를 가리키는 것을 금지하고 남은 인용 7개를 앵커로 바꿨다. 이슈 #1 **본문**에
  낡음 배너 (CURRENT 는 스레드가 SSOT, `docs/BASELINE.md` → 루트).
- [2026-08-31] 좌표 확정 — 재감사 = 커버리지 대응 + kill receipt, 둘 다. 4인 검수 통과.
  261:65 경보로 강등. `clojure-runtime.md` 「코드를 읽어야만 알던 것」 1·2 행 추가. 이슈 #1 좌표 댓글.
