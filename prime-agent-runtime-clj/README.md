# prime-agent-runtime-clj

Phase A Lisp runtime. Clojure source, SCI persistent evaluator, GraalVM native-image.
Delivery artifact is `target/rlm-repl`. The process that serves the protocol is a
native executable — not a JVM.

Contract: `docs/clojure-runtime.md`. Coordinate: `NEXT--feat_clojure-runtime.md`.

## Build

```text
./native-image/build.sh
```

`--initialize-at-build-time` is required. Without it the image links and dies at
run with `Could not locate clojure/core__init.class`. AOT classes go to
`target/classes` (gitignored via `target/`).

## Tests

SUT is `target/rlm-repl`. The test runner is Clojure CLI; it spawns that binary.
Missing or stale binary fails immediately — there is no JVM protocol runtime to fall back to.

```text
clojure -M:test           # same as :test-native
clojure -M:test-native    # fails if target/rlm-repl is missing or older than src/**
```

Each run prints the SUT, e.g. `SUT: native /abs/target/rlm-repl (built 17:44:46)`.

Receipt is local: `build.sh`, then `clojure -M:test`, then `ldd target/rlm-repl` has no `libjvm`. GitHub Actions only runs clj-kondo — no JDK, no GraalVM.

GraalVM is a local Nix build tool. It is not the runtime and not a CI image.

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
