#!/usr/bin/env python3
"""List shelf books that need restore / change-source / readable remapping.

Usage:
  python scripts/shelf-restore-pick-books.py
  python scripts/shelf-restore-pick-books.py --kind missing_origin --limit 30 --json
  python scripts/shelf-restore-pick-books.py --kind empty_toc --limit 20
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


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument(
        "--kind",
        choices=("missing_origin", "empty_toc", "disabled_origin", "all"),
        default="all",
    )
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--db", type=Path)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--out-db", type=Path, default=ROOT / "temp" / "_shelf_restore_books.db")
    args = ap.parse_args()

    if args.db:
        db = args.db
    else:
        require_device()
        db = pull_legado_db(args.out_db, pkg=args.pkg)

    con = sqlite3.connect(str(db))
    out: list[dict] = []
    try:
        if args.kind in ("missing_origin", "all"):
            for r in con.execute(
                """
                select b.name, b.author, b.origin, b.bookUrl, 'missing_origin'
                from books b
                left join book_sources s on s.bookSourceUrl=b.origin
                where b.origin not like 'loc_%' and s.bookSourceUrl is null
                order by b.durChapterTime desc limit ?
                """,
                (args.limit,),
            ):
                out.append(
                    {
                        "kind": r[4],
                        "name": r[0],
                        "author": r[1],
                        "origin": r[2],
                        "bookUrl": r[3],
                    }
                )
        if args.kind in ("disabled_origin", "all"):
            for r in con.execute(
                """
                select b.name, b.author, b.origin, b.bookUrl, 'disabled_origin'
                from books b
                join book_sources s on s.bookSourceUrl=b.origin
                where b.origin not like 'loc_%' and s.enabled=0
                order by b.durChapterTime desc limit ?
                """,
                (args.limit,),
            ):
                out.append(
                    {
                        "kind": r[4],
                        "name": r[0],
                        "author": r[1],
                        "origin": r[2],
                        "bookUrl": r[3],
                    }
                )
        if args.kind in ("empty_toc", "all"):
            for r in con.execute(
                """
                select b.name, b.author, b.origin, b.bookUrl, 'empty_toc'
                from books b
                where b.origin not like 'loc_%'
                  and (b.tocUrl is null or b.tocUrl='')
                  and length(b.name)>1
                order by b.durChapterTime desc limit ?
                """,
                (args.limit,),
            ):
                out.append(
                    {
                        "kind": r[4],
                        "name": r[0],
                        "author": r[1],
                        "origin": r[2],
                        "bookUrl": r[3],
                    }
                )
    finally:
        con.close()

    # de-dupe by bookUrl keeping first kind
    seen = set()
    uniq = []
    for row in out:
        if row["bookUrl"] in seen:
            continue
        seen.add(row["bookUrl"])
        uniq.append(row)
    uniq = uniq[: args.limit]

    if args.json:
        print(json.dumps(uniq, ensure_ascii=False, indent=2))
    else:
        print(f"count={len(uniq)} db={db}")
        for i, r in enumerate(uniq, 1):
            print(f"{i}. [{r['kind']}] 《{r['name']}》 {r['author']} | {r['origin'][:48]}")
            print(f"   {r['bookUrl']}")
    return 0 if uniq else 1


if __name__ == "__main__":
    raise SystemExit(main())
