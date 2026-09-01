# feat/clojure-runtime — 8홉 레일 닫힘

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H8은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = **H1–H8 재감사 — 커버리지 대응 + kill receipt** (좌표 2026-09-01, 이슈 #1).
**Pass A·B 는 끝났다. GLG 가 범위를 결정했다 — MCP 는 out, 분모 120→72.** 표는 0행이 아니다:
행 30 · 카드 14 · 분모 게이트 · 양 arm 실행 영수증이 서 있다.
**단 그 72 는 아직 게이트 사실이 아니다** — 원장·`check.py` 미집행이 조정 부채로 남아 있다(NOW).
**H1 은 아직 닫히지 않았다**(전 행 `green/no-kill`, 킬은 Pass C) — 그러므로 **TS 17파일을 열지 않는다.** Emmy 는 성능평가 다음.

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
- [x] **H4 process lifecycle** — `4c42dbb4` → `9229aa77`. id registry + setsid group. native 48/313. leftover 「SIGKILL orphan」은 `H4.8`/`H4.9` 로 닫힘.
- [x] **H5 edit / write receipts** — `2e5753a2`. write-text / edit-text. native 57/443. GLM `abff01` PASS.
- [x] **H6 compaction / restart continuity** — `2ea1b170`. registry 회수 verb + runtime별 통지. native 65/497.
- [x] **H7 기능 A/B (DeepSeek)** — `b5e9e424`. 8런 both arms, $0.00576. 테라 `55b3ea` raw JSONL 재분석 일치.
- [x] **H8 default switch + soak** — `edc3a3e8`. default = clojure, fallback 없음. 테라 `55b3ea` native 65/497 PASS.
- [x] **H2 leftover receive** — `ed702304`. 2차 `primeclj` Q-R3 PASS (notice 0). H8 재오픈 아님.
- [ ] **H1–H8 재검수** — **진행 중.** Pass A(기계) 끝, Pass B(GLG 결정) **수령**, Pass C(킬) 남음.
  홉을 빼지 말고 전부. 방법과 지금 사실은 아래 NOW. 새 기능 아님. Emmy 아님.
- [ ] **Emmy / SICM** — 재검수 다음. 지금 열지 않음.

현재 좌표: H0–H8 checkpoint · leftover receive 닫힘 → **H1–H8 재검수 — Pass A·B 끝** → 원장·게이트 집행 + **H9–H12 parity 홉** → Pass C(킬) → (그다음 Emmy).

# NOW — Pass B 결정 수령. **H9 갈래 결정 + 원장·게이트 집행 대기**

좌표: 이슈 [#1](https://github.com/junghan0611/prime-agent/issues/1) 스레드. **본문과 어긋나면 스레드가 이긴다.**

## 2026-09-01 저녁 레인 — 무엇이 바뀌었나 (여기부터 읽어라)

**세 가지가 바뀌었다. 하나는 GLG 결정, 하나는 측정, 하나는 사고다.**

**① GLG 범위 결정 — MCP 는 이 포크의 RLM 이 쓰지 않는다.**
기준은 「Python 이 되는 범위」이고 기본값은 support 이지만, GLG 가 **제품 범위로** MCP 를 뺐다 —
*"내가 바라보는 rlm 에이전트는 mcp 안 써. 인터넷 검색도 안 써. 스킬도 전통적인 방식으로 없어도 돼.
lisp 으로 agent-server 로 내 하네스를 eval 하는 것 제외하면 그냥 모델 그 자체로 어떠한 외부 자극 없이
어느 정도 단절된 터널에서 쭉 나아가게 할 거야."*
→ 분모 **120 → 72** (MCP 48 out). 홉 9개 → **4개**. **HTTP 클라이언트·인증 계약 분기 blocker 후보가 통째로 사라졌다.**
좌표: [#2 재범위 댓글](https://github.com/junghan0611/prime-agent/issues/2#issuecomment-5492713076).
정정 둘: `find_models` 는 웹검색이 아니라 **자식 스폰용 모델 조회**이고, `harness.py` 는 전통적 스킬 계층이 아니라
**에이전트 자기 노트장**이다 — 둘 다 터널 안쪽이다.

**② 측정 — SCI 는 계산 루프에서 `Thread.interrupt` 를 관찰하지 못한다.**
이 레인의 마지막 미측정 blocker 후보였고 답은 「관찰하지 못한다」다. 그러나 **그 사실이 H9 를 막지 않는다** —
오라클의 `D-INTERRUPT` 21건 중 **순수 계산 루프는 0건**이고, 오라클 자신도 유일한 `while True: pass` 를
interrupt 가 아니라 **watchdog 의 프로세스 exit** 으로 푼다(그 테스트는 `D-OWNER-WATCHDOG` 소속이다).
→ **갈래 ⑤(셀 스레드 + `Thread.interrupt` 전달 + parking 상태기계)가 17/21 을 SCI 무변경으로 덮는다.**
영수증: [측정·negative contract](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5493202373) ·
[21건 전수 분류 + 갈래 ⑤](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5494371045).

**③ 사고 — 21:15 오라클 재부팅으로 형제 셋이 한꺼번에 사라졌다.**
감수 fable · 구현 opus · 검수 kimi 의 tmux 창이 전부 날아갔다. 세션 안에만 있던 영수증은 **건너가지 못한다.**
그래서 위 두 링크가 있다. **다음 형제도 같은 방식으로 죽는다고 가정하고, 결정적 줄은 즉시 이슈로 옮겨라.**

### 그래서 지금 열려 있는 것 — GLG 자리 셋

1. **H9 갈래** — ⑤ 채택 여부. tight-loop 하나만 declared-divergence → H11 watchdog 으로 보낸다.
   **이것이 정해져야 실무가 움직인다.**
2. **Defect 3 — H10 ↔ `BASELINE.md` 충돌.** H10(harness state)이 parity-target 이 되는 순간
   세션 넘는 global 항목은 **기능**인데, `BASELINE.md` Q-R0 은 "이전 세션 기억 없음"을 지시하고
   FAIL 기준에 "claims memory of another session" 을 둔다. **BASELINE 개정 vs 인터뷰별 격리 global store.**
3. **홉 배치 확정** — H9 셀취소 21 · H10 harness 33 · H11 프로세스회수 11 · H12 표면 7 (`emit` + `find-models`).

### 조정 부채 — 결정과 무관하게 닫아야 한다 (Defect 1)

**「분모 72」는 이슈 댓글에만 있고 게이트에는 없다.** 2026-09-01 22:00 재측정:
`registry.tsv`/`manifest.tsv` 에 `out-of-scope` **0 hit** · D-* 카드 14장 전부 `DECISION REQUIRED` ·
`check.py` 의 `chosen = {support, exclude, future}` 는 새 어휘를 모름 · 게이트 **exit 1**.
집행할 것: ⓐ 카드 status 를 `parity-target(H<n>)` / `out-of-scope(GLG,2026-09-01)` 로 재작성하되
**GLG 결정 영수증 URL 을 앵커로 달 것**(에이전트 편집만으로 도달 가능한 status 가 되지 않게)
ⓑ `check.py` 의 `chosen` 확장 ⓒ **게이트 이원화** — 재감사 닫힘 / parity 기준점 도달은 다른 선이다
(*"terminal 은 회계가 끝났다는 뜻이지 Python 대체 기준을 충족했다는 뜻은 아니다"* — GLG)
ⓓ 분모 261 과 D=120 은 **불변**, 48 은 지우지 말고 `out-of-scope(GLG,…): 48` 로 **매번 함께 인쇄**.
**숨기지 않는 제외만 제외다.**

### 감수·검수가 남긴 살아 있는 결함 둘 (H10 이 흡수한다)

- **harness 프롬프트 블록이 clj 전 세션에 Python 문법을 상시 주입한다.**
  `_loadMergedHarnessState` 가 항상 객체를 반환 → `buildSystemPrompt` 의 `if (harnessState)` 항상 참 →
  Call contract 가 `await rlm(...)`·`await agent_message.send(...)` 를 가르치는데,
  같은 프롬프트의 `CLOJURE_REPL_CONTROL_PROMPT` 는 *"Do not write Python … `await` … are not valid here"* 라 적는다.
  **엔트리 0 개인 빈 상태에서도 렌더된다.** 진짜 결함은 "clj 변형이 없다"가 아니라
  **변형 선택자가 `hasIpython = tools.includes("ipython")` 이라는 상수에 묶인 것**이다 —
  이 포크가 커널을 런타임 중립으로 만들면서 clj 커널도 `ipython` 도구 뒤에 섰고(`ToolName` 단일, 개명 금지),
  프롬프트 조립만 도구 이름에서 런타임을 추론하는 채로 남았다. 수정 지점은 **런타임 신호의 별도 통로**다.
- **MCP out-of-scope 에 경계 negative test 가 없다 (Defect 2).** `mcp.refresh`/`mcp.config` 는 런타임 중립 등록이라
  clj 셀이 `host-request` 로 촉발할 수 있다 — **"MCP 범위 밖"은 프롬프트 층에서만 참이고 프로토콜 경계에서는 아직 거짓이다.**

### H7 판정에 대한 정확한 문장

**"H7 판정은 선다" 참 · "오염된 런이 없었다" 미증명.** `model-fumble` 0 · pyLeak 0 은 **문법 축** 측정이고,
발견 3(clj 자식이 답을 안 보냄 / Python 자식은 보냄)은 **행동 축**이다. 프롬프트가 clj 팔에 없는 capability 를
Python 문법으로 가르친 자리가 정확히 거기다. 두 문장을 붙여 쓰지 마라.

## 지금 사실 (2026-09-01 저녁, oracle · 워킹트리 clean — interrupt negative contract 는 이 커밋에 들어갔다)

```
kill buckets: 45 rows owe a kill -- A(strong)=29 B(weak, earns killed/weak not PASS)=3 C(equivalent mutant, documented limit)=13
              B: H1.9 H4.11 H6.6
              C: H1.10 H1.13 H1.14b H1.18 H1.23 H1.4b H1.5a H1.7a H1.7b H1.7c H4.3 H4.4 H4.5
registry:     52 rows, 15 cards
denominator: 261 oracle tests (unittest, AST-extracted)
verdicts:    D=120  a=91  b=4  c=9  row=37
host_skip:   6 tests carry a runtime skipTest guard (a contract this host may never watch Python keep)
(a) anchors:  40 of 91 (a) entries name a row that shows the exclusion is enforced -- 38 via PASS (H1.3b), 2 via green/no-kill (H1.3c)
row status:  36 of 36 REFERENCED rows are not PASS -- H1.10=green/no-valid-mutant, H1.11=green/no-kill, H1.12=green/no-kill, H1.13=green/no-valid-mutant, H1.14a=green/no-kill, H1.15=green/no-kill, H1.16=green/no-kill, H1.17=green/no-kill, H1.18=green/no-valid-mutant, H1.19=green/no-kill, H1.20=green/no-kill, H1.4a=green/no-kill, H1.5a=green/no-valid-mutant, H1.5b=green/no-kill, H1.6=green/no-kill, H1.7a=green/no-valid-mutant, H1.7b=green/no-valid-mutant, H1.7c=green/no-valid-mutant, H1.9=green/no-kill, H2.1=green/no-kill, H4.10=green/no-kill, H4.1a=green/no-kill, H4.2=green/no-kill, H4.3=green/no-valid-mutant, H4.4=green/no-valid-mutant, H4.5=green/no-valid-mutant, H4.6=green/no-kill, H4.7=green/no-kill, H4.8=green/no-kill, H4.9=green/no-kill, H6.1=green/no-kill, H6.2=green/no-kill, H6.3=green/no-kill, H6.4=green/no-kill, H6.5=green/no-kill, H6.6=green/no-kill
unreferenced: 16 of 52 rows have no manifest entry terminating at them -- PASS: H1.1a H1.2 H1.3b; declared-divergence: H1.22 H4.D1 H6.7; empty: H1.8; green/no-kill: H1.1b H1.21 H1.3a H1.3c H4.11 H4.1b; green/no-valid-mutant: H1.14b H1.23 H1.4b
dead triggers: 5 of 14 cards carry a reopen trigger that cannot fire -- D-HARNESS-CRUD, D-HARNESS-SCOPE, D-HARNESS-EXTERNAL, D-ORPHAN-JOURNAL, D-OWNER-WATCHDOG
semantic:    stratified sample n=55 (sol, 2026-09-01) -- 4 terminal misclassifications found (7.3%), all 4 fixed. This gate checks structure, not meaning; the sample implies more remain.
OPEN DEBT -- 9 (c) entries owe a row, 14 cards await a GLG choice:
exit=1
```

- **clj:** `./run.sh test-native` → **`85 tests / 0 failures`** (아침 65 → H1.4 로 67 → 빈 행 71 → 부채 정리 83 → interrupt negative contract 85). `./run.sh test repl-kernel-clojure-runtime` **14 passed**. `./run.sh lint` 0/0.
- **python oracle:** 전체 실행 6회 중 **5회 green**. 1회 `test_bash.BashTest.test_term_ignoring_child_is_escalated` 실패(journal record `active == true`), 단독 재실행 3/3 green. **full-suite 재현성 간헐 실패이며 원인 미확정.**
- **production source 0줄.** 바뀐 것은 `prime-agent-runtime-clj/test/` 다섯과 `docs/clojure-runtime.md`(stale 배너 + H4 알려진 편차) 하나, 그리고 artifact `evals/coverage-denominator/`.
- **인용 규칙:** `85 tests / 0 failures` 만 안정 수로 쓴다 (assertion 은 같은 트리에서 622 와 623 이 둘 다 관측됐다). assertion 총수는 영수증에 적되 **비교 근거로 쓰지 않는다** — 흔들리는 메커니즘이 밝혀져 있다(아래 은퇴 목록).

## 다음 세션 첫 손 — **뒤로 1–2발자국 재검증** (GLG 지정)

> **"다음 텀은 우리 한 것에서 뒤로 1–2 발자국만 돌아가서부터 검증하고 다음 진행."** (GLG)

습관 문구가 아니라 목록이다. **무엇을 돌리고 무엇이 보이면 통과인지**가 각 항목에 있다.

**① 게이트가 자기 주장을 무는가 — 이게 첫 손이다.** 이것이 안 물면 그 아래 전부가 근거를 잃는다.
- `evals/coverage-denominator/run.sh check` → **exit 1**, `kill buckets … A(strong)=29 B(…)=3 C(…)=13`, 합 **45**
- **오늘 만든 킬 영수증 5건을 다시 재현한다.** 임시 사본에서 registry 를 흔들어 각각 **exit 2** 인지 확인: 킬을 빚는데 통에 없는 행 · `PASS` 인데 통을 든 행 · **B 행을 `PASS` 로**(→ `a weak kill never earns PASS`) · 같은 row id 중복 · 통 합 불일치

**② 분모가 여전히 서 있는가**
- `run.sh extract` → **261**
- 오라클: `prime-agent-runtime/` 에서 `uv run --frozen python -m unittest discover -s test` → `Ran 261 tests`.
  **간헐 실패 1건(`test_bash.BashTest::test_term_ignoring_child_is_escalated`)은 알려진 것이다** — 6회 중 1회, 원인 미확정. 놀라지 마라
- 숫자는 **손으로 세지 말고** `run.sh table verdicts` 로 렌더한다

**③ green 수선 3건이 아직 계약을 무는가**
- `H4.1b` marker(`rlm-override-shell-ran`) · `H1.3a` 12슬롯 + `toHaveLength` · `H1.7a` fault 제외
- `./run.sh test-native` **83 / 0 failures** · `./run.sh test repl-kernel-clojure-runtime` **14 passed**

**④ A 통 표본 재검 — A 29 중 2~3개를 골라 다시 물어라: 「이 초록이 더 약한 구현으로도 초록인가.」**

> **오늘 A 안에서 C 가 세 번 나왔다**(`H4.4` · `H1.4b` · `H1.5a`). **다음 사람은 A 통을 신뢰하지 말고 표본으로 다시 물어라.**

## 읽을 곳 (순서대로)

1. **[이슈 #2](https://github.com/junghan0611/prime-agent/issues/2)** — **GLG 의 자리.** 결정 10건(카드 8 + J1 terminal-set + `H1.7b`/`H1.7c`). 이슈 #1 은 흐르는 판이라 결정이 스레드에 묻혀서 갈랐다.
2. **Pass B 결정 시트** — [comment 5490271142](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5490271142).
3. **Pass C 종결 문서** — [comment 5491766546](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5491766546). 실행계획이면서 동시에 **안 태울 때의 종결 문서**다.
4. **Pass A 헌장** — [comment 5489607349](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5489607349).
5. **artifact** — `evals/coverage-denominator/`. `run.sh check` 게이트 · `run.sh table` 표 렌더 · `manifest.tsv` 261행 전수 · `registry.tsv` 행·카드 원장(`kill_bucket` 포함). **산문을 믿지 말고 돌려봐라.**
6. 카드 14장 — [9장](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5489867845) · [5장+재분류](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5489938033) · [D-INTERRUPT](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5489802290).

## 다음 한 걸음 — 우선순위

1. **조정 부채 집행** — 원장 어휘 + `check.py` `chosen` 확장 + **게이트 이원화**. 위 「조정 부채」 ⓐ–ⓓ.
   **GLG 결정(A)(B) 와 무관하게 지금 할 수 있다.** 이것이 안 서면 「분모 72」를 인용하는 모든 문장이 근거를 잃는다.
2. **GLG 자리 셋** — H9 갈래 ⑤ · Defect 3 · 홉 배치. **에이전트가 고르지 않는다.**
3. **H9 착수(⑤ 채택 시)** — 셀 스레드 추적 + `Thread.interrupt` 전달 + parking 상태기계. **SCI 를 건드리지 않는다.**
   tight-loop 은 declared-divergence 로 H11 watchdog 에 보낸다 — 오라클 자신의 답과 같은 모양이다.
4. **Pass C 잔무** — A29 재검증 후 집행(기계 37~48분). 이 레인과 독립, 병렬 가능.
   `H1.7b`/`H1.7c` 는 GLG 가 「집행하지 않음」으로 확정 — `green/no-kill` 그대로.
5. **`H1.8`·`D-INTERRUPT` 원장 갱신** — named test 가 생겼으니 갱신 대상이지만
   **negative contract 는 커버리지 크레딧이 아니다**(Hard Rule 3). `D-INTERRUPT` 21건은 `parity-target(H9)` 로 두고
   negative-contract 행만 붙인다.
6. **부채 9** — 처분 확정. **새 green 테스트로 더 줄일 자리는 없다.**

## 살아 있는 금지

- **커밋·푸시는 GLG 의 현재 세션 지시가 있을 때만.** 커밋 요청이 푸시를 함의하지 않는다.
- **형제의 영수증을 세션에 묵혀두지 않는다.** 21:15 재부팅으로 형제 셋이 한꺼번에 사라졌다 — 결정적 줄은 즉시 이슈로 옮긴다.
- **TS 테스트 17파일을 열지 않는다** (H1 미닫힘). `src/` 읽기는 계약 찾기용으로 허용.
- **`git stash` 금지** — 여러 형제가 같은 트리에 있다. 격리는 임시 worktree.
- **새 카드·새 계측 동결.** manifest 전수 재검증 금지.
- **`PASS` 는 강한 킬 뒤에만.** 약한 킬은 `killed/weak` 이고 **게이트가 B→`PASS` 를 하드 실패로 막는다.**
- **v5 계획 문서를 쓰지 않는다.** 다음 산출은 **킬 영수증이거나 C 통 종결 문서**다.
- **원천(오라클 테스트 또는 계약서 문장) 없는 원리 행 신설은 `H1.8` 에서 캡한다.**
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

## 남은 부채 9건 — **다섯 갈래로 처분이 끝났다** (전량은 `manifest.tsv`)

`c` 40 → 17 → 7 → **9**. **숫자가 도로 올랐고 그게 맞다** — 2차 검수가 "대응"이라 적힌 넷을 되짚어 셋을 편차·약화로 돌려놓았다. **`c` 를 낮추는 것은 목표가 아니었다.**

| 처분 | 항목 | 뜻 |
|---|---|---|
| **탈것 재현 불가 + 포인터** | `large_buffer_write…`→`H1.23` · `slow_pump…`→`H4.11` · `exception_with_broken_str…`→`H1.21` | 오라클은 **탈것이나 보고 모양이 곧 계약**이다. 인접 행이 도달 가능한 더 약한 관측을 물고, **오라클 칸은 덮이지 않은 채** 남는다 |
| **선언된 편차 (NO-CREDIT)** | `running_reflects_group_liveness` · `requires_a_default_session_name` · `rebound_stdout_without_flush…` | 대응이 아니라 **반대 결과**다. 셋 다 `docs/clojure-runtime.md` 「알려진 편차」에 **사실 진술로** 선언. **선언은 커버리지가 아니다**(Hard Rule 3) |
| **seam 이 먼저 필요** | `pump_paused_between_read_and_commit…` | 이전에 「Pass C 킬」로 적었던 것은 **오류였다** — 킬은 **이미 선 green 이 무는지**를 증명한다. 여기엔 green 이 없다. **pause seam/픽스처가 먼저** |
| **OPEN by contract** | `traceback_clean_with_source_line` | 계약서가 `error` 를 OPEN 으로 열어뒀다. **재면 닫히는 게 아니라 정해야 닫힌다.** 추상 아님 — `H1.22` 의 실패가 `rlm.eval$eval_cell`·`rlm.repl$serve` 포함 **23프레임**을 모델에게 돌려준다(측정됨) |
| **탈것 없음, 무포인터** | `delivered_status_wins_when_shell_dies_during_drain` | 탈것도 없고 **그 부재를 무는 행도 없다** → `b` 가 아니라 부채 |

**정리된 leftover:** 「H4 (c) SIGKILL orphan」은 **`H4.8`·`H4.9`** 로 좌표를 얻고 닫혔다.

**아직 green 이 없는 표면 하나:** `rlm-process-cleanup` 훅의 tear surface. `rlm.repl/serve` 가 done 을 쓰기 **전에** `kill-all!` 을 부르므로 정상 shutdown 경로에서 훅은 일하지 않는다 — **훅을 지워도 `H1.4b` 는 초록이다.** 비정상 종료 경로로만 닿는다.

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

`hop / Python 계약(oracle) / Clojure 대응 또는 선언된 divergence / HEAD + named test + **그 행의 assertion·receipt fragment** / 실행 영수증 / **kill receipt** / native SUT 필요 / status`

- **커버리지 열** = Python이 지키던 계약이 Clojure에 대응되는가
- **kill receipt 열** = 그 계약을 고의로 깨면 그 테스트가 죽는가
- **심볼 이름은 찾아가는 주소일 뿐 행의 정밀도가 아니다** (terra `651b4c`). `parentReplyCountBeforeRun` 같은 이름 하나는 캡처·비교·notice 부재의 *관계*를 지시하지 못한다. 행에는 반드시 assertion 또는 receipt fragment 를 함께 적는다.
- **status 어휘:** kill receipt 가 붙기 전의 초록은 `PASS` 가 아니라 **`green/no-kill`** 이다.

kill receipt는 새 개념이 아니다 — H2 receive 랜딩 때 게이트를 되돌려 4건 중 2건이 실패함을 이미 확인했다. 표의 **상시 열로 승격**하는 것.

**행의 단위는 파일·grep 개수·test 함수가 아니라 하나의 observable scenario 다.** 한 Python 파일은 여러 행을 낼 수 있고, 한 named test 도 여러 행의 영수증이 될 수 없다. source 는 계약과 test 위치를 찾을 때만 읽고 **품질 평가는 하지 않는다.**

## 착수 순서 — **1·2·3 은 끝났다. 남은 것은 4번뿐이고 H1 미닫힘이라 열지 않는다**

> 아래는 2026-08-31 에 세운 순서이고 **기록으로 남긴다.** 1번(대응표)은 행 30개로,
> 2번(env 이음새)은 `H4.1a`/`H4.1b` 로, 3번(receive 기계화)은 H2 leftover 로 닫혔다.
> **4번 TS 17파일은 H1 이 닫힌 뒤다** — 지금 전 행이 `green/no-kill` 이라 안 닫혔다.


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
   - **(D) 항목별 결정** — `49`/`35` 라는 수는 **결정 정보가 아니다.** MCP 에는 auth credential resolution · host refresh · tool list/call · structured/error result 가 섞여 있고 (`prime-agent-runtime/test/test_mcp_base.py`), harness 에는 persistent local/global state · memory/skill CRUD · Python reference enforcement · external-write reload 가 섞여 있다 (`test_harness.py`). 따라서 **capability family 아래 contract bundle 단위로** 카드를 만들어 **이슈 #1 댓글로 올려** GLG 가 고르게 한다:
     `Decision ID | Python user-visible job(한 문장) | Python tests/observable contract | Clojure 현재 surface + 실제 unavailable evidence | RLM loop 에서 잃는 것 / 대체 없음 | dependency·security boundary | smallest paired acceptance test | support 구현 범위 | explicit-exclusion meaning + negative test | future 면 reopen trigger | GLG 선택(support/exclude/future) | owner`
     GLG 선택 전에는 status = **`DECISION REQUIRED`**, (D)/PASS/exclusion 어느 것으로도 쓰지 않는다.
2. **env 이음새 비대칭 기록** — runtime 종류는 팩토리 인자지만 실행파일 주입이 비대칭. python은 `python` 옵션, clojure는 **`PRIME_AGENT_CLOJURE_RUNTIME` env로만** (`runtime.ts` 의 `resolveClojureRuntimeExecutable()`). `describe.each` 가능하되 arm별 주입을 갈라야 한다.
3. **receive 재판정 기계화** — `agent-session.ts` 가 `parentReplyCountBeforeRun` 을 캡처한 뒤 종료 시 비교한다. notice는 `child._parentReplyCount === parentReplyCountBeforeRun` 일 때만 발화 → Q-R3 재판정은 A/B 재실행 없이 **인터뷰 + 카운트로 $0**.
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

- **명령은 `./run.sh help` 가 SSOT 다** — `clj` / `py` (두 팔) · `test <name>` · `test-native` · `lint` · `build`. 문서에 명령 문자열을 적지 않는다. `npm test` 전체 금지는 `AGENTS.md` Hard Rule 9.
- 네이티브 바이너리 `prime-agent-runtime-clj/target/rlm-repl` (없으면 `./run.sh build`). 게이팅 `it.skipIf(!existsSync(nativeRuntime))`.
- **CI는 lint 전용** (`clojure-runtime.yml`, GraalVM 없음). flip 결과는 로컬에서만 돈다.
- **경로:** `agent-session.ts` / `repl-manager.ts` / `runtime.ts` 는 전부 `packages/coding-agent/src/core/` 아래.
- **shape 함정:** `repl-manager.ts` 의 host-request 디스패치 `handler({ ...data, cellSourceCode })` — 양 팔 공통의 의도된 provenance 태깅.
- 인프라는 이미 양쪽 다 돈다: `repl-kernel-clojure-runtime` 14 pass 4.3s(네이티브 스폰) · `repl-kernel-execute` 6 pass 2.9s(실 python 커널).
- `agent_message` 행에서는 네이티브 라이브 프로브가 `list_agents`·`send`·receipt·2회 독립성 **5/6 PASS** 를 보였다 (`agent-session.ts` 의 `agentMessageAnnouncedToModel` 게이트 주석 *"The host verbs themselves are runtime-neutral."*). **이 관측은 `agent_message` 행에만 적용한다.** 다른 빈 행은 표의 named test 와 실행 결과 전에는 '테스트 부재'도 '능력 부재'도 단정하지 않는다.

## 어디에 쓰는가 — **새 문서를 만들지 않는다**

- **NEXT** = 할 것과 좌표만.
- **GitHub 이슈 #1** = 표·영수증·결정을 **공개로 진행한다.** 홉 하나가 댓글 하나. 그래야 서로 봐주면서 돕는다.
- **`docs/` 아래 새 문서를 만들지 않는다.** docs 아래 문서는 deprecated 되기 쉽다 (그래서 `BASELINE.md` 는 루트로 올라왔다). 리포에 문서를 늘리지 않는다.

실무자는 자기 홉의 **이슈 댓글 하나**와 해당 named test 만 만진다. 머지 충돌이 없고, 진행이 공개된다.

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
