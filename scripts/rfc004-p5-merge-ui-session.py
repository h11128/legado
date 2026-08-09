#!/usr/bin/env python3
"""RFC-004 P5 merge UI smoke (dual fixtures).

Uses shared scripts.lib.rfc004_device. For real-provider acceptance prefer:
  python scripts/rfc004-run-acceptance.py

Prereq: fixtures on device:
  legado-fixture://review-overlay
  legado-fixture://review-overlay-b
"""
from __future__ import annotations

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

FIX_A = "legado-fixture://review-overlay"
FIX_B = "legado-fixture://review-overlay-b"
OUT = ROOT / "temp" / "rfc004_ui" / "p5_merge"


def main() -> int:
    d.require_device()
    OUT.mkdir(parents=True, exist_ok=True)
    db = d.pull_db_wal(OUT, stop_app=True)
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    cols = {r[1] for r in cur.execute("PRAGMA table_info(book_review_bindings)")}
    for url in (FIX_A, FIX_B):
        if not cur.execute(
            "select 1 from book_sources where bookSourceUrl=?", (url,)
        ).fetchone():
            print(f"FAIL: missing {url}", file=sys.stderr)
            return 2
    row = cur.execute(
        "SELECT bookUrl, name, author, origin FROM books WHERE name LIKE ? LIMIT 1",
        ("%综漫：我同时穿越了99个世界%",),
    ).fetchone()
    if not row:
        print("FAIL: book not found", file=sys.stderr)
        return 2
    book_url, name, author, origin = row
    cands = cur.execute(
        "select title, `index` from chapters where bookUrl=? order by `index` limit 80",
        (book_url,),
    ).fetchall()
    chosen = None
    for t, i in cands:
        m = re.search(r"第\s*0*(\d+)\s*章", t or "")
        if m and 1 <= int(m.group(1)) <= 50:
            chosen = (t, i)
            break
    if not chosen:
        chosen = cands[1] if len(cands) > 1 else cands[0]
    title, idx = chosen
    cur.execute(
        "update books set durChapterIndex=?, durChapterTitle=?, durChapterPos=0 where bookUrl=?",
        (idx, title, book_url),
    )
    print("dur ->", title, idx)
    cur.execute("DELETE FROM book_review_bindings")
    for so, fix, author_p in (
        (0, FIX_A, "夹具作者"),
        (1, FIX_B, "夹具作者B"),
    ):
        d.insert_review_binding(
            cur,
            cols,
            book_url=book_url,
            name=name.strip(),
            author=author or "",
            origin=origin,
            provider_source=fix,
            provider_book=fix + "/book?name=" + urllib.parse.quote(name.strip()),
            provider_name=name.strip(),
            provider_author=author_p,
            sort_order=so,
        )
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    d.push_db_raw(db, "p5merge")
    d.logcat_clear()
    open_read_book(book_url, d.PKG)

    def merge_ok() -> bool:
        return any(
            "merge providers=" in ln and "providers=2" in ln and re.search(r"bucket=[1-9]", ln)
            for ln in d.overlay_lines()
        )

    if not d.wait_until(merge_ok, timeout_s=40, interval_s=1.5, label="p5-merge"):
        print("FAIL: expected merge providers=2", file=sys.stderr)
        return 1
    print(d.overlay_lines()[-1])

    time.sleep(0.8)
    png = d.shot(OUT / "ui_title.png")
    xml = d.dump_ui(OUT / "ui_title.xml")
    x, y = d.find_badge_tap(png, xml)
    print("tap badge", x, y)
    opened = False
    for dx, dy in ((0, 0), (-10, 0), (10, 0), (0, -8), (0, 8), (-20, 5)):
        d.tap_xy(x + dx, y + dy)
        time.sleep(0.8)
        xml = d.dump_ui(OUT / "ui_after_badge.xml")
        ts = d.ui_texts(xml)
        if any("本章评论" in t for t in ts) or any(re.search(r"\d+\s*源", t) for t in ts):
            opened = True
            d.shot(OUT / "ui_merge_dialog.png")
            break
        if any(t in ("目录", "换源", "亮度", "下一章") for t in ts):
            d.keyevent(4)
            time.sleep(0.3)
    if not opened:
        print("FAIL: merge dialog not opened", file=sys.stderr)
        d.shot(OUT / "ui_fail_no_merge.png")
        return 1
    print("PASS: merge dialog")

    xml = d.dump_ui(OUT / "ui_merge_open.xml")
    _, h = d.screen_size()
    mid = int(h * 0.45)
    rows = []
    for text, x1, y1, x2, y2 in d.ui_nodes(xml):
        if not text.strip():
            continue
        if "本章评论" in text or re.search(r"共\s*\d+\s*条", text):
            continue
        if y1 < mid:
            continue
        if y2 - y1 < 40:
            continue
        rows.append((text, x1, y1, x2, y2))
    if not rows:
        print("FAIL: no merge rows", file=sys.stderr)
        return 1
    pick = next(
        (r for r in rows if any(k in r[0] for k in ("章评夹具", "合集章评", "paraData="))),
        rows[0],
    )
    tx, x1, y1, x2, y2 = pick
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    print("tap row", tx[:50], cx, cy)
    before_merge = any("本章评论" in t for t in d.ui_texts(xml))
    d.tap_xy(cx, cy)
    time.sleep(1.2)
    xml2 = d.dump_ui(OUT / "ui_after_row.xml")
    d.shot(OUT / "ui_after_row.png")
    ts2 = d.ui_texts(xml2)
    after_merge = any("本章评论" in t for t in ts2)
    detail_count = next((t for t in ts2 if re.search(r"共\s*\d+\s*条", t)), "")
    detail_ok = (
        before_merge
        and not after_merge
        and bool(detail_count)
        and any(k in "".join(ts2) for k in ("章评夹具", "夹具用户", "合集章评", "paraData"))
    )
    if not detail_ok:
        print(
            "FAIL: row click did not open provider ReviewDetailDialog "
            f"(merge_after={after_merge}, count={detail_count!r})",
            file=sys.stderr,
        )
        return 1
    print("PASS: row → provider detail", detail_count)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
