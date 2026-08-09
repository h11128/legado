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



## 2026-08-09 harness: 如何确保每站 deep dig 都反思提升

**用户问：** 如何确保以后都按纪律？

### 根因（不是「忘了」这么简单）

1. 深挖常走 `scripts/lib/legado_mcp.py`，**不**经过 `source-cli diagnose`/`push` → **不 claim** `deep_active`。
2. stop hook 只在 `legadoSkill` 文档里写了，工作区常是 `legado` → **找不到** `deep_active.json`，followup 不触发。
3. IDE MCP hook 以前只认 `save_source`，不认 `debug_source`/`start_check_sources`。
4. 散文 discipline / skill 挡不住跳过 close-out。

### 结构性修复（已落地）

| 层 | 改动 |
|----|------|
| Python | `LegadoMcp.debug/save/check` → `source-cli closeout claim` |
| Hook | `legado`+`legadoSkill` `.cursor/hooks.json`：stop + afterMCP |
| Hook script | `check-deep-active-stop.py` 搜 sibling `legadoSkill`；`mcp-deep-dig-claim.py` |
| HookRule | `legado_progress_next_unsealed_remind` ASK |
| Skill trap | `manual_mcp_bypass_closeout` |
| Discipline | §5c |

### 仍靠 agent 但被门禁兜底的部分

claim 之后必须：`ledger` → `retro`（trap/skill_fix/script_fix）→ 新陷阱改 SKILL → retrospective 短记 → commit。未 seal 则 stop 催、`progress next` DENY。

Trap: `manual_mcp_bypass_closeout` · script_fix: `legado_mcp.py+hooks`

## 2026-08-09 www.75zwz.com deep dig

- Bug: searchUrl 误用 `searchtype={{key}}`；真字段 `369koolearn` POST 仍空壳无 `#sitembox`。
- Open: `/1123134/` 目录 1417 + `#content` 正文 OK（手机 debug）。
- Verify: `checkSearch=false` `checkDiscovery=false` → 校验成功（发现 alone 会「发现目录失效」勿当整源死）。
- Twin: `m.75zwz.com` 已迁通；本源保留打开路径。
- Trap: `search_empty_shell_open_ok`；反思：本站 close-out 当场写 skill+retro（用户要求每站提升）。


## 2026-08-09 www.121ds.cc deep dig

- PC/手机：`23.224.254.235` 连接超时（HTTP 日志 ~43s）。
- hunt empty；`www.121ds.com` 活着但是**影视站**（快看影视），非小说孪生；`.net` CF Redirecting。
- 已禁用。Trap: `name_similar_video_not_novel_twin`。


## 2026-08-09 www.27k.net deep dig

- PC timeout；手机 POST `/search/` Cronet 60s（HTTP #644）。
- `hunt --probe` empty；已禁用。Trap: `host_phone_timeout_no_mirror`。


## 2026-08-09 www.bamxs.com deep dig

- DNS `23.225.63.70`；PC/手机超时；hunt empty；已禁用。Trap: `known:host_phone_timeout_no_mirror`。


## 2026-08-09 www.wuxianxs.cc deep dig

- 首页 PC/手机 **403**（HTTP #5）；searchUrl JS `java.ajax` 取 form → TypeError null[1]。
- hunt empty；`.net`→safebrowse 威胁页；`.com` 超时。已禁用。
- Trap: `http_403_home_hunt_empty`。deep_active 由 LegadoMcp.debug 自动 claim。


## 2026-08-09 www.69shu.xyz deep dig

- POST `/search` → CF **520 Origin Error**（手机 HTTP #4）；列表 0。
- hunt empty。已有启用孪生 `https://www.69shuba.com`（先前迁通；搜索或仍 CF）。
- 已禁用 xyz。Trap: `cf_520_origin_error_hunt_empty`。


## 2026-08-09 154.37.154.143（番茄小说）deep dig

- 裸 IP PC/手机 POST search **60s timeout**；hunt empty。
- 源 `Host: m.xhsxsw.com` / toc Host `www.xhsxsw.com` → 打开是「官网首页」停车壳，无搜索/目录。
- 已禁用。Trap: `ip_url_host_header_parked`。


## 2026-08-09 www.qingzichan.net deep dig

- PC timeout / 连接被关；手机校验 `Failed to connect …38.55.186.43:80`（POST /search ConnectException）。
- hunt empty；`gegedang.com` 标题「官网首页」停车壳。已禁用。
- Trap: `known:host_phone_timeout_no_mirror`。


## 2026-08-09 m.boshishuwu.com deep dig

- `.com` 301→`m.boshishuwu.net`；手机对 .com/.net 的 http+https 均 `PROTOCOL_ERROR`（StreamReset）。
- PC RemoteDisconnected；hunt empty。已禁用各变体。
- Trap: `stream_protocol_error_reset`。


## 2026-08-09 hongxiud.com deep dig

- http 通但整站被劫持成色情广告壳（title「美女日韩一区…」）；search.aspx 同壳无书列表。
- 已禁用 http/https。Trap: `known:域名广告劫持 (pyzht)`。


## 2026-08-09 m.92yanqing.com deep dig

- 手机 POST `/search/` Cronet 60s timeout；PC 不通；hunt empty。已禁用。
- Trap: `known:host_phone_timeout_no_mirror`。

## 2026-08-09 www.00ksw.com deep dig

- PC/手机均 TCP hang；DNS alias `23.224.148.*` via uucdn；`hunt --probe` empty。
- 候选 `00ksw.cc`=XDNS 威胁页、`.org`=广告 redirect、`00ks`/`ldks` 停车。已禁用；书架 5 靠自动换源。
- Trap: `known:host_phone_timeout_no_mirror`。
- 注：本机 APK `save_source` 仍默认保留 enabled（响应无 `enabled=` 行）；本批用 `legado_adb` 拉库改 `enabled=0` 后推回。

## 2026-08-09 www.ffxs8.top deep dig

- `.top` NXDOMAIN；孪生 `https://www.ffxs8.com` 首页/详情/目录/正文 OK；站内搜索恒「没有搜索到相关的内容」。
- 手机 debug 打开书架路径成功；`checkSearch=false` 校验成功。书架 5 本 `origin`+URL remap→`.com`；`.top` 已禁用。
- Trap: `known:search_empty_shell_open_ok`。

## 2026-08-09 www.dubu123.com deep dig

- NXDOMAIN（手机 `UnknownHostException`）；hunt empty。
- `dubuxs.cc`=XDNS 威胁页；`dbxs123.com`=影视壳（勿迁）；`dbxsn` 404/403。已禁用；书架 4 靠自动换源。
- Trap: `known:host_phone_timeout_no_mirror`。

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
