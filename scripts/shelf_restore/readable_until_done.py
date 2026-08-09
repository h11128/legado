#!/usr/bin/env python3
"""
One-by-one shelf restore/verify until readable.
Priority: rebind to existing source for bookUrl host → restore donor source → search change-source.
Accept only if debug shows toc>0 and content length>50.
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import subprocess
import sys
import time
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

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
    ("https://www.caimoge.net", "采墨阁"),
    ("DragonQuestQBall", "●QB男生"),
    ("DragonQuestQBkd1", "🎉 QB女生"),
    ("DQuestQBall", "🌙 松庭鹤沐"),
    ("http://api.lemiyigou.com", "猫眼看书"),
    ("https://bookshelf.html5.qq.com/", "松鹤阅读"),
    ("https://trxs.cc", "同人小说trxs.cc"),
    ("https://www.ffxs8.com", "饭饭小说www.ffxs8.com"),
    ("https://jpxs123.com", "精品小说"),
]

PROGRESS = ROOT / "readable_progress.jsonl"
STATE = ROOT / "readable_state.json"
DB = ROOT / "live_readable.db"

MARKER_RE = re.compile(
    r"┌获取(书名|作者|详情页链接)\n(?:\[[^\]]+\] )?└([^\n]*)"
)


def _wake_app(_e: Exception, _i: int) -> None:
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


def connect_mcp() -> LegadoMcp:
    return LegadoMcp(connect_retries=25, on_retry=_wake_app)


def host(u: str) -> str:
    try:
        return (urlparse(u).hostname or "").lower()
    except Exception:
        return ""


def registrable(u: str) -> str:
    h = host(u)
    parts = [p for p in h.split(".") if p]
    if len(parts) >= 2:
        return ".".join(parts[-2:])
    return h


def normalize_title(s: str) -> str:
    s = s or ""
    s = re.sub(r"[（(].*?[）)]", "", s)
    s = re.sub(r"[^\w\u4e00-\u9fff]+", "", s)
    return s.lower()


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


# require exact title match for search accept; fuzzy only with author match
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
        if not hn:
            continue
        if hn == nt:
            exact.append(h)
            continue
        if na and ha and na == ha and (nt in hn or hn in nt):
            shorter, longer = (hn, nt) if len(hn) <= len(nt) else (nt, hn)
            if len(shorter) >= 8 and len(shorter) / max(len(longer), 1) >= 0.8:
                fuzzy.append(h)
    return (exact or fuzzy or [None])[0]


def score_readable(log: str) -> dict:
    toc_m = re.search(r"目录总数:(\d+)", log)
    toc = int(toc_m.group(1)) if toc_m else -1
    # content may have timestamp between markers
    m = re.search(
        r"┌获取正文内容\n(?:\[[^\]]+\] )?└([\s\S]*?)(?:\n\[|$)", log
    )
    content = (m.group(1) if m else "").strip()
    # also accept if content section completed with substantial text earlier
    if len(content) < 20:
        m2 = re.search(r"正文页解析完成", log)
        # grab last chunk before that
        if m2:
            chunk = log[max(0, m2.start() - 800) : m2.start()]
            content = chunk
    ok = toc > 0 and len(content) > 50
    return {"ok": ok, "toc": toc, "content_len": len(content)}


def pull_db() -> sqlite3.Connection:
    pull_legado_db(DB, pkg=PKG, min_bytes=1_000_000)
    con = sqlite3.connect(str(DB))
    books = con.execute("select count(*) from books").fetchone()[0]
    if books < 10:
        con.close()
        raise RuntimeError(f"pulled DB books={books} — refuse empty shelf")
    try:
        con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    except Exception:
        pass
    return con


def push_db() -> None:
    if not DB.exists() or DB.stat().st_size < 1_000_000:
        raise RuntimeError(f"refuse push: DB missing/too small ({DB})")
    con = sqlite3.connect(str(DB))
    try:
        ic = con.execute("PRAGMA integrity_check").fetchone()[0]
        books = con.execute("select count(*) from books").fetchone()[0]
        if ic != "ok" or books < 10:
            raise RuntimeError(f"refuse push: integrity={ic} books={books}")
        # URLs we must keep (working copy + anything MCP added mid-run via merge)
        require = [
            r[0]
            for r in con.execute(
                "SELECT bookSourceUrl FROM book_sources WHERE bookSourceComment "
                "LIKE '%shelf-readable-restore%'"
            )
        ]
    finally:
        con.close()
    push_legado_db(DB, pkg=PKG, require_source_urls=require or None)
    time.sleep(2)


def append_progress(row: dict) -> None:
    with open(PROGRESS, "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_done() -> set[str]:
    done = set()
    if PROGRESS.exists():
        for line in PROGRESS.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                o = json.loads(line)
            except Exception:
                continue
            # only skip successful outcomes; retry unresolved/conflict
            if o.get("status") in {
                "readable",
                "restored_readable",
                "rebind_readable",
                "search_readable",
                "dedupe_readable",
            }:
                done.add(o.get("bookUrl_before") or "")
                if o.get("bookUrl_after"):
                    done.add(o["bookUrl_after"])
                if o.get("name") and o.get("author") is not None:
                    done.add(f"name::{o['name']}::{o.get('author')}")
    return done


def verify_book(mcp: LegadoMcp, origin: str, book_url: str, name: str | None = None) -> dict:
    # Never verify by name alone — that can open a different book with similar title.
    keys = [book_url, "::" + book_url]
    best = {"ok": False, "toc": -1, "content_len": 0, "key": None}
    for key in keys:
        for attempt in range(4):
            try:
                log = mcp.debug_source(origin, key, timeout_sec=55)
                if "占用" in log or "请稍后" in log:
                    time.sleep(2 + attempt * 2)
                    continue
                sc = score_readable(log)
                sc["key"] = key
                if sc["ok"]:
                    return sc
                if sc["toc"] > best.get("toc", -1) or sc["content_len"] > best.get(
                    "content_len", 0
                ):
                    best = sc
                break
            except Exception as e:
                best["err"] = str(e)[:120]
                time.sleep(1)
    return best


def clear_manual_intro(intro: str | None) -> str:
    intro = intro or ""
    return intro.replace("[needs_manual_reshelve] ", "").replace(
        "[needs_manual_reshelve]", ""
    )


def build_catalog() -> dict[str, dict]:
    by_url: dict[str, dict] = {}
    candidates = [
        Path(os.environ["LEGADO_ALL_SOURCES_JSON"])
        if os.environ.get("LEGADO_ALL_SOURCES_JSON")
        else None,
        REPO.parent / "legadoSkill" / "temp" / "all_sources.json",
        REPO / "temp" / "all_sources.json",
        ROOT.parent / "backup_0726" / "bookSource.json",
        ROOT.parent / "backup_now" / "bookSource.json",
    ]
    loaded = 0
    for path in candidates:
        if not path or not path.is_file():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, dict) and "data" in data:
            data = data["data"]
        if not isinstance(data, list):
            continue
        for s in data:
            if isinstance(s, dict) and s.get("bookSourceUrl"):
                by_url[s["bookSourceUrl"]] = s
        loaded += 1
        print("catalog+", path, flush=True)
    if not by_url:
        raise FileNotFoundError(
            "No donor catalog found. Set LEGADO_ALL_SOURCES_JSON or place "
            "bookSource.json under temp/shelf_restore/backup_*/"
        )
    print("catalog sources", len(by_url), "files", loaded, flush=True)
    return by_url


def main() -> None:
    lock = ROOT / "readable_worker.lock"
    if lock.exists():
        try:
            old = int(lock.read_text().strip().split()[0])
        except Exception:
            old = -1
        # if lock stale (>2h) ignore; else exit
        age = time.time() - lock.stat().st_mtime
        if age < 7200:
            print(f"another worker lock present pid={old} age={age:.0f}s — exit", flush=True)
            return
    lock.write_text(f"{os.getpid()} {time.time()}\n", encoding="utf-8")
    try:
        _main_inner()
    finally:
        try:
            lock.unlink()
        except Exception:
            pass


def build_items_from_db(con: sqlite3.Connection) -> list[dict]:
    """Default queue when work_queue_all.json is absent."""
    items: list[dict] = []
    seen: set[str] = set()

    def add(row, kind: str) -> None:
        name, author, origin, origin_name, book_url, intro = row
        key = book_url or f"{name}|{author}"
        if key in seen:
            return
        seen.add(key)
        items.append(
            {
                "name": name,
                "author": author,
                "origin": origin,
                "originName": origin_name,
                "bookUrl": book_url,
                "intro": intro or "",
                "work_kind": kind,
            }
        )

    for row in con.execute(
        """
        select name, author, origin, originName, bookUrl, intro from books
        where intro like '%needs_manual_reshelve%'
        order by length(ifnull(name,'')) asc
        """
    ):
        add(row, "manual")
    for row in con.execute(
        """
        select name, author, origin, originName, bookUrl, intro from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ):
        add(row, "missing")
    return items


def _main_inner() -> None:
    print("pull db…", flush=True)
    con = pull_db()
    queue_path = ROOT / "work_queue_all.json"
    items: list[dict] = []
    seen: set[str] = set()

    def add(item: dict, kind: str) -> None:
        key = item.get("bookUrl") or f"{item.get('name')}|{item.get('author')}"
        if key in seen:
            return
        seen.add(key)
        items.append({**item, "work_kind": kind})

    if queue_path.is_file():
        queues = json.loads(queue_path.read_text(encoding="utf-8"))
        for r in queues.get("rebind_existing") or []:
            if "needs_manual" in (r.get("intro") or ""):
                add(r, "rebind")
        for r in queues.get("verify_remapped") or []:
            add(r, "verify")
        for r in queues.get("manual") or []:
            add(r, "manual")
        print("queue file", queue_path, flush=True)
    # Always merge live missing / manual rows so a stale queue file cannot hide orphans.
    for row in build_items_from_db(con):
        add(row, row.get("work_kind") or "db")
    if not items:
        print("empty queue", flush=True)
        return
    print("merged queue size", len(items), flush=True)

    done = load_done()
    print("items", len(items), "already_done_keys", len(done), flush=True)

    print("loading catalog…", flush=True)
    catalog = build_catalog()
    print("catalog", len(catalog), flush=True)

    print("push db / start app…", flush=True)
    push_db()
    print("connect mcp…", flush=True)
    mcp = connect_mcp()
    try:
        print(mcp.reset_channel(), flush=True)
    except Exception as e:
        print("reset_mcp_channel", e, flush=True)
    print("mcp ready", flush=True)
    push_needed = False
    stats = defaultdict(int)

    enabled = {
        r[0]: r[1]
        for r in con.execute(
            "select bookSourceUrl, bookSourceName from book_sources where enabled=1"
        )
    }
    by_reg: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for u, n in enabled.items():
        rg = registrable(u)
        if rg:
            by_reg[rg].append((u, n))

    def find_bind(book_url: str) -> tuple[str, str] | None:
        hb = host(book_url)
        if not hb:
            return None
        rg = registrable(book_url)
        cands = by_reg.get(rg) or []
        exact = [(u, n) for u, n in cands if host(u) == hb]
        pick = exact or cands
        return pick[0] if pick else None

    def save_donor(origin_url: str, donor: dict) -> bool:
        s = deepcopy(donor)
        s["bookSourceUrl"] = origin_url
        s["enabled"] = True
        g = s.get("bookSourceGroup") or ""
        s["bookSourceGroup"] = (
            ",".join(
                p.strip()
                for p in g.split(",")
                if p.strip() and p.strip() != "网站失效"
            )
            or "未整理"
        )
        s["bookSourceComment"] = (s.get("bookSourceComment") or "") + "\n# shelf-readable-restore"
        msg = mcp.save_source(s)
        ok = "失败" not in msg
        if ok:
            # Keep working DB in sync so a later push cannot wipe this URL
            # even if merge_live_sources is somehow skipped.
            try:
                from lib.legado_db_mutate import upsert_book_source

                upsert_book_source(con, s)
                con.commit()
            except Exception as e:
                print(f"warn: local upsert after save_donor failed: {e}", flush=True)
        return ok

    def apply_update(
        book_url_before: str,
        origin: str,
        origin_name: str,
        book_url_after: str,
        intro: str,
    ) -> None:
        nonlocal push_needed
        existing = {
            r[0] for r in con.execute("select bookUrl from books")
        }
        if book_url_after != book_url_before and book_url_after in existing:
            raise RuntimeError("bookUrl conflict")
        con.execute(
            """
            update books
            set origin=?, originName=?, bookUrl=?, intro=?, tocUrl=''
            where bookUrl=?
            """,
            (origin, origin_name, book_url_after, clear_manual_intro(intro), book_url_before),
        )
        con.commit()
        push_needed = True
        # refresh enabled maps if needed
        enabled[origin] = origin_name

    # Process with periodic push
    processed = 0
    for item in items:
        name = item.get("name") or ""
        author = item.get("author") or ""
        book_url = item.get("bookUrl") or ""
        origin = item.get("origin") or ""
        intro = item.get("intro") or ""
        # refresh row from DB (may have changed)
        row = con.execute(
            "select name, author, origin, originName, bookUrl, intro from books where bookUrl=?",
            (book_url,),
        ).fetchone()
        if not row:
            # try by name+author
            row = con.execute(
                "select name, author, origin, originName, bookUrl, intro from books where name=? and ifnull(author,'')=?",
                (name, author),
            ).fetchone()
        if not row:
            stats["missing_row"] += 1
            continue
        name, author, origin, origin_name, book_url, intro = row
        intro = intro or ""
        key_name = f"name::{name}::{author}"
        if book_url in done or key_name in done:
            stats["skip_done"] += 1
            continue

        # Already readable? verify current binding first for remapped/manual
        work = item["work_kind"]
        print(f"\n== [{work}] {name} | {origin} | {book_url[:70]}")

        # Step A: if bookUrl host has enabled source, rebind & verify
        bind = find_bind(book_url)
        if bind and bind[0] != origin:
            sc = verify_book(mcp, bind[0], book_url, name)
            if sc.get("ok"):
                apply_update(book_url, bind[0], bind[1], book_url, intro)
                rec = {
                    "status": "rebind_readable",
                    "name": name,
                    "author": author,
                    "bookUrl_before": book_url,
                    "origin": bind[0],
                    "toc": sc["toc"],
                    "content_len": sc["content_len"],
                }
                append_progress(rec)
                done.add(book_url)
                done.add(key_name)
                stats["rebind_readable"] += 1
                print("  REBIND OK", bind[0], sc, flush=True)
                processed += 1
                if push_needed and processed % 5 == 0:
                    push_db()
                    mcp = connect_mcp()
                    push_needed = False
                continue

        # Step B: verify current origin+bookUrl
        if origin in enabled or not str(origin).startswith("http") or origin:
            sc = verify_book(mcp, origin, book_url, name)
            if sc.get("ok"):
                if "needs_manual" in intro:
                    apply_update(book_url, origin, origin_name, book_url, intro)
                rec = {
                    "status": "readable",
                    "name": name,
                    "author": author,
                    "bookUrl_before": book_url,
                    "origin": origin,
                    "toc": sc["toc"],
                    "content_len": sc["content_len"],
                }
                append_progress(rec)
                done.add(book_url)
                done.add(key_name)
                stats["already_readable"] += 1
                print("  ALREADY OK", sc, flush=True)
                processed += 1
                continue
            print("  current not readable", sc, flush=True)
        else:
            sc = {"ok": False, "toc": -1, "content_len": 0}

        # Step C: restore donor for bookUrl registrable / exact old origin
        restored = False
        rg = registrable(book_url)
        donor = None
        donor_url = None
        # prefer exact bookUrl origin-like URLs in catalog
        for u, s in catalog.items():
            if registrable(u) == rg and rg:
                donor, donor_url = s, u
                break
        if donor and rg:
            # choose bookSourceUrl as scheme+host root of bookUrl
            bu_host = host(book_url)
            # try common forms
            candidates = [
                f"https://{bu_host}",
                f"https://{bu_host}/",
                f"http://{bu_host}",
                f"http://{bu_host}/",
                donor_url,
            ]
            target = None
            for cand in candidates:
                if cand in enabled:
                    target = cand
                    break
            if target is None:
                target = candidates[0]
                if target not in enabled:
                    ok_save = save_donor(target, donor)
                    print("  restore source", target, ok_save, flush=True)
                    if ok_save:
                        enabled[target] = donor.get("bookSourceName") or target
                        by_reg[registrable(target)].append(
                            (target, enabled[target])
                        )
                    else:
                        target = None
            if target:
                sc2 = verify_book(mcp, target, book_url, name)
                if sc2.get("ok"):
                    apply_update(
                        book_url,
                        target,
                        enabled.get(target) or target,
                        book_url,
                        intro,
                    )
                    append_progress(
                        {
                            "status": "restored_readable",
                            "name": name,
                            "author": author,
                            "bookUrl_before": book_url,
                            "origin": target,
                            "toc": sc2["toc"],
                            "content_len": sc2["content_len"],
                        }
                    )
                    done.add(book_url)
                    done.add(key_name)
                    stats["restored_readable"] += 1
                    print("  RESTORE OK", target, sc2, flush=True)
                    restored = True
                    processed += 1
                    if push_needed and processed % 5 == 0:
                        push_db()
                        mcp = connect_mcp()
                        push_needed = False
        if restored:
            continue

        # Step D: search change-source then verify BEFORE accepting
        base = re.split(r"[（(]", name)[0].strip() or name
        base = base[:40]
        alts = [base]
        stripped = re.sub(r"^(火影|斗破|斗罗|玄幻|娱乐|洪荒|木叶|海贼|美利坚|都市)[：:]", "", base)
        if stripped and stripped != base:
            alts.append(stripped)
        short = re.split(r"[，,！!？?\s]", base)[0].strip()
        if short and short not in alts and len(short) >= 4:
            alts.append(short)
        found = None
        print(f"  search… keys={alts!r}", flush=True)
        for search_key in alts:
            if found:
                break
            for src_url, src_name in SEARCH_SOURCES:
                try:
                    log = mcp.debug_source(src_url, search_key, timeout_sec=40)
                    if "占用" in log:
                        print("  channel busy, sleep", flush=True)
                        time.sleep(5)
                        mcp.reset_channel()
                        log = mcp.debug_source(src_url, search_key, timeout_sec=40)
                    hits = parse_search_hits(log)
                    hit = pick_hit(hits, name, author)
                    print(
                        f"  key={search_key!r} src={src_url} hits={len(hits)} pick={hit.get('name') if hit else None}",
                        flush=True,
                    )
                    if not hit:
                        continue
                    sc3 = verify_book(mcp, src_url, hit["bookUrl"], hit["name"])
                    if sc3.get("ok"):
                        found = (src_url, src_name, hit, sc3)
                        break
                    print("  pick not readable", sc3, flush=True)
                except Exception as e:
                    print("  search err", src_url, e, flush=True)
                    continue
        if found:
            src_url, src_name, hit, sc3 = found
            new_bu = hit["bookUrl"]
            existing_row = con.execute(
                "select name, author, bookUrl from books where bookUrl=?",
                (new_bu,),
            ).fetchone()
            if existing_row and new_bu != book_url:
                en_name, en_author, _ = existing_row
                # same work already on shelf → drop current orphan row
                if normalize_title(en_name) == normalize_title(name) or (
                    normalize_title(en_name) == normalize_title(hit["name"])
                ):
                    con.execute("delete from books where bookUrl=?", (book_url,))
                    # clear manual on survivor if any
                    intro2 = con.execute(
                        "select intro from books where bookUrl=?", (new_bu,)
                    ).fetchone()
                    intro2 = (intro2[0] if intro2 else "") or ""
                    con.execute(
                        "update books set origin=?, originName=?, intro=? where bookUrl=?",
                        (src_url, src_name, clear_manual_intro(intro2), new_bu),
                    )
                    con.commit()
                    push_needed = True
                    append_progress(
                        {
                            "status": "dedupe_readable",
                            "name": name,
                            "author": author,
                            "bookUrl_before": book_url,
                            "origin": src_url,
                            "bookUrl_after": new_bu,
                            "toc": sc3["toc"],
                            "content_len": sc3["content_len"],
                        }
                    )
                    done.add(book_url)
                    done.add(key_name)
                    done.add(new_bu)
                    stats["dedupe_readable"] += 1
                    print("  DEDUPE OK", new_bu, sc3, flush=True)
                    processed += 1
                    if push_needed and processed % 3 == 0:
                        push_db()
                        mcp = connect_mcp()
                        try:
                            mcp.reset_channel()
                        except Exception:
                            pass
                        push_needed = False
                    continue
                append_progress(
                    {
                        "status": "conflict",
                        "name": name,
                        "author": author,
                        "bookUrl_before": book_url,
                        "hit": hit,
                        "existing": {"name": en_name, "author": en_author},
                    }
                )
                stats["conflict"] += 1
                print("  CONFLICT", new_bu, flush=True)
                continue
            apply_update(book_url, src_url, src_name, new_bu, intro)
            append_progress(
                {
                    "status": "search_readable",
                    "name": name,
                    "author": author,
                    "bookUrl_before": book_url,
                    "origin": src_url,
                    "bookUrl_after": new_bu,
                    "hit_name": hit["name"],
                    "toc": sc3["toc"],
                    "content_len": sc3["content_len"],
                }
            )
            done.add(book_url)
            done.add(key_name)
            done.add(new_bu)
            stats["search_readable"] += 1
            print("  SEARCH OK", hit["name"], sc3, flush=True)
            processed += 1
            if push_needed and processed % 3 == 0:
                push_db()
                mcp = connect_mcp()
                try:
                    mcp.reset_channel()
                except Exception:
                    pass
                push_needed = False
            continue

        # Don't permanently burn unresolved in resume set — allow retry next run
        append_progress(
            {
                "status": "unresolved",
                "name": name,
                "author": author,
                "bookUrl_before": book_url,
                "origin": origin,
                "work_kind": work,
            }
        )
        stats["unresolved"] += 1
        print("  UNRESOLVED", flush=True)
        processed += 1

    if push_needed:
        push_db()

    # final counts
    con2 = pull_db()
    manual_left = con2.execute(
        "select count(*) from books where intro like '%needs_manual_reshelve%'"
    ).fetchone()[0]
    miss = con2.execute(
        """
        select count(*) from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchone()[0]
    con2.close()
    push_db()
    summary = {
        "stats": dict(stats),
        "manual_left": manual_left,
        "missing": miss,
        "processed_this_run": processed,
    }
    STATE.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print("SUMMARY", summary)


if __name__ == "__main__":
    main()
