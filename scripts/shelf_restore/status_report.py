#!/usr/bin/env python3
"""Shelf restore status report (+ optional MCP smoke on sample books).

Examples:
  python scripts/shelf-restore-report.py
  python scripts/shelf-restore-report.py --smoke 4
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
from lib.legado_adb import (  # noqa: E402
    DEFAULT_PKG,
    pull_legado_db,
    require_device,
    shelf_restore_queue,
)
from lib.legado_mcp import LegadoMcp  # noqa: E402

MARKER = "[needs_manual_reshelve]"


def content_len(log: str) -> int:
    m = re.search(r"┌获取正文内容\n└([\s\S]*?)(?:\n\[|$)", log)
    return len((m.group(1) if m else "").strip())


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--db", type=Path)
    ap.add_argument("--smoke", type=int, default=0, help="MCP debug N sample books")
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()

    queue = shelf_restore_queue()
    out = args.out or (queue / "shelf_restore_report.json")
    if args.db:
        db = args.db
    else:
        require_device()
        db = pull_legado_db(queue / "live_report.db", pkg=args.pkg)

    con = sqlite3.connect(str(db))
    miss = con.execute(
        """
        select count(*) from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchone()[0]
    manual = con.execute(
        "select count(*) from books where intro like ?", (f"%{MARKER}%",)
    ).fetchone()[0]
    dis = con.execute(
        """
        select count(*) from books b
        join book_sources s on b.origin=s.bookSourceUrl
        where b.origin not like 'loc_%' and s.enabled=0
        """
    ).fetchone()[0]
    samples = con.execute(
        """
        select name, author, origin, bookUrl from books
        where origin not like 'loc_%'
          and (intro is null or intro not like ?)
          and bookUrl like 'http%'
          and origin in (select bookSourceUrl from book_sources where enabled=1)
        limit 12
        """,
        (f"%{MARKER}%",),
    ).fetchall()
    con.close()

    smoke: list[dict] = []
    if args.smoke > 0:
        mcp = LegadoMcp(connect_retries=10)
        for name, _a, origin, book_url in samples[: args.smoke]:
            try:
                log = mcp.debug_source(origin, book_url, timeout_sec=50)
                clen = content_len(log)
                ok = clen > 30
                smoke.append(
                    {
                        "name": name,
                        "origin": origin,
                        "bookUrl": book_url,
                        "content_len": clen,
                        "ok": ok,
                    }
                )
                print("SMOKE", "OK" if ok else "FAIL", name, clen)
            except Exception as e:
                smoke.append(
                    {"name": name, "origin": origin, "ok": False, "err": str(e)[:120]}
                )
                print("SMOKE ERR", name, e)

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "acceptance": {
            "structural_missing": miss,
            "disabled_origin_books": dis,
            "pass_structural_zero": miss == 0 and dis == 0,
        },
        "counts": {
            "needs_manual_reshelve": manual,
            "smoke_ok": sum(1 for s in smoke if s.get("ok")),
            "smoke_tried": len(smoke),
        },
        "smoke_results": smoke,
    }
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (queue.parent / "shelf_restore_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report["acceptance"], ensure_ascii=False))
    print(json.dumps(report["counts"], ensure_ascii=False))
    print("wrote", out)
    return 0 if report["acceptance"]["pass_structural_zero"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
