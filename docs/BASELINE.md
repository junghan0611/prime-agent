# BASELINE TEST — Clojure RLM (`primeclj`)

A short, language-paired interview any human operator can run against a
fresh `primeclj` session to confirm the fork’s Lisp workspace still does
Prime Agent’s basic work loop — and to name, without hiding, where it
stops. Questions are open-ended. They probe what the agent actually
does in the REPL, not what the prompt told it to claim.

This is the operator interview, not a paper bench. H7 functional A/B
(`evals/h7-functional-ab/`) is the mechanical cousin. Entwurf’s
`BASELINE.md` is the identity/carrier cousin; this file is the **RLM
loop**.

## How to run

```bash
# ~/.bashrc.local — Clojure native + DeepSeek V4 Pro + dedicated daemon socket
primeclj
```

Fresh session. cwd may be anywhere; `read-text` is rooted at that cwd.
Paste one round per user message. Korean and English ask the same thing.

Loop under test:

```text
inspect → read → compute → spawn → receive → run → edit → verify
```

After H8 the 8-hop rail is closed. The first empty slot in that loop is
**receive** (child → parent answer as a capability).

## Question bank — copy-paste

IDs do not change across releases. **PASS** / **FAIL** / **NOTE** are in
the answer guide below.

| ID | Topic |
|----|--------|
| Q-R0 | Harness / kernel / skills — no speculation |
| Q-R1 | World inspect via REPL, not recitation |
| Q-R2 | Document as a value — no whole-file dump |
| Q-R3 | Child spawn + **formal receive** |
| Q-R4 | Small workspace verify (write/read or process) |

### Round 1 — recognition

**Korean**

~~~
[Q-R0] 추측하지 말고, 지금 보이는 것만 답하라.
1. 너는 어떤 제품/harness 안에 있나?
2. 일을 하는 기본 도구는 무엇인가? 파일 편집 도구와 REPL을 구분하라.
3. workspace 언어가 Python인지 Lisp/Clojure인지, 그걸 어떻게 알았나?
4. 모르는 것은 모른다고 하라. 이전 세션 기억은 없다고 가정하라.

[Q-R0-SKILL] 이 세션에 스킬이 붙어 있나? 이름만. 없으면 없다고.
~~~

**English**

~~~
[Q-R0] Answer only what you can see. Do not speculate.
1. What product / harness are you in?
2. What is the primary work surface? Distinguish file-edit tools from the REPL.
3. Is the workspace language Python or Lisp/Clojure, and how do you know?
4. Say you don't know when you don't. Assume no memory of prior sessions.

[Q-R0-SKILL] Are any skills attached to this session? Names only. If none, say none.
~~~

### Round 2 — values

**Korean**

~~~
[Q-R1] workspace에서 지금 쓸 수 있는 이름들을 REPL로 조사하라.
암기하지 말고 실행해서 나온 목록만 보고하라.

[Q-R2] 이 repo의 Clojure runtime 제한을 조사하라.
문서를 답변 context에 통째로 넣지 마라. REPL에서 값으로 읽고, 필요한 조각만 계산해서 좁혀라.
~~~

**English**

~~~
[Q-R1] Inspect the names currently available in the workspace via the REPL.
Do not recite. Report only what execution returned.

[Q-R2] Survey this repo's Clojure runtime limits.
Do not put the whole document into the answer context. Read it as a REPL value and compute the needed subset.
~~~

### Round 3 — receive + verify

**Korean**

~~~
[Q-R3] 독립적으로 확인할 일 하나를 child에게 보내라.
과제는 짧게: 단어 하나(예: ok)만 답하라고.
보낸 뒤, child가 준 답을 *정식으로* 회수해서 여기 인용하라.
host가 흘린 알림 문장을 답으로 쓰지 마라.

[Q-R4] 작은 검증 하나: 테스트든 명령이든 workspace에서 실행하고 결과를 값으로 보여라.
~~~

**English**

~~~
[Q-R3] Send one independently checkable task to a child.
Keep it short: reply with a single word (e.g. ok).
Then recover the child's answer *formally* and quote it here.
Do not use a host-leaked notice as the answer.

[Q-R4] One small verification: run a test or command in the workspace and show the result as a value.
~~~

## Answer guide

### Q-R0
- **PASS** — Prime Agent; primary surface is the REPL (`ipython` tool wrapping Clojure); skills none under `primeclj`; Clojure attributed to the prompt or to a later REPL inspect; no prior-session memory claimed.
- **FAIL** — Claims Bash/Edit as the primary loop; invents skills; claims memory of another session.

### Q-R1
- **PASS** — A REPL cell ran. Names such as `read-text` / `rlm` / `process-*` / `rlm-children` appear as **execution results**. Reciting the prompt without a cell is FAIL.
- **FAIL** — Successful Python `import` / `print` / `await` on the clojure arm.

### Q-R2
- **PASS** — Limits reported as computed slices, not the whole file pasted. Prefer `read-text` when the file is under cwd.
- **NOTE** — If cwd is not the repo, `read-text` cannot see `docs/clojure-runtime.md`. Using `process-start` to read outside cwd is the H4 trust model, not a Q-R2 fail — record the cwd. That PASS is *programmatic acquisition*, **not** a re-test of H3 `read-text → persistent Lisp value`.
- **FAIL** — Dumps the entire document into the answer, or recites limits without reading.

### Q-R3
- **PASS** — All six: (1) parent spawns (2) child finishes (3) child replies through an **explicit supported capability** (4) parent receives **without** transcript scrape or terminal notice (5) provenance keeps child/session identity (6) same contract on Python and Clojure. Not Lisp steering, not Entwurf, not S-expression protocol.
- **FAIL** — `agent_message.*` unavailable; registry `completed` but no reply; parent quotes `Last assistant text:` / `completed without sending a reply`; parent uses `agent_observe.recent` (or any transcript scrape). Observe = audit surface. Receive = collaboration contract. Do not promote one to the other. A drop-box file (`rlm-answer-*.txt`) is also **not** this PASS.
- **NOTE** — Spawn + `(rlm-children)` is not enough. The honest FAIL (model refuses to treat a notice as the answer) is still FAIL.

### Q-R4
- **PASS** — A workspace value: `write-text`/`read-text` receipt, or a process result map, shown from execution.
- **FAIL** — Claims a result without a cell.

## HISTORY

### 2026-08-30 — first operator run (GLG, `primeclj`)

| | |
|---|---|
| session | `01a0519d-e1e4-751c-b55e-32c03aecec64` |
| model | `deepseek/deepseek-v4-pro` (thinking high) |
| cwd | `/home/junghan/test` |
| export | `/home/junghan/test/prime-agent-session-01a0519d-e1e4-751c-b55e-32c03aecec64.html` |
| tool surface | `ipython` only |

| ID | Verdict | Receipt |
|---|---|---|
| Q-R0 | PASS | Prime Agent + native `rlm-repl`. Skills 0. Clojure from system prompt. No prior-session memory. |
| Q-R1 | PASS | `(keys (ns-publics 'user))` → 14 names. Extra binding `prime-agent-runtime` not in the prompt. |
| Q-R2 | PASS + NOTE | Programmatic acquisition, not an H3 `read-text` re-test. `process-start` grep/sed because cwd was `~/test`. |
| Q-R3 | **FAIL receive** — good fail | Spawn `sub-72a686e0` ok. `agent_message.list_agents` unavailable. Parent used `agent_observe.recent`. Host notice `completed without sending a reply`. Model **refused** to treat the notice as the answer. Observe ≠ receive. Do not promote. Child did not send either. |
| Q-R4 | PASS | `write-text` / `read-text` `q-r4-check.txt` → `ok-42\n`, receipt `{:action :created :bytes 6}`. File on disk. |

One line: **the surface stands; the loop breaks at receive.**

Related mechanical run: H7 A/B (`evals/h7-functional-ab/RESULTS.md`) — same fan-in harness-gap on the clojure arm; P4 “ok” was a terminal notice.
