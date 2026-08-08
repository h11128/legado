#!/usr/bin/env python3
"""Analyze auto-换源 device logcat dump.

Usage:
  python scripts/auto-change-analyze-log.py temp/legado_auto_change_session_*.txt
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


def analyze(text: str) -> dict:
    trigger = re.search(r"auto-change trigger=(\w+)", text)
    ask = re.search(
        r"auto-change ask name=(.*?) author=(.*?) exclude=(.*?) candidates=(\d+) cap=(\d+) threads=(\d+)",
        text,
    )
    gates = {
        "has_trigger": bool(trigger),
        "trigger": trigger.group(1) if trigger else None,
        "has_ask": bool(ask),
        "candidates": int(ask.group(4)) if ask else None,
        "cap": int(ask.group(5)) if ask else None,
        "cap_ok": (int(ask.group(4)) <= int(ask.group(5))) if ask else False,
        "threads": int(ask.group(6)) if ask else None,
        "ask_name": ask.group(1).strip() if ask else None,
        "ask_author": ask.group(2).strip() if ask else None,
    }
    # Soft: no hedge success required; trigger+ask+cap is the device gate for UI path.
    gates["pass"] = bool(gates["has_trigger"] and gates["has_ask"] and gates["cap_ok"])
    return gates


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("log", type=Path)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    text = args.log.read_text(encoding="utf-8", errors="replace")
    report = analyze(text)
    report["log"] = str(args.log)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"log={args.log}")
        print(f"PASS={report['pass']}")
        for k, v in report.items():
            if k in ("pass", "log"):
                continue
            print(f"  {k}={v}")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
