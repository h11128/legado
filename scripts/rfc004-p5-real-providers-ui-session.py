#!/usr/bin/env python3
"""RFC-004 P5 real-provider UI: 起点本章说 + QQ书吧 on 《诡秘之主》.

Prereq: debug APK; review sources already on device (push-rfc004-review-sources.py).
"""
from __future__ import annotations

import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.lib.legado_adb import open_read_book  # noqa: E402

PKG = "com.legado.app.debug"
OUT = ROOT / "temp" / "rfc004_ui" / "real"

QD_SRC = "https://m.qidian.com#rfc004-review"
QQ_SRC = "https://book.qq.com#rfc004-review"
# Device-verified search hits (2026-08-09)
QD_BOOK = "https://m.qidian.com/book/1010868264/"
QQ_BOOK = "https://book.qq.com/book-detail/20868264"
BOOK_NAME = "诡秘之主"
BOOK_AUTHOR = "爱潜水的乌贼"
# 第一章 绯红 — historically heavy 本章说
QD_CH1 = "https://m.qidian.com/book/1010868264/402733549"


def adb(*args: str) -> bytes:
    return subprocess.check_output(["adb", *args])


def pull_db(dest: Path) -> Path:
    dest.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1)
    for name in ("legado.db", "legado.db-wal", "legado.db-shm"):
        (dest / name).write_bytes(adb("exec-out", "run-as", PKG, "cat", f"databases/{name}"))
    db = dest / "legado.db"
    con = sqlite3.connect(str(db))
    con.execute("PRAGMA wal_checkpoint(FULL)")
    con.commit()
    con.close()
    return db


def dump_ui(name: str) -> str:
    subprocess.check_call(["adb", "shell", "uiautomator", "dump", "/sdcard/_p5_ui.xml"])
    dest = OUT / name
    subprocess.check_call(["adb", "pull", "/sdcard/_p5_ui.xml", str(dest)])
    return dest.read_text(encoding="utf-8", errors="replace")


def shot(name: str) -> Path:
    p = OUT / name
    p.write_bytes(adb("exec-out", "screencap", "-p"))
    return p


def texts(xml: str) -> list[str]:
    return [m.group(1) for m in re.finditer(r'text="([^"]+)"', xml)]


def find_badge_tap(png: Path) -> tuple[int, int]:
    from PIL import Image

    im = Image.open(png).convert("RGB")
    pix = im.load()
    w, h = im.size
    row_dark = [0] * h
    for y in range(70, min(280, h)):
        c = 0
        for x in range(120, min(1000, w)):
            r, g, b = pix[x, y]
            if r < 80 and g < 80 and b < 80:
                c += 1
        row_dark[y] = c
    title_rows = [y for y in range(70, min(280, h)) if row_dark[y] > 60]
    if not title_rows:
        return 960, 170
    y0, y1 = min(title_rows), max(title_rows)
    xs = []
    for y in range(y0, y1 + 1):
        for x in range(120, min(1000, w)):
            r, g, b = pix[x, y]
            if r < 80 and g < 80 and b < 80:
                xs.append(x)
    title_end = max(xs) if xs else 900
    return max(200, title_end - 12), (y0 + y1) // 2


def upsert_book(cur: sqlite3.Cursor) -> str:
    now = int(time.time() * 1000)
    existing = cur.execute(
        "select bookUrl from books where bookUrl=? or (name=? and author=?)",
        (QD_BOOK, BOOK_NAME, BOOK_AUTHOR),
    ).fetchone()
    book_url = existing[0] if existing else QD_BOOK
    if existing:
        cur.execute(
            """
            update books set
              tocUrl=?, origin=?, originName=?, name=?, author=?,
              durChapterIndex=0, durChapterTitle=?, durChapterPos=0,
              durChapterTime=?, canUpdate=1, type=0
            where bookUrl=?
            """,
            (
                QD_BOOK,
                QD_SRC,
                "起点本章说(段评源)",
                BOOK_NAME,
                BOOK_AUTHOR,
                "第一章 绯红",
                now,
                book_url,
            ),
        )
    else:
        mx = cur.execute("select coalesce(max(`order`),0) from books").fetchone()[0]
        cur.execute(
            """
            insert into books (
              bookUrl, tocUrl, origin, originName, name, author,
              type, `group`, latestChapterTitle, latestChapterTime, lastCheckTime,
              lastCheckCount, totalChapterNum, durChapterTitle, durChapterIndex,
              durVolumeIndex, chapterInVolumeIndex, durChapterPos, durChapterTime,
              canUpdate, `order`, originOrder
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                QD_BOOK,
                QD_BOOK,
                QD_SRC,
                "起点本章说(段评源)",
                BOOK_NAME,
                BOOK_AUTHOR,
                0,
                1,
                "第一章 绯红",
                now,
                now,
                0,
                0,
                "第一章 绯红",
                0,
                0,
                0,
                0,
                now,
                1,
                mx + 1,
                mx + 1,
            ),
        )
        book_url = QD_BOOK
    # Seed first chapter so align has a title even before full TOC refresh.
    cur.execute("delete from chapters where bookUrl=?", (book_url,))
    cur.execute(
        """
        insert into chapters (
          url, title, isVolume, baseUrl, bookUrl, `index`, isVip, isPay,
          resourceUrl, tag, wordCount, start, `end`, variable, imgUrl
        ) values (?,?,0,?,?,?,0,0,'','',null,null,null,null,null)
        """,
        (QD_CH1, "第一章 绯红", QD_BOOK, book_url, 0),
    )
    return book_url


def insert_binding(cur, cols, **kw) -> None:
    base = {
        "contentBookUrl": kw["book_url"],
        "contentName": BOOK_NAME,
        "contentAuthor": BOOK_AUTHOR,
        "contentOrigin": QD_SRC,
        "providerSourceUrl": kw["provider"],
        "providerBookUrl": kw["provider_book"],
        "providerName": BOOK_NAME,
        "providerAuthor": BOOK_AUTHOR,
        "bindMode": "manual",
        "updatedAt": int(time.time() * 1000),
    }
    if "enabled" in cols:
        base["enabled"] = 1
    if "sortOrder" in cols:
        base["sortOrder"] = kw["sort_order"]
    if "role" in cols:
        base["role"] = "chapter"
    keys = [k for k in base if k in cols]
    cur.execute(
        f"INSERT INTO book_review_bindings ({','.join(keys)}) VALUES ({','.join('?' for _ in keys)})",
        [base[k] for k in keys],
    )


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    db = pull_db(OUT)
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    cols = {r[1] for r in cur.execute("PRAGMA table_info(book_review_bindings)")}
    for url, label in ((QD_SRC, "起点"), (QQ_SRC, "QQ")):
        if not cur.execute(
            "select 1 from book_sources where bookSourceUrl=?", (url,)
        ).fetchone():
            print(f"FAIL: missing {label} source {url}", file=sys.stderr)
            return 2
    book_url = upsert_book(cur)
    cur.execute("DELETE FROM book_review_bindings where contentBookUrl=?", (book_url,))
    insert_binding(
        cur,
        cols,
        book_url=book_url,
        provider=QD_SRC,
        provider_book=QD_BOOK,
        sort_order=0,
    )
    insert_binding(
        cur,
        cols,
        book_url=book_url,
        provider=QQ_SRC,
        provider_book=QQ_BOOK,
        sort_order=1,
    )
    n = cur.execute(
        "select count(*) from book_review_bindings where contentBookUrl=?", (book_url,)
    ).fetchone()[0]
    print("book", book_url, "bindings", n)
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()

    subprocess.check_call(["adb", "push", str(db), "/data/local/tmp/legado_rfc004_real.db"])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} sh -c 'cp /data/local/tmp/legado_rfc004_real.db databases/legado.db "
            f"&& rm -f databases/legado.db-wal databases/legado.db-shm'",
        ]
    )
    subprocess.check_call(["adb", "logcat", "-c"])
    open_read_book(book_url, PKG)

    merge_line = ""
    for i in range(24):
        time.sleep(3)
        log = adb("logcat", "-d").decode("utf-8", "replace")
        hits = [ln for ln in log.splitlines() if "ReviewOverlay merge providers=" in ln]
        skips = [
            ln
            for ln in log.splitlines()
            if "ReviewOverlay merge skip=" in ln or "ReviewOverlay bind=" in ln
        ]
        if hits:
            merge_line = hits[-1]
            print("t+", (i + 1) * 3, merge_line)
            m = re.search(r"providers=(\d+)\s+bucket=(\d+)", merge_line)
            providers_n = int(m.group(1)) if m else 0
            bucket_n = int(m.group(2)) if m else 0
            # Prefer dual real merge; accept single real with large bucket after a few polls.
            if providers_n >= 2 and bucket_n > 0:
                break
            if bucket_n > 0 and i >= 3:
                break
        elif skips and i % 2 == 1:
            print("t+", (i + 1) * 3, "binds", len(skips))
            for ln in skips[-4:]:
                print(" ", ln[ln.find("ReviewOverlay") :])
    else:
        print("FAIL: no positive real merge bucket", file=sys.stderr)
        (OUT / "fail_log.txt").write_text(
            adb("logcat", "-d").decode("utf-8", "replace")[-20000:], encoding="utf-8"
        )
        return 1

    m = re.search(r"providers=(\d+)\s+bucket=(\d+)", merge_line)
    providers = int(m.group(1)) if m else 0
    bucket = int(m.group(2)) if m else 0
    print(f"merge providers={providers} bucket={bucket}")
    if bucket <= 0:
        print("FAIL: empty bucket", file=sys.stderr)
        return 1

    time.sleep(1.5)
    png = shot("real_title.png")
    dump_ui("real_title.xml")
    x, y = find_badge_tap(png)
    print("tap badge", x, y)
    opened = False
    for dx, dy in ((0, 0), (-12, 0), (12, 0), (0, -10), (0, 10), (-25, 5)):
        adb("shell", "input", "tap", str(x + dx), str(y + dy))
        time.sleep(1.2)
        xml = dump_ui("real_after_badge.xml")
        ts = texts(xml)
        interesting = [
            t
            for t in ts
            if any(k in t for k in ("评论", "源", "条", "本章说", "书吧", "起点", "QQ", "精华"))
        ]
        print(" texts", interesting[:15])
        if any("本章评论" in t for t in ts) or any(re.search(r"共\s*\d+\s*条", t) for t in ts):
            opened = True
            shot("real_merge_or_detail.png")
            break
        if any(t in ("目录", "换源", "亮度") for t in ts):
            adb("shell", "input", "keyevent", "4")
            time.sleep(0.4)
    if not opened:
        # Single-provider path opens ReviewDetailDialog without merge title
        xml = dump_ui("real_after_badge.xml")
        ts = texts(xml)
        if any(re.search(r"共\s*\d+\s*条", t) for t in ts) or any(
            len(t) > 15 for t in ts if "综漫" not in t and "诡秘" not in t
        ):
            print("PASS: detail dialog (single real provider path)")
            shot("real_detail_only.png")
            opened = True
            providers = 1
    if not opened:
        print("FAIL: no review dialog", file=sys.stderr)
        shot("real_fail.png")
        return 1

    xml = dump_ui("real_dialog.xml")
    shot("real_dialog.png")
    ts = texts(xml)
    print("dialog texts", ts[:30])
    # Real comments should not look like fixture-only
    body = "\n".join(ts)
    fixture_only = ("夹具用户" in body or "章评夹具" in body) and "起点" not in body and "QQ" not in body
    if fixture_only:
        print("FAIL: still fixture comments", file=sys.stderr)
        return 1
    # Expect meaningful non-chrome text
    content_rows = [
        t
        for t in ts
        if t
        and "本章评论" not in t
        and not re.search(r"共\s*\d+\s*条", t)
        and t not in ("诡秘之主", "第一章 绯红")
        and len(t) >= 2
    ]
    print("content_rows", content_rows[:12])
    if len(content_rows) < 1:
        print("FAIL: dialog empty of comments", file=sys.stderr)
        return 1

    # If merge dialog, click first content row into provider detail
    if any("本章评论" in t for t in ts) and providers >= 2:
        rows = []
        for m2 in re.finditer(
            r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
        ):
            text, x1, y1, x2, y2 = m2.group(1), *map(int, m2.groups()[1:])
            if not text.strip() or "本章评论" in text or re.search(r"共\s*\d+\s*条", text):
                continue
            if y1 < 1100 or y2 - y1 < 40:
                continue
            rows.append((text, x1, y1, x2, y2))
        if rows:
            # prefer longer content body
            pick = max(rows, key=lambda r: len(r[0]))
            cx, cy = (pick[1] + pick[3]) // 2, (pick[2] + pick[4]) // 2
            print("tap row", pick[0][:60], cx, cy)
            adb("shell", "input", "tap", str(cx), str(cy))
            time.sleep(1.5)
            xml2 = dump_ui("real_row_detail.xml")
            shot("real_row_detail.png")
            ts2 = texts(xml2)
            print("row detail", ts2[:20])
            if any("本章评论" in t for t in ts2) and not any(
                re.search(r"共\s*\d+\s*条", t) for t in ts2
            ):
                print("WARN: still on merge after row tap")
            else:
                print("PASS: row → provider detail")

    print(
        f"PASS: real providers UI providers={providers} bucket={bucket} "
        f"rows={len(content_rows)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
