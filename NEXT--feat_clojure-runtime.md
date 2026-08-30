# feat/clojure-runtime — 제품 8홉 중 H1–H2 (1차 boot)

최종 1번은 이 브랜치가 아니다. Clojure/SCI를 Prime Agent **실사용 대체**로 쓰는 것 — 8홉 전체는 `ROADMAP.md`.
이 파일은 **H1–H2만**. 여기 닫힘 = boot sector. 대체 완료가 아니다.

계약: `docs/clojure-runtime.md`.

# COMPASS — Entwurf #88

1. 목표는 CPython 복제가 아니다. Lisp workspace를 써서 원저자 RLM 루프가 서는지를 본다.
2. 이 브랜치는 REPL 언어축의 **host 접속 + 4실험**만. steering / Emacs / Emmy / 실사용 default는 ROADMAP.
3. Entwurf는 transport, local REPL은 computation이다. 섞지 않는다.
4. 자연어는 탐색과 해석, Lisp는 공개 상태·계약·계산 가능한 form이다.
5. 새 capability를 편의로 열지 않는다. H1–H2는 있는 `rlm` / `host-request`로 평가한다. 읽기·process·write는 H3–H5.
6. 이 브랜치 닫힘: **DeepSeek가 Clojure form으로 원저자 실험 네 개 + fan-in 한 줄을 조직하고 라벨이 남는가.** 최종 대체(H8)가 아니다.

# RAIL — 현재 좌표

작업량은 구현 LOC + 테스트 LOC. 견적: sol `20260830T103427-f6f942`.

제품 8홉 중 이 브랜치에 들어오는 것만 체크한다. H3–H8은 ROADMAP.

- [x] **runtime 1–3b** — native-image + SCI. src 284 + test 629. 32/158. GitHub 린트만.
- [x] **H1 host 선택면** — spawn + Clojure bootstrap/prompt + `list_names` skip. 구현 299 + 테스트 248. GLM 검수 통과.
- [ ] **H2 fan-in + DeepSeek 4실험** ← CURRENT: 이 브랜치 닫힘 (제품 1차 boot). 코드량 없음. 메터드 API.

현재 좌표: H1 완료 → H2 진행 → H3+는 ROADMAP

# NOW — H2

- Stem: H1이 호스트에 native SCI를 붙였다. 기본 runtime은 python. Clojure는 `PRIME_AGENT_KERNEL_RUNTIME=clojure`.
- Next: DeepSeek로 아래 네 개 + fan-in 한 줄을 Clojure form으로 친다. 새 capability 없음. 읽기 cap 부족은 한 줄 적고 H3로.
- Verify: 라벨 `semantics-gap` / `model-fumble` / `harness-gap`. 영수증에 pin(model)·form·결과. Python fixture 공유 금지.
- Read: issue #1 · 이 문서 H2 절 · `docs/clojure-runtime.md` · `packages/coding-agent/docs/rlm.md`
- Do not touch: H1 코드 재개조, `list_names` 구현, H3–H8, Emmy, commit/push (GLG만)
- Blocker: 없음. DeepSeek는 메터드 — 키는 env, 메시지에 넣지 않음.

# H2 — 이 브랜치 닫힘 실험

README / `rlm.md` 네 개. DeepSeek · Clojure form. Python fixture 공유 금지.

1. **world** — public bindings와 child registry를 Lisp 값으로
2. **prompt-as-variable** — 문서를 값으로 붙잡고 heading만 계산
3. **`rlm`은 답이 아님** — child 둘, **admission handle**만
4. **harness 경계** — 파일과 줄을 값에 바인딩

**fan-in (`rlm.md:53-72`):** child 최종 답은 `rlm` 반환값이 아니다. `agent_message` 또는 파일. 이 slice에 입구가 없으면 **`harness-gap`으로 기록하고 cap을 열지 않는다.** README:47 “returns their results programmatically”와 정밀 계약의 충돌을 영수증에 남긴다.

라벨: `semantics-gap` / `model-fumble` / `harness-gap`.
2번이 읽기 없이 부족하면 한 줄 적고 닫는다 — H3로 보낸다.

# RECENT

- [2026-08-29] 포크 + native SCI. 32/158.
- [2026-08-30] 문서 한 장. JVM SUT·GH GraalVM 삭제. `56631f36`
- [2026-08-30] sol: 견적 220–300/180–250, `list_names` skip, fan-in 한 줄.
- [2026-08-30] GLG 교정: 이 브랜치 닫힘 = 1차 boot (H1–H2). 최종 1번 = 실사용 대체 (ROADMAP H1–H8). 논문 첫 pilot은 H7, arXiv 2608.23552 §3 RQ2.
- [2026-08-30] H1 착지. GLM 검수 green. fragment `h1-clojure-kernel-runtime.md`.
