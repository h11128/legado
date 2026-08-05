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
Scripts:

- `scripts/change-source-smoke.sh`
- `scripts/change-source-device-session.sh`
- `scripts/change-source-analyze-log.py`

Debug package: `com.legado.app.debug` (override `LEGADO_DEBUG_PKG`)

<!-- Mirror: E:/shared-skills/legado-change-source-test/ ; .cursor/skills/ (local exclude) -->

## Gate (MUST)

1. `GRADLE_USER_HOME` = `E:/.gradle` (Git Bash often `/e/.gradle`; WSL `/mnt/e/.gradle`).
2. USB device with bookshelf **网络书** that has ≥2 alternate sources; mid-chapter with cached body preferred.
3. Never claim PASS without evidence (gradle / UI dump / MCP JSON / `LegadoChangeSource` logcat **and** analyzer verdict). Partial = note which prefs/gates were off.

## One-shot

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"
./scripts/change-source-smoke.sh --unit-only
./scripts/change-source-smoke.sh --apply-prefs
./scripts/change-source-smoke.sh --device-session
# Re-analyze a saved dump:
./scripts/change-source-smoke.sh --analyze-log temp/legado_change_source_session_*.txt
adb logcat -s LegadoChangeSource
```

`--device-session` opens last-read book (or `CHANGE_SOURCE_BOOK_URL` / `--book-url`), taps 换源, starts **once**, waits for `finish`, writes log + `temp/change_source_analyze_*.md|json`.

## Device checklist (≤5 min manual fallback)

| Step | Action | Pass |
|---|---|---|
| Prefs | `--apply-prefs` or MCP `set_change_source_prefs` | `--assert-prefs` OK |
| 整书 | toolbar「换源」 | `已询问`/`询问中`/`深探`; good `字数：N`; early-stop subtitle |
| 单章 | long-press「换源」→ 单章换源 | same quality path |
| Stall | single probe >70s | fail hard-cancel |
| MCP | `start_check_sources` 1 URL | `finished`; fail RT / 失效分组 |

## Unit

```bash
./scripts/change-source-smoke.sh --unit-only
# = checkalgo.* + Rfc001*
```

## Record

1. Keep the session log under `temp/legado_change_source_*.txt`.
2. Run analyzer; paste verdict into guide「Agent run record」.
3. For regressions worth keeping, add/update `docs/reference/change-source-selftest-*.md`.
