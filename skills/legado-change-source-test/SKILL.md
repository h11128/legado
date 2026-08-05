---
name: legado-change-source-test
description: >-
  Smoke-test Legado 整书/单章换源 (ask-order, quality badges, early-stop, MCP
  check writeback). Use after change-source / RFC-001 / quality / AskTimeout
  edits, or when asked to device-verify 换源.
---

# Legado change-source test

Process SOT: `docs/guides/change-chapter-verify-test.md`  
Repo skill: `skills/legado-change-source-test/SKILL.md`  
Script: `scripts/change-source-smoke.sh`  
Debug package: `com.legado.app.debug` (override `LEGADO_DEBUG_PKG`)

<!-- Mirror: E:/shared-skills/legado-change-source-test/ ; .cursor/skills/ (local exclude) -->

## Gate (MUST)

1. `GRADLE_USER_HOME` = `E:/.gradle` (Git Bash often `/e/.gradle`; WSL `/mnt/e/.gradle`).
2. USB device with bookshelf **网络书** that has ≥2 alternate sources; mid-chapter with cached body preferred.
3. Never claim PASS without evidence (gradle / UI dump / MCP JSON / `LegadoChangeSource` logcat). Partial = note which prefs/gates were off.

## One-shot

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"
./scripts/change-source-smoke.sh
./scripts/change-source-smoke.sh --apply-prefs
./scripts/change-source-smoke.sh --assert-prefs
adb logcat -s LegadoChangeSource
```

## Device checklist (≤5 min)

| Step | Action | Pass |
|---|---|---|
| Prefs | `--apply-prefs` or MCP `set_change_source_prefs` | `--assert-prefs` OK |
| 整书 | 短按「换源」 | `探测中 …`；坏源劫持徽章；好源 `字数：N`；足够好后「已足够好源」 |
| 单章 | 长按「换源」→ 单章换源 | 同上徽章 |
| Stall | 单源探测 >70s | fail hard-cancel |
| MCP | `start_check_sources` 1 URL | `finished`；失败 `respondTime > 180000` 或失效分组 |

## Unit

```bash
./scripts/change-source-smoke.sh --unit-only
# = checkalgo.* + Rfc001*
```

## Record

Append one row to the guide「Agent run record」. Use `PASS` / `PASS (partial: …)` / `FAIL`.
