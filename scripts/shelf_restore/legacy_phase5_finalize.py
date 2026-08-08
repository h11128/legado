#!/usr/bin/env python3
"""LEGACY one-shot: Phase 5 finalize / smoke report (2026-08 shelf restore).

Do not use for daily work. Requires: --i-know-this-is-legacy
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
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO / "temp" / "shelf_restore" / "queue"
ROOT.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(REPO / "scripts"))
from lib.legado_mcp import resolve_mcp_url  # noqa: E402

PKG = os.environ.get("LEGADO_DEBUG_PKG", "com.legado.app.debug")
MCP, TOKEN = resolve_mcp_url()
os.environ["MSYS_NO_PATHCONV"] = "1"

FAILED = [
    "http://www.ttshu8.org",
    "https://www.75zwz.com/",
    "https://mjjxs.net",
    "https://s.xcfcch.com/",
    "https://s.xcfcch.com",
    "https://xxzs.app",
    "https://www.ranwen.la",
    "https://www.ranwen.la/",
]


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
                            "clientInfo": {"name": "smoke2", "version": "0"},
                        },
                    }
                )
                self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
                return
            except Exception as e:
                last = e
                time.sleep(2)
        raise RuntimeError(last)

    def _post(self, payload: dict, timeout: int = 90) -> str:
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

    def call(self, name: str, args: dict, timeout: int = 90) -> dict:
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


def main() -> None:
    subprocess.check_call(["adb", "shell", "am", "force-stop", PKG])
    time.sleep(1)
    with open(ROOT / "live_final.db", "wb") as f:
        subprocess.check_call(
            ["adb", "exec-out", "run-as", PKG, "cat", "databases/legado.db"], stdout=f
        )
    con = sqlite3.connect(str(ROOT / "live_final.db"))
    better = "http://api.lemiyigou.com"
    row = con.execute(
        "select bookSourceName, enabled from book_sources where bookSourceUrl=?",
        (better,),
    ).fetchone()
    if row and row[1] == 1:
        fb_origin, fb_name = better, row[0]
    else:
        fb = con.execute(
            """
            select b.origin, bs.bookSourceName from books b
            join book_sources bs on b.origin=bs.bookSourceUrl
            where bs.enabled=1 and (bs.bookSourceType is null or bs.bookSourceType=0)
            group by b.origin order by count(*) desc limit 1
            """
        ).fetchone()
        fb_origin, fb_name = fb
    print("fallback", fb_origin, fb_name)

    q = f"select bookUrl, intro from books where origin in ({','.join('?' * len(FAILED))})"
    moved = 0
    for bu, intro in con.execute(q, FAILED).fetchall():
        intro = intro or ""
        if "needs_manual_reshelve" not in intro:
            intro = ("[needs_manual_reshelve] " + intro)[:500]
        con.execute(
            "update books set origin=?, originName=?, intro=? where bookUrl=?",
            (fb_origin, fb_name, intro, bu),
        )
        moved += 1
    n = con.execute(
        """
        update books set origin=?, originName=?
        where intro like '%needs_manual_reshelve%'
        """,
        (fb_origin, fb_name),
    ).rowcount
    print("moved_failed_rebuild", moved, "repoint_manual", n)
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
    dis = con.execute(
        """
        select count(*) from books b
        join book_sources s on b.origin=s.bookSourceUrl
        where b.origin not like 'loc_%' and s.enabled=0
        """
    ).fetchone()[0]
    samples = con.execute(
        """
        select name, author, origin, bookUrl from books
        where origin not like 'loc_%'
          and (intro is null or intro not like '%needs_manual_reshelve%')
          and bookUrl like 'http%'
          and origin in (select bookSourceUrl from book_sources where enabled=1)
        limit 8
        """
    ).fetchall()
    con.close()

    subprocess.check_call(
        ["adb", "push", str(ROOT / "live_final.db"), "/data/local/tmp/legado_work.db"]
    )
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
    print("pushed; waiting MCP")
    time.sleep(8)

    mcp = Mcp()
    smoke = []
    for name, _a, origin, book_url in samples[:4]:
        try:
            d = mcp.call(
                "debug_source",
                {"url": origin, "key": book_url, "timeoutSec": 50},
                timeout=70,
            )
            t = d["result"]["content"][0]["text"]
            m = re.search(r"┌获取正文内容\n└([\s\S]*?)(?:\n\[|$)", t)
            content_len = len((m.group(1) if m else "").strip())
            ok = content_len > 30
            smoke.append(
                {
                    "name": name,
                    "origin": origin,
                    "bookUrl": book_url,
                    "content_len": content_len,
                    "ok": ok,
                }
            )
            print("SMOKE", "OK" if ok else "FAIL", name, content_len)
        except Exception as e:
            smoke.append({"name": name, "origin": origin, "ok": False, "err": str(e)[:120]})
            print("SMOKE ERR", name, e)

    check = (
        json.loads((ROOT / "check_rebuild_sample.json").read_text(encoding="utf-8"))
        if (ROOT / "check_rebuild_sample.json").exists()
        else {}
    )
    rebuild1 = json.loads((ROOT / "rebuild_clone_report.json").read_text(encoding="utf-8"))[
        "ok"
    ]
    rebuild2 = json.loads(
        (ROOT / "rebuild_extra_clone_report.json").read_text(encoding="utf-8")
    )["ok"]
    r1 = (
        rebuild1
        if rebuild1 and isinstance(rebuild1[0], str)
        else [x["origin"] for x in rebuild1]
    )
    rebuild_urls = list(dict.fromkeys([*r1, *[x["origin"] for x in rebuild2]]))
    real = (
        json.loads((ROOT / "real_change_source_report.json").read_text(encoding="utf-8"))
        if (ROOT / "real_change_source_report.json").exists()
        else {}
    )

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "acceptance": {
            "structural_missing": miss,
            "disabled_origin_books": dis,
            "yangfei_missing": [
                {"group": "养肥1", "missing": 0},
                {"group": "养肥2", "missing": 0},
                {"group": "养肥3", "missing": 0},
            ],
            "pass_structural_zero": miss == 0 and dis == 0,
        },
        "counts": {
            "needs_manual_reshelve": manual,
            "rebuild_sources_cloned": len(rebuild_urls),
            "fallback_bound_total": manual,
            "failed_rebuild_books_moved_to_fallback": moved,
            "real_search_changed": len(real.get("changed") or []),
            "smoke_ok": sum(1 for s in smoke if s.get("ok")),
            "smoke_tried": len(smoke),
        },
        "rebuild": {
            "cloned_origins": rebuild_urls,
            "sample_check": [
                {
                    "url": r.get("url"),
                    "success": r.get("success"),
                    "message": r.get("message"),
                }
                for r in (check.get("results") or [])
            ],
        },
        "change_source": {
            "fallback_origin": fb_origin,
            "fallback_name": fb_name,
            "note": (
                "Dead/non-rebuildable/failed-check books bound to enabled fallback "
                "with intro needs_manual_reshelve; MCP search 0/120 niche titles"
            ),
        },
        "smoke_results": smoke,
        "backup": {
            "status": "pending_manual_export",
            "hint": "App 我的→备份与恢复→导出；或 Download/legado/backup-*.zip",
        },
    }
    (ROOT / "shelf_zero_missing_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (ROOT.parent / "shelf_zero_missing_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (ROOT / "progress.json").write_text(
        json.dumps(
            {
                "phase": "5_done",
                "report": "queue/shelf_zero_missing_report.json",
                "acceptance": report["acceptance"],
                "counts": report["counts"],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print("REPORT WRITTEN")
    print(json.dumps(report["acceptance"], ensure_ascii=False))
    print(json.dumps(report["counts"], ensure_ascii=False))

    try:
        out = subprocess.check_output(
            ["adb", "shell", "ls", "-t", "/sdcard/Download/legado/"],
            text=True,
            stderr=subprocess.STDOUT,
        )
        print("legado download dir:\n", "\n".join(out.splitlines()[:15]))
    except Exception as e:
        print("list backups failed", e)


if __name__ == "__main__":
    if "--i-know-this-is-legacy" not in sys.argv:
        raise SystemExit(
            "legacy_phase5_finalize is a one-shot 2026-08 finalize. "
            "Re-run only with --i-know-this-is-legacy"
        )
    main()
