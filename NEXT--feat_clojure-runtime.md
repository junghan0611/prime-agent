# feat/clojure-runtime — 재감사에서 parity 홉으로

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H8 은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = **H9–H12 parity 홉** (좌표 2026-09-01 밤, 이슈 #1).

**재감사의 Pass A·B 는 끝났고, GLG 의 범위 결정이 처음으로 게이트 사실이 됐다** —
`parity 69 = D 119 − out-of-scope(GLG) 50`, **카드 대기 0**, 게이트는 둘로 갈렸다
(① 재감사 닫힘 / ② parity 기준점, `exit 3` 이 「①닫힘·②미도달」을 든다).
**H1 은 아직 닫히지 않았다** — 그러므로 **TS 17파일을 열지 않는다.** Pass C(킬 집행)와 Emmy 는 그 뒤.

계약: `docs/clojure-runtime.md`. 판: [issue #1](https://github.com/junghan0611/prime-agent/issues/1). 범위 결정: [issue #2](https://github.com/junghan0611/prime-agent/issues/2).

# COMPASS — Entwurf #88

1. CPython 복제가 아니다. Lisp workspace로 원저자 RLM 루프가 서는지를 본다.
2. REPL 언어축만. steering / Emacs / Emmy는 ROADMAP.
3. Entwurf는 transport, local REPL은 computation.
4. 자연어는 탐색, Lisp는 공개 상태·계약·form.
5. 새 capability는 게이트를 통과한 뒤만.
6. 성공은 GLG가 form을 읽고 판단하는가. 테스트 개수·속도 아님.

# RAIL — 현재 좌표

같은 브랜치 `feat/clojure-runtime`. 새 브랜치 없음.

- [x] **1. H0–H8 8홉 레일** — native-image+SCI → host → fan-in → bounded read → process → edit/write → continuity → 기능 A/B → default switch. checkpoint `edc3a3e8`, leftover receive `ed702304`. 홉별 커밋은 RECENT.
- [x] **2. H1–H8 재감사 Pass A(기계) · Pass B(GLG 범위 결정)** — 분모 게이트 `evals/coverage-denominator/`. 카드 14장 처분 완료, 어휘 `parity-target(H<n>)` / `out-of-scope(GLG,2026-09-01)`.
- [ ] **3. H9–H12 parity 홉** ← CURRENT: **H9 갈래 ⑤ 착수 (clj 팔 only, TS 안 건드림)**
- [ ] **4. 유료 pilot (승인됨, 12런 ≈ $0.01–0.03)** ← PAUSED: 전제인 host `src/` seam 둘이 프리즈에 막혔다. GLG 결정 대기
- [ ] **5. Pass C(킬 집행) · Emmy/SICM** — 그 뒤. 지금 열지 않는다

현재 좌표: 1·2 완료 → **3 진행(H9 착수 가능)** → 4 보류(프리즈) → 5 미개시.

# NOW — H9 갈래 ⑤ 승인됨. **clj 팔부터 갈 수 있다**

좌표: 이슈 #1 스레드. **본문과 어긋나면 스레드가 이긴다.**

## 첫 손 — H9 ⑤ 구현 (clj 팔)

**셀을 별도 스레드에서 평가하고 스레드 핸들을 보관 → `Thread.interrupt` 전달 → parking 상태기계.**
**SCI 를 건드리지 않는다.** 지금 `rlm.repl/serve` 가 `handle-execute` 를 루프 안에서 동기 호출해
셀이 serve 루프 스레드에서 돈다 — 스레드 추적만 더하면 열린다.

- 덮는 것: 게이트가 인쇄하는 `bundles:` 의 **`thread-interrupt` 11 + `parking` 4 = 15**
- `process-ownership` **5** 는 ⑤가 공급하는 메커니즘이 아니다 — `process-*` 동사로의 **재해석 대상**이고 설계는 아직 안 열었다
- **tight loop 은 declared-divergence 로 H11 watchdog 에 보낸다.** 오라클 자신도 유일한 `while True: pass`(`OwnerWatchdogTest`)를 interrupt 가 아니라 프로세스 exit 으로 푼다
- **`17/21` 을 다시 쓰지 않는다.** 숫자는 게이트가 데이터에서 센다

**설계 입력 둘 (이미 관측됨, 재측정 불필요):**
- `handle-execute` 가 `done` 을 보낸 **뒤** `finally` 가 inflight 에서 id 를 뺀다 → 그 창에 도착한 interrupt 가 끝난 셀에 거절 프레임을 그린다. Python 은 `_finishing_rid` 로 회계한다
- 지금 거절 프레임은 `id = nil` 이라 호스트에서 `appendKernelDiagnostic("protocol error: …")` 로 간다. 상관관계·ename 이 경계에서 소실된다. 진짜 취소가 서는 날 「거절 vs 성공 취소」를 같은 채널에서 갈라야 한다

## 대기 중인 GLG 결정 — **프리즈 국소 해제**

유료 pilot 의 전제(sol 순서 2)가 **host `src/` 둘**이다: 런타임 신호 통로(`system-prompt.ts::hasIpython`)와
격리 seam(`refinement.ts::getGlobalHarnessStateDir` + `agent-session.ts` 호출 5곳).
「TS 17파일 닫힘 + 새 계측 동결」에 막혀 **에이전트가 스스로 풀 수 없다.**

sol 권고 = **(ii) 국소 해제**. 프리즈의 목적은 H1 미닫힘에서 전수 뒤집기·capability 증식을 막는 것인데,
이 둘은 새 기능이 아니라 **이미 측정된 입력 오염을 제거하는 pilot validity seam** 이다.
(i) H1 선행은 pilot 오염과 무관한 것을 앞세워 피드백을 늦추고, (iii) 약한 카드는 **오염을 안고** 유료 관측을 해
「생각보다 실제는 다르다」의 실제와 하네스 오염을 분리할 수 없다.

해제 시 경계를 문면으로 박는다: ① clj 프롬프트가 Python harness call contract 를 받지 않고 Python 팔은 그대로임을 무는 test
② host prompt loader·kernel env·refinement 가 한 per-run global path 를 공유하고 launcher 가 arm·local·global 경로를 receipt 로 인쇄함을 무는 test
③ 이 둘 밖의 TS 파일·기능은 열지 않음 ④ **pilot 종료가 이 예외의 자동 종결점.**

## 아직 GLG 자리인 방향 제안 (채택 전)

sol: **"Python 은 여전히 옳은 `oracle` 이지만 더 이상 옳은 단일 `yardstick` 이 아니다."**
잣대를 둘로 — ① **oracle delta**(120 원장 유지) ② **tunnel comparator acceptance**(외부 자극 없이
`inspect→read→compute→spawn→receive→run→edit→verify` 가 서고, routine blocking/abort 는 H9 가 workspace 를 보존하고
tight-loop catastrophic 은 H11 + **declared state-loss** 로 정직히 분리되며, 공개 상태가 Lisp form 으로 읽히는가).
**69 를 다 복제해도 ②가 자동으로 서지 않고, ②만 돌리면 parity 를 말할 자격이 없다.**
H10 도 33-for-33 이 아니라 네 observable bundle 로 다시 접자는 제안이 함께 있다. **아직 채택 안 됨.**

## 지금 사실 (2026-09-01 밤, oracle, HEAD 는 이 커밋)

- 게이트: `denominator 261` · `D=119 a=91 b=4 c=10 row=37` · `parity 69 (H9=20 H10=33 H11=11 H12=5)` ·
  `out-of-scope(GLG,2026-09-01) 50` · `bundles: D-INTERRUPT 20 — parking 4 · process-ownership 5 · thread-interrupt 11` ·
  ① OPEN(c 10) · ② NOT REACHED · **exit 1**
- `./run.sh test-native` **85 tests / 0 failures**. assertion 총수는 **폴링 루프 때문에 흔들린다**(622/623 둘 다 관측) — **비교 근거로 쓰지 않는다**
- `npm run check` exit 0 · `run.sh extract` 261
- **kill receipt 15/15** — M1–M10(원장·게이트·bundle) + R1–R5 무회귀
- **오늘 숫자가 세 번 움직였다: 72 → 70 → 69.** 성격이 다 다르다 — ① published 배치 결정의 정정(`D-SKILL-SURFACE` out)
  ② 원장의 **의미 오분류** 수선(`test_second_await_after_cancelled_oneshot_only_waits` 본문에 cancel 이 없다 → `c`)
  ③ (앞서) 조정이 지시문에 **지어낸** `D-DISPLAY 5`. **게이트가 스스로 인쇄하는 `semantic:` 한계가 실현된 것이다**

## 읽을 곳 (순서대로)

1. **[이슈 #2](https://github.com/junghan0611/prime-agent/issues/2)** — GLG 의 범위 결정 자리.
2. **오늘의 영수증 넷** — [SCI 인터럽트 측정 + negative contract](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5493202373) ·
   [21건 전수 분류 + 갈래 ⑤](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5494371045) ·
   [방향 검수: 잣대 둘 + `17/21` 미성립](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5494791957) ·
   [BASELINE 격리 계약 + 프리즈 충돌](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5495027053)
3. **artifact** — `evals/coverage-denominator/`. `run.sh check` 게이트 · `run.sh table` 표 · `manifest.tsv` 261행 전수(+`bundle` 열) ·
   `registry.tsv` 원장(+`decision_url` 열). **산문을 믿지 말고 돌려봐라.**
4. **`BASELINE.md`** — 운영자 인터뷰. 「Baseline isolation」 과 「Recovery outcomes」 가 새로 섰다.
5. Pass A/B/C 헌장과 카드 14장 원문은 이슈 #1 스레드에 있다. **NEXT 로 복사해오지 않는다.**

## 살아 있는 금지

- **커밋·푸시는 GLG 의 현재 세션 지시가 있을 때만.** 커밋 요청이 푸시를 함의하지 않는다.
- **형제의 영수증을 세션에 묵혀두지 않는다.** 2026-09-01 21:15 재부팅으로 형제 셋이 한꺼번에 사라졌다 — 결정적 줄은 **즉시** 이슈로 옮긴다. 다음 형제도 같은 방식으로 죽는다고 가정한다.
- **TS 테스트 17파일을 열지 않는다** (H1 미닫힘). `src/` 읽기는 계약 찾기용으로 허용. **프리즈 국소 해제가 승인되면 위 NOW 의 4조 경계 안에서만.**
- **`git stash` 금지** — 여러 형제가 같은 트리에 있다. 격리는 임시 `git worktree add --detach`.
  **디렉터리 복사 금지** — 게이트가 오라클을 자기 파일 기준으로 해석해 엉뚱한 트리를 읽는다(이제 hard failure 로 막히고 `source:` 행이 읽은 경로를 인쇄한다).
- **새 카드·새 계측 동결.** manifest 전수 재검증 금지.
- **`PASS` 는 강한 킬 뒤에만.** 약한 킬은 `killed/weak` 이고 게이트가 B→`PASS` 를 하드 실패로 막는다.
- **negative contract 를 커버리지로 세지 않는다.** 「거절한다」고 적는 것은 PASS 칸이 아니다 — 게이트가 M5 로 막는다.
- **리터럴 숫자를 게이트·문서에 박지 않는다.** registry 에서 세어 인쇄한다. 오늘 72→70→69 정정이 정확히 그 교리가 막으려던 사고다.
- **v5 계획 문서를 쓰지 않는다.** 진행·표·영수증·결정은 이슈에 쌓는다.
- **비용 상한을 스스로 정하지 않는다.**

## 오늘 얻은 규칙 — **다시 유도하면 하루가 또 간다**

| # | 규칙 |
|---|---|
| 1 | **fixture ≠ fault seam.** mode-1 은 **테스트도 소스도 안 고치고** 주입할 때만 |
| 2 | **레버가 있는 것 ≠ 계약을 무는 것.** mode 분류와 킬 대상 존재는 별개 질문이고 **둘 다 통과해야** 표적이다 |
| 3 | **킬은 구현을 깨는 것이지 의존물을 갈아끼우는 것이 아니다** |
| 4 | **fault 는 행 설명이 아니라 소스에서 쓴다.** 그 표현이 없으면 **그 fault 는 존재하지 않는다** |
| 5 | **합격 모양은 「표적만 Red」가 아니라 「예상 폐쇄만 Red」.** 귀속은 "혼자 Red 인가"가 아니라 **"예측한 대로 Red 인가"** 로 산다 |
| 6 | **약한 킬은 PASS 를 만들지 않는다** (`killed/weak`) |
| 7 | **green 강화는 계약이 요구할 때 한다. 킬을 가능하게 하려고 하지 않는다** |

**규칙을 더 만들기 전의 판별식:**
> **「게이트 검사로 표현 가능한가?」 가능하면 규칙 대신 검사를 만든다.**

이미 셋이 게이트로 갔다(`kill_bucket` partition · 전이 검사 · id 중복). 하루 종일 쓴 손 검사 하나는 그대로다 — **"이 초록이 더 약한 구현으로도 초록인가."**

**표준 용어:** C 통 = **equivalent mutant**(mutation testing 의 알려진 하한) · B 통 = **weak test oracle**(변이가 상태는 바꾸는데 단언이 못 본다).

## 은퇴한 상속 주장 (전부 실측으로 대체됨)

| 주장 | 실측 |
|---|---|
| **mode-1 seam 이 있어 빌드 예산이 준다** | **유효 mode-1 은 0개다.** 후보 5개 전부 fixture 이거나 의존물 교체였다 |
| **Pass C fault 38개** | **29개.** 소스에 없는 표현을 세고 있었다 |
| **A28 / B4 / C13** | 표에서 **재현되지 않았다**(네 행이 두 통, 한 행이 무통). 지금은 `kill_bucket` 을 **게이트가 센다** |
| "261 pytest" | **unittest** 다. CI 가 `unittest discover`, pytest 는 의존성에 없다. 숫자 261 은 생존 |
| clj verb 13개 | **12개**. `sci/init` 맵 심볼 13 중 `'user` 는 네임스페이스. 런타임에 `user` 에 보이는 이름은 `bind-_` 의 `_` 포함 13 |
| Windows 36건 | **38건**. `test_windows_*` 접두어가 없는데 본문이 taskkill/job 을 모는 2건이 있다 |
| journal 7건 | **9건** (순수 8 + 혼재 1) |
| 「거의 1:1」 `test_subagent_registry` 10 ↔ `host_bridge` 10 | **5 승격 / 3 부채.** 개수 대칭은 계약 대칭이 아니다 |
| assertion 총수 변동 "메커니즘 미상" | **폴링 루프다.** `process-test/wait-exit` 가 `eval-edn` 을 최대 200회 부르고 그 안에 `is` 가 하나 있다. 폴 횟수 = 단언 수. **헌장의 `doseq` 정적 분석은 틀린 자리를 봤다** |
| "`^def test_` 로 앵커하면 된다" | python 은 전부 클래스 메서드라 그 앵커가 **0** 을 준다. clj 함정은 부풀리고 python 함정은 비운다. **정본은 AST** |


## ⚠ 다시 열지 말 것

- **"재감사냐 커버리지냐"는 양자택일이 아니다.** 같은 표의 다른 열. 이 논쟁을 다시 열면 **둘 다 놓친다.**
- **커버리지 열 + kill receipt 열은 둘 다 있어야 한다.** 하나만 고르자는 논쟁의 판정은 이슈 #1 에 박혀 있다.
- `261:65` 는 **경보**지 판단 근거가 아니다. 근거는 contract row 수.
- **`out-of-scope` 는 부채가 아니라 제품 범위 결정이다.** 커버리지 크레딧 0 이고, Hard Rule 3 의 「부재 선언으로 PASS 만들기」와 구분된다. 다만 **선언과 프로토콜 경계는 다르다** — `mcp.refresh`/`mcp.config` 는 런타임 중립 등록이라 clj 셀이 `host-request` 로 닿을 수 있다(경계 negative test 미집행, Defect 로 열려 있음).
- **오라클 test 수를 제품 가치의 분모로 읽지 않는다.** H10 33 은 「남은 가치의 절반」이 아니라 한 기능의 Python env/cache/파일 동시성 구현이 테스트를 많이 낳은 것이다.
- TS 17파일 뒤집기는 H1 이 닫힌 뒤다.
- H7 의 기능·성능 **결과**는 receive 수선 전 commit 이므로 수선 후 기준선으로 재사용하지 않는다.

## 환경 사실 — 모르면 첫 시도에서 막힌다

- **명령은 `./run.sh help` 가 SSOT 다** — `clj` / `py` (두 팔) · `test <name>` · `test-native` · `lint` · `build`. 문서에 명령 문자열을 적지 않는다. `npm test` 전체 금지는 `AGENTS.md` Hard Rule 9.
- 네이티브 바이너리 `prime-agent-runtime-clj/target/rlm-repl` (없으면 `./run.sh build`). 게이팅 `it.skipIf(!existsSync(nativeRuntime))`.
- **CI는 lint 전용** (`clojure-runtime.yml`, GraalVM 없음). flip 결과는 로컬에서만 돈다.
- **경로:** `agent-session.ts` / `repl-manager.ts` / `runtime.ts` 는 전부 `packages/coding-agent/src/core/` 아래.
- **shape 함정:** `repl-manager.ts` 의 host-request 디스패치 `handler({ ...data, cellSourceCode })` — 양 팔 공통의 의도된 provenance 태깅.
- 인프라는 이미 양쪽 다 돈다: `repl-kernel-clojure-runtime` 14 pass 4.3s(네이티브 스폰) · `repl-kernel-execute` 6 pass 2.9s(실 python 커널).
- `agent_message` 행에서는 네이티브 라이브 프로브가 `list_agents`·`send`·receipt·2회 독립성 **5/6 PASS** 를 보였다 (`agent-session.ts` 의 `agentMessageAnnouncedToModel` 게이트 주석 *"The host verbs themselves are runtime-neutral."*). **이 관측은 `agent_message` 행에만 적용한다.** 다른 빈 행은 표의 named test 와 실행 결과 전에는 '테스트 부재'도 '능력 부재'도 단정하지 않는다.

## kill receipt — 값싸게, 반복 가능하게

**`git stash` 금지** (타인 변경을 함께 보관한다).

1. **우선 production source 무변경 fault seam** — fixture·env·fake-host 입력으로 깬다: fake runtime 의 bad ready / extra stdout, env 의 bad executable, no-controller, host reply error. H1 에는 이미 bad-ready fake test 가 있다 — `repl-kernel-clojure-runtime` 의 `"rejects a runtime that does not announce the clojure language"`.
2. **그런 seam 이 없는 행만** isolated temporary worktree 에서 single causal expression 을 뒤집는다. 공유 worktree 는 건드리지 않는다 — pristine HEAD worktree 에 apply → named specific test **Red** → receipt 저장 → worktree 제거.

receipt 10필드: `row / HEAD / mode / fault semantic / patch digest / green command+result / kill command+expected failure / observed decisive line / native-or-fake / cleaned`.
**negative-contract test**(unsupported 가 정직하게 실패하는가)는 kill receipt 와 **별개로** 적는다. H2 의 "게이트 되돌리기 → 4 중 2 fail"(`ed702304`)이 이 형식의 선례다.

## 완료 조건

supported 계약이 전부 (1) 양 arm 또는 **선언된 one-arm oracle**, (2) named test 실제 PASS, (3) **kill receipt**, (4) native SUT 필요 행은 native 실행 영수증, (5) known deviation은 intentional FAIL/NO-CREDIT + owner 를 가질 때. runner manifest 로 "조용히 안 도는 파일" 없음을 함께 증명한다.

**`green/no-kill` 도 미완료다** — 초록 하나로 PASS 칸을 만들지 않는다.
**`DECISION REQUIRED` 는 미완료다. exclusion 은 negative-contract 가 PASS 여도 supported coverage PASS 가 아니다.** (이 문장이 없으면 '명시적 unavailable' 이 다시 coverage PASS 로 세어진다.)

### 비용 — 스스로 상한을 정하지 않는다 (GLG)

> "숫자 정하지 마라. 나한테 알려주면 내가 돈 있으면 지원할 테니까. **돈 때문에 못 하면 안 돼.** 빌려서라도 지원한다."

- 비용이 드는 작업이면 **먼저 GLG에게 예상 비용을 알리고 상의한다.** 에이전트가 임의로 범위를 줄이지 않는다.
- DeepSeek 는 API 여유가 있다 — **v4 pro / flash 둘 다 쓸 만하다.** 평가 arm 선택 시 첫 후보.
- H7 의 `$0.00576` 은 **참고 실적**이지 상한이 아니다.
- **승인된 pilot 카드 (GLG, 2026-09-01):** 터널형 장기 과제에서 모델이 self-note 를 실제로 만들고 다음 턴에 재사용하는지,
  clj 표면이 Python 문법 오염 없이 도는지, child/실패 회수 뒤 상태가 남는지. **parity·성능 주장에 쓰지 않고 설계 순서에만 쓴다.**
  model `deepseek/deepseek-v4-flash` · 양팔 · 3 task × 2 arm × 2 repeat = **12런** · **≈ $0.01–0.03**(H7 8런 $0.00576 선형 + self-note 여유).
  **가장 이른 정직한 시점은 sol 순서 1–4 뒤** — 2(입력 오염 제거)와 4(H10 최소 수직 slice) 없이 돌리면 알려진 오염·부재를 재확인할 뿐이다.

## 누적 leftover (blocker 아님, 표로 흡수)

H4 (a)no-setsid (b)re-group (c)SIGKILL orphan · H5 symlink 거부/diff event 없음/delete·rename·mkdir 없음 · H6 busy-kernel 대화상자 Python 어휘 · H7 `process-tail`·`Integer/parseInt` 문구 · spawn handle `name` vs registry `session_name` · child doctrine (B) 미분리 · 봉투 문구 · SCI 닫힌 이름.

- 기준점: stable = v0.8.1, oracle pin = v0.8.1 + `bc0fa7606`. Entwurf #88.
- Do not touch: Python oracle 삭제, `ipython` 개명, `list_names`, snapshot/restore, `spit`/`slurp`, Emmy.

# RECENT

- [2026-09-01 밤] **범위 결정이 게이트 사실이 됐고, 숫자가 세 번 움직였다.** GLG 가 MCP 를 제품 범위로 제외 →
  원장 어휘 `parity-target(H<n>)`/`out-of-scope(GLG,2026-09-01)` + `decision_url` 열, **게이트 이원화**(① 재감사 닫힘 / ② parity 기준점, `exit 3` 신설),
  리터럴 숫자 제거. 카드 대기 **14 → 0**. `72 → 70`(`D-SKILL-SURFACE` 는 호스트가 이미 같은 네 에러를 밀어 H12 에 지을 게 없다) →
  `70 → 69`(`test_second_await_after_cancelled_oneshot_only_waits` 본문에 **cancel 이 없다** → `c` + H4 포인터).
  SCI 는 tight loop 에서 `Thread.interrupt` 를 **못 본다**(native 3/3, JVM 대조 동일) — 그런데 오라클에 순수 루프 취소 계약이 **0건**이라
  **갈래 ⑤**(셀 스레드 + parking, **SCI 무변경**)가 `thread-interrupt 11 + parking 4` 를 덮는다. interrupt 를 조용히 버리던 자리에
  negative contract(`InterruptNotSupported`, `id = nil`). `BASELINE.md` 에 격리 fixture 계약과 recovery 용어절.
  형제 다섯(fable 감수 · opus 구현×2 · kimi 검수 · sol 방향), 21:15 재부팅으로 셋 소실 — 영수증은 전부 이슈 #1 에 있다.
  **함께 틀린 곳 셋:** 프롬프트 표면을 `rlm.ts` 와 동일시 · 조정이 `D-DISPLAY 5` 를 지어냄 · **오라클 test 수를 제품 가치로 읽음.**
  **NEXT 정리:** Pass A/B/C 헌장·착수 순서·분류 4분기·지도·표 형태·부채 전량 서술을 덜어냈다. 전부 이슈 #1 스레드와
  `evals/coverage-denominator/`(manifest·registry) 에 산다. NEXT 는 좌표만 든다.

- [2026-09-01] **범위가 줄고 blocker 가 하나 남았다가 그것도 무력화됐다.** GLG 가 MCP 를 **제품 범위로** 제외 —
  분모 120→72, 홉 9→4, HTTP·인증 blocker 후보 소멸. 이어 SCI 인터럽트를 실측: **tight loop 미관찰**(native 3/3, JVM 대조 동일).
  그런데 `D-INTERRUPT` 21건 전수 분류 결과 **순수 계산 루프 계약이 0건**이고 오라클도 유일한 `while True: pass` 를
  watchdog 프로세스 exit 으로 푼다 → **갈래 ⑤가 17/21 을 SCI 무변경으로 덮는다.** interrupt 를 조용히 버리던 자리에
  negative contract + named test 2 + kill receipt 3(강 2·약 1). 형제 셋(fable 감수 · opus 구현 · kimi 검수) 전원 보고,
  21:15 재부팅으로 전원 소실 — 영수증은 이슈 [5493202373](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5493202373) ·
  [5494371045](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5494371045) 에 있다.
  **함께 틀린 곳: 프롬프트 표면을 `rlm.ts` 와 동일시했다** — 실제 조립은 `system-prompt.ts` 이고 부록 넷 중 런타임 분기는 하나뿐이다.

- [2026-09-01] **Pass C 계획 종결.** 계획 4회차 + Fable/sol 감사 5회. 결정 숫자 **A29 / B3 / C13 = 45**,
  이제 `kill_bucket` 열에 있고 **게이트가 partition·전이·중복을 하드 실패로 검사**한다 — `killed/weak` 가 처음으로 기계 규칙이 됐다.
  **유효 mode-1 = 0**(후보 5개 전부 fixture 이거나 의존물 교체). **C 13 = equivalent mutant**, 각 행이
  「그래서 이 초록이 보장하는 것」을 든다 — *"자손이 죽는다. descendants sweep 이 그 일에 필요한지는 모른다."*
  **A 안에서 C 가 세 번 나왔다**(`H4.4`·`H1.4b`·`H1.5a`) — 다음 사람은 A 를 표본으로 다시 물어야 한다.
  `run.sh table` 로 손 세기 표면 제거. GLG 결정 자리는 [이슈 #2](https://github.com/junghan0611/prime-agent/issues/2).
- [2026-09-01] **부채 정리 끝.** `c` 40 → 17 → **7**, `test-native` **65 → 83 / 0 failures**.
  남은 7은 네 갈래로 처분 확정(탈것 재현 불가 2 · Pass C 킬 2 · OPEN by contract 1 · 선언된 편차 1 · 무포인터 1).
  실측 발견 둘: **`with-out-str` 가 네이티브에서 죽는다**(core 매크로가 `(java.io.StringWriter.)` 로 확장 —
  계약서 「코드를 읽어야만 알던 것」 8 이 모델이 닿는 자리에서 실현), 그리고 그 에러가 **호스트 스택 23프레임을
  모델에게 돌려준다** — `error` OPEN 이 추상이 아니라는 살아 있는 영수증.
  `H4` 알려진 편차 선언: `:status` 는 그룹이 아니라 리더를 보고한다.
- [2026-09-01] **Pass A 끝.** production 이틀 0줄 → **`71 tests / 0 failures`**. 빈 행 4 → **0**.
  분모가 열거의 산물에서 **실행의 산물**이 됐다(AST 261 = 오라클 런타임 수집 261, 이 HEAD 에서 처음 돌렸다).
  `evals/coverage-denominator/` 신설 — 게이트가 미매핑·stale·풀리지 않는 id 에 **시끄럽게 실패**하고,
  구조만 검사하고 **의미는 검사하지 않는다**는 사실까지 스스로 인쇄한다. 킬 영수증 6건.
  카드 14장 · Pass B 결정 시트. 검수 Fable 5(Defect 3) · sol(Blocker 1, 의미 표본 오분류 7.3%).
  `c` 40 → 17 (승격 21 · `b` 2 · 부채 17) — **안 올린 17이 이 표를 믿을 수 있게 만든 부분이다.**

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
- [2026-08-31] `run.sh` 신설 + 좌표 인용 폐지 `c30f1514`. 문서·소스의 줄번호 인용 11개를 심볼
  이름으로 교체(`resolveClojureRuntimeExecutable`, `agentMessageAnnouncedToModel`,
  `parentReplyCountBeforeRun`, `sci/init` 바인딩 맵, bad-ready 테스트 이름). 명령 문자열은
  `./run.sh` 로 이사 — `clj`/`py`(두 팔) · `build` · `test` · `test-native` · `lint` · `check`.
- [2026-08-31] terra `651b4c` 검수 → **실물 결함 1건.** `run.sh` 주석이 "체크아웃마다 따로"라 적었는데
  소켓 경로에 체크아웃 식별자가 없어 같은 UID 의 모든 worktree 가 `clj.sock`/`py.sock` 을 공유했다
  (`main.ts` 는 `--daemon-socket` 을 그대로 쓴다). BASELINE 인터뷰가 이 wrapper 로 도니 양-arm
  영수증의 격리가 조용히 깨질 자리였다. 수정 `0101191e` — 경로에 checkout 해시, 띄울 때 소켓 경로
  출력, `py` arm 에서 clojure env 제거. **`bash -n` 은 의미 오류를 잡지 못한다.**
- [2026-08-31] 좌표 확정 — 재감사 = 커버리지 대응 + kill receipt, 둘 다. 4인 검수 통과.
  261:65 경보로 강등. `clojure-runtime.md` 「코드를 읽어야만 알던 것」 1·2 행 추가. 이슈 #1 좌표 댓글.
