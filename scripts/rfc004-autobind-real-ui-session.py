#!/usr/bin/env python3
"""DEPRECATED wrapper — use scripts/rfc004-run-acceptance.py.

Forwards to full G1–G9 real-provider screenshot acceptance.
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
        "NOTE: rfc004-autobind-real-ui-session.py is a thin wrapper; "
        "prefer python scripts/rfc004-run-acceptance.py --no-install",
        file=sys.stderr,
    )
    ap = argparse.ArgumentParser()
    ap.add_argument("--book-substr", default="诡秘之主")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--from", dest="from_gate", default=None)
    ap.add_argument("--only", default=None)
    args, extra = ap.parse_known_args()
    cmd = [
        sys.executable,
        str(ENTRY),
        "--no-install",
        "--mode",
        "full",
        "--book-substr",
        args.book_substr,
    ]
    if args.serial:
        cmd += ["--serial", args.serial]
    if args.from_gate:
        cmd += ["--from", args.from_gate]
    if args.only:
        cmd += ["--only", args.only]
    cmd += extra
    return subprocess.call(cmd, cwd=str(ROOT))


if __name__ == "__main__":
    raise SystemExit(main())
