# Book-source repair efficiency (hot path)

Goal: finish each URL with **minimum wall time** without risking DB wipe or fake `fixed`.

## Default hot path (MUST)

| Step | Do | Avoid |
|------|-----|--------|
| Gate / hunt | `source-cli gate` → `hunt --probe` only if hunt | Brand-alike probes with timeout≥15s in a long serial list |
| PC HTML | timeout **≤12s** per URL | 25–60s urlopen on obvious NXDOMAIN |
| Patch | MCP `save_source` / `legado-db-mutate` | Hand SQL then forget upsert |
| Disable / remap | `python scripts/legado-db-mutate.py …` | Pull → MCP → push old file |
| Device check | `start_check_sources` → **`LegadoMcp.wait_check_done`**；无搜索时 **`checkDiscovery=true`**（双关会假成功） | `sleep(3)×N` / `for i in range(40)`；`checkSearch=false`+`checkDiscovery=false` |
| Debug | `debug_source(..., timeout_sec=35)` default | Default 55–90s on dead hosts |
| Close-out | ledger + retro seal | Ending turn mid-URL |

## DB push (MUST know)

`push_legado_db(merge_live_sources="auto")` (default):

1. If pending MCP saves (session file / IDE hook) already in the work DB → **skip merge** (fast).
2. Else upsert missing via MCP `get_source` → **no whole-DB re-pull**.
3. Else full baseline merge (slow fallback only).

`LegadoMcp.save_source` and IDE `save_source` (via `mcp-deep-dig-claim.py`) call `note_mcp_save`.

Prefer `legado-db-mutate.py` so local upsert + `require_source_urls` keep you on the fast path.

## Infra that enforces this

| Guard | Behavior |
|-------|----------|
| Hook `legado_inefficient_check_poll_ask` | ASK on `get_check_progress` + `sleep(2+)` without `wait_check_done` |
| Hook `legado_probe_long_timeout_ask` | ASK on PC probe `timeout=` ≥20 in repair shells |
| Hook `legado_adb_push_unsafe_false_ask` | ASK when `merge_live_sources=False` without `require_source_urls` |
| Discipline §2b + §efficiency | alwaysApply |
| Trap `adb_db_push_stale_snapshot_wipes_source` | skill |
| Trap `check_search_discovery_both_off_vacuous` | `legado_mcp` forces discovery when both off |

## Proof

```bash
python -c "from scripts.lib.legado_adb import push_legado_db; import inspect; assert inspect.signature(push_legado_db).parameters['merge_live_sources'].default=='auto'"
python -c "from scripts.lib.legado_mcp import LegadoMcp; import inspect; assert inspect.signature(LegadoMcp.debug_source).parameters['timeout_sec'].default==35"
rg -n "wait_check_done|merge_live_sources|note_mcp_save|legado_inefficient" scripts/lib .cursor/audit-hooks/custom_rules.json .cursor/rules/book-source-repair-discipline.mdc
```
