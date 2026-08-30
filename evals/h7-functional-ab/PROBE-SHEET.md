# H7 probe sheet — functional A/B (DeepSeek)

Question (GLG): does the Clojure surface get eaten, and does the model actually
do RLM — persistent REPL, read / write / process / (rlm …) — or does it just chat?

Two arms. Identical prompt text. Only `PRIME_AGENT_KERNEL_RUNTIME` differs
(`python` | `clojure`). Same model, same budget, same cwd, same probe order.
Prompts name the TASK, never the syntax — each arm's own runtime prompt is what
teaches it the language.

Model: `deepseek/deepseek-v4-flash` (reasoning visible; $0.14/$0.28 per M).
Driver: `pi --mode json -p` → full event stream, thinking blocks included.

## Probes

| id | probe | surface under test | why it cannot be chatted |
|---|---|---|---|
| P1 | world | workspace introspection | the answer is a list only the runtime holds |
| P2 | document as a value | H3 `read-text` + compute | file is 300+ lines; answer is a computed subset |
| P3 | write then verify by command | H5 write + H4 process | two independent measurements must be compared in the workspace |
| P4 | child, then recover it | H6 registry verb | the handle var is deliberately forbidden in step 2 |

## Verdict criteria — "did RLM", checked from the JSON stream, not from impression

Per probe, per arm:

1. **used the REPL** — ≥1 `ipython` tool call. A text-only answer fails.
2. **state crossed cells** — a name bound in one cell is used in a later cell.
3. **answer came from the workspace** — the reported fact appears in a tool
   result before it appears in the assistant text. Prose-first fails.
4. **no fallback (clojure arm)** — 0 cells containing Python syntax
   (`print(`, `import `, `await `, `def `, f-string), 0 successful `slurp`/`spit`/interop.
5. **harness clean** — 0 tool crashes, 0 kernel errors the model could not read.

Failure labels stay H2's: `semantics-gap` / `model-fumble` / `harness-gap`.
A Clojure arm that fails because the model wrote bad Clojure is `model-fumble`,
not a runtime defect. Only `harness-gap` and `semantics-gap` count against the surface.

## What this is NOT

No score-vs-Python claim. No benchmark. No verifiers env, no GPU, no `prime login`.
Two arms of the same small probes, and a record of what each one actually did.
