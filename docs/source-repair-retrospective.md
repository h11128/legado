# Source repair retrospective


## 2026-08-09 浅层「修不了」被打脸 — 必须记的总教训

**用户反馈：** 先前说修不了，再一站一站 deep diagnose 又能修；缺反思记录。

### 做错了什么

1. 把 `gate` / `serial oneshot` / `hunt empty` / 「搜索失效」标签当成终局，口头判死。
2. 批次结束后只写了单站 ledger/retro，**没有**把「浅层判死 ≠ 不可修」写进回顾 + skill trap。
3. 混淆了「搜索挂了」和「整源不可用」——书架打开路径（详情/目录/正文）仍可能活。

### 深挖后翻案（同批证据）

| 先前浅判 | 深挖结果 | 关键证据 |
|----------|----------|----------|
| haitang123 超时/skip | **migrate** → `m.haitang4.com` 校验成功 | 孪生已在机；原站 L1 超时 |
| m.75zw NXDOMAIN/skip | **migrate** → `m.75zwz.com`；书架 remap | 搜索仍是热门假结果；打开路径 OK |
| 31xs 搜索失效 → fail | **fixed**（无搜索） | 正文 `qsbs.bb` base64；发现/打开校验成功 |
| ttshu8 POST 500 → fail | **fixed**（无搜索） | 打开/目录/正文 OK；`checkSearch=false` 校验成功 |
| b520 搜索 404 → skip | **skip 加深** | TOC 几乎全 `href="/"`；孪生 PC 可修但手机 `23.224.*` 超时 |
| lrxs / kanshushen skip | **skip 确认** | 占位壳→Google；api_search 404 + hunt empty |

### 以后怎么做（可执行）

1. **禁止**仅凭 gate/serial/标签说「修不了」收工；至少做：PC 抓首页+搜索+一本详情 TOC/正文，或手机 `debug_source` + `get_http_logs`。
2. 搜索死 ≠ 整源死：打开路径通 → 可 `checkSearch=false` 验证并注明「无搜索」；§16 默认 disable 假搜索，但**不要**在未看打开路径时直接 skip。
3. hunt empty / L2 通 都不够：孪生要 **手机可达**（HTTP 日志超时也算证据）。
4. 每批 deep dig 结束：`docs/source-repair-retrospective.md` 写总教训 + 单站 `retro append`；新行为陷阱进 SKILL（本条 trap：`shallow_unfixable_claim`）。
5. 名单 SOT：`docs/guides/shelf-stale-tag-remaining.md`（深挖表与浅表矛盾时以深挖为准）。

Trap: `shallow_unfixable_claim` · script_fix: `no_auto:agent_must_html_or_phone_debug`


## 2026-08-09 www.75zwz.com deep dig

- Bug: searchUrl 误用 `searchtype={{key}}`；真字段 `369koolearn` POST 仍空壳无 `#sitembox`。
- Open: `/1123134/` 目录 1417 + `#content` 正文 OK（手机 debug）。
- Verify: `checkSearch=false` `checkDiscovery=false` → 校验成功（发现 alone 会「发现目录失效」勿当整源死）。
- Twin: `m.75zwz.com` 已迁通；本源保留打开路径。
- Trap: `search_empty_shell_open_ok`；反思：本站 close-out 当场写 skill+retro（用户要求每站提升）。

## 2026-08-09 书架「失效」标签源抽查 + deep diagnose 关门

- 抽查 10 个大源：表与队列见 `docs/guides/shelf-stale-tag-source-queue.md`。
- **迁通 5**：trxs→trxs.cc；乐文→ilwxs；爱下 www+api→已有 ixdzs8（证书过期/API 死）；69shu→69shuba.com（真机 toc+正文 OK，搜索仍 CF）。
- **深挖仍 skip 5**：81zw CF 403 challenge；lrxsw CF 522；xxbiqudu TLS；uukanshu DNS→127.0.0.1（.cc 亦 CF）；69shuba.pro TLS（改用 .com）。
- 教训：gate「hunt 空」≠未深挖；真机 HTTP 日志（403/522/loopback）才能定性。UU 不是简单超时，是域名解析到本机。
- 策略：书架靠自动换源；不删仍有 origin 引用的 URL。

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
