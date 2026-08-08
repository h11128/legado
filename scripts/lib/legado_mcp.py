#!/usr/bin/env python3
"""Minimal MCP JSON-RPC client for Legado device server.

URL resolution order:
1. --url / LEGADO_MCP_URL
2. config/mcp_defaults.json (repo)
3. ~/.cursor/mcp.json → mcpServers.legado.url
"""
from __future__ import annotations

import json
import os
import urllib.request
from pathlib import Path
from typing import Any


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def resolve_mcp_url(explicit: str | None = None) -> tuple[str, str]:
    token = os.environ.get("LEGADO_MCP_TOKEN", "1234")
    if explicit:
        return explicit.rstrip("/"), token
    env = os.environ.get("LEGADO_MCP_URL", "").strip()
    if env:
        return env.rstrip("/"), token
    defaults = repo_root() / "config" / "mcp_defaults.json"
    if defaults.is_file():
        data = json.loads(defaults.read_text(encoding="utf-8"))
        url = (data.get("url") or data.get("mcp_url") or "").strip()
        tok = (data.get("token") or data.get("mcp_token") or token).strip()
        if url:
            return url.rstrip("/"), tok
    cursor = Path.home() / ".cursor" / "mcp.json"
    if cursor.is_file():
        data = json.loads(cursor.read_text(encoding="utf-8"))
        server = (data.get("mcpServers") or {}).get("legado") or {}
        url = (server.get("url") or "").strip()
        headers = server.get("headers") or {}
        tok = headers.get("X-Legado-Token") or headers.get("Authorization") or token
        if url:
            return url.rstrip("/"), str(tok)
    raise SystemExit(
        "MCP URL missing. Set LEGADO_MCP_URL or write config/mcp_defaults.json "
        "(see api.md). Do not hard-code DHCP IPs."
    )


class LegadoMcp:
    def __init__(self, url: str | None = None, token: str | None = None, timeout: int = 90):
        resolved_url, resolved_token = resolve_mcp_url(url)
        self.url = resolved_url
        self.token = token or resolved_token
        self.timeout = timeout
        self.sess: str | None = None
        self._id = 0
        self._initialize()

    def _post(self, payload: dict[str, Any], timeout: int | None = None) -> dict[str, Any]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "X-Legado-Token": self.token,
        }
        if self.sess:
            headers["Mcp-Session-Id"] = self.sess
        req = urllib.request.Request(
            self.url,
            data=json.dumps(payload).encode(),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
            if not self.sess:
                self.sess = r.headers.get("Mcp-Session-Id")
            body = r.read().decode()
            # SSE: take last data: line if present
            if body.lstrip().startswith("event:") or "\ndata:" in body:
                for line in reversed(body.splitlines()):
                    if line.startswith("data:"):
                        return json.loads(line[5:].strip() or "{}")
            return json.loads(body or "{}")

    def _initialize(self) -> None:
        self._post(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "legado-scripts", "version": "1"},
                },
            }
        )
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def call(self, name: str, arguments: dict[str, Any] | None = None, timeout: int | None = None) -> Any:
        self._id += 1
        resp = self._post(
            {
                "jsonrpc": "2.0",
                "id": self._id,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments or {}},
            },
            timeout=timeout,
        )
        if "error" in resp:
            raise RuntimeError(resp["error"])
        return resp.get("result")
