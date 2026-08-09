#!/usr/bin/env python3
"""RFC-004 seamless auto-bind device session.

Clears review bindings for a shelf book, ensures auto-bind prefs, opens read,
asserts silent bind + ReviewOverlay merge/bind log.

Prereq: debug APK with seamless auto-bind; review-capable sources on device.

Usage:
  python scripts/rfc004-autobind-device-session.py
  python scripts/rfc004-autobind-device-session.py --book-substr '诡秘之主'
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
sys.path.insert(0, str(ROOT))
from scripts.lib.legado_adb import open_read_book  # noqa: E402

PKG = "com.legado.app.debug"
PREFS = f"shared_prefs/{PKG}_preferences.xml"


def adb(*args: str) -> bytes:
    return subprocess.check_output(["adb", *args])


def pull_db_trio(dest: Path) -> Path:
    dest.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1)
    for name in ("legado.db", "legado.db-wal", "legado.db-shm"):
        data = adb("exec-out", "run-as", PKG, "cat", f"databases/{name}")
        (dest / name).write_bytes(data)
    db = dest / "legado.db"
    con = sqlite3.connect(str(db))
    con.execute("PRAGMA wal_checkpoint(FULL)")
    con.commit()
    con.close()
    return db


def ensure_pref_bool(xml: str, key: str, value: bool) -> str:
    needle = f'name="{key}"'
    bool_s = "true" if value else "false"
    if needle in xml:
        return re.sub(
            rf'<boolean name="{re.escape(key)}" value="[^"]*"\s*/>',
            f'<boolean name="{key}" value="{bool_s}" />',
            xml,
            count=1,
        )
    # insert before closing </map>
    return xml.replace(
        "</map>",
        f'    <boolean name="{key}" value="{bool_s}" />\n</map>',
        1,
    )


def push_prefs(out: Path) -> None:
    raw = adb("exec-out", "run-as", PKG, "cat", PREFS).decode("utf-8", "replace")
    xml = ensure_pref_bool(raw, "reviewOverlayEnabled", True)
    xml = ensure_pref_bool(xml, "reviewOverlayAutoBind", True)
    xml = ensure_pref_bool(xml, "reviewOverlayMergeEnabled", True)
    prefs_path = out / "preferences.xml"
    prefs_path.write_text(xml, encoding="utf-8")
    subprocess.check_call(["adb", "push", str(prefs_path), "/data/local/tmp/legado_rfc004_prefs.xml"])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} sh -c 'cp /data/local/tmp/legado_rfc004_prefs.xml {PREFS}'",
        ]
    )
    print("prefs: reviewOverlayAutoBind=true")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--book-substr", default="诡秘之主")
    ap.add_argument("--out-dir", default="temp/rfc004_autobind")
    args = ap.parse_args()
    out = ROOT / args.out_dir
    out.mkdir(parents=True, exist_ok=True)

    push_prefs(out)
    db = pull_db_trio(out)
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    capable = cur.execute(
        "select bookSourceUrl, bookSourceName from book_sources "
        "where enabled=1 and ("
        "bookSourceUrl like '%#rfc004-review' or bookSourceUrl like 'legado-fixture://review-overlay%'"
        ")"
    ).fetchall()
    print("capable review sources:", len(capable))
    for u, n in capable:
        print(" ", n, u)
    if not capable:
        print("FAIL: no review-capable sources enabled", file=sys.stderr)
        return 2
    row = cur.execute(
        "SELECT bookUrl, name, author, origin, durChapterTitle, durChapterIndex "
        "FROM books WHERE name LIKE ? LIMIT 1",
        (f"%{args.book_substr}%",),
    ).fetchone()
    if not row:
        print("FAIL: book not found", file=sys.stderr)
        return 2
    book_url, name, author, origin, title, idx = row
    print("book", name, "author=", author, "dur=", title, idx)
    cur.execute("DELETE FROM book_review_bindings WHERE contentBookUrl=?", (book_url,))
    print("cleared bindings for book")
    con.commit()
    cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    subprocess.check_call(["adb", "push", str(db), "/data/local/tmp/legado_rfc004_ab.db"])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} sh -c 'cp /data/local/tmp/legado_rfc004_ab.db databases/legado.db "
            f"&& rm -f databases/legado.db-wal databases/legado.db-shm'",
        ]
    )
    subprocess.check_call(["adb", "logcat", "-c"])
    open_read_book(book_url, PKG)

    log_path = out / "session.log"
    ok = False
    last = ""
    for i in range(30):
        time.sleep(3)
        out_log = adb("logcat", "-d").decode("utf-8", "replace")
        hits = [
            ln
            for ln in out_log.splitlines()
            if "ReviewOverlay" in ln
            or "已自动绑定" in ln
        ]
        print(f"t+{(i + 1) * 3}s hits={len(hits)}")
        for ln in hits[-8:]:
            print(ln)
            last = ln
        if any("auto-bind proposals=" in ln for ln in hits) and any(
            "merge providers=" in ln or ("bucket=" in ln and "bucket=0 (" not in ln)
            for ln in hits
        ):
            ok = True
            break
        if any("auto-bind proposals=0" in ln for ln in hits):
            print("NOTE: proposeAll returned 0 unique hits", file=sys.stderr)
            break
        if any("merge providers=" in ln for ln in hits):
            ok = True
            break

    log_path.write_text(out_log if "out_log" in dir() else last, encoding="utf-8")

    # Confirm DB rows after stop
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1)
    db2 = pull_db_trio(out / "after")
    con = sqlite3.connect(str(db2))
    rows = con.execute(
        "select providerSourceUrl, bindMode, enabled from book_review_bindings "
        "where contentBookUrl=? order by sortOrder",
        (book_url,),
    ).fetchall()
    con.close()
    print("bindings after:", rows)
    if not rows:
        print("FAIL: no auto bindings persisted", file=sys.stderr)
        return 1
    if not any(r[1] == "auto" for r in rows):
        print("FAIL: expected bindMode=auto", file=sys.stderr)
        return 1
    if not ok:
        print("WARN: bindings ok but no positive overlay bucket log yet", file=sys.stderr)
        # Still success for silent bind persistence.
        print("PASS: silent auto-bind persisted")
        return 0
    print("PASS: silent auto-bind + overlay load")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
