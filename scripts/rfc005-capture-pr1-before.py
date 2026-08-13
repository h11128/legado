#!/usr/bin/env python3
"""Install upstream (no smart-merge) APK and capture RFC-005 PR1 before/after.

Downloads LegadoTeam release if needed, imports a small source set from the
debug DB, searches the same title on both packages.

  python scripts/rfc005-capture-pr1-before.py
  python scripts/rfc005-capture-pr1-before.py --query 斗破苍穹 --apk path.apk
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import subprocess
import sys
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from lib.rfc004_device import (  # noqa: E402
    adb_call,
    dump_ui,
    pull_db_wal,
    require_device,
    shot,
    tap_xy,
)

ASSETS = ROOT / "docs" / "design" / "rfc-005-assets"
TEMP = ROOT / "temp" / "rfc005_shots"
DEBUG_PKG = "com.legado.app.debug"
BEFORE_PKG = "com.legado.app.release"
UPSTREAM_REPO = "LegadoTeam/legado"
UPSTREAM_TAG = "3.26081201"
APK_NAME = "legado_app_3.26081201_universal_release.apk"
JSON_RULES = (
    "ruleExplore",
    "ruleSearch",
    "ruleBookInfo",
    "ruleToc",
    "ruleContent",
    "ruleReview",
)
BOOL_COLS = {
    "enabled",
    "enabledExplore",
    "enabledCookieJar",
    "eventListener",
    "customButton",
}
def _mid(g: tuple[str, ...]) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, g)
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_xy(xml: str, *needles: str) -> tuple[int, int] | None:
    for needle in needles:
        pats = [
            rf'content-desc="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            rf'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="{re.escape(needle)}"',
            rf'text="{re.escape(needle)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            rf'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="{re.escape(needle)}"',
        ]
        for pat in pats:
            m = re.search(pat, xml)
            if m:
                return _mid(m.groups())
    return None


def dump() -> str:
    TEMP.mkdir(parents=True, exist_ok=True)
    return dump_ui(TEMP / "_ui.xml")


def tap_label(*needles: str, wait: float = 1.2) -> bool:
    xy = find_xy(dump(), *needles)
    if not xy:
        return False
    tap_xy(*xy)
    time.sleep(wait)
    return True


YIMING_ORIGINS = (
    "https://m.popofree.com",
    "http://www.bookbenx.cc",
    "https://wap.yushuwu.cloud",
    "http://www.wuxianxs.cc",
    "https://m.lamei2.com",
    "https://m.aaread.club",
    "https://alicesw.org",
    "https://trxs.cc",
    "http://www.amtxt.net",
)


def _row_to_source(cols: list[str], row: tuple) -> dict:
    src: dict = {}
    for col, val in zip(cols, row):
        if val is None:
            continue
        if col in JSON_RULES:
            if isinstance(val, str) and val.strip().startswith(("{", "[")):
                try:
                    src[col] = json.loads(val)
                    continue
                except json.JSONDecodeError:
                    pass
            src[col] = val
        elif col in BOOL_COLS:
            src[col] = bool(val)
        else:
            src[col] = val
    return src


def export_sources(db: Path, dest: Path, limit: int) -> int:
    con = sqlite3.connect(str(db))
    cols = [c[1] for c in con.execute("PRAGMA table_info(book_sources)")]
    seen: set[str] = set()
    out: list[dict] = []
    placeholders = ",".join("?" * len(YIMING_ORIGINS))
    forced = con.execute(
        f"SELECT * FROM book_sources WHERE enabled=1 AND bookSourceUrl IN ({placeholders})",
        YIMING_ORIGINS,
    ).fetchall()
    rest = con.execute(
        """
        SELECT * FROM book_sources
        WHERE enabled=1 AND bookSourceType=0
          AND searchUrl IS NOT NULL AND trim(searchUrl) != ''
        ORDER BY weight DESC, respondTime ASC
        """
    ).fetchall()
    con.close()
    for row in list(forced) + list(rest):
        src = _row_to_source(cols, row)
        url = str(src.get("bookSourceUrl") or "")
        if not url or url in seen:
            continue
        seen.add(url)
        out.append(src)
        if len(out) >= limit:
            break
    dest.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"exported {len(out)} sources -> {dest}")
    return len(out)


def ensure_apk(apk: Path) -> Path:
    if apk.exists() and apk.stat().st_size > 1_000_000:
        return apk
    apk.parent.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(
        [
            "gh",
            "release",
            "download",
            UPSTREAM_TAG,
            "-R",
            UPSTREAM_REPO,
            "-p",
            APK_NAME,
            "-D",
            str(apk.parent),
            "--clobber",
        ]
    )
    return apk


def install_apk(apk: Path) -> None:
    subprocess.check_call(["adb", "install", "-g", "-r", str(apk)])
    print("installed", BEFORE_PKG)


def launch(pkg: str) -> None:
    adb_call(
        "shell",
        "monkey",
        "-p",
        pkg,
        "-c",
        "android.intent.category.LAUNCHER",
        "1",
    )
    time.sleep(2.5)


def dismiss_first_run(rounds: int = 8) -> None:
    for _ in range(rounds):
        xml = dump()
        if tap_label("同意", "Agree", "确认", "允许", "Allow", "While using the app", wait=0.8):
            continue
        if "书架" in xml or "搜索" in xml or "我的" in xml:
            return
        time.sleep(0.6)


def serve_and_import(sources_json: Path) -> None:
    class _DirHandler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(sources_json.parent), **kwargs)

        def log_message(self, fmt: str, *args) -> None:
            print("http", fmt % args)

    httpd = ThreadingHTTPServer(("127.0.0.1", 8765), _DirHandler)
    thread = Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    adb_call("reverse", "tcp:8765", "tcp:8765")
    src = f"http://127.0.0.1:8765/{sources_json.name}"
    uri = f"legado://import/bookSource?src={quote(src, safe='')}"
    print("import", uri)
    try:
        adb_call(
            "shell",
            "am",
            "start",
            "-n",
            f"{BEFORE_PKG}/io.legado.app.ui.association.OnLineImportActivity",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            uri,
        )
        time.sleep(4)
        for _ in range(15):
            xml = dump()
            if "取消全选" in xml or "新增" in xml:
                break
            time.sleep(1)
        if not tap_label("确认", "OK"):
            raise SystemExit("FAIL: import confirm not found")
        time.sleep(3)
    finally:
        httpd.shutdown()
        subprocess.call(["adb", "reverse", "--remove", "tcp:8765"])


def finish_import_if_open() -> bool:
    xml = dump()
    if "导入书源" not in xml:
        return False
    for _ in range(10):
        xml = dump()
        if "取消全选" in xml or "新增" in xml:
            break
        time.sleep(0.8)
    if not tap_label("确认", "OK"):
        raise SystemExit("FAIL: import confirm not found")
    time.sleep(3)
    return True


def search_on(pkg: str, query: str, wait_s: float) -> None:
    """SearchActivity is not exported; SEND into SharedReceiverActivity starts it."""
    adb_call("shell", "am", "force-stop", pkg)
    time.sleep(0.4)
    adb_call(
        "shell",
        "am",
        "start",
        "-n",
        f"{pkg}/io.legado.app.receiver.SharedReceiverActivity",
        "-a",
        "android.intent.action.SEND",
        "-t",
        "text/plain",
        "--es",
        "android.intent.extra.TEXT",
        query,
    )
    time.sleep(wait_s)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--query", default="天才之上")
    ap.add_argument("--limit", type=int, default=50)
    ap.add_argument("--apk", type=Path, default=TEMP / APK_NAME)
    ap.add_argument("--skip-install", action="store_true")
    ap.add_argument("--skip-import", action="store_true")
    ap.add_argument("--uninstall", action="store_true")
    args = ap.parse_args()

    require_device()
    TEMP.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)

    db = pull_db_wal(TEMP / "debug_db", stop_app=True)
    sources = TEMP / "pr1-before-sources.json"
    n = export_sources(db, sources, args.limit)
    if n < 3:
        raise SystemExit("FAIL: not enough sources to demo merge")

    apk = ensure_apk(args.apk)
    if not args.skip_install:
        install_apk(apk)
    launch(BEFORE_PKG)
    dismiss_first_run()
    if finish_import_if_open():
        print("finished leftover import dialog")
    elif not args.skip_import:
        serve_and_import(sources)

    search_on(BEFORE_PKG, args.query, wait_s=18)
    for _ in range(8):
        xml = dump()
        if args.query in xml and ("作者" in xml or "暂无" in xml or "佚名" in xml):
            break
        time.sleep(2)
    shot(ASSETS / "pr1-before.png")
    xml = dump()
    print(
        "before 佚名=",
        "佚名" in xml,
        "empty-hint=",
        "暂无封面" in xml,
        "query=",
        args.query in xml,
    )

    search_on(DEBUG_PKG, args.query, wait_s=22)
    for _ in range(8):
        xml = dump()
        if args.query in xml:
            break
        time.sleep(2)
    shot(ASSETS / "pr1-after.png")
    xml = dump()
    print("after 佚名=", "佚名" in xml, "query=", args.query in xml)

    if args.uninstall:
        subprocess.call(["adb", "uninstall", BEFORE_PKG])
    print("ASSETS", ASSETS / "pr1-before.png", ASSETS / "pr1-after.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
