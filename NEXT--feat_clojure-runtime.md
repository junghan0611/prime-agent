# feat/clojure-runtime — H7 checkpoint, H8 대기

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H7은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = H8 대기 (시작 아님). 구현 Opus `61f23b`. 검수 테라 `55b3ea` PASS. GLM `abff01` 퇴근.

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
- [ ] **H8** — ROADMAP. **시작 아님.**

현재 좌표: H0–H7 checkpoint → H8는 default switch + soak (시작 아님)

# NOW — H7 닫힘, 푸시·H8는 GLG

- Stem: H7 `b5e9e424` (ahead 5, 푸시 안 함). 산출물 `evals/h7-functional-ab/`.
- Next: GLG 푸시. **H8 시작하지 않음.**
- **GLG 결정으로 H7 은 논문 eval 이 아니라 기능 A/B 였다.** PMPP pin 안 함, GPU 안 빌림,
  `prime login` 안 함. 두 arm 의 유일한 차이는 `PRIME_AGENT_KERNEL_RUNTIME`.
- **결과 한 줄:** 8/8 런이 REPL 을 썼다. clojure arm Python 문법 유출 0, tool crash 0,
  셀 에러는 clojure 2 / python 2 — Clojure 쪽이 더 틀리지 않았다. 점수 비교 아님.
- **H7 이 찾은 것 (RESULTS.md 4개):** (1) `process-tail` 이 "lines" 로 읽히는데 String 을
  돌려줘 모델이 2셀 낭비 — prompt 한 줄이면 닫힘 `harness-gap` (2) `Integer/parseInt` 닫힘은
  계약대로 동작 `semantics-gap`, 모델이 스스로 `edn/read-string` 으로 회복
  (3) **H2 fan-in gap 그대로** — clojure arm 은 `agent_message` 가 없어 child 가 답을 못 보낸다.
  P4 에서 부모가 답을 안 것은 host 의 terminal notice 가 "Last assistant text: ok" 를
  실어 날랐기 때문. capability 아니라 우연 `harness-gap`
  (4) spawn handle 의 `name` 과 registry entry 의 `session_name` 이 다르다 — **양쪽 runtime 다**.
  python arm 이 여기서 `AttributeError` 로 죽었다. H6 은 키 *철자*만 통일했지 field set 은 아니다.
- **(1)(2) 는 prompt 한 줄씩이면 닫히지만 안 고쳤다.** 측정 뒤 표면을 바꾸면 RESULTS.md 와
  GLM 실측이 어긋난다. 요청하면 고칩니다.
- **H6 leftover / H5 leftover / H4 leftover:** 이전 그대로.
- Do not touch: Python oracle, `ipython` 개명, `list_names`, snapshot/restore, `spit`/`slurp`,
  Emmy, H8, verifiers env / GPU / `prime login`.
- Blocker: 없음. 푸시는 GLG.

# RECENT

- [2026-08-30] H3 닫힘. 푸시 `ba2bba55`.
- [2026-08-30] H4 `4c42dbb4` → `9229aa77`. 푸시 `a114668f`.
- [2026-08-30] H5 `2e5753a2`. GLM PASS. H5 close 권고.
- [2026-08-30] H6 `2ea1b170`. GLM PASS, 코디가 닫음. 측정된 defect: `(rlm …)` 는 `:rlm-child-id`,
  raw `host-request` 는 `:rlm_child_id` — 회수한 handle 만 어긋났다. 키 정규화로 닫힘.
- [2026-08-30] H7 `b5e9e424`. GLG 가 논문 eval 대신 **기능 A/B** 로 돌림. DeepSeek 8런 $0.00576.
  P4 에서 `(rlm-children)` 이 live 로 동작 — H6 이 만든 dashed key 가 트레이스에 보인다.
