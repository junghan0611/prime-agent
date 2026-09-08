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

Corrected after cross-review (`xai/grok-4.6`). Two FAILs in the first published
table were **my analyzer's bug, not the model's**: it scored the *last* assistant
text block of a turn, and in both cells the graded answer was in the first block
with the closing pleasantry after it. Both are now PASS.

`✓` PASS · `✗` FAIL · `∅` unresolved in this run (see the formal-receive section
below) · `—` never reached.

| cell | Q-R0 / T-1.1 | Q-R1+R2 / T-1.2 | Q-R3+R4 / T-1.3 | T-1.4 | api req |
|---|---|---|---|---|---|
| pa-clojure-r1 | ✓ | ✓ ✓ | ∅ ✓ | | 16 |
| pa-clojure-r2 | ✓ | ✓ ✓ | ∅ ✓ | | 18 |
| pa-python-r1 | ✓ | ✓ ✓ | ∅ ✓ | | 15 |
| pa-python-r2 | ✓ | ✓ ✓ | ∅ — | | 8 |
| pb-clojure-r1 | ✓ | ∅ | ✓ | ✓ | 41 |
| pb-clojure-r2 | ✓ | — | — | — | 21 |
| pb-python-r1 | ✓ | ∅ | ✓ | ✓ | 31 |
| pb-python-r2 | ✓ | ∅ | ✓ | ✓ | 39 |

Every formal-receive cell — Q-R3 on both arms, T-1.2 on both arms — is `∅`, so
the table says what the prose says. The first table scored those cells green on
the Clojure arm and red on the Python arm while the prose called the comparison
unresolved; one column cannot say two things.

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

And it is not only volume. In the same `rlm.ts` subagent block the Python branch
teaches, unconditionally, **"Have children write files and read those files for
fan-in."** The Clojure branch teaches `agent_message.send` in that slot instead.
So the two arms were given **different fan-in contracts**, and every Python cell
did exactly what its own prompt taught. Behaviour split along that line — all four Clojure cells used
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
called out of `main.ts::createDaemonClientConnection` on reconnect. **The "so the
daemon returned a non-string `error` field" inference is withdrawn** —
`DaemonResponse.error` is typed `string` in `daemon-protocol.ts`, so that message
is as likely to be the error string the daemon handed back. What holds is the
part that matters: zero API requests on those turns, so nothing model-side, and
the cell simply has no T-1.2–T-1.4 data. Whether turn 1's long 21-round-trip turn
left the state that broke reconnect is a lead, not a finding.

**`model-fumble` — one cell, not three.** The first version of this section named
three, and cross-review retired two of them against the raw turns.
`pa-python-r2` is the real one: it stopped at "I will now wait for the child
agent" and never returned, so Q-R4 was never reached. `pa-python-r1` did report
its Q-R4 value (`{'exit_code': 0, 'output': 'Python version: 3.13.14', …}`) in the
same turn, and `pb-clojure-r1` did give the T-1.4 truthful report — no answer
received, `(rlm-children)` status, and the binding list — before its closing
line. Both were scored off a trailing pleasantry by an analyzer that read only
the last text block.

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
