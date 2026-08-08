#!/usr/bin/env python3
"""Shared adb helpers for Legado debug-device scripts."""
from __future__ import annotations

import os
import sqlite3
import subprocess
import time
from pathlib import Path

DEFAULT_PKG = os.environ.get("LEGADO_DEBUG_PKG", "com.legado.app.debug")


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def adb(*args: str, check: bool = True, text: bool = True) -> str:
    kw: dict = {"text": text, "errors": "ignore"}
    if check:
        return subprocess.check_output(["adb", *args], **kw)
    p = subprocess.run(["adb", *args], capture_output=True, **kw)
    return (p.stdout or "") + (p.stderr or "")


def require_device() -> None:
    if subprocess.call(["adb", "get-state"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) != 0:
        raise SystemExit("no adb device")


def force_stop(pkg: str = DEFAULT_PKG) -> None:
    subprocess.call(["adb", "shell", "am", "force-stop", pkg])
    time.sleep(0.8)


def pull_legado_db(dest: Path, pkg: str = DEFAULT_PKG, *, stop_app: bool = True) -> Path:
    """Pull databases/legado.db via run-as. Caller should treat dest as ephemeral under temp/."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if stop_app:
        force_stop(pkg)
    with open(dest, "wb") as f:
        subprocess.check_call(
            ["adb", "exec-out", "run-as", pkg, "cat", "databases/legado.db"],
            stdout=f,
        )
    if dest.stat().st_size < 1000:
        raise RuntimeError(f"db too small: {dest} ({dest.stat().st_size} bytes)")
    con = sqlite3.connect(str(dest))
    try:
        ic = con.execute("pragma integrity_check").fetchone()[0]
        if ic != "ok":
            raise RuntimeError(f"integrity_check={ic}")
    finally:
        con.close()
    return dest


def open_read_book(book_url: str, pkg: str = DEFAULT_PKG, *, in_bookshelf: bool = True) -> None:
    """Open ReadBookActivity. Quote URL so shell does not eat '?'."""
    subprocess.check_call(
        [
            "adb",
            "shell",
            "monkey",
            "-p",
            pkg,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    time.sleep(3)
    # Single-quoted URL inside adb shell so ? & are preserved.
    escaped = book_url.replace("'", "'\\''")
    cmd = (
        f"am start -n {pkg}/io.legado.app.ui.book.read.ReadBookActivity "
        f"--es bookUrl '{escaped}' --ez inBookshelf "
        f"{'true' if in_bookshelf else 'false'}"
    )
    print(adb("shell", cmd).rstrip())
