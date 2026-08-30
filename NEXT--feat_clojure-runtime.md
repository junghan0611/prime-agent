# feat/clojure-runtime — H3 (같은 브랜치)

최종 1번은 실사용 대체 (`ROADMAP.md` H8). 이 브랜치를 H1–H2에서 닫지 않는다. H1–H2는 **rollback checkpoint**.
지금 CURRENT = **H3**. 순서: key-shape 계약 → bounded read/context.

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
- [ ] **H3 bounded read / context** ← CURRENT: (1) host-request reply key 규약 고정 (2) 그다음 read cap.
- [ ] **H4–H8** — ROADMAP. 이 브랜치에서 이어서 할 수 있음. H4≠H5.

현재 좌표: H0–H2 checkpoint → H3 진행

# NOW — H3

- Stem: boot는 섰다. 다음이 읽기. 그 전에 reply key 규약이 먼저다.
- Next: key-shape는 **recursive keywordize at SCI boundary**로 고정 (`walk/keywordize-keys`). 와이어 JSON은 string. nested registry fixture 통과 (33/162). 다음은 session/project root 아래 **읽기만**. write 아님.
- Verify: key-shape 테스트 + read positive/negative 짝 (쓰기·interop·raw output 닫힘).
- Read: issue #1 · `docs/clojure-runtime.md` · H2 leftover (s6)
- Do not touch: Python oracle, `ipython` 개명, `list_names` 구현, write/process, Emmy, H4+H5 합치기
- Blocker: 없음. 푸시는 GLG.

# RECENT

- [2026-08-30] H1 `7d509e75`. GLM green.
- [2026-08-30] H2 evalue/:type `9d8f69f5`. s6 world·fan-in 성공.
- [2026-08-30] 도장 `52a5f9db`는 checkpoint 기록. GLG: 같은 브랜치에서 H3 계속. 새 브랜치 없음.
