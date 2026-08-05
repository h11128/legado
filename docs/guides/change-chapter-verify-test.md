# Change-chapter verify test procedure

Manual + automated checks for 单章换源正文质量 / 多源共识
(`ChangeChapterVerify` + `ChangeChapterSourceViewModel`).

## Scope

| Layer | What it proves | Command / action |
|---|---|---|
| A. Unit | Consensus / stitch / length gates | `./gradlew :app:testAppDebugUnitTest --tests io.legado.app.model.checkalgo.ChangeChapterVerifyTest` |
| B. Install | Current code on device | `./gradlew :app:installAppDebug` |
| C. Device UI | End-to-end chapter verify labels | Steps below on phone |
| D. Log smoke | Consensus demotion logged / UI strings | `adb logcat` filters below |

Requires: `GRADLE_USER_HOME=E:\.gradle` (or unset so `./gradlew` picks it), USB device, package `com.legado.app`.

## A — Unit (agent MUST run)

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"
./gradlew :app:testAppDebugUnitTest --tests 'io.legado.app.model.checkalgo.ChangeChapterVerifyTest'
```

Pass = `BUILD SUCCESSFUL`, 0 failed tests.

## B — Install debug

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"
./gradlew :app:installAppDebug
adb shell pm path com.legado.app
```

## C — Device UI checklist

Preconditions: bookshelf has a **网络书** with ≥2 working alternate sources for the same title; you are mid-chapter with cached body (local reference helps consensus).

1. Open the book → reading → menu → **换源** / chapter change-source (单章换源).
2. Do **not** force full search if DB already has hits; wait for **章节校验** progress.
3. Observe list badges:
   - `本章可读 · N 字` / Chapter OK
   - `已对齐章节`
   - `无此章` / `正文过短` / `疑似防盗/空壳` / `疑似错书/广告劫持`
4. **Consensus case:** if several sources share near-identical good text and one is a clear wrong-book body, the wrong one should show hijack/fail — not stay OK above the cluster.
5. **Majority-spam case (no local chapter):** many identical anti-theft bodies + one coherent different body must **not** demote the coherent minority solely for being alone (unit covers this; UI: minority should not flip to hijack without local ref / stitch).
6. Tap a source marked OK → chapter content loads and matches expectation.
7. Re-open 换源 within ~1 day → probe cache should skip re-fetch for same `chapterKey` where status was OK.

## D — Log filters

```bash
adb logcat -c
# reproduce C
adb logcat -d | rg -i '换源|ChangeChapter|chapter verify|multiSource|hijack|consensus' | tail -80
```

## Agent run record

Fill after each run:

| Step | Result | Evidence |
|---|---|---|
| A Unit | PASS (2026-08-04; reconfirmed 2026-08-05) | `./gradlew …checkalgo.*` (+ Rfc001 / CheckHttpLimits) → `BUILD SUCCESSFUL`, 0 failures |
| B Install | PASS (2026-08-05) | `installAppDebug` on SM-A366U1 `RZCYA19Z3DX`; `com.legado.app.debug` `versionName=3.26080511debug` after `merge upstream/master` (`f531b2538`) |
| C UI | PASS (2026-08-05) | Book **吞噬星空：收徒万倍返还**. Long-press 换源 → **单章换源**. Badges: `正文过短`, `疑似错书/广告劫持`, `目录规模疑似不一致`, `最新章疑似不一致`, OK row `字数：3675` (源「小说」). Progress seen `结果 9, 当前进度 265 / 1113`. |
| C2 整书换源 | PASS (2026-08-05) + quality badges | Short-tap 换源. Mid-run list mixed PO18/海棠 ~4400 字; after `changeSourceLoadWordCount=true` evaluation: demotion badges on 顶点/海棠/笔趣阁* (`疑似错书/广告劫持` + 最新章/目录不一致). Progress `结果 12, 当前进度 260 / 1113`. Ask-order restart first probe **Xpicvid** stalled **>80s** at `1/1113` (past 60s `CHANGE_SOURCE_MS` — likely WebView/hang path). Device pref `changeSourceEarlyStop=false` during run (default code=true); flipped to true in prefs for next cold start. Menu shows 「足够好源后提前停止」. |
| D Logs | PASS (partial) | Cronet timeouts; UI strings for quality badges. AppLog tag filter thin. |
| E MCP check smoke | PASS path (2026-08-05) | `start_check_sources` 1 URL `https://m.bqg.fun` → finished; `success=false`, group `搜索失效`, `respondTime=180289` (FAILURE-class writeback). |

### Notes

- Short tap 换源 = 整书换源; **long-press** 换源 = menu with 单章换源 / 整书换源.
- First attempt on 「在追」 book hit auto-换源 / lock; switched to **未分组** as requested.
- RFC-001 ask-order works, but almost all sources are SUCCESS-class (`respondTime < 180000`); only ~3 FAILURE rows → rank band barely helps; rest is ascending respondTime. RFC forbids writing failure on empty/timeout ask probes — dead hosts stay SUCCESS until a proper check encodes failure.
- Follow-up in tree: 整书换源 word-count path runs `ChangeChapterVerify.evaluateContent` (align local chapter by title; one-shot fetch from current origin if disk cache empty; session-cached context). **2026-08-05 device:** 「疑似错书/广告劫持」等徽章已在整书/单章换源列表复现（需 `changeSourceLoadWordCount=true`）。
- Book-quality pack (2026-08-04): multi-source content consensus + latest-title outliers + TOC size band + quality-first sort + session soft-fail (timeout/hijack, no respondTime write) + early-stop (`changeSourceEarlyStop`, default 20 OK). Menu: 「足够好源后提前停止」. Device may have pref=false — check menu before claiming early-stop worked.
- Sort refined: **content tier → length band → likes → probe respondTime → soft latest/TOC hint**. Latest/TOC stay badges + soft penalty only; they do not veto content-OK.
- 2026-08-05: ask-order first hit **Xpicvid** can stall past `CHANGE_SOURCE_MS` on restart; treat as known timeout/WebView hang watch item.

## Out of scope

- Full 1200-source ask-order perf (RFC-001 separate).
- Exhaustive early-stop wall-clock on device this session (pref was off until end).
