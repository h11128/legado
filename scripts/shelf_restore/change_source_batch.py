#!/usr/bin/env python3
"""Batch change-source for shelf books that need remapping (MCP debug search).

Prefer: python scripts/shelf-restore-change-source.py
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO / "temp" / "shelf_restore" / "queue"
ROOT.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(REPO / "scripts"))
from lib.legado_mcp import resolve_mcp_url  # noqa: E402

MCP, TOKEN = resolve_mcp_url()
PKG = os.environ.get("LEGADO_DEBUG_PKG", "com.legado.app.debug")
os.environ["MSYS_NO_PATHCONV"] = "1"

SEARCH_SOURCES = [
    ("http://api.lemiyigou.com", "猫眼看书"),
    ("DragonQuestQBall", "●QB男生"),
    ("DragonQuestQBkd1", "🎉 QB女生"),
    ("https://bookshelf.html5.qq.com/", "松鹤阅读"),
    ("https://trxs.cc", "同人小说trxs.cc"),
]

MAX_BOOKS = 450
MAX_SECONDS = 35 * 60


class Mcp:
    def __init__(self) -> None:
        self.sess = None
        self._id = 0
        last: Exception | None = None
        for _ in range(20):
            try:
                self._post(
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {
                            "protocolVersion": "2024-11-05",
                            "capabilities": {},
                            "clientInfo": {"name": "chg2", "version": "0"},
                        },
                    }
                )
                self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
                return
            except Exception as e:
                last = e
                time.sleep(2)
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
        raise RuntimeError(last)

    def _post(self, payload: dict, timeout: int = 100) -> str:
        h = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "X-Legado-Token": TOKEN,
        }
        if self.sess:
            h["Mcp-Session-Id"] = self.sess
        req = urllib.request.Request(
            MCP, data=json.dumps(payload).encode(), headers=h, method="POST"
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if not self.sess:
                self.sess = r.headers.get("Mcp-Session-Id")
            return r.read().decode()

    def call(self, name: str, args: dict, timeout: int = 100) -> dict:
        self._id += 1
        return json.loads(
            self._post(
                {
                    "jsonrpc": "2.0",
                    "id": self._id,
                    "method": "tools/call",
                    "params": {"name": name, "arguments": args},
                },
                timeout=timeout,
            )
        )


def normalize_title(s: str) -> str:
    s = s or ""
    s = re.sub(r"[（(].*?[）)]", "", s)
    s = re.sub(r"[^\w\u4e00-\u9fff]+", "", s)
    return s.lower()


MARKER_RE = re.compile(
    r"┌获取(书名|作者|详情页链接)\n(?:\[[^\]]+\] )?└([^\n]*)"
)


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
        if hn == nt:
            if not na or not ha or ha == na:
                exact.append(h)
            else:
                fuzzy.append(h)
        elif hn and (nt in hn or hn in nt):
            # avoid over-short false positives like 华娱
            shorter, longer = (hn, nt) if len(hn) <= len(nt) else (nt, hn)
            if len(shorter) >= 4 and len(shorter) / max(len(longer), 1) >= 0.55:
                fuzzy.append(h)
    return (exact or fuzzy or [None])[0]


def pull_db(dest: Path) -> None:
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1.0)
    with open(dest, "wb") as f:
        subprocess.check_call(
            ["adb", "exec-out", "run-as", PKG, "cat", "databases/legado.db"], stdout=f
        )


def push_db(src: Path) -> None:
    subprocess.check_call(["adb", "push", str(src), "/data/local/tmp/legado_work.db"])
    subprocess.check_call(
        [
            "adb",
            "shell",
            f"run-as {PKG} cp /data/local/tmp/legado_work.db databases/legado.db "
            f"&& run-as {PKG} rm -f databases/legado.db-wal databases/legado.db-shm",
        ]
    )
    subprocess.check_call(
        ["adb", "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def main() -> None:
    # unit-check parser against saved log if present
    sample = ROOT / "debug_lemi_hypnosis.txt"
    if sample.exists():
        hits = parse_search_hits(sample.read_text(encoding="utf-8"))
        print("parser selfcheck hits", len(hits), hits[:2])
        assert hits and hits[0]["name"] == "催眠手机", hits[:1]

    db = ROOT / "live_chg2.db"
    pull_db(db)
    con = sqlite3.connect(str(db))
    rows = con.execute(
        """
        select name, author, origin, originName, bookUrl
        from books
        where intro like '%needs_manual_reshelve%'
        order by length(name) asc
        """
    ).fetchall()
    seen = set()
    queue = []
    for r in rows:
        key = (r[0] or "", r[1] or "")
        if key in seen:
            continue
        seen.add(key)
        queue.append(r)
        if len(queue) >= MAX_BOOKS:
            break
    print("queue", len(queue), "of manual", len(rows))

    mcp = Mcp()
    existing = {r[0] for r in con.execute("select bookUrl from books")}
    changed = []
    failed = []
    t0 = time.time()

    for idx, (name, author, origin, origin_name, book_url) in enumerate(queue):
        if time.time() - t0 > MAX_SECONDS:
            print("time budget stop", idx)
            break
        search_key = re.split(r"[（(]", (name or "").strip())[0].strip() or (name or "")
        search_key = search_key[:40]
        if not search_key:
            failed.append({"name": name, "reason": "empty_name"})
            continue

        hit = None
        used = None
        raw_sizes = {}
        for src_url, src_name in SEARCH_SOURCES:
            try:
                d = mcp.call(
                    "debug_source",
                    {"url": src_url, "key": search_key, "timeoutSec": 35},
                    timeout=50,
                )
                log = d["result"]["content"][0]["text"]
                m = re.search(r"列表大小:(\d+)", log.split("开始解析详情页")[0])
                raw_sizes[src_url] = int(m.group(1)) if m else -1
                hits = parse_search_hits(log)
                pick = pick_hit(hits, name or "", author or "")
                if pick:
                    hit = pick
                    used = (src_url, src_name)
                    break
            except Exception as e:
                raw_sizes[src_url] = f"err:{e}"
                continue

        if not hit or not used:
            failed.append(
                {
                    "name": name,
                    "author": author,
                    "reason": "no_hit",
                    "sizes": raw_sizes,
                }
            )
            print(f"[{idx}] MISS {name} sizes={raw_sizes}")
            continue

        new_bu = hit["bookUrl"]
        if new_bu in existing and new_bu != book_url:
            failed.append(
                {"name": name, "reason": "bookUrl_conflict", "hit": new_bu}
            )
            print(f"[{idx}] CONFLICT {name}")
            continue

        intro = con.execute(
            "select intro from books where bookUrl=?", (book_url,)
        ).fetchone()
        intro = (intro[0] if intro else "") or ""
        intro = intro.replace("[needs_manual_reshelve] ", "").replace(
            "[needs_manual_reshelve]", ""
        )
        con.execute(
            """
            update books
            set origin=?, originName=?, bookUrl=?, intro=?, tocUrl=''
            where bookUrl=?
            """,
            (used[0], used[1], new_bu, intro, book_url),
        )
        existing.discard(book_url)
        existing.add(new_bu)
        changed.append(
            {
                "name": name,
                "author": author,
                "from_origin": origin,
                "to_origin": used[0],
                "to_bookUrl": new_bu,
                "hit_name": hit["name"],
                "hit_author": hit.get("author"),
            }
        )
        print(f"[{idx}] OK {name} -> {hit['name']} @ {used[0]}")
        if idx % 10 == 9:
            con.commit()

    con.commit()
    miss = con.execute(
        """
        select count(*) from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchone()[0]
    manual = con.execute(
        "select count(*) from books where intro like '%needs_manual_reshelve%'"
    ).fetchone()[0]
    print(
        "changed",
        len(changed),
        "failed",
        len(failed),
        "missing",
        miss,
        "manual_left",
        manual,
        "elapsed",
        round(time.time() - t0, 1),
    )
    (ROOT / "real_change_source_report_v2.json").write_text(
        json.dumps(
            {
                "changed": changed,
                "failed": failed,
                "missing": miss,
                "manual_left": manual,
                "elapsed_s": round(time.time() - t0, 1),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    con.close()
    push_db(db)
    print("pushed")


if __name__ == "__main__":
    main()
