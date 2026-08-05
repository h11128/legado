# Change-source self-test — 2026-08-05c

Agent-driven 整书换源 on device after quality/list fixes
(`d212ea925` and follow-ups). Raw logcat:
`temp/legado_change_source_selftest_2026-08-05.txt`.

Analyzer (reproducible):

```bash
python scripts/change-source-analyze-log.py \
  temp/legado_change_source_selftest_2026-08-05.txt --expect-deep-cap
```

## Environment

| Item | Value |
|---|---|
| Device | SM-A366U1 (`RZCYA19Z3DX`) |
| APK | `com.legado.app.debug` `3.26080601debug` (versionCode 38007) |
| Book | 《吞噬星空：收徒万倍返还》 / 新乙 |
| bookUrl | `http://m.elkoparts.net/kanshu/102/102927/` |
| dur chapter | index 411（第410章…）, cached body used as ref |
| Prefs | `loadWordCount=true`, `earlyStop=true`, `earlyStopCount=20`, `threadCount=100` |
| Wall time | ~2m20s (start 10:24:42 → finish 10:27:05) |

## Verdict

**PASS for product goals of that build; FAIL on strict `deep_within_cap` gate
(before Semaphore). Re-verify after Semaphore: PASS all gates (2026-08-05e).**

| Gate | Result | Evidence |
|---|---|---|
| `has_start` / `has_finish` | PASS | start+finish both in logcat |
| Ask parallel (`inFlight`→cap) | PASS | max `inFlight=100/100` within ~1s |
| Ask/deep split (UI not stuck at 1) | PASS | `已询问` climbs while `深探` runs; early `list+` |
| Content-bad list hygiene | PASS | `list- drop` content-bad×21; viewport no hijack badges |
| Stitch false-positive fix | PASS | `ok_stitch_override`×19 kept high-`refSim` stitch |
| qualityOk / early-stop | PASS | `early-stop qualityOk=20`; finish `list=65 qualityOk=20` |
| **Deep concurrency ≤16** | **FAIL** | max `deep=49/16` (limitedParallelism releases on IO suspend) |

Compared to pre-fix session (`docs/reference/change-source-session-2026-08-05.md`):
`qualityOk=1` / `missContentBad=140` / `list=141` → this run
`qualityOk=20` / `missContentBad=21` / `list=65` after drops.

## Finish line

```text
finish cause=JobCancellationException early=true completed=779/1113
list=65 qualityOk=20 hits=89 published=112
missEmpty=426 missTimeout=116 missError=55 missContentBad=21
top=[肉文小说(w=3885) | 夜天连看(w=3826) | …]
```

UI subtitle at end: `结果 65 · 已足够好源（20）· 已停止 779 / 1113`.

## Word-eval mix

| reason | n | Meaning |
|---|---|---|
| `ok_stitch_override` | 19 | stitch flag but strong ref → keep |
| `stitch_weak_ref` | 11 | stitch + weak ref → drop (hijack-like) |
| `ref_sim` | 5 | body ≠ local ref → drop |
| `too_short_*` | 4 | absolute / relative floor |
| `ok` | 1 | clean pass |
| `fetch_error` | 1 | network/parse |

## Test-process gaps found while running

1. Smoke script stopped at install/prefs — **no automated open→换源→capture**.
2. Manual UI taps double-fired start/stop; Chinese `content-desc` broke ASCII coord files under cp1252.
3. Guide still said「探测中」; live UI uses「已询问 / 询问中 / 深探」.
4. No machine gate for `deep ≤ deepCap` until analyzer `--expect-deep-cap`.

## Harness improvements (this change)

| Piece | Role |
|---|---|
| `scripts/change-source-device-session.sh` | Open last-read (or `--book-url`), tap 换源, start once, wait finish, save log |
| `scripts/change-source-analyze-log.py` | Parse start/progress/finish; gate report; `--expect-deep-cap` |
| `change-source-smoke.sh --device-session` / `--analyze-log` | Single entry |
| Guide + skill | Updated pass criteria + record row |

## Optimization backlog (from this run)

### P0 — Deep gate real concurrency (fixed in same change)

`Dispatchers.IO.limitedParallelism(16)` frees the permit when the coroutine
**suspends on network**, so many deep HTTP calls run together (`deep` peaked at 49).
Fix: acquire `Semaphore(deepParallel)` around each deep job so in-flight
(including suspended) stays ≤ cap; progress `深探 n/16` becomes truthful.

### P1 — Soft meta noise on good rows

Top OK rows still show `最新章疑似不一致` beside `字数：3xxx`. Soft meta is
informative but clutters “good enough” results. Options: demote soft badges
below word-count line, or hide latest mismatch when content `refSim` is high.

### P1 — `missEmpty=426` still dominates ask cost

~55% of asks empty for this title. Not a regression, but early-stop at 20 OK
still burned ~780 asks. Ideas: tighter ask-order using prior empty memory for
*this book title* (session already demotes timeout/error/content-bad; empty is
intentionally session-only globally — maybe title-scoped empty skip).

### P2 — Locale progress strings drift

`values-zh` uses「已询问」; `zh-rHK` / `zh-rTW` still「已完成」; JA/ES still
“done/parallel”. Align copy with ask/deep split semantics.

### P2 — Guide gesture wording

Skill said 短按=整书; on this build the toolbar 换源 opens 整书 dialog
directly (long-press still available). Keep docs in sync with actual menu.

### P3 — Re-verify after deep Semaphore

**Done 2026-08-05e:** `./scripts/change-source-device-session.sh --no-install`
→ analyzer **PASS**, `max deep / deepCap = 16 / 16`
(`temp/legado_change_source_session_2026-08-05_112211.txt`).

## How to re-run

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"
./scripts/change-source-smoke.sh --unit-only
./scripts/change-source-smoke.sh --device-session
# or analyze an existing dump:
./scripts/change-source-smoke.sh --analyze-log temp/legado_change_source_selftest_2026-08-05.txt
```
