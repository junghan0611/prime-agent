# feat/clojure-runtime — H3 checkpoint, H4 대기

최종 1번은 실사용 대체 (`ROADMAP.md` H8). H1–H3는 **rollback checkpoint**. 새 브랜치 없음.
H4는 시작하지 않음. 시작 때 CURRENT만 H4로 옮긴다.

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
- [x] **H3 bounded read / context** — `f0b5183e` → `10fde370` → `13e88738`. keywordize + `(read-text)`. 34/173. GLM 닫힘 동의.
- [ ] **H4–H8** — ROADMAP. 이 브랜치에서 이어서 할 수 있음. H4≠H5.

현재 좌표: H0–H3 checkpoint → H4는 process lifecycle (H5와 합치지 않음)

# NOW — H4 대기 (시작 아님)

- Stem: H3 checkpoint. native 34/173. host 10/10.
- Next: GLG가 오면 H4. **시작 전에 게이트:** process object를 SCI에 노출하지 않음. runtime registry id→immutable handle map. start/poll/tail/kill. bounded captured output. protocol stdout 불오염. shutdown/EOF cleanup. descendant/process-group 정리. H5 write와 분리.
- Known deviation (H3, blocker 아님): `(read-text)`는 lexical Path.startsWith. workspace 안 symlink로 루트 밖 가능. 프롬프트 "stay under workspace"는 그 경우 거짓. H4 process가 열리면 OS-permission trust — 보안 경계로 승격하지 않음. sol `f6f942`.
- Do not touch: Python oracle, `ipython` 개명, `list_names` 구현, spit/write, Emmy, H4+H5 합치기
- Blocker: 없음. 푸시는 GLG.

# RECENT

- [2026-08-30] H1 `7d509e75`. GLM green.
- [2026-08-30] H2 evalue/:type `9d8f69f5`. s6 world·fan-in 성공.
- [2026-08-30] 도장 `52a5f9db`는 checkpoint 기록. GLG: 같은 브랜치에서 H3 계속. 새 브랜치 없음.
