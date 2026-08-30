# feat/clojure-runtime — H6 닫힘, H7 대기

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H6은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = H7 대기 (시작 아님). 검수는 GLM `abff01`. 코디 grok `cd2dd4`.

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
- [x] **H6 compaction / restart continuity** — `2ea1b170`. registry 회수 verb + runtime별 통지. native 65/497. **GLM 실측 대기.**
- [ ] **H7–H8** — ROADMAP. H7은 verifier pin + A/B. **시작 아님.**

현재 좌표: H0–H6 checkpoint → H7은 verifier pin (시작 아님)

# NOW — H6 닫힘, 검수·푸시는 GLG

- Stem: H6 `2ea1b170` (ahead 3, 푸시 안 함). native 65/497, clj-kondo 0/0, biome+tsgo clean.
- Next: GLM `abff01` 실측. 그다음 GLG 푸시. **H7 시작하지 않음.**
- **H6 이 세운 것 (parity 아님):** compaction 은 프로세스를 안 건드리므로 workspace 가 통째로 남는다 —
  없던 것은 그 사실을 말하는 통지였다. restart 는 snapshot 이 없어 workspace 를 **복원하지 않는다**;
  비어 있는 게 정직한 상태이고, host 소유인 child registry 만 건너온다.
  `(rlm-children)` / `(rlm-delete-child)` 가 그 회수 경로. `list_names` frame 은 여전히 0.
- **H6 leftover (blocker 아님):** (a) busy-kernel 대화상자는 Python 어휘 그대로 (사용자 UI 문자열).
  (b) `ipython` 도구 이름·파라미터 설명은 Phase A 대로 Python 문구. (c) `ns-publics` 는 runtime
  자기 binding 까지 같이 내놓는다 — 통지가 그렇다고 말할 뿐 거르지 않는다.
- **H5 leftover (blocker 아님):** write/edit 는 symlink 거부(parent real-path). H3 `read-text`
  lexical deviation 그대로. delete/rename/mkdir 없음. diff display event 없음.
- **H4 leftover (blocker 아님):** (a) no-setsid (b) re-group 자식 (c) SIGKILLed runtime 은
  고아를 못 치운다 — restart 후 새 프로세스는 그 id 를 모른다. orphan journal 은 아직 없다.
- Do not touch: Python oracle, `ipython` 개명, `list_names` 구현, snapshot/restore, `spit`/`slurp`,
  Emmy, H7.
- Blocker: 없음. 푸시는 GLG.

# RECENT

- [2026-08-30] H3 닫힘. 푸시 `ba2bba55`.
- [2026-08-30] H4 `4c42dbb4` → `9229aa77`. 푸시 `a114668f`.
- [2026-08-30] H5 `2e5753a2`. GLM PASS. H5 close 권고.
- [2026-08-30] H6 `2ea1b170`. 새 Opus 구현. 측정된 defect: `(rlm …)` 는 `:rlm-child-id`,
  raw `host-request` 는 `:rlm_child_id` — 회수한 handle 만 어긋났다. 키 정규화로 닫힘.
