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

说明：浅层 gate/serial「搜不了=修不了」不可信；本轮以 HTML+手机 debug/HTTP 日志为准，能开书就按打开路径修。结构化 ledger/retro 在 `legadoSkill/temp/full_fix/repair_session_ledger.jsonl` 与 `repair_serial_retro.jsonl`（勿只看过期的 `temp/shelf_restore/backup_now/bookshelf.json`）。**教训**：孪生源勿只看首页——`dbxsn` 首页 404 曾被误判死站（trap `home_404_paths_alive`）。
