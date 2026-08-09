#!/usr/bin/env python3
"""One-shot RFC-004 seamless auto-bind acceptance (agent entry).

Unified entry for all RFC-004 device acceptance. Prefer this over legacy
session scripts (they wrap here).

Usage:
  python scripts/rfc004-run-acceptance.py
  python scripts/rfc004-run-acceptance.py --no-install
  python scripts/rfc004-run-acceptance.py --push-sources
  python scripts/rfc004-run-acceptance.py --auto-push-sources
  python scripts/rfc004-run-acceptance.py --mode smoke
  python scripts/rfc004-run-acceptance.py --from G8 --no-install
  python scripts/rfc004-run-acceptance.py --only G9 --no-install
  python scripts/rfc004-run-acceptance.py --probe-only
  python scripts/rfc004-run-acceptance.py --serial SERIAL

Exit 0 only when ACCEPTANCE.json PASS=true.
Guide: docs/guides/rfc-004-autobind-acceptance.md
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.lib import rfc004_device as d  # noqa: E402

OUT = ROOT / "temp" / "rfc004_ui" / "acceptance"
ACCEPT = OUT / "ACCEPTANCE.json"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "app" / "debug" / "app-app-debug.apk"
UI = ROOT / "scripts" / "rfc004-autobind-acceptance-ui.py"
PUSH = ROOT / "scripts" / "push-rfc004-review-sources.py"
PROBE = ROOT / "scripts" / "rfc004-probe-qidian-para-align.py"


def run(cmd: list[str], *, check: bool = True, env: dict | None = None) -> int:
    print("+", " ".join(cmd), flush=True)
    p = subprocess.run(cmd, cwd=str(ROOT), env=env)
    if check and p.returncode != 0:
        raise SystemExit(p.returncode)
    return p.returncode


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
    if APK.is_file():
        apk = APK
    else:
        cands = list((ROOT / "app" / "build" / "outputs" / "apk").rglob("*debug*.apk"))
        if not cands:
            print("FAIL: debug APK missing", file=sys.stderr)
            raise SystemExit(2)
        apk = max(cands, key=lambda p: p.stat().st_mtime)
    run(["adb", "install", "-r", str(apk)])


def apk_is_fresh(max_age_s: float = 3600) -> bool:
    if not APK.is_file():
        return False
    return (time.time() - APK.stat().st_mtime) < max_age_s


def device_has_real_review_sources() -> bool:
    try:
        db = d.pull_db_wal(OUT / "precheck", stop_app=True)
        return len(d.count_real_review_sources(db)) >= 1
    except Exception as e:
        print("precheck sources failed:", e)
        return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument(
        "--skip-if-apk-fresh",
        action="store_true",
        help="Skip assemble/install when debug APK mtime < 1h",
    )
    ap.add_argument("--push-sources", action="store_true")
    ap.add_argument(
        "--auto-push-sources",
        action="store_true",
        help="Push review sources when device has none (default on for full mode)",
    )
    ap.add_argument("--no-auto-push-sources", action="store_true")
    ap.add_argument("--probe-only", action="store_true")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--book-substr", default=d.BOOK_SUB_DEFAULT)
    ap.add_argument("--from", dest="from_gate", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--mode", choices=("full", "smoke"), default="full")
    args = ap.parse_args()

    d.require_device(serial=args.serial)

    if args.probe_only:
        return run([sys.executable, str(PROBE)])

    do_install = not args.no_install
    if args.skip_if_apk_fresh and apk_is_fresh():
        print("skip install (APK fresh <1h)")
        do_install = False
    if do_install:
        assemble_install()
    else:
        print("skip install")

    auto_push = args.push_sources or (
        args.auto_push_sources
        or (args.mode == "full" and not args.no_auto_push_sources and not args.only and not args.from_gate)
    )
    if auto_push or args.push_sources:
        if args.push_sources or not device_has_real_review_sources():
            run([sys.executable, str(PUSH)])
        else:
            print("real review sources present; skip push")

    ui_cmd = [
        sys.executable,
        str(UI),
        "--mode",
        args.mode,
        "--book-substr",
        args.book_substr,
    ]
    if args.serial:
        ui_cmd += ["--serial", args.serial]
    if args.from_gate:
        ui_cmd += ["--from", args.from_gate]
    if args.only:
        ui_cmd += ["--only", args.only]

    rc = run(ui_cmd, check=False)
    if ACCEPT.is_file():
        data = json.loads(ACCEPT.read_text(encoding="utf-8"))
        print("--- ACCEPTANCE.json ---")
        print(json.dumps(data, ensure_ascii=False, indent=2))
        if data.get("PASS") is True and rc == 0:
            print("PASS: full acceptance →", OUT)
            return 0
        print(
            "FAIL: PASS!=true or UI rc=",
            rc,
            "fail=",
            data.get("fail"),
            file=sys.stderr,
        )
        return rc or 1
    print("FAIL: missing", ACCEPT, file=sys.stderr)
    return rc or 1


if __name__ == "__main__":
    raise SystemExit(main())
