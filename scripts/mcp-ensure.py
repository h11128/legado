#!/usr/bin/env python3
"""Wake Legado MCP on the debug phone and sync PC URL config.

Typical failure: app process was force-stopped (adb pull DB / APK install) while
`mcpService=true`. Port 1236 is then closed; Cursor shows ECONNREFUSED / tool
discovery error until MainActivity starts and McpService.restoreIfEnabled runs.

Usage:
  python scripts/mcp-ensure.py
  python scripts/mcp-ensure.py --no-cursor   # skip ~/.cursor/mcp.json bump
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from lib.legado_adb import (  # noqa: E402
    DEFAULT_PKG,
    adb,
    ensure_mcp_listening,
    phone_wlan_ip,
    require_device,
)
from lib.legado_mcp import resolve_mcp_url  # noqa: E402


def health_ok(url: str, token: str, timeout: float = 5.0) -> dict:
    base = url.rstrip("/")
    health = base[: -len("/mcp")] + "/mcp/health" if base.endswith("/mcp") else base + "/health"
    req = urllib.request.Request(health, headers={"X-Legado-Token": token}, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace") or "{}")


def write_defaults(url: str, token: str) -> Path:
    path = ROOT / "config" / "mcp_defaults.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"url": url, "token": token}, indent=2) + "\n", encoding="utf-8")
    return path


def sync_cursor_mcp(url: str, token: str, *, bump_client: bool = True) -> Path | None:
    path = Path.home() / ".cursor" / "mcp.json"
    if not path.is_file():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    server = data.setdefault("mcpServers", {}).setdefault("legado", {})
    server["url"] = url
    headers = server.setdefault("headers", {})
    headers["X-Legado-Token"] = token
    if bump_client:
        # Cursor treats failed streamableHttp as non-retryable; header change
        # forces a reconnect after the phone is back.
        headers["X-Legado-Client"] = f"cursor-reload-{datetime.now().strftime('%H%M%S')}"
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def main() -> int:
    ap = argparse.ArgumentParser(description="Ensure Legado phone MCP is up and PC URL is synced")
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--token", default=None)
    ap.add_argument("--port", type=int, default=1236)
    ap.add_argument("--no-cursor", action="store_true", help="Do not touch ~/.cursor/mcp.json")
    ap.add_argument("--timeout-s", type=float, default=20.0)
    args = ap.parse_args()

    require_device()
    # Resolve token from env / defaults / Cursor BEFORE building a new URL —
    # resolve_mcp_url(explicit_url) skips stored token and would always fall to 1234.
    token = args.token
    if not token:
        try:
            _, token = resolve_mcp_url(None)
        except SystemExit:
            token = os.environ.get("LEGADO_MCP_TOKEN", "1234")

    ip = phone_wlan_ip()
    if not ip:
        print("FAIL: no wlan0 IPv4 on device (need Wi-Fi)", file=sys.stderr)
        return 2

    url = f"http://{ip}:{args.port}/mcp"

    listening = ensure_mcp_listening(pkg=args.pkg, port=args.port, timeout_s=args.timeout_s)
    # Port probe can false-negative on odd ss/toybox; still try HTTP health.
    deadline = time.time() + args.timeout_s
    last_err: Exception | None = None
    body: dict = {}
    while time.time() < deadline:
        try:
            body = health_ok(url, token)
            if body.get("ok") and body.get("serviceRun"):
                break
            last_err = RuntimeError(f"health not ready: {body}")
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            last_err = e
            if not listening:
                ensure_mcp_listening(pkg=args.pkg, port=args.port, timeout_s=3.0)
        time.sleep(0.6)
    else:
        print(f"FAIL: /mcp/health not ok ({last_err})", file=sys.stderr)
        if not listening:
            print(
                f"hint: :{args.port} closed after launch (pkg={args.pkg}); "
                "open app → 其他设置 → MCP 服务，确认令牌与开关",
                file=sys.stderr,
            )
        return 4

    defaults = write_defaults(url, token)
    cursor = None if args.no_cursor else sync_cursor_mcp(url, token)
    print(f"ok url={url} health={json.dumps(body, ensure_ascii=False)}")
    print(f"wrote {defaults}")
    if cursor:
        print(f"synced {cursor} (Cursor should reconnect; if still red, Reload MCP once)")
    # Show process/service briefly for evidence
    print(adb("shell", f"pidof {args.pkg}").strip() or "(no pid?)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
