# Postmortem: 换源质量 / 软徽章 / smartScore / TOC 降权 / early-stop（整条 thread）

Date: 2026-08-08 → 2026-08-09  
Scope: 《同时穿越，用装备栏打穿诸天》换源从「结果被滤掉」到分数 UX、错书 PO18、目录强降权、会话挂死根治，以及度量行回退讨论  
Probe book: 《同时穿越，用装备栏打穿诸天》/ 上古情书 / `https://book.qq.com/book-detail/58892002`  
Package: `com.legado.app.debug`  
Thread: [3f95be51-0eb6-4218-87de-0887e61ab32e](3f95be51-0eb6-4218-87de-0887e61ab32e)

Related guide: [change-chapter-verify-test.md](../guides/change-chapter-verify-test.md)  
Related RFC: [RFC-003 佚名弱作者 merge](../design/rfc-003-author-placeholder-smart-merge.md)（**明确不在换源硬挡佚名作者**）

## 一句话

换源列表先从「软徽章误伤/挤掉字数」理清展示与过滤；再拆成「字数度量 · 质检标签 · 0–100 智能分」并拉开同档；用最新章硬不匹配 + 目录标题身份强降权压住同名错书（不碰佚名）；最后根治「好源凑不满就不 early-stop → 千源扫死 → analyzer FAIL」。收尾时发现度量行把「总章节数」砍瘦了，**回排版尚未落地**（见开放项）。

## 整条时间线（按用户意图）

| # | 用户意图 | 我们做了什么 | 代表产出 |
|---|---|---|---|
| 1 | 换源结果被自动过滤，点菜单也没变化？debug 修 | 查过滤菜单 vs 真实 gate；部分是硬过滤/展示逻辑纠缠 | 调查起步 |
| 2 | 「目录/最新章不一致」该不该屏蔽？彻底修 | **不硬删**；收紧 soft-meta 徽章展示条件（弱正文才叠、不可信本地参考不乱打） | `35ad11241`（+ paywall 短引用 `2f6e9505e`） |
| 3 | 正文过短也要显示字数；质检别占字数字段；要智能评分 UX | 规划并落地：度量行 / `qualityTags` / 右侧 `smartScore`；默认按分排序 | `f64869b33` |
| 4 | 分数怎么算？准不准？要不要再拆？ | 说明公式；指出同档塌缩与「长错书仍可高分」 | 沟通 + 后续改分 |
| 5 | 实际换源下载分析，同档拉开 | 设备会话 + `change-source-score-analyze.py`；连续长度加成 + respond 细分 | `0a97fd2f8` |
| 6 | 继续修能修的 | 最新章硬不匹配必扣分（不跟徽章门） | `539eddbb2` |
| 7–9 | PO18 怎么回事？能修书源吗？为什么没书名过滤？ | **不是过滤坏了**——同名过关；PO18=同名残本+黄链 TOC；单修 CSS 不治本 | 调查结论 |
| 10 | 站点怎么骗过的？ | 搜索空→详情回落；meta 书名准、作者佚名；目录混推荐黄链 | 调查 |
| 11–12 | 哪个修法实际？做成强降权；佚名有 merge，用目录对比 | **目录标题重合 + 章数比 → tocMatch 强降权**；不硬挡佚名 | `d9a607990`（+ 证据 `9e45aaf16`） |
| 13 | 实际测试 | 真机分数：PO18 再掉；当时 analyzer 仍 FAIL（会话没跑完） | 分数 OK / harness FAIL 分离 |
| 14–15 | fail 为什么？要根治 robust | early-stop 好源=Ok+Weak；plateau；脚本超时停；analyzer 认进度 | `a545d3294` |
| 16 | 总结进本地文档 | 首版 postmortem（偏 TOC/early-stop） | `1c6839a6b` |
| 17–18 | 少了当前章节字数？改回来；总章节也要，先说怎么排 | 确认拆分时砍瘦了旧「总章节·字数·响应」；**排版方案未实现** | 开放项（见下） |

---

## 阶段详述

### A. 软徽章与「被过滤」体感（`35ad11241`）

- 用户看到大量「目录不一致 / 最新章不一致」，以为菜单无效或结果被偷偷删。
- **产品结论：** 这些信号适合 **提示/降权**，不适合默认硬屏蔽（误伤合法弱正文）。
- **收紧展示：** 正文已失败不再叠 meta；目录徽章要本地参考可信；QQ 短试读等不可信引用少打徽章（另有 `2f6e9505e` 把付费墙短本地引用标 untrusted）。

### B. 列表 UX：字数 / 质检 / 智能分（`f64869b33`）

**痛点：** 失败时把真实字数写成 `-1`，质检文案和字数挤一行 → 「开了加载字数却看不到字数」。

**拆成三块：**

1. **度量行** `chapterWordCountText`：尽量只报字数（过短也保留真实 N）+ 响应时间等  
2. **质检短标签** `qualityTags`  
3. **右侧 0–100 `smartScore`**，默认按分排序  

### C. 分数准不准 & 同档拉开（`0a97fd2f8`）

- 用户要求真机拉数再改，而不是空谈雷达图。
- **同档塌缩：** 一堆 Ok 同分 → 排序无意义。  
- **改法：** 连续长度加成（`字数/350` 等封顶）+ 更细 respondTime 键，拉开 Ok 档。
- **仍不够：** 错书若正文很长，仍可能高分 → 必须上「同书」信号（最新章 / TOC）。

### D. 最新章硬不匹配（`539eddbb2`）

- Bug：`latestMatchesLocal == false` 时，旧逻辑要先过 soft-meta 徽章门才扣分；Ok + 可信本地引用时徽章被挡 → **错书长正文仍高分**（PO18 曾到 ~78）。
- **修法：** 硬不匹配必扣（约 −22）并限制长度加成；标签始终可展示。

### E. PO18 骗术层（调查，非单站 CSS 根治）

| 用户误解 | 事实 |
|---|---|
| 搜索完全错乱 / 没书名过滤 | 换源比 `fName == name`；PO18 **过了**书名门 |
| 佚名该硬挡 | 与 RFC-003 弱作者 merge 冲突；会误伤合法佚名源 |
| 修 PO18 TOC CSS 即可 | 站内双 list（真目录+黄链）可被规则一起吞；换源侧强降权才是护栏 |

链路：搜索 302/空列表 →「按详情页解析」→ meta 书名准、作者「佚名」→ 正文/目录却是残本或污染内容。

### F. 目录内容对比强降权（`d9a607990`）

- `tocTitleAffinity` / `tocIdentity` → `tocMatch`：false −20（并限长度加成），true +5。  
- **`SearchBook.tocMatch` 会话持久化**，防止后续 `refreshSmartScore` 把硬 −20 洗成软 −3。  
- 真机：PO18 Ok 再掉到 ~23 量级（相对「只扣最新章」时的 ~44）。

### G. early-stop / 会话挂死根治（`a545d3294`）

**FAIL 拆解：**

- 分数对比早已成立（TOC 降权有效）。  
- Analyzer 要 `finish` / `early-stop` 行；旧 early-stop **只数 Ok、target=20**，全书好源不够就扫完整池 ~1380 → 超时无 finish → harness 三连 FAIL。

**根治：**

- 好源 useful = **Ok + Weak**  
- **plateau**：≥5 好源后连续 N 询问无 useful 增长则 early-stop  
- 设备脚本超时点「停止」  
- analyzer 认进度 max  

收口日志：`temp/legado_change_source_session_2026-08-08_213537.txt` → `early-stop … plateau` → analyzer **PASS**；PO18 Ok **24** ≪ QQ Weak **61**。

### H. 度量行回退（开放 — thread 末尾）

用户指出：旧 UI 有 **总章节数 · 当前章字数 · 响应时间**；拆分 smartScore 后度量行变瘦，**总章节/字数体感缺失**。

用户要求：**改回来**；总章节必不可少；先讨论排布再改。

**建议排布（尚未实现，供下一轮）：**

```
度量行：共 N 章 · 字数：M · Xs
质检行：短标签（目录/最新章/过短…）
右侧：  smartScore 0–100
```

原则：度量三件套始终可扫读；质检不进字数字段；分数不替代章节规模信息。

---

## 踩了什么坑

1. **把「提示徽章」当成「过滤删除」** — UI 文案与菜单开关让用户以为点了就该消失。  
2. **质检文案污染字数字段** — 失败写 `-1` + 长句 → 「加载字数」形同虚设。  
3. **徽章门和扣分门绑死** — 不显示徽章就不扣分 → 错书高分。  
4. **以为没书名过滤** — 其实同名骗过精确匹配。  
5. **想用佚名硬挡修错书** — 撞 RFC-003；刀口应在目录/最新章。  
6. **单修脏源 CSS** — 挡不住同类站；阅读侧强降权才是护栏。  
7. **`tocMatch` 只活在一次调用参数** — 被 refresh 洗分。  
8. **单测 digram/抽样写飞** — 均匀抽标题虚高亲和；要用互不重合中文样本。  
9. **Analyzer FAIL ≠ 产品回归** — 分数与会话完整性要分开判。  
10. **Early-stop 只认绝对 target** — 好源稀少的书上永不停止。  
11. **UX 拆分过猛** — 修了字数/分数，顺手砍掉用户仍要的「总章节数」信息（开放项）。  
12. **工程杂音** — `GRADLE_USER_HOME` 同盘；`tee|tail` 缓冲；`noteUsefulQuality` 并发写 lastUseful（已修）。

---

## 经验教训

1. **先定骗术层再选刀口**（搜索回落 / 弱作者 / 污染 TOC / 会话挂死是不同层）。  
2. **本场景强降权 > 硬删除**：错书行可留观察，靠排序沉底。  
3. **展示、过滤、计分三门独立** — 徽章显隐 ≠ 是否扣分 ≠ 是否 drop。  
4. **硬信号要持久化在会话对象上**（`tocMatch`、useful 计数）。  
5. **Early-stop 要有收益递减出口**（plateau），不能只靠凑满 N 个 Ok。  
6. **验证分层**：list+score 看质量；finish/early-stop 看会话；勿混谈。  
7. **改 UX 前列一张「用户仍要扫读的字段清单」** — 避免拆字段时误删总章节等。

---

## 下一步（建议顺序）

| 优先级 | 项 | 说明 |
|---|---|---|
| **P0** | **恢复度量三件套排布** | 共 N 章 · 字数 · 响应；与 qualityTags / smartScore 分行（thread 末尾已拍板要改） |
| P1 | 观察 plateau 误伤 | 必要时 pref 化 `EARLY_STOP_PLATEAU_ASKS`，或改「连续无 hit」 |
| P1 | log 字段 `qualityOk`→`useful` | 已含 Weak，名字易误导；analyzer 双读 |
| P1 | 最新章+TOC 双 false 额外 cap / 默认折叠 | 错书 Ok 仍偏高时 |
| P2 | 单章换源是否复用 useful/plateau | 当前整书路径已落地 |
| P2 | 脏源书源侧 disable | jile1 PO18 等；换源降权只是护栏 |
| P2 | Gradle wrapper 文档化 | wrapper 坏 → `E:/.gradle/wrapper/dists/.../bin/gradle` |

---

## 关键提交索引

| Hash | 阶段 |
|---|---|
| `2f6e9505e` | 付费墙短本地引用标不可信 |
| `35ad11241` | 收紧 TOC/最新章软徽章 |
| `f64869b33` | 字数 / 质检标签 / smartScore 拆分 |
| `0a97fd2f8` | Ok 档同档拉开 |
| `539eddbb2` | 最新章硬不匹配扣分 |
| `d9a607990` | TOC 标题身份强降权 |
| `9e45aaf16` | TOC 降权真机分数记录 |
| `a545d3294` | early-stop plateau + Ok/Weak useful |
| `1c6839a6b` / `1b0a29db4` | 首版 postmortem + guide 链接 |

## 关键证据路径

| 用途 | 路径 |
|---|---|
| TOC 降权会话（analyzer 当时 FAIL） | `temp/legado_change_source_session_2026-08-08_205705.txt` |
| Plateau 根治 PASS | `temp/legado_change_source_session_2026-08-08_213537.txt` |
| 分数分析器 | `scripts/change-source-score-analyze.py` |
| 会话分析器 | `scripts/change-source-analyze-log.py` |
| Guide run record | `docs/guides/change-chapter-verify-test.md` |

## 复跑

```bash
# 设备换源会话（见 guide）
# 分析
python scripts/change-source-score-analyze.py …
python scripts/change-source-analyze-log.py temp/legado_change_source_session_….txt

export GRADLE_USER_HOME=E:/.gradle
./gradlew :app:testDebugUnitTest --tests 'io.legado.app.model.checkalgo.ChangeBookSourceQualityTest'
```
