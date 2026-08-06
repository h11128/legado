# Source repair retrospective


## 2026-08-06 非小说壳 + QQ 搜索 + 换源 gate

### 书源
- **百度图片 / 百度知道**：`bookList` 曾用 `decodeURIComponent(word)` 伪造成一书 → 改为 `@js:[]`（非小说站，校验记 skip）。Trap: `url_decode_fake_booklist_non_novel`。
- **白浏览器 / 松鹤庭沐**：`bookList=$.data.state[*]` 混入推荐模块；搜索 JSON 无 lastChapter。改为只 flatten `novel_search_list.items`；详情已有 `lastSerialname`。校验成功（keyword=吞噬星空）。Trap: `qq_search_state_not_items`。
- **百度B / novelapi**：真小说 API，搜索无 lastChapter 但有作者+长简介 → 不改规则，靠 App 新 gate 放行。

### 过滤（ChangeBookSourceQuality）
- `isNonNovelSearchHost`：image/zhidao/tieba/baike/wenku/dict.cn 等硬拒。
- `hasCredibleAuthorIntro`：空最新章时，作者非空 + 简介≥40 且非词典壳 → 可进换源列表（覆盖 QQ/百度小说 API）。
- 单测：`ChangeBookSourceQualityTest` BUILD SUCCESSFUL。


## 2026-08-06 肉文小说 author
- URL: https://www.xn--7dv141d.com/
- Bug: search `class.author@text` concat sibling 阅读量; detail missing author
- Fix: search `class.author.0@text##作者：`; bookInfo author from booktag a.0
- Verify: debug author=新乙; check 校验成功 (~3.8s)
- 晴天: one aggregator `v1.gyks.cf` with source=全部 → multi SearchBook same originName

## 2026-08-06 海词 dict.cn
- Fake name from URL decode fixed: bookList empty on 词条未找到; name from h1/.word
- Verify: novel key → 0 books; hello check 校验成功
- App: isAcceptableChangeSourceHit (usable latest, author when local set, non-book intro)
