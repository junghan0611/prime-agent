# feat/clojure-runtime — 8홉 레일 닫힘

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H8은 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = 레일 끝. Emmy는 별 레일. 9번째 홉 없음.

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

현재 좌표: **H0–H8 checkpoint. 이 레일 끝.**

# NOW — 레일 닫힘, 푸시는 GLG

- Stem: H8 `edc3a3e8` (ahead 2, 푸시 안 함).
- Next: GLG 푸시. **9번째 홉 없음.** Emmy는 별건.
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
- **누적 leftover (전부 blocker 아님):** H4 (a)no-setsid (b)re-group (c)SIGKILL orphan ·
  H5 symlink 거부/diff event 없음/delete·rename·mkdir 없음 · H6 busy-kernel 대화상자 Python 어휘 ·
  H7 `process-tail` 문구·`Integer/parseInt` 문구·**fan-in harness-gap(clojure arm 은 child 가 답을 못 보낸다)** ·
  spawn handle `name` vs registry `session_name` 불일치(양쪽 runtime).
- Do not touch: Python oracle 삭제, `ipython` 개명, `list_names`, snapshot/restore, `spit`/`slurp`, Emmy.
- Blocker: 없음. 푸시는 GLG.

# RECENT

- [2026-08-30] H3 닫힘. 푸시 `ba2bba55`.
- [2026-08-30] H4 `4c42dbb4` → `9229aa77`. 푸시 `a114668f`.
- [2026-08-30] H5 `2e5753a2`. GLM PASS. H5 close 권고.
- [2026-08-30] H6 `2ea1b170`. GLM PASS, 코디가 닫음. 측정된 defect: `(rlm …)` 는 `:rlm-child-id`,
  raw `host-request` 는 `:rlm_child_id` — 회수한 handle 만 어긋났다. 키 정규화로 닫힘.
- [2026-08-30] H8 `edc3a3e8`. default = clojure. flip 델타 +49 → 수선 후 0. 레일 8홉 끝.
- [2026-08-30] H7 `b5e9e424`. GLG 가 논문 eval 대신 **기능 A/B** 로 돌림. DeepSeek 8런 $0.00576.
  P4 에서 `(rlm-children)` 이 live 로 동작 — H6 이 만든 dashed key 가 트레이스에 보인다.
