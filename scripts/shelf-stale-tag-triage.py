#!/usr/bin/env python3
"""CLI: stale-tag triage with mandatory hunt--probe (see shelf_restore/stale_tag_triage.py)."""
from __future__ import annotations

import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(
        str(Path(__file__).resolve().parent / "shelf_restore" / "stale_tag_triage.py"),
        run_name="__main__",
    )
