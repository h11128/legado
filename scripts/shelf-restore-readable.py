#!/usr/bin/env python3
"""CLI wrapper: one-by-one readable shelf restore (MCP). See docs/guides/shelf-restore.md."""
from __future__ import annotations

import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(
        str(Path(__file__).resolve().parent / "shelf_restore" / "readable_until_done.py"),
        run_name="__main__",
    )
