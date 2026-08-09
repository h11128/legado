# Real review providers (RFC-004)

Durable JS sources for **real** cross-source 段评 (not the offline fixture).

| File | bookSourceUrl | What it provides |
|------|---------------|------------------|
| `qidian-review-provider.js` | `https://m.qidian.com#rfc004-review` | 起点本章说 via `m.qidian.com/majax/chapterReview/*` |
| `weread-review-provider.js` | `https://weread.qq.com#rfc004-review` | 微信读书热门划线 via `/web/book/bestbookmarks` |

**Bind as 段评源 only.** Do not use these as the shelf content origin (VIP/WAF/划线拼正文会导致段序错乱).

## Push to phone

```bash
# LEGADO_MCP_URL from legadoSkill/config/mcp_defaults.json (or env)
python scripts/push-rfc004-review-sources.py
```

## Device evidence (2026-08-09)

### 起点

- `debug_source` 诡秘之主 → search list size **20** (script asks `pageSize=10`; API returns more) / toc 1418 / content len 2544
- `eval_js` `reviewSummary` chapter `402733549`: raw field **`textCount`**, e.g. `paragraphId=-1 textCount=11174`, para1=`5009` (matches `reviewList.total`)
- `eval_js` `reviewList` `segmentId=-1`: real nicknames + 本章说正文

### 微信读书

- `debug_source` 红楼梦 → search 10 / toc **8** (only chapters that appear in popular highlights) / content preview from划线
- `eval_js` `bestbookmarks`: `totalCount=963`, 10 items across chapterUids `11…134`

### Overlay UI

API + `getReviewSummary`/`Detail` wiring verified via device ajax/`debug_source`.  
**In-reader chapter-bucket overlay with these two URLs was not re-run in this turn** (fixture overlay evidence remains in the guide). Next: bind 段评源 on a matching shelf title and confirm `ReviewOverlay … bucket=` logcat.

### QQ 阅读

Web 段评接口未在本轮抓到稳定公开路径。起点与微信读书优先；QQ 留待 App 抓包。

## Overlay test (章评)

1. Enable `起点本章说(段评源)` or `微信读书划线(段评源)`.
2. Open a shelf book with a **matching title** from any content origin.
3. Book info → **段评源** → pick the provider → confirm.
4. Open a chapter whose title exists on the **provider TOC** (微信读书只有热门划线那几章；起点用 `第一章 绯红` 等).
5. Expect chapter-bucket bubble; tap → real comments.
6. Paragraph icons: still gated by `ContentSplitVerified` (fixture URL only unless authority extended).

## Notes

- 起点 PC `read.qidian.com` / `vipreader` ajax is WAF `202` probe; **mobile majax works**.
- 微信读书 `i.weread.qq.com` needs login; **web** `weread.qq.com/web/*` search + bestbookmarks work without login.
