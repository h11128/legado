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

## Session numbers (finish)

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
