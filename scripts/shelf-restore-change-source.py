#!/usr/bin/env python3
"""CLI wrapper: batch MCP change-source remaps. See docs/guides/shelf-restore.md."""
from __future__ import annotations

import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(
        str(Path(__file__).resolve().parent / "shelf_restore" / "change_source_batch.py"),
        run_name="__main__",
    )
