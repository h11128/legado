#!/usr/bin/env python3
"""Safe whole-DB mutations for Legado (avoid MCP-save + stale-push wipe).

Rule: every push must come from a pull that already contains every URL you
care about (new sources INSERTed in the same working copy). Never:
  mcp.save_source(new_url) → push(old_pull_without_new_url)
"""
from __future__ import annotations

import json
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator, Mapping

try:
    from .legado_adb import (  # type: ignore
        DEFAULT_PKG,
        pull_legado_db,
        push_legado_db,
        shelf_restore_queue,
    )
except ImportError:  # python scripts/lib/legado_db_mutate.py
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from legado_adb import (  # type: ignore
        DEFAULT_PKG,
        pull_legado_db,
        push_legado_db,
        shelf_restore_queue,
    )


def _norm_cell(v: Any) -> Any:
    if isinstance(v, (dict, list)):
        return json.dumps(v, ensure_ascii=False)
    if isinstance(v, bool):
        return 1 if v else 0
    return v


def upsert_book_source(con: sqlite3.Connection, source: Mapping[str, Any]) -> str:
    """INSERT or REPLACE one row in book_sources. Returns bookSourceUrl."""
    url = str(source.get("bookSourceUrl") or "").strip()
    if not url:
        raise ValueError("bookSourceUrl required")
    cols = [c[1] for c in con.execute("PRAGMA table_info(book_sources)")]
    row = {c: _norm_cell(source.get(c)) for c in cols}
    row["bookSourceUrl"] = url
    if row.get("enabled") is None:
        row["enabled"] = 1
    if row.get("bookSourceType") is None:
        row["bookSourceType"] = 0
    if row.get("customOrder") is None:
        row["customOrder"] = 0
    if row.get("lastUpdateTime") is None:
        row["lastUpdateTime"] = int(time.time() * 1000)
    if row.get("respondTime") is None:
        row["respondTime"] = 180000
    if row.get("weight") is None:
        row["weight"] = 0
    # Room JSON rule fields: empty string better than NULL for some builds
    for k in (
        "ruleSearch",
        "ruleBookInfo",
        "ruleToc",
        "ruleContent",
        "ruleExplore",
        "ruleReview",
        "exploreUrl",
        "searchUrl",
        "header",
    ):
        if k in row and row[k] is None:
            row[k] = ""
    con.execute("DELETE FROM book_sources WHERE bookSourceUrl = ?", (url,))
    placeholders = ",".join("?" for _ in cols)
    con.execute(
        f"INSERT INTO book_sources ({','.join(cols)}) VALUES ({placeholders})",
        [row.get(c) for c in cols],
    )
    return url


def set_sources_enabled(
    con: sqlite3.Connection,
    urls: list[str],
    *,
    enabled: bool,
) -> int:
    if not urls:
        return 0
    flag = 1 if enabled else 0
    n = 0
    for u in urls:
        cur = con.execute(
            "UPDATE book_sources SET enabled = ? WHERE bookSourceUrl = ?",
            (flag, u),
        )
        n += cur.rowcount
    return n


def require_source_urls(con: sqlite3.Connection, urls: list[str]) -> None:
    missing = []
    for u in urls:
        row = con.execute(
            "SELECT 1 FROM book_sources WHERE bookSourceUrl = ?", (u,)
        ).fetchone()
        if not row:
            missing.append(u)
    if missing:
        raise RuntimeError(
            "refuse push: book_sources missing "
            + ", ".join(missing)
            + " (stale snapshot would wipe MCP-saved sources)"
        )


def remap_book_origin(
    con: sqlite3.Connection,
    *,
    old_origin: str,
    new_origin: str,
    book_url_map: Mapping[str, str] | None = None,
) -> int:
    """Remap books.origin (= bookSourceUrl).

    When ``book_url_map`` is provided and non-empty, **only** those bookUrls
    are remapped (keys = current bookUrl, values = new bookUrl). This avoids
    accidental whole-origin remaps when the intent was a per-book map.

    When ``book_url_map`` is omitted/empty, every book under ``old_origin``
    keeps its bookUrl and only ``origin`` changes.
    """
    if book_url_map:
        n = 0
        misses: list[str] = []
        for book_url, new_book in book_url_map.items():
            cur = con.execute(
                "UPDATE books SET origin = ?, bookUrl = ? "
                "WHERE bookUrl = ? AND origin = ?",
                (new_origin, new_book, book_url, old_origin),
            )
            if cur.rowcount == 0:
                misses.append(book_url)
            else:
                n += cur.rowcount
        if misses:
            raise RuntimeError(
                "remap --map miss (wrong bookUrl or old-origin): "
                + ", ".join(misses)
            )
        return n
    rows = con.execute(
        "SELECT bookUrl FROM books WHERE origin = ?", (old_origin,)
    ).fetchall()
    n = 0
    for (book_url,) in rows:
        con.execute(
            "UPDATE books SET origin = ?, bookUrl = ? WHERE bookUrl = ? AND origin = ?",
            (new_origin, book_url, book_url, old_origin),
        )
        n += 1
    return n


@contextmanager
def with_legado_db(
    dest: Path | None = None,
    *,
    pkg: str = DEFAULT_PKG,
    require_urls: list[str] | None = None,
    relaunch: bool = True,
    push: bool = True,
) -> Iterator[sqlite3.Connection]:
    """Pull (WAL-aware) → yield connection → commit → optional guarded push.

    Use this for disable / upsert / remap. Put every new bookSourceUrl into
    ``require_urls`` (and INSERT them before exit) so push cannot wipe them.
    """
    path = dest or (shelf_restore_queue() / "mutate_work.db")
    pull_legado_db(path, pkg=pkg, stop_app=True)
    con = sqlite3.connect(str(path))
    try:
        yield con
        if require_urls:
            require_source_urls(con, require_urls)
        con.commit()
    except Exception:
        con.rollback()
        con.close()
        raise
    else:
        con.close()
        if push:
            push_legado_db(
                path,
                pkg=pkg,
                relaunch=relaunch,
                require_source_urls=require_urls,
            )


def _self_test() -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as td:
        db = Path(td) / "t.db"
        con = sqlite3.connect(str(db))
        con.execute(
            "CREATE TABLE book_sources ("
            "bookSourceUrl TEXT PRIMARY KEY, bookSourceName TEXT, enabled INTEGER, "
            "bookSourceType INTEGER, customOrder INTEGER, lastUpdateTime INTEGER, "
            "respondTime INTEGER, weight INTEGER, searchUrl TEXT, ruleSearch TEXT, "
            "ruleBookInfo TEXT, ruleToc TEXT, ruleContent TEXT, ruleExplore TEXT, "
            "ruleReview TEXT, exploreUrl TEXT, header TEXT, bookSourceGroup TEXT, "
            "bookSourceComment TEXT)"
        )
        con.execute(
            "CREATE TABLE books (name TEXT, bookUrl TEXT, origin TEXT, tocUrl TEXT)"
        )
        con.commit()
        upsert_book_source(
            con,
            {
                "bookSourceUrl": "http://example.test",
                "bookSourceName": "t",
                "enabled": True,
                "ruleSearch": {"bookList": "li"},
            },
        )
        require_source_urls(con, ["http://example.test"])
        try:
            require_source_urls(con, ["http://missing.test"])
            raise AssertionError("expected missing")
        except RuntimeError as e:
            assert "missing" in str(e)
        set_sources_enabled(con, ["http://example.test"], enabled=False)
        assert con.execute(
            "SELECT enabled FROM book_sources WHERE bookSourceUrl=?",
            ("http://example.test",),
        ).fetchone()[0] == 0
        con.execute(
            "INSERT INTO books VALUES (?,?,?,?)",
            ("n", "http://old/1", "http://old", ""),
        )
        con.execute(
            "INSERT INTO books VALUES (?,?,?,?)",
            ("keep", "http://old/2", "http://old", ""),
        )
        # --map present: only listed bookUrls move
        n = remap_book_origin(
            con,
            old_origin="http://old",
            new_origin="http://example.test",
            book_url_map={"http://old/1": "http://example.test/1"},
        )
        assert n == 1
        assert con.execute(
            "SELECT origin, bookUrl FROM books WHERE name='n'"
        ).fetchone() == ("http://example.test", "http://example.test/1")
        assert con.execute(
            "SELECT origin, bookUrl FROM books WHERE name='keep'"
        ).fetchone() == ("http://old", "http://old/2")
        # no map: whole origin
        n2 = remap_book_origin(
            con,
            old_origin="http://old",
            new_origin="http://example.test",
        )
        assert n2 == 1
        assert con.execute(
            "SELECT origin FROM books WHERE name='keep'"
        ).fetchone()[0] == "http://example.test"
        try:
            remap_book_origin(
                con,
                old_origin="http://example.test",
                new_origin="http://example.test",
                book_url_map={"http://missing/9": "http://example.test/9"},
            )
            raise AssertionError("expected miss")
        except RuntimeError as e:
            assert "miss" in str(e)
        con.close()
    print("legado_db_mutate self-test OK")


if __name__ == "__main__":
    _self_test()
