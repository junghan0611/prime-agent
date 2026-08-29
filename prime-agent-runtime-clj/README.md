# prime-agent-runtime-clj

Phase A Lisp runtime. Clojure source, SCI persistent evaluator, GraalVM native-image.
Delivery artifact is `target/rlm-repl`. The process that serves the protocol is a
native executable — not a JVM.

## Build

```text
./native-image/build.sh
```

`--initialize-at-build-time` is required. Without it the image links and dies at
run with `Could not locate clojure/core__init.class`. AOT classes go to
`target/classes` (gitignored via `target/`).

## Tests — why two rails

Silent JVM fallback is how "green" later means "native is broken". The rails are named.

| alias | SUT | missing/stale binary |
|---|---|---|
| `:test-native` (also `:test`) | `target/rlm-repl` | fail immediately, tell you to run `native-image/build.sh` |
| `:test-jvm` | `clojure -M -m rlm.repl` | n/a — named as JVM on purpose |

`:test` is native. There is no env-var drop to JVM.

```text
clojure -M:test-native    # fails if target/rlm-repl is missing or older than src/**
clojure -M:test-jvm       # explicit JVM runtime, implementation means only
```

Each run prints which SUT it attached, e.g. `SUT: native /abs/target/rlm-repl (built 17:44:46)`.

## `bin/rlm` — type forms

JSONL is hidden. You type Clojure.

```text
./bin/rlm
> (def x 41)
> (inc x)
42
> (rlm "child task")
host_request id=… data=…
host_reply> {"status":"ok","rlm_child_id":"c1","name":"child","session_dir":"/tmp/x","model":"test"}
```

Needs `target/rlm-repl`. Ctrl-D or `:quit` shuts down.

## Known Phase A deviations

These are not protocol v2 parity.

1. **output batching** — `*out*`/`*err*` flush as one event at cell end. Cell-id attribution matches the oracle; mid-cell streaming does not (`eval.clj`).
2. **ename / traceback** — SCI wraps user errors; `ename` is the wrapper class and traceback still carries runtime frames. OPEN (`repl.clj`).
3. **interrupt** — request is parsed, cancellation is not guaranteed.
