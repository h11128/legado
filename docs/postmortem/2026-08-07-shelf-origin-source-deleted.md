# 2026-08-07: Bookshelf origins deleted as “disabled duplicates”

## Symptom

书架（尤其养肥1/2/3）大量书打不开：`books.origin` 指向的书源在 `book_sources` 里已不存在，或仍在但 `enabled=0`。

## Evidence

| Snapshot | Total sources | Enabled | Notes |
|----------|---------------|---------|-------|
| `backup-2026-07-26.zip` `bookSource.json` | **4741** | — | pre-cleanup |
| `backup.zip` (2026-08-06) / live before restore | **2810** | ~1252 | post-cleanup |
| Live after restore (2026-08-07) | **3014** | **1563** | shelf origins restored |

养肥123（groupId `128\|16\|4096`，363 本）before → after（设备 DB 实测）：

| | MISSING | DISABLED | OK |
|--|---------|----------|-----|
| Before | 204 | 54 | 105 |
| After | 4 | 0 | 359 |

全书架网络书 before：MISSING **2297** / DISABLED **538** / OK **652** → after：MISSING **697** / OK **2790**。

`books.origin` 绑定是精确等值：`BookSourceDao.getBookSource` → `bookSourceUrl = :key`（非 host 归一）。

## Root cause

**2026-07-30** `legadoSkill` 清理波次「delete disabled duplicates」（retro §128）：

- A：同 base 已有启用源 → 删禁用孪生（~1232）
- B：仅禁用组内留 1 条 → 删多余（~673）
- mop：脏 URL（~15）
- 报告（legadoSkill，当时）：`temp/full_fix/queues/disabled_dup_delete_report.json`
  `deleted_claimed=1902`（candidates 1905）；retro 正文写「约 1917」含 mop，以 **claimed 1902** 为准。

假设错误：同 host / 同 base 的禁用变体可删，因为“启用孪生还能搜到”。  
事实：阅读按 **精确** `book.origin == bookSourceUrl` 绑定。删掉 `https://www.69shuba.com` 而留下 `https://www.69shuba.com/`，书架上仍指向无斜杠的书会丢源。

同日还有 `disable-dead` / 校验打「网站失效」标签（禁用但不删）。真正让书架大面积**丢源**的是 **去重删除**；大批量禁用也会让书架书打不开（本次另恢复了 107 条书架仍引用的禁用源）。不是换源本身删的。

## Snapshot used

用户说的 export / snapshot：手机 `Download/legado/backup-2026-07-26.zip` 里的 `bookSource.json`（不是空的 `shareBookSource.json`，也不是与 live 同构的 `legado_export.db`）。

## Restore actions (2026-08-07)

1. 从 7/26 快照 **精确恢复** 书架缺失 origin：**151** 条，并 `enabled=true`。
2. 书架仍禁用的 origin：**107** 条；MCP `save_source` 默认 `preserveEnabled=true`（手机旧 build 甚至忽略该参数），改用 **delete→save** 才真正启用。
3. 斜杠/归一化变体从快照再补：**21**；同 base 启用孪生克隆：**10+**；69 书吧镜像族（`.cx` / `69xinshu` / `.pro` 等）从 `https://www.69shuba.com/` 克隆。

仍缺的 **697** 本：origin 不在 7/26 快照且无可用同站 donor（例如部分站已消失），需换源或另找源，不能凭空恢复。

## Prevention

1. **禁止** `delete_sources` / 去重删源前不查 `books.origin`（查法见纪律 §2-shelf-origin）。
2. 清理禁用孪生时：若 origin 仍被书架引用 → **只保留禁用，不删**；或先批量 `origin` remap 到保留 URL 再删。
3. 书架仍引用的 origin：**不要批量 `enabled=false`**，除非已 remap 或用户确认可换源。
4. 大批量删源前强制导出 `bookSource.json` + `bookshelf.json`（或菜单「导出所有书的书源」）。
5. 用 MCP 改启用状态时显式传 `preserveEnabled=false`；若手机 build 忽略该参数，用 **delete→save**（本次 re-enable 即如此）。
