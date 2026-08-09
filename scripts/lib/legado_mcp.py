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
import subprocess
import time
import urllib.request
from pathlib import Path
from typing import Any, Callable


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _skill_root() -> Path | None:
    """legadoSkill root (source-cli closeout / deep_active live here)."""
    env = (os.environ.get("LEGADO_SKILL_ROOT") or "").strip()
    if env:
        p = Path(env)
        if (p / "config" / "mcp_defaults.json").is_file():
            return p
    sibling = repo_root().parent / "legadoSkill"
    if (sibling / "config" / "mcp_defaults.json").is_file():
        return sibling
    fixed = Path("E:/Projects/legadoSkill")
    if (fixed / "config" / "mcp_defaults.json").is_file():
        return fixed
    return None


def claim_deep_active(url: str, note: str = "legado_mcp") -> tuple[bool, str]:
    """Arm deep_active so stop/progress cannot skip ledger+retro (discipline §22)."""
    url = (url or "").strip()
    if not url.startswith(("http://", "https://")):
        return False, "claim skipped: not an http(s) bookSourceUrl"
    skill = _skill_root()
    cwd = str(skill) if skill else str(repo_root())
    env = {**os.environ}
    if skill:
        env["LEGADO_SKILL_ROOT"] = str(skill)
    try:
        r = subprocess.run(
            [
                "source-cli",
                "closeout",
                "claim",
                "--url",
                url,
                "--note",
                note[:80],
            ],
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            env=env,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return False, str(e)
    if r.returncode == 0:
        return True, (r.stdout or "").strip()[:200]
    return False, ((r.stderr or r.stdout or "")[:300])


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


def tool_text(result: Any) -> str:
    """Extract first text payload from an MCP tools/call result."""
    if result is None:
        return ""
    if isinstance(result, str):
        return result
    content = result.get("content") if isinstance(result, dict) else None
    if isinstance(content, list) and content:
        first = content[0]
        if isinstance(first, dict):
            return str(first.get("text") or "")
        return str(first)
    return str(result)


def tool_is_error(result: Any) -> bool:
    if not isinstance(result, dict):
        return False
    if result.get("isError"):
        return True
    return "失败" in tool_text(result)


class LegadoMcp:
    def __init__(
        self,
        url: str | None = None,
        token: str | None = None,
        timeout: int = 90,
        *,
        connect_retries: int = 1,
        on_retry: Callable[[Exception, int], None] | None = None,
    ):
        resolved_url, resolved_token = resolve_mcp_url(url)
        self.url = resolved_url
        self.token = token or resolved_token
        self.timeout = timeout
        self.sess: str | None = None
        self._id = 0
        self._connect_retries = max(1, connect_retries)
        self._on_retry = on_retry
        self._connect()

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
            if body.lstrip().startswith("event:") or "\ndata:" in body:
                for line in reversed(body.splitlines()):
                    if line.startswith("data:"):
                        return json.loads(line[5:].strip() or "{}")
            return json.loads(body or "{}")

    def _connect(self) -> None:
        last: Exception | None = None
        for attempt in range(self._connect_retries):
            try:
                self.sess = None
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
                return
            except Exception as e:
                last = e
                if self._on_retry:
                    self._on_retry(e, attempt)
                time.sleep(min(2 + attempt, 8))
        raise RuntimeError(last)

    def reconnect(self) -> None:
        self._connect()

    def call(
        self,
        name: str,
        arguments: dict[str, Any] | None = None,
        timeout: int | None = None,
        *,
        retry_once: bool = True,
    ) -> Any:
        self._id += 1
        payload = {
            "jsonrpc": "2.0",
            "id": self._id,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments or {}},
        }
        try:
            resp = self._post(payload, timeout=timeout)
        except Exception:
            if not retry_once:
                raise
            self.reconnect()
            resp = self._post(payload, timeout=timeout)
        if "error" in resp:
            raise RuntimeError(resp["error"])
        return resp.get("result")

    def text(
        self,
        name: str,
        arguments: dict[str, Any] | None = None,
        timeout: int | None = None,
    ) -> str:
        return tool_text(self.call(name, arguments, timeout=timeout))

    def get_source(self, url: str) -> dict[str, Any]:
        return json.loads(self.text("get_source", {"url": url}))

    def save_source(
        self,
        source: dict[str, Any] | str,
        *,
        preserve_enabled: bool | None = None,
        preserve_group: bool | None = None,
        claim: bool = True,
    ) -> str:
        args: dict[str, Any] = {
            "source": source if isinstance(source, str) else json.dumps(source, ensure_ascii=False),
            "format": "json",
        }
        if preserve_enabled is not None:
            args["preserveEnabled"] = preserve_enabled
        if preserve_group is not None:
            args["preserveGroup"] = preserve_group
        out = self.text("save_source", args)
        if claim:
            book_url = ""
            if isinstance(source, dict):
                book_url = str(source.get("bookSourceUrl") or "")
            elif isinstance(source, str):
                try:
                    book_url = str(json.loads(source).get("bookSourceUrl") or "")
                except json.JSONDecodeError:
                    book_url = ""
            if book_url:
                ok, msg = claim_deep_active(book_url, "legado_mcp save_source")
                if not ok:
                    out = f"{out}\n[deep_active claim failed: {msg}]"
        return out

    def debug_source(self, url: str, key: str, timeout_sec: int = 55) -> str:
        claim_deep_active(url, "legado_mcp debug_source")
        return self.text(
            "debug_source",
            {"url": url, "key": key, "timeoutSec": timeout_sec},
            timeout=timeout_sec + 30,
        )

    def start_check_sources(
        self,
        urls: list[str],
        *,
        check_search: bool = True,
        check_discovery: bool = False,
        timeout_ms: int = 180000,
        thread_count: int = 1,
        claim: bool = True,
    ) -> Any:
        if claim and urls:
            claim_deep_active(urls[0], "legado_mcp start_check_sources")
        return self.call(
            "start_check_sources",
            {
                "urls": urls,
                "checkSearch": check_search,
                "checkDiscovery": check_discovery,
                "timeoutMs": timeout_ms,
                "threadCount": thread_count,
            },
        )

    def get_check_progress(self) -> dict[str, Any]:
        raw = self.text("get_check_progress", {})
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as e:
            raise RuntimeError(f"get_check_progress not JSON: {raw[:200]}") from e
        if not isinstance(data, dict):
            raise RuntimeError(f"get_check_progress unexpected: {type(data)}")
        return data

    def wait_check_done(
        self,
        *,
        max_wait_s: float = 90.0,
        poll_s: float = 1.0,
        min_finished: int = 1,
    ) -> dict[str, Any]:
        """Poll until check job is not running (MCP field ``running``).

        Completes when ``running is False`` and ``finished >= min_finished``
        (or ``finishedAt`` is set). Never treat a missing key as "done".
        """
        deadline = time.time() + max_wait_s
        last: dict[str, Any] = {}
        while time.time() < deadline:
            last = self.get_check_progress()
            running = last.get("running")
            finished = int(last.get("finished") or 0)
            if running is False and (
                finished >= min_finished or last.get("finishedAt") is not None
            ):
                return last
            # Some builds omit ``running`` once idle; finishedAt is enough.
            if (
                running is None
                and last.get("finishedAt") is not None
                and finished >= min_finished
            ):
                return last
            time.sleep(poll_s)
        raise TimeoutError(
            f"check not done in {max_wait_s}s: "
            f"finished={last.get('finished')} failed={last.get('failed')} "
            f"running={last.get('running')} finishedAt={last.get('finishedAt')}"
        )

    def reset_channel(self) -> str:
        return tool_text(self.call("reset_mcp_channel", {}))
