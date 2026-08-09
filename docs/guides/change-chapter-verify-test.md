# Change-source device verify

Skill: `skills/legado-change-source-test/SKILL.md`  
Scripts:

- `scripts/change-source-smoke.sh` — unit / install / prefs / wrappers
- `scripts/change-source-device-session.sh` — open book → 换源 → logcat → analyze
- `scripts/change-source-analyze-log.py` — gate report from `LegadoChangeSource` dump
- `scripts/auto-change-device-session.sh` — open dead/empty-toc book → auto-换源 logcat
- `scripts/auto-change-pick-book.py` — list/pick `missing_source` / `empty_toc` candidates
- `scripts/auto-change-analyze-log.py` — gate `trigger` + `ask` + `cap≤30`

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
./scripts/change-source-smoke.sh --device-session          # preferred agent path (manual 换源 dialog)
./scripts/change-source-smoke.sh --auto-change-session     # auto-换源 on open
# or stepwise:
./scripts/change-source-smoke.sh --install-only
./scripts/change-source-device-session.sh --no-install --book-url 'http://…'
./scripts/auto-change-device-session.sh --no-install --kind missing_source
python scripts/auto-change-pick-book.py --kind all --limit 10
./scripts/change-source-smoke.sh --analyze-log temp/legado_change_source_session_*.txt
python scripts/auto-change-analyze-log.py temp/legado_auto_change_session_*.txt
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

## Auto-换源 on open (info page primary)

Preference `自动换源` (default on). Triggers once per book URL (shared across info / read / manga) when:

| Trigger | When |
|---|---|
| `missing_source` | `bookSource == null` (origin not in catalog) |
| `info_fail` | detail (`getBookInfo`) fails while a source is bound |
| `toc_fail` | chapter list load fails while a source is bound |

**Primary surface:** book **简介页** (`BookInfoActivity`) — enter info → invalid source → auto-ask + live UI.  
Text novels only on info page (skips image/audio/video).  
**Fallback:** reading / manga page still hooks the same triggers when opened directly (skip info). Session key: `AutoChangeSource.attemptedBookUrl`.

Does **not** auto-change on content-only failures.

Ask budget (not a full-catalog scan):

- Candidate cap: **`AutoChangeSource.CANDIDATE_CAP = 30`** (ask-order head only)
- Per-source timeout: `AskTimeout.AUTO_CHANGE_MS` (45s)
- Live UI (aligned with manual 换源 dialog):
  - Top: determinate `RefreshProgressBar` (`done/total`)
  - Bottom strip: `自动换源 · 已问 a/b · 问中 x/y` + marquee `询问中 源名…`
  - Info page: same bar + strip above 加入书架/阅读
  - Manga: same metrics on loading overlay (spinner + two-line text)
- Payload: `AutoChangeProgressUi` via `autoChangeProgressLiveData` (not `ReadBook.msg`)

Logcat: `LegadoChangeSource` lines `auto-change trigger=…` / `auto-change ask … candidates=N cap=30`. Unit: `AutoChangeSourceTest`.

### Device session (reusable)

```bash
# List candidates on phone (prefer missing_source)
python scripts/auto-change-pick-book.py --kind missing_source --limit 10
# Full session: optional install → open book → capture → analyze
./scripts/auto-change-device-session.sh --no-install --kind missing_source
# Or pin a book:
./scripts/auto-change-device-session.sh --no-install --book-url 'http://…'
```

PASS when analyzer reports `has_trigger` + `has_ask` + `cap_ok` (candidates ≤ 30).  
UI check (manual): open **简介页** → top determinate bar + bottom `自动换源 · 已问 a/b · 问中 x/y` + `询问中 源名…`.

Known good probe book (when present): **《信仰诸天》朝不保夕** with origin `https://www.9txs.com/` (`missing_source`).

## Agent run record

| Date | Result | Evidence |
|---|---|---|
| 2026-08-08j | smartScore wrong-book demote | Hard `latestMatch=false` always tags + −22 score + length cap 4 (fixes PO18 long Ok ranking first). Pending sort key 55. Sample after: `temp/legado_change_source_score_after_*.txt`. |
| 2026-08-08i | smartScore same-tier spread | Device sample `temp/legado_change_source_score_sample_2026-08-08_194403.txt`: Ok 12/15 collapsed at 82. Formula → continuous length (`chars/350`) + finer respond + soft −3. Analyzer: `scripts/change-source-score-analyze.py`. |
| 2026-08-08h | score UX: metric/tags/smartScore | Word count always shown when measured (incl. too_short); quality tags separate; 0–100 smartScore sort. Unit `ChangeBookSourceQualityTest`. Device: `temp/_cs_score_ux/list.xml` shows `字数：N · Xs` + score + soft tags; log `words=53 verdict=TooShort score=40 visible=true` with `dropContentBad=false` (`temp/legado_change_source_session_2026-08-08_192159.txt` writeback; UI session 19:27). |
| 2026-08-08g | soft-meta badge tighten | Never filter on TOC/latest; no badges on content-bad; TOC never on quality-OK; untrusted → latest only. Unit `ChangeBookSourceQualityTest`; device `temp/legado_change_source_session_2026-08-08_190233.txt` (`too_short` + `stitch_soft_noref`) |
| 2026-08-08f | PASS unit + device soft-stitch | QQ《同时穿越，用装备栏打穿诸天》refLen=199→`too_short`; `stitch_soft_noref`×17 kept (was Hijack); log `temp/legado_change_source_session_2026-08-08_180654.txt`. Full early-stop N/A (120s cut; many QQ too_short_abs) |
| 2026-08-08e | auto-change on **简介页** + shared attemptedBookUrl | BookInfo bar/strip; read/manga fallback; `AutoChangeSource.attemptedBookUrl` |
| 2026-08-08 | code: auto-change on info/toc fail | `AutoChangeSource` helper + ReadBook/ReadManga hooks; unit `AutoChangeSourceTest`; no device session this step |
| 2026-08-08b | auto-change cap=30 + progress UI | `limitCandidates(30)`; `AUTO_CHANGE_MS=45s`; read `upMsg` / manga loading `done/total` |
| 2026-08-08c | auto-change live strip UI | top `RefreshProgressBar` + bottom metrics/current strip; `AutoChangeProgressUi` + inFlight source names |
| 2026-08-08d | harness: auto-change + shelf-restore scripts | promoted `temp/` one-offs → `scripts/auto-change-*`, `scripts/shelf-restore-*`, `docs/guides/shelf-restore.md` |
| 2026-08-06b | PASS untrusted-ref soft gate | 学霸也开挂/必读居: `trusted=false trustReason=page_toc`; `stitch_weak_ref=0`; `stitch_soft_unref` kept; `qualityOk=20` early-stop (`temp/legado_cs_trust_xueba_2026-08-06_145414.txt`). 吞噬: `trusted=true` on real TOC title (`temp/legado_cs_trust_tunshi_2026-08-06_150133.txt`; local body login-walled refLen=32 so early-stop N/A). |
| 2026-08-06 | PASS menu filters (partial session) | UI overflow shows 过滤非小说源/过滤词典简介/正文不合格时移除; prefs broadcast OK; dropContentBad ON→179 content-bad drops (`142328`); OFF→0 content-bad drops + list+ words=-1 tier=5 kept (`143313`); full early-stop FAIL on 学霸也开挂 (qualityOk≪20) |
| 2026-08-05 | PASS (partial: earlyStop=false) | pre-fix device run; motivated the 7 fixes below |
| 2026-08-05b | code fix | hard-cancel Cronet + ask memory demotion + loadWordCount default + early-stop UI + LegadoChangeSource log + prefs deep link/MCP |
| 2026-08-05c | PASS product / FAIL deep_cap | [self-test report](../reference/change-source-selftest-2026-08-05.md); log `temp/legado_change_source_selftest_2026-08-05.txt`; qualityOk=20 early-stop; max deep=49/16 |
| 2026-08-05d | harness + deep Semaphore | device-session script + analyzer; deep `Semaphore` so `--expect-deep-cap` can PASS on re-run |
| 2026-08-05e | PASS (all gates) | Automated `--device-session`; log `temp/legado_change_source_session_2026-08-05_112211.txt`; max deep=16/16; qualityOk=20 early-stop |
| 2026-08-05f | PASS | UX/perf on device; log `temp/legado_change_source_session_2026-08-05_114404.txt`; finish list=20=qualityOk; UI no latest/pending badges; max deep=16/16; label 好源 k/20 |
| 2026-08-05g | PASS | RFC-002 + host pace + title-empty TTL + TOC badge; log `temp/legado_change_source_session_2026-08-05_131638.txt`; `ask-budget` present; list=20; UI latest/toc=0 |
| 2026-08-05h | PASS | OkHttp `maxRequests` raise during 换源 (was 64 vs threads=100 → 正文假慢 40–60s); log `temp/legado_change_source_session_2026-08-05_163812.txt`; bqquge word `ms=1654` (was 57573); word_ok contentMs p50/p90 ≈352/850 |
| 2026-08-05i | PASS | UI「响应时间」= workMs（content−OkHttp queue）；queueMs / deep-gate-wait 仅日志；log `temp/legado_change_source_session_2026-08-05_172955.txt`; bqquge respondMs=1279=workMs; queue p50≈2ms |


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
