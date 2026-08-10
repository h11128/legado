# Source repair retrospective

## 2026-08-10 — shelf stale continue (feisxs →)

| URL | 结果 | 证据 |
|-----|------|------|
| `https://m.feisxs.com` | **skip**（禁用） | PC CF403；手机搜索空、书页 HTTP **404**；Google→官网 `www.fsuzw.com`（同 `/book-47672/` 详情/目录通）；正文 `#nr1` YHFixed/`&#13xxxx` 字体加密不可读（同 feibzw）；`hunt --probe` empty；书架 1 本靠换源。Trap：`content_font_encrypt_dynamic` |
| `https://199.33.126.51/` | **skip migrate** | L1 TCP timeout；seeds 指向 `m.tongrenquan.org`（旧同人圈共享 IP）；oneshot 误改 slash 孪生 search→GET 失败；手工 **25 本** origin+bookUrl remap→`https://m.tongrenquan.org`；禁 IP 源+slash dup；`m.tongrenquan` **校验成功 2716ms**。Trap：`known:apex_no_a_try_m` |
| `https://m.fuwenh.com` | **fixed**（无搜索） | POST `/s.php` 空壳；详情/目录/`#content` DOM 改版；发现 `sort/N-{{page}}.html`+`.topul li`；`checkSearch=false`+discovery **校验成功 738ms**。书架 ID 可能错书。Trap：`search_empty_shell_open_ok` |

## 2026-08-10 — shelf stale continue (midu retag → bqg18)

| URL | 结果 | 证据 |
|-----|------|------|
| `http://api.midukanshu.com#yc1101` | **fixed**（清标签） | 已可用；备注含「搜索失效」被误扫；清备注后校验成功 1351ms |
| `http://m.kygnew.com` | **skip**（禁用） | parklogic Redirecting 壳；kayege/kaye-ge 无同路径；hunt/OSINT 空 |
| `http://www.7zzw.com` | **skip**（禁用） | safebrowse 威胁墙；孪生 `##@遇知` 一并禁 |
| `http://www.969193.com` | **skip**（禁用） | NXDOMAIN；书架 bookUrl 在 236318→236317 但 POST/书页 **403** 空壳 |
| `https://b.faloo.com#温暖满怀` | **fixed**（复验） | dig verify；校验成功 3154ms |
| `https://m.bqg18.cc/` | **skip**（禁用） | PC TLS corrupt；手机详情 OK；目录/正文跳 `/user/verify.html` |
| `https://m.75zwcom.com` | **skip migrate** | 搜索空；同路径孪生 `m.75zwz.com` 详情/572章/正文曾 OK；书架 remap；`start_check`/偶发 debug **PROTOCOL_ERROR** → **未**记校验成功 |


## 2026-08-10 — shelf stale-tag dig batch (shenyebook → shufahouse)

| URL | 结果 | 证据 |
|-----|------|------|
| `https://www.shenyebook.com/` | **skip**（禁用） | TLS `InvalidContentType`；hunt/OSINT empty；同停车 IP 族；`legado-db-mutate disable` |
| `https://www.shukuai.net` | **skip**（禁用） | dig `l2_bot_shell`；301→`shukuai3.com` CF；手机书页 HTTP **403**；`shukuaixs.com` 路径 404；OSINT 无非 CF 镜像 |
| `https://www.tqcyjy.com` | **skip**（禁用） | PC TLS corrupt；手机详情空；hunt/OSINT empty；force disable |
| `https://m.shufahouse.com` | **fixed** | dig 因 **证书过期** 误 hunt_empty→disable；PC `danger`/手机仍 200；搜索 120 + 目录 267 + 正文 OK；校验成功 **4240ms**；清「搜索失效」 |
| `http://m.uuxsw8.cc` | **skip**（禁用） | `deadish:this domain` 停车；`.com` NXDOMAIN；hunt/OSINT empty |
| `https://www.121ds.cc` | **skip**（禁用） | L1 TCP timeout；手机 debug 超时；hunt/OSINT empty；slash 孪生一并禁 |
| `https://www.zw34.net` | **skip**（禁用） | L1 TCP timeout；hunt/OSINT empty |

**新陷阱 `cert_expired_phone_ok`**：PC rustls 拒过期证 ≠ 站死。Harness：`source-gate/classify.rs` → `l2_cert_expired` + `Verify`。

**MCP sticky busy**：`McpToolServer` 私有 `debugMutex` 与 `reset_mcp_channel` 解锁的 `McpChannelGuard.debugMutex` 不是同一把锁 → force-reset 后仍「通道占用中」。已改共用 Guard mutex（需重装 debug APK 才上手机）。

## 2026-08-09 — midu + bq9 + mhtxs dig

| URL | 结果 | 证据 |
|-----|------|------|
| `http://api.midukanshu.com#yc1101` | **fixed** | dig ok；校验成功 1389ms；清陈旧备注 |
| `https://m.bq9.cc` | **skip**（禁用） | Click-here 停车壳；hunt empty |
| `http://wap.mhtxs.cc` | **skip**（禁用） | L2 timeout；hunt empty；`mhtxs.la` 另源已启用 |
| `https://m.lrxs.org` | **skip**（已禁用） | dig 再确认 Web accesible→Google；hunt empty；另禁 `http://www.lrxsw.org` CF522 |
| `https://m.75zwz.com` | **fixed** | dig oneshot `set searchUrl`；校验成功 1152ms；搜索仍混淆热门壳（打开路径 OK） |
| `http://www.uu234.net` | **skip**（禁用） | NXDOMAIN；hunt/OSINT 无活孪生；书架 bookUrl 在 uuxs5/8 威胁/跳转壳 |
| `https://www.69shu.xyz` | **skip**（禁用） | CF520；hunt empty；69shuba 孪生已启用；oneshot disable 需 legado-db-mutate 再确认 |
| `http://www.shu05.com` | **skip**（禁用） | gate `l2_domain_parked_or_expired`（this domain）；force disable |
| `https://www.ixpsge.com/` | **skip**（禁用） | CF Just a moment→`xpshuku`；hunt empty；手机书页亦 challenge；slash/m 孪生一并禁 |
| `https://m.shouda88.com/` | **skip**（禁用） | `deadish:this domain` 停车；force disable |
| `https://m.paipaiwx.com` | **fixed**（无搜索） | 打开/目录/正文 OK；`checkSearch=false`+发现 **校验成功** 662ms |
| `http://download.maoyankanshu.la` | **fixed** | dig 因标签域 NXDOMAIN 误走 hunt/disable；`type=maoyankanshu` API 仍活；恢复 comment 内 JS 助手；校验成功 859ms |
| `http://www.ffxs8.com/` | **fixed**（无搜索） | 打开路径 OK；发现校验成功 1080ms；清「搜索失效」分组 |
| `http://www.xqishuta.com` | **fixed** | PC CF 假死；手机→.org 搜索/打开 OK；校验成功 2974ms |
| `https://www.aixiawx.com/#pb1101` | **fixed migrate** | dig 报 migrate 但未落库；手工→`http://www.aixiashu.la`；书架 remap；校验成功 2576ms |
| `https://m.paozww.com` | **skip**（禁用） | CF Attention Required；手机书页 403；hunt empty |
| `http://www.jpxs123.cc` | **skip**（禁用） | safebrowse 威胁页；书架 2 本 remap→已校验成功的 `https://jpxs123.com` |
| `http://www.rzlib.org/` | **skip**（禁用） | Unexpected EOF；hunt empty |
| `http://www.siluke.com` | **skip**（禁用） | PC/手机 timeout；hunt empty；.cc→isiluke.la 另源仍启用 |
| `http://www.xiaoshuobuluo.com/` | **skip**（禁用） | DNS NXDOMAIN；hunt empty |
| `http://www.xstxt.com/` | **skip**（禁用） | safebrowse→fjshu；搜索空 |
| `https://ggs.manmeng168.com/` | **skip**（禁用） | NXDOMAIN；hunt empty |
| `https://m.pzshen.com##@` | **skip**（禁用） | TLS packet header corrupt |
| `https://www.75zhongwen.com/` | **skip**（禁用） | timeout；书架《洪荒…》remap→`m.75zwz.com` 打开 OK |

### 会话教训（2026-08-10 补记 — 反思曾不完整）

两件事当时在对话里说了，但 **close-out 反思不对称**：

1. **`bookSourceComment` 里藏 JS 助手**（猫眼）：已写入本文件 + SKILL，但 per-URL `retro append` 偷用了旧 trap `check_keyword_too_broad`、`skill_fix=false`，等于 **结构性反思漏记**。根因：赶着 seal，用旧 trap 过 gate，而不是为新 trap 走 `--skill-fix`。
2. **PC CF ≠ 死站**（奇书网）：后来补全了 — SKILL `alias_booksourceurl_false_dead` + `retro --skill-fix` + `script_fix=no_auto:phone_debug_before_disable_cf`。

**MUST（本批起）：** 对话里强调的新陷阱，同一回合必须同时落到 (a) SKILL Traps 行、(b) `retro append` 正确 trap + `skill_fix`/`script_fix`、(c) 本 retrospective；缺一不算反思完成。

**Trap `whole_origin_remap_without_map_filter`：** `legado-db-mutate remap` 旧语义在给了 `--map` 时仍会改写该 origin 下**全部**书。误把 6 本已是 `mhtxs.la` bookUrl 的书 `origin` 拽回禁用的 wap。修复：有 `--map` 时只动 map 列出的 bookUrl；书架已恢复（6→`https://www.mhtxs.la`，`资本论` 留禁用 wap）。

**Trap `oneshot_disable_not_sticky`：** dig/oneshot 报 `disabled` 后手机 DB 仍可能 `enabled=1`；收工前用 `legado-db-mutate disable` 再确认。

**Trap `booksourcecomment_holds_js_helpers`：** 规则里 `eval(String(source.bookSourceComment))` —— **禁止**用白话备注覆盖 comment；改备注前先确认是否 JS 库。

**Trap `alias_label_host_nxdomain_api_alive`：** `bookSourceUrl` 标签域 NXDOMAIN，但 `searchUrl`/`type=…` 实际打活 API（如 maoyankanshu→jxgtzxc）；**勿**仅凭 L1 hunt_empty 当死站禁用；先 `debug_source`。

**Trap `alias_booksourceurl_false_dead`：** PC gate `l2_bot_shell`/CF「Just a moment」，手机仍可能 301 到 `.org` 活域；勿只凭 PC CF 禁用。

**Trap `dig_migrate_report_without_persist`：** dig 报 `migrated` 不等于库已改；须 `get_source`/SQL 确认，否则手工 upsert+remap+校验。

## 2026-08-09 — faloo + hongxiua dig

| URL | 结果 | 证据 |
|-----|------|------|
| `https://b.faloo.com#温暖满怀` | **fixed** | dig ok；校验成功 2394ms；清陈旧 DNS 备注 |
| `https://hongxiua.com` | **skip**（禁用） | Loading JS 壳；ww1 广告停车；无活后继 |

## 2026-08-09 — dbxsn.com → dbxsz.com

| URL | 结果 | 证据 |
|-----|------|------|
| `https://www.dbxsn.com` | **fixed migrate** | →`dbxsz.com`；首页404路径活；校验成功709ms；书架4 remap |

## 2026-08-09 — app.yqzw5.net dig

| URL | 结果 | 证据 |
|-----|------|------|
| `https://app.yqzw5.net` | **skip**（禁用） | 停车壳；hunt/OSINT `.com` 不可达 |

## 2026-08-09 — m.92popo.cc dig

| URL | 结果 | 证据 |
|-----|------|------|
| `http://m.92popo.cc` | **skip**（禁用） | 搜索可出书；`/N/` 分卷无章节列表；webView 正文空 |

## 2026-08-09 — m.yushuwu.asia dig

| URL | 结果 | 证据 |
|-----|------|------|
| `http://m.yushuwu.asia` | **fixed** | dig fake_detail；s.php 404→GET search.php；#readerlist+#YiJianZhan；校验成功 1879ms |

## 2026-08-09 — dxtxt.com → dxtxt.cc

| URL | 结果 | 证据 |
|-----|------|------|
| `http://www.dxtxt.com` | **fixed migrate** | 手机书页 307→`.cc`；搜索 `#sitebox@dl`+POST；校验成功 2354ms；书架 remap |

## 2026-08-09 — 5200xiaoshuo dig

| URL | 结果 | 证据 |
|-----|------|------|
| `http://www.5200xiaoshuo.com/` | **fixed**（无搜索） | dig diagnose search；搜索空分页壳；发现=首页 lastest；打开路径校验成功 6133ms |

## 2026-08-09 — lianjianxsw dig（官方 dig 路径）

| URL | 结果 | 证据 |
|-----|------|------|
| `http://www.lianjianxsw.com/` | **skip**（禁用） | dig→L2 403→hunt empty；OSINT 无后继；`legado-db-mutate disable` |

教训：oneshot 在 hunt empty 后会 `MCP ensure_session`；MCP 挂掉时会静默卡住。**dig 前/后应用 `mcp-ensure`**；硬停后 kill + closeout，勿干等。

## 2026-08-09 — dig path enforcement (A+B)

Harness: Prefer→MUST dig/diagnose; `deep_active.entry`; `retro fixed` DENY without diagnose
unless `manual_mcp_bypass` + `no_auto:diagnose_transport…`; hook ASK on LegadoMcp debug/save;
`source-cli dig`. Driven by ttkan MCP-first bypass.

## 2026-08-09 — ttkan.co deep diagnose

| URL | 结果 | 要点 |
|-----|------|------|
| `cn.ttkan.co` | **fixed** | 搜索仍返回结果，但 `bookList=.novel_cell` 已空；改为 `.li_first_node`；目录 `full_chapters@div`；校验成功 2254ms |

## 2026-08-09 — wodescw deep diagnose

| URL | 结果 | 要点 |
|-----|------|------|
| `www.wodescw.com` | **fixed**（无搜索） | `/search/` 空壳；sososhu 无命中；打开路径 debug 详情/目录/正文 OK；真实验证须 `checkDiscovery=true` |

**新陷阱**：`checkSearch=false`+`checkDiscovery=false` 时 `BookSourceCheckRunner` 不拉书仍报「校验成功」（~1ms）。已修：(1) runner 抛「校验项为空」；(2) `legado_mcp` 强制开发现；SKILL `check_search_discovery_both_off_vacuous`。历史若干「无搜索 fixed」若只跑了双关，证据不足——以 `debug_source(bookUrl)` 或发现校验为准。

## 2026-08-09 batch6 — zbcxw / so.27k / 80xs

| URL | 结果 | 要点 |
|-----|------|------|
| `m.zbcxw.cn` | skip | NXDOMAIN；hunt/OSINT 无后继；已禁用 |
| `so.27k.net` | skip | parking 壳；so+www 已禁用；书架 13 换源 |
| `www.80xs.la` | **fixed migrate** | →`wap.80ge.info`；须 SQL INSERT 进同一份 DB 再 push，忌 MCP save 后推旧快照 |

### Session harness lesson (speed + DB wipe)

| 问题 | 根因 | 落实 |
|------|------|------|
| push 冲掉新源 | 整库覆盖 + 缺 WAL / 旧快照 | `pull` WAL-aware；`legado-db-mutate`；trap `adb_db_push_stale_snapshot_wipes_source`；hook ASK |
| 空等很久 | `sleep×N`、死域长 timeout、整段 Await | `wait_check_done`；probe timeout≤12s |
| 详见 | | `docs/postmortem/2026-08-09-adb-db-push-wipes-mcp-source.md` |

## 2026-08-09 batch5 — shuquge / sodu / iyueba

| URL | 结果 | 要点 |
|-----|------|------|
| `shuquge.co` | skip | nginx 444；品牌孪生超时 |
| `sodu.info` | skip | 403；soduzw 超时 |
| `iyueba.net` | **fixed** | 搜索验证码；修 TOC/正文，打开路径 OK |

## 2026-08-09 batch4 — 147xs / 16kbook / 31xs

| URL | 结果 | 要点 |
|-----|------|------|
| `147xs.org` | skip | http 404；https 广告跳转壳 |
| `16kbook.co` | **fixed migrate** | →`.net`；`article@html` 校验成功 |
| `31xs.net` | skip | 403；`.com` 搜死 + 旧 ID 错书 |

## 2026-08-09 batch3 — dajiadu8 / baimashuwu / 18ys / 猫眼

| URL | 结果 | 要点 |
|-----|------|------|
| `dajiadu8.com` | skip | NXDOMAIN；`.cc` 假友错书 |
| `baimashuwu.com` | skip | 「精选推荐」+gg_card；gate 假 verify |
| `m.18ys.net` | skip | 502 品牌簇全死；新 trap `l2_502_brand_cluster_dead` |
| `download.maoyankanshu.la` | **fixed** | 标签域死、API 活；`checkKeyWord=万世书` 清「搜索失效」 |
| `www.wuxianxs.cc` | skip | 403；同名站假友 |
| `m.75zwz.com` | **fixed** | 清陈旧「发现失效」 |
| `m.shuqixs.cc` | skip | CF Just a moment / 403 |
| `m.shuhui8.cc` | skip | →shuhui9 后 search 空 + verify.html 墙 |

## 2026-08-09 tongrenquan.org — apex DNS 死、m. 活（known trap）

| 项 | 结果 |
|----|------|
| 症状 | `www.tongrenquan.org` / apex **无 A**；备注 `Unable to resolve host`；书架 4 本 |
| Gate | `l1_unreachable` → hunt |
| Hunt | `https://m.tongrenquan.org/` L2 200；路径 `/tongren/{id}.html` 同书可开 |
| 设备 | `m.tongrenquan.org` **校验成功**（1335ms）；debug 搜索+641章+正文 OK |
| 动作 | 书架 4 remap → m；www+apex `enabled=0` |
| Trap | `apex_no_a_try_m` + 新行为 trap `gate_hunt_deferred_unprobed` |

### 错判根因（为何像「要 skip」）

1. 分流只跑 `gate`，把 `action=hunt` 丢进 **maybe_hunt / 以后再挖**，口头像放弃。  
2. **没当场** `hunt --probe`——而 `domain_hunt_seeds.json` **早已**写着 `tongrenquan → m.tongrenquan.org`。  
3. `Unable to resolve host` 被当成整站死，而不是「这个 hostname 死了、孪生子域可能还活」。

### 已落的防再犯

| 层 | 改动 |
|----|------|
| SKILL | trap `gate_hunt_deferred_unprobed`；强化 `apex_no_a_try_m` / `shallow_unfixable_claim` |
| 脚本 | `scripts/shelf-stale-tag-triage.py`：hunt 必须 probe，migrate 升优先 |
| closeout | `trap_in_skill` 先匹配原始 underscore slug（修 `apex_no_a_try_m` 被当成 novel） |
| 纪律 | `book-source-repair-discipline`：失效标签分流禁止未 probe 的 hunt 缓修 |

## 2026-08-09 00txs.com（九桃）— timeout，无活孪生

| 项 | 结果 |
|----|------|
| Gate | `l1_unreachable`（TCP timeout；DNS 有 A=`85.208.118.160`） |
| Hunt | empty（含种子 11/9/33txs、9txs.org、9taoxs） |
| OSINT | Wayback 有历史快照、**无 redirect 新域**；crt.sh 502 |
| 假孪生 | `9txs.org` for-sale 壳；`9taoxs` analytics 空壳（书路径同壳） |
| 手机 | debug 45s 无响应；HTTP 日志 `11txs` 亦 IOException~60s |
| 动作 | `http://00txs.com` + `/` **enabled=0**；书架 4 本靠自动换源 |
| Trap | `host_phone_timeout_no_mirror`（known） |

## 2026-08-09 yjcat.com → ibiquxs.org（CF 跳转孪生）

| 项 | 结果 |
|----|------|
| Gate | `l2_bot_shell`（403 CF；`final_url=http://www.ibiquxs.org/`） |
| 证据 | PC：`yjcat` 首页/书路径 302 到 ibiquxs；书页 TOC 可读；搜索 `modules/article/search.php` OK |
| 设备 | 新源 `http://www.ibiquxs.org` **校验成功**（~1–3s）；debug 搜索 100 / 目录 1457 / 有正文 |
| 书架 | 4 本 origin remap（bookUrl 本已在 ibiquxs） |
| 动作 | `yjcat` + `yjcat#` disable |
| Trap | `主机跳转`（CF 壳勿当终局 skip；跟 final_url / 已有书路径） |

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
- Verify: 当时写了 `checkSearch=false` `checkDiscovery=false` →「校验成功」——**现已认定为空跑假成功**（trap `check_search_discovery_both_off_vacuous`）。有效证据应是 `debug_source(bookUrl)` 打开路径，或 `checkDiscovery=true` 且 duration 非毫秒级。
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

- **首轮**：PC timeout / 手机连不上 38.55.186.43:80；hunt empty；`gegedang.com` 官网壳。
- **再挖（2026-08-09）**：`:80` 仍超时；**https** 通但整站已换成「6080新视觉影视」；手机打开 `/ggd/82390/` → 301 `6080xsjys.com`，目录空。
- 格格党孪生 `ax81.com` 同路径 `/ggd/82390/` 是**另一本书**（极品道士下山≠修仙从华娱开始），不可 remap。
- http+https 均已禁用；书架 6 靠自动换源。Trap: `name_similar_video_not_novel_twin`。


## 2026-08-09 m.boshishuwu.com deep dig

- **首轮**：`.com` 301→`m.boshishuwu.net`；手机 http/https 均 `PROTOCOL_ERROR`（StreamReset）。
- **再挖**：手机打开 `.net/boshi/0_536/` 仍 `StreamResetException` + Cronet `ERR_HTTP2_PROTOCOL_ERROR`；PC curl/Python TLS `RemoteDisconnected` / schannel close；`hunt --probe` empty。
- 无可用镜像。已禁用 `https://m.boshishuwu.com`、`https://m.boshishuwu.net`、`http://m.boshishuwu.net`；书架 6 靠自动换源。
- Trap: `stream_protocol_error_reset`。


## 2026-08-09 hunt OSINT + Wayback rate-limit harness

- 问题：deep dig 迁域只靠 `hunt --probe` / 零散 curl；Wayback 连发易 429；Google/crt.sh/付费档案未进纪律。
- 落地：`scripts/lib/wayback_cdx.py`（默认 12s 间隔 + 429 退避 + 共享锁）；`scripts/domain-successor-hunt.py`；指南 `docs/guides/domain-successor-osint.md`。
- Skill trap `hunt_osint_skipped`；discipline §5c/§12b；`legadoSkill/docs/domain-hunt-trial-2026-07-26.md` 同步。

## 2026-08-09 www.qinqinxsw.cc deep dig

- Gate L2 `UnknownIssuer`（LE YE1）；curl 仍能拉到 ~4.7KB HTML，title「Redirecting…」，正文含 `ad-overlay` / prebid（非小说页）。
- 首页 / 搜索 / `book/…` / `m.…/324_…/` 同壳；手机搜索「获取成功但书籍总数:0」；打开详情后 `TocEmptyException`。
- `hunt --probe`（含 `.com` / `qinqinxs.cc` seeds）empty；`.com` 手机 `SSLV3_ALERT_HANDSHAKE_FAILURE` / `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`。
- 已禁用 `https://www.qinqinxsw.cc/`、`https://m.qinqinxsw.cc/`、`https://www.qinqinxsw.com/`；书架靠自动换源。
- Trap: `known:域名停车/过期`（Redirecting shell）。

## 2026-08-09 www.wbxs.org deep dig

- Gate L1 通、L2 `tls connection init failed: corrupt message`；备注已有 `Unable to parse TLS packet header`。
- PC：443 TCP 通但 TLS `record layer failure`；HTTP 被本机拦到 `safebrowse.io` 威胁页。
- 手机：搜索与打开 `/a/184943` 均 `SSLException: Unable to parse TLS packet header` + Cronet `ERR_SSL_PROTOCOL_ERROR`。
- `hunt --probe` empty；同名「完本」活站（ghost580 / wanbenshenzhan / book15）路径 `/a/{id}` 均 404，不可 remap。
- 手机 DB `enabled=0`；书架 6 靠自动换源。
- Trap（新）：`tls_packet_header_corrupt` → SKILL + `no_auto:tls_origin_corrupt`。

## 2026-08-09 hongxiud.com deep dig（再挖）

- Gate 对 `https://` 报 `l1_unreachable`（TCP timeout）；`hunt --probe` empty。
- **HTTP 通**：`http://(www.|m.)hongxiud.com/`、`/search.aspx`、书架书 URL（如 `/KG21bN.html`）全部同一 ~1985B 色情广告壳；title「美女日韩一区_你懂的网址…」。
- 手机：`https` debug 45s 卡搜索；`http` debug 301→www 后「列表为空/书籍总数:0」。
- `hongxiu.com`=阅文「红袖读书」，**非**小说源孪生，不可 migrate。
- 手机 DB 将 `http://hongxiud.com` + `https://hongxiud.com` 置 `enabled=0`（此前文档写已禁用但源仍开着）。书架 5 靠自动换源。
- Trap: `known:域名广告劫持 (pyzht)`。


## 2026-08-09 m.92yanqing.com deep dig（再挖）

- Gate `l1_unreachable`；`m`/`www`/apex 的 http+https 首页、POST `/search/`、书架 `/read/70675/` **PC 全 timeout**。
- 手机：搜索 debug 35s 无响应；打开详情 `read/70675/` 30s 无响应。
- `m.92yanqing.net` NXDOMAIN（库内已禁用）；hunt empty；旁路 `92yq.com`=「你好乐清」门户，`/read/…` 404，非孪生。
- 手机 DB 将 `https://m.92yanqing.com` 置 `enabled=0`（此前文档写已禁用但源仍开着）。书架 5 靠自动换源。
- Trap: `known:host_phone_timeout_no_mirror`。

## 2026-08-09 www.00ksw.com deep dig

- PC/手机均 TCP hang；DNS alias `23.224.148.*` via uucdn；`hunt --probe` empty。
- 候选 `00ksw.cc`=XDNS 威胁页、`.org`=广告 redirect、`00ks`/`ldks` 停车。手机 DB `enabled=0`；书架 5 靠自动换源。
- Trap: `host_phone_timeout_no_mirror`。Ledger：`legadoSkill/temp/full_fix/repair_session_ledger.jsonl`（2026-08-09T04:05–04:06）。
- 注：本机 APK `save_source` 默认保留 enabled（响应无 `enabled=` 行）；本批用 `legado_adb` 拉库改 `enabled=0` 后推回。

## 2026-08-09 www.ffxs8.top deep dig

- `.top` NXDOMAIN；孪生 `https://www.ffxs8.com` 站内搜索恒「没有搜索到相关的内容」，但打开路径可读。
- 设备证据：MCP `debug_source` 详情/目录182/正文 OK；`start_check_sources`（`checkSearch=false`）→ `校验成功`（ledger `https://www.ffxs8.com` @ 2026-08-09T04:08:52）。
- 书架 remap 在**手机 DB**（非 `backup_now/bookshelf.json`）：`.top` origin 剩 0；`https://www.ffxs8.com` 含原 `/dsyq/21029` 等；`.top` `enabled=0`。
- Trap: `search_empty_shell_open_ok`（`.com` 打开路径）；`.top` 结果标签 **fixed migrate**。

## 2026-08-09 http://www.b520.cc deep dig（再挖）

- 首页 CF 通（DNS 已非 23.224）；`modules/article/search.php` 与书架 5 本路径全 **404**。
- 仍在架书（如 `/2_2157/` 太古神王）：详情/og OK；TOC `dd a` 共 2268，真链仅约 180（~8%），其余 `href="/"`；稀疏真链正文可读。
- 孪生 `biquge5200.cc` / `b5200.org` / `m.biquge5200.cc`：手机 GET → IOException ~35s（仍 23.224）；`hunt --probe` empty。
- 已禁用；书架靠自动换源。Trap: `toc_href_slash_twin_unreachable`。

## 2026-08-09 https://m.lrxs.org deep dig（再挖）

- 首页仍西语占位：`title=Web accesible`，`meta refresh`→Google；`/9_9146/` 等书架路径与 `/s.php` 均 404。
- 同名孪生 `http://www.lrxsw.org`：PC/手机 **CF 522**（搜索/打开空）；非可迁目标。`xinqishuxs` 路径不兼容。
- `hunt --probe` empty。已禁用；书架 6 靠自动换源。
- Trap: `placeholder_web_accesible_google`（SKILL）。

## 2026-08-09 www.dubu123.com deep dig

- **首轮（浅）**：NXDOMAIN；`dubuxs.cc` 威胁页；误把 `dbxsn.com` 首页 404 当整站死 → skip。
- **再挖（2026-08-09 下午）**：`https://www.dbxsn.com` 首页仍 404，但书架同路径 `/book/p18753/` 等 200（og:novel+目录），`/plus/search.php` 有结果；手机 301→`www.dbxsz.com`。
- 设备：`debug_source` 详情/目录/正文 OK；搜索「斗破」4 本；`start_check_sources` → **校验成功**（`https://www.dbxsn.com`）。
- 动作：启用 `dbxsn`、书架 4 remap、`dubu123` 保持禁用。
- 可核对证据：手机 DB 快照 `legado/temp/full_fix/cache/dubu_migrate_proof.json`（`dbxsn enabled=1`，4 本 `origin=https://www.dbxsn.com`，`dubu123` 书架 0）。
- Ledger：`dbxsn`=`校验成功`；误写在 `dubu123` 上的 `校验成功` 已 retract 为 `skip:ledger_retract_false_success`。
- Trap: `home_404_paths_alive`（SKILL + `source-gate/sniff.rs` 去掉裸 404→parked）。

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

### 2026-08-09 PO文屋 https://www.powenwu1.com (toc related-list)
- Scan: 启用源里同模板 POST search.php 共 73 host；**双 list-charts 黄链污染仅 jile1 + powenwu1**
- jile1: 已 `.0` + 校验成功（见上条）
- powenwu1: `chapterList` → `class.list-charts.0@li@a`（已 save）；PC HTML 块=[48,6]→取首块 48
- Verify: 手机 Cronet **ERR_TIMED_OUT**（源分组含「网站失效」）→ **未**宣称校验成功；ledger `fail:phone_timeout`

### 2026-08-09 PO18文学 https://www.jile1.com (toc related-list)
- Trap: `multi_list_charts_toc` — 双 `ul.list-group.list-charts`；第二块是相关推荐黄链（非「最新几章」）
- Fix: `ruleToc.chapterList` → `class.list-charts.0@li@a`（只取第一块正文目录）
- Debug: 目录总数 **48**，最新/末章「第四十八章 显露真身」（不再吞推荐位）
- Verify: `start_check_sources` → **校验成功** (~2.3s, keyword=我的)
- Note: 换源 tocIdentity 降权仍保留作产品护栏；本修只清这家脏目录
