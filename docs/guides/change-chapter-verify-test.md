# Change-source device verify

Skill: `skills/legado-change-source-test/SKILL.md`  
Scripts:

- `scripts/change-source-smoke.sh` — unit / install / prefs / wrappers
- `scripts/change-source-device-session.sh` — open book → 换源 → logcat → analyze
- `scripts/change-source-analyze-log.py` — gate report from `LegadoChangeSource` dump

Package: `com.legado.app.debug`

Covers **整书换源** / **单章换源** quality + ask-order + early-stop + MCP check writeback.

## Preconditions (UI)

- Bookshelf has a **网络书** with ≥2 working alternate sources for the same title.
- Prefer mid-chapter with **cached body** (local ref helps consensus / hijack demotion).
- Avoid books stuck in auto-换源 lock if the dialog never opens.

## Fast path

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"   # or /mnt/e/.gradle on WSL
./scripts/change-source-smoke.sh --unit-only
./scripts/change-source-smoke.sh --apply-prefs
./scripts/change-source-smoke.sh --device-session          # preferred agent path
# or stepwise:
./scripts/change-source-smoke.sh --install-only
./scripts/change-source-device-session.sh --no-install --book-url 'http://…'
./scripts/change-source-smoke.sh --analyze-log temp/legado_change_source_session_*.txt
adb logcat -s LegadoChangeSource
```

| Layer | Prove | How |
|---|---|---|
| A Unit | checkalgo + Rfc001 + demotion | `--unit-only` |
| B Install | code on phone | `--install-only` / `--device-session` |
| C Device session | ask parallel + deep cap + early-stop + list-drop | `--device-session` → analyze JSON/MD in `temp/` |
| D Prefs | quality path armed | `--apply-prefs` / MCP |
| E MCP | check status writeback | 1 URL `start_check_sources` |

## Log gates (analyzer)

`change-source-analyze-log.py` (add `--expect-deep-cap` for strict deep concurrency):

| Gate | Pass when |
|---|---|
| `has_start` / `has_finish` | session head+tail present |
| `ask_parallel_ok` | `inFlight` reaches ≥ half of ask cap |
| `deep_within_cap` | max `deep` ≤ `deepParallel` (requires Semaphore gate) |
| `list_drop_on_bad` | content-bad drops and/or OK word-evals exist |
| `quality_ok_useful` | `qualityOk≥5` or early-stop fired |
| `early_stop_honored` | if pref on and target hit → `early=true` |

Never claim PASS without the log file path + analyzer verdict.

## UI pass criteria

- Progress: `结果 N · 已询问 a/b · 询问中 x/y · 询问中 … · 深探 n/cap` (not bouncing indeterminate bar)
- Early-stop: `已足够好源（N）· 已停止 …`
- Bad rows removed after probe (`list- drop`), not left as hijack spam
- Good rows: `字数：N` (pending rows may show briefly while deep runs)
- Stall single probe **>70s** → fail hard-cancel

Toolbar **换源** opens 整书 dialog; long-press still offers 单章/整书 where wired.

## Agent run record

| Date | Result | Evidence |
|---|---|---|
| 2026-08-05 | PASS (partial: earlyStop=false) | pre-fix device run; motivated the 7 fixes below |
| 2026-08-05b | code fix | hard-cancel Cronet + ask memory demotion + loadWordCount default + early-stop UI + LegadoChangeSource log + prefs deep link/MCP |
| 2026-08-05c | PASS product / FAIL deep_cap | [self-test report](../reference/change-source-selftest-2026-08-05.md); log `temp/legado_change_source_selftest_2026-08-05.txt`; qualityOk=20 early-stop; max deep=49/16 |
| 2026-08-05d | harness + deep Semaphore | device-session script + analyzer; deep `Semaphore` so `--expect-deep-cap` can PASS on re-run |
| 2026-08-05e | PASS (all gates) | Automated `--device-session`; log `temp/legado_change_source_session_2026-08-05_112211.txt`; max deep=16/16; qualityOk=20 early-stop |
| 2026-08-05f | PASS | UX/perf on device; log `temp/legado_change_source_session_2026-08-05_114404.txt`; finish list=20=qualityOk; UI no latest/pending badges; max deep=16/16; label 好源 k/20 |
| 2026-08-05g | PASS | RFC-002 + host pace + title-empty TTL + TOC badge; log `temp/legado_change_source_session_2026-08-05_131638.txt`; `ask-budget` present; list=20; UI latest/toc=0 |


## Fixes landed (was improvement backlog)

1. Cronet **production** path (`CronetInterceptor`/`NewCallBack`): always timed (≤90s) + cancel UrlRequest; timeout/cancel **no** OkHttp fallback. Probe uses `withTimeoutOrNull`; progress shows in-flight names
2. `ChangeSourceAskMemory` demotes **timeout/error/content-bad** for later asks; **empty** is session-only (does not poison global search). No failure RT write (RFC-safe)
3. `changeSourceLoadWordCount` default **true**
4. Early-stop subtitle strings + `ChangeSourceProgressUi` (no probing flicker after early-stop; 单章校验 mid-stop also shows early-stop copy)
5. Ask-order uses `RespondTimeRank.classify` + demote tail
6. `ChangeSourceLog` → `adb logcat -s LegadoChangeSource`
7. Broadcast `io.legado.app.action.SET_CHANGE_SOURCE_PREFS` + deep link + MCP `set_change_source_prefs` + script `--apply-prefs`
8. Ask/deep split + early `list+` + content-bad drop + stitch override (see self-test report)
9. Deep **Semaphore** cap (true ≤`deepParallel` including suspended IO) + automated device-session/analyze harness

Follow-up Warning fixes: `refreshList` uses `withTimeoutOrNull`; both Cronet interceptors share `CronetHardStop` (no OkHttp stack on timeout).

Open backlog after 2026-08-05e: further perf/UX ideas in
[self-test report](../reference/change-source-selftest-2026-08-05.md)
(remaining: host pacing, shorter ask timeout for known-slow tails — optional).

Landed 2026-08-05f: latest-badge suppress on strong content; title-scoped empty
skip; locale progress strings; `好源 k/n` progress hint; drop pending on early-stop.

Landed 2026-08-05g: [RFC-002](../design/rfc-002-adaptive-ask-timeout.md) adaptive
ask budget; host ask pacing; title-empty 7d persist; TOC soft-badge suppress.
