# RFC-001: Change-source / search ask-order + book-source sort UX

| Field | Value |
|-------|--------|
| Status | Implemented (P0a/P0b/P0c/P1 — code in `c5a201d84`) |
| Date | 2026-08-03 |
| Repo | `legado` (Android app), branch `master` |
| Related | `legadoSkill` repair tooling (MCP check paths only; no product UX there) |
| Authoring context | User reports slow 换源 with ~1200+ enabled sources; management “smart sort” does not affect ask order |

**Audience:** implementers and other agents reviewing before code changes.

**How to review this RFC:** check (1) problem/evidence accuracy vs cited files, (2) concept boundaries (置顶 vs 点赞 vs 手动顺序 vs 响应时间), (3) non-goals, (4) acceptance tests are falsifiable, (5) no invented parallel systems.

> **Rev 3 note.** Design review against the working tree found six Critical defects in rev 2. This revision fixes them in place: (C1) bad MCP `respondTime` rows are a real ask-order regression under P0b, not “current behaviour” — P0a now includes a one-shot heal pass; (C2) failure formula gains `+ 1` so `spending == 0` cannot collide with the unknown sentinel; (C3) load sites enumerated without the false “four bullets” count; (C4) check vs ask ordering share a rank classifier, not one comparator; (C5) full `BookType`→`BookSourceType` map; (C6) P0c write triggers named per path. Warnings on `CheckPriorityOrder` wording, `Debug.startChecking` compile break, MCP `timeoutMs`, acceptance #5/#10, string locales, and line-number drift are also applied.

> **Rev 2 note (retained).** Every claim in §4 was re-verified against the working tree with file:line citations. Four claims from rev 1 were wrong or incomplete and are corrected inline (marked **[corrected]**). The largest change: §4.4's “must be verified” is now a **confirmed defect** — the MCP check path and the UI check path write *different* failure encodings today, which makes naive ASC-respondTime ordering actively harmful. §6.1's original three-state design also does not survive a user-lowered `CheckSource.timeout` and has been redesigned.

---

## 1. Summary

Two separate product problems:

1. **Ask-order (scheduling):** 换源 / 全局搜索 / 自动换源 load enabled sources ordered by `customOrder` only. Slow or dead sources still occupy the thread pool. Viewing “响应时间排序” in book-source manage does **not** change who is queried first.
2. **Manage-page sort UX:** Most sort menu items only re-order the on-screen list. Only “手动排序” + drag persists `customOrder`. Menu label “智能排序” sorts by `weight`, which is never computed in any production path → looks broken / misleading.

This RFC proposes:

- Unify failure `respondTime` encoding across **all writers that persist check results**, and heal existing MCP-encoded fast failures that would otherwise rank as successes — this is a hard prerequisite, not a nice-to-have.
- Ask order: **置顶 head-reserve → success/unknown/failure rank → respondTime ascending → customOrder**.
- Same-type filter for 换源 (map `Book.type` bits → `bookSourceType`; novel does not query audio/image/file/video sources).
- Update `respondTime` on successful 换源/search at named trigger points, gated against the check path's optimistic-concurrency write.
- Clarify manage-page copy; optional “write current view into 手动顺序”; do **not** invent pin/weight-scheduling/AIMD-for-search.

---

## 2. Goals

| ID | Goal |
|----|------|
| G1 | With many enabled sources (`enabledCount > threadCount`), faster sources are queried earlier during 换源/search. |
| G2 | 置顶 still means “query this source in the first batch”. |
| G3 | Failed sources do not jump ahead because failure was “fast”. |
| G4 | Manage-page sort labels do not imply they change 换源 ask-order. |
| G5 | Reuse existing App concepts only (置顶 / 点赞 / 手动顺序 / 响应时间 / `concurrentRate`). |

## 3. Non-goals

| ID | Non-goal | Why |
|----|----------|-----|
| NG1 | A new “pin” feature with its own UI | Duplicates 置顶. Persisting the *existing* 置顶 action (`pinnedAt` in P2) is **not** a new product feature and is out of P0/P1. |
| NG2 | Use 点赞 to decide ask-order | 点赞 is a per-`(origin, name, author)` preference for the **result list** |
| NG3 | New `weight`-based ask-order | `weight` has no writer in any production path (§4.3), so it carries no signal; respondTime already covers “which source is worth asking first” |
| NG4 | Port check-side AIMD / host token bucket into user 换源 | Sources already have `concurrentRate`; separate anti-ban work |
| NG5 | Silent overwrite of `customOrder` when switching sort menu | Destroys user drag order |
| NG6 | Claiming “应用到手动顺序” fixes 换源 speed by itself | Ask-order uses respondTime after this RFC |

---

## 4. Current behavior (evidence)

All line references are against the working tree at the time of writing. Prefer **symbol names** over line numbers — lines drift.

### 4.1 Ask-order paths

| Path | Load order | Concurrency | Per-source timeout | Early stop | Uses historical `BookSource.respondTime`? |
|------|------------|-------------|--------------------|------------|-------------------------------------------|
| Manual / chapter 换源 | `allEnabledPart` *or* `getEnabledPartByGroup` — both `order by customOrder asc` | `min(AppConfig.threadCount, AppConst.MAX_THREAD)`; default 32, cap 999 | 60s (`withTimeout`) | No (scan all) | No |
| Global search | `SearchScope.getBookSourceParts()` → `HashSet` → `.sortedBy { customOrder }` | same | 30s | No | No |
| Auto 换源 (read view) | `allTextEnabledPart` → `order by b.customOrder` | same | none (full chain) | `.take(1)` — first inner flow to *complete*, then upstream is cancelled | No |

Concurrency primitive differs by path: manual 换源 uses `mapParallel` ([ChangeBookSourceViewModel.kt:238](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:238)); global search and auto 换源 use `mapParallelSafe`. The `.take(1)` note applies **only** to auto 换源.

- **[corrected]** rev 1 listed only `allEnabledPart` for 换源. The group path is a second query, `getEnabledPartByGroup` (function at [BookSourceDao.kt:168](app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt:168); `@Query` starts at :162), reached when `AppConfig.searchGroup` is non-blank ([ChangeBookSourceViewModel.kt:197](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:197)). Any reordering must be applied to **both** branches, and to the empty-group fallback at [:202–204](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:202).
- **[corrected]** rev 1 described `.take(1)` as “first full success in first parallel wave”. `mapParallelSafe` is `flatMapMerge(concurrency).buffer(0)` ([FlowExtensions.kt:59](app/src/main/java/io/legado/app/utils/FlowExtensions.kt:59)), so `.take(1)` yields whichever inner flow completes first *across the whole merge*, not only the first wave. It has no timeout, so a hung source occupies a slot indefinitely.

Primary files:

- [ChangeBookSourceViewModel.kt](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt) — `startSearch()` (:184), `startSearch(origin)` (:214, **single-source refresh — do not reorder**), `search` (:228), `topSource` (:496), result comparators (:86–98). `ChangeChapterSourceViewModel` extends it and inherits the whole load path ([ChangeChapterSourceViewModel.kt:12–13](app/src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceViewModel.kt:12)) — **verified**, no separate edit needed.
- [BookSourceDao.kt](app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt) — `allEnabledPart` (:201–202), `getEnabledPartByGroup` (:162–168), `allTextEnabledPart` (:222–227)
- [SearchScope.kt:108](app/src/main/java/io/legado/app/ui/book/search/SearchScope.kt:108) — `getBookSourceParts()`
- [SearchModel.kt:79](app/src/main/java/io/legado/app/model/webBook/SearchModel.kt:79) — `startSearch()`
- [ReadBookViewModel.kt:322](app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt:322) — `autoChangeSource`

Result-list sort (**not** ask-order): book score → `SourceConfig` source score → `originOrder` / word-count options
([ChangeBookSourceViewModel.kt:86–98](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:86)).

> Note: `ChangeBookSourceViewModel.autoChangeSource` (:535) is a *different* function from `ReadBookViewModel.autoChangeSource` (:322). The former iterates already-collected `searchBooks` and is not an ask path; only the latter is in scope.

### 4.2 置顶 vs 点赞 (must not conflate)

| UI action | Storage | Affects ask-order today? | Affects result list order? |
|-----------|---------|--------------------------|----------------------------|
| 置顶 (`to_top`) | `BookSource.customOrder = bookSourceDao.minOrder - 1` (global min over **all** sources) | Yes (because ask-order is customOrder) | Weak (tie-break via `originOrder`, which `topSource` also updates) |
| 点赞 / 点踩 | `SourceConfig` score for `(origin, name, author)` (+ aggregate source score) | **No** | **Yes** (comparator prefers score) |

Files: `ChangeBookSourceViewModel.topSource` (:496) / `setBookScore` (:557); `help/config/SourceConfig.kt`; adapter menus use `R.string.to_top` ([ChangeBookSourceAdapter.kt:192](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceAdapter.kt:192), [ChangeChapterSourceAdapter.kt:169](app/src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceAdapter.kt:169)).

**置顶 is not durably identifiable.** `SourceHelp.adjustSortNumber()` ([SourceHelp.kt:176](app/src/main/java/io/legado/app/help/source/SourceHelp.kt:176)), called on every app start ([App.kt:131](app/src/main/java/io/legado/app/App.kt:131)), renumbers all sources to `0..n-1` when `maxOrder > 99999 || minOrder < -99999 || hasDuplicateOrder`. Renumbering reads `allPart` (already `order by customOrder asc`) so **relative order is preserved**, but the negative `customOrder` that marked a topped source is erased. There is therefore no persisted flag distinguishing “user topped this” from “this happened to be first”. §6.2 addresses this directly.

### 4.3 Manage-page sort

- Menu: [book_source.xml](app/src/main/res/menu/book_source.xml) — `menu_sort_manual` (:25), `menu_sort_auto` (:31), `menu_sort_respondTime` (:53)
- Strings: `sort_manual` = 手动排序, `sort_auto` = 智能排序 / “Sort automatically”, `sort_by_respondTime` = **响应时间排序** / “Sort by respond time” ([values-zh/strings.xml:446](app/src/main/res/values-zh/strings.xml:446), :1023; [values/strings.xml:449](app/src/main/res/values/strings.xml:449), :925). Also present in `values-zh-rTW`, `values-zh-rHK`, `values-es-rES`, `values-ja-rJP`, `values-pt-rBR`, `values-vi`. **[corrected]** rev 1 quoted this label as “按响应时间”; no such string exists.
- Logic: [BookSourceActivity.kt:354–401](app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt:354) — every non-`Default` sort is applied inside a `.map {}` on the DB flow and is therefore **in-memory only**, never written back.
- Drag gate: [BookSourceActivity.kt:410](app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt:410) — `itemTouchCallback.isCanDrag = sort == BookSourceSort.Default && !groupSourcesByDomain`. **[corrected]** rev 1 omitted the `groupSourcesByDomain` condition; drag is also disabled in domain-grouping mode.
- `BookSourceSort.Weight` ← menu “智能排序” ([BookSourceActivity.kt:220](app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt:220), sorted at :363/:383).

`BookSource.weight` ([BookSource.kt:80](app/src/main/java/io/legado/app/data/entities/BookSource.kt:80)) has exactly one non-UI reference in the app: [JsSourceUpsert.kt:171](app/src/main/java/io/legado/app/model/jsSource/JsSourceUpsert.kt:171), which *preserves* the old value on upsert. **Nothing in this app computes it.** Imported JSON can still carry non-zero values. In the common case (never imported with weight) values are zeros, so “智能排序” degenerates to an arbitrary stable order.

### 4.4 respondTime write paths — **confirmed divergence** (was “must be verified”)

Default: `BookSource.respondTime = 180000L` ([BookSource.kt:78](app/src/main/java/io/legado/app/data/entities/BookSource.kt:78)); the same literal is repeated in the `book_sources_part` view class ([BookSourcePart.kt](app/src/main/java/io/legado/app/data/entities/BookSourcePart.kt)).

There are two **persisting** writers, fed by one in-memory encoder:

| Role | Failure encoding today | Persisted via |
|------|------------------------|---------------|
| `BookSourceCheckRunner.checkSource` (in-memory) | **raw elapsed ms** — [:86](app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt:86) sets `source.respondTime = now - startTime` in an `.also {}` for success *and* failure alike | (caller decides) |
| `CheckSourceService.checkOne` (UI check) | **overwrites** the runner value at [:273](app/src/main/java/io/legado/app/service/CheckSourceService.kt:273) with `Debug.getRespondTime(...)` → `CheckSource.timeout + spending` on failure ([Debug.kt:285](app/src/main/java/io/legado/app/model/Debug.kt:285)) | `updateCheckResult(...)` **CAS form** ([BookSourceDao.kt:301](app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt:301); unconditional overload at :277) |
| `McpSourceCheckJob.checkOne` (MCP check) | **does not overwrite** — persists the runner's raw elapsed ms | `CheckSourceResultWriter.enqueueAndMaybeFlush` → unconditional `updateCheckResult` |

**P0a compile note:** `BookSourceCheckRunner.doCheckSource` currently calls `Debug.startChecking(source)` ([BookSourceCheckRunner.kt:97](app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt:97)), but `Debug` only exposes `startChecking(sessionId, source)` ([Debug.kt:245](app/src/main/java/io/legado/app/model/Debug.kt:245)). Touching the runner for encoding **must** delete or fix that call in the same change.

**Consequence:** a source that fails fast under an MCP check (DNS failure, instant 404, ~200 ms) persists `respondTime ≈ 200`. Ordering by ASC respondTime would put that dead source **ahead of every healthy source**. Today this does **not** affect 换源 ask-order (ask still uses `customOrder` only), but the moment P0b lands, those rows become a **regression relative to today's ask order** — see §6.1 Migration. This is why P0a (encode + heal) is a blocking prerequisite for P0b.

`换源` may set `SearchBook.respondTime` ([ChangeBookSourceViewModel.kt:346](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:346), word-count path only, `Int` ms) — a different entity and column. It does **not** update `BookSource.respondTime`.

Existing check-side ordering helper: [CheckPriorityOrder.kt](app/src/main/java/io/legado/app/model/checkalgo/CheckPriorityOrder.kt) — pure ASC by mapped respondTime; missing map keys use `Long.MAX_VALUE / 2` (so “unknown” here means **absent from the map**, not the `180000` sentinel). Already used by `CheckSourceService` at [:165](app/src/main/java/io/legado/app/service/CheckSourceService.kt:165) and `McpSourceCheckJob` at [:157](app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt:157), not by user 换源. Its comment “Unknowns sort last” describes the missing-key case only; a stored `180000` is treated as an ordinary value. Ask-order needs a different comparator family (§6.2).

### 4.5 Other existing levers

- `AppConfig.searchGroup` / `SearchScope`: filter subset before ask.
- `BookSource.concurrentRate` + `ConcurrentRateLimiter`: per-source pacing at request layer.
- Check-only: host token bucket / AIMD in `model/checkalgo/*` — **out of scope** for this RFC.

### 4.6 Data availability (good news for implementation)

`BookSourcePart` — the type actually loaded by every ask path — already exposes `respondTime`, `weight`, `bookSourceType`, and `customOrder` as columns of the `book_sources_part` view ([BookSourcePart.kt](app/src/main/java/io/legado/app/data/entities/BookSourcePart.kt)). **No schema change and no extra query is needed** to sort or type-filter the ask list. This was not stated in rev 1 and materially lowers the cost of §6.2 and §6.3. (P2 `pinnedAt` would add a column later; P0/P1 do not.)

---

## 5. Concept map (product)

| Concept | Already in App? | Responsibility after this RFC |
|---------|-----------------|--------------------------------|
| 置顶 | Yes | Prefer **querying** this source first (head-reserve, §6.2) |
| 点赞/点踩 | Yes | Prefer **showing** this hit for this book |
| 手动顺序 (`customOrder`) | Yes | Head-reserve key + final tie-break; drag in manage; optional strict ask mode later |
| 响应时间 (`respondTime`) | Yes | Primary ask-order key (after the head reserve) |
| `concurrentRate` | Yes | Keep; do not replace with new 换源 limiter |
| 智能排序 / `weight` | Menu exists; no production writer | Rename/clarify UX only; **not** an ask-order key |

---

## 6. Proposal

### 6.1 Unify respondTime semantics

Rev 1 proposed three states on a single numeric axis and assumed the boundaries fall out naturally. **They do not.** `CheckSource.timeout` is user-configurable and defaults to `180000L` ([CheckSource.kt:63](app/src/main/java/io/legado/app/model/CheckSource.kt:63)) — the *same* value as the never-measured default. So:

- With default settings, failure (`180000 + spending`) happens to sort after unknown (`180000`). Coincidence, not design.
- If the user lowers the check timeout to 30 s, failure encodes as `30000 + spending` → **failures sort ahead of never-measured sources**, and ahead of any honest 45 s success.
- The 換源 success path (§6.4) runs under a 60 s timeout, so it can legitimately write values above a 30 s `CheckSource.timeout`. Any rule of the form “`respondTime >= CheckSource.timeout` means failure” misclassifies those.
- MCP check already passes a per-run `timeoutMs` override into the runner ([McpSourceCheckJob.kt:165,370](app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt:165)). Encoding must use that **effective** timeout, not always the global `CheckSource.timeout`.

**Adopted rule — pin the classification boundary to a constant, not to the configurable timeout:**

1. Extract the literal `180000L` into a single named constant, `BookSource.DEFAULT_RESPOND_TIME`, and use it in both `BookSource` and the `book_sources_part` view class.
2. All writers obey (where `effectiveTimeout` = the `timeoutMs` argument actually applied to that check invocation, defaulting to `CheckSource.timeout`):

| State | Written value | Invariant |
|-------|---------------|-----------|
| Success | `min(elapsed, DEFAULT_RESPOND_TIME - 1)` | strictly `< DEFAULT_RESPOND_TIME` |
| Never measured | `DEFAULT_RESPOND_TIME` exactly | `== DEFAULT_RESPOND_TIME` |
| Failure | `max(effectiveTimeout, DEFAULT_RESPOND_TIME) + spending + 1` | strictly `> DEFAULT_RESPOND_TIME` |

The trailing `+ 1` is required: without it, `spending == 0` and `effectiveTimeout ≤ DEFAULT` yields exactly `DEFAULT`, colliding with unknown. This makes `success < unknown < failure` hold **by construction**, for any timeout setting (including MCP overrides and a user-set timeout of 0), with no schema change. The success clamp is lossy only above 180 s, where the distinction is meaningless anyway.

3. Apply the rule at the single place that owns elapsed + outcome: `BookSourceCheckRunner.checkSource` ([:86](app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt:86)), which already knows `startTime`, `timeoutMs`, and the success/failure fold — encode there and delete the redundant overwrite in `CheckSourceService` ([:273](app/src/main/java/io/legado/app/service/CheckSourceService.kt:273)). That fixes UI check and MCP check in one edit and removes the divergence at its root. In the same edit, fix/remove the broken `Debug.startChecking(source)` call (§4.4). `Debug.getRespondTime` then has no production caller and should be removed or reduced to the debug-panel display it is named for.

> **Migration (adopted — blocking for P0b):**  
> Rev 2 claimed that leaving MCP-encoded fast failures alone is “current behaviour, not a regression.” That is **false for ask-order**. Today ask-order ignores `respondTime`; after P0b those rows (`respondTime ≈ 200`) would rank as **success** and be queried first — worse than today's `customOrder` order.  
>
> **One-shot heal in P0a** (same release as the encoder, before or with first P0b ship): any source whose `getInvalidGroupNames()` is non-blank ([BookSource.kt:224](app/src/main/java/io/legado/app/data/entities/BookSource.kt:224) — groups containing `失效` or equal to `校验超时`) **and** `respondTime < DEFAULT_RESPOND_TIME` is rewritten to `DEFAULT_RESPOND_TIME + 1` (minimal failure encoding). Honest fast successes keep small values and have no invalid group, so they are untouched. Rows that somehow lost their invalid group but kept a bad small value cannot be distinguished; they self-correct on the next check. Do **not** attempt a broader migration.  
>
> Acceptance §9 #13 covers this heal.

### 6.2 Ask-order comparator

After loading the enabled (and group-filtered) list, sort in memory:

1. **置顶 head reserve.** Take the first `HEAD_RESERVE` entries by `customOrder` ascending and keep them at the front, unsorted by respondTime.
2. **State rank** for the remainder: success (0) → unknown (1) → failure (2), derived from §6.1's constant boundaries.
3. **respondTime ascending** within a rank.
4. **customOrder ascending** as final tie-break (keeps the sort deterministic and stable across the paginated global-search re-runs at [SearchModel.kt:74](app/src/main/java/io/legado/app/model/webBook/SearchModel.kt:74)).

**On `HEAD_RESERVE` — adopted default `min(8, threadCount)`.** Per §4.2 there is no persisted marker for 置顶, so “the topped cohort” is not exactly computable. Reserving a fixed head slice is the cheapest construction that makes G2 mechanically true: with `HEAD_RESERVE = 8` and a default `threadCount` of 32, a topped source (still at the front of `customOrder` after `adjustSortNumber`) always lands in the first parallel wave, at a cost of at most 8 possibly-slow slots per run.

Known failure modes, stated rather than hidden:
- A user who topped 20 sources gets 8 guaranteed; the other 12 fall back to respondTime ranking.
- A user who topped nothing donates up to 8 slots to whichever sources happen to have the lowest `customOrder` — a bounded G1 cost accepted for G2. Reviewers who prefer maximising G1 for never-top users should argue for `HEAD_RESERVE = 0` until P2 `pinnedAt`; that is an open product choice (§10), not an implementation ambiguity.

If that proves insufficient in practice, the exact fix is to persist the existing 置顶 action (a `pinnedAt: Long?` column on `book_sources`, surfaced in the `book_sources_part` view) — see NG1. That is a Room migration and is deliberately deferred to P2.

**Wire into these load sites** (same comparator helper, applied after the list is in memory):

| # | Site | Notes |
|---|------|-------|
| 1 | `ChangeBookSourceViewModel.startSearch()` | Apply once to the list after the allEnabled / group / empty-group fallback resolves ([:197–208](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:197)). Three DB branches, one sort. |
| 2 | `SearchScope.getBookSourceParts()` | Replace the terminal `.sortedBy { it.customOrder }` ([SearchScope.kt:141](app/src/main/java/io/legado/app/ui/book/search/SearchScope.kt:141)). Page 2 must reuse this ordered list — do not re-fetch and reshuffle. |
| 3 | `ReadBookViewModel.autoChangeSource` | After loading `allTextEnabledPart` ([:325](app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt:325)). |

**Out of scope for reordering:** `ChangeBookSourceViewModel.startSearch(origin)` ([:214](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:214)) — single-source refresh; leave the one-element list alone.

`ChangeChapterSourceViewModel` inherits `startSearch`/`search` — verified, no separate edit.

**Architecture (do not pretend check and ask share one comparator):**

- Extract a shared **`RespondTimeRank`** (name flexible) that classifies a `Long` into success / unknown / failure using §6.1 boundaries. Both check and ask may call it.
- Keep `CheckPriorityOrder.orderByPriority(urls, map)` as the check-side ASC helper (missing-key → last). Optionally it can later consult `RespondTimeRank`; that is not required for P0.
- Add a dedicated **`AskSourceOrder`** (or equivalent) for `List<BookSourcePart>`: head-reserve + state rank + respondTime + customOrder. This is intentionally a second comparator. Sharing the *rank classifier* is enough; forking a second *ordering rule* for ask is correct because ask has head-reserve and customOrder tie-break that check does not need.

**Note:** if `enabledCount <= threadCount`, reordering has little effect on “who finishes first”; the benefit appears when the enabled count exceeds concurrency (the user's ~1200 case).

### 6.3 Same-type filter

`BookSource.bookSourceType` is a small enum — `BookSourceType`: `0 default/文本, 1 audio, 2 image, 3 file, 4 video`. It is **not** the same axis as `Book.type` / `SearchBook.type`, which is the `BookType` bitmask (`text = 0b1000`, `audio = 0b100000`, …). Do not compare them directly; map explicitly.

**Adopted mapping** (same priority as `BookInfoEditActivity` type picker: video > image > audio > webFile > text):

| Book flags (`Book` / `SearchBook.type`) | Target `bookSourceType` |
|-----------------------------------------|-------------------------|
| `isVideo` | `BookSourceType.video` (4) |
| else `isImage` | `BookSourceType.image` (2) |
| else `isAudio` | `BookSourceType.audio` (1) |
| else `isWebFile` | `BookSourceType.file` (3) |
| else (including bare `BookType.text`) | `BookSourceType.default` (0) |

Ignore `local` / `archive` / `notShelf` / `updateError` bits for this filter. If no content bit is set, treat as text → `default`.

For 换源, restrict the pool to sources matching the mapped type. This matches shipped behaviour in auto 换源 for text novels, whose query already hard-codes `b.bookSourceType = 0` ([BookSourceDao.kt:222–227](app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt:222)) — so for text books no new SQL filter is needed on the auto path; manual 换源 still needs the in-memory filter. For audio/image/video/file books, apply the mapped equality filter on the manual 换源 path (auto 换源 today is text-only via `allTextEnabledPart` — leave that as-is unless a separate change extends auto 换源).

**Global search:** no type filter (no single target book). Confirmed in §10.

**Risk worth stating before implementing:** a non-trivial share of community sources are mis-tagged (text sources saved with a non-zero `bookSourceType`). Filtering them out silently removes results that users see today, and the symptom (“换源 finds fewer sources than before”) is hard for a user to attribute. Mitigation: log the filtered count to `AppLog` so the drop is diagnosable, and keep the filter strict-equality rather than a denylist so the behaviour matches auto 换源 for text.

### 6.4 Update respondTime on successful use

On a successful probe, update that source's `BookSource.respondTime` using the §6.1 success encoding. Pick **last-write-wins with the clamp**, not EWMA, for P0c: EWMA needs a second column (or a lossy in-band encoding) to be meaningful across restarts, and the win over last-write is small when the value is only used for coarse ranking. Unit-test the clamp boundary.

**Adopted write triggers (once per source per run):**

| Path | When to write | Elapsed measured from |
|------|---------------|------------------------|
| Manual / chapter 换源 | `ChangeBookSourceViewModel.search(source)` produces ≥1 accepted hit (after name/author filter), regardless of loadInfo/toc/wordCount options | Start of that `search(source)` call → first accepted hit (or end of successful search without further loads) |
| Global search | `SearchModel` receives a non-empty result list from a source that contributes to the merge | That source's search call start → first non-empty return |
| Auto 换源 | The source that wins `.take(1)` in `ReadBookViewModel.autoChangeSource` | That source's parallel block start → completion |

**Do not write** on: empty results, timeout/cancel, dialog open alone, `startSearch(origin)` without a new success, or failed probes.

Two hazards, both concrete:

1. **CAS collision with a running check.** `CheckSourceService` writes via the optimistic form that requires `respondTime = :expectedRespondTime` ([BookSourceDao.kt:301](app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt:301)). A 换源 success write landing mid-check makes the check's write return `updated == 0`, which the service reports to the user as 「校验结果未写回：书源已变更或删除」 ([CheckSourceService.kt:285](app/src/main/java/io/legado/app/service/CheckSourceService.kt:285)) — a misleading message for what is actually a benign race. **Gate the success-path write on `!Debug.isChecking`.** MCP check also calls `Debug.tryStartChecking()` ([McpSourceCheckJob.kt:148](app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt:148)), so the gate covers **UI and MCP** bulk checks: success-path writes are intentionally dropped for the whole check session (not queued). After the check finishes, subsequent 换源/search successes write again. This is accepted for P0c; deferred queue is out of scope.
2. **Write amplification.** A 1200-source 换源 that succeeds on 400 sources would issue 400 row updates. Route them through a batching seam (reuse `CheckSourceResultWriter` patterns or an equivalent), rather than writing per-hit. Note that `CheckSourceResultWriter.updateCheckResult` also writes `enabled` and comment/group columns — the success path must not clobber those, so it needs a narrower `@Query` that touches `respondTime` only.

Respect the MCP/save default that preserves device `respondTime` / `customOrder` / `weight` when `preserveOrderWeight` is true ([McpSourceStore.kt:18](app/src/main/java/io/legado/app/web/mcp/McpSourceStore.kt:18), [JsSourceUpsert.kt:169](app/src/main/java/io/legado/app/model/jsSource/JsSourceUpsert.kt:169)).

### 6.5 Manage-page UX

1. Rename “智能排序” (`sort_auto`) to an honest label (e.g. 「按权重」 / “Sort by weight”) and note it usually has no data; it **does not** change 换源 ask-order. Update **all** locale files that define `sort_auto` / help strings: at minimum `values/strings.xml`, `values-zh/strings.xml`, `values-zh-rTW`, `values-zh-rHK`, and the other translated `values-*` that currently ship `sort_auto`.
2. Help text:
   - Most sorts only change how this page is viewed.
   - Only 手动排序 + drag saves order (and drag is also off in domain-grouping mode — §4.3).
   - 换源 ask-order uses **响应时间 + 置顶**, not the current “view by …” menu.
3. Optional button: 「把当前列表写入手动顺序」 (confirm dialog). Persists the current view into `customOrder` via the existing `upOrder(List<BookSourcePart>)` path ([BookSourceViewModel.kt:63](app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceViewModel.kt:63)). Does **not** by itself disable respondTime ask-order.
4. Optional setting (can ship later): 「换源按手动顺序问」 (default off) for users who want strict drag order. When on, it **disables** respondTime ranking and head-reserve for ask paths (customOrder only) — priority over §6.2.

### 6.6 Result list

Keep the existing 点赞 → `SourceConfig` → `originOrder` comparators. Do not merge into ask-order.

### 6.7 Observed, not proposed: producer-side N+1 query

Both 换源 and global search resolve sources one row at a time inside the flow producer — `bs.getBookSource()` at [ChangeBookSourceViewModel.kt:232](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:232) and [SearchModel.kt:90](app/src/main/java/io/legado/app/model/webBook/SearchModel.kt:90), each a single-row `select * from book_sources where bookSourceUrl = ?`. At 1200 enabled sources that is 1200 sequential queries on the producer coroutine, which is part of the user's original “换源 很慢” complaint but is **orthogonal to ask-order** and not fixed by this RFC.

`BookSourcePart.toBookSource()` already implements chunked bulk resolution (`BOOK_SOURCE_QUERY_CHUNK_SIZE = 900`). Note that the current lazy pattern is not purely an oversight — it avoids materialising 1200 full `BookSource` objects (each carrying rule JSON) at once. A chunked prefetch of ~100–200 keeps memory bounded while collapsing the query count.

Recorded here so it is not lost; **out of scope for P0/P1**, candidate for its own RFC.

---

## 7. Implementation phases

| Phase | Work | Depends on | User-visible effect |
|-------|------|------------|---------------------|
| P0a | `DEFAULT_RESPOND_TIME` + encode in `BookSourceCheckRunner` (using `timeoutMs`); drop `CheckSourceService` overwrite; fix `Debug.startChecking` call; **one-shot heal** of invalid-group + `respondTime < DEFAULT` rows | — | Dead hosts stop recording fast times via MCP; existing bad rows no longer look like successes |
| P0b | `AskSourceOrder` + `RespondTimeRank` on the three load sites; same-type filter with full map | **P0a (including heal)** | Faster sources queried earlier when many enabled; 置顶 still first |
| P0c | Success path updates `BookSource.respondTime` at the named triggers (clamped, batched, `Debug.isChecking`-gated, narrow `@Query`) | **P0a** | Metrics improve without a full check every time |
| P1 | Manage copy + rename (all locales) + 「写入手动顺序」 (+ optional strict manual ask) | — | Less confusion |
| P2 (deferred) | Persist 置顶 (`pinnedAt` column + migration) if the head-reserve heuristic proves insufficient | P0b in the field | Exact 置顶 semantics |

P0b before P0a (or P0a without the heal) is a **regression**, not a partial improvement — ordering ASC by today's mixed encodings / unhealed MCP rows promotes fast-failing dead sources ahead of today's `customOrder` order. Ship them together or in order.

Out of this RFC's P0/P1: consecutive-fail skip lists, host token bucket for user search, producer-side bulk source loading (§6.7), deferred queue for gated P0c writes.

---

## 8. Files likely touched

Ask-order + encoding:

- [`model/BookSourceCheckRunner.kt`](app/src/main/java/io/legado/app/model/BookSourceCheckRunner.kt) — encode success/failure at :86; fix `startChecking` call at :97
- [`service/CheckSourceService.kt`](app/src/main/java/io/legado/app/service/CheckSourceService.kt) — remove the :273 overwrite
- [`web/mcp/McpSourceCheckJob.kt`](app/src/main/java/io/legado/app/web/mcp/McpSourceCheckJob.kt) — verify it picks up the runner's encoding unchanged (already passes `timeoutMs`)
- [`model/Debug.kt`](app/src/main/java/io/legado/app/model/Debug.kt) — `getRespondTime` loses its production caller
- [`data/entities/BookSource.kt`](app/src/main/java/io/legado/app/data/entities/BookSource.kt), [`BookSourcePart.kt`](app/src/main/java/io/legado/app/data/entities/BookSourcePart.kt) — shared `DEFAULT_RESPOND_TIME`
- [`model/checkalgo/CheckPriorityOrder.kt`](app/src/main/java/io/legado/app/model/checkalgo/CheckPriorityOrder.kt) — keep URL ASC form; add shared `RespondTimeRank` nearby or in the same package
- New (or same package): ask-order helper for `List<BookSourcePart>`
- [`ui/book/changesource/ChangeBookSourceViewModel.kt`](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt)
- [`ui/book/search/SearchScope.kt`](app/src/main/java/io/legado/app/ui/book/search/SearchScope.kt)
- [`ui/book/read/ReadBookViewModel.kt`](app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt)
- [`data/dao/BookSourceDao.kt`](app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt) — narrow respondTime-only update for P0c; heal query for P0a

Manage UX:

- [`ui/book/source/manage/BookSourceActivity.kt`](app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt), `BookSourceViewModel.kt`
- [`res/menu/book_source.xml`](app/src/main/res/menu/book_source.xml), [`res/values/strings.xml`](app/src/main/res/values/strings.xml), [`res/values-zh/strings.xml`](app/src/main/res/values-zh/strings.xml), plus other `values-*/strings.xml` that define `sort_auto`

Not touched in P0/P1: no Room migration for ask-order columns (§4.6). P2 may add `pinnedAt`. Do not change `applicationId`. Prefer editing existing files.

---

## 9. Acceptance tests

Required. Each must be falsifiable — a specific input and a specific assertion.

| # | Setup | Assertion |
|---|-------|-----------|
| 1 | Same source fails once via UI check and once via MCP check | Both paths persist `respondTime > DEFAULT_RESPOND_TIME`. Unit-testable against `BookSourceCheckRunner` without a device. |
| 2 | Set `CheckSource.timeout = 30_000` (or pass `timeoutMs = 30_000`); record one failure with `spending = 0` | Persisted value still `> 180_000`; a never-measured peer at exactly `180_000` still ranks ahead of it. |
| 3 | Sources A (never measured, `180000`), B (success `2000`), C (failure `183000`) | Ask order is B, A, C. |
| 4 | Slow source with small `customOrder`, fast source with large `customOrder`, `threadCount < sourceCount`, neither in the head reserve | Fast source is emitted to the search pool before the slow one. |
| 5 | 置顶 a source that has the worst `respondTime` in the pool | It appears within the first `HEAD_RESERVE` entries of the ask list (not “first `threadCount`”). |
| 6 | 点赞 a result | Result-list position rises; the ask list for the next run is byte-identical to the run before the 点赞. |
| 7 | Text novel 换源 with audio/image/file/video sources enabled; separately, audio book 换源 with mixed types | Text pool is only `bookSourceType == 0`; audio pool is only `== 1`; filtered count is logged to `AppLog`. |
| 8 | Non-blank `AppConfig.searchGroup`; then a group with zero enabled sources | Ordering applies inside the filtered subset; the empty-group fallback at `ChangeBookSourceViewModel.kt:202–204` still resets to all-enabled **and** applies the same ordering. |
| 9 | Manage page: switch to 响应时间排序, then back | No `customOrder` write occurs (assert on `upOrder` call count). 「写入手动顺序」 writes only after the confirm dialog is accepted. |
| 10 | Trigger a 换源 success write while a check is running (`Debug.isChecking == true`) | The success-path write is skipped; the check's CAS write still succeeds; no 「校验结果未写回」 message. After check ends, a later success write persists. |
| 11 | MCP `save_source` with default options on an existing source | Device `respondTime` / `customOrder` / `weight` are preserved. |
| 12 | Global search, page 1 then page 2 | The source order is identical across pages (comparator is total and stable; page 2 must not re-get + reshuffle). |
| 13 | Preload a row with invalid group (`网站失效`) and `respondTime = 200`; run P0a heal; then ask-order | Row becomes `> DEFAULT`; it ranks in the failure band, not ahead of honest successes. |
| 14 | MCP check with `timeoutMsOverride = 10_000` ≠ global `CheckSource.timeout` | Failure encoding still `> DEFAULT` and uses the effective 10 s bound in the formula. |
| 15 | `startSearch(origin)` for a single origin | The one-element list is not passed through a global reshuffle that would matter; no multi-source comparator side effects. |
| 16 | P0c narrow update | Success-path write changes only `respondTime`; `enabled`, groups, and comments are unchanged. |

---

## 10. Risks / open points for reviewers

1. **置顶 head reserve is a heuristic** (§6.2). Adopted default `HEAD_RESERVE = min(8, threadCount)`. Product alternative: `0` until P2 `pinnedAt` (maximises G1 for never-top users, weakens G2). Pick one before coding; do not leave both half-implemented.
2. **Heal pass coverage** (§6.1). Only heals `invalidGroup ∧ respondTime < DEFAULT`. Confirm this is enough; do not expand to “all small respondTimes”.
3. **Type filter may reduce visible results** for users with mis-tagged sources (§6.3). This is the change in this RFC most likely to generate “it got worse” reports.
4. Global search (no current book) does **not** type-filter — confirmed.
5. Whether 「换源按手动顺序问」 is required in P1 or can wait. When present, it overrides §6.2 entirely.
6. `ChangeBookSourceViewModel` captures `threadCount` at construction ([:63](app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt:63)), so a mid-session concurrency change is not picked up. Pre-existing; relevant only because `HEAD_RESERVE` is sized relative to it.
7. Auto 换源 has no per-source timeout (§4.1). Reordering makes a hung source *more* likely to be picked early if its historical respondTime is stale-good. **Out of scope for P0b** — do not silently add `withTimeout` in this RFC's P0; track as a follow-up if field reports hang-after-reorder.
8. P0c drops writes during any check session (UI or MCP) without a deferred queue — accepted for P0.

---

## 11. Review checklist (for other AIs)

- [ ] Evidence in §4 re-verified against the current tree (line numbers drift — check the symbol, not just the line)
- [ ] No new concept duplicates 置顶 / 点赞 / respondTime
- [ ] Non-goals respected (no AIMD port, no weight ask-order, no silent `customOrder` overwrite)
- [ ] P0a (encode **and** heal) lands before or with P0b (§7) — reversing them is a regression vs today's customOrder ask-order
- [ ] The §6.1 invariant (`success < DEFAULT_RESPOND_TIME < failure`) holds for **every** writer, at any `effectiveTimeout`, including `spending == 0`
- [ ] Ordering applied to all three load sites; `startSearch(origin)` excluded; group + empty-group branches covered inside site #1
- [ ] `ChangeChapterSourceViewModel` verified to inherit the reordered path
- [ ] Ask-order uses a dedicated comparator; check keeps URL ASC; only `RespondTimeRank` is shared
- [ ] Acceptance tests are observable on device or with fakes; §9 #1–#3 and #13–#14 should be plain JVM unit tests
- [ ] Auto 换源 `.take(1)` semantics (first *completion* across the merge, upstream cancelled) still understood after reordering

---

## 12. Revision history

| Date | Change |
|------|--------|
| 2026-08-03 | Initial draft RFC from product discussion (ask-order + sort UX; drop pin/weight-scheduling) |
| 2026-08-03 | Rev 2: verified all §4 evidence with file:line; corrected four claims (group load path, `.take(1)` semantics, `sort_by_respondTime` label, drag gate condition). Upgraded §4.4 from “must be verified” to a confirmed three-writer divergence. Redesigned §6.1 after finding `CheckSource.timeout` is user-configurable and collides with the `180000` sentinel. Added §4.6 (no schema change needed), §6.7 (producer N+1, out of scope), CAS/write-amplification hazards in §6.4, `BookSourceType` vs `BookType` distinction and mis-tagging risk in §6.3, phase dependencies in §7, and a falsifiable §9. |
| 2026-08-03 | Rev 3: design-review Critical fixes — (1) P0a one-shot heal for invalid-group + small respondTime (rev2 “not a regression” claim was wrong vs today's customOrder ask-order); (2) failure formula `+ 1` so spending=0 cannot equal DEFAULT; (3) load sites enumerated as three sites / `startSearch(origin)` excluded; (4) `RespondTimeRank` shared + dedicated `AskSourceOrder` (dropped “do not fork”); (5) full BookType→BookSourceType map; (6) P0c write triggers named per path. Also: CheckPriorityOrder missing-key wording; `Debug.startChecking` compile note; MCP `timeoutMs` / effectiveTimeout; acceptance #5/#10/#13–#16; string locales; softened weight claim; auto `withTimeout` deferred as follow-up not silent P0 scope. |
