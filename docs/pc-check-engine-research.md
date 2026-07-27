# PC Reuse of Legado Book-Source Check — Research

Date: 2026-07-26  
Related plan: OOM + high concurrency + PC-orchestrated check

## Question

Can we write code so a PC reuses the app’s existing check logic?

## Two different goals

| Goal | Difficulty | Status |
|------|------------|--------|
| PC **calls** app check (`BookSourceCheckRunner` via MCP) | Low | Already done (`start_check_sources`) |
| PC **runs** the same Kotlin engine in-process (no phone) | Medium–Hard | Not in current scope |

Calling the app is not hard. Extracting a pure-JVM engine is.

## Shared entry on device

- [`BookSourceCheckRunner.checkSource`](../app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt)
- App UI: `CheckSourceService`
- PC: MCP `start_check_sources` → `McpSourceCheckJob`

Both paths share the same runner. PC scripts should orchestrate MCP (precheck → batches → report), not reimplement rules.

## Effort to run check on pure JVM

Rough call chain: `BookSourceCheckRunner` → `WebBook.*Await` → `AnalyzeUrl` / `AnalyzeRule` / `JsExtensions`.

| Target | Effort | Notes |
|--------|--------|-------|
| MVP: JSON sources, OkHttp only; WebView sources fail-fast | ~3–4 person-weeks | New `:check-core`, in-memory cookie/cache, strip `appCtx` / `R.string` |
| Parity with phone (WebView / `startBrowserAwait`) | ~8–12+ person-weeks | Need embedded browser or Playwright-class bridge |

### Top blockers

1. `BackstageWebView.kt` — WebView request path  
2. `SourceVerificationHelp.kt` + `WebViewActivity` — interactive verification  
3. Room `CacheManager` / `CookieStore` + `appCtx`  
4. `AppConfig` / SharedPreferences bootstrap before HTTP  
5. `JsExtensions.kt` — large Android JS bridge  

### JVM-friendly pieces already present

- `modules/rhino` — nearly JVM (still `android.library` in Gradle)  
- Rule parse / Rhino / Jsoup / JSONPath — largely pure Kotlin  
- Existing `app/src/test` JVM tests cover fragments only (no e2e `checkSource`)  
- No Robolectric, no desktop CLI in this repo  

Estimate: ~45–55% of check-path logic is already JVM-shaped; ~25–35% is Android but replaceable; ~20–30% is structural Android (WebView / verification UI).

## External repos (not chosen for this work)

| Repo | Format | Engine | Fit for us |
|------|--------|--------|------------|
| [legadoSkill](https://github.com/rezmdie/legadoSkill) | Android JSON | Approx + MCP to device | **Chosen**: same format, already wired |
| [Tthfyth/source](https://github.com/Tthfyth/source) | Legado JSON | Electron/Cheerio rewrite | Useful UI debugger; not same engine |
| [LegadoTeam](https://github.com/LegadoTeam/legado) CLI | JS function sources | Tauri host | Strong desktop tooling; **cannot** run our JSON library |

## Device check performance (2026-07-26 follow-up)

Implemented for high-concurrency bulk check:

- TOC early-stop / page cap under `CheckMode`
- Skip discovery deep-check when search deep-check succeeded
- Nested `mapAsync` capped via `CheckMode.nestedMapAsync`
- Host scheduling evolved from round-robin (`CheckHostSharding`) to
  AIMD + per-host token bucket + work-stealing (`model/checkalgo/`),
  wired in `McpSourceCheckJob` and `CheckSourceService`
- Priority order by historical `respondTime`; Bloom URL dedup; EWMA host skip;
  hedged L1 domain probe; consistent-hash helper for multi-device shards
- Batched partial Room updates (`CheckSourceResultWriter`)
- DNS fail circuit-breaker (`CheckDnsGuard`)
- HTTP body byte cap on check path (`OkHttpUtils` + `CheckMode.maxBodyBytes`)
- WebView default 900ms delay skipped while `Debug.isChecking`
- Debug maps use `ConcurrentHashMap`

PC gaps filled: `disable_dead_sources.py`; `batch_check_mcp.py` classifies failures
and dumps materials under `temp/check_materials/`; `shard_urls.py` for multi-phone splits.

## Physical limits (context for OOM work)

- OkHttp defaults (`maxRequests=64`, `maxRequestsPerHost=5`) are soft and configurable.  
- DNS has no hard client “max 64”; upstream QPS and dead NXDOMAIN hosts dominate.  
- Heap peaks came from holding `dao.all` (~thousands of entities) plus many concurrent TOC/HTTP pipelines.
