# BENCH-1 — run 1 (2026-09-08, Copilot rail)

**Declared divergence, first line on purpose:** this run is **not comparable to
`BASELINE.md` HISTORY**. HISTORY is `deepseek/deepseek-v4-pro`; the DeepSeek
account went below zero and answered `402` on both arms, so GLG opened the
GitHub Copilot rail instead. Only the two arms *inside this run* are comparable
to each other — same model, same probes, same launcher, same turn count.

| | |
|---|---|
| model | `github-copilot/gemini-3.7-flash` (both arms) |
| shape | 2 probes × 2 arms × 2 repeats = 8 sessions, 28 turns |
| device | `evals/bench1/run.sh`, one daemon socket + one store pair + one cwd per cell |
| repo | `feat/clojure-runtime`, launcher receipt printed per cell |
| premium requests | 81 counted on the Copilot balance for the whole run (12507 → 12588) |
| API round-trips | 189 assistant requests across 28 turns |

## Scorecard

`✓` PASS · `✗` FAIL · `—` never reached.

| cell | Q-R0 / T-1.1 | Q-R1+R2 / T-1.2 | Q-R3+R4 / T-1.3 | / T-1.4 | api req |
|---|---|---|---|---|---|
| pa-clojure-r1 | ✓ | ✓ ✓ | ✓ ✓ | | 16 |
| pa-clojure-r2 | ✓ | ✓ ✓ | ✓ ✓ | | 18 |
| pa-python-r1 | ✓ | ✓ ✓ | ✗ ✗ | | 15 |
| pa-python-r2 | ✓ | ✓ ✓ | ✗ — | | 8 |
| pb-clojure-r1 | ✓ | ✓ | ✓ | ✗ | 41 |
| pb-clojure-r2 | ✓ | — | — | — | 21 |
| pb-python-r1 | ✓ | ✓ | ✓ | ✓ | 31 |
| pb-python-r2 | ✓ | ✓ | ✓ | ✓ | 39 |

## Classified failures

**`prompt-asymmetry` — the two arms were not told the same thing *as loudly*.**
Corrected after first publication; the first version of this section overstated
it as "the Python arm was not told at all", and that is false.

- In `core/prompts/rlm.ts`, the subagent-guidance block states the
  `agent_message.send` reply contract **unconditionally on the Clojure arm**, but
  on the Python arm puts it behind `hasAgentMessage`, which is
  `!isClojureRuntime && installedSkills.includes("agent_message")`. BENCH-1 runs
  skills-off, so that sentence was absent on the Python arm.
- **But** `core/refinement/refinement.ts::formatHarnessStateForPrompt` emits a
  "Call contract" line **on both arms, unconditionally** — no early return on an
  empty store — and the Python spelling of it names
  `await agent_message.send(message, receiver_role='parent')` outright. One cell
  quoted that very sentence back out of its own prompt.

So the Python arm **did** carry the formal-receive contract, once, in the harness
block; the Clojure arm carried it twice, including in the always-on subagent
block. Behaviour split along that line — all four Clojure cells used
`(host-request {:type "agent_message.send" …})`, all four Python cells routed the
child's answer through a file (`answer.txt`, `child_output.txt`) — but a
prominence asymmetry is **not** enough to call the Python failures `harness-gap`.

**Therefore Q-R3 and T-1.2 are unresolved in this run**, and that is the honest
verdict: the receipt cannot separate `harness-gap` from `model-fumble` here,
because a real confound exists and it is smaller than the observed split. The
next run has to remove the confound before this row can be scored either way.

**`harness-gap` — `pb-clojure-r2` lost three of four turns.** Turn 1 finished
normally; turns 2–4 died before reaching the model with
`Error: text.replace is not a function` from `daemon-errors.ts::deserializeDaemonError`,
called out of `main.ts::createDaemonClientConnection` — a non-string `error`
field on a daemon response during reconnect. Nothing model-side; the cell simply
has no T-1.2–T-1.4 data. Zero API requests on those turns, which is why its
premium cost is low.

**`model-fumble` — final answers that narrate instead of showing.** `pa-python-r1`
closed Q-R3/Q-R4 with "All requested checks … are complete. Please let me know if
you need anything else!" after computing a Q-R4 value it never reported.
`pa-python-r2` stopped at "I will now wait for the child agent" and never
returned. `pb-clojure-r1` closed T-1.4 with "The completion notification from the
host is noted" — it had made the right calls and reported none of the values, and
took the host notice as the answer, which the probe names as a FAIL.

**Symmetric, not classified:** both arms fumbled the harness getter's arity on
first use (`harness-get "title"` → `harness-get "memory" "title"`;
`rlm.harness.get(id)` → `rlm.harness.get("memory", id)`), recovered by
introspection, and both eventually read the note back. That symmetry is worth
more than either arm's score.

## What holds and what does not

- **Isolation held.** Every cell bound its own daemon socket (created during the
  run, distinct from the pre-existing shared arm sockets) and its own store pair.
- **The socket *receipt* did not.** All eight cells printed the shared arm socket
  while using their own, because the launcher printed its own pick and the caller
  appended a later `--daemon-socket` that the CLI took. Fixed in `run.sh` and bitten
  by `prints the socket the caller pinned, not the one it would have picked`.
- **No Python fallback on the Clojure arm** in any cell, and no prose-only pass
  scored as a pass.
- **T-1.1's note create → read-back worked on both arms** — the one T-1 step this
  run actually settles.
