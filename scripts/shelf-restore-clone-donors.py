#!/usr/bin/env python3
"""CLI: clone missing shelf origins from donor catalogs. See docs/guides/shelf-restore.md."""
from __future__ import annotations

import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(
        str(Path(__file__).resolve().parent / "shelf_restore" / "clone_donors.py"),
        run_name="__main__",
    )
