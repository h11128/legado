#!/usr/bin/env python3
"""RFC-004 fixture overlay session (manual bind + log assert).

Uses shared scripts.lib.rfc004_device. For real auto-bind acceptance prefer:
  python scripts/rfc004-run-acceptance.py

Usage:
  python scripts/rfc004-overlay-device-session.py
  python scripts/rfc004-overlay-device-session.py --book-substr '综漫'
  python scripts/rfc004-overlay-device-session.py --merge
"""
from __future__ import annotations

import argparse
import re
import sqlite3
import sys
import time
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.lib import rfc004_device as d  # noqa: E402
from scripts.lib.legado_adb import open_read_book  # noqa: E402

FIXTURE = "legado-fixture://review-overlay"
MERGE_PEER = "https://m.qidian.com#rfc004-review"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--book-substr", default="综漫：我同时穿越了99个世界")
    ap.add_argument("--out-dir", default="temp/rfc004_db")
    ap.add_argument("--serial", default=None)
    ap.add_argument(
        "--merge",
        action="store_true",
        help="Bind fixture + a second review source to exercise P5 merge load",
    )
    args = ap.parse_args()
    d.require_device(serial=args.serial)
    out = ROOT / args.out_dir
    db = d.pull_db_wal(out, stop_app=True)
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    cols = {r[1] for r in cur.execute("PRAGMA table_info(book_review_bindings)")}
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
    d.insert_review_binding(
        cur,
        cols,
        book_url=book_url,
        name=name,
        author=author or "",
        origin=origin,
        provider_source=FIXTURE,
        provider_book=prov,
        provider_name=name.strip(),
        provider_author="夹具作者",
        sort_order=0,
    )
    if args.merge:
        peer = cur.execute(
            "select bookSourceUrl, bookSourceName from book_sources where bookSourceUrl=?",
            (MERGE_PEER,),
        ).fetchone()
        if not peer:
            print(f"FAIL: merge peer missing {MERGE_PEER}", file=sys.stderr)
            return 2
        d.insert_review_binding(
            cur,
            cols,
            book_url=book_url,
            name=name,
            author=author or "",
            origin=origin,
            provider_source=MERGE_PEER,
            provider_book=f"{MERGE_PEER}/book?name={urllib.parse.quote(name.strip())}",
            provider_name=name.strip(),
            provider_author=author or "",
            sort_order=1,
        )
        print("merge bindings: fixture +", MERGE_PEER)
    con.commit()
    n = cur.execute("select count(*) from book_review_bindings").fetchone()[0]
    print("bindings", n)
    cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    d.push_db_raw(db, "overlay")
    d.logcat_clear()
    open_read_book(book_url, d.PKG)
    want_merge = args.merge

    def done() -> bool:
        hits = d.overlay_lines()
        if want_merge:
            return any("merge providers=" in ln for ln in hits)
        return any(
            "bucket=2" in ln or re.search(r"bucket=[1-9]", ln)
            for ln in hits
            if "bind=" in ln
        )

    if not d.wait_until(done, timeout_s=45, interval_s=2.0, label="overlay-fixture"):
        print(
            "FAIL: no positive " + ("merge" if want_merge else "bucket") + " log",
            file=sys.stderr,
        )
        return 1
    log = d.logcat_applog()
    hits = d.overlay_lines(log)
    for ln in hits[-8:]:
        print(ln)
    if want_merge:
        if any("merge:" in ln and "paraData" in ln.lower() for ln in log.splitlines()):
            print("FAIL: fake merge: paraData key in logs", file=sys.stderr)
            return 1
        print("PASS: P5 merge load logged")
        return 0
    if any("not review-capable" in ln for ln in hits):
        print("FAIL: provider not review-capable", file=sys.stderr)
        return 1
    print("PASS: overlay chapter-bucket loaded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
