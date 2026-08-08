#!/usr/bin/env python3
"""LEGACY one-shot: Phase 2–4 rebuild/migrate/fallback (2026-08 shelf restore).

Do not use for daily work. Prefer:
  python scripts/shelf-restore-pick-books.py
  python scripts/shelf-restore-readable.py
  python scripts/shelf-restore-change-source.py

Requires: --i-know-this-is-legacy
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
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO / "temp" / "shelf_restore" / "queue"
SHELF = ROOT.parent
ROOT.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(REPO / "scripts"))
from lib.legado_mcp import resolve_mcp_url  # noqa: E402

MCP, TOKEN = resolve_mcp_url()
PKG = os.environ.get("LEGADO_DEBUG_PKG", "com.legado.app.debug")
os.environ["MSYS_NO_PATHCONV"] = "1"


class Mcp:
    def __init__(self) -> None:
        self.sess = None
        self._id = 0
        self._post(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "rebuild2", "version": "0"},
                },
            }
        )
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def _post(self, payload: dict) -> str:
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
        with urllib.request.urlopen(req, timeout=180) as r:
            if not self.sess:
                self.sess = r.headers.get("Mcp-Session-Id")
            return r.read().decode()

    def call(self, name: str, args: dict) -> dict:
        self._id += 1
        return json.loads(
            self._post(
                {
                    "jsonrpc": "2.0",
                    "id": self._id,
                    "method": "tools/call",
                    "params": {"name": name, "arguments": args},
                }
            )
        )


def registrable(url: str) -> str:
    try:
        h = urlparse(url).hostname or ""
        parts = [p for p in h.split(".") if p]
        if len(parts) >= 2:
            return ".".join(parts[-2:])
        return h
    except Exception:
        return ""


def bare(s: str | None) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]+", "", s or "")


def pull_db(dest: Path) -> None:
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1.2)
    with open(dest, "wb") as f:
        subprocess.check_call(
            ["adb", "exec-out", "run-as", PKG, "cat", "databases/legado.db"], stdout=f
        )
    for suf in ("-wal", "-shm"):
        try:
            with open(str(dest) + suf, "wb") as f:
                subprocess.check_call(
                    [
                        "adb",
                        "exec-out",
                        "run-as",
                        PKG,
                        "cat",
                        f"databases/legado.db{suf}",
                    ],
                    stdout=f,
                )
        except Exception:
            pass


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
    candidates = [
        Path(os.environ["LEGADO_ALL_SOURCES_JSON"])
        if os.environ.get("LEGADO_ALL_SOURCES_JSON")
        else None,
        REPO.parent / "legadoSkill" / "temp" / "all_sources.json",
        REPO / "temp" / "all_sources.json",
    ]
    all_path = next((p for p in candidates if p and p.is_file()), None)
    if all_path is None:
        raise FileNotFoundError("all_sources.json missing; set LEGADO_ALL_SOURCES_JSON")
    all_data = json.loads(all_path.read_text(encoding="utf-8"))["data"]
    backup = json.loads((SHELF / "backup_0726/bookSource.json").read_text(encoding="utf-8"))
    catalog = [
        s for s in all_data + backup if isinstance(s, dict) and s.get("bookSourceUrl")
    ]
    by_url = {s["bookSourceUrl"]: s for s in catalog}

    miss = json.loads((ROOT / "still_missing_after_clone.json").read_text(encoding="utf-8"))
    classed = {
        c["origin"]: c
        for c in json.loads((ROOT / "classified_all.json").read_text(encoding="utf-8"))
    }
    already = set(
        json.loads((ROOT / "rebuild_clone_report.json").read_text(encoding="utf-8"))["ok"]
    )

    by_reg: dict[str, list] = defaultdict(list)
    for s in catalog:
        by_reg[registrable(s["bookSourceUrl"])].append(s)
    by_bare: dict[str, list] = defaultdict(list)
    for s in catalog:
        by_bare[bare(s.get("bookSourceName"))].append(s)

    extra_map: dict[str, tuple[str, dict]] = {}
    for m in miss:
        o = m["origin"]
        if o in already or not o.startswith("http"):
            continue
        c = classed.get(o, {})
        bucket = c.get("bucket", "")
        if o in by_url:
            extra_map[o] = ("exact", by_url[o])
            continue
        reg = registrable(o)
        cands = [s for s in by_reg.get(reg, []) if s["bookSourceUrl"] != o]
        if cands:
            cands = sorted(
                cands,
                key=lambda s: (0 if s.get("enabled") else 1, len(s.get("bookSourceUrl") or "")),
            )
            extra_map[o] = ("same_reg", cands[0])
            continue
        if bucket == "B_html_alive":
            name = bare(c.get("originName") or "")
            host = (urlparse(o).hostname or "").replace("www.", "").replace("m.", "")
            hits = by_bare.get(name, [])
            related = [
                s
                for s in hits
                if any(
                    tok in (s.get("bookSourceUrl") or "")
                    for tok in [host[:5], host.split(".")[0][:5]]
                    if tok
                )
            ]
            if related:
                extra_map[o] = ("same_name", related[0])

    print("extra donors", len(extra_map))

    aes_donor = None
    for s in catalog:
        url = s.get("bookSourceUrl") or ""
        comment = s.get("bookSourceComment") or ""
        if (
            "s.pjxhmy.com" in url
            or "s.mocaiys.com" in url
            or ("值得阅读" in (s.get("bookSourceName") or "") and "decode(str)" in comment)
        ):
            aes_donor = s
            break
    if not aes_donor:
        for s in catalog:
            if "decode(str)" in (s.get("bookSourceComment") or "") and (
                s.get("bookSourceUrl") or ""
            ).startswith("https://s."):
                aes_donor = s
                break
    print(
        "aes_donor",
        aes_donor.get("bookSourceUrl") if aes_donor else None,
        aes_donor.get("bookSourceName") if aes_donor else None,
    )

    c_targets = [
        m["origin"]
        for m in miss
        if classed.get(m["origin"], {}).get("bucket") == "C_app_api"
        and m["origin"].startswith("http")
    ]
    print("C targets", c_targets)

    mcp = Mcp()
    clone_ok: list[dict] = []
    clone_fail: list[dict] = []
    aes_report: list[dict] = []

    def save_clone(origin: str, donor: dict, tag: str) -> bool:
        s = deepcopy(donor)
        s["bookSourceUrl"] = origin
        on = classed.get(origin, {}).get("originName")
        if on:
            s["bookSourceName"] = on
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
        s["bookSourceComment"] = (
            (s.get("bookSourceComment") or "")
            + f"\n# shelf-rebuild {tag} from {donor.get('bookSourceUrl')}"
        )
        if tag.startswith("aes"):
            old = urlparse(donor["bookSourceUrl"])
            new = urlparse(origin)
            old_host, new_host = old.netloc, new.netloc
            for field in ["searchUrl", "exploreUrl", "loginUrl"]:
                if s.get(field) and old_host and old_host in str(s[field]):
                    s[field] = str(s[field]).replace(old_host, new_host)
            for rule_key in [
                "ruleSearch",
                "ruleBookInfo",
                "ruleToc",
                "ruleContent",
                "ruleExplore",
            ]:
                if isinstance(s.get(rule_key), dict):
                    blob = json.dumps(s[rule_key], ensure_ascii=False)
                    if old_host in blob:
                        s[rule_key] = json.loads(blob.replace(old_host, new_host))
        resp = mcp.call(
            "save_source",
            {"source": json.dumps(s, ensure_ascii=False), "format": "json"},
        )
        msg = resp["result"]["content"][0]["text"]
        if resp["result"].get("isError") or "失败" in msg:
            clone_fail.append({"origin": origin, "err": msg[:120], "tag": tag})
            return False
        clone_ok.append(
            {"origin": origin, "donor": donor.get("bookSourceUrl"), "tag": tag}
        )
        print("OK", tag, origin)
        return True

    count_of = {m["origin"]: m["bookCount"] for m in miss}
    for o, (reason, donor) in sorted(
        extra_map.items(), key=lambda x: -count_of.get(x[0], 0)
    ):
        save_clone(o, donor, reason)

    if aes_donor:
        for o in c_targets:
            ok = save_clone(o, aes_donor, "aes_migrate")
            aes_report.append({"origin": o, "ok": ok})
    else:
        print("NO AES DONOR")

    (ROOT / "rebuild_extra_clone_report.json").write_text(
        json.dumps(
            {"ok": clone_ok, "fail": clone_fail, "aes": aes_report},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print("clones ok", len(clone_ok), "fail", len(clone_fail))

    db = ROOT / "live_work.db"
    pull_db(db)
    con = sqlite3.connect(str(db))
    try:
        con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    except Exception:
        pass

    ok_books = con.execute(
        """
        select name, author, origin, originName, bookUrl from books
        where origin in (select bookSourceUrl from book_sources where enabled=1)
        """
    ).fetchall()
    idx: dict[tuple, list] = defaultdict(list)
    for n, a, o, on, bu in ok_books:
        idx[(n or "", a or "")].append(
            {"origin": o, "originName": on, "bookUrl": bu}
        )

    existing = {r[0] for r in con.execute("select bookUrl from books")}
    missing = con.execute(
        """
        select name, author, origin, originName, bookUrl from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchall()

    fb = con.execute(
        """
        select b.origin, bs.bookSourceName, count(*) c
        from books b
        join book_sources bs on b.origin = bs.bookSourceUrl
        where bs.enabled = 1
          and (bs.bookSourceType is null or bs.bookSourceType = 0)
        group by b.origin
        order by c desc
        limit 1
        """
    ).fetchone()
    print("fallback source", fb)
    fb_origin, fb_name, _ = fb

    remap_n = fallback_n = 0
    remap_rows: list[dict] = []
    fallback_rows: list[dict] = []

    for n, a, o, on, bu in missing:
        hits = [h for h in idx.get((n or "", a or ""), []) if h["bookUrl"] != bu]
        applied = False
        if hits:
            h = hits[0]
            new_bu = h["bookUrl"]
            if new_bu not in existing or new_bu == bu:
                con.execute(
                    "update books set origin=?, originName=?, bookUrl=? where bookUrl=?",
                    (h["origin"], h["originName"], new_bu, bu),
                )
                existing.discard(bu)
                existing.add(new_bu)
                remap_n += 1
                remap_rows.append(
                    {"from": bu, "to": new_bu, "origin": h["origin"], "name": n}
                )
                applied = True
        if applied:
            continue
        intro_row = con.execute(
            "select intro from books where bookUrl=?", (bu,)
        ).fetchone()
        intro = (intro_row[0] if intro_row else "") or ""
        if "needs_manual_reshelve" not in intro:
            intro = ("[needs_manual_reshelve] " + intro)[:500]
        con.execute(
            "update books set origin=?, originName=?, intro=? where bookUrl=?",
            (fb_origin, fb_name or "shelf_fallback", intro, bu),
        )
        fallback_n += 1
        fallback_rows.append(
            {
                "bookUrl": bu,
                "name": n,
                "old_origin": o,
                "new_origin": fb_origin,
            }
        )

    con.commit()
    miss_left = con.execute(
        """
        select count(*) from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
        """
    ).fetchone()[0]
    en_bad = con.execute(
        """
        select count(*) from books b
        join book_sources s on b.origin = s.bookSourceUrl
        where b.origin not like 'loc_%' and s.enabled = 0
        """
    ).fetchone()[0]
    print(
        "missing left",
        miss_left,
        "disabled origin books",
        en_bad,
        "remapped",
        remap_n,
        "fallback",
        fallback_n,
    )
    (ROOT / "change_source_report.json").write_text(
        json.dumps(
            {
                "remapped": remap_rows,
                "fallback": fallback_rows,
                "fallback_origin": fb_origin,
                "missing_left": miss_left,
                "disabled_origin_books": en_bad,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    con.close()
    push_db(db)
    print("done push")


if __name__ == "__main__":
    if "--i-know-this-is-legacy" not in sys.argv:
        raise SystemExit(
            "legacy_phase234 is a one-shot 2026-08 rebuild. "
            "Daily path: shelf-restore-pick-books / readable / change-source. "
            "Re-run only with --i-know-this-is-legacy"
        )
    main()
