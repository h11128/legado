# Source repair retrospective


## 2026-08-09 书架「失效」标签源抽查 + 逐个修复关门

- 抽查 10 个大源（书架书最多）：当时 0/10 正文完整；仅 69shu / lwxs 能拉目录。表与队列见 `docs/guides/shelf-stale-tag-source-queue.md`。
- **trxs.me**：DNS NXDOMAIN → `https://trxs.cc`；禁用 `.me`；书架 8 remap / 13 自动换源。
- **乐文 m.lwxs.com**：L2 host redirect → `https://m.ilwxs.com`；旧源 disable；书架 31 已 remap；设备校验成功（`checkSearch=false`，发现→目录→正文）；**搜索 POST 站端 500**，未谎称搜索修好。
- **其余 8**：gate/hunt 后 skip（CF/TLS/超时/证书过期/不可达），保持启用给挂梯用户，不删书架引用 URL。
- 策略确认：暂不批量修空目录书架；靠自动换源兜底。

## 2026-08-06 非小说壳 + QQ 搜索 + 换源 gate

### 书源
- **百度图片 / 百度知道**：`bookList` 曾用 `decodeURIComponent(word)` 伪造成一书 → 改为 `@js:[]`（非小说站，校验记 skip）。Trap: `url_decode_fake_booklist_non_novel`。
- **白浏览器 / 松鹤庭沐**：`bookList=$.data.state[*]` 混入推荐模块；搜索 JSON 无 lastChapter。改为只 flatten `novel_search_list.items`；详情已有 `lastSerialname`。校验成功（keyword=吞噬星空）。Trap: `qq_search_state_not_items`。
- **百度B / novelapi**：真小说 API，搜索无 lastChapter 但有作者+长简介 → 不改规则，靠 App 新 gate 放行。

### 过滤（ChangeBookSourceQuality）— 一律跟换源菜单
- 「校验作者」：作者重叠（默认关，跟菜单）。
- 「过滤非小说源」：`isNonNovelSearchHost`（image/zhidao/dict.cn 等；默认开）。
- 「过滤词典简介」：`looksLikeNonBookIntro`（默认开）。
- 「正文不合格时移除」：字数/consensus 失败时删列表；关则降档+徽章保留（默认开）。
- 短 tip（≤6 无数字）不当章节；`prepare` 剥 chrome tip（展示卫生，非开关）。
- 进度 UI：两行（结果/命中/已问 + 当前源）；`hitCount` 按书条累加。
- 单测：`ChangeBookSourceQualityTest`。
- 真机 iter1（学霸也开挂，本地作者空）：命中~205，search-quality 仅 2；正文深探后 list≈1（stitch_weak_ref）。两行进度可读。


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
