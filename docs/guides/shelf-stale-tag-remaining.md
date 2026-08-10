# 书架「失效」标签 — 剩余可修候选（2026-08-09）

在 top-10 抽查队列关门之后，从手机 DB 重扫：**仍启用**、备注/分组含 `Error` / `失效`、且书架有书的源。

| 指标 | 数量 |
|------|------|
| 带失效标签且有书架书 | 151 源 / 749 书 |
| 其中仍启用 | 145 源 |
| 已排除本轮已关门 top-10 相关 URL 后 | **145 源 / 356 书**（大头书在已 disable 的 69shu/UU/aixdzs 等上，不计入「仍启用可修」） |

运行时全量 JSON：`temp/shelf_restore/queue/stale_tag_remaining_candidates.json`  
Top30 gate：`temp/shelf_restore/queue/stale_tag_remaining_gated_top30.json`  
URL 列表（便于 `source-cli serial`）：`temp/shelf_restore/queue/stale_tag_fixable_priority.urls.txt`

## 优先可修（gate = verify，站还活着）

这些 **L0/L1/L2 已过**：更像是规则/搜索层坏了，值得 deep diagnose，而不是死站。

| # | 书架本数 | 名称 | bookSourceUrl |
|---|---------|------|---------------|
| 1 | 6 | 懒人小说 | `https://m.lrxs.org` |
| 2 | 5 | 三一小说 | `http://www.31xs.com` |
| 3 | 5 | 笔趣阁 | `http://www.b520.cc` |
| 4 | 5 | 天天书吧 | `https://m.ttshu8.com` |
| 5 | 4 | 海棠书屋 | `https://haitang123.net` |
| 6 | 4 | 微风小说 | `https://m.wfxs.tw` |
| 7 | 4 | 起舞中文 | `https://www.75zwz.com/` |
| 8 | 3 | 看书神 | `http://apitt.kanshushenapp.com/` |

## 需 hunt / 可能迁站（gate = hunt）

本机连不上或 L2 死，但按纪律应先 `hunt --probe`，有活镜像再迁。按书架本数前几：

| 本数 | 名称 | URL | gate |
|------|------|-----|------|
| 15 | 起舞中文 | `https://m.75zw.com` | l1_unreachable（注意：同系 `75zwz.com` 上面 verify 过） |
| 13 | 番茄小说 | `http://154.37.154.143` | l1_unreachable |
| 11 | 一读小说 | `https://www.121ds.cc/` | l1_unreachable |
| 11 | 乐阅读 | `https://www.27k.net` | l1_unreachable |
| 10 | 八毛小说网 | `http://www.bamxs.com` | l1_unreachable |
| 8 | 无限小说 | `http://www.wuxianxs.cc` | l2_http_dead |
| 8 | 69书xyz | `https://www.69shu.xyz` | l2_http_dead |
| … | （其余见 gated JSON） | | |

## 暂不建议深挖（CF / 停车页）

| 本数 | 名称 | URL | 原因 |
|------|------|-----|------|
| 9 | UU小说 uu234 | `http://www.uu234.net` | CF redirecting shell |
| 5 | 书屋小说网 | `http://www.shu05.com` | 域名停车 |
| 4 | 手打吧 | `https://m.shouda88.com/` | 域名停车 |
| 4 | 爱读小说 | `https://www.ixpsge.com/` | CF challenge |

## 已关门（勿重复排进优先队列）

见 [`shelf-stale-tag-source-queue.md`](shelf-stale-tag-source-queue.md)：trxs / 乐文 / 爱下→ixdzs8 / 69shu→69shuba.com，以及 UU.com DNS loopback、81zw CF、lrxsw 522、xxbiqudu TLS 等 skip。

## 建议下一轮顺序

1. **不要**再只跑 `gate` 就把 `action=hunt` 丢进「maybe later」。用：  
   `python scripts/shelf-stale-tag-triage.py --candidates temp/shelf_restore/queue/stale_tag_remaining_candidates.json --limit 30`  
   （hunt 会当场 `--probe`；migrate 候选进优先 URL 列表）
2. 优先挖 `stale_tag_fixable_priority.urls.txt`
3. CF/停车页保持 skip，靠自动换源  

Trap：`gate_hunt_deferred_unprobed`（tongrenquan 缓修教训）。

书架上仍挂在**已禁用**源上的书（如 69shu 325、UU 44）不算「源还可修」，靠自动换源。

## Deep diagnose 本批 9 源（2026-08-09）

| URL | 结果 | 证据 |
|-----|------|------|
| `https://m.wfxs.tw` | **fixed / 校验成功** | 搜索改 `/s/?q=`+`.result-card`；目录 `/booklist/{id}/1.html` `#html_box`；正文 `#read_conent_box` |
| `https://m.75zw.com` | **fixed migrate** | DNS 已 NXDOMAIN；→ `https://m.75zwz.com`（打开/目录/正文 OK；搜索仍返回热门假结果）；书架 15 remap |
| `https://www.75zwz.com/` | **fixed**（无搜索） | 混淆字段仍空壳；打开路径校验成功；trap `search_empty_shell_open_ok` |
| `https://m.lrxs.org` | skip（深挖确认） | 占位页「Web accesible」→Google；hunt empty；已禁用 |
| `http://www.31xs.com` | **fixed**（无搜索） | 见下方 deep dig：正文 `qsbs.bb`；发现/打开校验成功 |
| `http://www.b520.cc` | skip（深挖） | TOC `href="/"`；孪生手机不可达；见下方 |
| `https://m.ttshu8.com` | **fixed**（无搜索） | POST 搜索 500；打开路径校验成功；见下方 |
| `https://haitang123.net` | **fixed migrate** | 原站超时；用已有 `https://m.haitang4.com`（校验成功）；旧源 disable |
| `http://apitt.kanshushenapp.com/` | skip（深挖确认） | 跳转 gegedangbook 超时；`api_search` 404；hunt empty；已禁用 |

串行日志：`temp/shelf_restore/queue/serial_9.log`

## 2026-08-09 deep dig (session)

| URL | Result | Evidence |
|-----|--------|----------|
| `http://www.31xs.com` | **fixed**（无搜索） | 搜索 meta refresh 回首页；正文 `qsbs.bb` base64；发现/打开校验成功 |
| `http://www.b520.cc` | **skip** | 再挖：首页通但 search+书架5本全 404；TOC `href="/"`≈92%（样例 2268→180 真链）；孪生 `biquge5200.cc`/`b5200.org` 手机仍 23.224 超时；hunt empty；已禁用 |
| `https://m.ttshu8.com` | **fixed**（无搜索） | POST `searchkey`→500；打开/目录/正文 OK，`checkSearch=false` 校验成功 |
| `https://m.lrxs.org` | **skip** | 再挖：仍 `Web accesible`→Google；书架6+搜索 404；孪生 `lrxsw.org` CF **522**；hunt empty；已禁用 |
| `http://apitt.kanshushenapp.com/` | **skip** | api_search 404；gegedangbook 超时；hunt empty；已禁用 |
| `https://www.121ds.cc/` | **skip** | 23.224 超时；.com 影视非孪生；hunt empty；已禁用 |
| `https://www.27k.net` | **skip** | PC/手机 60s timeout；hunt empty；已禁用 |
| `http://www.bamxs.com` | **skip** | 23.225 超时；hunt empty；已禁用 |
| `http://www.wuxianxs.cc` | **skip** | 首页 403；hunt empty；已禁用 |
| `https://www.69shu.xyz` | **skip** | CF 520；孪生 69shuba 已启用；已禁用 |
| `http://154.37.154.143` | **skip** | IP 超时；xhsxsw Host 停车壳；已禁用 |
| `http://www.qingzichan.net` | **skip** | 再挖：http 超时；https 已成 **6080影视** 壳（301→6080xsjys）；`/ggd/` ID 在 ax81 错书；hunt empty；http+https 已禁用 |
| `https://m.boshishuwu.com` | **skip** | 再挖：仍 `StreamReset PROTOCOL_ERROR` / Cronet `ERR_HTTP2`（.com→.net 同）；PC `RemoteDisconnected`；hunt empty；.com+.net 已禁用 |
| `https://hongxiud.com` | **skip** | 再挖：HTTPS 超时；HTTP 全路径色情广告壳（搜索/书 URL 同壳）；phone 列表空；hunt empty；http+https 已禁用 |
| `https://m.92yanqing.com` | **skip** | 再挖：PC/手机首页+搜索+详情全超时；hunt empty；.net NXDOMAIN；已禁用 |
| `https://www.wbxs.org` | **skip** | TLS 握手损坏（`Unable to parse TLS packet header` / `ERR_SSL_PROTOCOL_ERROR`）；hunt empty；已禁用 |
| `https://www.qinqinxsw.cc/` | **skip** | JS「Redirecting…」广告壳；搜索/目录空；孪生 `.com` TLS 失败；hunt empty；www/m .cc +.com 已禁用 |
| `https://www.00ksw.com` | **skip** | PC/手机 TCP hang（DNS→23.224 uucdn）；hunt empty；假镜像停车/威胁页；手机 DB `enabled=0`；书架 5 靠自动换源 |
| `https://www.ffxs8.top` | **fixed migrate** | NXDOMAIN；孪生 `https://www.ffxs8.com` debug 打开 OK + MCP `checkSearch=false`→`校验成功`（ledger）；手机 DB 书架 5 remap（`.top` 剩 0，`.com` 含原路径）；`.top` `enabled=0` |
| `https://www.dubu123.com` | **fixed migrate** | NXDOMAIN 仍在；孪生 `https://www.dbxsn.com`（首页 404 但 `/book/p*`+搜索活；301→`dbxsz`）；MCP debug+`keyword=斗破` **校验成功**；书架 4 remap（证据 `temp/full_fix/cache/dubu_migrate_proof.json`）；`.com` 已禁用 |
| `https://www.tongrenquan.org` | **fixed migrate** | www/apex **无 A**（DNS 失败）；`hunt --probe` → `https://m.tongrenquan.org/`（手机已有启用「同人圈」）；路径 `/tongren/{id}.html` 可开；设备校验 **校验成功**（1335ms）；书架 4 本 origin+bookUrl remap；www+apex `enabled=0`。Trap：`known:apex_no_a_try_m` |
| `http://00txs.com` | **skip**（已禁用，含 `http://00txs.com/`） | PC/手机 TCP **timeout**（DNS 有 A）；`hunt --probe` empty；OSINT Wayback 无 redirect；`9txs.org`=for-sale 壳；`9taoxs`/`m.9taoxs`=analytics 空壳；`11txs`/`33txs`/`9txs.com` 超时。书架 4 本靠自动换源。Trap：`host_phone_timeout_no_mirror` |
| `http://www.yjcat.com` | **fixed migrate** | Gate `l2_bot_shell`（CF Just a moment → `http://www.ibiquxs.org/`）；书路径已在 ibiquxs；克隆源 `http://www.ibiquxs.org` 设备 **校验成功**（搜索/1457章/正文）；书架 4 本 remap；`yjcat`+`yjcat#` `enabled=0`。Trap：`主机跳转` |
| `https://www.tcknh.com` | **skip**（已禁用，含 `m.tcknh.com`） | DNS **NXDOMAIN**；hunt empty；OSINT 无 redirect；`xiakexs.com` 同名活站但 `/book/{id}` 与旧 `/novel/{id}` **不是同一本书**，书架书名搜索空。书架 3 靠自动换源。Trap：`dns_nxdomain_hunt_empty` |
| `https://www.qqduw.com` | **skip**（已禁用） | DNS **A=127.0.0.1**（sinkhole）；hunt empty；`quduxsw` TLS/威胁页；`quduw.com`/`quduxs` 403。书架 3 靠自动换源。Trap：`known:dns_loopback` |
| `https://www.iqushuwang.com/` | **skip**（已禁用） | `.com` 首页通但 **search POST 404**；书 URL 在 `.cc`；手机连 `.cc`→`23.224.*` **ENETUNREACH**（PC 见 WAF CAPTCHA）。书架 3 靠自动换源。Trap：`toc_href_slash_twin_unreachable` |
| `https://m.paipaiwx.com` | **fixed**（无搜索） | search POST 字段 `searchkey`→`369koolearn` 仍恒返回热搜空壳（m/www 同）；debug 详情/目录292/正文 OK；`checkSearch=false`→校验成功；书架 3 保留。Trap：`search_post_hot_shell` |
| `https://m.feibzw.com/` | **skip**（已禁用 m/www/`#` +试验 `fsuzw`） | TLS `record layer failure`；`feisuzw` 公告→`https://www.fsuzw.com`（同 `/book-{id}/` 详情/目录通）；正文 YHFixed **每章换 woff2** 字体加密，Legado 解不开；搜索 0。书架靠换源。Trap：`tls_packet_header_corrupt` / `content_font_encrypt_dynamic` |
| `https://m.6yzw.com/` | **fixed migrate** | 跳转 `https://www.lzshu.cc`；新源「六月中文网」校验成功（search=`/search.php?q=`，TOC=`.book_list2`+`index_N`）；旧 `/N_ID/` **错书**不可直 remap；书架 2/3 按书名 remap（`84_84198`/`10_10911`）；`龙珠…` 无同名仍挂旧 origin。`m.6yzw` disabled。Trap：`主机跳转` |
| `http://www.xiaoshuozu.cc` | **fixed** | `s.php` 404→`/search.php?q=`（lzshu 同模板）；https 源「小说族」校验成功；旧 `/shu/{id}/` 错书；仅「大唐开局震惊李世民」精确 remap→`/shu/92982/`；另 2 本无精确同名靠换源。http 旧源 disabled。 |
| `http://www.shatanxs.com/` | **skip**（含无尾斜线已禁用） | CF `Attention Required` **403**（PC/手机详情+搜索同）；`hunt --probe` empty；OSINT 无后继（crt 仅本域）。书架 3 靠自动换源。Trap：`http_403_home_hunt_empty` |
| `http://www.kanquanben.org` | **skip**（已禁用） | DNS **NXDOMAIN**；hunt empty；`.com` CF403；`.net`/`m.` 广告 Redirecting 壳；`kqbxs` 威胁页。书架 3 靠换源。Trap：`dns_nxdomain_hunt_empty` |
| `http://m.hetunxs.com` | **skip**（已禁用） | CF `Attention Required` **403**（PC/手机同）；hunt empty；OSINT 无后继。书架 3 靠换源。Trap：`http_403_home_hunt_empty` |
| `https://www.xinshuw.com` | **skip**（已禁用） | gate migrate→`.cc` 是 **Z-Blog 媒体假友**（旧 `/novel_*`/搜索 404）；OSINT `xinshuw.net` 手机 POST **CF 522**；`hunt --probe` empty。书架 2 靠换源。Trap：`migrate_false_friend_cms`（初记 `主机跳转`） |
| `https://quark.sm.cn/` | **skip**（含孪生已禁用） | DOM 改 `qk-title-text` 后能搜到书，但首条第三方 `maoshu520` **403**/目录空；聚合源无稳定本站 TOC。书架 3 靠换源。Trap：`meta_search_third_party_toc_dead` |
| `http://www.huanxiangji.com` | **fixed migrate** | → `https://www.huanxiangji.net`；桌面 UA **403 blocked**，改 Android Mobile UA；TOC `.section-list`；搜索 POST 仍 403；`checkSearch=false`+发现 **校验成功**。书架 2 origin remap（站内无同名精确书靠换源）。Trap：`desktop_ua_blocked_mobile_ok` / `主机跳转` |
| `https://www.shenyekanshu.com` | **skip**（已禁用） | `Loading...`+JWT `?js=` 壳；webView→`ovret.com` 广告；`ww547` 品牌壳非书站；hunt/OSINT 无后继。书架 2 靠换源。Trap：`js_loading_jwt_ad_hijack` |
| `https://www.wfxs.tw` | **fixed** | 旧搜 `m…/s.html` 空壳；同步 `m.wfxs.tw` 的 `/s/?q=`+`.result-card`+booklist/正文规则；设备 **校验成功**；书架 3 保留 www。Trap：`search_empty_shell_open_ok` |
| `http://www.ffxs8.com/` | **fixed** | 备注 Error 过期；POST 搜索→https 结果；目录253+正文 OK；设备 **校验成功**。Trap：`search_empty_shell_open_ok`（stale tag） |
| `https://m.xxbiqudu.com/` | **skip**（m/www/# 已禁用；2026-08-09 再挖确认） | 手机 HTTP 正文明确 **`[xxbiqudu.com] is for sale`（4.cn）**；搜索/书架 `/N_ID/` 同出售壳（~1781B）；`hunt --probe` empty；OSINT/crt 无后继；`xxbiquge*` TLS 超时。书架靠换源。Trap：`域名停车/过期` |
| `https://m.mozhua2.com` | **skip**（已禁用；2026-08-09 再挖确认） | 全路径 **401**（`<meta refresh>`/`loading` WAF）；UA/cookie/webView 无效；手机 POST **TLS packet header**；孪生 `mozhua.app/2wxh/juqisw/dizhuwu/luhubook` 同壳；hunt/OSINT 无后继。书架 2 靠换源。Trap：`waf_401_meta_refresh_cluster` |
| `http://www.530p.com/` | **skip**（含孪生已禁用；2026-08-09 再挖确认） | 手机/PC 正文明确 **4.cn 出售**（一口价 CNY 11178，「域名可以转让」）；首页/搜索/书架书 URL 全是同停车壳；`hunt`/OSINT 无后继；`3zwx.com` 是「三者文学网」假友非同站。书架 4 靠换源。Trap：`域名停车/过期` |
| `https://www.dajiadu8.com` | **skip**（已禁用） | DNS **NXDOMAIN**；hunt empty；`dajiadu.cc` 同路径 **错书假友**。书架靠换源。Trap：`dns_nxdomain_hunt_empty` |
| `https://www.baimashuwu.com/` | **skip**（已禁用） | gate 假 verify；title「精选推荐」+`gg_card` 广告劫持；search 404；OSINT 无后继。Trap：`域名广告劫持` |
| `http://m.18ys.net` | **skip**（已禁用） | L2/书架书 **502**；`www.18ys.com` lander；`ixs.cc` NXDOMAIN、`ixs5200` 502；hunt+OSINT 无活后继。Trap：`l2_502_brand_cluster_dead` |
| `http://download.maoyankanshu.la` | **fixed** | 标签域 NXDOMAIN，但 comment JS→`api.longchunbajiao.com` 活；「我的」API 空、「万世书」通；设 `checkKeyWord=万世书`→**校验成功**。Trap：`check_keyword_too_broad` |
| `http://www.wuxianxs.cc` | **skip**（已禁用） | PC/手机首页+书 URL **403**；hunt empty；OSINT `wuxianxiaoshuo`/`wuxianxs123` 同名假友（路径 `/books/` 且无精确书架书名）。书架 8 靠换源。Trap：`http_403_home_hunt_empty` |
| `https://m.75zwz.com` | **fixed** | 仅「发现失效」陈旧标签；`checkDiscovery=false`+keyword=斗破 **校验成功**（搜索/目录/正文 OK）。Trap：`search_empty_shell_open_ok` |
| `http://m.shuqixs.cc` | **skip**（已禁用；www 早已 disabled） | gate `l2_bot_shell`（Just a moment）；手机搜索 **403**；webView/jar 无效；hunt/OSINT 无后继。书架 2 靠换源。Trap：`CF 空搜索体` |
| `https://m.shuhui8.cc` | **skip**（已禁用；含 .com） | gate migrate→`m.shuhui9.cc`；搜索 API 恒 `[]`；www TOC 有章但章节→`/user/verify.html` 加载壳、正文空。书架 3 靠换源。Trap：`chapter_verify_html_wall` |
| `http://www.147xs.org/` | **skip**（http+https 已禁用） | http L2 **404**；https→`gwiver.bond` 安全检测跳转壳；phone search 404；hunt/OSINT 无后继。书架 2 靠换源。Trap：`域名广告劫持` |
| `https://m.75zwz.com` | **fixed**（batch4 再清） | 「发现失效」标签复发；`checkDiscovery=false` 再验 **校验成功** |
| `http://www.16kbook.co` | **fixed migrate** | `.co` unexpected EOF；→`http://www.16kbook.net`（`/search.php?q=` + `article@html` + `.book_list2`）；设备 **校验成功**；书架「游戏面板」remap→`/1/1137/`；「长生…」站内无同名靠换源；`.co` disabled。Trap：`主机跳转` |
| `http://www.31xs.net` | **skip**（net/#/com 已禁用） | `.net` **403**；书架书在 `.com` 但同路径 **错书**；搜索 404/`search.html`→首页；hunt/OSINT 无后继。书架 8 靠换源。Trap：`fake_detail` |
| `http://www.shuquge.co` | **skip**（已禁用） | L2/手机 **444**；hunt empty；`ishuquge.la`/wap 亦超时；书架 2 靠换源（1 本已在 ishuquge）。Trap：`known:l2_http_dead` |
| `http://www.sodu.info` | **skip**（已禁用） | 首页+搜索 **403**；hunt empty；`soduzw` 超时。书架 2 靠换源。Trap：`http_403_home_hunt_empty` |
| `https://iyueba.net` | **fixed**（无搜索） | `/z/` 搜索返回**验证码**；详情/目录 `zjml`+`#content_1`、正文 `#booktxt` 修好；打开路径 **校验成功**。Trap：`search_empty_shell_open_ok` |
| `https://m.zbcxw.cn` | **skip**（已禁用） | DNS **NXDOMAIN**（PC/手机）；`hunt --probe` empty；OSINT/书源仓仍指向 zbcxw；`xxshu.com` 等假友非书站。书架 2 靠换源。Trap：`known:dns_nxdomain_hunt_empty` |
| `https://so.27k.net/` | **skip**（so+www 已禁用） | HTTPS TLS EOF；HTTP/www 为 **parking** 壳；书/搜索 404；hunt/OSINT 无后继。书架 13 靠换源。Trap：`known:域名停车/过期` |
| `https://www.80xs.la` | **fixed migrate** | →`http://wap.80ge.info`（PC `www.80ge` TOC 章链挂死域 `qiushu.info`）；wap 搜索/分页目录/`#nr1` 正文 OK；设备校验 **failed=0**；书架 2 remap；`.la` disabled。Trap：`known:主机跳转` |
| `https://www.wodescw.com` | **fixed**（无搜索） | 原生 `POST /search/` 空壳 63B；sososhu meta 无命中；书架书详情/目录/正文 OK；`checkSearch=false`+`checkDiscovery=true`→**校验成功**（1477ms）。首轮双关曾假成功≈1ms → trap `check_search_discovery_both_off_vacuous`。 |
| `https://cn.ttkan.co` | **fixed** | 搜索 DOM 改版：`bookList` `.novel_cell`→`.li_first_node`；`toc` `.full_chapters@div.1`→`@div`；debug 17书/正文 OK；MCP **校验成功**（2254ms）。 |
| `http://www.lianjianxsw.com/` | **skip**（已禁用） | `source-cli dig`：L2 **403** → hunt empty；OSINT crt.sh 502、Wayback 无 redirect、孪生 NXDOMAIN/403/521；Web 仍指本域无后继；`legado-db-mutate disable`；书架 1《我有神级修改器》靠换源。Trap：`http_403_home_hunt_empty`。中间 dig 在 MCP 挂掉后卡在 `ensure_session`，已 `mcp-ensure`。 |
| `http://www.5200xiaoshuo.com/` | **fixed**（无搜索） | dig→diagnose `layer=search`；`search.php?keywords=` 仅分页壳无结果行；书架书详情/目录143/正文 OK；加发现 `exploreUrl=最新::/`+`class.lastest@li!0`；`checkSearch=false`+discovery→**校验成功**（6133ms）。Trap：`search_empty_shell_open_ok`。 |
| `http://www.dxtxt.com` | **fixed migrate** | dig oneshot 改 search 仍失败；手机详情 **307→`https://www.dxtxt.cc/`**；迁站后 `bookList=#sitebox@dl`+POST `keyword`；`checkKeyWord=龙王篓`→**校验成功**（2354ms）；书架 remap；`.com` disabled。Trap：`主机跳转`。 |
| `http://m.yushuwu.asia` | **fixed** | dig `fake_detail`：旧 `s.php` **404**；改 GET `/modules/article/search.php?searchkey=` + `#sitebox@dl`；目录 `text.目录`→`#readerlist`（+下一页）；正文 `#YiJianZhan`；**校验成功**（1879ms）。书架多数挂 `yushuwu.cloud`/eyushuwu，靠换源。Trap：`fake_detail`。 |
| `http://m.92popo.cc` | **skip**（已禁用） | dig 搜索可修（`.list@li`+https）；详情「章节目录」→`/N/` 仅「开始阅读/分卷」，无 `#chapterlist`；webView 正文仍空。书架多本已在 `popofree.com`。Trap：分卷站无标准 TOC。 |
| `https://app.yqzw5.net` | **skip**（已禁用） | dig：首页仅 1052B「Click here to enter」停车壳；hunt empty；OSINT 指向 `yqzw5.com` 但 PC/手机均断连超时；debug 搜索挂起。书架 1《万界最强之光》靠换源。Trap：`known:域名停车/过期`。 |
| `https://www.dbxsn.com` | **fixed migrate** | dig 主机跳转→`https://www.dbxsz.com`（首页 404，`/book/p*` 活）；搜索 `/plus/search.php?q=`；**校验成功**（709ms）；书架 4 本 origin+bookUrl remap；`.com` 旧源 disabled。Trap：`known:主机跳转` / `home_404_paths_alive`。 |

说明：浅层 gate/serial「搜不了=修不了」不可信；本轮以 HTML+手机 debug/HTTP 日志为准，能开书就按打开路径修。结构化 ledger/retro 在 `legadoSkill/temp/full_fix/repair_session_ledger.jsonl` 与 `repair_serial_retro.jsonl`（勿只看过期的 `temp/shelf_restore/backup_now/bookshelf.json`）。**教训**：孪生源勿只看首页——`dbxsn` 首页 404 曾被误判死站（trap `home_404_paths_alive`）。
