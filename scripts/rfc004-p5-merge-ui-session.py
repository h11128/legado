#!/usr/bin/env python3
"""RFC-004 P5 merge UI smoke: dual fixtures → tap chapter chip → merge dialog → row detail.

Prereq: debug APK; fixtures A/B on device:
  legado-fixture://review-overlay
  legado-fixture://review-overlay-b
"""
from __future__ import annotations

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
FIX_A = "legado-fixture://review-overlay"
FIX_B = "legado-fixture://review-overlay-b"
OUT = ROOT / "temp" / "rfc004_ui"


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


def insert_binding(cur, cols, **kw) -> None:
    base = {
        "contentBookUrl": kw["book_url"],
        "contentName": kw["name"],
        "contentAuthor": kw.get("author") or "",
        "contentOrigin": kw["origin"],
        "providerSourceUrl": kw["provider"],
        "providerBookUrl": kw["provider_book"],
        "providerName": kw["provider_name"],
        "providerAuthor": kw.get("provider_author") or "",
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
    for y in range(70, min(260, h)):
        c = 0
        for x in range(150, min(1000, w)):
            r, g, b = pix[x, y]
            if r < 80 and g < 80 and b < 80:
                c += 1
        row_dark[y] = c
    title_rows = [y for y in range(70, min(260, h)) if row_dark[y] > 80]
    if not title_rows:
        return 965, 171
    y0, y1 = min(title_rows), max(title_rows)
    xs = []
    for y in range(y0, y1 + 1):
        for x in range(150, min(1000, w)):
            r, g, b = pix[x, y]
            if r < 80 and g < 80 and b < 80:
                xs.append(x)
    title_end = max(xs) if xs else 900
    # badge sits on/near title end; prefer a bit left of absolute max ink
    cx = max(200, title_end - 15)
    cy = (y0 + y1) // 2
    return cx, cy


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    db = pull_db(OUT)
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
        insert_binding(
            cur,
            cols,
            book_url=book_url,
            name=name.strip(),
            author=author or "",
            origin=origin,
            provider=fix,
            provider_book=fix + "/book?name=" + urllib.parse.quote(name.strip()),
            provider_name=name.strip(),
            provider_author=author_p,
            sort_order=so,
        )
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    subprocess.check_call(["adb", "push", str(db), "/data/local/tmp/legado_rfc004.db"])
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

    merge_ok = False
    for i in range(15):
        time.sleep(2)
        log = adb("logcat", "-d").decode("utf-8", "replace")
        hits = [ln for ln in log.splitlines() if "ReviewOverlay merge providers=" in ln]
        if hits:
            print(hits[-1])
            if "providers=2" in hits[-1] and re.search(r"bucket=[1-9]", hits[-1]):
                merge_ok = True
                break
    if not merge_ok:
        print("FAIL: expected merge providers=2", file=sys.stderr)
        return 1

    time.sleep(1)
    png = shot("ui_title.png")
    dump_ui("ui_title.xml")
    x, y = find_badge_tap(png)
    print("tap badge", x, y)
    # avoid page-turn: small precise taps around badge
    opened = False
    for dx, dy in ((0, 0), (-10, 0), (10, 0), (0, -8), (0, 8), (-20, 5)):
        adb("shell", "input", "tap", str(x + dx), str(y + dy))
        time.sleep(1.0)
        xml = dump_ui("ui_after_badge.xml")
        ts = texts(xml)
        print(" after tap texts", [t for t in ts if any(k in t for k in ("评论", "源", "条", "夹具", "用户"))][:12])
        if any("本章评论" in t for t in ts) or any(re.search(r"\d+\s*源", t) for t in ts):
            opened = True
            shot("ui_merge_dialog.png")
            break
        # dismiss menu if opened
        if any(t in ("目录", "换源", "亮度", "下一章") for t in ts):
            adb("shell", "input", "keyevent", "4")
            time.sleep(0.4)

    if not opened:
        print("FAIL: merge dialog not opened", file=sys.stderr)
        shot("ui_fail_no_merge.png")
        return 1
    print("PASS: merge dialog")

    xml = dump_ui("ui_merge_open.xml")
    rows = []
    for m in re.finditer(
        r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
    ):
        text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        if not text.strip():
            continue
        # Skip toolbar / count chip
        if "本章评论" in text or re.search(r"共\s*\d+\s*条", text):
            continue
        if y1 < 1100:
            continue
        if y2 - y1 < 40:
            continue
        rows.append((text, x1, y1, x2, y2))
    badges = [t for t in texts(xml) if "RFC004" in t or "夹具B" in t]
    print("badges", badges[:8])
    print("rows", [(r[0][:40], r[1:]) for r in rows[:6]])
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
    before_merge = any("本章评论" in t for t in texts(xml))
    adb("shell", "input", "tap", str(cx), str(cy))
    time.sleep(1.5)
    xml2 = dump_ui("ui_after_row.xml")
    shot("ui_after_row.png")
    ts2 = texts(xml2)
    print("detail texts", ts2[:25])
    after_merge = any("本章评论" in t for t in ts2)
    detail_count = next((t for t in ts2 if re.search(r"共\s*\d+\s*条", t)), "")
    # A19: merge title gone; single-provider detail shows that provider's items only.
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
