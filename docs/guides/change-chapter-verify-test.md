# Change-source device verify

Process SOT: `docs/guides/change-chapter-verify-test.md`  
Skill (repo): `skills/legado-change-source-test/SKILL.md`  
Script: `scripts/change-source-smoke.sh`  
Package: `com.legado.app.debug`

Covers **整书换源** / **单章换源** quality + ask-order + early-stop + MCP check writeback.

## Preconditions (UI)

- Bookshelf has a **网络书** with ≥2 working alternate sources for the same title.
- Prefer mid-chapter with **cached body** (local ref helps consensus / hijack demotion).
- Avoid books stuck in auto-换源 lock if the dialog never opens.

## Fast path

```bash
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/e/.gradle}"   # or /mnt/e/.gradle on WSL
./scripts/change-source-smoke.sh
./scripts/change-source-smoke.sh --assert-prefs
# expect changeSourceLoadWordCount=true and changeSourceEarlyStop not false
```

| Layer | Prove | How |
|---|---|---|
| A Unit | checkalgo + Rfc001 contracts | `--unit-only` |
| B Install | code on phone | script → debug package |
| C UI | badges + sort | 短按整书；长按→单章 |
| D Prefs | quality path armed | `--assert-prefs` |
| E MCP | check status writeback | 1 URL `start_check_sources` → `get_check_progress` |

## UI pass criteria

- Progress: `结果 N, 当前进度 a / b`
- Bad rows: `疑似错书/广告劫持` / `正文过短` / `无此章` / 最新章或目录不一致
- Good rows: `字数：N`
- Without **加载字数**, length-only rank may promote shells — not a full quality PASS
- Stall `1/b: <name>` **>70s** → fail timeout/cancel

Gestures: **短按**换源 = 整书；**长按** = 选单章/整书。

## Log / dump

```bash
adb logcat -d | rg -i '换源|ChangeChapter|hijack|consensus|early' | tail -80
adb shell uiautomator dump /sdcard/uidump.xml
adb pull /sdcard/uidump.xml /tmp/uidump.xml
rg -o '疑似[^"]+|字数：[0-9]+|结果 [^"]+|停止|刷新' /tmp/uidump.xml | head
```

## Agent run record

| Date | Result | Evidence |
|---|---|---|
| 2026-08-05 | PASS (partial: earlyStop=false) | unit+install `3.26080511debug`; badges OK with loadWordCount; Xpicvid stall >80s at `1/1113`; MCP `m.bqg.fun` fail → `respondTime=180289` |
| 2026-08-04 | PASS schedule | first device pass; quality demotion later confirmed 08-05 |

## Improvements from device evidence (priority)

1. **Hard-cancel probe** — `withTimeout(CHANGE_SOURCE_MS)` often fails to stop WebView/Cronet; ask-order head can block >60s.
2. **Ask-order head pollution** — tiny SUCCESS `respondTime` (e.g. Xpicvid) leads 换源 while dead for this title; need session soft-fail / recent-miss demotion.
3. **Default `changeSourceLoadWordCount=true`** — badges/sort depend on it; default false hides quality UX (early-stop already defaults true).
4. **Surface early-stop** — progress hint when pref off / when early-stop fired.
5. **RespondTime band weak at scale** — most sources stay SUCCESS; ask-order ≈ ascending rt until check/soft-fail demotes.
6. **Observable logs** — AppLog thin in `adb logcat`; tag key 换源 events for agents.
7. **Pref write for agents** — `run-as` read OK, write often denied; keep menu / add debug intent.
