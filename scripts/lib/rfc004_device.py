#!/usr/bin/env python3
"""Shared device helpers for RFC-004 acceptance / session scripts.

All RFC-004 adb/db/prefs/UI helpers live here so session scripts stay thin
wrappers (or unique fixture flows) without copy-pasted pull_db/push_prefs.
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import subprocess
import time
from pathlib import Path
from typing import Callable, Iterable

PKG = os.environ.get("LEGADO_DEBUG_PKG", "com.legado.app.debug")
PREFS = f"shared_prefs/{PKG}_preferences.xml"
FIXTURE_PREFIX = "legado-fixture://"
REVIEW_SUFFIX = "#rfc004-review"
BOOK_SUB_DEFAULT = "诡秘之主"
ALL_GATES = ("G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8", "G9")

ROOT = Path(__file__).resolve().parents[2]


def set_serial(serial: str | None) -> None:
    if serial:
        os.environ["ANDROID_SERIAL"] = serial


def list_devices() -> list[str]:
    out = subprocess.check_output(["adb", "devices"], text=True, errors="replace")
    serials: list[str] = []
    for ln in out.splitlines()[1:]:
        parts = ln.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def require_device(*, serial: str | None = None) -> str:
    set_serial(serial)
    serials = list_devices()
    if not serials:
        raise SystemExit("FAIL: no adb device")
    env_serial = os.environ.get("ANDROID_SERIAL")
    if env_serial:
        if env_serial not in serials:
            raise SystemExit(f"FAIL: ANDROID_SERIAL={env_serial} not in {serials}")
        print("adb:", env_serial)
        return env_serial
    if len(serials) > 1:
        raise SystemExit(
            f"FAIL: multiple devices {serials}; pass --serial or set ANDROID_SERIAL"
        )
    print("adb:", serials[0])
    return serials[0]


def adb_bytes(*args: str) -> bytes:
    return subprocess.check_output(["adb", *args])


def adb_str(*args: str) -> str:
    return adb_bytes(*args).decode("utf-8", "replace")


def adb_call(*args: str) -> None:
    subprocess.check_call(["adb", *args])


def force_stop(*, sleep_s: float = 0.6) -> None:
    adb_call("shell", "am", "force-stop", PKG)
    time.sleep(sleep_s)


def screen_size() -> tuple[int, int]:
    out = adb_str("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        return 1080, 2400
    return int(m.group(1)), int(m.group(2))


def tap_xy(x: int, y: int) -> None:
    adb_call("shell", "input", "tap", str(x), str(y))


def tap_frac(fx: float, fy: float) -> None:
    w, h = screen_size()
    tap_xy(int(w * fx), int(h * fy))


def swipe_frac(
    fx1: float, fy1: float, fx2: float, fy2: float, duration_ms: int = 300
) -> None:
    w, h = screen_size()
    adb_call(
        "shell",
        "input",
        "swipe",
        str(int(w * fx1)),
        str(int(h * fy1)),
        str(int(w * fx2)),
        str(int(h * fy2)),
        str(duration_ms),
    )


def keyevent(code: int | str) -> None:
    adb_call("shell", "input", "keyevent", str(code))


def logcat_clear() -> None:
    adb_call("logcat", "-c")


def logcat_applog() -> str:
    """Lines relevant to ReviewOverlay.

    AppLog.put writes LogUtils (file) and, in DEBUG, android.util.Log.e(className, msg)
    — not an ``AppLog`` logcat tag. Filter the dump in Python.
    """
    raw = adb_str("logcat", "-d")
    keep = [
        ln
        for ln in raw.splitlines()
        if "ReviewOverlay" in ln or "段评源" in ln or "AppLog" in ln
    ]
    return "\n".join(keep)


def overlay_lines(log: str | None = None) -> list[str]:
    text = log if log is not None else logcat_applog()
    return [ln for ln in text.splitlines() if "ReviewOverlay" in ln]


def wait_until(
    predicate: Callable[[], bool],
    *,
    timeout_s: float,
    interval_s: float = 1.0,
    label: str = "condition",
) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval_s)
    print(f"wait_until timeout ({label}, {timeout_s}s)")
    return False


def pull_db_wal(dest: Path, *, stop_app: bool = True) -> Path:
    """WAL-aware pull; checkpoint into legado.db for sqlite3 on host."""
    dest.mkdir(parents=True, exist_ok=True)
    if stop_app:
        force_stop()
    for name in ("legado.db", "legado.db-wal", "legado.db-shm"):
        (dest / name).write_bytes(
            adb_bytes("exec-out", "run-as", PKG, "cat", f"databases/{name}")
        )
    db = dest / "legado.db"
    con = sqlite3.connect(str(db))
    con.execute("PRAGMA wal_checkpoint(FULL)")
    con.commit()
    con.close()
    return db


def push_db_raw(db: Path, tag: str = "rfc004") -> None:
    """Push DB without shelf min_books guard (acceptance mutates small scopes)."""
    remote = f"/data/local/tmp/legado_rfc004_{tag}.db"
    adb_call("push", str(db), remote)
    adb_call(
        "shell",
        f"run-as {PKG} sh -c 'cp {remote} databases/legado.db "
        f"&& rm -f databases/legado.db-wal databases/legado.db-shm'",
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


def push_review_prefs(
    out_dir: Path,
    **flags: bool,
) -> None:
    defaults = {
        "reviewOverlayEnabled": True,
        "reviewOverlayAutoBind": True,
        "reviewOverlayMergeEnabled": True,
        "reviewOverlayAllowParagraphIcons": True,
    }
    defaults.update(flags)
    raw = adb_bytes("exec-out", "run-as", PKG, "cat", PREFS).decode("utf-8", "replace")
    xml = raw
    for k, v in defaults.items():
        xml = ensure_pref_bool(xml, k, v)
    out_dir.mkdir(parents=True, exist_ok=True)
    p = out_dir / "preferences.xml"
    p.write_text(xml, encoding="utf-8")
    remote = "/data/local/tmp/legado_rfc004_prefs.xml"
    adb_call("push", str(p), remote)
    adb_call("shell", f"run-as {PKG} sh -c 'cp {remote} {PREFS}'")


def dump_ui(out_path: Path) -> str:
    adb_call("shell", "uiautomator", "dump", "/sdcard/_rfc004_ui.xml")
    adb_call("pull", "/sdcard/_rfc004_ui.xml", str(out_path))
    return out_path.read_text(encoding="utf-8", errors="replace")


def shot(out_path: Path) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(adb_bytes("exec-out", "screencap", "-p"))
    print("shot", out_path.name)
    return out_path


def ui_texts(xml: str) -> list[str]:
    return [m.group(1) for m in re.finditer(r'text="([^"]+)"', xml)]


def ui_nodes(xml: str) -> list[tuple[str, int, int, int, int]]:
    """(text, x1, y1, x2, y2) from accessibility dump."""
    nodes = []
    for m in re.finditer(
        r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
    ):
        text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        nodes.append((text, x1, y1, x2, y2))
    # Attribute order variant: bounds before text
    for m in re.finditer(
        r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="([^"]*)"', xml
    ):
        x1, y1, x2, y2 = map(int, m.groups()[:4])
        text = m.group(5)
        nodes.append((text, x1, y1, x2, y2))
    return nodes


def count_real_review_sources(db: Path) -> list[tuple[str, str]]:
    con = sqlite3.connect(str(db))
    rows = con.execute(
        "SELECT bookSourceUrl, bookSourceName FROM book_sources WHERE enabled=1 "
        "AND bookSourceUrl LIKE ? AND bookSourceUrl NOT LIKE ?",
        (f"%{REVIEW_SUFFIX}", FIXTURE_PREFIX + "%"),
    ).fetchall()
    con.close()
    return [(u, n) for u, n in rows]


def find_book_url(db: Path, book_sub: str = BOOK_SUB_DEFAULT) -> tuple[str, str, str, str]:
    con = sqlite3.connect(str(db))
    row = con.execute(
        "SELECT bookUrl, name, author, origin FROM books WHERE name LIKE ? LIMIT 1",
        (f"%{book_sub}%",),
    ).fetchone()
    con.close()
    if not row:
        raise SystemExit(f"FAIL: book missing (substr={book_sub})")
    return row[0], row[1], row[2] or "", row[3] or ""


def parse_gates(
    *,
    from_gate: str | None,
    only: str | None,
) -> set[str]:
    if only and from_gate:
        raise SystemExit("FAIL: use either --from or --only, not both")
    if only:
        parts = [p.strip().upper() for p in only.split(",") if p.strip()]
        bad = [p for p in parts if p not in ALL_GATES]
        if bad:
            raise SystemExit(f"FAIL: unknown gates {bad}; want {ALL_GATES}")
        return set(parts)
    if from_gate:
        g = from_gate.strip().upper()
        if g not in ALL_GATES:
            raise SystemExit(f"FAIL: unknown --from {g}")
        i = ALL_GATES.index(g)
        return set(ALL_GATES[i:])
    return set(ALL_GATES)


def clear_out_dir(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for p in out.rglob("*"):
        if p.is_file():
            p.unlink()


def write_acceptance(
    out: Path,
    *,
    passed: bool,
    book_url: str | None,
    gates: dict,
    shots: Iterable[str],
    fail: str | None = None,
    mode: str = "full",
) -> Path:
    report = {
        "PASS": passed,
        "mode": mode,
        "bookUrl": book_url,
        "fail": fail,
        "gates": gates,
        "shots": sorted(shots),
    }
    path = out / "ACCEPTANCE.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return path


def find_badge_tap(
    png: Path,
    xml: str | None = None,
) -> tuple[int, int]:
    """Prefer UI bounds near chapter title / review chrome; else pixel heuristic."""
    if xml:
        w, h = screen_size()
        top = int(h * 0.35)
        for text, x1, y1, x2, y2 in ui_nodes(xml):
            if y2 > top:
                continue
            if any(k in text for k in ("条", "评", "本章")) and y1 > 40:
                return (x1 + x2) // 2, (y1 + y2) // 2
        # Title row: longest darkish text in top band — tap just left of end
        title_nodes = [
            (t, x1, y1, x2, y2)
            for t, x1, y1, x2, y2 in ui_nodes(xml)
            if t and y2 < top and y1 > 40 and len(t) >= 2
        ]
        if title_nodes:
            t, x1, y1, x2, y2 = max(title_nodes, key=lambda n: n[3] - n[1])
            return max(x1, x2 - 24), (y1 + y2) // 2

    from PIL import Image

    try:
        im = Image.open(png).convert("RGB")
    except Exception as e:
        print("PIL badge fallback unavailable:", e)
        w, h = screen_size()
        return int(w * 0.89), int(h * 0.07)
    pix = im.load()
    w, h = im.size
    y_lo, y_hi = int(h * 0.03), int(h * 0.12)
    x_hi = min(int(w * 0.95), w)
    row_dark = [0] * h
    for y in range(y_lo, y_hi):
        c = sum(
            1
            for x in range(int(w * 0.1), x_hi)
            if pix[x, y][0] < 80 and pix[x, y][1] < 80 and pix[x, y][2] < 80
        )
        row_dark[y] = c
    title_rows = [y for y in range(y_lo, y_hi) if row_dark[y] > max(40, w // 40)]
    if not title_rows:
        return int(w * 0.89), int(h * 0.07)
    y0, y1 = min(title_rows), max(title_rows)
    xs = [
        x
        for y in range(y0, y1 + 1)
        for x in range(int(w * 0.1), x_hi)
        if pix[x, y][0] < 80 and pix[x, y][1] < 80 and pix[x, y][2] < 80
    ]
    title_end = max(xs) if xs else int(w * 0.85)
    return max(int(w * 0.2), title_end - 12), (y0 + y1) // 2


def insert_review_binding(
    cur: sqlite3.Cursor,
    cols: set[str] | None = None,
    *,
    book_url: str,
    name: str,
    author: str,
    origin: str,
    provider_source: str,
    provider_book: str,
    provider_name: str,
    provider_author: str,
    sort_order: int,
    bind_mode: str = "manual",
    role: str = "chapter",
) -> None:
    if cols is None:
        cols = {r[1] for r in cur.execute("PRAGMA table_info(book_review_bindings)")}
    base = {
        "contentBookUrl": book_url,
        "contentName": name.strip(),
        "contentAuthor": author or "",
        "contentOrigin": origin,
        "providerSourceUrl": provider_source,
        "providerBookUrl": provider_book,
        "providerName": provider_name,
        "providerAuthor": provider_author,
        "bindMode": bind_mode,
        "updatedAt": int(time.time() * 1000),
    }
    if "enabled" in cols:
        base["enabled"] = 1
    if "sortOrder" in cols:
        base["sortOrder"] = sort_order
    if "role" in cols:
        base["role"] = role
    keys = [k for k in base if k in cols]
    cur.execute(
        f"INSERT INTO book_review_bindings ({','.join(keys)}) VALUES "
        f"({','.join('?' for _ in keys)})",
        [base[k] for k in keys],
    )
