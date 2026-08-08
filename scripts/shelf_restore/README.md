# Shelf restore workers (promoted from temp/shelf_restore/queue one-offs).
#
# Daily:
#   python scripts/shelf-restore-pick-books.py
#   python scripts/shelf-restore-readable.py
#   python scripts/shelf-restore-change-source.py
#
# Legacy (2026-08 one-shot phases — require --i-know-this-is-legacy):
#   python scripts/shelf_restore/legacy_phase234.py --i-know-this-is-legacy
#   python scripts/shelf_restore/legacy_phase5_finalize.py --i-know-this-is-legacy
#
# MCP URL: LEGADO_MCP_URL or config/mcp_defaults.json (never hard-code DHCP).
# Runtime state / reports: temp/shelf_restore/queue/
