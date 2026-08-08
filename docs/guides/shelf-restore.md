# Shelf restore (orphaned / unreadable bookshelf books)

Debug package: `com.legado.app.debug`  
Related: `docs/postmortem/2026-08-07-shelf-origin-source-deleted.md`  
Auto-换源 (open-book): `docs/guides/change-chapter-verify-test.md`

## Goal

Restore **readable** shelf books after source delete / dead origin — not just rewrite `origin`.

Order of preference:

1. Rebind / remap to an existing enabled source that can open the same title  
2. Restore a donor source **only** if remap cannot open the book  
3. Change-source search (MCP `debug_source`) with TOC+content proof  
4. Leave tagged for manual when no hit  

Never claim fixed without device verify (TOC + content). Never delete shelf-referenced sources without bookshelf-origin check (see postmortem).

## Scripts

| Script | Purpose |
|---|---|
| `scripts/shelf-restore-pick-books.py` | List `missing_origin` / `disabled_origin` / `empty_toc` candidates from phone DB |
| `scripts/shelf-restore-readable.py` | One-by-one remount until readable (MCP) |
| `scripts/shelf-restore-change-source.py` | Batch search remaps (MCP debug parser) |
| `scripts/lib/legado_adb.py` | Shared adb pull / open book |
| `scripts/lib/legado_mcp.py` | MCP URL from env / `config/mcp_defaults.json` / Cursor mcp.json |

Legacy one-shots (do **not** use daily; need `--i-know-this-is-legacy`):

- `scripts/shelf_restore/legacy_phase234.py`
- `scripts/shelf_restore/legacy_phase5_finalize.py`

Runtime JSON / pulled DBs stay under `temp/shelf_restore/` (gitignored working area).

## MCP URL

```bash
# preferred
export LEGADO_MCP_URL='http://<phone>:1236/mcp'
export LEGADO_MCP_TOKEN=1234
# or write config/mcp_defaults.json {"url":"…","token":"1234"}
# never commit a DHCP IP as the only SOT
```

## Fast path

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"
export LEGADO_MCP_URL=…   # required for MCP workers

# 1) Inventory
python scripts/shelf-restore-pick-books.py --kind all --limit 40

# 2) Prefer auto-换源 UI path for missing_source books (read page):
./scripts/auto-change-device-session.sh --no-install --kind missing_source

# 3) Batch remaps when many books share dead hosts
python scripts/shelf-restore-change-source.py

# 4) Strict one-by-one readable worker (slow; MCP single-flight)
python scripts/shelf-restore-readable.py
```

Stop a stuck readable worker: `powershell -File scripts/shelf_restore/stop_readable_worker.ps1` (inspect first).

## Honesty rules

- Remap `books.origin` **and** `bookUrl` / toc when changing source — origin-only rewrite is dishonest.  
- Twins (`https://host` vs `https://host/`): remap shelf to survivor, then delete unused dup.  
- Prefer disable over delete for shelf-referenced URLs.

## Agent checklist

1. `source-cli check channel` / MCP idle before parallel workers  
2. Pull DB integrity_check=ok before push  
3. Record outcomes under `temp/shelf_restore/queue/*.jsonl`  
4. Update this guide’s run record when process changes  
