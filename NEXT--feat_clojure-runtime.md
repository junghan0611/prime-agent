# feat/clojure-runtime — H4 checkpoint, H5 대기

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H4는 **rollback checkpoint**. 새 브랜치 없음.
CURRENT = H5 대기 (시작 아님). 시작 때 CURRENT만 H5로 옮긴다.

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
- [x] **H3 bounded read / context** — `f0b5183e` → `10fde370` → `13e88738`. keywordize + `(read-text)`. 34/173.
- [x] **H4 process lifecycle** — `4c42dbb4` → `9229aa77`. id registry + setsid group. native 48/313. GLM `abff01` 2차 PASS.
- [ ] **H5–H8** — ROADMAP. H5는 write/edit receipts. H4와 합치지 않음.

현재 좌표: H0–H4 checkpoint → H5는 edit/write receipts (시작 아님)

# NOW — H4 닫힘, 푸시·H5는 GLG

- Stem: H4 `4c42dbb4` + `9229aa77`. ahead 2. native 48/313 (GLM 3연속 재실측).
- Next: GLG 푸시. H5는 시작하지 않음.
- **H4 leftover (blocker 아님):** (a) setsid 없는 host (`:contained false`, `cmd & exit 0` 탈출) (b) 스스로 re-group 하는 자식 (c) SIGKILLed runtime. orphan journal은 H6/H7 전 안건.
- Known deviation (H3, blocker 아님): `(read-text)` lexical Path.startsWith. symlink 경유 시 workspace 밖 가능. H4는 OS-permission trust — 보안 경계로 승격하지 않음.
- Do not touch until H5 opens: Python oracle, `ipython` 개명, `list_names`, spit/write receipts, Emmy, H4+H5 합치기.
- Blocker: 없음. 푸시는 GLG.

# RECENT

- [2026-08-30] H1 `7d509e75`. GLM green.
- [2026-08-30] H2 evalue/:type `9d8f69f5`. s6 world·fan-in 성공.
- [2026-08-30] H3 닫힘 `f0b5183e` → `10fde370` → `13e88738`. 푸시 `ba2bba55`.
- [2026-08-30] 코디 회전: grok `20260830T132225-cd2dd4`. H4 Opus `110899` + GLM `abff01`.
- [2026-08-30] H4 `4c42dbb4` registry. GLM 1차: leader-exit orphan. GLG (b) setsid.
- [2026-08-30] H4 (b) `9229aa77`. GLM 2차 48/313 PASS. H4 close 권고.
