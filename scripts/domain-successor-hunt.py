#!/usr/bin/env python3
"""OSINT domain-successor hunt for dead/parked Legado book sources.

Mandatory companion to `source-cli hunt --probe` when L1/L2 says hunt, or when
hunt-empty but brand may have migrated (user: qinqinxsw → qinqinxiaoshuo).

Includes:
  - crt.sh certificate names (free DNS-ish history)
  - rate-limited Wayback CDX (never burst archive.org)
  - printed Google / book+site query templates (agent runs browser)
  - optional shelf title list for follow-up searches

Usage:
  python scripts/domain-successor-hunt.py --host www.qinqinxsw.cc
  python scripts/domain-successor-hunt.py --url https://www.qinqinxsw.cc/ \\
      --title '开局荒岛捡了雏田' --title '火影：种树就变强' \\
      --out temp/full_fix/cache/qinqinxsw/successor_hunt.json
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from scripts.lib.wayback_cdx import (  # noqa: E402
    available,
    cdx_search,
    unique_redirects,
)

UA = "legado-domain-successor-hunt/1.0"


def _host_of(url_or_host: str) -> str:
    s = url_or_host.strip()
    if "://" not in s:
        return s.split("/")[0].lower()
    return (urlparse(s).hostname or s).lower()


def _related_hosts(host: str) -> list[str]:
    h = host.lower().removeprefix("www.").removeprefix("m.")
    labels = h.split(".")
    apex = ".".join(labels[-2:]) if len(labels) >= 2 else h
    base = labels[0] if labels else h
    out = [
        apex,
        f"www.{apex}",
        f"m.{apex}",
        host,
    ]
    # common brand migrations: foo.com → fooxiaoshuo.org etc. left to Google
    # also bare typos of trailing letter
    for cand in {apex, f"www.{apex}", f"m.{apex}", f"mmm.{apex}", f"wap.{apex}"}:
        out.append(cand)
    # dedupe preserve order
    seen: set[str] = set()
    uniq: list[str] = []
    for x in out:
        if x not in seen:
            seen.add(x)
            uniq.append(x)
    _ = base
    return uniq


def crt_sh_names(domain: str, timeout_s: float = 40.0) -> list[str]:
    q = urllib.parse.quote(f"%.{domain.removeprefix('www.')}")
    url = f"https://crt.sh/?q={q}&output=json"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
    except Exception as e:
        return [f"ERROR:{type(e).__name__}:{e}"]
    names: set[str] = set()
    if isinstance(data, list):
        for row in data:
            if not isinstance(row, dict):
                continue
            raw = row.get("name_value") or ""
            for line in str(raw).splitlines():
                n = line.strip().lower().lstrip("*.")
                if n:
                    names.add(n)
    return sorted(names)


def google_queries(host: str, titles: list[str]) -> list[str]:
    apex = host.removeprefix("www.").removeprefix("m.")
    brand = apex.split(".")[0]
    qs = [
        f'{apex} 最新网址 OR 备用域名 OR 已更换',
        f'{brand} 小说 最新域名 OR 官网',
        f'site:{apex} OR "{apex}" redirect OR 跳转',
    ]
    for t in titles[:5]:
        qs.append(f'"{t}" "{brand}" OR "{apex}"')
        qs.append(f'"{t}" 亲亲小说 OR 小说网 最新')
    return qs


def hunt(host: str, titles: list[str], *, skip_wayback: bool = False) -> dict[str, Any]:
    apex = host.removeprefix("www.").removeprefix("m.")
    related = _related_hosts(host)
    report: dict[str, Any] = {
        "host": host,
        "apex": apex,
        "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "crt_sh": {},
        "wayback_cdx": {},
        "wayback_available": {},
        "redirect_candidates": [],
        "google_queries": google_queries(host, titles),
        "titles": titles,
        "notes": [
            "Run google_queries in browser (Google). Do not claim hunt-empty without this OSINT pass.",
            "Wayback calls are serialized via temp/full_fix/cache/wayback_rate_lock.json.",
            "Paid archives (SecurityTrails/DomainTools) only if API key present in env.",
        ],
    }

    # crt.sh on apex (+ org/com variants of brand left to agent)
    for d in {apex, ".".join(apex.split(".")[-2:])}:
        report["crt_sh"][d] = crt_sh_names(d)
        time.sleep(1.0)  # be polite to crt.sh

    redirects: list[str] = []
    if not skip_wayback:
        for h in related[:6]:
            try:
                rows = cdx_search(h, limit=30)
                report["wayback_cdx"][h] = {
                    "n": max(0, len(rows) - 1),
                    "sample": rows[1:6],
                    "redirects": unique_redirects(rows),
                }
                redirects.extend(unique_redirects(rows))
            except Exception as e:
                report["wayback_cdx"][h] = {"error": f"{type(e).__name__}:{e}"}
            # extra spacing between hosts beyond module min-interval
            time.sleep(2.0)
        for u in [f"https://{host}/", f"https://{apex}/"]:
            try:
                report["wayback_available"][u] = available(u)
            except Exception as e:
                report["wayback_available"][u] = {"error": f"{type(e).__name__}:{e}"}

    # dedupe redirects
    seen: set[str] = set()
    for r in redirects:
        if r not in seen:
            seen.add(r)
            report["redirect_candidates"].append(r)

    # SecurityTrails optional
    st_key = (os.environ.get("SECURITYTRAILS_API_KEY") or "").strip()
    if st_key:
        report["securitytrails"] = _securitytrails_subdomains(apex, st_key)
    else:
        report["securitytrails"] = {"skipped": "no SECURITYTRAILS_API_KEY"}

    return report


def _securitytrails_subdomains(apex: str, api_key: str) -> dict[str, Any]:
    url = f"https://api.securitytrails.com/v1/domain/{urllib.parse.quote(apex)}/subdomains"
    req = urllib.request.Request(
        url,
        headers={"APIKEY": api_key, "Accept": "application/json", "User-Agent": UA},
    )
    try:
        with urllib.request.urlopen(req, timeout=40) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
        return {"ok": True, "data": data}
    except Exception as e:
        return {"ok": False, "error": f"{type(e).__name__}:{e}"}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--host", help="hostname e.g. www.qinqinxsw.cc")
    ap.add_argument("--url", help="bookSourceUrl; host extracted")
    ap.add_argument("--title", action="append", default=[], help="shelf book title (repeatable)")
    ap.add_argument("--titles-file", type=Path, help="one title per line")
    ap.add_argument("--out", type=Path, help="write JSON report")
    ap.add_argument("--skip-wayback", action="store_true", help="crt.sh + queries only")
    args = ap.parse_args()
    if not args.host and not args.url:
        ap.error("need --host or --url")
    host = args.host or _host_of(args.url)
    titles = list(args.title)
    if args.titles_file and args.titles_file.is_file():
        titles.extend(
            ln.strip()
            for ln in args.titles_file.read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.strip().startswith("#")
        )
    report = hunt(host, titles, skip_wayback=args.skip_wayback)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
        print(f"wrote {args.out}")
    print(text)
    print("\n=== Google queries (run in browser) ===")
    for q in report["google_queries"]:
        print("-", q)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
