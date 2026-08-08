#!/usr/bin/env python3
"""CLI: shelf restore structural report (+ optional MCP smoke)."""
from __future__ import annotations

import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(
        str(Path(__file__).resolve().parent / "shelf_restore" / "status_report.py"),
        run_name="__main__",
    )
