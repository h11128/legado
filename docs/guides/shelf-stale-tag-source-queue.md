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

## trxs.me 专项（已处理，非规则可修）

- **根因**：`www.trxs.me` DNS **NXDOMAIN**，不是选择器坏了。活站：`https://trxs.cc`。
- **书源**：启用并核对 `https://trxs.cc`（同人小说trxs.cc）；`http://www.trxs.me` **已禁用**。
- **书架**：21 本中 **8** 本按书名搜到并 remap 到 `trxs.cc`；**13** 本站点无对应/ID 撞车 → **不硬改链**，靠自动换源。
- 报告：`temp/shelf_restore/queue/trxs_source_fix_report.json`

## 修复队列状态

| URL | 状态 | 备注 |
|-----|------|------|
| `http://www.trxs.me` | done_migrate | → `https://trxs.cc`；死源已 disable |
| `https://www.69shu.com` | skip:l2_bot_shell_cf | CF/挂梯；保持启用 |
| `https://www.uukanshu.com` | skip:l1_unreachable | hunt 空；保持启用 |
| `https://m.lwxs.com` | done_migrate | → `https://m.ilwxs.com`；旧源 disable；书架 31 已 remap；设备校验成功（关搜索）；**搜索 POST 仍 500** |
| `https://www.xxbiqudu.com/` | skip:l2_tls_hunt_empty | TLS InternalError；hunt 空；保持启用 |
| `https://www.aixdzs.com` | skip:cert_expired_hunt_empty | 证书过期；hunt 空；保持启用 |
| `http://www.lrxsw.org` | skip:l2_timeout_hunt_empty | 10060；hunt 空；保持启用 |
| `https://www.81zw.com` | skip:l2_bot_shell_cf | CF；`81zw2.com` 同；保持启用 |
| `https://www.69shuba.pro` | skip:l2_tls_hunt_empty | TLS corrupt；hunt 空；保持启用 |
| `http://api.aixdzs.com/` | skip:l1_unreachable_hunt_empty | TCP timeout；hunt 空；保持启用 |

**本表 10 源已全部关门**（2026-08-09）：可修 2（trxs / 乐文迁移）；其余 8 skip。书架打不开靠自动换源。

状态值：`pending` / `fixed` / `skip:*` / `fail:*` / `done_migrate`

## 每源动作

1. `source-cli check channel`（MCP 空闲）  
2. `source-cli gate --url …`（勿 `--l0-only`）  
3. 能修则 `diagnose` → 层补丁 → `debug_source` / check（`checkDiscovery=false`）  
4. `ledger append` + `retro append`；更新本表 + jsonl  
5. 硬死 / 需梯子且本机不可达 → `skip:…` 或 disable，**不删**仍有书架引用的 URL  

自动换源：书架打不开时走 App 自动换源（见 `docs/guides/change-chapter-verify-test.md`）。
