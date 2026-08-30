# ROADMAP — Clojure/SCI를 Prime Agent 실사용 대체로

최종 1번 목표: Clojure/SCI native runtime을 Python kernel의 **실사용 대체물**로 쓴다. 논문/프로젝트가 주장한 평가를 **같은 evaluator · model · budget**에서 실행 가능하게 만든다.

이 파일은 제품 8홉. 전부 `feat/clojure-runtime`에서 이어서 간다. 새 브랜치로 자르지 않는다. H1–H7은 **rollback checkpoint**이지 브랜치 닫힘이 아니다. CURRENT = H8 대기 (시작 아님).

출처: sol `20260830T103427-f6f942`. README 배지 [Verifiers](https://github.com/PrimeIntellect-ai/verifiers), arXiv [2608.23552](https://arxiv.org/abs/2608.23552). 이 repo에 논문 eval config는 없다 — pin/acquisition은 H7 산출물.

각 홉 검수: Opus 구현 ~30분 + GLM/Grok focused tests + GLG 검수 1회. **H4와 H5를 합치지 않는다.**

| 홉 | 무엇 | 어디서 |
|---|---|---|
| H1 | host 선택 · spawn · prompt · state-op off (`list_names` skip) | checkpoint `7d509e75` |
| H2 | RLM child/registry/fan-in + DeepSeek 4실험 | checkpoint `9d8f69f5`. s6. leftover는 H3 입구였음 |
| H3 | key-shape 계약 → bounded read / context | checkpoint `f0b5183e` → `10fde370` → `13e88738`. symlink known deviation (blocker 아님) |
| H4 | process lifecycle | checkpoint `4c42dbb4` → `9229aa77`. SCI에 process 객체 금지. setsid group. leftover: no-setsid / re-group / SIGKILL |
| H5 | edit / write receipts | checkpoint `2e5753a2`. `spit` 닫힘. write는 symlink 거부. H3 read 편차는 그대로 |
| H6 | compaction / restart continuity | checkpoint `2ea1b170`. `list_names` frame 0, snapshot 없음. registry 회수 verb + runtime별 통지. restart 는 복원이 아니라 정직한 빈 workspace |
| H7 | 기능 A/B (DeepSeek thinking) | checkpoint `b5e9e424`. 8/8 REPL. clojure Python 유출 0. fan-in은 여전히 harness-gap |
| H8 | default switch + soak | 다음. **performance-accepted**. 제안 rollback: Clojure median ≥ Python 90% (실측 아님, GLG 승인 전) + PMPP 비열화 + soak harness failure 0 |

## 이 브랜치 이후 — 공존언어

**Emmy / SICM probe.** form → symbolic → numeric/render. H8 뒤에 연다. REPL 언어축과 독립.

## 더 뒤

Emacs 공존면, steering form, retract/install grammar, GLG 아이디어 섞기. H8 전에도 후에도 “평가가 안 돌아가는데 아이디어부터”는 하지 않는다.
