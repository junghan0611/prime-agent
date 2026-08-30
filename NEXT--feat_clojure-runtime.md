# feat/clojure-runtime — 8홉 레일 닫힘

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H8은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = H2 leftover receive. 그다음 = **H1–H8 재검수 (내일)**. Emmy로 바로 가지 않음. 9번째 제품홉 없음.

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
- [ ] **H2 leftover receive** — **지금.** H2 제목의 fan-in. H8 재오픈 아님.
- [ ] **H1–H8 재검수** — **내일.** `docs/BASELINE.md` + 홉별 leftover. Emmy 아님.
- [ ] **Emmy / SICM** — 재검수 다음. 지금 열지 않음.

현재 좌표: H0–H8 checkpoint → **H2 leftover receive** → 내일 재검수 → (그다음 Emmy).

# NOW — H2 leftover: receive (fan-in)

명분: H2 제목이 child/registry/**fan-in** 이었으나 receive는 harness-gap으로 남고 H3–H8이 그 위에 섰다. H8(default=clojure)을 다시 여는 게 아니다. 9번째 제품홉도 아니다. `primeclj` 일상 루프가 기본 런타임에서 끊기지 않게 H2가 못 닫은 칸을 닫는다.

- Stem: origin `619790d1`. 인터뷰 SSOT: `docs/BASELINE.md`.
- **2026-08-30 primeclj (DeepSeek v4 pro):** Q-R0/R1/R2/R4 PASS. **Q-R3 FAIL receive.** spawn `sub-72a686e0` 됨. `agent_message.*` unavailable. 모델이 `agent_observe.recent`로 대본을 읽음 — 사이드 채널이지 receive 아님. host notice `completed without sending a reply`. H7 P4와 같은 구멍.
- Next: fan-in 닫힘 = **기존 host messaging의 thin Clojure exposure** (Python 스킬 이름에 묶인 게이트를 풂 + child doctrine). drop-box 파일 / observe 승격은 PASS 아님. `rlm-answer-*.txt` 는 gitignore만.
- Q-R3 PASS 6항 (GPT): spawn → child 끝 → **명시 capability로 reply** → parent가 scrape/notice 없이 받음 → provenance에 child id → Python/Clojure 같은 계약.
- **랜딩됨 (A+B+C, 코드 3곳 전부 조건/텍스트):**
  A `agent-session.ts:9144` 게이트에 `kernelRuntime === "clojure"` OR — Python 분기 무변경.
  B `prompts/rlm.ts` clojure child doctrine 1줄 (전에는 0줄), 맵 리터럴 `:receiver_role "parent"`.
  C `rlm.ts` clojure subagent guidance 의 자기모순 문장 제거.
  새 Clojure verb 없음 · dashed 키 관대화 없음 · Python 패키지 SCI 설치 없음.
- **진단 영수증:** 인터뷰의 *not available in this session* 은 `kernel/repl-manager.ts:835`(**미등록 핸들러**)
  이지 `agent-session.ts:3183`(컨트롤러 부재)가 아니었다. 같은 프롬프트가 `rlm.ts:198` 에서
  `(host-request {:type "agent_message.list_agents"})` 를 이미 가르치면서 host 는 verb 를 등록하지 않았다 —
  프롬프트와 핸들러의 불일치가 원인. 컨트롤러 공급처는 daemon 3곳뿐이고(`daemon-mode.ts:1732/2602/3023`)
  **런타임 조건이 없다.** 그래서 `primeclj` = daemon 필수.
- **테스트 영수증:** 새 4건 (clojure 게이트 · 컨트롤러 없으면 여전히 미등록 · child doctrine 철자 · guidance 정합).
  게이트를 되돌리면 그중 2건이 실패한다(확인함) — 테스트가 이 변경을 실제로 잡는다.
  `npm run check` clean. 표적 실행 14파일 pass; `4649-subagent-model-selection` 2 fail 은
  **stash 한 깨끗한 트리에서도 동일** = pre-existing.
  `agent-session-services.test.ts` 의 hidden-skill 단언은 `kernelRuntime: "python"` 으로 명시 고정 —
  회피가 아니라 그 규칙이 python 팔의 규칙임을 드러낸 것(H8 flip 때와 같은 처리).
- **레일 밖 결정 (기록):** clojure 에서는 `agent-message` 스킬의 `disableModelInvocation` 이 더는
  host verb 를 막지 못한다. clojure 프롬프트(`rlm.ts:198`)가 애초에 그 플래그를 보지 않고 verb 를
  광고해 왔으므로 핸들러도 같게 맞춘 것. 되돌리려면 capability 개념을 1급으로 들여야 하는데 이번 게이트 밖.
- 같은 `docs/BASELINE.md` 인터뷰를 다시 돈다. 그 다음 실측 blocker.
- **재인터뷰 판정 신호:** `agent-session.ts:10507-10510` — notice 는 child 의 parent reply 수가
  안 움직였을 때만 쓰인다. 그래서 Q-R3 에서 `completed without sending a reply` 가 **안 보이면**
  criterion 4 통과의 기계 영수증이고, 또 보이면 send 가 안 일어난 것이다(모델 서술 무관).
- 2번: interrupt. 이번 인터뷰에서 안 밟음. 미리 넣지 않음.
- wait / snapshot: 지금 열지 않음. restart는 빈 workspace가 정직.
- 기준점: stable = v0.8.1. oracle pin = v0.8.1 + post-release `bc0fa7606` (sibling messaging). “stable 그대로”라고 부르지 않음. Entwurf #88.
- **H8 이 한 것:** `DEFAULT_KERNEL_RUNTIME = "clojure"`. oracle 은 `PRIME_AGENT_KERNEL_RUNTIME=python`
  으로 계속 선택 가능. **fallback 안 한다** — 바이너리 없으면 teaching error 로 안 뜬다 (GLG 안 (가)).
- **테스트가 말하게 된 것:** flip 델타는 +49 tests / 16 files 였고 세 갈래였다.
  A(Python 셀을 default 커널에 먹임)·C(Python-backed skill)는 `runtime: "python"` 72곳 명시 —
  숨은 의존을 드러낸 것이지 회피 아님. B(default 단언)는 clojure 로 뒤집음.
  vitest env 우회는 쓰지 않았다. **수선 뒤 델타 0** (양쪽 default 다 8 fail / 4 files, 전부 pre-existing).
- **soak 영수증:** native `clojure -M:test` 65/497 · TS 4,415 pass / 8 fail(pre-existing 4파일) ·
  biome+tsgo clean · default 실측 = env 하나도 없이 p1 1회, Clojure 로 떠서 14 binding 반환,
  1셀·오류 0·Python 유출 0 ($0.0008).
- **레일 밖 (H8 게이트대로 안 함):** 90% median 주장, PMPP, Emmy, `list_names`, snapshot.
- **CI 주의:** GraalVM 없음 — `clojure-runtime.yml` 은 의도적 lint 전용. 그래서 TS 는 바이너리 없이
  초록이어야 하고, 위 A·C 고정이 그것을 만든다.
- **누적 leftover (blocker 아님):** H4 (a)no-setsid (b)re-group (c)SIGKILL orphan ·
  H5 symlink 거부/diff event 없음/delete·rename·mkdir 없음 · H6 busy-kernel 대화상자 Python 어휘 ·
  H7 `process-tail` 문구·`Integer/parseInt` 문구 · spawn handle `name` vs registry `session_name`.
- Do not touch: Python oracle 삭제, `ipython` 개명, `list_names`, snapshot/restore, `spit`/`slurp`, Emmy.
- **Blocker: fan-in (receive).** interrupt는 인터뷰 흔적 보고.

# RECENT

- [2026-08-30] H3 닫힘. 푸시 `ba2bba55`.
- [2026-08-30] H4 `4c42dbb4` → `9229aa77`. 푸시 `a114668f`.
- [2026-08-30] H5 `2e5753a2`. GLM PASS. H5 close 권고.
- [2026-08-30] H6 `2ea1b170`. GLM PASS, 코디가 닫음. 측정된 defect: `(rlm …)` 는 `:rlm-child-id`,
  raw `host-request` 는 `:rlm_child_id` — 회수한 handle 만 어긋났다. 키 정규화로 닫힘.
- [2026-08-30] H8 `edc3a3e8`. default = clojure. flip 델타 +49 → 수선 후 0. 레일 8홉 끝.
- [2026-08-30] H7 `b5e9e424`. GLG 가 논문 eval 대신 **기능 A/B** 로 돌림. DeepSeek 8런 $0.00576.
  P4 에서 `(rlm-children)` 이 live 로 동작 — H6 이 만든 dashed key 가 트레이스에 보인다.
