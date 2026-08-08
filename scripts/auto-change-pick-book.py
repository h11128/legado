#!/usr/bin/env python3
"""List / pick shelf books that should trigger auto-换源 on open.

Usage:
  python scripts/auto-change-pick-book.py
  python scripts/auto-change-pick-book.py --kind missing_source --limit 10
  python scripts/auto-change-pick-book.py --kind empty_toc --json
  python scripts/auto-change-pick-book.py --pick   # print one bookUrl for shell
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from lib.legado_adb import DEFAULT_PKG, pull_legado_db, require_device  # noqa: E402


KINDS = ("missing_source", "empty_toc", "all")


def query_candidates(con: sqlite3.Connection, kind: str, limit: int) -> list[dict]:
    rows: list[dict] = []
    if kind in ("missing_source", "all"):
        for r in con.execute(
            """
            select b.name, b.author, b.origin, b.bookUrl,
                   length(coalesce(b.tocUrl,'')) as toc_len,
                   'missing_source' as kind
            from books b
            left join book_sources s on s.bookSourceUrl = b.origin
            where b.origin not like 'loc_%'
              and s.bookSourceUrl is null
              and length(b.name) > 1
            order by b.durChapterTime desc
            limit ?
            """,
            (limit,),
        ):
            rows.append(
                {
                    "kind": r[5],
                    "name": r[0],
                    "author": r[1],
                    "origin": r[2],
                    "bookUrl": r[3],
                    "tocLen": r[4],
                }
            )
    if kind in ("empty_toc", "all"):
        for r in con.execute(
            """
            select b.name, b.author, b.origin, b.bookUrl,
                   length(coalesce(b.tocUrl,'')) as toc_len,
                   s.bookSourceName,
                   'empty_toc' as kind
            from books b
            join book_sources s on s.bookSourceUrl = b.origin
            where b.origin not like 'loc_%'
              and (b.tocUrl is null or b.tocUrl = '')
              and length(b.author) > 0
              and s.enabled = 1
              and s.bookSourceType = 0
            order by b.durChapterTime desc
            limit ?
            """,
            (limit,),
        ):
            rows.append(
                {
                    "kind": r[6],
                    "name": r[0],
                    "author": r[1],
                    "origin": r[2],
                    "bookUrl": r[3],
                    "tocLen": r[4],
                    "sourceName": r[5],
                }
            )
    # Prefer missing_source first when kind=all
    if kind == "all":
        rows.sort(key=lambda x: (0 if x["kind"] == "missing_source" else 1))
    return rows[:limit]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--kind", choices=KINDS, default="all")
    ap.add_argument("--limit", type=int, default=15)
    ap.add_argument("--db", type=Path, help="Reuse a pulled db instead of adb pull")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--pick", action="store_true", help="Print one bookUrl only (exit 1 if none)")
    ap.add_argument("--out-db", type=Path, default=ROOT / "temp" / "_auto_change_books.db")
    args = ap.parse_args()

    if args.db:
        db_path = args.db
    else:
        require_device()
        db_path = pull_legado_db(args.out_db, pkg=args.pkg)

    con = sqlite3.connect(str(db_path))
    try:
        rows = query_candidates(con, args.kind, args.limit)
    finally:
        con.close()

    if args.pick:
        if not rows:
            print("no candidate", file=sys.stderr)
            return 1
        print(rows[0]["bookUrl"])
        return 0

    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
    else:
        if not rows:
            print("no candidates")
            return 1
        for i, r in enumerate(rows, 1):
            src = r.get("sourceName") or "(missing)"
            print(
                f"{i}. [{r['kind']}] 《{r['name']}》 {r['author']} | {src} | {r['origin'][:48]}"
            )
            print(f"   bookUrl={r['bookUrl']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
