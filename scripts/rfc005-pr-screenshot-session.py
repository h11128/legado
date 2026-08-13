#!/usr/bin/env python3
"""Capture RFC-005 upstream-PR device screenshots.

Writes docs/design/rfc-005-assets/prN-*.png (and optional GIF/mp4).
Usage:
  python scripts/rfc005-pr-screenshot-session.py
  python scripts/rfc005-pr-screenshot-session.py --no-prefs --skip-auto
"""
from __future__ import annotations

import argparse
import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from lib.rfc004_device import (  # noqa: E402
    PKG,
    adb_call,
    dump_ui,
    force_stop,
    keyevent,
    pull_db_wal,
    require_device,
    shot,
    tap_frac,
    tap_xy,
)

ASSETS = ROOT / "docs" / "design" / "rfc-005-assets"
TEMP = ROOT / "temp" / "rfc005_shots"


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


def last_book(db: Path) -> tuple[str, str]:
    con = sqlite3.connect(str(db))
    row = con.execute(
        "SELECT bookUrl, name FROM books ORDER BY durChapterTime DESC LIMIT 1"
    ).fetchone()
    con.close()
    if not row or not row[0]:
        raise SystemExit("FAIL: no bookshelf book")
    return str(row[0]), str(row[1] or "书")


def open_read(book_url: str) -> None:
    from lib.legado_adb import open_read_book

    force_stop()
    open_read_book(book_url)
    time.sleep(3)


def reveal_read_menu() -> None:
    tap_frac(0.5, 0.55)
    time.sleep(1.0)


def open_search_via_book_info(book_url: str, book_name: str) -> None:
    """SearchActivity is not exported; go Read → title → book name."""
    open_read(book_url)
    reveal_read_menu()
    tap_frac(0.35, 0.08)
    time.sleep(2.0)
    xml = dump()
    xy = find_xy(xml, book_name) if book_name else None
    if xy:
        tap_xy(*xy)
    else:
        tap_frac(0.42, 0.18)
    time.sleep(2.0)
    xml = dump()
    if "源操作" in xml or "确定" in xml:
        tap_label("确定", "OK", "搜索")
        time.sleep(1.0)
    time.sleep(12)


def capture_pr1(book_url: str, book_name: str) -> None:
    open_search_via_book_info(book_url, book_name)
    shot(ASSETS / "pr1-after.png")
    print("PR1 search via book info, name=", book_name)


def open_change_source() -> bool:
    reveal_read_menu()
    if tap_label("换源"):
        return True
    tap_frac(0.5, 0.55)
    time.sleep(0.8)
    return tap_label("换源")


def overflow_menu() -> bool:
    xml = dump()
    xy = find_xy(xml, "更多选项", "More options", "Overflow")
    if xy:
        tap_xy(*xy)
        time.sleep(0.8)
        return True
    # Toolbar overflow is usually top-right of the dialog.
    tap_frac(0.94, 0.08)
    time.sleep(0.8)
    return True


def start_or_stop_search() -> str:
    xml = dump()
    xy = find_xy(xml, "刷新", "Refresh", "停止", "Stop")
    if not xy:
        return "missing"
    desc = "stop" if ("停止" in xml or 'content-desc="Stop"' in xml) else "refresh"
    tap_xy(*xy)
    time.sleep(0.6)
    return desc


def screenrecord(remote: str, seconds: int) -> None:
    adb_call(
        "shell",
        f"screenrecord --time-limit {seconds} {remote}",
    )


def mp4_to_gif(mp4: Path, gif: Path) -> bool:
    ffmpeg = subprocess.run(["ffmpeg", "-version"], capture_output=True)
    if ffmpeg.returncode != 0:
        return False
    r = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(mp4),
            "-vf",
            "fps=8,scale=540:-1:flags=lanczos",
            str(gif),
        ],
        capture_output=True,
    )
    return r.returncode == 0 and gif.exists()


def apply_prefs() -> None:
    adb_call(
        "shell",
        "monkey",
        "-p",
        PKG,
        "-c",
        "android.intent.category.LAUNCHER",
        "1",
    )
    time.sleep(2)
    adb_call(
        "shell",
        "am",
        "broadcast",
        "-a",
        "io.legado.app.action.SET_CHANGE_SOURCE_PREFS",
        "--ez",
        "loadWordCount",
        "true",
        "--ez",
        "earlyStop",
        "true",
        "--ez",
        "filterNonNovelHost",
        "true",
        "--ez",
        "filterNonBookIntro",
        "true",
        "--ez",
        "dropContentBad",
        "true",
        "--ez",
        "checkAuthor",
        "false",
        "-n",
        f"{PKG}/io.legado.app.receiver.ChangeSourcePrefsReceiver",
    )
    time.sleep(1)


def capture_pr7(book_url: str) -> None:
    open_read(book_url)
    reveal_read_menu()
    if not tap_label("设置", "Setting"):
        tap_frac(0.78, 0.92)
        time.sleep(1.2)
    for _ in range(12):
        xml = dump()
        if "跨源段评" in xml or "自动发现段评" in xml or "Cross-source" in xml:
            shot(ASSETS / "pr7-settings.png")
            print("PR7 overlay prefs visible")
            return
        from lib.rfc004_device import swipe_frac

        swipe_frac(0.5, 0.82, 0.5, 0.35, 400)
        time.sleep(0.4)
    shot(ASSETS / "pr7-settings.png")
    print("PR7 WARN: overlay prefs not found, saved last frame")


def capture_change_source(book_url: str, *, record: bool) -> None:
    open_read(book_url)
    if not open_change_source():
        raise SystemExit("FAIL: 换源 button not found")
    time.sleep(1.5)
    overflow_menu()
    shot(ASSETS / "pr2-menu.png")
    keyevent(4)
    time.sleep(0.6)

    # Drain leftover search so we control the start.
    xml = dump()
    if find_xy(xml, "停止", "Stop"):
        tap_label("停止", "Stop")
        time.sleep(2)

    remote = "/sdcard/rfc005_pr3.mp4"
    rec = None
    if record:
        rec = subprocess.Popen(
            ["adb", "shell", f"screenrecord --time-limit 12 {remote}"]
        )
        time.sleep(0.5)

    start_or_stop_search()
    time.sleep(3.5)
    shot(ASSETS / "pr4-after.png")
    time.sleep(6)
    shot(ASSETS / "pr5-after.png")
    if rec is not None:
        rec.wait(timeout=20)
        mp4 = ASSETS / "pr3-demo.mp4"
        adb_call("pull", remote, str(mp4))
        gif = ASSETS / "pr3-demo.gif"
        if mp4_to_gif(mp4, gif):
            print("PR3 gif", gif)
        else:
            print("PR3 mp4 only (no ffmpeg)", mp4)
    # Also a still of the running/stopped list as PR2 after (filtered list).
    shot(ASSETS / "pr2-after.png")


def capture_pr6() -> None:
    pick = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "auto-change-pick-book.py"), "--kind", "all", "--pick"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
    )
    url = (pick.stdout or "").strip().splitlines()
    url = url[-1] if url else ""
    if not url or "FAIL" in url or pick.returncode != 0:
        print("PR6 skip: no auto-change candidate")
        return
    from lib.legado_adb import open_read_book

    force_stop()
    open_read_book(url)
    time.sleep(1.2)
    remote = "/sdcard/rfc005_pr6.mp4"
    rec = subprocess.Popen(
        ["adb", "shell", f"screenrecord --time-limit 12 {remote}"]
    )
    rec.wait(timeout=20)
    mp4 = ASSETS / "pr6-demo.mp4"
    adb_call("pull", remote, str(mp4))
    shot(ASSETS / "pr6-after.png")
    gif = ASSETS / "pr6-demo.gif"
    if mp4_to_gif(mp4, gif):
        print("PR6 gif", gif)
    else:
        print("PR6 mp4", mp4)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-prefs", action="store_true")
    ap.add_argument("--skip-auto", action="store_true")
    ap.add_argument("--only", default="", help="comma list: pr1,pr7,cs,pr6")
    args = ap.parse_args()

    require_device()
    ASSETS.mkdir(parents=True, exist_ok=True)
    TEMP.mkdir(parents=True, exist_ok=True)
    if not args.no_prefs:
        apply_prefs()

    db = pull_db_wal(TEMP / "db", stop_app=True)
    book_url, book_name = last_book(db)
    print("book", book_name, book_url[:80])

    wanted = {x.strip().lower() for x in args.only.split(",") if x.strip()}
    steps = [
        ("pr1", lambda: capture_pr1(book_url, book_name)),
        ("pr7", lambda: capture_pr7(book_url)),
        ("cs", lambda: capture_change_source(book_url, record=not args.no_record)),
    ]
    if not args.skip_auto:
        steps.append(("pr6", capture_pr6))
    if wanted:
        steps = [(n, f) for n, f in steps if n in wanted]
    for name, fn in steps:
        try:
            fn()
        except Exception as e:
            print(f"WARN: {name} failed: {e}")

    print("ASSETS", ASSETS)
    for p in sorted(ASSETS.iterdir()):
        print(" ", p.name, p.stat().st_size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
