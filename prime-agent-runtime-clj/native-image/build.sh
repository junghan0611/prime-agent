#!/usr/bin/env bash
# native-image entry. --initialize-at-build-time is required: without it the
# image links but dies at runtime with Could not locate clojure/core__init.class.
set -euo pipefail
cd "$(dirname "$0")/.."
# AOT into target/classes (already gitignored via prime-agent-runtime-clj/target/).
# Do not write classes/ at the project root — that path is not ignored.
rm -rf target/classes
mkdir -p target/classes target
clojure -M -e "(binding [*compile-path* \"target/classes\"] (doseq [n '[rlm.io rlm.core rlm.eval rlm.repl]] (compile n)))"
native-image \
  -cp "$(clojure -Spath):target/classes" \
  --no-fallback \
  --initialize-at-build-time \
  -H:+ReportExceptionStackTraces \
  -o target/rlm-repl \
  rlm.repl
bytes=$(stat -c%s target/rlm-repl)
echo "built $(pwd)/target/rlm-repl ($bytes bytes)"
