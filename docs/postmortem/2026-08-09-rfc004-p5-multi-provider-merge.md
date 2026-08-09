# Postmortem: RFC-004 P5 多源章评合集（本 thread）

Date: 2026-08-08 → 2026-08-09  
Scope: RFC-004 §12 Multi-provider merge（P5a/b 落地 + 审查修复 + 夹具/真源 UI 验收）  
PE: `#682`  
Package: `com.legado.app.debug`

Related:

- Design: [rfc-004-cross-source-review-provider.md](../design/rfc-004-cross-source-review-provider.md) §12
- Device guide: [rfc-004-review-overlay-device-verify.md](../guides/rfc-004-review-overlay-device-verify.md)
- Thread: [段评功能无法打开](3d47363c-3cd3-4e8d-80d2-89400622c7c4)（本地会话）

## 一句话

把「一书绑多家段评源 → 章评角标求和 → 合集抽屉分源徽章 → 行点击进该源详情」做成可真机验收的能力；坑主要在取消竞态/伪造 paraData、书级评源对齐、以及夹具冒烟≠真源 UI。

## 做了什么

### 产品 / 实现（P5）

| 项 | 内容 |
|---|---|
| Schema | Room **101→102**：`book_review_bindings` 多行；唯一键 `(contentBookUrl, providerSourceUrl)`；`enabled` / `sortOrder` / `role` |
| Merge load | `ReviewOverlayLoader.loadMerged`：并行 per-binding；`-1` count **求和**；P2 图标只走 `paragraph_primary` |
| UI | `ReviewMergeDetailDialog`（新建）；行点击 → 既有 `ReviewDetailDialog`（A19） |
| BookInfo | 多源添加 / 启停 / 设段落主源 / 移除 |
| Prefs | `reviewOverlayMergeEnabled`（默认 true）+ 阅读设置开关；`reviewOverlayMergeMax`≤5 |
| 真源供给 | 起点本章说、QQ 书吧、番茄/晋江/微信读书等 JS 源（七猫验签仍未通） |

### 本 thread 收口动作

1. **Subagent 审查** → 修 Critical/Warning → commit  
2. **夹具双源 UI** → `scripts/rfc004-p5-merge-ui-session.py`  
3. **真源双源 UI** → 《诡秘之主》第一章绯红 + 起点 + QQ → `scripts/rfc004-p5-real-providers-ui-session.py`

### 代表提交

| Hash | 说明 |
|---|---|
| `cf8a51a81` | P5 合集主体（schema / merge / dialog / BookInfo） |
| `071b1b78a` | 审查修复：`putMerge` 延后、禁 `merge:N` 假 key、行进详情 |
| `246bd8e08` | 夹具双源 UI 会话脚本 |
| `6e3b91e00` | 真源会话脚本 + QQ TOC 对齐锚点 |
| `7754bb8fb` | 设备指南补真源证据 |

### 真机证据（摘要）

| 场景 | 结果 |
|---|---|
| 夹具 A+B | `merge providers=2 bucket=5` → `本章评论（2 源）` → 行进详情 `共 2 条` |
| 起点+QQ《诡秘之主》 | `providers=2 bucket=11194`（起点 11174 + QQ 20）→ 合集抽屉真本章说文案 → 行进起点详情 |

## 踩了什么坑

### 1. `runCatching` 吞掉 `CancellationException` 仍 `putMerge`

并行 merge 子任务把取消当「该源失败」，外层仍写 `MergeActive`；Activity `onSuccess` 因 token/cancel 不刷 UI → **脏会话**，点章评路由错乱。

**教训：** 取消必须 rethrow；会话写入只放在调用方校验通过之后（本仓：`mergeSession` 返回 + Activity `putMerge`）。

### 2. 伪造 `displayKeys[-1]="merge:N"`（撞 A13）

合集 chip 只需 sum count。假 paraData 一旦被误当成真实 `ProviderParaRef` 打详情 API 就会坏。

**教训：** 合集展示与可点击身份分离；count 可合，identity 不可编。

### 3. 以为「绑了 2 个源就会开合集抽屉」

`MergeActive.providers` 只含 **加载成功** 的源。一点起点 + 对齐失败的 QQ → `providers=1` → 走单源 `ReviewDetailDialog`，看不到「（2 源）」。

**教训：** UI 验收要用「两源都 `bucket>0`」；日志看 `merge providers=` 不是看 bindings 行数。

### 4. 章评气泡挂在标题行 —— 隐藏标题 / 不在首页就点不到

`titleMode=2` 或 `durChapterPos` 不在章首时，角标可能不在当前页。adb 乱点还容易翻页。

**教训：** UI 自动化先 `durChapterPos=0` + 确认正文标题行；角标坐标从截图像素估，不要盲 tap。

### 5. QQ 书吧是书级评论，TOC 与正文章名对不齐 → `align=fail`

详情页刮到的目录常是番外/倒序，对不上「第一章 绯红」。

**修法：** `qqread-review-provider.js` 增加 `第一章` / `第1章` 等 **书评锚点章**（同一 `book-comment` URL），让 `alignResult` 的章号匹配给出 0.7。

**教训：** 书级评源要单独设计 align 策略，不能假设有完整同构 TOC。

### 6. 番茄搜「诡秘之主」易命中同人

社区镜像搜索排序差，不能默认当第二真源。

**教训：** 真源配对先 `debug_source` 核对 `bookUrl`/书名，再写 bindings。

### 7. 夹具成功 ≠ 产品验收

夹具证明合集路径；真源才证明 API/对齐/文案。两者脚本分开（`*-merge-ui-*` vs `*-real-providers-*`）。

### 8. UI 归属易误解

角标 + `ReviewDetailDialog` **原有**；`ReviewMergeDetailDialog` **P5 新建**。合集只做 page-1 预览，完整翻页在单源详情（§12.4.3）。

### 9. 工程杂音

- Git Bash 下 adb `/sdcard/...` 被路径转换 → `MSYS_NO_PATHCONV=1`
- `force-stop` 后 MCP 断连 → 先 `mcp-ensure.py`
- 角标大数显示成 `999` 是展示封顶，不等于 bucket 真是 999

## 经验教训（可复用）

1. **Merge 会话与 UI apply 同生命周期** — 取消/换章不得留下半成品 `MergeActive`。  
2. **验收分层** — 单测纯函数 → 夹具路径 → 真源 API+UI；不要跳级宣称「真源合集 OK」。  
3. **书级 vs 章级评源** — 书级必须有 align 逃生舱（锚点章 / 或未来 skip-align 策略）。  
4. **自动化放 `scripts/`** — 可复跑；证据写进 `docs/guides/…`；过程教训写 `docs/postmortem/`。  
5. **审查门禁有效** — Critical（取消、假 key、A19）不修就宣称设备可用会埋雷。

## 下一步（建议优先级）

| 优先级 | 项 | 说明 |
|---|---|---|
| P0 | 合集抽屉内 **per-provider load-more** | §12.4.3 仍 follow-up；现靠行进单源详情翻页 |
| P0 | 合集列表里 **QQ 书吧条目可见性** | 当前总和含 QQ，但首屏多为起点热评；可按源分块或交错 |
| P1 | `BookInfo` **拖拽 sortOrder** | RFC §12.6；现仅 primary/启停/移除 |
| P1 | **番茄** 真书命中策略 | 避免同人；或换更稳供给 |
| P1 | **七猫** 验签 | 仍 blocked |
| P2 | P5c 弱文案去重 | 默认关；11174+ 规模下要谨慎 |
| P2 | 真源自动化扩到 晋江/微信读书 | 复制 real session 模板 + 对齐探针 |
| P2 | Web `ReviewDialog.vue` overlay | RFC §10.5 |
| — | PE `#682` 是否关单 | 视是否把 P0 load-more 算进本任务 |

## 复跑命令

```bash
# 夹具双源 UI
python scripts/rfc004-p5-merge-ui-session.py

# 真源：诡秘之主 + 起点 + QQ
python scripts/rfc004-p5-real-providers-ui-session.py

# 单测
export GRADLE_USER_HOME=E:/.gradle
./gradlew :app:testDebugUnitTest --tests 'io.legado.app.model.review.*'
```
