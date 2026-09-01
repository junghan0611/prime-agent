#!/usr/bin/env python3
"""Machine extraction of the Python oracle's test denominator.

Two traps this file exists to walk around, both measured on 2026-09-01:

1. It is not pytest.  Every suite under prime-agent-runtime/test is a
   unittest.TestCase / IsolatedAsyncioTestCase subclass, run by
   `python -m unittest discover -s test` (.github/workflows/ci.yml).  pytest is
   not a dependency in prime-agent-runtime/pyproject.toml.  "261 pytest tests"
   was an inherited label; the number survives, the label does not.

2. The anchoring habit inverts.  AGENTS.md says to anchor `^(deftest` because a
   bare `deftest ` grep counts the `[deftest is]` in a require and inflates 65
   to 73.  Transplanted naively to Python -- `^def test_` -- the anchor matches
   ZERO of the 261, because all of them are class methods.  The clojure trap
   inflates; the python trap silently empties.  So extraction goes through the
   AST, never through grep, and never imports the modules (no deps, no network).
"""

import ast
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
TEST_DIR = REPO / "prime-agent-runtime" / "test"

# unittest collects TestCase subclasses regardless of the pytest `Test*`
# class-name convention, so the base name is the gate -- not the class name.
BASES = {"TestCase", "IsolatedAsyncioTestCase"}


def test_ids(test_dir=TEST_DIR):
    """Every `file.py::Class::test_name` the oracle suite runs, in file order."""
    out = []
    for path in sorted(test_dir.glob("test_*.py")):
        tree = ast.parse(path.read_text())
        for node in tree.body:
            if not isinstance(node, ast.ClassDef):
                continue
            if not {ast.unparse(b).rsplit(".", 1)[-1] for b in node.bases} & BASES:
                continue
            for sub in node.body:
                if isinstance(sub, (ast.FunctionDef, ast.AsyncFunctionDef)) and sub.name.startswith("test_"):
                    out.append(f"{path.name}::{node.name}::{sub.name}")
    return out


def host_skips(test_dir=TEST_DIR):
    """test id -> reason string, for every runtime `self.skipTest(...)` guard.

    Directly relevant to the denominator: a skipped case is a contract nobody
    on this host has watched Python keep.  Whether a guard actually fires is a
    host property, so this reports the guard, not the outcome.
    """
    found = {}
    for path in sorted(test_dir.glob("test_*.py")):
        tree = ast.parse(path.read_text())
        for cls in [n for n in tree.body if isinstance(n, ast.ClassDef)]:
            for m in cls.body:
                if not (isinstance(m, (ast.FunctionDef, ast.AsyncFunctionDef)) and m.name.startswith("test_")):
                    continue
                for node in ast.walk(m):
                    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "skipTest":
                        reason = node.args[0].value if node.args and isinstance(node.args[0], ast.Constant) else "?"
                        found[f"{path.name}::{cls.name}::{m.name}"] = reason
    return found


if __name__ == "__main__":
    ids = test_ids()
    skips = host_skips()
    for i in ids:
        print(f"{i}\t{skips.get(i, '-')}")
    print(f"# {len(ids)} tests, {len(skips)} carrying a runtime skipTest guard", file=sys.stderr)
