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
- [ ] **H1 host 선택면** ← CURRENT: spawn + Clojure bootstrap/prompt + snapshot-off(**`list_names` 전체 skip**). **구현 220–300 + 테스트 180–250.** `ipython` 이름 유지. Python oracle 토글. `repl-manager.ts` / `ipython.ts` 전면 개조 금지.
- [ ] **H2 fan-in + DeepSeek 4실험** ← 이 브랜치 닫힘 (제품 1차 boot). 코드량 없음. 메터드 API.

현재 좌표: runtime 완료 → H1 진행 → H2가 브랜치 닫힘 → H3+는 ROADMAP

# NOW — H1

- Stem: Clojure runtime을 TypeScript host가 띄운다. 새 capability 없음.
- Next: `target/rlm-repl` spawn, Clojure bootstrap/prompt(Python 문법 잔류 금지), snapshot-off + compaction `list_names` skip, child `AgentSession`이 같은 runtime 상속. 상단 300/250 초과 = parity가 stem에 섞인 것.
- Verify: fake executable argv/ready, Clojure bootstrap·no-Python, snapshot/`list_names` 미호출, Clojure prompt, 로컬 native host-request 관통. 런타임 `clojure -M:test`. `npm run check`. GH GraalVM 없음.
- Read: `docs/clojure-runtime.md` · `packages/coding-agent/docs/rlm.md` · `packages/coding-agent/docs/rlm-runtime.md` · `ROADMAP.md`
- Do not touch: Python oracle 삭제, `ipython` 개명, `list_names`/snapshot/restore **구현**, bash/skills/MCP, interrupt 보장, persist CLI 선택면, Python API/await/future 주입, H3 읽기 cap, H4 process, H5 write, Emmy, GH native-image
- Blocker: 없음.

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
