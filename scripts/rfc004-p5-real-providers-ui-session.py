#!/usr/bin/env python3
"""DEPRECATED wrapper — use scripts/rfc004-run-acceptance.py.

P5 real-provider UI smoke is covered by full acceptance (auto-bind + merge).
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENTRY = ROOT / "scripts" / "rfc004-run-acceptance.py"


def main() -> int:
    print(
        "NOTE: rfc004-p5-real-providers-ui-session.py is a thin wrapper; "
        "prefer python scripts/rfc004-run-acceptance.py --no-install",
        file=sys.stderr,
    )
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--from", dest="from_gate", default=None)
    ap.add_argument("--only", default=None)
    args, extra = ap.parse_known_args()
    # Drop duplicate --mode from leftover argv so wrapper mode wins
    cleaned = []
    skip = False
    for a in extra:
        if skip:
            skip = False
            continue
        if a in ("--mode",):
            skip = True
            continue
        if a.startswith("--mode="):
            continue
        cleaned.append(a)
    cmd = [
        sys.executable,
        str(ENTRY),
        "--no-install",
        "--mode",
        "full",
    ]
    if args.serial:
        cmd += ["--serial", args.serial]
    if args.from_gate:
        cmd += ["--from", args.from_gate]
    if args.only:
        cmd += ["--only", args.only]
    cmd += cleaned
    return subprocess.call(cmd, cwd=str(ROOT))


if __name__ == "__main__":
    raise SystemExit(main())
