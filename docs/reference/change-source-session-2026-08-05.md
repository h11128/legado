# Change-source session investigation — 2026-08-05

## Artifacts

- Filtered logcat: `temp/legado_change_source_session_2026-08-05.txt` (~1060 lines)
- Wider dump (incl. AppLog mirrors): `temp/legado_change_source_session_wide.txt`
- Device tag: `LegadoChangeSource` (+ AppLog prefix `换源 `)
- Finish line (complete):

```text
finish cause=ok early=false completed=1113/1113 list=141 qualityOk=1
hits=150 published=141 missEmpty=648 missTimeout=224 missError=106 missContentBad=140
```

**Gap:** `start book=…` was **lost** from the ring buffer. High-volume `ChangeSourceLog` → `AppLog.put` (DEBUG also `Log.e`) tripled log pressure and pushed the session head out.

## User reports vs evidence

### A. 「并行没有真正并行，已完成一个一个更新」

| Claim | Verdict | Evidence |
|---|---|---|
| Network ask is not parallel | **False** | Mid-session `inFlight=99..100` sustained; many concurrent `phase toc` / `phase word` on different threads |
| 「已完成」feels serial | **True (UI semantics)** | `completedProbeCount` increments only in `mapParallel` `finally` **after** search **and** deep (`loadWordCount` toc+content) |

So the pool is busy in parallel, but the counter the UI labels as progress is **full-pipeline completions**, which naturally tick **1 by 1** as each multi-second deep probe ends.

### B. 「一开始并行 100，已完成卡在 1 很久」

| Claim | Verdict | Evidence |
|---|---|---|
| Stuck early at low `done` | **Very likely / design bug** | Ask-order puts historically fast SUCCESS sources first → they **hit** → enter deep and **hold a `mapParallel(100)` slot** for ~10–50s |
| Empty sources cannot drain | **Follows** | Empties later in the ask queue cannot start until a deep slot frees |

Measured on the captured tail (buffer starts ~`done=617`):

- `hit → list+` lag: **avg 15.3s**, worst **51s**
- Max overlapping `phase word` in the buffer window: **~16** (slots held earlier in search/toc too)
- `qualityOk` stayed **1** for the whole captured window through finish

Root mechanism (code):

```text
mapParallel(threadCount) {
  search()           // may HIT
  → loadBookInfo     // holds same concurrency slot
  → loadBookToc
  → loadBookWordCount
  finally completed++   // only then UI「已完成」moves
}
```

## Problem list (all)

1. **P0 — Ask slot held through deep probe**  
   `mapParallel` concurrency covers search+info+toc+word. Hits monopolize the 100 slots; empties starve; 「已完成」stalls.

2. **P0 — Progress metric = deep-complete, not ask-complete**  
   UI `已完成 a/b` tracks full pipeline. Users read it as “parallel search progress,” which mismatches reality.

3. **P0 — Results only appear after word-count**  
   With `loadWordCount=true`, no `list+` until content probe finishes → sparse early list despite many hits (`hit` then ~15s later `list+`).

4. **P1 — Sort buries pending / unknown**  
   Until early-publish exists, less visible; `TIER_PENDING` was prepared but early-publish not wired. Content-bad still floods the list (`words=-1`).

5. **P1 — qualityOk=1 / early-stop never useful this run**  
   Finish: `qualityOk=1`, `missContentBad=140`. Almost every content probe failed the quality gate; only one OK body (`小说`, 3675 chars, respondMs=32810). Early-stop cannot fire.

6. **P1 — Log / AppLog flood lost session head**  
   Every miss/hit/phase mirrored to AppLog; DEBUG `Log.e` duplicates. `start` missing from buffer → harder forensics.

7. **P2 — Progress subtitle can look “stuck” on same names**  
   Long deep probes keep the same `探测中 A、B、C 等96个` while `done` barely moves — reinforces “not parallel.”

8. **P2 — Content-bad still published into list**  
   Failed word probes still `list+` with `words=-1` (141 published vs 150 hits), so the list fills with low-tier rows and one OK at top after sort.

9. **P2 — AppLog ring only keeps 100 entries**  
   In-app log cannot hold a full 换源 session; logcat is required.

## What is *not* broken

- `threadCount` / `inFlight≈100` wiring: parallel ask **is** attempted.
- Cronet cancel/timeout path: many `miss timeout` / `empty` complete; session reached `1113/1113`.
- Prefetch chunking (`AskSourcePrefetch` 150) is not the stall cause.

## Fix direction (ordered)

1. **Split ask vs deep concurrency** — `mapParallel` only for search; deep on a separate limited pool (≤16) so empties keep draining. **Done in follow-up commit.**
2. **Early `list+` on hit** (`chapterWordCount=0` / pending tier); upgrade on word result (upsert by origin). **Done.**
3. **Progress** — 「已询问」= ask finished; label shows `深探 n/16`. **Done.**
4. **ChangeSourceLog** — logcat always; AppLog only for milestones. **Done.**
5. Follow-up: investigate why content quality almost always fails for this book (wrong chapter index / gate too strict / hijack true).

## Content-bad deep dive (same session DB)

Pulled `legado.db` via `adb exec-out` → `temp/ld3.db` (134 `searchBooks` rows for
《吞噬星空：收徒万倍返还》 / 新乙). Local bookshelf: `durChapterIndex=411`
(第410章…), `totalChapterNum=877`, origin 饿狼小说.

### Failure mix (from `chapterWordCountText`)

| Reason | Count | Notes |
|---|---|---|
| **疑似错书/广告劫持** (`ContentQuality.Hijack`) | **93** | Dominant |
| **正文过短** | 27 | Shell / VIP / relative floor |
| 获取字数失败 | 3 | empty / JS errors |
| Other empty text | 11 | |
| **OK (`chapterWordCount≥1`)** | **0** in DB now | Finish log had 1× `小说(w=3675)` earlier; not in current table |

Probed TOC index peaks at **412** (67×), then 410/411 — around reading position.
118 rows also have **最新章疑似不一致** badge (meta), 14 have TOC-size badge.

### What Hijack means in code

`evaluateContent` order:

1. absolute TooShort (`<120`)
2. relative TooShort vs cached local reference length (`<22%` when expected≥400)
3. **`looksLikeStitchedParagraphs` → Hijack**
4. **digram Jaccard vs local reference `<0.04` → Hijack**
5. anti-theft shell markers → AntiTheft
6. else Ok

Session log only stored `chars=-1` — **did not record which branch**. Samples show both:

- **True wrong-book hits** (search name match, body/latest unrelated), e.g. 讲课通知 / 钓鱼刀斩不朽 while reading 第410章秘法.
- **Same-looking chapter titles still Hijack** (e.g. `第410章 四十万年，宇宙霸主巅峰秘法`) → likely **stitch false-positive** and/or **bad/locked reference**, not “empty body”.

### Amplifiers (bugs / design)

1. **`wordCountEvalContext` cached once per search**  
   First deep probe’s aligned local chapter becomes the **only** reference for everyone. A wrong first hit can poison similarity for the whole run.

2. **`content-bad` → `processDemote=true`**  
   Ask-memory demotes the source globally after one bad chapter probe (alignment/VIP/stitch), same bucket as timeout — too harsh.

3. **`qualityOk` needs `chapterWordCount≥1000`**  
   Even legitimate `Ok` bodies of 120–999 chars never trip early-stop (`QUALITY_OK_MIN_CHARS`).

4. **Search accepts same title from wrong novels**  
   Exact name filter still returns fanfic / wrong shelves; content gate then correctly Hijacks many — but they still enter the list as `words=-1`.

### Not the parallel bug

`inFlight≈100` / ask-slot holding is separate (already fixed). Content-bad volume is **quality-gate + reference/stitch + wrong-book search**, proven by DB text labels.

## Session numbers (finish log)

| Metric | Value |
|---|---|
| Sources | 1113 |
| Hits | 150 |
| List published | 141 |
| qualityOk | 1 |
| empty | 648 |
| timeout | 224 |
| error | 106 |
| content-bad | 140 |

