#!/usr/bin/env bash
# native-image entry. --initialize-at-build-time is required: without it the
# image links but dies at runtime with Could not locate clojure/core__init.class.
set -euo pipefail
cd "$(dirname "$0")/.."
# AOT into target/classes (already gitignored via prime-agent-runtime-clj/target/).
# Do not write classes/ at the project root — that path is not ignored.
rm -rf target/classes
mkdir -p target/classes target
# warn-on-reflection is a build gate, not decoration: a reflective interop form
# links fine and then dies in the native image (no reflect-config by contract).
# It only became a gate on 2026-09-02. Before that it printed the warning and
# exited 0, so the "only gate" was a gate exactly as far as a human read it --
# a (Thread/sleep some-var) shipped, and the watchdog thread it was in died
# silently inside a catch. Now the warning fails the build.
aot_log="target/aot-warnings.log"
clojure -M -e "(binding [*compile-path* \"target/classes\" *warn-on-reflection* true] (doseq [n '[rlm.io rlm.core rlm.process rlm.eval rlm.repl]] (compile n)))" 2>&1 | tee "$aot_log"
if grep -q "Reflection warning" "$aot_log"; then
  echo "" >&2
  echo "build gate: reflective interop above. It links and then dies in the native image" >&2
  echo "(reflect-config is closed by contract). Hint the local, or wrap the argument --" >&2
  echo "a var deref leaves an overload unresolved too, e.g. (Thread/sleep (long ms))." >&2
  exit 1
fi
native-image \
  -cp "$(clojure -Spath):target/classes" \
  --no-fallback \
  --initialize-at-build-time \
  -H:+ReportExceptionStackTraces \
  -o target/rlm-repl \
  rlm.repl
bytes=$(stat -c%s target/rlm-repl)
echo "built $(pwd)/target/rlm-repl ($bytes bytes)"
