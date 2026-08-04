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
| A Unit | PASS (2026-08-04) | `./gradlew …ChangeChapterVerifyTest :app:installAppDebug` → `BUILD SUCCESSFUL` |
| B Install | PASS (2026-08-04) | `Installed on 1 device` SM-A366U1; package `com.legado.app.debug` |
| C UI | PASS (2026-08-04, 未分组) | Book **学霸也开挂** (未分组). Long-press 换源 → **单章换源**. Progress `校验章节 …` then `章节校验完成`. Badges seen: `已对齐章节`, `字数：2565`, `字数：37`, `无此章`, earlier `获取字数失败：内容为空`. Log: `ChangeChapterSourceDialog` + `换源类型过滤`. |
| C2 整书换源 | PASS schedule (2026-08-04); quality gate **code-only** | Book **吞噬星空：收徒万倍返还** (新乙, 养肥2). Short-tap 换源. Progress `1/1113` → `143/1113` complete. Type filter: 1252 enabled → **1113** text. Ask-order: first non-reserve source **Xpicvid** (rt=35) matches `AskSourceOrder` rest sort. Early results ~18 by ~272 done; stall ~40s on dead hosts (60s `CHANGE_SOURCE_MS`). WebView/site UI flash (**仙域书库**) mid-search. **Pre-fix list:** many OK ~3800 on ch410, but same-title shells (PO18/海棠/肉文*) also ~4400 — length-only rank promoted them. **Post-fix:** `loadBookWordCount` now runs `ChangeChapterVerify.evaluateContent` (+ one-shot origin fetch if no disk cache); device re-verify of demotion badges not re-run this session. |
| D Logs | PASS (partial) | `AppLog 换源类型过滤…`; LiveEventBus on `ChangeChapterSourceDialog` / `sourceChanged`. |

### Notes

- Short tap 换源 = 整书换源; **long-press** 换源 = menu with 单章换源 / 整书换源.
- First attempt on 「在追」 book hit auto-换源 / lock; switched to **未分组** as requested.
- RFC-001 ask-order works, but almost all sources are SUCCESS-class (`respondTime < 180000`); only ~3 FAILURE rows → rank band barely helps; rest is ascending respondTime. RFC forbids writing failure on empty/timeout ask probes — dead hosts stay SUCCESS until a proper check encodes failure.
- Follow-up in tree: 整书换源 word-count path runs `ChangeChapterVerify.evaluateContent` (align local chapter by title; one-shot fetch from current origin if disk cache empty; session-cached context). Device re-test of 「疑似错书」badges on PO18 shells still pending.
- Book-quality pack (2026-08-04): multi-source content consensus + latest-title outliers + TOC size band + quality-first sort + session soft-fail (timeout/hijack, no respondTime write) + early-stop (`changeSourceEarlyStop`, default 20 OK). Menu: 「足够好源后提前停止」.

## Out of scope

- MCP `debug_source` / `start_check_sources` (书源校验, not 换源 UI).
- Full 1200-source ask-order perf (RFC-001 separate).
