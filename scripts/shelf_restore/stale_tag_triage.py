#!/usr/bin/env python3
"""Triage shelf stale-tag sources: gate + mandatory hunt--probe before defer.

trap gate_hunt_deferred_unprobed: never park ``l1_unreachable`` / ``action=hunt``
as "maybe later / skip-ish" without ``source-cli hunt --probe``. If hunt returns
migrate/original_alive candidates, promote them to the priority dig list.

Examples:
  python scripts/shelf-stale-tag-triage.py \\
    --candidates temp/shelf_restore/queue/stale_tag_remaining_candidates.json \\
    --limit 30
"""
from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
QUEUE = REPO / "temp" / "shelf_restore" / "queue"


def _cli_json(args: list[str], *, timeout: int) -> dict[str, Any]:
    """Parse source-cli JSON (compact one-liner or pretty multi-line)."""
    out = subprocess.check_output(
        ["source-cli", *args],
        text=True,
        errors="ignore",
        timeout=timeout,
        cwd=str(REPO),
    )
    text = out.strip()
    if not text:
        raise RuntimeError(f"empty output from source-cli {' '.join(args)}")
    start = text.find("{")
    if start < 0:
        raise RuntimeError(f"no JSON from source-cli {' '.join(args)}: {text[:300]}")
    decoder = json.JSONDecoder()
    obj, _ = decoder.raw_decode(text[start:])
    if not isinstance(obj, dict):
        raise RuntimeError(f"expected object from source-cli {' '.join(args)}")
    return obj


def _as_url(maybe: str | None) -> str | None:
    if not maybe:
        return None
    s = maybe.strip()
    if not s:
        return None
    if "://" not in s:
        return f"https://{s.rstrip('/')}/"
    return s


def load_candidates(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, list):
        return data
    cands = data.get("candidates") or data.get("urls") or []
    out: list[dict[str, Any]] = []
    for c in cands:
        if isinstance(c, str):
            out.append({"url": c, "shelf_n": 0, "name": ""})
        else:
            out.append(
                {
                    "url": c.get("url") or c.get("bookSourceUrl") or "",
                    "shelf_n": int(c.get("shelf_n") or c.get("books") or 0),
                    "name": c.get("name") or c.get("bookSourceName") or "",
                    "comment": (c.get("comment") or c.get("bookSourceComment") or "")[:120],
                }
            )
    return [c for c in out if c.get("url")]


def triage_one(url: str, *, hunt_timeout: int) -> dict[str, Any]:
    gate = _cli_json(["gate", "--url", url], timeout=30)
    row: dict[str, Any] = {
        "url": url,
        "gate_action": gate.get("action"),
        "gate_reason": gate.get("reason"),
        "verify": bool(gate.get("verify")),
        "l1_ok": bool((gate.get("l1") or {}).get("ok")),
        "l2_status": (gate.get("l2") or {}).get("status"),
        "migrate_to": None,
        "hunt": None,
        "priority": False,
        "bucket": "other",
    }
    action = (gate.get("action") or "").lower()
    if action in ("verify", "migrate") or gate.get("verify"):
        row["bucket"] = "priority_verify_or_migrate"
        row["priority"] = True
        if action == "migrate":
            row["migrate_to"] = _as_url(
                gate.get("migrate_to") or gate.get("best_candidate")
            )
        return row

    if action == "hunt" or (gate.get("reason") or "").startswith("l1_unreachable") or (
        gate.get("reason") or ""
    ).startswith("l2_http_dead"):
        # MUST probe — do not defer unprobed hunt into a skip-ish bucket.
        hunt = _cli_json(["hunt", "--url", url, "--probe"], timeout=hunt_timeout)
        best = _as_url(hunt.get("best_candidate"))
        h_action = (hunt.get("action") or "").lower()
        row["hunt"] = {
            "action": hunt.get("action"),
            "best_candidate": best,
            "candidates": hunt.get("candidates"),
            "status": hunt.get("status"),
            "note": hunt.get("note"),
        }
        # HuntAction: migrate|weak_candidate|original_alive|none_alive|no_mirror|…
        if h_action == "migrate" and best:
            row["bucket"] = "priority_verify_or_migrate"
            row["priority"] = True
            row["migrate_to"] = best
            row["gate_action"] = "migrate"
            row["gate_reason"] = f"hunt_promoted:{hunt.get('note') or h_action}"
        elif h_action == "original_alive":
            row["bucket"] = "priority_verify_or_migrate"
            row["priority"] = True
            row["migrate_to"] = best or url
            row["gate_action"] = "verify"
            row["gate_reason"] = "hunt_original_alive"
        elif h_action in ("none_alive", "no_mirror", "empty"):
            row["bucket"] = "hunt_empty_after_probe"
            row["priority"] = False
        else:
            # weak_candidate / candidates_only / …
            row["bucket"] = "maybe_hunt_probed"
            row["priority"] = False
            row["migrate_to"] = best
        return row

    reason = (gate.get("reason") or "").lower()
    if any(x in reason for x in ("cf", "park", "wall", "deadish", "challenge")):
        row["bucket"] = "hard_skip_or_cf"
    else:
        row["bucket"] = "other"
    return row


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--candidates",
        type=Path,
        default=QUEUE / "stale_tag_remaining_candidates.json",
    )
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--hunt-timeout", type=int, default=90)
    ap.add_argument(
        "--out",
        type=Path,
        default=QUEUE / "stale_tag_remaining_gated_top30.json",
    )
    ap.add_argument(
        "--urls-out",
        type=Path,
        default=QUEUE / "stale_tag_fixable_priority.urls.txt",
    )
    args = ap.parse_args()

    cands = load_candidates(args.candidates)
    cands.sort(key=lambda c: (-int(c.get("shelf_n") or 0), c.get("url") or ""))
    cands = cands[: max(0, args.limit)]

    rows: list[dict[str, Any]] = []
    for c in cands:
        url = c["url"]
        try:
            row = triage_one(url, hunt_timeout=args.hunt_timeout)
        except Exception as e:  # noqa: BLE001 — per-URL continue
            row = {
                "url": url,
                "bucket": "error",
                "priority": False,
                "error": str(e)[:240],
            }
        row["shelf_n"] = c.get("shelf_n")
        row["name"] = c.get("name")
        row["comment"] = c.get("comment")
        rows.append(row)
        print(
            f"{row.get('bucket'):28} shelfn={row.get('shelf_n')} "
            f"prio={row.get('priority')} {url} -> {row.get('migrate_to') or row.get('gate_action')}",
            flush=True,
        )

    priority = [r for r in rows if r.get("priority")]
    maybe = [r for r in rows if r.get("bucket") == "maybe_hunt_probed"]
    empty = [r for r in rows if r.get("bucket") == "hunt_empty_after_probe"]
    hard = [r for r in rows if r.get("bucket") == "hard_skip_or_cf"]

    payload = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "scoped": f"top{len(rows)}_stale_tag_triage_with_hunt_probe",
        "trap": "gate_hunt_deferred_unprobed",
        "note": "Hunt URLs are probed before any defer; migrate hits land in priority.",
        "priority_verify_or_migrate": priority,
        "maybe_hunt_probed": maybe,
        "hunt_empty_after_probe": empty,
        "hard_skip_or_cf": hard,
        "all_gated": rows,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    urls = [r["migrate_to"] or r["url"] for r in priority if r.get("url")]
    args.urls_out.write_text("\n".join(urls) + ("\n" if urls else ""), encoding="utf-8")
    print(
        json.dumps(
            {
                "out": str(args.out),
                "urls_out": str(args.urls_out),
                "priority": len(priority),
                "maybe_hunt_probed": len(maybe),
                "hunt_empty": len(empty),
                "hard_skip": len(hard),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
