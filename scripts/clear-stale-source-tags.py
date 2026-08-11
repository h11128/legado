#!/usr/bin/env python3
"""Clear stale Error/失效 tags from book sources after a successful dig/verify.

Does NOT wipe bookSourceComment when it looks like JS helpers
(eval / function / java. / source.bookSourceComment patterns).

Examples:
  python scripts/clear-stale-source-tags.py --url 'http://m.tingroom.com'
  python scripts/clear-stale-source-tags.py --urls-file temp/full_fix/cache/clear_tags.urls.txt
  python scripts/clear-stale-source-tags.py --dry-run --url 'http://www.8kbook.com'
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.lib.legado_db_mutate import with_legado_db  # noqa: E402

_JS_HINT = re.compile(
    r"(eval\s*\(|function\s*\(|\bjava\.|source\.bookSourceComment|@js:)",
    re.I,
)
# Match App BookSource.removeErrorComment: drop \n\n-separated blocks that
# start with "// Error:" / "Error:" (localizedMessage may be multi-line).
_ERROR_BLOCK = re.compile(
    r"(?is)(?:^|\n\n)[ \t]*(?://\s*)?(?:\"?error:|Error:).*(?=(?:\n\n|\Z))"
)
_ERROR_LINE = re.compile(
    r"(?im)^[ \t]*(?://\s*)?(?:\"?error:|Error:|Timed out|Unable to resolve|"
    r"Connection reset|校验失败|搜索失效|目录失效|正文内容为空).*$"
)


def _looks_like_js_helpers(comment: str) -> bool:
    return bool(_JS_HINT.search(comment or ""))


def scrub_comment(comment: str) -> str:
    """Strip check Error prefixes; never delete JS helper body."""
    if not comment:
        return ""
    # Always drop App-style Error blocks first (even when JS helpers follow).
    cleaned = _ERROR_BLOCK.sub("", comment)
    if not _looks_like_js_helpers(cleaned):
        cleaned = _ERROR_LINE.sub("", cleaned)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned).strip()
    return cleaned


def scrub_group(group: str) -> str:
    """Drop invalid groups like App getInvalidGroupNames: '失效' in name or 校验超时."""
    if not group:
        return ""
    parts = [p.strip() for p in group.replace("，", ",").split(",") if p.strip()]
    kept = [p for p in parts if "失效" not in p and p != "校验超时"]
    return ",".join(kept)

def clear_urls(con: sqlite3.Connection, urls: list[str], *, dry_run: bool) -> list[dict]:
    out: list[dict] = []
    for url in urls:
        row = con.execute(
            "SELECT bookSourceUrl, bookSourceName, bookSourceGroup, bookSourceComment "
            "FROM book_sources WHERE bookSourceUrl = ?",
            (url,),
        ).fetchone()
        if not row:
            out.append({"url": url, "ok": False, "reason": "missing"})
            continue
        _u, name, group, comment = row
        new_c = scrub_comment(comment or "")
        new_g = scrub_group(group or "")
        changed = (new_c != (comment or "")) or (new_g != (group or ""))
        js_kept = _looks_like_js_helpers(comment or "") and _looks_like_js_helpers(new_c)
        rec = {
            "url": url,
            "name": name,
            "ok": True,
            "changed": changed,
            "js_helpers_kept": js_kept,
            "group_before": group,
            "group_after": new_g,
            "comment_before": (comment or "")[:120],
            "comment_after": new_c[:120],
        }
        if changed and not dry_run:
            con.execute(
                "UPDATE book_sources SET bookSourceGroup = ?, bookSourceComment = ? "
                "WHERE bookSourceUrl = ?",
                (new_g, new_c, url),
            )
        out.append(rec)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", action="append", default=[])
    ap.add_argument("--urls-file", type=Path)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--json-out", type=Path)
    args = ap.parse_args()
    urls: list[str] = list(args.url)
    if args.urls_file:
        urls.extend(
            ln.strip()
            for ln in args.urls_file.read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.strip().startswith("#")
        )
    # dedupe preserve order
    seen: set[str] = set()
    urls = [u for u in urls if not (u in seen or seen.add(u))]
    if not urls:
        print("no urls", file=sys.stderr)
        return 2

    report: list[dict] = []

    if args.dry_run:
        work = ROOT / "temp" / "shelf_restore" / "queue" / "mutate_work.db"
        if not work.exists():
            print("dry-run needs mutate_work.db; run without --dry-run to pull", file=sys.stderr)
            return 2
        con = sqlite3.connect(str(work))
        try:
            report = clear_urls(con, urls, dry_run=True)
        finally:
            con.close()
    else:
        with with_legado_db(require_urls=urls) as con:
            report = clear_urls(con, urls, dry_run=False)

    changed = sum(1 for r in report if r.get("changed"))
    missing = sum(1 for r in report if not r.get("ok"))
    print(json.dumps({"urls": len(urls), "changed": changed, "missing": missing, "dry_run": args.dry_run}, ensure_ascii=False))
    for r in report:
        flag = "CHANGE" if r.get("changed") else ("KEEP" if r.get("ok") else "MISS")
        print(f"{flag}\t{r.get('url')}\t{r.get('name','')}")
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
