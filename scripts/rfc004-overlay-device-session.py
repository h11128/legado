#!/usr/bin/env python3
"""RFC-004 overlay device session: WAL-aware DB bind + open read + logcat assert.

Prereq:
  - Debug APK installed (com.legado.app.debug)
  - Fixture already saved via MCP save_source (legado-fixture://review-overlay)
  - GRADLE_USER_HOME not required (adb-only)

Usage:
  python scripts/rfc004-overlay-device-session.py
  python scripts/rfc004-overlay-device-session.py --book-substr '综漫'
"""
from __future__ import annotations

import argparse
import re
import sqlite3
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.lib.legado_adb import open_read_book  # noqa: E402

PKG = "com.legado.app.debug"
FIXTURE = "legado-fixture://review-overlay"


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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--book-substr", default="综漫：我同时穿越了99个世界")
    ap.add_argument("--out-dir", default="temp/rfc004_db")
    args = ap.parse_args()
    out = ROOT / args.out_dir
    db = pull_db_trio(out)
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    fx = cur.execute(
        "select enabled, bookSourceName from book_sources where bookSourceUrl=?",
        (FIXTURE,),
    ).fetchall()
    if not fx:
        print("FAIL: fixture missing — MCP save_source first", file=sys.stderr)
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
    print("book", name, title, idx)
    cands = cur.execute(
        "select title, `index` from chapters where bookUrl=? order by `index` limit 80",
        (book_url,),
    ).fetchall()
    for t, i in cands:
        m = re.search(r"第\s*0*(\d+)\s*章", t or "")
        if m and 1 <= int(m.group(1)) <= 50:
            cur.execute(
                "update books set durChapterIndex=?, durChapterTitle=? where bookUrl=?",
                (i, t, book_url),
            )
            print("dur ->", t, i)
            break
    prov = FIXTURE + "/book?name=" + urllib.parse.quote(name.strip())
    cur.execute("DELETE FROM book_review_bindings")
    cur.execute(
        """INSERT INTO book_review_bindings
        (contentBookUrl, contentName, contentAuthor, contentOrigin, providerSourceUrl,
         providerBookUrl, providerName, providerAuthor, bindMode, updatedAt)
        VALUES (?,?,?,?,?,?,?,?,?,?)""",
        (
            book_url,
            name.strip(),
            author or "",
            origin,
            FIXTURE,
            prov,
            name.strip(),
            "夹具作者",
            "manual",
            int(time.time() * 1000),
        ),
    )
    con.commit()
    cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    subprocess.check_call(
        ["adb", "push", str(db), "/data/local/tmp/legado_rfc004.db"]
    )
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} sh -c 'cp /data/local/tmp/legado_rfc004.db databases/legado.db "
            f"&& rm -f databases/legado.db-wal databases/legado.db-shm'",
        ]
    )
    subprocess.check_call(["adb", "logcat", "-c"])
    open_read_book(book_url, PKG)
    for i in range(15):
        time.sleep(3)
        out_log = adb("logcat", "-d").decode("utf-8", "replace")
        hits = [ln for ln in out_log.splitlines() if "ReviewOverlay bind=" in ln]
        print(f"t+{(i + 1) * 3}s hits={len(hits)}")
        for ln in hits[-8:]:
            print(ln)
        if any("bucket=2" in ln or re.search(r"bucket=[1-9]", ln) for ln in hits):
            print("PASS: overlay chapter-bucket loaded")
            return 0
        if any("not review-capable" in ln for ln in hits) and i >= 3:
            print("FAIL: provider not review-capable", file=sys.stderr)
            return 1
    print("FAIL: no positive bucket log", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
