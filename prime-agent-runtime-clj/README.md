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
4. **process containment** — commands run under `setsid`, so cleanup signals the whole process group and reclaims a child whose leader already exited (`cmd & exit 0`). What is left: a host with no `setsid` (`:contained false` in the snapshot) falls back to the `ProcessHandle.descendants()` sweep alone and loses that child; a process that re-groups itself escapes either way; a SIGKILLed runtime cannot clean up at all. There is no orphan journal (`process.clj`).
5. **no wait** — `Thread/sleep` is closed, so a cell cannot block on a command. Poll `process-poll` in a later cell; a busy-loop inside one cell holds the runtime and `interrupt` will not free it.
6. **write receipts, no diff event** — `write-text`/`edit-text` return receipt maps. The Python `edit` skill also emits a diff display event to the host; this runtime has no display axis, so it does not (`io.clj`). There is no delete, rename, or mkdir, and two cells writing the same file do not coordinate.
7. **no snapshot, so a restart does not restore** — the oracle revives its namespace from a snapshot file; there is no `snapshot`, `restore`, or `list_names` here. Host compaction leaves this process alone, so the workspace keeps everything. A kernel restart is a new process and keeps nothing: vars, functions, and the process registry are gone. Only the child registry crosses, because the host owns it (`core.clj`).

## `process-*` — H4

`(process-start "cmd")` spawns under the shell and returns immediately with a
data-only handle. The live `java.lang.Process` stays in the runtime registry;
the workspace only ever holds a `:process-id`.

```text
> (def h (process-start "echo hello-h4; exit 3"))
> (process-poll h)
{:process-id "p1", :command "echo hello-h4; exit 3", :pid 1706302, :status :exited,
 :exit-code 3, :killed false, :contained true, :output-bytes 9, :output-truncated false}
> (process-tail h)
"hello-h4"
> (process-kill h)
```

Key order is not part of the shape — read by key.

Output is captured head 128 KiB + tail 128 KiB with the middle dropped. The
child gets pipes, never the protocol descriptors, and stdin is closed. Shutdown,
stdin EOF, and SIGTERM to the runtime all reap the whole process group.
`:contained false` means this host has no `setsid` and cleanup is the weaker
descendants sweep.

Contract and deviations: `docs/clojure-runtime.md`.

## `rlm-children` / `rlm-delete-child` — H6

The child registry lives in the host, so it is the one piece of workspace state
that survives a kernel restart. These two verbs are how an emptied workspace
reaches it. Both are wrappers over host requests that `host-request` could
already reach; they add no capability, only one key shape.

```text
> (rlm "review the auth flow" {:name "auth-reviewer"})
{:rlm-child-id "c1", :name "auth-reviewer", :session-dir "/…/c1", :model "…"}
> (rlm-children)
[{:rlm-child-id "c1", :active-session-id nil, :session-id "s1",
  :session-name "auth-reviewer", :session-dir "/…/c1", :status "running"}]
> (rlm-delete-child {:rlm-child-id "c1"})
{:subagent {:rlm-child-id "c1", …}, :outcome "deleted"}
```

Host JSON spells that field `rlm_child_id`, and `(rlm …)` had already taught the
workspace `:rlm-child-id`. Both verbs normalize every key the host sends from
`_` to `-`, so a handle recovered after a compaction or a restart matches the
handle the workspace learned at spawn. A host error, or a reply with no
`subagents`, raises instead of handing back a map with the payload missing. A
delete target that carries no id is refused before any frame goes out.

After a compaction, nothing was lost: `(keys (ns-publics 'user))` lists what is
still bound — the runtime's own bindings among them — and `(process-list)` still
holds the running commands.


## `write-text` / `edit-text` — H5

Same workspace bound as `read-text`: relative paths, under the root, 1 MiB cap,
UTF-8. Receipts are plain maps, never a file handle; `slurp` and `spit` stay
closed.

```text
> (write-text "notes/a.md" "hello\nworld\n")
{:path "notes/a.md", :action :created, :bytes 12, :lines 2}
> (edit-text "notes/a.md" "world" "there")
{:path "notes/a.md", :action :edited, :line 2, :bytes-before 12, :bytes-after 12}
```

`old` must occur exactly once — 0 or 2+ matches are refused and the file is left
untouched. The parent directory must already exist. Unlike `read-text`, the
write verbs resolve the parent's real path and refuse a symlink target, so a
symlink inside the workspace cannot aim a write outside it.
