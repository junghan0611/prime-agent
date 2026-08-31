# feat/clojure-runtime — 8홉 레일 닫힘

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H8은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = **H1–H8 재검수 — 테스트로 비교 검출** (방향 확정 2026-08-31, 이슈 #1). H2 leftover receive 닫힘 (`ed702304`). Emmy로 바로 가지 않음.

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

# NOW — H1–H8 재검수: **테스트로 비교 검출**

새 기능 없음. Emmy 없음. 코드 품질 리뷰 아님. 질문은 하나 — **각 홉이 조용히 깨졌을 때 무엇이 잡아내는가.** 산출물은 의견이 아니라 커버리지 표.

방향 확정: 이슈 [#1 comment](https://github.com/junghan0611/prime-agent/issues/1#issuecomment-5475204988) (2026-08-31, Opus 코디 + Sonnet 측량 + zai/glm-5.3 2회 검수, 전부 읽기전용).

## 계량법 — 셋을 병기한다

| 축 | python | clojure |
|---|---|---|
| **런타임 층** (진짜 분모) | **261** pytest | **65** deftest |
| TS 호스트 pin | occurrence **79** / python-경유 it **~105** | 대칭 it **14** |

```bash
grep -c 'def test_' prime-agent-runtime/test/*.py                        # 261
grep -h '^(deftest' prime-agent-runtime-clj/test/rlm/*_test.clj | wc -l  # 65  ← 앵커 필수
grep -ro 'runtime: *"python"\|kernelRuntime: *"python"' packages/coding-agent/test/ | wc -l  # 79 occurrences
grep -rl 'runtime: *"python"\|kernelRuntime: *"python"' packages/coding-agent/test/ | wc -l  # 20 files
grep -rl 'kernelRuntime: *"clojure"\|runtime: *"clojure"' packages/coding-agent/test/       # 4 → 20-3=17 순수 비대칭
grep -cE '^\s*it(\.skipIf)?\(' packages/coding-agent/test/repl-kernel-clojure-runtime.test.ts # 14
```
`grep -c 'deftest '` 는 require의 `[deftest is]` 를 잡아 73으로 부풀린다. `it(` 정적 카운트는 `it.skipIf(` 를 놓쳐 13으로 샌다(실제 14). **표는 it 단위로 센다. 79는 occurrence 메트릭으로만.**

## 분류 4분기 — 정적 징후로 가른다

- **(a)** python이 주제 — `pythonSkills:[...]` 넘김 · venv 요구 · snapshot/restore 단언
- **(b)** 우연한 pin — `python:"/nonexistent…"` · mock child 주입
- **(c)** 대칭 기능, 테스트만 없음 — 진짜 커널 프로토콜 단언
- **(D)** 기능 자체 없음(제품 구멍) — clj src에 개념 부재

**판정법:** `expect` 대상이 `buildX()` 반환값이면 정적, `manager.execute(...)` 반환값이면 라이브.
파일 단위 3분법은 큰 파일에서 깨진다 — `acp-kernel-features`·`kernel-goal-skill`·`kernel-rlm-heartbeat-skill` 은 한 파일에 (a)/(b) 혼재. 확정된 오분류 2건: `attach-image` (b)→**(D)**, `ipython-provisioner` (a)→**(b)**.

## 착수 순서 — TS 뒤집기가 첫 수가 아니다

1. **런타임 층 261:65 대응표** + **(D) 목록을 `docs/clojure-runtime.md` 에 선언.** 최대 구멍: **`test_mcp`+`test_mcp_base` 49 ↔ 0** (clj src 0파일, 계약서 0건 — 제외 선언조차 없음). `test_harness` 35↔0 동일. `test_winjob` 22 는 정당 (a).
2. **env 이음새 비대칭 기록** — runtime *종류*는 팩토리 인자지만 실행파일 주입이 비대칭. python은 `python` 옵션, clojure는 **`PRIME_AGENT_CLOJURE_RUNTIME` env로만** (`src/core/kernel/runtime.ts:66-77`). `describe.each` 가능하되 arm별 주입을 갈라야 한다.
3. **receive 재판정 기계화** — `src/core/agent-session.ts:10502` 캡처(`parentReplyCountBeforeRun`) → `10514-10518` 비교. `child._parentReplyCount === parentReplyCountBeforeRun` 일 때만 notice 발화 → Q-R3 재판정은 A/B 재실행이 아니라 **인터뷰 + 카운트로 $0**.
4. **TS 17파일 뒤집기** (20 중 3개는 이미 clojure 형제 단언 보유): `kernel-agent-message-skill`(7) → `kernel-agent-observe-skill`(2) → `acp-kernel-features`(4).

## 환경 사실 — 모르면 첫 시도에서 막힌다

- 실행은 **`npx tsx ../../node_modules/vitest/dist/cli.js --run test/<file>.test.ts`**, `packages/coding-agent` 에서. **`npm test` 전체 금지** (AGENTS.md:27-28).
- 네이티브 바이너리 `prime-agent-runtime-clj/target/rlm-repl` (없으면 `native-image/build.sh`). 게이팅은 `it.skipIf(!existsSync(nativeRuntime))` 패턴.
- **CI는 lint 전용** (`clojure-runtime.yml` — GraalVM 없음). flip 결과는 로컬에서만 돈다.
- **경로 주의:** `agent-session.ts` / `repl-manager.ts` / `runtime.ts` 는 전부 **`packages/coding-agent/src/core/`** 아래다.
- **shape 함정:** `src/core/kernel/repl-manager.ts:841` `handler({ ...data, cellSourceCode })` — 양 팔 공통의 의도된 provenance 태깅. clojure 단언에 반영해야 python 파일과 대칭이 된다.

## 오늘 실증된 것 (능력 부재가 아니라 테스트 부재)

네이티브 바이너리 라이브 프로브: `list_agents` roster 횡단 · `send` round-trip · receipt 회귀 · 2회 독립성 **5/6 pass**. 소스가 명문화 — `src/core/agent-session.ts:9148` *"The host verbs themselves are runtime-neutral."*
인프라 영수증: `repl-kernel-clojure-runtime` 14 pass 4.3s(네이티브 스폰, 외부의존 없음) · `repl-kernel-execute` 6 pass 2.9s(실 python 커널). **양쪽 다 이미 돈다.**
라이브 검증은 0이 아니라 **1** — `test/repl-kernel-clojure-runtime.test.ts:341` "drives a host request through the native runtime".

## 범위 — 선언은 안에, 구현은 밖

(D) 제품 구멍은 **이름붙은 목록 + 계약서 선언**까지만. 구현은 별도 홉(ROADMAP: "재검수는 새 기능 아님"). 커버리지는 무료 vitest 축이, 가능성은 유료 A/B 축이 증명한다 — 두 축은 겹치지 않으므로 A/B를 줄이지 않는다.

**완료 조건:** 17파일이 it 단위로 (a)/(b)/(c)/(D) 표의 행이 되고, (c) 중 FAIL한 것이 "채울 빈 곳"으로 이름을 얻는다.
(a)(b)(c)(D)는 **커버리지 축**, `harness-gap`/`surface-seam`/`semantics-gap`/`model-fumble` 은 **원인 축** — 상보. FAIL에 원인 태그를 함께 달면 표 한 장에서 entwurf#88 연구질문이 읽힌다.

## 폐기된 항목

- ~~ROADMAP "native/JVM SUT rails" 문구 정정~~ — 살아있는 문서에 그 문구가 없다(grep 0건). 커밋 `311643a6` 제목일 뿐이고 `56631f36` 이 의도적으로 지웠으며 이유는 `docs/clojure-runtime.md:136`. **체크리스트에서 삭제.**

## 누적 leftover (blocker 아님, 위 표로 흡수)

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
