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
