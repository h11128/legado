# RFC-004 device verify (P1 chapter-bucket)

## Fixture

Import JS source from:

`docs/design/fixtures/rfc-004-review-provider-fixture.js`

Source URL: `legado-fixture://review-overlay`  
Name: `RFC004段评提供方(夹具)`

It returns offline chapter-bucket reviews (`paraIndex=-1`) for any search key.

## Steps

1. Install/debug build with Room v101+.
2. Import the fixture JS source and enable it.
3. Open a shelf book whose **content origin is NOT** the fixture (any normal novel source).
4. Book info → **段评源** → pick `RFC004段评提供方(夹具)` → confirm bind (search echoes book name).
5. Open reading page on a chapter whose title can align (e.g. `第N章…`). Fixture TOC uses `第1章`…`第50章` plus named first three.
6. Expect title/章评 bubble with count `2` (only `-1`; no paragraph icons in P1).
7. Tap bubble → dialog shows two fixture comments.
8. Logcat: `ReviewOverlay bind=… align=… bucket=2`
9. 换源 content book → binding should survive (URL migrate); re-open read and step 6–7 still work.

## Fail expectations

- No capable sources → toast「没有已启用的段评能力书源」
- Align fail (weird title) → no bubble, no crash
- Unbound → no overlay icons
