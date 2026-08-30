# H7 — functional A/B, both arms

Run 2026-08-30 KST. Stem `af2b0c54`. Not a benchmark and not a score claim.

Question (GLG): does the Clojure surface get eaten, and does the model actually do
RLM — persistent REPL, read / write / process / `(rlm …)` — or does it just chat?

| | |
|---|---|
| model | `deepseek/deepseek-v4-flash` (reasoning visible) |
| driver | this repo's print mode, `--mode json --no-session`, dedicated daemon socket per arm |
| arms | `PRIME_AGENT_KERNEL_RUNTIME=python` vs `=clojure`. Nothing else differs |
| prompts | identical text, and they name the task only — never the syntax |
| probes | 4, one per surface (see `probes/`) |
| reproduce | `./run.sh <outdir>` then `python3 analyze.py <outdir>` |

Raw JSONL is 16 MB and stays host-local. Every decisive line is inlined below.

## Mechanical result

`analyze.py` output, both arms:

```
probe                arm      cells cellErr pyLeak carried      in    out   cache    cost$
p1-world             python       2       0      0       0    5482   1345   11520  0.00118
p2-document-as-value python       3       0      0       1    1982    473   19712  0.00047
p3-write-then-verify python       4       1      0       3     490   1684   28032  0.00062
p4-child-registry    python       3       1      0       1    1098   1431   28288  0.00063
p1-world             clojure      1       0      0       0    4344    446    4224  0.00074
p2-document-as-value clojure      3       0      0       1    1936    711   17536  0.00052
p3-write-then-verify clojure      5       2      0       7    1921   2984   38784  0.00121
p4-child-registry    clojure      2       0      0       0     408   1010   18304  0.00039
TOTAL                                                                              0.00576
```

Per arm: python 9,052 in / 4,933 out / 87,552 cacheRead / $0.00289 —
clojure 8,609 in / 5,151 out / 78,848 cacheRead / $0.00287. Same budget held.

Against the five criteria:

1. **used the REPL** — 8/8 runs. Zero text-only answers on either arm.
2. **state crossed cells** — every multi-cell probe carried bindings forward.
   Clojure p3 carried 7.
3. **answer came from the workspace** — checked per probe below. Held in all 8,
   including the one place it looked like an overclaim (p4 clojure).
4. **no Python fallback on the clojure arm** — 0 cells with Python syntax,
   0 `slurp`/`spit`/interop successes. `cellErr` on the clojure arm is Clojure
   erroring as Clojure, never a language slip.
5. **harness clean** — 0 tool crashes. Cell errors 2 clojure / 2 python: the
   Clojure arm was not the error-prone one.

## What each probe actually did

### p1 world — clojure, 1 cell, 0 errors

```clojure
(keys (ns-publics 'user))
;; => (read-text rlm write-text process-tail process-list rlm-delete-child _
;;     edit-text host-request process-start process-kill process-poll
;;     prime-agent-runtime rlm-children)
```

The model then said, unprompted, that two of those names "were not in my
instructions" (`_` and `prime-agent-runtime`). It interrogated the runtime rather
than reciting the prompt — which is the whole point of the probe. The H6 verbs
`rlm-children` and `rlm-delete-child` are visible in the live workspace.

Python took 2 cells and an `importlib` scan to answer the same question.

### p2 document as a value — clojure, 3 cells, 0 errors

```clojure
(def md (read-text "docs/clojure-runtime.md"))
(def lines (clojure.string/split-lines md))
(count lines)                                        ;; => 375
;; cell 2 reuses `lines`
(def idx (keep-indexed (fn [i l] (when (clojure.string/includes?
           (clojure.string/lower-case l) "lifecycle") [i l])) lines))
;; cell 3 reuses `lines` again
(clojure.string/join "\n" (subvec lines 157 (min 210 (count lines))))
```

375 lines, correct. Five form names extracted, correct. The file never entered
the answer. This is §3.2's "programmatic information management" behavior
happening on the Clojure surface, and it is what H3 `read-text` was built for.
Python did the same in 3 cells.

### p3 write then verify — clojure, 5 cells, 2 errors, both self-recovered

Cell 1 wrote the file, read it back, counted in the workspace, and started the
shell command — all in one form:

```clojure
(write-text "probe3.txt" "line one\nline two\n…")
(def probe-content (read-text "probe3.txt"))
(def count-workspace (count (re-seq #"\n" probe-content)))
(def wc-proc (process-start "wc -l probe3.txt"))
;; => {:count-workspace 5, :wc-proc {… :status :running :pid 1854911 …}}
```

Cell 5 closed it: `{:count-workspace 5, :count-shell 5, :counts-agree true}`.

Cells 2 and 4 failed first. Both failures are recorded as findings below. The
model diagnosed cell 2 by asking the runtime `(type tail-val)` — again
interrogation, not guessing — and recovered without help and without Python.

Python also errored once here (`TypeError`).

### p4 child, then recover it from the registry — clojure, 2 cells, 0 errors

```clojure
(def spawned (rlm "Reply with the single word ok" {:name "ok-replier"}))
;; next cell, without touching `spawned`:
(rlm-children)
;; => [{:rlm-child-id "sub-58ddab55", :active-session-id "bf821fa596e4",
;;      :session-id "01a0514f-…", :session-name "ok-replier",
;;      :session-dir "/tmp/prime-agent-rlm-GnK6Aw/sub-58ddab55",
;;      :status "completed"}]
```

H6's verb worked live, and the dashed key shape it normalizes is visible in the
trace. Python needed 3 cells and hit an `AttributeError` (finding 4).

## Findings

**1. `harness-gap` (prompt) — `process-tail` return shape.** The prompt says it
"returns the last 50 captured lines", which reads as a collection. It returns a
String. The model wrote `(first tail-val)`, got
`java.lang.Character cannot be cast to java.lang.CharSequence`, and spent 2 of its
5 cells recovering. One sentence in the prompt fixes it.

**2. `semantics-gap` — documented boundary, working as designed.**
`(Integer/parseInt …)` raises `No matching method parseInt found taking 1 args`:
reflective static interop is closed in the native image, by contract, because
there is no reflect-config. The model recovered on its own with
`clojure.edn/read-string`. Not a defect. Naming it in the prompt would save a cell.

**3. `harness-gap` — fan-in on the Clojure arm still has no capability.** This is
H2's recorded gap, now with a trace. `agent_message` is not installed for the
Clojure runtime, so the child cannot reply. The parent learned the answer anyway,
but only because the host's terminal notice happens to carry it:

```
custom rlm_child_terminal_notice:
  RLM child ok-replier (sub-58ddab55) completed without sending a reply.
  Last assistant text: ok
```

The Python arm received a real `agent_message` from its child. So fan-in on the
Clojure arm currently rides on a notice, not on a capability. The model's claim
about the child's answer was therefore sourced, not invented — criterion 3 holds —
but the mechanism is accidental and should not be mistaken for fan-in.

**4. Both runtimes — spawn handle and registry entry do not share a field set.**
The Python arm crashed on exactly this:

```
AttributeError: 'RLMSubagent' object has no attribute 'name'
```

The spawn handle carries `name`; the registry entry carries `session_name`. H6
unified key *spelling* on the Clojure side (`_` → `-`), not the field set, and the
field set still differs in both runtimes. The Clojure arm avoided the crash only
because it printed the whole map instead of reaching for a field. Recording this,
not claiming it: H6 did not fix it and neither arm is better here.

## What this is not

No score comparison. No benchmark. No verifiers environment, no GPU, no
`prime login`, no paid eval beyond $0.00576 of DeepSeek tokens.
