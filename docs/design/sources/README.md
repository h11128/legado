# Real review providers (RFC-004)

Durable JS sources for **real** cross-source 段评/章评/书评 (not the offline fixture).

| File | bookSourceUrl | What it provides |
|------|---------------|------------------|
| `qidian-review-provider.js` | `https://m.qidian.com#rfc004-review` | 起点本章说 via `m.qidian.com/majax/chapterReview/*` |
| `weread-review-provider.js` | `https://weread.qq.com#rfc004-review` | 微信读书热门划线 via `/web/book/bestbookmarks` |
| `fanqie-review-provider.js` | `https://fanqienovel.com#rfc004-review` | 番茄书评 via community mirror `/api/comment` → chapter-bucket |
| `jjwxc-review-provider.js` | `https://www.jjwxc.net#rfc004-review` | 晋江章评 via `comment.php` HTML |
| `qqread-review-provider.js` | `https://book.qq.com#rfc004-review` | QQ阅读书吧 via `/book-comment/{bid}` HTML → chapter-bucket |

**Bind as 段评源 only.** Do not use these as the shelf content origin (VIP/WAF/划线拼正文/书吧无章序 会导致段序错乱).

## Push to phone

```bash
# LEGADO_MCP_URL from legadoSkill/config/mcp_defaults.json (or env)
python scripts/push-rfc004-review-sources.py
```

## Device evidence (2026-08-09)

### 起点

- `debug_source` 诡秘之主 → search list size **20** (script asks `pageSize=10`; API returns more) / toc 1418 / content len 2544
- `eval_js` `reviewSummary` chapter `402733549`: field **`textCount`** is the **comment count** (not the segment id). Example: `paragraphId=-1 textCount≈11175`; body `paragraphId=1 textCount=5009` (5009 = 条数).
- **段号坐标系（2026-08-09 复探针）:** `python scripts/rfc004-probe-qidian-para-align.py` → `getContent` `<p>`→`\\n\\n` 拆出 **69** 段；summary body **69** 条；`paragraphId` **== 1..69**（`idEqualsPosition=true`）。因此「opaque 大整数当段号」是误读 `textCount`；本源在本章上段号与拆段下标一致。
- **但仍不能直接开 P2 段角标：** `ReviewParagraphAuthority` 仍只有夹具 URL 为 `ContentSplitVerified`；盗版正文书源正文 ≠ 起点 `<p>` 正文时 digram hard-map coverage 会塌（夹具异文真机曾见 `coverage=0/4`）。下一步若开门：需同书同章对照 coverage 证据，或 `paraPreview` 桥，而不是只看段号是否 1..N。
- `eval_js` `reviewList` `segmentId=-1`: real nicknames + 本章说正文
- Probe raw: `temp/rfc004_qidian_para_probe_out.txt`

### 微信读书

- `debug_source` 红楼梦 → search 10 / toc **8** (only chapters that appear in popular highlights) / content preview from划线
- `eval_js` `bestbookmarks`: `totalCount=963`, 10 items across chapterUids `11…134`

### 番茄

- Community mirror `http://101.35.133.34:5000` (host may change)
- `debug_source` 修罗武神 → search 15 / toc **6760** / content len 2939
- `eval_js` `getReviewSummary/Detail`: chapter-bucket count **10** (= page-1 length lower bound, not site total), first nick `用户11538048` + real book-comment text
- Detail paging: **page-1 only** (`nextPageUrl=null` until page-2 verified)
- **七猫** `api-ks.wtzw.com` → 验签失败；true Fanqie paragraph 段评 needs unidbg-signed APIs — deferred

### 晋江

- Search: **prefer numeric novelId**; keyword only client-filters the free-library homepage scrape — miss → `[]` (no unrelated dump)
- `debug_source` novelId `10265010` → search 1 / toc **74** / content len 6692
- `eval_js` chapter 1 comments: **14** items after `mormalcomment_*` parse; sample `963: 走，手榴弹来一发！`
- Detail paging: **page-1 only** (`nextPageUrl=null` until page-2 verified)

### QQ阅读

- App/Web `commontgw*.reader.qq.com` comment XHR: **404 / DNS fail** — no stable public 段评 API
- Fallback: SSR `book-comment/{bid}` `li.reply-list` (书吧书评 → chapter-bucket)
- `debug_source` 修罗武神 → search **10** / toc 80 (detail page partial catalog) / content placeholder
- `debug_source` bid `41089201` → 星环使命; `eval_js` review: **20** items, first `青玲_cE: 非常的不错很喜欢这本书`
- Keyword search can soft-404 for some titles; bind can use numeric **bid** or paste `book-detail/{bid}` URL

### Overlay UI

API + `getReviewSummary`/`Detail` wiring verified via device ajax/`debug_source` for the providers above.  
**In-reader chapter-bucket overlay bind + logcat was not re-run for every new URL** (fixture overlay evidence remains in the guide). Next: bind 段评源 on a matching shelf title and confirm `ReviewOverlay … bucket=` logcat.

## Overlay test (章评)

1. Enable one of the `*段评源` sources above.
2. Open a shelf book with a **matching title** from any content origin.
3. Book info → **段评源** → pick the provider → confirm.
4. Open a chapter whose title exists on the **provider TOC** when the provider is chapter-scoped (起点/晋江); 番茄/QQ 书评是全书桶，任意对上的章都可出章评气泡。
5. Expect chapter-bucket bubble; tap → real comments.
6. Paragraph icons: still gated by `ContentSplitVerified` (fixture URL only unless authority extended). 起点段号虽可与自身 `getContent` 对齐（见上探针），跨正文书源仍需 coverage 证据。

## Notes

- 起点 PC `read.qidian.com` / `vipreader` ajax is WAF `202` probe; **mobile majax works**.
- 微信读书 `i.weread.qq.com` needs login; **web** `weread.qq.com/web/*` search + bestbookmarks work without login.
- Fanqie community mirror is third-party; treat as best-effort supply, not official ByteDance API.
