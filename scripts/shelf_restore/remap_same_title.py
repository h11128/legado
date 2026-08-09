#!/usr/bin/env python3
"""Dedupe / tag shelf books with missing origins.

When the same title+author already has an enabled copy on the shelf, delete the
orphan row (honest dedupe — bookUrl is PK, cannot remap two rows onto one URL).

Optional --tag-manual only stamps intro with [needs_manual_reshelve] and does
**not** rebind origin (origin-only rebinds are dishonest).

Examples:
  python scripts/shelf-restore-remap.py --dry-run
  python scripts/shelf-restore-remap.py --tag-manual --push
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
from lib.legado_adb import (  # noqa: E402
    DEFAULT_PKG,
    pull_legado_db,
    push_legado_db,
    require_device,
    shelf_restore_queue,
)

MARKER = "[needs_manual_reshelve]"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--db", type=Path)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument(
        "--tag-manual",
        action="store_true",
        help="stamp remaining missing books' intro with needs_manual_reshelve (no origin rewrite)",
    )
    ap.add_argument(
        "--push",
        action="store_true",
        help="push modified DB back to the phone (ignored with --dry-run)",
    )
    ap.add_argument(
        "--out",
        type=Path,
        default=None,
        help="report JSON (default temp/shelf_restore/queue/remap_report.json)",
    )
    args = ap.parse_args()

    queue = shelf_restore_queue()
    out = args.out or (queue / "remap_report.json")
    if args.db:
        db = args.db
    else:
        require_device()
        db = pull_legado_db(queue / "live_remap.db", pkg=args.pkg, min_bytes=1000)

    con = sqlite3.connect(str(db))
    try:
        con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    except Exception:
        pass

    ok_books = con.execute(
        """
        select name, author, origin, originName, bookUrl from books
        where origin in (select bookSourceUrl from book_sources where enabled=1)
        """
    ).fetchall()
    idx: dict[tuple, list] = defaultdict(list)
    for n, a, o, on, bu in ok_books:
        idx[(n or "", a or "")].append(
            {"origin": o, "originName": on, "bookUrl": bu}
        )

    missing = con.execute(
        """
        select name, author, origin, originName, bookUrl from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchall()

    dedupe_rows: list[dict] = []
    tagged_rows: list[dict] = []

    for n, a, o, on, bu in missing:
        hits = [h for h in idx.get((n or "", a or ""), []) if h["bookUrl"] != bu]
        if hits:
            h = hits[0]
            dedupe_rows.append(
                {
                    "deleted": bu,
                    "kept": h["bookUrl"],
                    "kept_origin": h["origin"],
                    "name": n,
                    "old_origin": o,
                }
            )
            if not args.dry_run:
                # Honest: keep the enabled copy; drop the orphan PK row.
                con.execute("delete from books where bookUrl=?", (bu,))
            continue
        if args.tag_manual:
            intro_row = con.execute(
                "select intro from books where bookUrl=?", (bu,)
            ).fetchone()
            intro = (intro_row[0] if intro_row else "") or ""
            if MARKER not in intro:
                intro = (f"{MARKER} " + intro)[:500]
                tagged_rows.append(
                    {"bookUrl": bu, "name": n, "origin": o}
                )
                if not args.dry_run:
                    con.execute(
                        "update books set intro=? where bookUrl=?",
                        (intro, bu),
                    )

    if not args.dry_run:
        con.commit()

    miss_left = con.execute(
        """
        select count(*) from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchone()[0]
    en_bad = con.execute(
        """
        select count(*) from books b
        join book_sources s on b.origin = s.bookSourceUrl
        where b.origin not like 'loc_%' and s.enabled = 0
        """
    ).fetchone()[0]
    con.close()

    report = {
        "dry_run": args.dry_run,
        "deduped": dedupe_rows,
        "tagged_manual": tagged_rows,
        "missing_left": miss_left,
        "disabled_origin_books": en_bad,
        "note": (
            "Same-title orphans are deleted when an enabled copy already exists "
            "(bookUrl PK). --tag-manual never rewrites origin."
        ),
    }
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        json.dumps(
            {
                "deduped": len(dedupe_rows),
                "tagged_manual": len(tagged_rows),
                "missing_left": miss_left,
                "disabled_origin_books": en_bad,
                "out": str(out),
            },
            ensure_ascii=False,
        )
    )

    if args.push and not args.dry_run:
        require_device()
        # Keep enabled origins referenced by remaining shelf books.
        con2 = sqlite3.connect(str(db))
        try:
            require = [
                r[0]
                for r in con2.execute(
                    """
                    SELECT DISTINCT origin FROM books
                    WHERE origin IN (SELECT bookSourceUrl FROM book_sources WHERE enabled=1)
                    """
                )
            ]
        finally:
            con2.close()
        push_legado_db(db, pkg=args.pkg, require_source_urls=require or None)
        print("pushed", db)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
