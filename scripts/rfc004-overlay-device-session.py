#!/usr/bin/env python3
"""RFC-004 overlay device session: WAL-aware DB bind + open read + logcat assert.

Prereq:
  - Debug APK installed (com.legado.app.debug)
  - Fixture already saved via MCP save_source (legado-fixture://review-overlay)
  - GRADLE_USER_HOME not required (adb-only)

Usage:
  python scripts/rfc004-overlay-device-session.py
  python scripts/rfc004-overlay-device-session.py --book-substr '综漫'
  python scripts/rfc004-overlay-device-session.py --merge
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
# Second review-capable source for P5 merge path (may fail align; still exercises merge).
MERGE_PEER = "https://m.qidian.com#rfc004-review"


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


def binding_columns(cur: sqlite3.Cursor) -> set[str]:
    return {r[1] for r in cur.execute("PRAGMA table_info(book_review_bindings)").fetchall()}


def insert_binding(
    cur: sqlite3.Cursor,
    cols: set[str],
    *,
    book_url: str,
    name: str,
    author: str,
    origin: str,
    provider_source: str,
    provider_book: str,
    provider_name: str,
    provider_author: str,
    sort_order: int,
) -> None:
    # Room v102 requires enabled/sortOrder/role; older rows may lack them.
    base = {
        "contentBookUrl": book_url,
        "contentName": name.strip(),
        "contentAuthor": author or "",
        "contentOrigin": origin,
        "providerSourceUrl": provider_source,
        "providerBookUrl": provider_book,
        "providerName": provider_name,
        "providerAuthor": provider_author,
        "bindMode": "manual",
        "updatedAt": int(time.time() * 1000),
    }
    if "enabled" in cols:
        base["enabled"] = 1
    if "sortOrder" in cols:
        base["sortOrder"] = sort_order
    if "role" in cols:
        base["role"] = "chapter"
    keys = [k for k in base if k in cols or k in {
        "contentBookUrl", "contentName", "contentAuthor", "contentOrigin",
        "providerSourceUrl", "providerBookUrl", "providerName", "providerAuthor",
        "bindMode", "updatedAt",
    }]
    # Prefer only columns that exist
    keys = [k for k in keys if k in cols]
    placeholders = ",".join("?" for _ in keys)
    cur.execute(
        f"INSERT INTO book_review_bindings ({','.join(keys)}) VALUES ({placeholders})",
        [base[k] for k in keys],
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--book-substr", default="综漫：我同时穿越了99个世界")
    ap.add_argument("--out-dir", default="temp/rfc004_db")
    ap.add_argument(
        "--merge",
        action="store_true",
        help="Bind fixture + a second review source to exercise P5 merge load",
    )
    args = ap.parse_args()
    out = ROOT / args.out_dir
    db = pull_db_trio(out)
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    cols = binding_columns(cur)
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
    insert_binding(
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
        insert_binding(
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
    print("bindings", n, "cols", sorted(c for c in cols if c in (
        "enabled", "sortOrder", "role",
    )))
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
    want_merge = args.merge
    for i in range(15):
        time.sleep(3)
        out_log = adb("logcat", "-d").decode("utf-8", "replace")
        bind_hits = [ln for ln in out_log.splitlines() if "ReviewOverlay bind=" in ln]
        merge_hits = [
            ln for ln in out_log.splitlines() if "ReviewOverlay merge providers=" in ln
        ]
        print(f"t+{(i + 1) * 3}s bind={len(bind_hits)} merge={len(merge_hits)}")
        for ln in (merge_hits or bind_hits)[-8:]:
            print(ln)
        if want_merge:
            if any("ReviewOverlay merge providers=" in ln for ln in merge_hits):
                if any("merge:" in ln and "paraData" in ln.lower() for ln in out_log.splitlines()):
                    print("FAIL: fake merge: paraData key in logs", file=sys.stderr)
                    return 1
                print("PASS: P5 merge load logged")
                return 0
        else:
            if any("bucket=2" in ln or re.search(r"bucket=[1-9]", ln) for ln in bind_hits):
                print("PASS: overlay chapter-bucket loaded")
                return 0
            if any("not review-capable" in ln for ln in bind_hits) and i >= 3:
                print("FAIL: provider not review-capable", file=sys.stderr)
                return 1
    print(
        "FAIL: no positive " + ("merge" if want_merge else "bucket") + " log",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
