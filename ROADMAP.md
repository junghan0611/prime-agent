# ROADMAP — Clojure/SCI를 Prime Agent 실사용 대체로

최종 1번 목표: Clojure/SCI native runtime을 Python kernel의 **실사용 대체물**로 쓴다. 논문/프로젝트가 주장한 평가를 **같은 evaluator · model · budget**에서 실행 가능하게 만든다.

이 파일은 제품 8홉. `feat/clojure-runtime`은 **H1–H2만** (1차 boot sector). 그 브랜치 닫힘 ≠ 최종 대체.

출처: sol `20260830T103427-f6f942`. README 배지 [Verifiers](https://github.com/PrimeIntellect-ai/verifiers), arXiv [2608.23552](https://arxiv.org/abs/2608.23552). 이 repo에 논문 eval config는 없다 — pin/acquisition은 H7 산출물.

각 홉 검수: Opus 구현 ~30분 + GLM/Grok focused tests + GLG 검수 1회. **H4와 H5를 합치지 않는다.**

| 홉 | 무엇 | 어디서 |
|---|---|---|
| H1 | host 선택 · spawn · prompt · state-op off (`list_names` skip) | `feat/clojure-runtime` CURRENT |
| H2 | RLM child/registry/result fan-in + DeepSeek 4실험 | 같은 브랜치 닫힘 = 1차 boot |
| H3 | bounded read / context | 다음 브랜치 |
| H4 | process lifecycle | 다음. H5와 별도 |
| H5 | edit / write receipts | 다음. H4와 별도 |
| H6 | compaction / restart continuity | 다음 |
| H7 | official verifier config pin + A/B 1회 완주 | 다음. **evaluation-ready** (성능 주장 금지). 첫 pilot = 논문 §3 RQ2 long-context → PMPP-Hard. 0 harness/intervention/Python-fallback |
| H8 | default switch + soak | 다음. **performance-accepted**. 제안 rollback: Clojure median ≥ Python 90% (실측 아님, GLG 승인 전) + PMPP 비열화 + soak harness failure 0 |

## 이 브랜치 이후 — 공존언어

**Emmy / SICM probe.** form → symbolic → numeric/render. H8 뒤에 연다. REPL 언어축과 독립.

## 더 뒤

Emacs 공존면, steering form, retract/install grammar, GLG 아이디어 섞기. H8 전에도 후에도 “평가가 안 돌아가는데 아이디어부터”는 하지 않는다.
