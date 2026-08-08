# 2026-08-08 — Disposable `temp/` scripts instead of reusable harness

## Summary

Device verification for auto-换源 and shelf-restore was implemented as one-off Python under `temp/shelf_restore/queue/`. The next turn could not re-run a trusted path; work had to be re-promoted into `scripts/` + `docs/guides/`.

## What went wrong

1. Treated a multi-step device workflow as “just for this chat”.
2. Put **executable procedure** in `temp/` (gitignored / untracked working dump), not `scripts/`.
3. Skipped extending existing owners (`change-source-device-session.sh`, smoke wrappers, guides).
4. Hard-coded MCP DHCP IPs inside queue scripts.

## Correct pattern

| Layer | Location |
|---|---|
| Code | `scripts/` (+ `scripts/lib/` shared helpers) |
| How-to | `docs/guides/*.md` |
| Runtime outputs | `temp/` only |
| True throwaway | `.local-scripts/` then delete |

## Harness follow-up

- Shared alwaysApply MDC: `prefer-reusable-scripts.mdc` (SOT `E:/shared-cursor-rules/`, synced to all projects)
- Baseline pointer in `jason-dev-practices.mdc`
- Legado HookRules: `legado_temp_script_write_deny`, `legado_temp_script_shell_deny`
- Promoted scripts: auto-change + shelf-restore under `scripts/` + guides
- agent-harness skill: common pattern “Agent keeps writing one-off scripts under temp/”

## Agent takeaway

If you are about to write a `.py`/`.sh` under `temp/` to “quickly test” — **stop**. Extend or add under `scripts/` and document the runbook in the same turn unless the user explicitly wants a disposable probe in `.local-scripts/`.

## Secondary failure — shared-rules health (phantom cites)

While promoting the lesson into shared alwaysApply `prefer-reusable-scripts.mdc`, the first drafts wrapped **legado-only** paths in backticks (session scripts, guides under `docs/guides/`, even example `scripts/foo-….sh`).  

`sync-rules.ps1` → `verify-health.ps1` → myforge `scan_phantom_cites.py` treats those backticks as path cites and resolves them against the **myforge** tree. Paths that only exist in legado → `missing≠0` → HEALTH FAILED.

### Why it felt like a surprise

1. The path was real in the current workspace (legado), so the agent treated backticks as harmless formatting.
2. Shared rules are projected to every repo; the health root is deliberately myforge, not “whatever repo you edited from”.
3. The phantom-cite gate already existed (2026-08-08 shared-rules sync regression); the agent did not treat `verify-health.ps1` as a required close-out after editing shared MDC.

### Prevention now

| Layer | What |
|---|---|
| Agent rule | `memory-rules.mdc` §「改共享 MDC 时的路径引用门禁」 |
| Shared README | Phantom policy: no project-only backticks; verify-health before done |
| Shared MDC style | `prefer-reusable-scripts.mdc` keeps examples repo-agnostic |
| Machine gate | unchanged: sync → verify-health → phantom scan |

**Close-out checklist after any `E:/shared-cursor-rules` edit:** edit SOT → run `sync-rules.ps1` (it calls verify-health) → require exit 0. If the hard link already wrote through and you skip sync, run `verify-health.ps1` alone and require exit 0 before claiming done.
