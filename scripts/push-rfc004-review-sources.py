#!/usr/bin/env python3
"""Push RFC-004 real review JS sources to the phone via MCP."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
from legado_mcp import LegadoMcp  # noqa: E402

SOURCES = [
    "qidian-review-provider.js",
    "weread-review-provider.js",
]


def main() -> int:
    mcp = LegadoMcp()
    src_dir = ROOT / "docs" / "design" / "sources"
    failed = 0
    for name in SOURCES:
        path = src_dir / name
        text = path.read_text(encoding="utf-8")
        result = mcp.call("save_source", {"source": text, "format": "js"})
        from legado_mcp import tool_is_error, tool_text

        msg = tool_text(result)
        print(name, "=>", msg)
        if tool_is_error(result) or "失败" in msg or "Exception" in msg:
            failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
