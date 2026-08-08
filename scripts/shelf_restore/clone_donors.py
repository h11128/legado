#!/usr/bin/env python3
"""Clone missing shelf origins from donor sources in a catalog (MCP save_source).

Reusable CLI for shelf-origin rebuilds (donor exact / same-domain / same-name).

Examples:
  python scripts/shelf-restore-clone-donors.py \\
    --catalog temp/shelf_restore/backup_0726/bookSource.json \\
    --limit 30

  python scripts/shelf-restore-clone-donors.py \\
    --catalog path/to/all_sources.json --dry-run
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
from lib.legado_adb import (  # noqa: E402
    DEFAULT_PKG,
    pull_legado_db,
    require_device,
    shelf_restore_queue,
)
from lib.legado_mcp import LegadoMcp  # noqa: E402


def registrable(url: str) -> str:
    try:
        h = (urlparse(url).hostname or "").lower()
    except Exception:
        return ""
    for pfx in ("www.", "m.", "s.", "wap.", "api."):
        if h.startswith(pfx):
            h = h[len(pfx) :]
    parts = h.split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else h


def bare(s: str | None) -> str:
    return (s or "").strip().lower()


def load_catalog(paths: list[Path]) -> list[dict]:
    out: list[dict] = []
    for path in paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, dict) and "data" in data:
            data = data["data"]
        if not isinstance(data, list):
            raise SystemExit(f"catalog not a list: {path}")
        for s in data:
            if isinstance(s, dict) and s.get("bookSourceUrl"):
                out.append(s)
    return out


def missing_origins(db: Path, limit: int) -> list[dict]:
    import sqlite3

    con = sqlite3.connect(str(db))
    rows = con.execute(
        """
        select origin, originName, count(*) c
        from books
        where origin not like 'loc_%'
          and origin not in (select bookSourceUrl from book_sources)
          and origin like 'http%'
        group by origin
        order by c desc
        """
    ).fetchall()
    con.close()
    return [
        {"origin": o, "originName": n, "bookCount": c}
        for o, n, c in rows[:limit]
    ]


def pick_donor(
    origin: str,
    by_url: dict[str, dict],
    by_reg: dict[str, list[dict]],
    by_name: dict[str, list[dict]],
    origin_name: str | None,
) -> tuple[str, dict] | None:
    if origin in by_url:
        return "exact", by_url[origin]
    reg = registrable(origin)
    cands = [s for s in by_reg.get(reg, []) if s["bookSourceUrl"] != origin]
    if cands:
        cands = sorted(
            cands,
            key=lambda s: (0 if s.get("enabled") else 1, len(s.get("bookSourceUrl") or "")),
        )
        return "same_reg", cands[0]
    name = bare(origin_name)
    if name:
        host = (urlparse(origin).hostname or "").replace("www.", "").replace("m.", "")
        hits = by_name.get(name, [])
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
            return "same_name", related[0]
    return None


def rewrite_host(donor: dict, new_origin: str) -> dict:
    s = deepcopy(donor)
    old = urlparse(donor["bookSourceUrl"])
    new = urlparse(new_origin)
    old_host, new_host = old.netloc, new.netloc
    if not old_host or not new_host or old_host == new_host:
        return s
    for field in ["searchUrl", "exploreUrl", "loginUrl"]:
        if s.get(field) and old_host in str(s[field]):
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
    return s


def save_clone(
    mcp: LegadoMcp,
    origin: str,
    donor: dict,
    tag: str,
    origin_name: str | None,
    *,
    rewrite: bool,
) -> tuple[bool, str]:
    s = rewrite_host(donor, origin) if rewrite else deepcopy(donor)
    s["bookSourceUrl"] = origin
    if origin_name:
        s["bookSourceName"] = origin_name
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
        + f"\n# shelf-restore-clone {tag} from {donor.get('bookSourceUrl')}"
    )
    msg = mcp.save_source(s, preserve_enabled=False)
    ok = "失败" not in msg
    return ok, msg[:160]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--catalog",
        action="append",
        type=Path,
        required=True,
        help="bookSource.json or all_sources.json (repeatable)",
    )
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--db", type=Path, help="existing pulled DB; default pull from phone")
    ap.add_argument("--limit", type=int, default=80)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument(
        "--aes-donor-url",
        help="optional donor URL used with --host-rewrite for listed origins",
    )
    ap.add_argument(
        "--host-rewrite-origins",
        type=Path,
        help="JSON list of origins (or {origin:...} rows) to clone via --aes-donor-url host rewrite",
    )
    ap.add_argument(
        "--out",
        type=Path,
        default=None,
        help="report path (default temp/shelf_restore/queue/clone_donors_report.json)",
    )
    args = ap.parse_args()

    for p in args.catalog:
        if not p.is_file():
            raise SystemExit(f"catalog missing: {p}")

    queue = shelf_restore_queue()
    out = args.out or (queue / "clone_donors_report.json")

    if args.db:
        db = args.db
    else:
        require_device()
        db = pull_legado_db(queue / "live_clone.db", pkg=args.pkg)

    miss = missing_origins(db, args.limit)
    catalog = load_catalog(args.catalog)
    by_url = {s["bookSourceUrl"]: s for s in catalog}
    by_reg: dict[str, list[dict]] = defaultdict(list)
    by_name: dict[str, list[dict]] = defaultdict(list)
    for s in catalog:
        by_reg[registrable(s["bookSourceUrl"])].append(s)
        by_name[bare(s.get("bookSourceName"))].append(s)

    plan: list[tuple[str, str, dict, str | None, bool]] = []
    for m in miss:
        picked = pick_donor(
            m["origin"], by_url, by_reg, by_name, m.get("originName")
        )
        if picked:
            tag, donor = picked
            plan.append((m["origin"], tag, donor, m.get("originName"), False))

    if args.aes_donor_url and args.host_rewrite_origins:
        donor = by_url.get(args.aes_donor_url)
        if not donor:
            raise SystemExit(f"aes donor not in catalog: {args.aes_donor_url}")
        raw = json.loads(args.host_rewrite_origins.read_text(encoding="utf-8"))
        origins = []
        if isinstance(raw, list):
            for x in raw:
                if isinstance(x, str):
                    origins.append(x)
                elif isinstance(x, dict) and x.get("origin"):
                    origins.append(x["origin"])
        for o in origins:
            if o.startswith("http"):
                plan.append((o, "host_rewrite", donor, None, True))

    print(f"missing_origins={len(miss)} plan={len(plan)} dry_run={args.dry_run}")
    ok_rows: list[dict] = []
    fail_rows: list[dict] = []
    if args.dry_run:
        for origin, tag, donor, on, rewrite in plan:
            print(f"DRY {tag} {origin} <- {donor.get('bookSourceUrl')} rewrite={rewrite}")
            ok_rows.append(
                {
                    "origin": origin,
                    "donor": donor.get("bookSourceUrl"),
                    "tag": tag,
                    "dry_run": True,
                }
            )
    else:
        mcp = LegadoMcp(connect_retries=8)
        for origin, tag, donor, on, rewrite in plan:
            ok, msg = save_clone(mcp, origin, donor, tag, on, rewrite=rewrite)
            row = {
                "origin": origin,
                "donor": donor.get("bookSourceUrl"),
                "tag": tag,
                "ok": ok,
                "msg": msg,
            }
            if ok:
                ok_rows.append(row)
                print("OK", tag, origin)
            else:
                fail_rows.append(row)
                print("FAIL", tag, origin, msg)

    report = {"ok": ok_rows, "fail": fail_rows, "missing_scanned": len(miss)}
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote", out)
    return 0 if not fail_rows or args.dry_run else 1


if __name__ == "__main__":
    raise SystemExit(main())
