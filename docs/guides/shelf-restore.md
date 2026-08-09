# Shelf restore (orphaned / unreadable bookshelf books)

Debug package: `com.legado.app.debug`  
Related: `docs/postmortem/2026-08-07-shelf-origin-source-deleted.md`  
Auto-换源 (open-book): `docs/guides/change-chapter-verify-test.md`

## Goal

Restore **readable** shelf books after source delete / dead origin — not just rewrite `origin`.

Order of preference:

1. Clone missing origins from a donor catalog (same registrable domain / exact URL)  
2. Dedupe when an enabled same-title copy already exists (drop orphan row)  
3. Change-source search (MCP `debug_source`) with TOC+content proof  
4. One-by-one readable worker for leftovers  
5. Leave tagged `[needs_manual_reshelve]` when no hit  

Never claim fixed without device verify (TOC + content). Never delete shelf-referenced sources without bookshelf-origin check (see postmortem).

## Scripts

| Script | Purpose |
|---|---|
| `scripts/shelf-restore-pick-books.py` | List `missing_origin` / `disabled_origin` / `empty_toc` candidates from phone DB |
| `scripts/shelf-restore-clone-donors.py` | Clone missing origins from donor `bookSource.json` / all_sources catalogs |
| `scripts/shelf-restore-remap.py` | Same-title dedupe (delete orphan if enabled copy exists); optional `--tag-manual` (no origin rewrite) |
| `scripts/shelf-restore-change-source.py` | Batch MCP search remaps for manual-tagged books |
| `scripts/shelf-restore-readable.py` | One-by-one remount until readable (MCP); always merges live missing/manual from DB |
| `scripts/shelf-restore-report.py` | Structural missing/disabled/manual counts; optional `--smoke N` |
| `scripts/lib/legado_adb.py` | Shared adb pull / push / open book / ensure MCP port |
| `scripts/lib/legado_mcp.py` | MCP URL from env / `config/mcp_defaults.json` / Cursor mcp.json |
| `scripts/mcp-ensure.py` | Wake MCP after force-stop; write defaults + bump Cursor mcp.json |

Runtime JSON / pulled DBs stay under `temp/shelf_restore/` (gitignored working area).

## MCP URL

```bash
# preferred: wake phone MCP + sync URL (after force-stop / APK install / Cursor ECONNREFUSED)
python scripts/mcp-ensure.py

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

# 2) Clone missing origins from a backup / export catalog
python scripts/shelf-restore-clone-donors.py \
  --catalog temp/shelf_restore/backup_0726/bookSource.json \
  --limit 80

# 3) Honest same-title dedupe (drop orphan when enabled copy exists); optional tag only
python scripts/shelf-restore-remap.py --push
python scripts/shelf-restore-remap.py --tag-manual --push

# 4) Prefer auto-换源 UI path for missing_source books (read page):
./scripts/auto-change-device-session.sh --no-install --kind missing_source

# 5) Batch search remaps when many books are tagged needs_manual_reshelve
python scripts/shelf-restore-change-source.py --limit 50

# 6) Strict one-by-one readable worker (slow; MCP single-flight)
python scripts/shelf-restore-readable.py

# 7) Status / optional smoke
python scripts/shelf-restore-report.py --smoke 4
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

## Stale「失效」tag sources (2026-08-09)

Bookshelf books whose origin is still **enabled**, but source comment/group still says `搜索失效` / `Error` — spot-check + per-source repair queue:

- Guide: [`shelf-stale-tag-source-queue.md`](shelf-stale-tag-source-queue.md)（top-10 已关门）  
- Remaining candidates: [`shelf-stale-tag-remaining.md`](shelf-stale-tag-remaining.md)  
- Runtime: `temp/shelf_restore/queue/stale_tag_repair_queue.jsonl`  
- Remaining JSON / priority URLs: `temp/shelf_restore/queue/stale_tag_remaining_candidates.json`, `stale_tag_fixable_priority.urls.txt`  
- **Triage (mandatory hunt probe):** `python scripts/shelf-stale-tag-triage.py` — never park `gate_action=hunt` as “maybe later” without `--probe` (trap `gate_hunt_deferred_unprobed`)  

Unreadable shelf rows may rely on **auto-换源**; do not delete shelf-referenced URLs without origin check.  
