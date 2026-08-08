#!/usr/bin/env python3
"""Batch change-source for shelf books that need remapping (MCP debug search).

Prefer: python scripts/shelf-restore-change-source.py
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
from lib.legado_adb import (  # noqa: E402
    DEFAULT_PKG,
    pull_legado_db,
    push_legado_db,
    shelf_restore_queue,
)
from lib.legado_mcp import LegadoMcp  # noqa: E402

ROOT = shelf_restore_queue()
PKG = DEFAULT_PKG
os.environ["MSYS_NO_PATHCONV"] = "1"

SEARCH_SOURCES = [
    ("http://api.lemiyigou.com", "猫眼看书"),
    ("DragonQuestQBall", "●QB男生"),
    ("DragonQuestQBkd1", "🎉 QB女生"),
    ("https://bookshelf.html5.qq.com/", "松鹤阅读"),
    ("https://trxs.cc", "同人小说trxs.cc"),
]


def normalize_title(s: str) -> str:
    s = s or ""
    s = re.sub(r"[（(].*?[）)]", "", s)
    s = re.sub(r"[^\w\u4e00-\u9fff]+", "", s)
    return s.lower()


MARKER_RE = re.compile(
    r"┌获取(书名|作者|详情页链接)\n(?:\[[^\]]+\] )?└([^\n]*)"
)


def parse_search_hits(log: str) -> list[dict]:
    part = log.split("开始解析详情页")[0]
    hits: list[dict] = []
    cur: dict = {}
    for kind, val in MARKER_RE.findall(part):
        val = val.strip()
        if kind == "书名":
            if cur.get("name") and cur.get("bookUrl"):
                hits.append(cur)
            cur = {"name": val, "author": "", "bookUrl": ""}
        elif kind == "作者":
            cur["author"] = val
        elif kind == "详情页链接":
            cur["bookUrl"] = val
    if cur.get("name") and cur.get("bookUrl"):
        hits.append(cur)
    return hits


def pick_hit(hits: list[dict], name: str, author: str) -> dict | None:
    nt = normalize_title(name)
    na = normalize_title(author)
    if not nt:
        return None
    exact = []
    fuzzy = []
    for h in hits:
        hn = normalize_title(h["name"])
        ha = normalize_title(h.get("author") or "")
        if hn == nt:
            if not na or not ha or ha == na:
                exact.append(h)
            else:
                fuzzy.append(h)
        elif hn and (nt in hn or hn in nt):
            shorter, longer = (hn, nt) if len(hn) <= len(nt) else (nt, hn)
            if len(shorter) >= 4 and len(shorter) / max(len(longer), 1) >= 0.55:
                fuzzy.append(h)
    return (exact or fuzzy or [None])[0]


def score_readable(log: str) -> dict:
    toc = 0
    m = re.search(r"列表大小:(\d+)", log)
    if m:
        toc = int(m.group(1))
    content = ""
    m = re.search(r"┌获取正文内容\n└([\s\S]*?)(?:\n\[|$)", log)
    if m:
        content = (m.group(1) or "").strip()
    ok = toc > 0 and len(content) > 50
    return {"ok": ok, "toc": toc, "content_len": len(content)}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--limit", type=int, default=450)
    ap.add_argument("--max-seconds", type=int, default=35 * 60)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument(
        "--no-push",
        action="store_true",
        help="do not push DB to phone after changes",
    )
    args = ap.parse_args()

    sample = ROOT / "debug_lemi_hypnosis.txt"
    if sample.exists():
        hits = parse_search_hits(sample.read_text(encoding="utf-8"))
        print("parser selfcheck hits", len(hits), hits[:2])
        assert hits and hits[0]["name"] == "催眠手机", hits[:1]

    db = ROOT / "live_chg2.db"
    pull_legado_db(db, pkg=PKG, min_bytes=1000)
    con = sqlite3.connect(str(db))
    rows = con.execute(
        """
        select name, author, origin, originName, bookUrl
        from books
        where intro like '%needs_manual_reshelve%'
        order by length(name) asc
        """
    ).fetchall()
    seen = set()
    queue = []
    for r in rows:
        key = (r[0] or "", r[1] or "")
        if key in seen:
            continue
        seen.add(key)
        queue.append(r)
        if len(queue) >= args.limit:
            break
    print("queue", len(queue), "of manual", len(rows), "dry_run", args.dry_run)

    def wake(_e: Exception, _i: int) -> None:
        import subprocess

        subprocess.call(
            [
                "adb",
                "shell",
                "monkey",
                "-p",
                PKG,
                "-c",
                "android.intent.category.LAUNCHER",
                "1",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    mcp = LegadoMcp(connect_retries=20, on_retry=wake)
    existing = {r[0] for r in con.execute("select bookUrl from books")}
    changed = []
    failed = []
    t0 = time.time()

    for idx, (name, author, origin, origin_name, book_url) in enumerate(queue):
        if time.time() - t0 > args.max_seconds:
            print("time budget stop", idx)
            break
        search_key = re.split(r"[（(]", (name or "").strip())[0].strip() or (name or "")
        search_key = search_key[:40]
        if not search_key:
            failed.append({"name": name, "reason": "empty_name"})
            continue

        hit = None
        used = None
        raw_sizes: dict = {}
        for src_url, src_name in SEARCH_SOURCES:
            try:
                log = mcp.debug_source(src_url, search_key, timeout_sec=35)
                m = re.search(r"列表大小:(\d+)", log.split("开始解析详情页")[0])
                raw_sizes[src_url] = int(m.group(1)) if m else -1
                hits = parse_search_hits(log)
                pick = pick_hit(hits, name or "", author or "")
                if not pick:
                    continue
                # Prove TOC+content on the hit bookUrl before accepting.
                prove = mcp.debug_source(src_url, pick["bookUrl"], timeout_sec=45)
                sc = score_readable(prove)
                if not sc.get("ok"):
                    raw_sizes[src_url] = f"hit_not_readable:{sc}"
                    continue
                hit = {**pick, **sc}
                used = (src_url, src_name)
                break
            except Exception as e:
                raw_sizes[src_url] = f"err:{e}"
                continue

        if not hit or not used:
            failed.append(
                {
                    "name": name,
                    "author": author,
                    "reason": "no_hit",
                    "sizes": raw_sizes,
                }
            )
            print(f"[{idx}] MISS {name} sizes={raw_sizes}")
            continue

        new_bu = hit["bookUrl"]
        if new_bu in existing and new_bu != book_url:
            failed.append(
                {"name": name, "reason": "bookUrl_conflict", "hit": new_bu}
            )
            print(f"[{idx}] CONFLICT {name}")
            continue

        if args.dry_run:
            changed.append(
                {
                    "name": name,
                    "dry_run": True,
                    "to_origin": used[0],
                    "to_bookUrl": new_bu,
                    "toc": hit.get("toc"),
                    "content_len": hit.get("content_len"),
                }
            )
            print(f"[{idx}] DRY {name} -> {hit['name']} @ {used[0]}")
            continue

        intro = con.execute(
            "select intro from books where bookUrl=?", (book_url,)
        ).fetchone()
        intro = (intro[0] if intro else "") or ""
        intro = intro.replace("[needs_manual_reshelve] ", "").replace(
            "[needs_manual_reshelve]", ""
        )
        con.execute(
            """
            update books
            set origin=?, originName=?, bookUrl=?, intro=?, tocUrl=''
            where bookUrl=?
            """,
            (used[0], used[1], new_bu, intro, book_url),
        )
        existing.discard(book_url)
        existing.add(new_bu)
        changed.append(
            {
                "name": name,
                "author": author,
                "from_origin": origin,
                "to_origin": used[0],
                "to_bookUrl": new_bu,
                "hit_name": hit["name"],
                "hit_author": hit.get("author"),
                "toc": hit.get("toc"),
                "content_len": hit.get("content_len"),
            }
        )
        print(f"[{idx}] OK {name} -> {hit['name']} @ {used[0]}")
        if idx % 10 == 9:
            con.commit()

    if not args.dry_run:
        con.commit()
    miss = con.execute(
        """
        select count(*) from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchone()[0]
    manual = con.execute(
        "select count(*) from books where intro like '%needs_manual_reshelve%'"
    ).fetchone()[0]
    print(
        "changed",
        len(changed),
        "failed",
        len(failed),
        "missing",
        miss,
        "manual_left",
        manual,
        "elapsed",
        round(time.time() - t0, 1),
    )
    (ROOT / "real_change_source_report_v2.json").write_text(
        json.dumps(
            {
                "changed": changed,
                "failed": failed,
                "missing": miss,
                "manual_left": manual,
                "elapsed_s": round(time.time() - t0, 1),
                "dry_run": args.dry_run,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    con.close()
    if not args.dry_run and not args.no_push and changed:
        push_legado_db(db, pkg=PKG)
        print("pushed")
    elif args.dry_run:
        print("dry-run: no push")
    else:
        print("skip push")


if __name__ == "__main__":
    main()
