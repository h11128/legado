# RFC-001: Change-source / search ask-order + book-source sort UX

| Field | Value |
|-------|--------|
| Status | Draft |
| Date | 2026-08-03 |
| Repo | `legado` (Android app) |
| Related | `legadoSkill` repair tooling (MCP check paths only; no product UX there) |
| Authoring context | User reports slow 换源 with ~1200+ enabled sources; management “smart sort” does not affect ask order |

**Audience:** implementers and other agents reviewing before code changes.

**How to review this RFC:** check (1) problem/evidence accuracy vs cited files, (2) concept boundaries (置顶 vs 点赞 vs 手动顺序 vs 响应时间), (3) non-goals, (4) acceptance tests are falsifiable, (5) no invented parallel systems.

---

## 1. Summary

Two separate product problems:

1. **Ask-order (scheduling):** 换源 / 全局搜索 / 自动换源 load enabled sources ordered by `customOrder` only. Slow or dead sources still occupy the thread pool. Viewing “按响应时间” in book-source manage does **not** change who is queried first.
2. **Manage-page sort UX:** Most sort menu items only re-order the on-screen list. Only “手动排序” + drag persists `customOrder`. Menu label “智能排序” sorts by `weight`, which is almost never updated → looks broken / misleading.

This RFC proposes:

- Unify failure `respondTime` encoding (UI check + MCP check).
- Ask order: **置顶 first → respondTime ascending → customOrder**.
- Same-type filter for 换源 (novel does not query video/file sources).
- Update `respondTime` on successful 换源/search (not only full check).
- Clarify manage-page copy; optional “write current view into 手动顺序”; do **not** invent pin/weight-scheduling/AIMD-for-search.

---

## 2. Goals

| ID | Goal |
|----|------|
| G1 | With many enabled sources (`count > threadCount`), faster sources are queried earlier during 换源/search. |
| G2 | 置顶 still means “query this source in the first batch”. |
| G3 | Failed sources do not jump ahead because failure was “fast”. |
| G4 | Manage-page sort labels do not imply they change 换源 ask-order. |
| G5 | Reuse existing App concepts only (置顶 / 点赞 / 手动顺序 / 响应时间 / `concurrentRate`). |

## 3. Non-goals

| ID | Non-goal | Why |
|----|----------|-----|
| NG1 | New “pin” feature | Duplicates 置顶 |
| NG2 | Use 点赞 to decide ask-order | 点赞 is per book+source preference for **result list** |
| NG3 | New `weight`-based ask-order | Duplicates fail→large respondTime + 点赞; `weight` is dead today |
| NG4 | Port check-side AIMD / host token bucket into user 换源 | Sources already have `concurrentRate`; separate anti-ban work |
| NG5 | Silent overwrite of `customOrder` when switching sort menu | Destroys user drag order |
| NG6 | Claiming “应用到手动顺序” fixes 换源 speed by itself | Ask-order uses respondTime after this RFC |

---

## 4. Current behavior (evidence)

### 4.1 Ask-order paths

| Path | Load order | Concurrency | Per-source timeout | Early stop | Uses historical `BookSource.respondTime`? |
|------|------------|-------------|--------------------|------------|-------------------------------------------|
| Manual / chapter 换源 | `bookSourceDao.allEnabledPart` → `order by customOrder` | `AppConfig.threadCount` (default 32) | 60s (`withTimeout`) | No (scan all) | No |
| Global search | `SearchScope` → `sortedBy { customOrder }` | same | 30s | No | No |
| Auto 换源 | `allTextEnabledPart` → `customOrder` | same | full chain | `.take(1)` first full success in first parallel wave | No |

Primary files:

- `app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt` (`startSearch`, `search`, `topSource`, result comparators)
- `app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt` (`allEnabledPart`, `allTextEnabledPart`)
- `app/src/main/java/io/legado/app/ui/book/search/SearchScope.kt`
- `app/src/main/java/io/legado/app/model/webBook/SearchModel.kt`
- `app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt` (`autoChangeSource`)

Result-list sort (not ask-order): book score → `SourceConfig` source score → `originOrder` / word-count options  
(`ChangeBookSourceViewModel` `comparatorBase` / `wordCountComparator`).

### 4.2 置顶 vs 点赞 (must not conflate)

| UI action | Storage | Affects ask-order today? | Affects result list order? |
|-----------|---------|--------------------------|----------------------------|
| 置顶 (`to_top`) | Sets `BookSource.customOrder = minOrder - 1` | Yes (because ask-order is customOrder) | Weak (tie-break via `originOrder`) |
| 点赞 / 点踩 | `SourceConfig` score for `(origin, name, author)` (+ aggregates source score) | **No** | **Yes** (comparator prefers score) |

Files: `ChangeBookSourceViewModel.topSource` / `setBookScore`; `help/config/SourceConfig.kt`; adapter menus use `R.string.to_top`.

### 4.3 Manage-page sort

Menu: `app/src/main/res/menu/book_source.xml`  
Strings: `sort_manual`, `sort_auto` (“智能排序”), `sort_by_respondTime`, … in `values-zh/strings.xml`  
Logic: `BookSourceActivity` — non-Default sorts are **in-memory only**; drag enabled only for `BookSourceSort.Default`.

`BookSourceSort.Weight` ← menu “智能排序”. Field `BookSource.weight` is preserved on upsert but **not computed** in normal production paths → typically all zeros.

### 4.4 respondTime write paths

- Default: `BookSource.respondTime = 180000L` (`BookSource.kt`).
- UI check: failure encoding via `Debug.getRespondTime` (timeout + spending pattern).
- MCP / `BookSourceCheckRunner`: must be verified to use the **same** failure encoding before ask-order by ASC respondTime (raw short failure times would wrongly prioritize dead hosts).
- 换源 may set `SearchBook.respondTime` in some paths; that does **not** reliably update `BookSource.respondTime`.

Existing check-side ordering helper (same semantic family):  
`app/src/main/java/io/legado/app/model/checkalgo/CheckPriorityOrder.kt` (unknowns last; used in bulk check, not user 换源).

### 4.5 Other existing levers

- `AppConfig.searchGroup` / `SearchScope`: filter subset before ask.
- `BookSource.concurrentRate` + `ConcurrentRateLimiter`: per-source pacing at request layer.
- Check-only: host token bucket / AIMD in check services — **out of scope** for this RFC.

---

## 5. Concept map (product)

| Concept | Already in App? | Responsibility after this RFC |
|---------|-----------------|--------------------------------|
| 置顶 | Yes | Prefer **querying** this source first |
| 点赞/点踩 | Yes | Prefer **showing** this hit for this book |
| 手动顺序 (`customOrder`) | Yes | Tie-break; drag in manage; optional strict ask mode later |
| 响应时间 (`respondTime`) | Yes | Primary ask-order key (after 置顶) |
| `concurrentRate` | Yes | Keep; do not replace with new 换源 limiter |
| 智能排序 / `weight` | Menu exists; data dead | Rename/clarify UX only; **not** ask-order key |

---

## 6. Proposal

### 6.1 Unify respondTime semantics

Three states, same for UI check and MCP check writers:

| State | Value | Ask-order position |
|-------|-------|--------------------|
| Success | Measured ms (optionally EWMA-smoothed on later successes) | Ascending (faster first) |
| Failure | `CheckSource.timeout + spending` (align with UI `Debug.getRespondTime`) | After successes |
| Never measured | Default sentinel still “unknown” | After known successes; do **not** treat every literal `180000` as unknown if it can also mean real slow success / configured timeout |

### 6.2 Ask-order comparator

After loading the enabled (and group-filtered) list, sort in memory:

1. **置顶 cohort** — sources whose `customOrder` is in the “topped” region (same mechanism as today’s `topSource`: near global `minOrder`). Exact detection: document in implementation (e.g. `customOrder <= currentMinOrder + epsilon` of topped set, or track topped URLs if needed). Requirement: user-visible 置顶 still enters the first parallel wave.
2. **respondTime ascending** using §6.1 effective values.
3. **customOrder ascending** as tie-break.

Wire into:

- `ChangeBookSourceViewModel` (book + chapter 换源)
- `SearchScope` / `SearchModel` load path
- `ReadBookViewModel.autoChangeSource`

Prefer extending `CheckPriorityOrder` (or one shared helper) so check and user search share the same respondTime ordering rules.

**Note:** If `enabledCount <= threadCount`, reordering has limited effect on “who finishes first”; benefit is when enabled count is larger than concurrency (user’s ~1200 case).

### 6.3 Same-type filter

For 换源 (and search when scoped to a book type), only include sources whose `bookSourceType` matches the current book (align with auto-change using text sources for novels). Do not query video/file sources while changing origin for a text novel.

### 6.4 Update respondTime on successful use

On successful 换源/search probe for a source, update that `BookSource.respondTime` (EWMA or replace-with-measured — pick one in implementation and unit-test). Do not write raw fast-failure times into success path.

Respect MCP / save defaults that preserve device `respondTime` / `customOrder` / `weight` when `preserveOrderWeight` is true.

### 6.5 Manage-page UX

1. Rename “智能排序” to an honest label (e.g. “按权重”) and note it usually has no data; **does not** change 换源 ask-order.
2. Help text:
   - Most sorts only change how this page is viewed.
   - Only 手动排序 + drag saves order.
   - 换源 ask-order uses **响应时间 + 置顶**, not the current “view by …” menu.
3. Optional button: **「把当前列表写入手动顺序」** (confirm dialog). Persists view → `customOrder`. Does **not** by itself disable respondTime ask-order.
4. Optional setting (can ship later): **「换源按手动顺序问」** (default off) for users who want strict drag order.

### 6.6 Result list

Keep existing 点赞 → `SourceConfig` → … comparators. Do not merge into ask-order.

---

## 7. Implementation phases

| Phase | Work | User-visible effect |
|-------|------|---------------------|
| P0a | Unify failure respondTime (UI + MCP writers) | Dead hosts stop jumping ahead on short failures |
| P0b | Ask-order comparator + 置顶 + same-type filter on all four entry points | Faster sources queried earlier when many enabled; 置顶 still first |
| P0c | Success path updates `BookSource.respondTime` | Metrics improve without full check every time |
| P1 | Manage copy + rename + “write into 手动顺序” (+ optional strict manual ask) | Less confusion |

Out of this RFC’s P0/P1: consecutive-fail skip lists, host token bucket for user search.

---

## 8. Files likely touched

- `app/.../model/BookSourceCheckRunner.kt` (and check result writer / `Debug.getRespondTime` alignment)
- `app/.../model/checkalgo/CheckPriorityOrder.kt` (extend / share)
- `app/.../ui/book/changesource/ChangeBookSourceViewModel.kt`
- `app/.../ui/book/search/SearchScope.kt`, `model/webBook/SearchModel.kt`
- `app/.../ui/book/read/ReadBookViewModel.kt`
- `app/.../ui/book/source/manage/BookSourceActivity.kt`, `BookSourceViewModel.kt`
- `app/src/main/res/menu/book_source.xml`, `values/strings.xml`, `values-zh/strings.xml`

Do not change `applicationId`. Prefer editing existing files; no 300-line split mandate in this repo.

---

## 9. Acceptance tests

Reviewers should treat these as required:

1. **Failure encoding:** After UI check failure and after MCP check failure, ask-order places that source after successful low-RT sources (not first due to short failure).
2. **Unknown:** Never-measured sentinel sources after known successful RT sources.
3. **Many sources:** Construct slow source with small `customOrder` and fast source with large `customOrder`, `threadCount < sourceCount` → progress / first successful hits prefer the fast source.
4. **置顶:** Topped source appears in the first parallel wave.
5. **点赞:** Raises result-list position; does not alone change ask-order.
6. **Type filter:** Text-novel 换源 pool excludes video/file source types.
7. **searchGroup / SearchScope:** Ordering applies inside filtered subset; empty-group fallback unchanged.
8. **Manage view:** Switching “按响应时间” does not write `customOrder`; “写入手动顺序” does after confirm.
9. **preserveOrderWeight:** Default save keeps device respondTime/customOrder/weight.

---

## 10. Risks / open points for reviewers

1. **How to detect “置顶 cohort”** after many tops and `adjustSortNumber` renumbers — need a robust rule or explicit flag.
2. **EWMA vs last-write** for success updates — pick one; avoid fighting check writes.
3. **Literal 180000** ambiguity (default vs real 180s) — document detection of “never measured”.
4. Whether global search (no current book) should type-filter or only 换源 should.
5. Whether “换源按手动顺序问” is required in P1 or can wait.

---

## 11. Review checklist (for other AIs)

- [ ] Evidence in §4 matches current `main` / local tree
- [ ] No new concept duplicates 置顶 / 点赞 / respondTime
- [ ] Non-goals respected (no AIMD port, no weight ask-order, no silent customOrder overwrite)
- [ ] MCP and UI failure respondTime called out as a hard prerequisite for ASC ordering
- [ ] Acceptance tests are observable on device or with fakes
- [ ] Auto 换源 `.take(1)` behavior still understood after reordering first wave

---

## 12. Revision history

| Date | Change |
|------|--------|
| 2026-08-03 | Initial draft RFC from product discussion (ask-order + sort UX; drop pin/weight-scheduling) |
