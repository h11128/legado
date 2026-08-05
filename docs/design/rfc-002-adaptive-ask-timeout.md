# RFC-002: Adaptive 换源 ask timeout + source quality signals

Status: **Accepted for implementation (v1)**  
Date: 2026-08-05  
Related: [RFC-001](rfc-001-change-source-ask-order.md), change-source self-test backlog item 2

## 1. Problem

换源 ask 对每个源固定 `AskTimeout.CHANGE_SOURCE_MS`（60s）。尾部大量
`RespondTimeRank.FAILURE` / 本轮已 demote 的源仍占满 60s，拖慢 early-stop 前的
「已询问」进度；会话里常见 `missTimeout` 过百。

历史方案（按 SUCCESS/UNKNOWN/FAILURE 把超时改成 60/30/8）已被否决
（见 `AskTimeout` KDoc）：

| 旧分层 | 为什么坏 |
|---|---|
| SUCCESS → 60s | 全局搜索预算被加倍 |
| UNKNOWN → 30s | 新装/未校验源被砍半，误伤 |
| FAILURE → 8s | 过短；偶发慢源自我强化成永久失败 |

需要：**只缩短「已经有明确差信号」的 ask**，且 **不写坏 `respondTime`**。

## 2. Goals / Non-goals

**Goals**

1. 对「差源」用更短但仍可恢复的 grace 超时（秒级，不是 8s）。
2. 判定差源的信号可解释、可测、与 RFC-001 编码一致。
3. 超时仍走现有 `ChangeSourceAskMemory.noteMiss`（进程 demote），**不**把缩短预算写成 FAILURE `respondTime`（避免挖更深的坑）。
4. 与 host 限流、title-empty 跳过正交：空搜跳过不进超时；host 限流管并发，不管预算长度。

**Non-goals (v1)**

- 不改全局搜书 `SEARCH_MS`、自动换源 `AUTO_CHANGE_MS`。
- 不做 host 连续超时 streak 动态再砍（留 v2）。
- 不持久化 demote 集合（仍是进程级；title-empty 另有 TTL 存储）。

## 3. Quality signals (how we judge a source for timeout)

Ask 超时只用 **廉价、已有** 信号，不在超时前再发网：

| Signal | Source | Meaning | Timeout effect |
|---|---|---|---|
| `RespondTimeRank.SUCCESS` | `BookSource.respondTime` | 曾经成功较快 | **Full** 60s |
| `RespondTimeRank.UNKNOWN` | `respondTime == DEFAULT` | 未校验 / 默认 | **Full** 60s（保护新装） |
| `RespondTimeRank.FAILURE` | failure encoding | 校验失败编码 | **Grace** 20s |
| Session demoted | `ChangeSourceAskMemory.isDemoted` | 本进程超时/错误/content-bad demote | **Grace** 20s |
| Title empty | title-empty cache | 本书搜空 | **Skip**（0ms，不进 withTimeout） |

**不作为 v1 超时输入：** 用户赞踩分、`weight`、最新章/目录软徽章、深探正文质量
（那些只影响排序/展示，且深探在 ask 之后）。

### 3.1 Why FAILURE gets grace, not skip

FAILURE 可能是旧 MCP 脏数据、站点短暂故障、或校验配置过严。再给 **20s**
一次机会；仍超时 → session demote → 同进程后续 ask 继续 grace。成功则
`RespondTimeUpdater` 按 RFC-001 写 SUCCESS，下次恢复 full。

### 3.2 Why UNKNOWN stays full

新装几乎全是 UNKNOWN。砍半会让「第一次换源」系统性变脆，正是旧分层的坑。

## 4. Timeout policy (normative)

```text
BASE_MS  = AskTimeout.CHANGE_SOURCE_MS   // 60_000
GRACE_MS = 20_000
MIN_MS   = 12_000

budget(respondTime, sessionDemoted):
  if sessionDemoted → GRACE_MS
  else if classify(respondTime) == FAILURE → GRACE_MS
  else → BASE_MS
  return coerceIn(MIN_MS, BASE_MS)
```

API: `AskTimeoutBudget.forChangeSourceAsk(respondTime, sessionDemoted)`.

Logging: `ask-budget ms=20000 rank=FAILURE demoted=true origin=…`

### 4.1 Interaction with host pacing

Host token bucket **先** `acquire`，再 `withTimeoutOrNull(budget)`。
等 token 的时间 **不计入** ask budget（与校验路径一致：压力控制与单次预算分离）。

### 4.2 respondTime write rules (unchanged)

| Outcome | Write BookSource.respondTime? |
|---|---|
| Hit + success path | Yes（EWMA SUCCESS） |
| Empty | No |
| Timeout / error | No（仅 AskMemory demote） |
| Grace timeout | No（同上） |

## 5. Companion work in same ship (backlog 1/3/4)

Not part of the timeout formula, but same UX/perf release:

1. **Host ask pacing** — `CheckHostTokenBucket` on 换源 ask（默认 4 token / 4 QPS，尊重 `concurrentRate`）。
3. **Title-empty persist + TTL** — 7 天；过期可再问；不进 global demote。
4. **TOC soft badge** — 与最新章相同：正文 quality-OK 且强 `refSim`（或无参考）时不展示「目录规模疑似不一致」。

## 6. Acceptance

| # | Check |
|---|---|
| A1 | Unit: SUCCESS/UNKNOWN → 60s；FAILURE → 20s；demoted → 20s |
| A2 | Unit: title-empty TTL 过期后 `isEmpty` 为 false |
| A3 | Device: log 出现 `ask-budget`；FAILURE/demoted 源不再普遍卡满 60s |
| A4 | Device: 同 host 并发受 bucket 约束（超时洪峰下降或分散） |
| A5 | Device: early-stop 后好源行无 TOC 徽章（正文已 OK） |
| A6 | 不回归：`miss empty` 仍不写 respondTime；analyzer `--expect-deep-cap` PASS |

## 7. v2 (explicitly deferred)

- Host streak：同 host 连续 N 次 timeout → 临时 grace 或跳过。
- 按 `concurrentRate` 动态抬高/压低 grace。
- 把 grace 策略扩展到单章换源 content probe（今日仍用固定 `CHANGE_SOURCE_MS`）。

## 8. Decision

**Implement v1 as specified.** Prefer predictability over aggressive 8s cuts;
pair with host pacing + title-empty TTL so wall-time drops without self-poisoning
`respondTime`.
