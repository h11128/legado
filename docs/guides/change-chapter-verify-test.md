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
| A Unit | PASS (2026-08-04) | `./gradlew …ChangeChapterVerifyTest :app:installAppDebug` → `BUILD SUCCESSFUL`; `:app:testAppDebugUnitTest` green |
| B Install | PASS (2026-08-04) | `Installed on 1 device` SM-A366U1; package `com.legado.app.debug` |
| C UI | BLOCKED (2026-08-04) | Device lock screen (数字密码). App process reached `MainActivity` behind keyguard; UI dump cannot interact until unlocked. |
| D Logs | SKIPPED | Waiting on unlock for interactive C |

### Unblock C/D

Unlock the phone (PIN), then re-run from step C, or:

```bash
adb shell am start -n com.legado.app.debug/io.legado.app.ui.main.MainActivity
```

## Out of scope

- MCP `debug_source` / `start_check_sources` (书源校验, not 换源 UI).
- Full 1200-source ask-order perf (RFC-001 separate).
