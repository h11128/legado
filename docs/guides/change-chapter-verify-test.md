# Change-source device verify

Skill: `skills/legado-change-source-test/SKILL.md`  
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
./scripts/change-source-smoke.sh --apply-prefs    # broadcast SET_CHANGE_SOURCE_PREFS
./scripts/change-source-smoke.sh --assert-prefs
# or MCP: set_change_source_prefs / get_change_source_prefs (after app/MCP warm)
# or deep link: legado://import/changeSourcePrefs?loadWordCount=true&earlyStop=true
adb logcat -s LegadoChangeSource
```

| Layer | Prove | How |
|---|---|---|
| A Unit | checkalgo + Rfc001 + demotion | `--unit-only` |
| B Install | code on phone | script → debug package |
| C UI | badges + sort + early-stop subtitle | 短按整书；长按→单章 |
| D Prefs | quality path armed | `--apply-prefs` / MCP |
| E MCP | check status writeback | 1 URL `start_check_sources` |

## UI pass criteria

- Progress shows **探测中 &lt;源&gt;** while probing (not only last completed name)
- Early-stop: subtitle `已足够好源（N）· 已停止 …`
- Bad rows: `疑似错书/广告劫持` / `正文过短` / …
- Good rows: `字数：N`
- After a timeout/empty miss, that URL is demoted for later asks in-process (no DB failure write)
- Stall `探测中 X` **>70s** → fail hard-cancel

Gestures: **短按**换源 = 整书；**长按** = 选单章/整书。

## Agent run record

| Date | Result | Evidence |
|---|---|---|
| 2026-08-05 | PASS (partial: earlyStop=false) | pre-fix device run; motivated the 7 fixes below |
| 2026-08-05b | code fix | hard-cancel Cronet + ask memory demotion + loadWordCount default + early-stop UI + LegadoChangeSource log + prefs deep link/MCP |

## Fixes landed (was improvement backlog)

1. Cronet **production** path (`CronetInterceptor`/`NewCallBack`): always timed (≤90s) + cancel UrlRequest; timeout/cancel **no** OkHttp fallback. Probe uses `withTimeoutOrNull`; progress shows in-flight names
2. `ChangeSourceAskMemory` demotes **timeout/error/content-bad** for later asks; **empty** is session-only (does not poison global search). No failure RT write (RFC-safe)
3. `changeSourceLoadWordCount` default **true**
4. Early-stop subtitle strings + `ChangeSourceProgressUi`
5. Ask-order uses `RespondTimeRank.classify` + demote tail
6. `ChangeSourceLog` → `adb logcat -s LegadoChangeSource`
7. Broadcast `io.legado.app.action.SET_CHANGE_SOURCE_PREFS` + deep link + MCP `set_change_source_prefs` + script `--apply-prefs`
