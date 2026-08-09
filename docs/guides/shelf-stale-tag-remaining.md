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

1. 上表 **8 个 verify** 源：`diagnose` → 层补丁 → 设备校验  
2. `m.75zw.com`（15 本）→ 尝试迁到已通的 `https://www.75zwz.com/`  
3. 再按书架本数对 hunt 列表跑 `source-cli hunt --probe`  
4. CF/停车页保持 skip，靠自动换源  

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
| `http://www.b520.cc` | **skip** | TOC 几乎全 `href="/"`；孪生 `biquge5200.cc`/`b5200.org` PC 可搜可目录，手机 `23.224.*` Cronet 60s timeout |
| `https://m.ttshu8.com` | **fixed**（无搜索） | POST `searchkey`→500；打开/目录/正文 OK，`checkSearch=false` 校验成功 |
| `https://m.lrxs.org` | **skip** | Web accesible→Google；hunt empty；已禁用 |
| `http://apitt.kanshushenapp.com/` | **skip** | api_search 404；gegedangbook 超时；hunt empty；已禁用 |
| `https://www.121ds.cc/` | **skip** | 23.224 超时；.com 影视非孪生；hunt empty；已禁用 |
| `https://www.27k.net` | **skip** | PC/手机 60s timeout；hunt empty；已禁用 |
| `http://www.bamxs.com` | **skip** | 23.225 超时；hunt empty；已禁用 |
| `http://www.wuxianxs.cc` | **skip** | 首页 403；hunt empty；已禁用 |
| `https://www.69shu.xyz` | **skip** | CF 520；孪生 69shuba 已启用；已禁用 |
| `http://154.37.154.143` | **skip** | IP 超时；xhsxsw Host 停车壳；已禁用 |
| `http://www.qingzichan.net` | **skip** | 连不上；hunt empty；已禁用 |
| `https://m.boshishuwu.com` | **skip** | PROTOCOL_ERROR；301→.net 同死；已禁用 |
| `https://hongxiud.com` | **skip** | 广告/色情劫持壳；已禁用 |
| `https://m.92yanqing.com` | **skip** | 搜索超时；hunt empty；已禁用 |
| `https://www.00ksw.com` | **skip** | PC/手机 TCP hang（DNS→23.224 uucdn）；hunt empty；假镜像停车/威胁页；已禁用 |
| `https://www.ffxs8.top` | **skip migrate** | NXDOMAIN；孪生 `https://www.ffxs8.com` 打开/目录/正文 OK（搜索空壳）；书架 5 remap→.com；.top 已禁用 |
| `https://www.dubu123.com` | **skip** | NXDOMAIN；hunt empty；dubuxs.cc 威胁页 / dbxs123 影视壳；已禁用 |

说明：浅层 gate/serial「搜不了=修不了」不可信；本轮以 HTML+手机 debug/HTTP 日志为准，能开书就按打开路径修。
