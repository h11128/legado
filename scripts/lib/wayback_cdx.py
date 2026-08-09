#!/usr/bin/env python3
"""Rate-limited Wayback CDX / availability client.

Archive.org returns 429 when bursted. All callers MUST go through this module
(or the same lock/min-interval policy) — never raw parallel curl to web.archive.org.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

DEFAULT_UA = (
    "legado-domain-successor-hunt/1.0 "
    "(+https://github.com/gedoor/legado; polite Wayback client)"
)
# Empirically safe floor for shared agent sessions on one IP.
DEFAULT_MIN_INTERVAL_S = float(os.environ.get("WAYBACK_MIN_INTERVAL_S", "12"))
DEFAULT_MAX_RETRIES = int(os.environ.get("WAYBACK_MAX_RETRIES", "6"))
LOCK_NAME = "wayback_rate_lock.json"


def _repo_temp() -> Path:
    # scripts/lib → scripts → repo
    root = Path(__file__).resolve().parents[2]
    p = root / "temp" / "full_fix" / "cache"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _lock_path() -> Path:
    return _repo_temp() / LOCK_NAME


def _load_lock() -> dict[str, Any]:
    path = _lock_path()
    if not path.is_file():
        return {"last_request_at": 0.0, "last_status": None, "backoff_until": 0.0}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {"last_request_at": 0.0, "last_status": None, "backoff_until": 0.0}


def _save_lock(data: dict[str, Any]) -> None:
    path = _lock_path()
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def _wait_slot(min_interval_s: float) -> None:
    """Serialize archive.org access across concurrent agent shells."""
    while True:
        now = time.time()
        data = _load_lock()
        backoff_until = float(data.get("backoff_until") or 0.0)
        last = float(data.get("last_request_at") or 0.0)
        wake = max(backoff_until, last + min_interval_s)
        if now >= wake:
            data["last_request_at"] = now
            _save_lock(data)
            return
        time.sleep(min(wake - now, 5.0))


def wayback_get(
    url: str,
    *,
    min_interval_s: float = DEFAULT_MIN_INTERVAL_S,
    max_retries: int = DEFAULT_MAX_RETRIES,
    timeout_s: float = 45.0,
) -> tuple[int, bytes]:
    """GET one Wayback/archive.org URL with min-interval + 429 backoff."""
    headers = {"User-Agent": DEFAULT_UA, "Accept": "application/json,text/plain,*/*"}
    last_err: Exception | None = None
    for attempt in range(max_retries):
        _wait_slot(min_interval_s)
        req = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                body = resp.read()
                code = getattr(resp, "status", 200) or 200
                data = _load_lock()
                data["last_status"] = code
                data["last_request_at"] = time.time()
                _save_lock(data)
                return int(code), body
        except urllib.error.HTTPError as e:
            last_err = e
            code = int(e.code)
            data = _load_lock()
            data["last_status"] = code
            data["last_request_at"] = time.time()
            if code == 429:
                retry_after = e.headers.get("Retry-After") if e.headers else None
                try:
                    wait_s = float(retry_after) if retry_after else (30.0 * (2**attempt))
                except ValueError:
                    wait_s = 30.0 * (2**attempt)
                wait_s = min(max(wait_s, 30.0), 300.0)
                data["backoff_until"] = time.time() + wait_s
                _save_lock(data)
                time.sleep(wait_s)
                continue
            data["backoff_until"] = 0.0
            _save_lock(data)
            return code, e.read() if e.fp else b""
        except Exception as e:
            last_err = e
            # network blip: short backoff then retry
            data = _load_lock()
            data["last_status"] = "error"
            data["backoff_until"] = time.time() + min(15.0 * (attempt + 1), 90.0)
            _save_lock(data)
            time.sleep(min(15.0 * (attempt + 1), 90.0))
    raise RuntimeError(f"wayback_get failed after retries: {url}: {last_err}")


def cdx_search(
    host_or_url: str,
    *,
    limit: int = 40,
    fl: str = "timestamp,original,statuscode,redirect,mimetype",
) -> list[list[str]]:
    """Query CDX for host (adds /* if bare host). Returns rows including header."""
    target = host_or_url.strip()
    if "://" not in target:
        target = f"{target}/*" if not target.endswith("*") else target
        if not target.startswith("http"):
            # host only → both schemes via host/*
            target = target if "/" in target else f"{target}/*"
    q = urllib.parse.urlencode(
        {
            "url": target,
            "output": "json",
            "fl": fl,
            "collapse": "digest",
            "limit": str(limit),
            "filter": "statuscode:30.|200|404",
        }
    )
    url = f"https://web.archive.org/cdx/search/cdx?{q}"
    code, body = wayback_get(url)
    if code != 200:
        raise RuntimeError(f"CDX HTTP {code} for {target}: {body[:200]!r}")
    if not body.strip():
        return []
    rows = json.loads(body.decode("utf-8", "replace"))
    if not isinstance(rows, list):
        raise RuntimeError(f"unexpected CDX payload: {type(rows)}")
    return rows


def available(url: str) -> dict[str, Any]:
    """Wayback availability API (also rate-limited)."""
    q = urllib.parse.urlencode({"url": url})
    api = f"https://archive.org/wayback/available?{q}"
    code, body = wayback_get(api)
    if code != 200:
        raise RuntimeError(f"available HTTP {code}: {body[:200]!r}")
    return json.loads(body.decode("utf-8", "replace"))


def unique_redirects(rows: list[list[str]]) -> list[str]:
    if not rows or len(rows) < 2:
        return []
    # header may be first row
    start = 1 if rows[0] and rows[0][0] == "timestamp" else 0
    out: list[str] = []
    seen: set[str] = set()
    for r in rows[start:]:
        if len(r) < 4:
            continue
        red = (r[3] or "").strip()
        if red and red not in seen:
            seen.add(red)
            out.append(red)
    return out
