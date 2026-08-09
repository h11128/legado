# RFC-004 device verify (P1–P3 overlay)

**Agent 全套截图验收（无缝自动绑，真实源）：** 见 [rfc-004-autobind-acceptance.md](./rfc-004-autobind-acceptance.md)  
一键：`python scripts/rfc004-run-acceptance.py`

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

## P3 — Auto-bind (default on, silent multi)

1. 阅读设置 → **自动发现段评源** (`reviewOverlayAutoBind`, **default on**). Turn off to disable.
2. Ensure ≥1 review-capable source is enabled (fixture and/or real `*段评源`).
3. Book with **no** (or incomplete) `book_review_bindings` → open read.
4. Expect **silent** bind of each capable source with **exactly one** `sameBook` hit (cap = `reviewOverlayMergeMax`, default 5). Toast:「已自动绑定 K 个段评源」.
5. Ambiguous (≥2 hits on one source) → that source skipped (no bind).
6. Existing rows (including **disabled**) are never overwritten / re-enabled.
7. `capableCount==0` → no scan spam / no toast.

Re-run: `python scripts/rfc004-autobind-device-session.py`  
(older confirm-snackbar path removed).

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
- `番茄书评(段评源)` → `https://fanqienovel.com#rfc004-review`（社区镜像书评章评桶；七猫验签未通）
- `晋江章评(段评源)` → `https://www.jjwxc.net#rfc004-review`
- `QQ阅读书吧(段评源)` → `https://book.qq.com#rfc004-review`（书吧 HTML；真段评 API 未公开）

Push with `python scripts/push-rfc004-review-sources.py` (or `save_source` `format=js`). Device **API**/`debug_source` evidence is in `docs/design/sources/README.md` (2026-08-09). In-reader overlay bucket logcat for these real URLs is still TODO; fixture overlay evidence below still stands.

### P5 multi-provider（合集）

1. Build with Room **v102** (multi-row `book_review_bindings`: `enabled` / `sortOrder` / `role`).
2. 简介 → **段评源** → **添加段评源**，先后绑定 ≥2 个有能力的源；**管理已绑定**可设段落主源 / 启停 / 移除。
3. 打开章节：章评气泡 = 各源 `-1` count **之和**（未去重）；logcat `ReviewOverlay merge providers=…`（无伪造 `merge:N` paraData）。
4. 点章评：`ReviewMergeDetailDialog`，条目带源名徽章；**点某一条** → 该源 `ReviewDetailDialog`（A19，完整分页在详情里）。
5. 阅读设置 → **多源章评合集** off（`reviewOverlayMergeEnabled=false`）→ 只用 sortOrder 最小的一个源。

Device smoke (2026-08-09): `assembleAppDebug` + install → logcat `DB version upgrading from 101 to 102` OK; unit `ReviewOverlayMergeTest` / resolver tests green.

P5 merge smoke (2026-08-09): `python scripts/rfc004-overlay-device-session.py --merge` → dual bind (fixture + 起点段评源) → logcat `ReviewOverlay merge providers=1 bucket=2` (peer align skip expected; merge path exercised).

P5 merge **UI** (2026-08-09): save fixture B `legado-fixture://review-overlay-b` → `python scripts/rfc004-p5-merge-ui-session.py` → logcat `merge providers=2 bucket=5` → tap 章评角标 → `本章评论（2 源）` / `共 5 条评论` + 源徽章 → tap 行 → `ReviewDetailDialog` `共 2 条评论`（该源详情，A19）.

P5 **real providers** (2026-08-09): 《诡秘之主》+ `起点本章说` + `QQ阅读书吧` → `python scripts/rfc004-p5-real-providers-ui-session.py` → logcat `merge providers=2 bucket=11194`（起点 11174 + QQ 20）→ UI `本章评论（2 源）` / 真本章说正文（如「赞美愚者」「king crimson!」）→ 行点击进起点详情 `共 11174 条评论`.

Thread close-out / full timeline: [docs/postmortem/2026-08-09-rfc004-cross-source-review-thread.md](../postmortem/2026-08-09-rfc004-cross-source-review-thread.md).


Chapter-bucket is expected to work once bound; paragraph icons still need `ContentSplitVerified` (fixture URL only today) and at most one `paragraph_primary`.

In-dialog per-provider load-more on the merge list itself is deferred (§12.4.3 follow-up).

## Agent run record (2026-08-09)

| Item | Evidence |
|------|----------|
| APK | `assembleAppDebug` + `adb install -r` `com.legado.app.debug` |
| Fixture | MCP `save_source` → `legado-fixture://review-overlay` enabled |
| Binding (manual P1) | `book_review_bindings` → content《综漫：我同时穿越了99个世界》+ fixture provider |
| Overlay log | `ReviewOverlay bind=legado-fixture://review-overlay align=0.7 authority=ContentSplitVerified coverage=0/4 (<0.5) → chapter-bucket only` |
| Chapter bucket | `… coverage=0/4 bucket=2` (P1 path OK; digram coverage fail expected on dissimilar body text) |
| P2 icons | Not asserted on dissimilar body (coverage gate correctly blocked wrong para icons) |
| P2 same-body (unit) | `ReviewParagraphMapTest.fixtureSameBodyCoverageOpensParagraphIcons` → coverage **4/4** when local==fixture paragraphs |
| **P3 auto-bind (legacy)** | Pref on; unbound《爱的艺术》; snackbar confirm → `bindMode=auto` |
| **P3 seamless (2026-08-09+)** | Default `reviewOverlayAutoBind=true`; silent `proposeAll` (parallel) + `bindAutoAll`; Native origin also discovers peers + binds capable origin; toast「已自动绑定 K 个」. Device `rfc004-autobind-device-session.py`《诡秘之主》→ `proposals` + `bind=… bucket=11175` `bindMode=auto` (`temp/rfc004_autobind_run3.log`) |
| 起点段号探针 | `python scripts/rfc004-probe-qidian-para-align.py` → `idEqualsPosition=true` (textCount≠id); authority still fixture-only |
| Prefs P4 | Switches in `pref_config_read.xml` (阅读设置) |
| Unit | `:app:testDebugUnitTest --tests 'io.legado.app.model.review.*'` BUILD SUCCESSFUL |

Commits: P2 `1968aa58a` · P3 `3d17ee2da` · P4 `dca536b88` · verify `62c269d04` (PE `#682`).

Re-run overlay: `python scripts/rfc004-overlay-device-session.py`  
Re-run silent auto-bind: `python scripts/rfc004-autobind-device-session.py`  
**Full screenshot acceptance (real only):** `python scripts/rfc004-autobind-acceptance-ui.py`  
→ `temp/rfc004_ui/acceptance/ACCEPTANCE.json` + gates G1–G9 PNGs (fixtures off; silent multi-bind 起点+QQ; `coverage=69/69`; merge dialog; 简介「段评源：已绑定 2 个»; autoBind-off negative).
