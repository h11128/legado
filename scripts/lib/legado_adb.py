#!/usr/bin/env python3
"""Shared adb helpers for Legado debug-device scripts."""
from __future__ import annotations

import os
import sqlite3
import subprocess
import sys
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
    time.sleep(0.35)


def phone_wlan_ip() -> str | None:
    """Return phone wlan0 IPv4, or None."""
    out = adb("shell", "ip", "-f", "inet", "addr", "show", "wlan0", check=False)
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("inet "):
            # inet 10.0.0.139/24 ...
            return line.split()[1].split("/")[0]
    return None


def mcp_port_listening(port: int = 1236) -> bool:
    # Prefer ss; fall back to netstat on older/toybox builds.
    out = adb(
        "shell",
        f"(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep ':{port}' || true",
        check=False,
    )
    return f":{port}" in out


def launch_main(pkg: str = DEFAULT_PKG) -> None:
    """Start MainActivity so App.onCreate → McpService.restoreIfEnabled can run."""
    subprocess.call(
        [
            "adb",
            "shell",
            "am",
            "start",
            "-n",
            f"{pkg}/io.legado.app.ui.main.MainActivity",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def ensure_mcp_listening(
    pkg: str = DEFAULT_PKG,
    port: int = 1236,
    *,
    timeout_s: float = 15.0,
) -> bool:
    """If MCP TCP port is down, launch the app and wait for restoreIfEnabled."""
    if mcp_port_listening(port):
        return True
    launch_main(pkg)
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if mcp_port_listening(port):
            return True
        time.sleep(0.5)
    return mcp_port_listening(port)


def shelf_restore_queue() -> Path:
    """Runtime artifact dir for shelf-restore reports/DBs (not scripts)."""
    p = repo_root() / "temp" / "shelf_restore" / "queue"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _adb_cat_bytes(pkg: str, remote_rel: str) -> bytes:
    return subprocess.check_output(
        ["adb", "exec-out", "run-as", pkg, "cat", remote_rel]
    )


def _pull_urls_sidecar(dest: Path) -> Path:
    return Path(str(dest) + ".pull_urls.json")


def write_pull_urls_sidecar(dest: Path, urls: list[str]) -> Path:
    """Record bookSourceUrl set at pull time (for safe post-MCP merge)."""
    import json
    import time

    side = _pull_urls_sidecar(dest)
    side.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "pulled_at_ms": int(time.time() * 1000),
                "urls": sorted(urls),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return side


def read_pull_urls_sidecar(dest: Path) -> set[str] | None:
    import json

    side = _pull_urls_sidecar(dest)
    if not side.is_file():
        return None
    try:
        data = json.loads(side.read_text(encoding="utf-8"))
        return {str(u) for u in (data.get("urls") or [])}
    except (OSError, json.JSONDecodeError, TypeError):
        return None


def pull_legado_db(
    dest: Path,
    pkg: str = DEFAULT_PKG,
    *,
    stop_app: bool = True,
    min_bytes: int = 1000,
    wal: bool = True,
) -> Path:
    """Pull databases/legado.db via run-as (WAL-aware by default).

    MCP ``save_source`` often lands in the WAL only. Copying ``legado.db``
    alone then pushing that file **wipes** those rows. Default ``wal=True``
    pulls ``-wal``/``-shm`` beside dest and checkpoints into dest.

    Also writes ``{dest}.pull_urls.json`` so ``push_legado_db(merge_live_sources)``
    can merge only URLs that appeared on device *after* this pull.
    """
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if stop_app:
        force_stop(pkg)
    dest.write_bytes(_adb_cat_bytes(pkg, "databases/legado.db"))
    if wal:
        for suffix in ("-wal", "-shm"):
            side = dest.parent / f"{dest.name}{suffix}"
            try:
                side.write_bytes(_adb_cat_bytes(pkg, f"databases/legado.db{suffix}"))
            except subprocess.CalledProcessError:
                # Missing WAL is fine after a clean checkpoint on device.
                if side.exists():
                    side.unlink(missing_ok=True)
        con = sqlite3.connect(str(dest))
        try:
            con.execute("PRAGMA wal_checkpoint(FULL)")
            con.commit()
        finally:
            con.close()
        for suffix in ("-wal", "-shm"):
            side = dest.parent / f"{dest.name}{suffix}"
            if side.exists():
                side.unlink(missing_ok=True)
    if dest.stat().st_size < min_bytes:
        raise RuntimeError(f"db too small: {dest} ({dest.stat().st_size} bytes)")
    con = sqlite3.connect(str(dest))
    try:
        ic = con.execute("pragma integrity_check").fetchone()[0]
        if ic != "ok":
            raise RuntimeError(f"integrity_check={ic}")
        urls = [
            r[0]
            for r in con.execute("SELECT bookSourceUrl FROM book_sources")
        ]
    finally:
        con.close()
    write_pull_urls_sidecar(dest, urls)
    return dest


def merge_book_sources_from_live_db(
    work: Path,
    live_db: Path,
    *,
    baseline_urls: set[str] | None = None,
) -> list[str]:
    """Copy ``book_sources`` rows in ``live_db`` that are missing from ``work``.

    Local (work) rows for the same URL are kept. Returns inserted URLs.

    If ``baseline_urls`` is provided (from ``{work}.pull_urls.json``):
    - missing URL that was in baseline → intentional delete → skip
    - missing URL not in baseline → appeared after pull (MCP) → insert

    If ``baseline_urls`` is None: insert every live URL missing from work
    (max wipe-protection; may resurrect intentional deletes).
    """
    work = Path(work)
    live_db = Path(live_db)
    live = sqlite3.connect(str(live_db))
    dest = sqlite3.connect(str(work), timeout=60.0)
    inserted: list[str] = []
    try:
        live_cols = [c[1] for c in live.execute("PRAGMA table_info(book_sources)")]
        work_cols = [c[1] for c in dest.execute("PRAGMA table_info(book_sources)")]
        if not live_cols or live_cols != work_cols:
            raise RuntimeError(
                f"book_sources schema mismatch live={live_cols!r} work={work_cols!r}"
            )
        have = {
            r[0]
            for r in dest.execute("SELECT bookSourceUrl FROM book_sources")
        }
        col_sql = ",".join(live_cols)
        placeholders = ",".join("?" for _ in live_cols)
        for row in live.execute(f"SELECT {col_sql} FROM book_sources"):
            url = row[0]
            if url in have:
                continue
            if baseline_urls is not None and url in baseline_urls:
                # Present at original pull, omitted from work → intentional.
                continue
            dest.execute(
                f"INSERT INTO book_sources ({col_sql}) VALUES ({placeholders})",
                list(row),
            )
            inserted.append(str(url))
            have.add(url)
        dest.commit()
    finally:
        dest.close()
        live.close()
    return inserted


def merge_live_book_sources_into(
    work: Path,
    pkg: str = DEFAULT_PKG,
    *,
    live_copy: Path | None = None,
) -> list[str]:
    """Pull device DB and insert post-pull MCP ``book_sources`` missing from ``work``.

    Uses ``{work}.pull_urls.json`` baseline from ``pull_legado_db`` (clock-free).
    Without sidecar, merges all missing live URLs (safer against wipe).
    Does **not** merge ``books``.
    """
    work = Path(work)
    baseline = read_pull_urls_sidecar(work)
    tmp = Path(live_copy) if live_copy else work.parent / f".{work.name}.live_merge.db"
    pull_legado_db(tmp, pkg=pkg, stop_app=True, wal=True, min_bytes=1000)
    try:
        return merge_book_sources_from_live_db(work, tmp, baseline_urls=baseline)
    finally:
        try:
            tmp.unlink(missing_ok=True)
            _pull_urls_sidecar(tmp).unlink(missing_ok=True)
        except OSError:
            pass


def _work_source_urls(work: Path) -> set[str]:
    con = sqlite3.connect(str(work), timeout=60.0)
    try:
        return {
            r[0]
            for r in con.execute("SELECT bookSourceUrl FROM book_sources")
        }
    finally:
        con.close()


def sync_pending_mcp_saves_into_work(work: Path) -> tuple[str, list[str]]:
    """Fast path: ensure MCP-saved URLs exist in ``work`` without full DB pull.

    Returns (mode, urls) where mode is:
    - ``skip``: nothing pending or already present
    - ``mcp_upsert``: fetched via MCP get_source + upsert
    - ``need_full_merge``: MCP unavailable; caller should full-merge
    """
    try:
        from .legado_session import clear_mcp_saves, pending_mcp_saves
    except ImportError:
        from legado_session import clear_mcp_saves, pending_mcp_saves  # type: ignore

    pending = pending_mcp_saves()
    if not pending:
        return "skip", []
    have = _work_source_urls(work)
    missing = [u for u in pending if u not in have]
    if not missing:
        clear_mcp_saves(pending)
        return "skip", []
    try:
        try:
            from .legado_db_mutate import upsert_book_source
            from .legado_mcp import LegadoMcp
        except ImportError:
            from legado_db_mutate import upsert_book_source  # type: ignore
            from legado_mcp import LegadoMcp  # type: ignore

        mcp = LegadoMcp(connect_retries=3)
        con = sqlite3.connect(str(work), timeout=60.0)
        try:
            for u in missing:
                upsert_book_source(con, mcp.get_source(u))
            con.commit()
        finally:
            con.close()
        clear_mcp_saves(pending)
        return "mcp_upsert", missing
    except Exception as e:
        print(
            f"push_legado_db: MCP upsert failed ({e}); will full-merge",
            file=sys.stderr,
        )
        return "need_full_merge", missing


def push_legado_db(
    src: Path,
    pkg: str = DEFAULT_PKG,
    *,
    relaunch: bool = True,
    min_bytes: int = 1000,
    min_books: int = 10,
    require_source_urls: list[str] | None = None,
    merge_live_sources: bool | str = "auto",
) -> None:
    """Push a working copy into the app's databases/legado.db (clears WAL).

    ``merge_live_sources`` modes:
    - ``"auto"`` (default, fastest safe): if pending MCP saves already in
      ``src`` → skip merge; else MCP ``get_source`` upsert; else full pull merge.
    - ``True``: always full baseline merge (slow; use only when needed).
    - ``False``: never merge (fixtures / caller fully owns ``require_source_urls``).

    Pass ``require_source_urls`` for hard guarantees after local INSERT/upsert.
    """
    src = Path(src)
    if not src.is_file() or src.stat().st_size < min_bytes:
        raise RuntimeError(f"refuse push of tiny/missing db: {src}")

    merged: list[str] = []
    mode = merge_live_sources
    if mode == "auto":
        sync_mode, synced = sync_pending_mcp_saves_into_work(src)
        if sync_mode == "mcp_upsert":
            print(
                f"push_legado_db: auto MCP-upserted {len(synced)} "
                f"(first={synced[0]!r})",
                file=sys.stderr,
            )
        elif sync_mode == "need_full_merge":
            mode = True
        else:
            # Still honor require_source_urls presence; no full merge needed
            # when work already complete.
            print("push_legado_db: auto skip merge (work complete)", file=sys.stderr)
    if mode is True:
        merged = merge_live_book_sources_into(src, pkg=pkg)
        if merged:
            print(
                f"push_legado_db: merged {len(merged)} live book_sources "
                f"(first={merged[0]!r})",
                file=sys.stderr,
            )
            try:
                from .legado_session import clear_mcp_saves
            except ImportError:
                from legado_session import clear_mcp_saves  # type: ignore
            clear_mcp_saves(merged)

    con = sqlite3.connect(str(src))
    try:
        ic = con.execute("pragma integrity_check").fetchone()[0]
        if ic != "ok":
            raise RuntimeError(f"refuse push: integrity_check={ic}")
        books = con.execute("select count(*) from books").fetchone()[0]
        if books < min_books:
            raise RuntimeError(f"refuse push: books={books}")
        if require_source_urls:
            missing = [
                u
                for u in require_source_urls
                if not con.execute(
                    "SELECT 1 FROM book_sources WHERE bookSourceUrl = ?", (u,)
                ).fetchone()
            ]
            if missing:
                raise RuntimeError(
                    "refuse push: stale snapshot missing book_sources "
                    + ", ".join(missing)
                    + " — INSERT in this file first, or re-pull after MCP save"
                )
        elif mode is False:
            print(
                "warning: push_legado_db(merge_live_sources=False) without "
                "require_source_urls — MCP-saved sources missing from this "
                "file will be wiped",
                file=sys.stderr,
            )
    finally:
        con.close()
    force_stop(pkg)
    subprocess.check_call(["adb", "push", str(src), "/data/local/tmp/legado_work.db"])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {pkg} cp /data/local/tmp/legado_work.db databases/legado.db "
            f"&& run-as {pkg} rm -f databases/legado.db-wal databases/legado.db-shm",
        ]
    )
    if relaunch:
        # Prefer MainActivity so McpService.restoreIfEnabled runs (monkey alone
        # can leave MCP down after force-stop / DB push).
        if not ensure_mcp_listening(pkg):
            print(
                f"warning: MCP :1236 still down after MainActivity; trying monkey launcher",
                file=sys.stderr,
            )
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
            time.sleep(2)
            if not mcp_port_listening():
                print(
                    "warning: MCP still not listening — run: python scripts/mcp-ensure.py",
                    file=sys.stderr,
                )


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
