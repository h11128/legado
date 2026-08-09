# RFC-003: Empty / 佚名 author smart-merge (search + shelf)

| Field | Value |
|-------|--------|
| Status | **Accepted (Rev 4)** — local×web no-delete/no-colliding-fill; `|S|≥2` weak policy unified; rebuild triggers (per-source + end + pagination); `hasProgress` + Rev2 progress superseded; highlights/bookmark retarget mandatory |
| Date | 2026-08-08 |
| Repo | `legado` (Android app) |
| Related code | `SearchBookMerge.kt`, `SearchModel.mergeItems`, `SearchBookShelfHelp`, `Book.isSameNameAuthor`, `Book` unique `(name, author)` |
| Related evidence | Device shelf: same title with `佚名`/empty + one real author → duplicate rows |
| Design review | [RFC review](8a71b0b5-2ae4-4df8-871c-522b70508bf1) → Needs changes → this Rev 4 |

**Audience:** implementers (gate passed).

---

## 0. 人话

书架和搜书认书靠「书名 + 作者」。空 / 佚名会被当成另一本 → 重复。

**要：**

1. 空 / 佚名不当独立作者。  
2. 同书名真实作者只有一种 → 合并并补作者。  
3. 搜书 + 书架都合并。  
4. 真实作者 ≥2 种 → 不猜。

**不要：** 靠改站点 HTML 硬抹佚名；乱删本地书；在已有 `(书名,作者)` 上硬改 author 撞库（见 §4.9）。

---

## 1. Problem

Identity ≈ `(name, author)`. Placeholder authors create false duplicates. DB: unique index on `(name, author)`; PK `bookUrl`; `chapters` CASCADE on book delete.

---

## 2. Goals

| # | Goal |
|---|------|
| G1 | Placeholder / empty → effective empty for identity. |
| G2 | Search merge when exactly one distinct real author among same-name peers. |
| G3 | Shelf same rules: add / match / same-name cleanup. |
| G4 | ≥2 real authors → do not merge weak into either. |
| G5 | One shared module for placeholders + same-book logic; `ChangeBookSourceQuality` calls the same `effectiveAuthor` (behavior unchanged except shared placeholder set). |
| G6 | Search correctness via **rebuild from raw per-source hits** (not in-place split of absorbed rows). |

---

## 3. Non-goals

- Inventing authors without a sole real author.
- Auto-retire / delete **local** books (`BookType.local`).
- Local↔web automatic merge-into that deletes either side (see §4.9.1).
- Per-site CSS-only 佚名 strips.
- Author normalization beyond §4.1 (`甲` vs `甲著` out of v1).
- Changing RFC-001 / RFC-002.
- Widening `isSameNameAuthor` to weak-match without peers.
- **Authority / weight preference** when filling author (prefer high-`weight` / “权威” source group): **out of v1**. v1 fill = sole distinct real author among peers (§4.5).
- One-shot **full-library** shelf scan / UI button to clean all 佚名 rows (v1 cleans on add-to-shelf for that title only). **Known debt:** old duplicate rows wait until the user adds that title again.
- User-confirmed manual “merge into…” for local×web pairs that v1 leaves as two rows (follow-up).

---

## 4. Rules (normative)

### 4.1 Effective author

```
effectiveAuthor(raw) =
  trim(raw);
  if empty OR lowercase(ascii) / exact (CJK) in PLACEHOLDER_SET → ""
  else → trimmed raw
```

v1: **do not** run `authorRegex` / `getRealAuthor` before the placeholder check.  
`作者：佚名` is **not** in the set → treated as a real author string in v1 (do not invent stripping unless added to SET later).

PLACEHOLDER_SET:  
`佚名`, `未知`, `无`, `无名氏`, `无名`, `未知作者`, `作者未知`, `作者不详`, `不详`, `暂无`, `暂无作者`, `匿名`, plus ASCII case-insensitive: `none`, `null`, `unknown`, `n/a`, `na`.

Single ownership module; `ChangeBookSourceQuality` reuses it.

### 4.2 Equal name

`equalName(a, b) = trim(a) == trim(b)`  
No further whitespace/fullwidth folding in v1.

### 4.3 Peers

```
peers := ALL hits/rows with equalName(name, T)
```

- Forbidden: sole-author decision with only `{A,B}` when other same-name rows exist.
- **Search peers** = all **raw** hits for this query session with that title, across **every** UI bucket (precision / fuzzy / any column). Never compute `|S|` from the currently visible bucket alone.
- **Shelf peers** = all DB rows for that title, including `notShelf`. Local rows **may** count in `|S|` but see §4.9.1.
- Canonical **selection** is defined only in §4.9 (this section defines the peer set only).

### 4.4 Same-book (`equalName` true)

1. `effectiveAuthor(A) == effectiveAuthor(B)` → same (includes both weak).
2. Both non-empty and unequal → not same.
3. One weak + one real (or one weak deciding against peers):
   - `S` = distinct non-empty effective authors from `peers`
   - `|S|==1` → same; fill = that sole author
   - `|S|==0` → both-weak (rule 1)
   - `|S|≥2` → weak↔real **never** same
4. **`|S|≥2` weak–weak policy (unified):**
   - **Search display:** may fold multiple weak hits into **one** weak display row; that row must **not** attach to any real author.
   - **Shelf v1:** **do not** weak–weak merge-into/retire when `|S|≥2` (keep multiple placeholder rows; avoids deleting progress/cache by accident). When `|S|≤1`, weak–weak collapse via §4.9 is allowed.

### 4.5 Absorb / fill (search display)

- Prefer non-empty effective author on the display row.
- Cover / intro: fill blanks.
- **Fill string:** when `|S|==1`, write/display the sole effective author. Prefer the `author` field already on an existing real-author peer row; else first-seen raw among peers whose `effectiveAuthor` equals the sole.
- **Origins:** v1 **must** retain multiple origins on the merged display row when absorb merges hits.
- **Provenance:** streaming absorb may sticky-merge for UX, but **must retain the raw per-source hit list** for the query. Correctness is defined by rebuild (§4.6).

### 4.6 Search merge + rebuild (required v1)

1. **Raw buffer:** for this query (same search key / session), each source’s returned `SearchBook` list is **appended** to raw (**pre-absorb**). Never write already-absorbed display rows back into raw.
2. **Rebuild triggers (all required):**
   - (a) after every source result enters the merge path;
   - (b) once more when the query completes or is cancelled;
   - (c) again after pagination / append hits for the same query.
3. Peers for §4.4 = full raw same-name set (cross-bucket).
4. The **authoritative** displayed list is **only** the rebuild output. Sticky UI before rebuild is temporary and must not be treated as durable state.
5. **Forbidden:** in-place “split” of already-absorbed rows to undo sticky merge.

### 4.7 Identity API

- New `sameBook` / `isSameBookIdentity(a, b, peers)` / resolve helpers implementing §4.4.
- **Peers contract:** callers must pass the **full** same-name set. If peers are omitted or known-incomplete → **fail-closed**: weak↔real must be false; only equal `effectiveAuthor` may still be true.
- Keep `isSameNameAuthor` as **exact raw `name` + raw `author`** (no `effectiveAuthor`). Used by ReadBook / 换源 unless migrated deliberately later.
- Shelf add / “already on shelf?” use the new API + §4.8.

### 4.8 Shelf resolve order

1. Same `bookUrl`
2. Exact `(equalName, effectiveAuthor)` including both weak
3. Sole-real same-name (`|S|==1`, weak↔real)

Then apply §4.9 if two physical rows must become one.

**Cleanup scan:** only when `|S|≤1` may same-name cleanup collapse extra weaks into the sole/canonical row. When `|S|≥2`, do not auto-delete weaks (§4.4.4 shelf).

### 4.9 Merge-into + retire (never in-place author rewrite onto existing key)

**Forbidden always:**

- `UPDATE books SET author = :new` when `(name, :new)` already exists.
- In-place rewrite between distinct placeholder raws (`佚名` → `""`) when target key exists — use merge-into + retire instead.

**Canonical pick (normative order):**

1. Prefer sole-real author row when present among candidates.
2. Else prefer shelf-visible (`!notShelf`) over `notShelf`.
3. Else higher progress per policy below / resolve order.

Canonical keeps its **`bookUrl`** (PK).

**Progress:**

- `hasProgress(row)` := `durChapterIndex > 0 || durChapterPos > 0`  
  (a mere `durChapterTime` update **without** index/pos does **not** count as hasProgress).
- Only one side `hasProgress` → keep that progress on canonical.
- Both have progress → prefer **higher `durChapterTime`**; tie → real-author row; tie → higher `durChapterIndex` then `durChapterPos`.

**Retire:**

- Copy missing cover/intro onto canonical if needed.
- Retire non-canonical with `DELETE` in a transaction **after** copy (accept CASCADE chapter loss on retired URL). **v1 does not copy chapters**; after retire, cached chapters for the retired URL may be empty — opening the book re-fetches toc/content.
- Before DELETE: retarget any holder of the retired `bookUrl`:
  - `ReadBook` / `AudioPlay` / `ReadManga` (if present)
  - **Highlights** and any other tables keyed by `bookUrl` (impl PR must `rg` schema / `bookUrl` and list them)
- Keys by `(bookName, bookAuthor)` (bookmarks, etc.): when canonical’s author string changes, **must update** matching keys to canonical name/author (v1: not “orphan ok as long as no crash”).

#### 4.9.1 Local × web v1 matrix

Same trimmed title:

| Case | v1 behavior |
|------|-------------|
| Would `DELETE` / `notShelf` a local book | **Forbidden** |
| Would `DELETE` web to “merge into” local (or reverse) as automatic cleanup | **Forbidden** |
| In-place author fill on local that would collide with existing `(name, effectiveAuthor)` row | **Forbidden** |
| Local empty/佚名 + web sole real (or reverse): look like one book | **Keep both rows**; local may count in `|S|`; “already on shelf” may report local exists for that title, but must not crash or delete to dedupe |
| Optional later | User-confirmed manual merge |

### 4.10 Cleanup trigger

On **add-to-shelf** for title `T`, run same-name cleanup for `T` (§4.8–4.9) subject to `|S|` rules above.  
**Ops one-shot:** `SearchBookShelfHelp.cleanupAllAuthorPlaceholders()` / MCP `cleanup_author_placeholders` may scan the whole library with the same per-title rules (not a product UI button).  
No automatic background full-library scan.

### 4.11 Mandatory call sites

Checklist (impl PR must tick; also `rg getBook\\(.*author` / `insertIgnore` / shelf-add paths):

- [ ] `SearchModel`: raw hits; rebuild on §4.6 triggers
- [ ] `SearchBookMerge` + unit tests (rebuild / sticky+second author / pagination append)
- [ ] `SearchBookShelfHelp.addLoadedBooksToShelf` (+ tests with `getBooksByName`)
- [ ] BookInfo “加入书架 / 是否已在书架” via §4.8 (not raw `getBook(name, author)` alone when weak/sole applies)
- [ ] `AudioPlayViewModel` / other `insertIgnore` shelf-entry paths
- [ ] Search “读过” / any `(name, author)` shelf index used for badges
- [ ] Restore / highlight paths that reverse-lookup by name/author
- [ ] Do **not** treat `insertIgnore == -1` as successful merge; resolve via `getBooksByName` / §4.8 first

---

## 5. Acceptance tests

1. Search rebuild: T/`佚名` + T/`七月观天` → one display row, author `七月观天`; **both origins retained**.
2. Search: T/empty + T/`甲` + T/`乙` → empty not merged into 甲 or 乙 after rebuild.
3. Search sticky then rebuild: empty first absorbed into 甲, then 乙 arrives → after rebuild from raw, 甲 and 乙 both remain; weak separate (not stuck only on 甲).
4. Search pagination: after rebuild-correct state, append page adds second real author → rebuild again; weak not attached.
5. Shelf: has T/`七月观天`; add T/`佚名` → no second shelf-visible row from that add.
6. Shelf: has T/`佚名`; add T/`七月观天` sole → one row, author `七月观天`, progress per §4.9.
7. Shelf: has T/`甲` and T/`乙`; add T/`佚名` → no author-merge attach (URL match still OK).
8. Collision: T/`佚名`(progress) + T/`真`(progress) → one visible row; author `真`; progress = §4.9; non-canonical deleted; no unique crash.
9. Placeholders when `|S|≤1`: T/`佚名` + T/`""` → one weak row via merge-into+retire (not UPDATE onto existing key).
10. `|S|≥2` dual placeholders on shelf → left alone (no auto-retire).
11. Local: T local + T web/`七月观天` → local not deleted; no colliding author UPDATE; dual rows allowed (§4.9.1).
12. Local/`""` + web/`七月观天` and local/`七月观天` + web/`佚名` → no crash, local kept.
13. `notShelf` real + shelf weak, `|S|==1` → one visible row; clear `notShelf` when adding to shelf.
14. Highlights/bookmarks retarget when retiring URL / changing author key.
15. ReadBook open on retired URL → retarget before delete.
16. “Already on shelf”: shelf `佚名`, detail/search sole `七月观天` → true via §4.8.
17. Shared module + `isSameNameAuthor` stays raw-exact (regression).
18. Unit tests without device.

---

## 6. Implementation sketch

- `help/book/BookAuthorIdentity.kt` — placeholders, equalName, sameBook, soleReal, pickCanonical, hasProgress helpers.
- Search: raw hit buffer + rebuild on §4.6; `SearchBookMerge` wraps identity.
- Shelf: `getBooksByName`, resolve §4.8, merge-into+retire §4.9, cleanup on add; respect §4.9.1.
- New identity API; leave `isSameNameAuthor` raw-exact.
- Enumerate §4.11 call sites in the implement PR.

---

## 7. Out-of-band

- `temp/search_snapshot_2026-08-08/yiming_author_queue.md` — site repair secondary (not the product fix).

---

## 8. Decision log

| Date | Decision |
|------|----------|
| 2026-08-08 | User: smart-merge empty/佚名; shelf too; record then code. |
| 2026-08-08 | Rev2: full peers; collision merge-into; don’t widen isSameNameAuthor; re-merge; trim; cleanup on add; progress prefer real then recent. **Superseded for progress order by Rev3/Rev4 §4.9.** |
| 2026-08-08 | Rev3 draft: rebuild from raw; never UPDATE onto existing unique key; never auto-retire local; progress `durChapterTime` then real; canonical `!notShelf`; sticky intermediate only. |
| 2026-08-08 | User gate: write optimizations → subagent review → then implement. |
| 2026-08-08 | Design review: Needs changes (local×unique, `|S|≥2` weak policy, rebuild triggers, hasProgress, satellite retarget). |
| 2026-08-08 | **Rev 4 Accepted:** §4.9.1 local matrix; unified `|S|≥2` weak policy; §4.6 rebuild triggers; `hasProgress`; mandatory highlight/bookmark retarget; fail-closed peers; no `authorRegex` before placeholder in v1. |
