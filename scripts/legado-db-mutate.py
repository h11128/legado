#!/usr/bin/env python3
"""CLI: safe Legado DB disable / upsert / remap (single pull→mutate→push).

Examples:
  python scripts/legado-db-mutate.py disable --url 'https://m.dead.example'
  python scripts/legado-db-mutate.py upsert-json --file temp/full_fix/cache/src.json
  python scripts/legado-db-mutate.py remap --old-origin 'https://a' --new-origin 'http://b' \\
      --map 'https://a/1=http://b/1'

Never: MCP save_source(new) then push an older pull that lacks that URL.
See docs/postmortem/2026-08-09-adb-db-push-wipes-mcp-source.md
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.lib.legado_db_mutate import (  # noqa: E402
    remap_book_origin,
    set_sources_enabled,
    upsert_book_source,
    with_legado_db,
)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_dis = sub.add_parser("disable", help="Set enabled=0 for URLs")
    p_dis.add_argument("--url", action="append", required=True)
    p_dis.add_argument(
        "--require-url",
        action="append",
        default=[],
        help="URLs that must exist in DB before push (e.g. MCP-migrated twin)",
    )

    p_en = sub.add_parser("enable", help="Set enabled=1 for URLs")
    p_en.add_argument("--url", action="append", required=True)

    p_up = sub.add_parser("upsert-json", help="INSERT/REPLACE source from JSON file")
    p_up.add_argument("--file", required=True, type=Path)

    p_rm = sub.add_parser(
        "remap",
        help="Remap books.origin; with --map only listed bookUrls move",
    )
    p_rm.add_argument("--old-origin", required=True)
    p_rm.add_argument("--new-origin", required=True)
    p_rm.add_argument(
        "--map",
        action="append",
        default=[],
        help=(
            "oldBookUrl=newBookUrl (repeatable). When any --map is given, "
            "ONLY those bookUrls are remapped (not the whole origin)."
        ),
    )
    p_rm.add_argument(
        "--require-url",
        action="append",
        default=[],
        help="Extra URLs that must exist before push",
    )

    args = ap.parse_args()
    require: list[str] = []
    if args.cmd == "enable":
        require = list(args.url)
    elif args.cmd == "upsert-json":
        raw = json.loads(args.file.read_text(encoding="utf-8"))
        if isinstance(raw, list):
            require = [str(s["bookSourceUrl"]) for s in raw]
        else:
            require = [str(raw["bookSourceUrl"])]
    elif args.cmd == "remap":
        require = [args.new_origin, *getattr(args, "require_url", [])]
    elif args.cmd == "disable":
        require = list(getattr(args, "require_url", []) or [])

    with with_legado_db(require_urls=require or None) as con:
        if args.cmd == "disable":
            n = set_sources_enabled(con, args.url, enabled=False)
            print(f"disabled rows={n} urls={args.url}")
        elif args.cmd == "enable":
            n = set_sources_enabled(con, args.url, enabled=True)
            print(f"enabled rows={n} urls={args.url}")
        elif args.cmd == "upsert-json":
            raw = json.loads(args.file.read_text(encoding="utf-8"))
            if isinstance(raw, list):
                print("upserted", [upsert_book_source(con, s) for s in raw])
            else:
                print("upserted", upsert_book_source(con, raw))
        elif args.cmd == "remap":
            m: dict[str, str] = {}
            for item in args.map:
                if "=" not in item:
                    raise SystemExit(f"bad --map {item!r}")
                a, b = item.split("=", 1)
                m[a] = b
            n = remap_book_origin(
                con,
                old_origin=args.old_origin,
                new_origin=args.new_origin,
                book_url_map=m or None,
            )
            print(f"remapped books={n}")
        else:
            raise SystemExit(f"unknown cmd {args.cmd}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
