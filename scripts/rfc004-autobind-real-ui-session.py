#!/usr/bin/env python3
"""RFC-004 seamless auto-bind — REAL providers only + screenshot acceptance.

Disables fixture review sources, clears bindings, opens 《诡秘之主》, waits for
silent auto-bind, then taps chapter-review chip and captures screenshots.

Usage:
  python scripts/rfc004-autobind-real-ui-session.py
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
OUT = ROOT / "temp" / "rfc004_ui" / "autobind_real"
PREFS = f"shared_prefs/{PKG}_preferences.xml"
BOOK_SUB = "诡秘之主"
FIXTURE_PREFIX = "legado-fixture://"


def adb(*args: str) -> bytes:
    return subprocess.check_output(["adb", *args])


def pull_db(dest: Path) -> Path:
    dest.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1)
    for name in ("legado.db", "legado.db-wal", "legado.db-shm"):
        (dest / name).write_bytes(
            adb("exec-out", "run-as", PKG, "cat", f"databases/{name}")
        )
    db = dest / "legado.db"
    con = sqlite3.connect(str(db))
    con.execute("PRAGMA wal_checkpoint(FULL)")
    con.commit()
    con.close()
    return db


def push_db(db: Path) -> None:
    subprocess.check_call(["adb", "push", str(db), "/data/local/tmp/legado_rfc004_abr.db"])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} sh -c 'cp /data/local/tmp/legado_rfc004_abr.db databases/legado.db "
            f"&& rm -f databases/legado.db-wal databases/legado.db-shm'",
        ]
    )


def ensure_pref_bool(xml: str, key: str, value: bool) -> str:
    bool_s = "true" if value else "false"
    if f'name="{key}"' in xml:
        return re.sub(
            rf'<boolean name="{re.escape(key)}" value="[^"]*"\s*/>',
            f'<boolean name="{key}" value="{bool_s}" />',
            xml,
            count=1,
        )
    return xml.replace(
        "</map>",
        f'    <boolean name="{key}" value="{bool_s}" />\n</map>',
        1,
    )


def push_prefs() -> None:
    raw = adb("exec-out", "run-as", PKG, "cat", PREFS).decode("utf-8", "replace")
    xml = ensure_pref_bool(raw, "reviewOverlayEnabled", True)
    xml = ensure_pref_bool(xml, "reviewOverlayAutoBind", True)
    xml = ensure_pref_bool(xml, "reviewOverlayMergeEnabled", True)
    p = OUT / "preferences.xml"
    p.write_text(xml, encoding="utf-8")
    subprocess.check_call(["adb", "push", str(p), "/data/local/tmp/legado_rfc004_prefs.xml"])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} sh -c 'cp /data/local/tmp/legado_rfc004_prefs.xml {PREFS}'",
        ]
    )


def dump_ui(name: str) -> str:
    subprocess.check_call(["adb", "shell", "uiautomator", "dump", "/sdcard/_abr_ui.xml"])
    dest = OUT / name
    subprocess.check_call(["adb", "pull", "/sdcard/_abr_ui.xml", str(dest)])
    return dest.read_text(encoding="utf-8", errors="replace")


def shot(name: str) -> Path:
    p = OUT / name
    p.write_bytes(adb("exec-out", "screencap", "-p"))
    print("shot", p)
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


def prepare_db(db: Path) -> tuple[str, list[tuple[str, str]]]:
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    # Disable ALL fixtures; enable only real #rfc004-review providers.
    cur.execute(
        "UPDATE book_sources SET enabled=0 WHERE bookSourceUrl LIKE ?",
        (FIXTURE_PREFIX + "%",),
    )
    disabled_fx = cur.execute(
        "SELECT bookSourceUrl FROM book_sources WHERE bookSourceUrl LIKE ?",
        (FIXTURE_PREFIX + "%",),
    ).fetchall()
    print("disabled fixtures:", [u[0] for u in disabled_fx])

    cur.execute(
        "UPDATE book_sources SET enabled=1 WHERE bookSourceUrl LIKE '%#rfc004-review' "
        "AND bookSourceUrl NOT LIKE ?",
        (FIXTURE_PREFIX + "%",),
    )
    real = cur.execute(
        "SELECT bookSourceUrl, bookSourceName FROM book_sources "
        "WHERE enabled=1 AND bookSourceUrl LIKE '%#rfc004-review' "
        "AND bookSourceUrl NOT LIKE ?",
        (FIXTURE_PREFIX + "%",),
    ).fetchall()
    print("enabled REAL review sources:", len(real))
    for u, n in real:
        print(" ", n, u)
    if not real:
        con.close()
        raise SystemExit("FAIL: no real review sources enabled")

    row = cur.execute(
        "SELECT bookUrl, name, author, origin, durChapterTitle "
        "FROM books WHERE name LIKE ? LIMIT 1",
        (f"%{BOOK_SUB}%",),
    ).fetchone()
    if not row:
        con.close()
        raise SystemExit(f"FAIL: book not found {BOOK_SUB}")
    book_url, name, author, origin, title = row
    print("book", name, author, "origin=", origin, "dur=", title)
    if FIXTURE_PREFIX in (origin or ""):
        print("FAIL: content origin is fixture — pick a real shelf book", file=sys.stderr)
        con.close()
        raise SystemExit(2)

    cur.execute("DELETE FROM book_review_bindings WHERE contentBookUrl=?", (book_url,))
    print("cleared bindings")
    # Prefer chapter 1 style title for 起点 align
    cands = cur.execute(
        "SELECT title, `index` FROM chapters WHERE bookUrl=? ORDER BY `index` LIMIT 30",
        (book_url,),
    ).fetchall()
    for t, i in cands:
        if t and ("第一章" in t or "第1章" in t or "绯红" in t):
            cur.execute(
                "UPDATE books SET durChapterIndex=?, durChapterTitle=? WHERE bookUrl=?",
                (i, t, book_url),
            )
            print("dur ->", t, i)
            break
    con.commit()
    cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    return book_url, real


def assert_no_fixture_bindings(book_url: str) -> list[tuple]:
    db = pull_db(OUT / "after")
    con = sqlite3.connect(str(db))
    rows = con.execute(
        "SELECT providerSourceUrl, bindMode, enabled FROM book_review_bindings "
        "WHERE contentBookUrl=? ORDER BY sortOrder",
        (book_url,),
    ).fetchall()
    con.close()
    print("bindings after UI:", rows)
    if not rows:
        raise SystemExit("FAIL: no bindings after auto-bind")
    if any(FIXTURE_PREFIX in (r[0] or "") for r in rows):
        raise SystemExit("FAIL: fixture binding present")
    if not any(r[1] == "auto" for r in rows):
        raise SystemExit("FAIL: expected bindMode=auto")
    return rows


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    push_prefs()
    db = pull_db(OUT)
    book_url, _real = prepare_db(db)
    push_db(db)
    subprocess.check_call(["adb", "logcat", "-c"])
    open_read_book(book_url, PKG)

    ok_log = False
    bind_line = ""
    out_log = ""
    for i in range(30):
        time.sleep(3)
        out_log = adb("logcat", "-d").decode("utf-8", "replace")
        hits = [ln for ln in out_log.splitlines() if "ReviewOverlay" in ln]
        print(f"t+{(i + 1) * 3}s hits={len(hits)}")
        for ln in hits[-6:]:
            print(ln)
        if any(FIXTURE_PREFIX in ln and "bind=" in ln for ln in hits):
            print("FAIL: fixture appeared in ReviewOverlay log", file=sys.stderr)
            (OUT / "session.log").write_text(out_log, encoding="utf-8")
            return 1
        for ln in reversed(hits):
            if "bucket=" in ln and "bucket=0" not in ln and "auto-bind" not in ln:
                bind_line = ln
                ok_log = True
                break
            if "merge providers=" in ln:
                bind_line = ln
                ok_log = True
                break
        if ok_log:
            break
        if any("auto-bind proposals=0" in ln for ln in hits) and i >= 5:
            print("NOTE: proposals=0 — may still have synthetic origin bind", file=sys.stderr)

    (OUT / "session.log").write_text(out_log, encoding="utf-8")
    if not ok_log:
        print("FAIL: no positive ReviewOverlay bucket/merge log", file=sys.stderr)
        shot("fail_no_bucket.png")
        return 1
    print("overlay log:", bind_line[bind_line.find("ReviewOverlay") :])

    time.sleep(1.5)
    png = shot("01_read_title.png")
    dump_ui("01_read_title.xml")
    x, y = find_badge_tap(png)
    print("tap badge", x, y)

    opened = False
    for dx, dy in ((0, 0), (-12, 0), (12, 0), (0, -10), (0, 10), (-25, 5), (20, 8)):
        adb("shell", "input", "tap", str(x + dx), str(y + dy))
        time.sleep(1.3)
        xml = dump_ui("02_after_badge.xml")
        ts = texts(xml)
        interesting = [
            t
            for t in ts
            if any(k in t for k in ("评论", "源", "条", "本章说", "书吧", "起点", "QQ", "精华"))
        ]
        print(" texts", interesting[:15])
        if any("夹具" in t for t in ts):
            print("FAIL: fixture text in UI", file=sys.stderr)
            shot("fail_fixture_ui.png")
            return 1
        if any("本章评论" in t for t in ts) or any(re.search(r"共\s*\d+\s*条", t) for t in ts):
            opened = True
            shot("02_merge_or_detail.png")
            break
        if any(t in ("目录", "换源", "亮度") for t in ts):
            adb("shell", "input", "keyevent", "4")
            time.sleep(0.4)

    if not opened:
        shot("fail_no_dialog.png")
        print("FAIL: no review dialog after badge tap", file=sys.stderr)
        return 1

    xml = dump_ui("03_dialog.xml")
    shot("03_dialog.png")
    ts = texts(xml)
    body = "\n".join(ts)
    if "夹具" in body:
        print("FAIL: fixture strings in dialog", file=sys.stderr)
        return 1
    content_rows = [
        t
        for t in ts
        if t
        and "本章评论" not in t
        and not re.search(r"共\s*\d+\s*条", t)
        and t not in ("诡秘之主", "第一章 绯红")
        and "夹具" not in t
        and len(t) >= 2
    ]
    print("content_rows", content_rows[:12])
    if len(content_rows) < 1:
        print("FAIL: dialog empty of real comments", file=sys.stderr)
        return 1

    # Merge → row detail when multi-source title present
    if any("本章评论" in t for t in ts):
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
            pick = max(rows, key=lambda r: len(r[0]))
            cx, cy = (pick[1] + pick[3]) // 2, (pick[2] + pick[4]) // 2
            print("tap row", pick[0][:60], cx, cy)
            adb("shell", "input", "tap", str(cx), str(cy))
            time.sleep(1.5)
            dump_ui("04_row_detail.xml")
            shot("04_row_detail.png")
            ts2 = texts((OUT / "04_row_detail.xml").read_text(encoding="utf-8", errors="replace"))
            print("row detail", ts2[:20])
            if any("夹具" in t for t in ts2):
                print("FAIL: fixture in detail", file=sys.stderr)
                return 1

    bindings = assert_no_fixture_bindings(book_url)
    (OUT / "RESULT.txt").write_text(
        "\n".join(
            [
                "PASS: real-only silent auto-bind + UI",
                f"bookUrl={book_url}",
                f"overlay={bind_line[bind_line.find('ReviewOverlay'):] if 'ReviewOverlay' in bind_line else bind_line}",
                f"bindings={bindings}",
                f"shots={[p.name for p in sorted(OUT.glob('*.png'))]}",
            ]
        ),
        encoding="utf-8",
    )
    print("PASS: real-only silent auto-bind + screenshot acceptance")
    print("evidence dir:", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
