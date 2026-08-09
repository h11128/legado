# RFC-004: Cross-source Review Provider（跨源段评提供方）

| Field | Value |
|-------|--------|
| Status | **Accepted for P1 implementation (Rev 1.1)** — P0 supply remains manual-only until a verified review-capable sample exists |
| Date | 2026-08-09 |
| Repo | `legado` (Android app + optional Web read) |
| Related code | `ReviewRule` / `ReviewRuleParser` / `JsSourceReview` / `ReadBookActivity.loadReviewSummaryIfNeeded` / `ReviewDetailDialog`; align: `BookAuthorIdentity`, `SearchBookMerge`, `ChangeChapterVerify.alignResult` / `digramJaccard`; ask: RFC-001/002 change-source |
| Related evidence | Backup ~2810 sources: `ruleReview` configured = 0, `getReviewSummary` = 0 → native 段评 icons never appear for typical shelves |
| Depends on | RFC-003 identity helpers (reuse, do not widen); RFC-001 ask-order for candidate discovery only |
| Design review | [Rev 0](5e2f41ab-1cf7-436d-91a7-58389dae9d53) → Needs changes → Rev 1; [Rev 1](1d6fe34a-ad15-4322-a1a1-53189f581e1d) → two P0 patches → this Rev 1.1 |
| PE task | `#682` |

**Audience:** implementers + product review before coding.

---

## 0. 人话

段评今天绑在「当前正文书源」上。绝大多数书源没有段评规则，所以功能等于死的。

**要：** 正文继续用现在的源；另外选一个**有段评能力的源**当「评论提供方」，把评论贴到当前阅读的章/段上。

**怎么贴：** 复用已有能力——认书（RFC-003 / 搜书合并）、认章（换源 `alignResult`）、认段（仅在段序可验证时用 digram）。不是让每个盗版源都去写段评规则。

**硬约束：**

1. **供给优先**：没有至少一个可验证的评论源样例前，不把「自动发现」当用户可见功能空转。
2. **先章后段**：P1 只做书+章+章桶（`-1`）；P2 段落图标有门禁（段边界与站点 `paraIndex` 同序同号可证明）。
3. **不对错段**：置信度不够 → 章桶或隐藏，禁止 soft 贴段图标。

---

## 1. Problem

### 1.1 Current model

```
Book.origin ──► BookSource.ruleReview / JS getReviewSummary+Detail
                      │
                      ▼
              summary counts keyed by paraIndex
                      │
                      ▼
              icons on TextLine.paragraphNum → ReviewDetailDialog
```

Opening review requires the **content** source to own complete review rules (`enabled` + summary/detail URLs or JS pair). Click path toasts `review_rule_missing` otherwise.

### 1.2 Why users cannot open 段评

1. Review is an optional source capability, not an app-global feed.
2. Sites with real paragraph comments are rare; scrapers rarely ship `ruleReview`.
3. Local / no-origin books have no provider at all.
4. **Supply gap (evidence):** a typical shelf backup can have **zero** review-capable sources → auto-bind alone cannot resurrect the feature.

### 1.3 Product gap

Users already treat **content origin** and **metadata quality** as separable (换源). They reasonably expect **comments** to be separable too: read wherever is readable, comment wherever comments exist.

---

## 2. Goals

| # | Goal |
|---|------|
| G1 | Decouple **content origin** from **review provider** for a shelf book. |
| G2 | Reuse existing review fetch/parse/UI; do not invent a second comment renderer in v1. |
| G3 | Book match via existing identity/search merge (`BookAuthorIdentity.sameBook` / `sameSearchBook`). |
| G4 | Chapter match via `ChangeChapterVerify.alignResult` (discrete scores only — §6.4). |
| G5 | Paragraph map **only when** provider paragraph boundary authority is proven (§6.5 gate); else chapter-bucket only. |
| G6 | Discover / bind review-capable sources; require §6.0 supply gate before auto-discovery UX. |
| G7 | Confidence fail → chapter bucket (`paraIndex = -1`) or hide — **never** silent wrong-paragraph icons. |
| G8 | Persist binding across process death **and content 换源** (migrate / secondary key — §6.8). |

---

## 3. Non-goals

- Central hosted comment service or Legado-account sync of reviews.
- Requiring every book source to implement `ruleReview`.
- Multi-provider merge with cross-site dedupe (**v1 = single primary provider**; multi merge is §10).
- Perfect 1:1 paragraph map across split/合章 / 插广告源.
- Changing how a source’s **own** `ruleReview` works on the native fast path.
- Auto-login / captcha solving for review sites.
- Web-only redesign (Web may consume the same binding later; Android read path is v1).
- Widening RFC-003 placeholder / same-book rules.
- Using review overlay as a substitute for 换源 content quality.
- Shipping P2 paragraph icons without a real provider whose `paraIndex` ↔ content paragraphs is verified (§6.5).

---

## 4. Concepts

| Term | Meaning |
|------|---------|
| **Content book** | Shelf `Book` the user is reading (`bookUrl`, `origin`, TOC, progress). |
| **Review provider source** | A `BookSource` that can load reviews (§6.1). |
| **Provider book** | Bound remote book on the provider source (name/author + provider `bookUrl`). |
| **Binding** | Persisted link content book ↔ provider book (§6.8). |
| **Chapter align** | Map content chapter → provider TOC index + score. |
| **Paragraph map** | Map provider `paraIndex` → local display paragraph id; store `ProviderParaRef` for click. |
| **Chapter bucket** | Provider `paraIndex = -1` (title/章评). Not a fabricated index. |
| **Fast path** | Content `origin` is review-capable and overlay not forced → today’s native behavior. |
| **ProviderParaRef** | `{ providerParaIndex: Int, paraData: String }` — **only** values sent to detail APIs. |

---

## 5. Architecture

```
ReadBook (content book)
    │
    ├─ reviewOverlayEnabled == false → native only (ignore binding)
    │
    ├─ fast path: origin capable && (no binding || binding.providerSource == origin)
    │             → existing loadReviewSummaryIfNeeded
    │
    └─ else: ReviewOverlayController
            │
            ├─ resolve Binding (migrate on 换源 if needed)
            ├─ ensure ProviderBook (cached)
            ├─ align chapter → AlignResult(index, score)
            ├─ fetch provider summary
            ├─ if P2 gate open: ParagraphMap → local icons
            │  else: chapter-bucket UI only (§6.5 / §8 P1)
            └─ onReviewClick → ReviewDetailDialog(
                 providerParaIndex, paraData, provider source/chapter…)
```

**Ownership module (proposed):** `io.legado.app.model.review` — UI thin; parsers stay in `ReviewRuleParser` / `JsSourceReview` (today `internal`: keep overlay in same package module or widen visibility deliberately in §9).

**Config object:** `ReviewAlignConfig` holds all thresholds (§6.4–6.6).

---

## 6. Normative rules

### 6.0 Supply gate (blocks empty product)

Before enabling **auto-discovery** (P3) or claiming “段评源可用” in default UX:

1. Ship or document **≥1** review-capable source that passes device verify: summary non-empty on a known book + detail open for chapter bucket and/or one paragraph.
2. Until then: overlay UI may exist for **manual** bind of capable sources the user imports; **do not** scan N sources or snackbar auto-bind when `capableCount == 0`.
3. Create-guide pointer: extend book-source create/repair docs with “最小段评规则/JS 配对” checklist (out of band doc task, linked from PE `#682`).

### 6.1 Review-capable source

```
isReviewCapable(source) =
  if source.isJsSource():
    declares both getReviewSummary and getReviewDetail
    // detection: static function-name scan of mainJs OR persisted flag after
    // successful JsSourceConfig parse — do NOT execute full JS for every
    // cold enumerate. Process LRU (JsSourceReview.rememberReviewCapability)
    // is cache only, not sole source of truth across process restarts.
  else:
    rule = source.ruleReview
    rule != null
    && rule.enabled
    && reviewSummaryUrl / summaryListRule / summaryParagraphIndexRule / summaryCountRule all non-blank
    && reviewDetailUrl / detailListRule / detailContentRule all non-blank
```

Incomplete rules → not a candidate (same bar as today’s click-open checks).

### 6.2 Fast path vs overlay

```
effectiveMode(book, binding, prefs):
  if !prefs.reviewOverlayEnabled → NATIVE_ONLY
       // ignore binding for load/click; A10
  if binding != null && binding.providerSourceUrl == book.origin
       && isReviewCapable(origin) → NATIVE   // short-circuit same-origin bind
  if binding != null → OVERLAY(binding)
  if isReviewCapable(book.origin source) → NATIVE
  else → OVERLAY_UNBOUND
```

- Clear binding is idempotent (second clear no-op).
- User may **force overlay** to a different capable source while origin is also capable (`binding.providerSourceUrl != origin`).

### 6.3 Book match (provider book)

Given content book `B` and candidate provider source `S`:

1. Search `S` with `B.name` (page 1; short timeout; do not block first paint of reading).
2. Let `peers` = all raw authors of hits with `equalName(name, B.name)` in that response (RFC-003 full peer set — not the pair alone).
3. Keep hits where  
   `BookAuthorIdentity.sameSearchBook(B, hit, peers)`  
   (or equivalent `sameBook(nameA, authorA, nameB, authorB, peerRawAuthors)`).
4. Exactly one same-book hit → auto-acceptable.
5. Zero → source unusable for this title.
6. ≥2 → **no auto-bind**; manual picker only.

**Do not** invent a three-arg `sameBook(Book, hit, peers)` API in prose — call the real helpers.

### 6.4 Chapter align

```
result = ChangeChapterVerify.alignResult(
  localChapter.index, localChapter.title, providerToc
)
accept iff result != null && result.score in CHAPTER_ALIGN_ACCEPT
```

**Discrete accept set (v1):**

| Score | Meaning | Accept? |
|------:|---------|---------|
| 1.0 | Exact pure title in ±10 window | yes |
| 0.95 | Exact pure title outside window | yes |
| 0.7 | Chapter number match in window | yes |
| 0.65 | Chapter number outside window | **no** |
| 0.5 | Blank title → index fallback | **no** |
| 0.4 | Title containment | **no** |

`CHAPTER_ALIGN_MIN` is not a continuous threshold; implementers must use the accept set above (equivalent to accepting `{1.0, 0.95, 0.7}` only).

If not accepted: no paragraph icons; no chapter-bucket click that claims “this chapter’s reviews” from a mis-aligned chapter.

### 6.5 Paragraph map — **gated**

#### 6.5.1 Authority gate (must pass before P2)

Paragraph icons require a **ProviderParagraphAuthority** for that provider source (per source, versioned):

```
authorityKind =
  | ContentSplitVerified   // lab evidence: split(providerContent) order == summary paraIndex set
  | RuleEmittedPreview     // summary/detail items include paraPreview / contentHash from the source
  | Unsupported            // default
```

Rules:

1. Default for unknown sources = `Unsupported` → **P2 forbidden**; only P1 chapter bucket.
2. `ContentSplitVerified` requires recorded evidence (fixture or device log) that the chosen split produces indices matching the site’s review API for ≥1 real book/chapter.
3. `ChangeChapterVerify` stitch split (`\n+` + min length) is **not** assumed equal to site `paraIndex`. Do not reuse it as authority without verification.
4. Local display ids come from `TextChapter` / `ChapterProvider` review id space (`paragraphNum - titleOffset`). Mapping is **text similarity into that space**, never “assume localIndex == providerParaIndex”.

#### 6.5.2 When authority is open

Inputs:

- `localParas`: body texts from current `TextChapter.getParagraphs(pageSplit=false)` in review-id order.
- `providerParas`: texts from the **authority split** of provider chapter content (or rule-emitted previews keyed by `paraIndex`).

Algorithm:

1. `sim[i][j] = digramJaccard(providerParas[i], localParas[j])`.
2. Greedy 1:1 hard-map: take max cell ≥ `PARA_MAP_MIN` (**0.55**), remove row+column; never overwrite an occupied local.
3. **No soft-map icons.** Paras that fail hard-map:
   - If provider supports chapter bucket and entry has reviews → accumulate into `-1` display count (optional).
   - Else drop from paragraph icons.
4. Store  
   `Map<localParaId, ProviderParaRef>`  
   and reverse lookup for coverage stats.
5. Caps: max paragraphs considered per side **`PARA_MAP_MAX = 400`**; map build timeout / cancel on chapter leave; provider content fetch cached for current ±1 chapter only.

#### 6.5.3 Coverage

```
reviewed = provider summary entries with count > 0 and paraIndex != 0
hardMappedReviewed = reviewed whose providerParaIndex has a hard-map entry
coverage = |hardMappedReviewed| / |reviewed|    // soft maps do not exist → not included
```

If `coverage < MAP_COVERAGE_MIN` (**0.5**): hide paragraph icons; optionally show chapter bucket only.

### 6.6 Confidence & degrade (aligned with G7)

| Condition | Behavior |
|-----------|----------|
| Chapter align not in accept set | No review UI for that chapter |
| Authority `Unsupported` | Chapter bucket only (P1); no paragraph icons |
| `coverage < 0.5` | No paragraph icons; chapter bucket optional |
| Provider summary empty | Clear overlay icons |
| Provider fetch error | Log; keep last good chapter cache if fresh; else clear |
| Soft similarity only | **Do not** draw paragraph icons (Rev 0 soft-map removed) |

### 6.7 Candidate discovery (auto) — after §6.0

When `OVERLAY_UNBOUND` and `reviewOverlayAutoBind` (**default off** in Rev 1 — was on in Rev 0; silent wrong-book risk):

1. If `capableCount == 0` → no-op (§6.0).
2. Enumerate enabled capable sources (cap **N=20**, RFC-001 ask-order / success respondTime first).
3. Probe search with small concurrency; **do not** write FAILURE `respondTime` for review-only probes (RFC-002 spirit).
4. Unique same-book hit → **snackbar confirm** before save (not silent bind). Prefer second signal when cheap: chapter align accept on current chapter after TOC fetch.
5. ≥2 hits → picker only.

Manual: book info / read menu → **段评源** → pick capable source → confirm search hit / clear.

### 6.8 Persistence & 换源 survival

Table `book_review_bindings`:

| Column | Notes |
|--------|-------|
| id | PK |
| contentBookUrl | current shelf bookUrl (unique while valid) |
| contentName | denormalized |
| contentAuthor | denormalized (raw) |
| contentOrigin | denormalized content source url |
| providerSourceUrl | |
| providerBookUrl | |
| providerName / providerAuthor | |
| bindMode | `auto` / `manual` |
| updatedAt | |

**换源 migration (mandatory):**

1. On successful content 换源 that replaces `bookUrl`: look up binding by **old `contentBookUrl` first** (exact). Rewrite `contentBookUrl` / `contentOrigin` (and denormalized name/author if changed); keep provider fields.
2. Name/author fallback `(contentName, effectiveAuthor(contentAuthor))` is allowed **only if** that query returns **exactly one** binding row **and** the old `contentBookUrl` row was already deleted/missing. If 0 or ≥2 rows → **do not migrate**; leave unbound / require re-bind. Never attach book A’s binding to book B under ambiguous same-name shelves.
3. Prefer passing `previousBookUrl` explicitly from 换源 call sites so step 1 always hits; fallback is last resort.
4. If name/author identity changes such that RFC-003 would no longer `sameBook` with provider book → invalidate binding (clear or mark stale).
5. G8 fails if binding is keyed only on `contentBookUrl` without migration — forbidden.

Memory caches: provider TOC, align results, paragraph maps, summaries keyed by `(providerBookUrl, providerChapterIndex, ruleHash)`.

### 6.9 Click / detail path

`onReviewClick(localPara, count, contentChapterIndex)`:

1. Resolve overlay session context (required for OVERLAY mode — not optional):
   - `providerSourceUrl` / provider `BookSource` key
   - `providerBookUrl` (+ in-memory provider `Book` / ruleData)
   - `providerChapterIndex` + provider `BookChapter` from **accepted** align result for this content chapter
2. Resolve `ProviderParaRef` from map (or chapter-bucket ref `{ providerParaIndex: -1, paraData }`).
3. Open dialog with **split fields**:
   - API: provider source + provider book + provider chapter + `providerParaIndex` + `paraData`
   - UI only: optional `localPara` / `displayLabel`
4. **Forbidden:**
   - passing local review id as API `paraIndex`
   - using content `ReadBook.book` / content chapter as the analyze/JS ruleData target in OVERLAY mode
   - opening detail when align for this chapter is missing/stale (re-align or abort)

Native fast path may keep today’s single `paragraphNum` meaning both display and API.

#### 6.9.1 P1 chapter-bucket click

- Show a tappable chapter entry **only if** provider summary contains `paraIndex == -1` with `count > 0`, **or** an explicit provider “章评” API the source already documents.
- If summary has only `>0` paragraph indices and authority is `Unsupported`: **no fake `-1`**, no “aggregate all paragraphs into one detail call”. UI may show read-only “已绑定段评源《…》” without a fake open action, or open the 段评源 panel.
- Aggregated count display without click is allowed; click requires a real provider para index.

### 6.10 Prefs (v1)

| Pref | Default | Meaning |
|------|---------|---------|
| `reviewOverlayEnabled` | true | Master switch; false → NATIVE_ONLY |
| `reviewOverlayAutoBind` | **false** | Auto discovery + confirm snackbar |
| `reviewOverlayAllowParagraphIcons` | true | Master for P2 when authority open |

---

## 7. UX sketch

1. **Reading page:** paragraph icons only when P2 gate + coverage ok; else optional chapter chip when §6.9.1 allows.
2. **Book info / read menu:** “段评源：未绑定 / 《源名》” → choose / clear / re-search.
3. **Auto-bind:** confirm snackbar; never silent.
4. **Debug:** AppLog `ReviewOverlay bind=… alignScore=… authority=… coverage=k/n`.

Settings: 段评可来自与正文不同的评论源；无评论源时功能保持不可见。

---

## 8. Implementation phases

### P0 — Design lock + supply

- Accept Rev 1 (or later).
- §6.0: at least one verified review-capable source sample **or** explicit “manual-only until supply exists”.

### P1 — Binding + chapter bucket only（用户可见里程碑）

- DAO + 换源 migration + UI bind/clear.
- Chapter align accept set.
- Chapter-bucket click per §6.9.1 only.
- **No paragraph icons.**

### P2 — Paragraph remap（门禁）

- Record `ProviderParagraphAuthority` for ≥1 real source.
- Implement §6.5 hard-map + `ProviderParaRef` dialog fields.
- Unit tests for map + coverage formula.

### P3 — Auto discovery

- Only if `capableCount > 0`.
- Confirm snackbar; no FAILURE respondTime writes for probes.

### P4 — Polish

- Cache eviction, Web parity, multi-provider (still §10).

---

## 9. Files likely touched

| Area | Paths |
|------|--------|
| Overlay core | `app/.../model/review/ReviewOverlay*.kt` (new; package visibility vs `internal` parsers) |
| Align | `ChangeChapterVerify.kt` (call only; optional exported split helper **after** authority proof) |
| Identity | `BookAuthorIdentity.kt` (call only) |
| Read path | `ReadBookActivity.kt` |
| Dialog | `ReviewDetailDialog.kt` — split provider vs display para fields |
| Persist + migration | Room entity; 换源 call sites that rewrite `bookUrl` |
| Prefs | `AppConfig` / read config |
| Tests | `ReviewParagraphMapTest`, binding migration tests |
| Supply | example source under `docs/` or verified import path |

---

## 10. Follow-ups (out of v1)

1. Multi-provider merge + dedupe.
2. Manual chapter link when align fails.
3. Backup ZIP export of bindings.
4. Provider health demote after N empty summaries.
5. Optional `paraPreview` / `contentHash` in JS/`ruleReview` to strengthen authority.
6. Web `ReviewDialog.vue` overlay query params.

---

## 11. Acceptance tests

| # | Case | Expect |
|---|------|--------|
| A1 | Bound; align accepted; authority open; coverage ≥ 0.5 | Paragraph icons; click uses `ProviderParaRef` |
| A2 | Clear binding | Overlay icons gone (unless native) |
| A3 | Align score ∉ `{1.0,0.95,0.7}` | No chapter-claimed review UI |
| A4 | Authority unsupported | Zero paragraph icons; no hard-map; chapter bucket only if `-1` exists |
| A5 | Two sameBook hits | No auto-bind |
| A6 | Origin capable; no binding | Native unchanged |
| A7 | Force other provider while origin capable | Overlay provider rules used |
| A8 | Empty summary | No icons; no crash |
| A9 | Unit map with known overlap | Hard-map bijection above 0.55; occupied local not overwritten |
| A10 | `reviewOverlayEnabled=false` | Binding ignored; native only |
| A11 | 换源 changes `bookUrl` | Binding migrates; provider unchanged |
| A12 | `capableCount==0` | Auto-discovery no-ops; no scan storm |
| A13 | P1: summary lacks `-1` | No fabricated chapter detail click |
| A14 | Overlay detail open | Dialog/ruleData use provider book+chapter, never content book |
| A15 | Name/author migrate fallback with ≥2 binding rows | No migration; stay unbound |

---

## 12. Risks / open points

| Risk | Mitigation |
|------|------------|
| Extra provider content fetch | Cache; ±1 prefetch; P1 may skip body fetch when only `-1` summary needed |
| paraIndex semantics differ | Opaque provider indices; text map only under authority |
| Login-walled APIs | Soft fail + provider login entry |
| Wrong-book auto-bind | Default auto-bind **off** + confirm |
| Empty supply | §6.0 gate |
| Open: first verified sample source choice | Owner picks (起点类 / 现有 JS 源); track under `#682` |

---

## 13. Review checklist (for other AIs)

- [ ] C1–C6 from Rev 0 review addressed in normative text
- [ ] Soft-map icons removed; G7 consistent
- [ ] P1 click forbids fake `-1`
- [ ] Binding survives 换源
- [ ] Supply gate blocks empty auto-discovery
- [ ] Dialog uses `ProviderParaRef`; local id not sent to APIs
- [ ] Discrete align accept set matches `alignResult` scores
- [ ] `sameBook` API shape matches `BookAuthorIdentity`
- [ ] Acceptance tests falsifiable (A4/A11–A13)

---

## 14. Decision log

| Date | Decision |
|------|----------|
| 2026-08-09 | Rev 0: single primary Review Provider; reuse identity + align + digram |
| 2026-08-09 | Product premise: 段评 must not require every content source to ship review rules |
| 2026-08-09 | Rev 1: kill soft-map icons; P1 chapter-only milestone; authority gate for P2; binding migration on 换源; supply gate; auto-bind default off + confirm; `ProviderParaRef` click invariant; discrete align accept set `{1.0,0.95,0.7}` |
| 2026-08-09 | Rev 1.1: overlay detail must carry full provider book/chapter context; name/author bind migrate only when unique |
| 2026-08-09 | P1a landed: binding table + manual UI + URL-only migrate (name/author fallback deferred — steal risk); providerName = provider book title |

---

## 15. Revision history

| Rev | Date | Notes |
|-----|------|-------|
| 0 | 2026-08-09 | Initial proposal |
| 1 | 2026-08-09 | Address design-review Critical C1–C6 + Warnings W1–W4/W6 |
| 1.1 | 2026-08-09 | Patch Rev 1 re-review P0s: §6.9 provider context; §6.8 unique migrate fallback |
| 1.1-P1a | 2026-08-09 | Implementation: Room bindings, capability, bind UI; migrate URL-only |
