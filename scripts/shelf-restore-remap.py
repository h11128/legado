#!/usr/bin/env python3
"""CLI: remap missing-origin shelf books to same-title enabled copies."""
from __future__ import annotations

import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(
        str(Path(__file__).resolve().parent / "shelf_restore" / "remap_same_title.py"),
        run_name="__main__",
    )
