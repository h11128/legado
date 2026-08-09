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

1. Pref `reviewOverlayAutoBind` = true (default is **false**; set via debug/`AppConfig` until Settings UI lands in P4).
2. Unbound book, fixture enabled, open read.
3. Expect confirm snackbar (never silent bind). Confirm → binding persists; dismiss → stays unbound.
4. `capableCount==0` → no snackbar / no scan spam.

## Fail expectations

- No capable sources → toast「没有已启用的段评能力书源」(manual pick)
- Align fail (weird title) → no bubble, no crash
- Unbound → no overlay icons
- Coverage &lt; 0.5 on P2 → chapter-bucket only (no wrong paragraph icons)
