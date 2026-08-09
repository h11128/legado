# Shelf「失效」标签源 — 抽查与逐个修复队列（2026-08-09）

抽查背景：书架书挂在 **enabled=1** 但备注/分组仍含 `Error` / `搜索失效` 的源上（约 157 源 / 916 书）。  
策略：**优先修书源**；书架可读性靠自动换源兜底。修不了的 **disable 或 skip 并记账**，不谎称 fixed。

运行时明细：`temp/shelf_restore/queue/stale_tag_repair_queue.jsonl`

## 抽查结果（每源 1 本样例，MCP debug）

| # | 源（书架本数） | bookSourceUrl | 目录 | 正文 | 搜索 | 当时判断 |
|---|----------------|---------------|------|------|------|----------|
| 1 | 统计书吧 69shu（325） | `https://www.69shu.com` | 360章 | 空 | 0 | 半残；备注要挂梯 |
| 2 | UU看书（44） | `https://www.uukanshu.com` | 失败 | — | 不清 | ConnectException |
| 3 | 乐文小说⑨（31） | `https://m.lwxs.com` | 279章 | 空 | 0 | 半残 |
| 4 | 笔趣阁 xxbiqudu（30） | `https://www.xxbiqudu.com/` | 失败 | — | 不清 | 打不开样例 |
| 5 | 爱下电子书 aixdzs（28） | `https://www.aixdzs.com` | 失败 | — | 不清 | 打不开 |
| 6 | 同人 trxs.me（21） | `http://www.trxs.me` | 失败 | — | 失败 | **UnknownHost / NXDOMAIN** |
| 7 | 懒人 lrxsw（20） | `http://www.lrxsw.org` | 失败 | — | 不清 | 打不开 |
| 8 | 八一中文（17） | `https://www.81zw.com` | 失败 | — | 不清 | 打不开 |
| 9 | 推荐书吧 69shuba.pro（17） | `https://www.69shuba.pro` | 失败 | — | 不清 | 打不开 |
| 10 | 爱下小说 api.aixdzs（17） | `http://api.aixdzs.com/` | 失败 | — | 不清 | 打不开 |

汇总（抽查当时）：**0/10 正文完整可读**；仅 2/10 能拉目录。

## trxs.me 专项（已处理）

- **根因**：`www.trxs.me` DNS **NXDOMAIN**。活站：`https://trxs.cc`。
- **书源**：启用并核对 `https://trxs.cc`；`http://www.trxs.me` **已禁用**。
- **书架**：21 本中 **8** remap；**13** 靠自动换源。
- 报告：`temp/shelf_restore/queue/trxs_source_fix_report.json`

## Deep diagnose 关门（2026-08-09 第二轮）

| URL | 状态 | 深挖证据 | 备注 |
|-----|------|----------|------|
| `http://www.trxs.me` | done_migrate | NXDOMAIN | → `https://trxs.cc` |
| `https://m.lwxs.com` | done_migrate | L2 host redirect | → `https://m.ilwxs.com`；校验成功（关搜索）；搜索 POST 500 |
| `https://www.aixdzs.com` | done_migrate | 证书过期；`m.aixdzs.com`→`ixdzs8.com` | 用已有 `https://ixdzs8.com`（校验成功）；旧源 disable |
| `http://api.aixdzs.com/` | done_migrate | L1 超时 | 同上，覆盖到 ixdzs8 |
| `https://www.69shu.com` | done_migrate | 真机打开 `69shuba.com/book/…` toc=771+正文 | → `https://www.69shuba.com`；**搜索/发现仍 CF 403** |
| `https://www.69shuba.pro` | skip:tls_corrupt | TLS 报文损坏 | 已 disable；用 69shuba.com |
| `https://www.81zw.com` | skip:cf_403 | 真机 HTTP `403` + `cf-mitigated: challenge` | 保持启用备注；需挂梯 |
| `http://www.lrxsw.org` | skip:cf_522 | 真机 GET → **522** origin down | 源站挂 |
| `https://www.xxbiqudu.com/` | skip:tls | ping 通；HTTPS TLS InternalError / 手机 ERR_TIMED_OUT | hunt 空 |
| `https://www.uukanshu.com` | skip:dns_loopback | 手机 DNS→**127.0.0.1**；`.cc` 亦 CF | 已 disable `.com`；试过 `.cc` 仍挑战页 |

**本表 10 源 deep diagnose 已关门**：迁通 **5**（trxs / 乐文 / 爱下×2 / 69 书吧）；硬 skip **5**（CF/522/TLS/DNS 毒化）。

状态值：`pending` / `fixed` / `skip:*` / `fail:*` / `done_migrate`

## 每源动作

1. `source-cli check channel`（MCP 空闲）  
2. `source-cli gate --url …`（勿 `--l0-only`）  
3. 能修则 `diagnose` → 层补丁 → `debug_source` / check（`checkDiscovery=false`）  
4. `ledger append` + `retro append`；更新本表 + jsonl  
5. 硬死 / 需梯子且本机不可达 → `skip:…` 或 disable，**不删**仍有书架引用的 URL  

自动换源：书架打不开时走 App 自动换源（见 `docs/guides/change-chapter-verify-test.md`）。
