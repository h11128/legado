# Shelf restore workers (promoted from temp/shelf_restore/queue one-offs).
#
# Daily CLIs (prefer these):
#   python scripts/shelf-restore-pick-books.py
#   python scripts/shelf-restore-clone-donors.py --catalog <bookSource.json>
#   python scripts/shelf-restore-remap.py [--tag-manual] [--push]
#   python scripts/shelf-restore-change-source.py
#   python scripts/shelf-restore-readable.py
#   python scripts/shelf-restore-report.py [--smoke 4]
#
# MCP URL: LEGADO_MCP_URL or config/mcp_defaults.json (never hard-code DHCP).
# Runtime state / reports: temp/shelf_restore/queue/
#
# Shared helpers: scripts/lib/legado_mcp.py, scripts/lib/legado_adb.py
