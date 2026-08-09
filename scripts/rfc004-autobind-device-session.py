#!/usr/bin/env python3
"""DEPRECATED wrapper — use scripts/rfc004-run-acceptance.py --mode smoke.

Forwards to the unified acceptance entry (G1–G3 log + bindings).
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
        "NOTE: rfc004-autobind-device-session.py is a thin wrapper; "
        "prefer python scripts/rfc004-run-acceptance.py --mode smoke",
        file=sys.stderr,
    )
    ap = argparse.ArgumentParser()
    ap.add_argument("--book-substr", default="诡秘之主")
    ap.add_argument("--serial", default=None)
    args, extra = ap.parse_known_args()
    cmd = [
        sys.executable,
        str(ENTRY),
        "--no-install",
        "--mode",
        "smoke",
        "--book-substr",
        args.book_substr,
        "--no-auto-push-sources",
    ]
    if args.serial:
        cmd += ["--serial", args.serial]
    cmd += extra
    return subprocess.call(cmd, cwd=str(ROOT))


if __name__ == "__main__":
    raise SystemExit(main())
