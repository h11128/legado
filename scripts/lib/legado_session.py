#!/usr/bin/env python3
"""Session-scoped MCP save ledger for fast, safe DB pushes.

When ``LegadoMcp.save_source`` runs, the URL is recorded here. ``push_legado_db``
in ``merge_live_sources="auto"`` mode then:
- skips full device re-pull if the working DB already has every pending URL
- otherwise upserts via MCP ``get_source`` (no whole-DB pull)
- only falls back to full baseline merge if MCP is down
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path


def _session_root() -> Path:
    env = (os.environ.get("LEGADO_SKILL_ROOT") or "").strip()
    if env:
        p = Path(env) / "temp" / "full_fix"
        p.mkdir(parents=True, exist_ok=True)
        return p
    sibling = Path(__file__).resolve().parents[2].parent / "legadoSkill" / "temp" / "full_fix"
    if sibling.parent.parent.exists():
        sibling.mkdir(parents=True, exist_ok=True)
        return sibling
    local = Path(__file__).resolve().parents[2] / "temp" / "full_fix"
    local.mkdir(parents=True, exist_ok=True)
    return local


def mcp_saves_path() -> Path:
    return _session_root() / "mcp_saved_urls.json"


def note_mcp_save(url: str) -> None:
    url = (url or "").strip()
    if not url:
        return
    path = mcp_saves_path()
    data = {"schema_version": 1, "urls": [], "ts": {}}
    if path.is_file():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            pass
    urls = list(data.get("urls") or [])
    if url not in urls:
        urls.append(url)
    ts = dict(data.get("ts") or {})
    ts[url] = int(time.time() * 1000)
    path.write_text(
        json.dumps(
            {"schema_version": 1, "urls": urls, "ts": ts},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def pending_mcp_saves() -> list[str]:
    path = mcp_saves_path()
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return [str(u) for u in (data.get("urls") or []) if u]
    except (OSError, json.JSONDecodeError):
        return []


def clear_mcp_saves(urls: list[str] | None = None) -> None:
    path = mcp_saves_path()
    if not path.is_file():
        return
    if urls is None:
        try:
            path.unlink()
        except OSError:
            pass
        return
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return
    drop = set(urls)
    left = [u for u in (data.get("urls") or []) if u not in drop]
    ts = {k: v for k, v in (data.get("ts") or {}).items() if k not in drop}
    if not left:
        try:
            path.unlink()
        except OSError:
            pass
        return
    path.write_text(
        json.dumps(
            {"schema_version": 1, "urls": left, "ts": ts},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
