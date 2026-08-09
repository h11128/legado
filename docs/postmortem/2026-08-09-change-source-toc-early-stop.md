# Postmortem: 换源 smartScore / TOC 同书降权 / early-stop 挂死

Date: 2026-08-08 → 2026-08-09  
Scope: 整书换源质量分、错书 PO18、目录身份强降权、会话 analyzer FAIL 根治  
Probe book: 《同时穿越，用装备栏打穿诸天》/ 上古情书 / `https://book.qq.com/book-detail/58892002`  
Package: `com.legado.app.debug`

Related guide: [change-chapter-verify-test.md](../guides/change-chapter-verify-test.md)  
Related RFC: [RFC-003 佚名弱作者 merge](../design/rfc-003-author-placeholder-smart-merge.md)（明确不在换源硬挡作者）

## 一句话

先把换源分拆成「字数 / 标签 / 0–100 分」并压住同名错书；再用目录标题重合+章数比强降权（不碰佚名）；最后根治「好源凑不满 20 → 永不 early-stop → 千级源池无 finish → analyzer FAIL」。

## 做了什么（按时间）

| 阶段 | 提交（代表） | 结果 |
|---|---|---|
| 分数 UX 拆分 | `f64869b33` | `chapterWordCountText` 只报字数；`qualityTags` / `smartScore` 独立 |
| 同档拉开 | `0a97fd2f8` | 连续长度加成 + 更细 respond；Ok 同分塌缩缓解 |
| 最新章硬不匹配 | `539eddbb2` | `latestMatch=false` → −22 + 长度 cap；PO18 从榜首被压到低于 QQ Weak |
| TOC 同书身份 | `d9a607990` | `tocTitleAffinity` / `tocIdentity` → `tocMatch` −20/+5；`SearchBook.tocMatch` 持久化防洗分 |
| early-stop 根治 | `a545d3294` | 好源=Ok+Weak；plateau（≥5 好源后 150 询问无增长）；脚本超时点停止；analyzer 认进度 |

真机收口（`a545d3294` 后）：

- Log: `temp/legado_change_source_session_2026-08-08_213537.txt`
- `early-stop … reason=plateau qualityOk=17` → `finish early=true`
- Analyzer **PASS**
- PO18 Ok **24** ≪ QQ Weak **61**

## 踩了什么坑

### 1. 以为「书名过滤坏了」——其实过滤过了

换源只比 `fName == name`。PO18 站搜索 302 到详情，Legado「列表为空,按详情页解析」后 meta 书名完全匹配，作者写「佚名」。**不是过滤失效，是同名残本+黄链污染 TOC。**

### 2. 想用佚名硬挡 —— 和 RFC-003 打架

`佚名` 是弱作者（merge 语义）。换源硬要求作者=上古情书会误伤合法弱作者源。正确刀口是 **目录内容 / 最新章**，不是作者门。

### 3. 只修 PO18 的 TOC CSS —— 不治本

站点两个 `ul.list-group`（真 48 章 + 推荐黄链）被规则一起吞。单修一个源 CSS 挡不住同类骗术；换源侧 **强降权保留行** 才是产品层解法。

### 4. `tocMatch=false` 的 −20 会被后续 `refreshSmartScore` 洗成软 −3

`annotateMetaQuality` 传了硬 `tocMatch`，但 gates / 用户评分路径裸调 `refreshSmartScore` 时默认 `tocMatch=null`，又从「目录…」tag 推断 soft −3。  
**修法：** `SearchBook.tocMatch` 会话字段持久化；soft 仅在 hard 为 null 时生效。

### 5. 单测「中等亲和+章数差」容易写飞

均匀抽 12 个本地标题时，前半重合过多 → affinity ≥0.28 走「同书残本 true」。拉丁串 digram 虚高更坑。要用 **互不重合的中文标题 + 控制命中抽样点**，或接受「高亲和残本为 true」并另测 mid 带。

### 6. Analyzer FAIL ≠ 功能失败

旧会话：`qualityOk` 进度已到 12，但门禁只认 `finish` / `early-stop` 行 → `has_finish` / `quality_ok_useful` / `early_stop_honored` 三连 FAIL。  
根因是 **early-stop 只数 Ok、target=20 凑不够 → 扫完 ~1380 源拖死**；不是 TOC 降权没生效（分数对比早已成立）。

### 7. 工程杂音

- Windows：`GRADLE_USER_HOME` 必须在 E:（KSP different roots）；wrapper 坏了时用解压后的 `gradle` 二进制。
- `tee … \| tail` 会缓冲到结束才出字；device-session 长跑应直接重定向文件。
- `noteUsefulQuality` 先 ++ 再写 `lastUsefulAtCompleted` 会与 ask 路径并发误触 plateau（已改为先写 lastUseful）。

## 经验教训

1. **先定骗术层再选刀口**：搜索空→详情回落 / 弱作者 / 污染 TOC 是不同层；不要用作者硬过滤「顺手」修目录污染。
2. **强降权 > 硬删除**（本场景）：错书行可保留观察，靠 smartScore 排序；与 `dropContentBad` 解耦。
3. **会话状态要持久化**：`tocMatch` / useful 计数这类硬信号不能只活在一次函数参数里。
4. **Early-stop 要有「收益递减」出口**：只靠绝对 target，在「全书只有十来个 Ok/Weak」的书上会永不等停。
5. **验证分层**：分数结论用 `list+ score=`；会话完整性用 finish/early-stop；两者失败原因要分开写，避免把 harness 超时说成产品回归。
6. **Harness 与产品双保险**：App plateau + 脚本超时点「停止」+ analyzer 认 progress max。

## 下一步（建议顺序）

1. **观察 plateau 误伤**：若某些书 150 询问后还有慢热好源，考虑把 `EARLY_STOP_PLATEAU_ASKS` 做成 pref，或 plateau 要求「连续无 hit」而不只「无 useful」。
2. **日志字段改名（可选）**：`qualityOk` 已含 Weak，文档/UI「好源」OK，但 log 字段名易误导；可逐步改为 `useful=` 并保持 analyzer 双读。
3. **单章换源对齐**：确认 `ChangeChapterSourceViewModel` 不需要同一套 useful/plateau（当前整书路径已落地）。
4. **错书 Ok 仍偏高时**：在 smartScore 上叠加「最新章+TOC 双 false」额外 cap，或 UI 默认折叠 `tocMatch=false` 行（仍不硬删）。
5. **禁用/降权脏源**：对确认污染站（如 jile1 PO18）走书源侧 disable / 校验分组，换源降权只是阅读侧护栏。
6. **Gradle wrapper**：修或文档化「wrapper 坏 → 用 `E:/.gradle/wrapper/dists/.../bin/gradle`」，避免 smoke 脚本静默 ClassNotFound。

## 关键证据路径

| 用途 | 路径 |
|---|---|
| TOC 降权会话（analyzer 当时 FAIL） | `temp/legado_change_source_session_2026-08-08_205705.txt` |
| Plateau 根治 PASS | `temp/legado_change_source_session_2026-08-08_213537.txt` |
| 分数分析器 | `scripts/change-source-score-analyze.py` |
| 会话分析器 | `scripts/change-source-analyze-log.py` |
| Guide run record | `docs/guides/change-chapter-verify-test.md`（2026-08-08k/l） |
