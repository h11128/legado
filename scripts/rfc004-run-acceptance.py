#!/usr/bin/env python3
"""One-shot RFC-004 seamless auto-bind acceptance (agent entry).

Runs device screenshot gates G1–G9 without manual UI clicking.

Usage:
  python scripts/rfc004-run-acceptance.py
  python scripts/rfc004-run-acceptance.py --no-install
  python scripts/rfc004-run-acceptance.py --push-sources
  python scripts/rfc004-run-acceptance.py --probe-only

Exit 0 only when ACCEPTANCE.json PASS=true.
Guide: docs/guides/rfc-004-autobind-acceptance.md
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "temp" / "rfc004_ui" / "acceptance"
ACCEPT = OUT / "ACCEPTANCE.json"
PKG = "com.legado.app.debug"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "app" / "debug" / "app-app-debug.apk"


def run(cmd: list[str], *, check: bool = True) -> int:
    print("+", " ".join(cmd), flush=True)
    p = subprocess.run(cmd, cwd=str(ROOT))
    if check and p.returncode != 0:
        raise SystemExit(p.returncode)
    return p.returncode


def adb_ok() -> None:
    out = subprocess.check_output(["adb", "devices"], text=True, errors="replace")
    lines = [ln for ln in out.splitlines()[1:] if ln.strip() and "device" in ln]
    if not lines:
        print("FAIL: no adb device", file=sys.stderr)
        raise SystemExit(2)
    print("adb:", lines[0])


def assemble_install() -> None:
    env = os.environ.copy()
    env.setdefault("GRADLE_USER_HOME", r"E:\.gradle")
    print("+ gradlew :app:assembleAppDebug", flush=True)
    p = subprocess.run(
        [str(ROOT / "gradlew"), ":app:assembleAppDebug"],
        cwd=str(ROOT),
        env=env,
    )
    if p.returncode != 0:
        raise SystemExit(p.returncode)
    if not APK.is_file():
        # flavor path variant
        cands = list((ROOT / "app" / "build" / "outputs" / "apk").rglob("*debug*.apk"))
        if not cands:
            print("FAIL: debug APK missing", file=sys.stderr)
            raise SystemExit(2)
        apk = max(cands, key=lambda p: p.stat().st_mtime)
    else:
        apk = APK
    run(["adb", "install", "-r", str(apk)])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--no-install",
        action="store_true",
        help="Skip assembleAppDebug + adb install",
    )
    ap.add_argument(
        "--push-sources",
        action="store_true",
        help="Push real RFC-004 review JS sources before acceptance",
    )
    ap.add_argument(
        "--probe-only",
        action="store_true",
        help="Only run 起点 paragraphId probe; skip G1–G9",
    )
    args = ap.parse_args()

    adb_ok()

    if args.probe_only:
        return run([sys.executable, str(ROOT / "scripts" / "rfc004-probe-qidian-para-align.py")])

    if not args.no_install:
        assemble_install()
    else:
        print("skip install (--no-install)")

    if args.push_sources:
        run([sys.executable, str(ROOT / "scripts" / "push-rfc004-review-sources.py")])

    rc = run(
        [sys.executable, str(ROOT / "scripts" / "rfc004-autobind-acceptance-ui.py")],
        check=False,
    )
    if ACCEPT.is_file():
        data = json.loads(ACCEPT.read_text(encoding="utf-8"))
        print("--- ACCEPTANCE.json ---")
        print(json.dumps(data, ensure_ascii=False, indent=2))
        print("--- shots ---", ", ".join(data.get("shots") or []))
        if data.get("PASS") is True and rc == 0:
            print("PASS: full acceptance →", OUT)
            return 0
        print("FAIL: ACCEPTANCE.json PASS!=true or UI script rc=", rc, file=sys.stderr)
        return rc or 1
    print("FAIL: missing", ACCEPT, file=sys.stderr)
    return rc or 1


if __name__ == "__main__":
    raise SystemExit(main())
