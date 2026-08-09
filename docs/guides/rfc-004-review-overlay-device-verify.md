# RFC-004 device verify (P1–P3 overlay)

## Fixture

Import JS source from:

`docs/design/fixtures/rfc-004-review-provider-fixture.js`

Source URL: `legado-fixture://review-overlay`  
Name: `RFC004段评提供方(夹具)`

- Offline chapter-bucket (`paraIndex=-1`, count 2) plus paragraph reviews (`1..N`) matching blank-line split of `getContent`.
- Authority: `ContentSplitVerified` for this fixture URL only (P2 hard-map open).

## P1 — Chapter-bucket

1. Install/debug build with Room v101+.
2. Import the fixture JS source and enable it.
3. Open a shelf book whose **content origin is NOT** the fixture (any normal novel source).
4. Book info → **段评源** → pick `RFC004段评提供方(夹具)` → confirm bind (search echoes book name).
5. Open reading page on a chapter whose title can align (e.g. `第N章…`). Fixture TOC uses `第1章`…`第50章` plus named first three.
6. Expect 章评 bubble with count `2`.
7. Tap bubble → dialog shows two fixture chapter comments.
8. Logcat: `ReviewOverlay bind=… align=… bucket=2` (authority may appear; coverage only on P2 path).
9. 换源 content book → binding should survive (URL migrate); re-open read and step 6–7 still work.

## P2 — Paragraph icons (fixture authority)

Same bind as P1. Content book chapter text should be **similar** to fixture paragraphs (same digram hard-map), or temporarily read the fixture as content with a second book bound oddly — easiest path:

1. Create/import a local/text book whose chapter body matches the four fixture paragraphs (copy from fixture `getContent` for `第1章`).
2. Bind fixture as 段评源.
3. After layout completes, expect paragraph icons (counts 1/3/1/1) **plus** chapter bubble.
4. Tap a paragraph icon → detail uses provider `paraData` (`para:N:…`).
5. Logcat: `authority=ContentSplitVerified coverage=… paras=N`.
6. Non-fixture provider URLs → chapter-bucket only (no paragraph icons).

## P3 — Auto-bind (opt-in)

1. 阅读设置 → enable **自动发现段评源** (`reviewOverlayAutoBind`, default off).
2. Unbound book, fixture enabled, open read.
3. Expect confirm snackbar (never silent bind). Confirm → binding persists; dismiss → stays unbound.
4. `capableCount==0` → no snackbar / no scan spam.

## Prefs (P4)

阅读设置 also exposes:

- **跨源段评** (`reviewOverlayEnabled`, default on)
- **显示段评图标** (`reviewOverlayAllowParagraphIcons`, default on; still needs fixture/ContentSplitVerified authority)

## Fail expectations

- No capable sources → toast「没有已启用的段评能力书源」(manual pick)
- Align fail (weird title) → no bubble, no crash
- Unbound → no overlay icons
- Coverage &lt; 0.5 on P2 → chapter-bucket only (no wrong paragraph icons)

## Real providers (not fixture)

See `docs/design/sources/README.md`:

- `起点本章说(段评源)` → `https://m.qidian.com#rfc004-review`
- `微信读书划线(段评源)` → `https://weread.qq.com#rfc004-review`

Push with `python scripts/push-rfc004-review-sources.py` (or `save_source` `format=js`). Device **API**/`debug_source` evidence is in `docs/design/sources/README.md` (2026-08-09). In-reader overlay bucket logcat for these two URLs is still TODO; fixture overlay evidence below still stands.

Chapter-bucket is expected to work once bound; paragraph icons still need `ContentSplitVerified` (fixture URL only today).

## Agent run record (2026-08-09)

| Item | Evidence |
|------|----------|
| APK | `assembleAppDebug` + `adb install -r` `com.legado.app.debug` |
| Fixture | MCP `save_source` → `legado-fixture://review-overlay` enabled |
| Binding (manual P1) | `book_review_bindings` → content《综漫：我同时穿越了99个世界》+ fixture provider |
| Overlay log | `ReviewOverlay bind=legado-fixture://review-overlay align=0.7 authority=ContentSplitVerified coverage=0/4 (<0.5) → chapter-bucket only` |
| Chapter bucket | `… coverage=0/4 bucket=2` (P1 path OK; digram coverage fail expected on dissimilar body text) |
| P2 icons | Not asserted on dissimilar body (coverage gate correctly blocked wrong para icons) |
| **P3 auto-bind** | Pref `reviewOverlayAutoBind=true`; unbound《爱的艺术》(empty author); UI snackbar「发现段评源「RFC004段评提供方(夹具)」，是否绑定？」; tap 确认 → `book_review_bindings` row `bindMode=auto` |
| Prefs P4 | Switches in `pref_config_read.xml` (阅读设置) |
| Unit | `:app:testDebugUnitTest --tests 'io.legado.app.model.review.*'` BUILD SUCCESSFUL |

Commits: P2 `1968aa58a` · P3 `3d17ee2da` · P4 `dca536b88` · verify `62c269d04` (PE `#682`).

Re-run overlay: `python scripts/rfc004-overlay-device-session.py`  
Re-run P3 snackbar: enable 自动发现段评源, clear binding, open unbound book with weak/empty author (fixture returns `夹具作者`).
