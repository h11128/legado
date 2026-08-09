#!/usr/bin/env python3
"""RFC-004 seamless auto-bind — FULL screenshot acceptance (real providers only).

Gates (all must pass with PNG under out-dir):
  G1 fixtures disabled / only real #rfc004-review enabled
  G2 silent auto-bind ≥2 real bindMode=auto, no fixture rows
  G3 positive ReviewOverlay bucket/merge log (no fixture URL)
  G4 read page screenshot (title + review chip)
  G5 comment dialog screenshot with real comments (no 夹具)
  G6 multi-provider merge UI or multi-bind evidence + dialog shot
  G7 paragraph authority attempt logged (ContentSplitVerified for 起点)
  G8 book info「段评源」shows bound count/name screenshot
  G9 negative: autoBind=false → clear → open read → still 0 bindings + screenshot

Usage:
  python scripts/rfc004-autobind-acceptance-ui.py
"""
from __future__ import annotations

import json
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
OUT = ROOT / "temp" / "rfc004_ui" / "acceptance"
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


def push_db(db: Path, tag: str = "acc") -> None:
    remote = f"/data/local/tmp/legado_rfc004_{tag}.db"
    subprocess.check_call(["adb", "push", str(db), remote])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} sh -c 'cp {remote} databases/legado.db "
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


def push_prefs(**flags: bool) -> None:
    raw = adb("exec-out", "run-as", PKG, "cat", PREFS).decode("utf-8", "replace")
    xml = raw
    defaults = {
        "reviewOverlayEnabled": True,
        "reviewOverlayAutoBind": True,
        "reviewOverlayMergeEnabled": True,
        "reviewOverlayAllowParagraphIcons": True,
    }
    defaults.update(flags)
    for k, v in defaults.items():
        xml = ensure_pref_bool(xml, k, v)
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
    subprocess.check_call(["adb", "shell", "uiautomator", "dump", "/sdcard/_acc_ui.xml"])
    dest = OUT / name
    subprocess.check_call(["adb", "pull", "/sdcard/_acc_ui.xml", str(dest)])
    return dest.read_text(encoding="utf-8", errors="replace")


def shot(name: str) -> Path:
    p = OUT / name
    p.write_bytes(adb("exec-out", "screencap", "-p"))
    print("shot", p.name)
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
        c = sum(
            1
            for x in range(120, min(1000, w))
            if pix[x, y][0] < 80 and pix[x, y][1] < 80 and pix[x, y][2] < 80
        )
        row_dark[y] = c
    title_rows = [y for y in range(70, min(280, h)) if row_dark[y] > 60]
    if not title_rows:
        return 960, 170
    y0, y1 = min(title_rows), max(title_rows)
    xs = [
        x
        for y in range(y0, y1 + 1)
        for x in range(120, min(1000, w))
        if pix[x, y][0] < 80 and pix[x, y][1] < 80 and pix[x, y][2] < 80
    ]
    title_end = max(xs) if xs else 900
    return max(200, title_end - 12), (y0 + y1) // 2


def prepare_positive(db: Path) -> str:
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    cur.execute(
        "UPDATE book_sources SET enabled=0 WHERE bookSourceUrl LIKE ?",
        (FIXTURE_PREFIX + "%",),
    )
    cur.execute(
        "UPDATE book_sources SET enabled=1 WHERE bookSourceUrl LIKE '%#rfc004-review' "
        "AND bookSourceUrl NOT LIKE ?",
        (FIXTURE_PREFIX + "%",),
    )
    real = cur.execute(
        "SELECT bookSourceUrl, bookSourceName FROM book_sources WHERE enabled=1 "
        "AND bookSourceUrl LIKE '%#rfc004-review' AND bookSourceUrl NOT LIKE ?",
        (FIXTURE_PREFIX + "%",),
    ).fetchall()
    print("G1 real sources", len(real), [n for _, n in real])
    if len(real) < 1:
        raise SystemExit("FAIL G1: no real review sources")
    row = cur.execute(
        "SELECT bookUrl, name, author, origin FROM books WHERE name LIKE ? LIMIT 1",
        (f"%{BOOK_SUB}%",),
    ).fetchone()
    if not row:
        raise SystemExit("FAIL: book missing")
    book_url, name, author, origin = row
    print("book", name, author, origin)
    if FIXTURE_PREFIX in (origin or ""):
        raise SystemExit("FAIL: fixture origin")
    cur.execute("DELETE FROM book_review_bindings WHERE contentBookUrl=?", (book_url,))
    for t, i in cur.execute(
        "SELECT title, `index` FROM chapters WHERE bookUrl=? ORDER BY `index` LIMIT 40",
        (book_url,),
    ):
        if t and ("第一章" in t or "绯红" in t):
            cur.execute(
                "UPDATE books SET durChapterIndex=?, durChapterTitle=? WHERE bookUrl=?",
                (i, t, book_url),
            )
            print("dur", t, i)
            break
    con.commit()
    cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    return book_url


def wait_overlay(timeout_s: int = 100) -> tuple[str, str]:
    deadline = time.time() + timeout_s
    out_log = ""
    while time.time() < deadline:
        time.sleep(3)
        out_log = adb("logcat", "-d").decode("utf-8", "replace")
        hits = [ln for ln in out_log.splitlines() if "ReviewOverlay" in ln]
        print(f"t+ hits={len(hits)}")
        for ln in hits[-5:]:
            print(" ", ln[ln.find("ReviewOverlay") :])
        if any(FIXTURE_PREFIX in ln and "bind=" in ln for ln in hits):
            raise SystemExit("FAIL G3: fixture in overlay log")
        for ln in reversed(hits):
            if "merge providers=" in ln or (
                "bucket=" in ln and "bucket=0" not in ln and "auto-bind" not in ln
            ):
                (OUT / "session_positive.log").write_text(out_log, encoding="utf-8")
                return ln, out_log
    (OUT / "session_positive.log").write_text(out_log, encoding="utf-8")
    raise SystemExit("FAIL G3: no positive bucket/merge")


def open_book_info_from_read(book_url: str) -> None:
    """BookInfoActivity is not exported — open via read menu title."""
    open_read_book(book_url, PKG)
    time.sleep(3)
    # Center tap shows read menu
    adb("shell", "input", "tap", "540", "1100")
    time.sleep(1.0)
    xml = dump_ui("G8_menu.xml")
    # Prefer clicking visible book title / 书籍信息
    targets = []
    for m in re.finditer(
        r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
    ):
        text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        if text in ("书籍信息",) or text == BOOK_SUB or "诡秘" in text:
            if y2 < 900:  # top menu chrome
                targets.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
    if not targets:
        # Fallback: top-center title area in menu
        adb("shell", "input", "tap", "540", "220")
    else:
        t = targets[0]
        print("tap info", t)
        adb("shell", "input", "tap", str(t[1]), str(t[2]))
    time.sleep(2.5)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    results: dict[str, str] = {}

    push_prefs(reviewOverlayAutoBind=True)
    db = pull_db(OUT / "pos")
    book_url = prepare_positive(db)
    results["G1"] = "fixtures off; real sources only"
    push_db(db, "pos")
    subprocess.check_call(["adb", "logcat", "-c"])
    open_read_book(book_url, PKG)
    best, log = wait_overlay()
    results["G3"] = best[best.find("ReviewOverlay") :]
    print("G3 OK", results["G3"])

    if "authority=ContentSplitVerified" in log:
        cov = [ln for ln in log.splitlines() if "coverage=" in ln and "ReviewOverlay" in ln]
        paras = [ln for ln in log.splitlines() if "paras=" in ln and "ReviewOverlay" in ln]
        results["G7"] = (paras or cov or ["ContentSplitVerified logged"])[-1]
        results["G7"] = results["G7"][results["G7"].find("ReviewOverlay") :]
    else:
        raise SystemExit("FAIL G7: expected ContentSplitVerified for 起点 authority")

    time.sleep(2)
    png = shot("G4_read_title.png")
    dump_ui("G4_read_title.xml")
    results["G4"] = "G4_read_title.png"

    x, y = find_badge_tap(png)
    opened = False
    merge_ui = False
    for dx, dy in ((0, 0), (-12, 0), (12, 0), (0, -10), (0, 10), (-25, 5), (20, 8)):
        adb("shell", "input", "tap", str(x + dx), str(y + dy))
        time.sleep(1.3)
        xml = dump_ui("G5_after_badge.xml")
        ts = texts(xml)
        if any("夹具" in t for t in ts):
            raise SystemExit("FAIL G5: fixture UI text")
        if any("本章评论" in t for t in ts):
            merge_ui = True
            opened = True
            break
        if any(re.search(r"共\s*\d+\s*条", t) for t in ts):
            opened = True
            break
        if any(t in ("目录", "换源", "亮度") for t in ts):
            adb("shell", "input", "keyevent", "4")
            time.sleep(0.4)
    if not opened:
        shot("FAIL_no_dialog.png")
        raise SystemExit("FAIL G5: no comment dialog")
    shot("G5_comment_dialog.png")
    results["G5"] = "G5_comment_dialog.png"
    xml = dump_ui("G5_dialog.xml")
    ts = texts(xml)
    if "夹具" in "\n".join(ts):
        raise SystemExit("FAIL G5: fixture in dialog")
    content_rows = [
        t
        for t in ts
        if t
        and "本章评论" not in t
        and not re.search(r"共\s*\d+\s*条", t)
        and len(t) >= 2
        and "夹具" not in t
    ]
    if len(content_rows) < 1:
        raise SystemExit("FAIL G5: empty comments")
    print("G5 comments sample", content_rows[:8])

    if merge_ui:
        # Capture merge UI BEFORE force-stop (same frame as G5 dialog).
        shot("G6_merge_dialog.png")
        results["G6"] = "G6_merge_dialog.png"
        if not any("本章评论" in t for t in ts) and not any(
            "起点" in t or "QQ" in t or "段评源" in t for t in ts
        ):
            raise SystemExit("FAIL G6: merge_ui set but no merge chrome in dialog texts")
    else:
        results["G6"] = "pending multi-bind check"

    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1)
    db2 = pull_db(OUT / "pos_after")
    con = sqlite3.connect(str(db2))
    rows = con.execute(
        "SELECT providerSourceUrl, bindMode, enabled FROM book_review_bindings "
        "WHERE contentBookUrl=? ORDER BY sortOrder",
        (book_url,),
    ).fetchall()
    con.close()
    print("G2 bindings", rows)
    if not rows or any(FIXTURE_PREFIX in r[0] for r in rows):
        raise SystemExit("FAIL G2: bad bindings")
    if not any(r[1] == "auto" for r in rows):
        raise SystemExit("FAIL G2: not auto")
    results["G2"] = f"{len(rows)} bindings: " + ", ".join(r[0] for r in rows)
    if len(rows) < 2:
        raise SystemExit(
            f"FAIL G2/G6: need ≥2 real auto bindings for full acceptance, got {len(rows)}"
        )

    if results["G6"] == "pending multi-bind check":
        push_db(db2, "merge")
        subprocess.check_call(["adb", "logcat", "-c"])
        open_read_book(book_url, PKG)
        time.sleep(8)
        log2 = adb("logcat", "-d").decode("utf-8", "replace")
        merge_hits = [ln for ln in log2.splitlines() if "merge providers=" in ln]
        shot("G6_read_multibind.png")
        if merge_hits:
            results["G6"] = merge_hits[-1][merge_hits[-1].find("ReviewOverlay") :]
            print("G6 merge log", results["G6"])
        else:
            raise SystemExit(
                "FAIL G6: ≥2 bindings but no merge providers= log / merge dialog title"
            )

    # G8 book info via read menu (activity not exported to shell)
    push_db(db2, "info")
    open_book_info_from_read(book_url)
    shot("G8_book_info.png")
    xml = dump_ui("G8_book_info.xml")
    ts = texts(xml)
    review_bits = [t for t in ts if "段评" in t or "已绑定" in t]
    print("G8 review texts", review_bits)
    if not review_bits:
        adb("shell", "input", "swipe", "540", "1800", "540", "600", "300")
        time.sleep(0.8)
        shot("G8_book_info_scrolled.png")
        xml = dump_ui("G8_book_info2.xml")
        ts = texts(xml)
        review_bits = [t for t in ts if "段评" in t or "已绑定" in t]
        print("G8 after scroll", review_bits)
    if not review_bits:
        raise SystemExit("FAIL G8: no 段评源 UI text")
    results["G8"] = "G8_book_info.png " + "; ".join(review_bits[:5])

    # G9 negative
    push_prefs(reviewOverlayAutoBind=False)
    dbn = pull_db(OUT / "neg")
    con = sqlite3.connect(str(dbn))
    con.execute("DELETE FROM book_review_bindings WHERE contentBookUrl=?", (book_url,))
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    push_db(dbn, "neg")
    subprocess.check_call(["adb", "logcat", "-c"])
    open_read_book(book_url, PKG)
    time.sleep(12)
    shot("G9_neg_read.png")
    dump_ui("G9_neg_read.xml")
    logn = adb("logcat", "-d").decode("utf-8", "replace")
    (OUT / "session_negative.log").write_text(logn, encoding="utf-8")
    if "auto-bind scan" in logn or "auto-bind proposals=" in logn:
        raise SystemExit("FAIL G9: auto-bind still scanned while pref off")
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1)
    dba = pull_db(OUT / "neg_after")
    con = sqlite3.connect(str(dba))
    n = con.execute(
        "SELECT count(*) FROM book_review_bindings WHERE contentBookUrl=?", (book_url,)
    ).fetchone()[0]
    con.close()
    if n != 0:
        raise SystemExit(f"FAIL G9: unexpected bindings count={n}")
    results["G9"] = "G9_neg_read.png no auto-bind"

    push_prefs(reviewOverlayAutoBind=True)

    report = {
        "PASS": True,
        "bookUrl": book_url,
        "gates": results,
        "shots": sorted(p.name for p in OUT.glob("*.png")),
    }
    (OUT / "ACCEPTANCE.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("PASS: full screenshot acceptance →", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
