# 2026-08-09: adb DB push wiped MCP-saved book sources

## Symptom

Migrating `https://www.80xs.la` → `http://wap.80ge.info`:

1. MCP `save_source(wap…)` returned 「已保存」; `debug_source` + `start_check_sources` reported `finished=1 / failed=0`.
2. Agent remapped shelf books + `enabled=0` on old URL via a **previously pulled** `legado.db`, then `push_legado_db`.
3. After push, `book_sources` no longer contained `http://wap.80ge.info` (only disabled `.la`). MCP `get_source` could still look “alive” briefly while Room/memory disagreed with the file that had been pushed.

Also: `pull` that only `cat databases/legado.db` (no `-wal`/`-shm`) missed MCP writes still in WAL → agents thought the new row was never persisted.

## Root cause

`push_legado_db` is a **whole-file replace** and deletes device WAL/SHM. Pushing a working copy that was pulled **before** the new `bookSourceUrl` existed (or pulled without WAL checkpoint) **deletes** that source.

Slow session side-effects (same turn): fixed `sleep(3)×N` check polls after `finished=1`; long timeouts on dead hosts; shell lines accidentally ending with `WebSearch` (exit 127).

## Evidence

- `persist verify` after SQL INSERT-in-same-file then push: `[('https://www.80xs.la', 0), ('http://wap.80ge.info', 1)]`.
- Pre-fix flow: MCP save → push(old pull) → DB lacked `wap.80ge.info`.
- `scripts/lib/rfc004_device.py` already had WAL-aware pull; `legado_adb.pull_legado_db` did not (until this fix).

## Prevention (shipped)

| Layer | Change |
|-------|--------|
| Library | `pull_legado_db(..., wal=True)` checkpoints WAL into dest |
| Library | `push_legado_db(..., require_source_urls=[…])` refuses stale snapshots |
| Library | `scripts/lib/legado_db_mutate.py` — `with_legado_db` / `upsert_book_source` / disable / remap |
| CLI | `python scripts/legado-db-mutate.py …` |
| MCP | `LegadoMcp.wait_check_done()` early-exit (no sleep×40) |
| Skill trap | `adb_db_push_stale_snapshot_wipes_source` |
| Discipline | MUST use mutate helper; ban MCP-save-then-stale-push |
| Hook | `legado_adb_push_stale_mcp_race_ask` — ASK on any shell `push_legado_db(` except `legado-db-mutate.py` |

## Agent checklist

1. Prefer `python scripts/legado-db-mutate.py` for disable / upsert / remap.
2. If hand-editing SQL: **one** pull → INSERT every new URL → `require_source_urls` → push.
3. Never push a file pulled before `save_source` of a new URL unless that URL is INSERTed into the file first.
4. Checks: `m.wait_check_done(max_wait_s=60)` after `start_check_sources`.
5. Dead-host probes: timeout ≤12s; do not Await whole multi-minute scripts.

## Proof commands

```bash
python -m scripts.lib.legado_db_mutate
# or: python scripts/lib/legado_db_mutate.py
python -c "from scripts.lib.legado_adb import pull_legado_db; import inspect; assert 'wal' in inspect.signature(pull_legado_db).parameters"
rg -n "adb_db_push_stale_snapshot_wipes_source|legado-db-mutate|require_source_urls" \
  scripts/lib/legado_adb.py scripts/lib/legado_db_mutate.py \
  docs/postmortem/2026-08-09-adb-db-push-wipes-mcp-source.md \
  ../legadoSkill/skills/legado-book-source-repair/SKILL.md
```
