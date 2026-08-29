#!/usr/bin/env python3
"""Native binary smoke: two cells persist + mock host reply handle. No JVM."""
from __future__ import annotations

import json
import os
import subprocess
import sys


def send(proc: subprocess.Popen, obj: dict) -> None:
    assert proc.stdin is not None
    proc.stdin.write(json.dumps(obj) + "\n")
    proc.stdin.flush()


def read(proc: subprocess.Popen) -> dict:
    assert proc.stdout is not None
    line = proc.stdout.readline()
    if not line:
        raise EOFError("runtime closed stdout")
    return json.loads(line)


def until_done(proc: subprocess.Popen, rid: str) -> list[dict]:
    events = []
    while True:
        event = read(proc)
        events.append(event)
        if event.get("event") == "done" and event.get("id") == rid:
            return events


def main() -> int:
    binary = sys.argv[1] if len(sys.argv) > 1 else "target/rlm-repl"
    file_out = subprocess.check_output(["file", binary], text=True).strip()
    print("file:", file_out)
    if "ELF" not in file_out:
        raise SystemExit(f"not an ELF executable: {file_out}")
    if "Java" in file_out or "JAR" in file_out:
        raise SystemExit(f"looks like JVM bytecode: {file_out}")

    proc = subprocess.Popen(
        [binary],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        comm = subprocess.check_output(
            ["ps", "-p", str(proc.pid), "-o", "comm="], text=True
        ).strip()
        print("proc comm:", comm, "pid:", proc.pid)
        if comm in {"java", "java.exe"}:
            raise SystemExit("process comm is java — JVM is running")

        ready = read(proc)
        if ready.get("event") != "ready" or ready.get("protocol") != 2:
            raise SystemExit(f"bad ready: {ready}")

        send(proc, {"type": "execute", "id": "c1", "code": "(def x 41)"})
        until_done(proc, "c1")
        send(proc, {"type": "execute", "id": "c2", "code": "(inc x)"})
        events = until_done(proc, "c2")
        result = next(e["text"] for e in events if e.get("event") == "result")
        if result != "42":
            raise SystemExit(f"persistent binding failed: {result!r}")

        send(proc, {"type": "execute", "id": "r", "code": '(rlm "child task")'})
        req = read(proc)
        while req.get("event") != "host_request":
            req = read(proc)
        send(
            proc,
            {
                "type": "host_reply",
                "id": req["id"],
                "data": {
                    "status": "ok",
                    "rlm_child_id": "c1",
                    "name": "child",
                    "session_dir": "/tmp/x",
                    "model": "test",
                    "answer": "THE ANSWER",
                },
            },
        )
        events = until_done(proc, "r")
        text = next(e["text"] for e in events if e.get("event") == "result")
        if "THE ANSWER" in text:
            raise SystemExit(f"rlm returned answer instead of handle: {text}")
        if "c1" not in text:
            raise SystemExit(f"rlm handle missing child id: {text}")

        send(proc, {"type": "shutdown", "id": "s"})
        until_done(proc, "s")
        rc = proc.wait(timeout=10)
        if rc != 0:
            raise SystemExit(f"shutdown exit {rc}")
        print("smoke ok result=42 handle=", text)
        return 0
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=10)


if __name__ == "__main__":
    raise SystemExit(main())
