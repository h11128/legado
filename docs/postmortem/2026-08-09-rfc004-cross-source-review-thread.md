# Postmortem: RFC-004 跨源段评整条 thread（问题 → RFC → P1–P5 → 真源验收）

Date: 2026-08-08 → 2026-08-09  
Scope: 从「段评打不开」到 RFC-004 全阶段落地、真源供给、P5 多源合集与真机 UI  
PE: `#682`  
Package: `com.legado.app.debug`  
Thread: [段评功能无法打开](3d47363c-3cd3-4e8d-80d2-89400622c7c4)

Related:

- Design: [rfc-004-cross-source-review-provider.md](../design/rfc-004-cross-source-review-provider.md)
- Device guide: [rfc-004-review-overlay-device-verify.md](../guides/rfc-004-review-overlay-device-verify.md)

## 一句话

段评打不开是因为功能绑在「当前正文书源的 `ruleReview`」上，而绝大多数书源没有段评规则；本 thread 写成 RFC-004，把「评论提供方」从正文源拆开，按 P1→P4 做完单源 overlay，再扩真源供给，最后做 P5 多源章评合集并用《诡秘之主》真机验收。

## 整条时间线（按用户意图）

| # | 用户意图 | 我们做了什么 | 代表产出 |
|---|---|---|---|
| 1 | 段评为什么打不开？ | 查清：入口依赖当前源 `ruleReview` / JS 段评能力；多数源无规则 → 等于死功能 | 结论沟通 |
| 2 | 为何不拿有段评的源 merge 上来？ | 认同方向；对齐可复用认书/认章/认段基础 | — |
| 3 | 写 RFC | 成文 RFC-004：供给优先、先章后段、不对错段 | `8e989ee81` |
| 4 | 一步一步做完；每阶段 subagent review→修→commit；最后真机 | 按 P0/P1a→P1b→P2→P3→P4 推进 | 见下表提交 |
| 5 | 继续 P3 真机；「夹具是什么？」 | 解释夹具=测试用书源；P3 snackbar 真机过 | `3d17ee2da` `58935eaed` |
| 6 | 不要夹具，要真实段评源；找起点/QQ | 探接口；做 **起点本章说**、**微信读书划线**；QQ 当时未抓到稳定段评 XHR | `ef803d294` |
| 7 | 还有别的吗？前三个：番茄/七猫→晋江→QQ | 番茄（社区镜像章评桶）、晋江、QQ 书吧 HTML；**七猫卡验签跳过** | `36614b298` |
| 8 | 七猫要什么？段评能不能合集显示？→ 要做多源合集，先设计 | 七猫=验签链路；合集=架构改动 → 写 §12 Multi-provider | RFC Rev + 设计 |
| 9 | 落地 P5；审查→修→commit→真机 | schema v102、merge loader、合集 dialog、BookInfo 多绑 | `cf8a51a81` `071b1b78a` |
| 10 | 验 UI；继续真源 UI | 夹具双源脚本；《诡秘之主》起点+QQ 真合集 | `246bd8e08` `6e3b91e00` |
| 11 | 段评 UI 是原有还是新建？ | 角标+`ReviewDetailDialog` 原有；`ReviewMergeDetailDialog` 新建 | 沟通澄清 |
| 12 | 总结进本地文档 | 本 postmortem（先 P5 版，后扩成整 thread） | `3bace97dd` + 本文 |

---

## 阶段详述

### A. 问题与产品判断

- **原行为：** 阅读页段评走当前正文书源的原生 `ruleReview` 或 JS `getReviewSummary/Detail`。
- **用户体感：** 「功能在，但打不开」——多数盗版/正文源根本没写段评规则。
- **产品转向：** 正文继续用现源；另绑一家（后改多家）**有段评能力的提供方**，把评论贴到当前章/段。
- **约束（写入 RFC）：** 供给优先；先章桶（`-1`）后段落；置信不够不对错段；P5 只合章评、段落图标最多一家。

### B. RFC-004（`8e989ee81`）

规范了 Binding、align、authority/coverage、P1–P4 切片，以及后来的 §12 多源合集（G9、A16–A20）。

### C. P1a — 绑定基础（`e2eeeb303`）

- `book_review_bindings`（当时单行/一书一源）
- 能力检测、简介页手动绑定、换源迁移挂点  
- 审查修过：`providerName`、迁移只按旧 `bookUrl` 等

### D. P1b — 章桶 overlay（`e11d035d8`）

- 阅读页拉提供方 summary，只认真实 `-1`（禁止伪造章评）
- 点击走 provider 的 book/chapter + `ProviderParaRef`
- 配套夹具源 + 设备会话脚本（后续 `62c269d04` 等）

### E. P2 — 段落硬映射（`1968aa58a`）

- authority 门禁 + digram hard-map + coverage  
- 不够则退回章桶，不对错段画图标  
- 每阶段 subagent review → 修 → commit

### F. P3 — 自动发现（`3d17ee2da`，真机证据 `58935eaed`）→ 无缝多绑

- **初版：** 未绑定时搜索 capable 源，**确认 snackbar** 后再绑（不静默）
- **夹具：** 专门做的测试书源（`legado-fixture://review-overlay`），用来稳定测路径，不是真实站点
- 真机坑：夹具作者与书架作者不一致 → `sameBook` 失败；改用空白/弱作者书测 snackbar
- **无缝自动（用户要求后）：** `reviewOverlayAutoBind` **默认开**；`proposeAll` 收集每源唯一命中；`bindAutoAll` 静默写入（上限 mergeMax）；toast「已自动绑定 K 个」；歧义/已有行（含禁用）跳过。章评合集接现有 P5 路径。段角标仍不自动开真实源 authority。

### G. P4 — 阅读设置（`dca536b88`）

- 跨源段评 / 自动发现 / 段评图标等 prefs 进 `pref_config_read.xml`  
- 设备验证记录进 guide

### H. 真源供给扩张

| 提交 | 内容 |
|---|---|
| `ef803d294` | **起点本章说**（m.qidian majax）、**微信读书划线** |
| `36614b298` | **番茄**（社区镜像→章评桶）、**晋江**、**QQ 书吧 HTML**（书级评论当 `-1`） |

- **七猫：** 需要可过验签的请求链路（签名/风控）；本 thread **未打通**，明确跳过。  
- **QQ：** App 真段评接口未公开；用书吧 SSR 当章评桶是折中。  
- **番茄：** 可搜到书，但同名同人噪声大（后在合集验收里踩过）。

### I. P5 — 多源章评合集

**设计触发：** 用户问「所有结果直接集合一起显示」→ 单绑模型不够 → §12。

**实现（`cf8a51a81`）：**

- Room **101→102**：多行 bindings；`(contentBookUrl, providerSourceUrl)` 唯一；`enabled` / `sortOrder` / `role`
- 并行 load；`-1` **求和**；`paragraph_primary` 独占 P2 图标  
- **新建** `ReviewMergeDetailDialog`（来源徽章）；行点击 → **原有** `ReviewDetailDialog`（A19）  
- BookInfo 多选管理；`reviewOverlayMergeEnabled` / `MergeMax`

**审查修复（`071b1b78a`）：** 见下方「踩坑」。

**UI / 真源验收：**

| 脚本 | 结果 |
|---|---|
| `rfc004-p5-merge-ui-session.py` | 夹具 A+B：`providers=2 bucket=5` → 合集抽屉 → 行进详情 |
| `rfc004-p5-real-providers-ui-session.py` | 《诡秘之主》起点+QQ：`providers=2 bucket=11194` → 真本章说文案 → 行进起点 `共 11174 条` |

---

## 踩了什么坑（整 thread）

### 产品 / 理解

1. **「段评打不开」≠ UI bug** — 是供给绑错对象（正文源无规则）。  
2. **夹具 ≠ 真源验收** — 夹具只证明路径；用户要真源后必须换供给与书。  
3. **合集抽屉 ≠ 绑了 N 行就出现** — `MergeActive.providers` 只含加载成功的源；一对齐失败就变成单源详情。  
4. **UI 归属** — 角标 + `ReviewDetailDialog` 原有；合集 dialog 才是新建。合集本身只 page-1，翻页在单源详情。

### P1–P4

5. **换源迁移** — 审查要求收紧：P1 按旧 `bookUrl`，避免误伤。  
6. **P3 sameBook** — 夹具固定「夹具作者」对不上书架作者 → 自动发现失败；测 snackbar 要用弱/空作者书。  
7. **后台子代理「未 commit」通知过期** — P1b/P2 实际已在后续 hash 提交，勿被 stale task 带偏。

### 真源

8. **手机上的起点/QQ「正文源」没有段评能力** — 不能直接当 RFC-004 provider；要单独做 `#rfc004-review` JS 源。  
9. **QQ 无稳定公开段评 XHR** — 退到书吧 HTML；书级评论要对齐章名 → 后来加「第一章」等锚点章（`6e3b91e00` 相关）。  
10. **七猫验签** — 无 unidbg/签名链路就不要假装能做。  
11. **番茄搜「诡秘之主」易出同人** — 配对前必须 `debug_source` 核对 bookUrl。

### P5 工程

12. **`runCatching` 吞 `CancellationException` 仍 `putMerge`** → 脏 merge 会话。  
13. **伪造 `displayKeys[-1]=merge:N`** → 撞 A13。  
14. **章评角标在标题行** — 隐藏标题 / 非章首点不到；adb 乱点会翻页。  
15. **工程杂音** — Git Bash adb 路径需 `MSYS_NO_PATHCONV=1`；`force-stop` 后要 `mcp-ensure`；角标 `999` 是展示封顶。

---

## 经验教训

1. **先问清「打不开」是入口、规则还是供给** — 本问题根因是供给模型。  
2. **RFC 切片 + 每阶段 review→commit** — 长 thread 可控；P5 审查也拦下了真实竞态。  
3. **验收三层：单测 → 夹具路径 → 真源 API+UI** — 跳级会假装完成。  
4. **书级评源要单独 align 策略** — 不能假设完整同构 TOC。  
5. **可复跑放 `scripts/`，证据进 `guides/`，过程进 `postmortem/`**。  
6. **对用户问题直接答**（夹具是什么 / UI 新旧 / 七猫要什么）再展开，避免只丢实现。

---

## 下一步（优先级）

| 优先级 | 项 | 说明 |
|---|---|---|
| P0 | 合集抽屉内 per-provider load-more | §12.4.3；现靠行进单源详情翻页 |
| P0 | 合集列表分源可见性 | 大章评下 QQ 书吧易被起点热评淹没；可分块/交错 |
| P1 | BookInfo 拖拽 `sortOrder` | §12.6 |
| P1 | 番茄真书命中 / 换更稳供给 | 抑同人 |
| P1 | 七猫验签 | 仍 blocked |
| P2 | P5c 弱文案去重 | 默认关；万级评论要谨慎 |
| P2 | 晋江 / 微信读书 真源合集自动化 | 复制 real session 模板 |
| P2 | Web `ReviewDialog.vue` overlay | RFC §10 |
| — | PE `#682` 关单与否 | 视是否把 P0 load-more 算进本任务 |

---

## 关键提交索引（本 thread 相关）

| Hash | 阶段 |
|---|---|
| `8e989ee81` | RFC-004 成文 |
| `e2eeeb303` | P1a 绑定基础 |
| `e11d035d8` | P1b 章桶 overlay |
| `1968aa58a` | P2 段落硬映射 |
| `3d17ee2da` | P3 自动绑定 |
| `dca536b88` | P4 阅读设置 |
| `62c269d04` / `58935eaed` | 设备验证记录 |
| `ef803d294` | 起点 + 微信读书真源 |
| `36614b298` | 番茄 + 晋江 + QQ 书吧 |
| `cf8a51a81` | P5 合集主体 |
| `071b1b78a` | P5 审查修复 |
| `246bd8e08` | 夹具双源 UI 脚本 |
| `6e3b91e00` | 真源 UI 脚本 + QQ 对齐锚点 |
| `7754bb8fb` / `3bace97dd` | 指南与 postmortem |

## 复跑

```bash
python scripts/rfc004-overlay-device-session.py          # P1 夹具章桶
python scripts/rfc004-p5-merge-ui-session.py             # P5 夹具合集 UI
python scripts/rfc004-p5-real-providers-ui-session.py    # P5 起点+QQ 真源 UI

export GRADLE_USER_HOME=E:/.gradle
./gradlew :app:testDebugUnitTest --tests 'io.legado.app.model.review.*'
```
