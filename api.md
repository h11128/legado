# 阅读[API](/app/src/main/java/io/legado/app/api/controller)

## 对于[Web](/app/src/main/java/io/legado/app/web/)的配置

您需要先在设置中启用"Web 服务"。

> Web 服务默认监听手机网络接口，多数接口不提供身份认证，请仅在可信局域网中启用，使用后及时关闭。书源写入、搜索和调试接口使用“Web 书源访问令牌”；纯 JavaScript 书源接口同样必须提供令牌。

旧 JSON 书源写入接口也必须通过 `X-Legado-Token` 提供令牌。搜索和调试 WebSocket 在 Upgrade 握手时使用 `Sec-WebSocket-Protocol: legado, legado.token.<令牌 UTF-8 字节的 base64url，无填充>`；固定协议必须位于第一项，服务端会在读取任何 WebSocket 帧前完成验证。浏览器同源状态不作为身份凭据；Web 页面中的令牌只保存在当前浏览器标签页会话中，关闭标签页或切换服务地址后需要重新输入。

## 使用

### Web

以下说明假设您的操作在本机进行，且开放端口为1234。  
如果您要从远程计算机访问[阅读]()，请将`127.0.0.1`替换成手机IP。

#### 插入单个书源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)

```
URL = http://127.0.0.1:1234/saveBookSource
Method = POST
```

#### 插入纯 JavaScript 单文件书源

请求 BODY 为纯 JavaScript 书源脚本文本，`Content-Type` 使用 `text/plain; charset=utf-8`，最大 1 MiB，并且必须提供正确的 `Content-Length`。HTTP 层会去除脚本文本首尾空白，应用随后复用编辑器的提取、校验和保存逻辑；等待其他保存和解析脚本各自最多 30 秒。

请先在“设置 > 其他设置 > Web 书源访问令牌”中配置令牌，并通过 `X-Legado-Token` 请求头提供完全相同的值。令牌验证会在读取请求体之前完成。令牌不会进入应用备份，恢复或更换设备后需要重新配置。该接口无论是否同源都必须发送令牌；使用明文 HTTP 时仍只能在可信网络中使用。

覆盖已有书源时会保留启用状态、发现开关、排序、权重、响应时间，以及脚本未声明时的原分组。书源有实质变化时会更新时间并回写脚本中的 `lastUpdateTime`；内容未变化时保留原时间。

接口按脚本声明的 `bookSourceUrl` 新建或覆盖。编辑已有脚本时可通过 `openedSourceUrl` 查询参数传入原书源 URL，以获得与应用内编辑器一致的改名和冲突检查语义。

```
URL = http://127.0.0.1:1234/saveJsSource?openedSourceUrl=原书源URL（可选）
Method = POST
Content-Type = text/plain; charset=utf-8
X-Legado-Token = 设置中配置的令牌
```

#### 插入多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1234/saveBookSources
URL = http://127.0.0.1:1234/saveRssSources
Method = POST
```

#### 获取书源

```
URL = http://127.0.0.1:1234/getBookSource?url=xxx
URL = http://127.0.0.1:1234/getRssSource?url=xxx
Method = GET
``` 

#### 获取所有书源or订阅源

```
URL = http://127.0.0.1:1234/getBookSources
URL = http://127.0.0.1:1234/getRssSources
Method = GET
```

#### 删除多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1234/deleteBookSources
URL = http://127.0.0.1:1234/deleteRssSources
Method = POST
```

#### 调试源

key为书源搜索关键词，tag为源链接

```
URL = ws://127.0.0.1:1235/bookSourceDebug
URL = ws://127.0.0.1:1235/rssSourceDebug
Message = { key: [String], tag: [String] }
```

#### HTTP 请求日志

HTTP 日志仅在设置中启用“记录 HTTP 日志”后写入内存，最多保留最近 50 条；请求和响应正文单次最多记录 8 KiB，
记录会在写入时脱敏认证信息、Cookie 和常见密钥字段。完整日志仍可能包含敏感业务数据，因此两个接口都要求通过
`X-Legado-Token` 提供“Web 书源访问令牌”，并且只应在可信局域网中使用。

```text
URL = http://127.0.0.1:1234/getHttpLogs?limit=50
URL = http://127.0.0.1:1234/getHttpLog?id=1
Method = GET
X-Legado-Token = 设置中配置的令牌
```

`getHttpLogs` 返回 `{ recording, logs }`，其中 `logs` 为摘要列表；`getHttpLog` 按 id 返回完整的已脱敏记录。

#### MCP 服务

应用可直接提供 Streamable HTTP MCP 服务，默认端口为 `1236`，端点为 `/mcp`。服务与 Web 服务相互独立，
但复用“Web 书源访问令牌”；启动前必须先配置令牌，所有 MCP 请求都必须携带 `X-Legado-Token`。
服务保留 SDK 的 Host 和 Origin 校验，只允许本机地址与设备当前局域网地址。令牌通过 HTTP 发送，因此只应在可信局域网中使用。
端点面向可设置自定义请求头的本地或桌面客户端，不提供浏览器跨域 CORS 预检。

通用 HTTP MCP 客户端可按以下字段配置：

```json
{
  "url": "http://<设备IP>:1236/mcp",
  "headers": {
    "X-Legado-Token": "设置中配置的令牌"
  }
}
```

服务提供核心工具：`save_source`、`list_sources`、`get_source`、`delete_sources`、
`debug_source`、`get_http_logs`、`get_http_log`、`set_http_log_recording`、
`get_cookies`、`set_cookie`、`clear_cookies`、`eval_js`、`check_source`，以及批量修复路径
`start_check_sources`、`get_check_progress`、`stop_check_sources`、`reset_mcp_channel`。
另提供轻量健康检查：`GET /mcp/health`（同样需要 `X-Legado-Token`），返回
`ok` / `serviceRun` / `debugBusy` / `checkRunning` / `stale` / `lastTool*` 等字段，
供 PC 在 oneshot 前探测「进程活着但通道卡住」。
书源写入、删除、调试、批量校验、通道重置、日志开关、Cookie 持久层写入和清理、
脚本求值均可能修改应用状态；书源全文、未脱敏 Cookie 与已脱敏 HTTP 日志仍可能包含敏感业务数据，
请只向可信客户端开放令牌。

`debug_source` 为单通道逐步调试，默认超时 **90s**（与 PC MCP 客户端对齐），输出不会脱敏；
调试期间会发送 `notifications/message` 日志；请求 `_meta.progressToken` 时还会同步发送
`notifications/progress`。通知超时或客户端不支持通知不会影响最终的完整有界日志结果。
`check_source` 按当前应用配置启动 App 内校验会话，更新书源分组、错误备注和响应时间，
也会在每个书源得到明确结果后发送日志和可选进度通知；校验期间若书源被删除或重新保存，
该项会标记为未完成，旧校验结果不会覆盖新记录。客户端取消 MCP 请求不会中止已经启动的
应用内校验，可在应用的校验通知中手动停止。
批量修复/PC 脚本请优先用 `start_check_sources`（多线程，与 App「校验书源」同逻辑），再用
`get_check_progress` 分页取结果（含 `lastProgressAt` / `checkFlags`）。
`reset_mcp_channel` 仅用于紧急解锁长期占用的 debug/校验通道。
大批量时建议电脑侧先 DNS 预检，再按 50–100 URL 分批调用，避免一次加载全库压垮手机堆。
设备侧校验已启用目录采样、搜索成功跳过发现深检、按 host 分片、AIMD/令牌桶与 HTTP body 上限。
`list_sources` 支持 `offset`/`limit` 分页（默认 100，最大 500），避免大库被截断。
`save_source` 默认保留已有 `enabled` 与空分组回填；传入 `preserveEnabled=false` /
`preserveGroup=false` 可覆盖启用状态或清空分组。
`get_cookies` 返回持久层与会话层合并后的未脱敏 Cookie；`set_cookie` 只合并写入持久层，
同名会话 Cookie 在当前会话中仍优先；`clear_cookies` 会同时清理持久层、会话层和 WebView Cookie。
`eval_js` 可在应用书源环境执行任意 JavaScript，令牌等同于书源脚本执行权限；求值结果和 `java.log`
输出不会脱敏，也可能访问网络、Cookie、缓存及已绑定书源的数据。传入书源 URL 只绑定其运行时身份，
不会自动执行该书源的 `mainJs`。

服务还会将应用内置 Markdown 帮助文档作为只读 resources 暴露，URI 格式为 `legado://help/<文件名>`，
例如 `legado://help/jsHelp` 和 `legado://help/ruleHelp`。客户端可先列出 resources，再按 URI 读取；
返回内容使用 UTF-8 和 `text/markdown`，不会修改应用状态。

MCP 开关在进程崩溃后会按用户偏好自启；仅用户关闭服务时才会持久为关闭。
装包/开机走 `McpLifecycleReceiver`（`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`）；
划掉任务在偏好开启时不杀 MCP；`McpWatchdog` 约每 3 分钟恢复服务并清理僵死通道。
Wi‑Fi 地址变化时若 debug/校验正在进行，**推迟**重启 MCP 引擎，避免中途掐断工具调用
（见 `docs/postmortem/2026-07-28-mcp-hang-59f4efb9.md`）。
CIO `connectionIdleTimeoutSeconds=180`。手机 NSD 发布 `_legado-mcp._tcp`（TXT `path=/mcp`）。PC 用：

```bash
python scripts/mcp_discover.py          # 发现并写回 config/mcp_defaults.json
```

优先 zeroconf，其次 dns-sd，再回退 adb 读 wlan0。成功后写回 `mcp_defaults.json`，并同步 `~/.cursor/mcp.json` 的 `mcpServers.legado.url`。
`mcp_client.ensure_session` 在连接失败时会自动 rediscover（repair 脚本无需用户手改 IP）。
Cursor IDE 内置 MCP 客户端不走 Python：若工具仍超时，discover 写完配置后 **Reload MCP / 重开 agent 一次** 即可，不要手改 DHCP IP。
勿死记 DHCP IP；SOT 仍是 `config/mcp_defaults.json`。

`start_check_sources` 参数（与 App「校验书源」同逻辑；布尔开关缺省=App 当前配置，仅作用于本次 MCP 批量校验）：

| 参数 | 类型 | 默认 / 修复波次建议 | 含义 |
|------|------|---------------------|------|
| `urls` | string[] | 全部书源 | 要校验的 `bookSourceUrl` |
| `enabledOnly` | bool | true / **false** | 只校验启用源 |
| `keyword` | string | App 配置 / **我的** | 搜索关键词 |
| `threadCount` | int | App 设置 / **~8** | 手机侧并发线程 |
| `timeoutMs` | int | App 超时 | 单源超时毫秒 |
| `checkDomain` | bool | App / **false** | 探测域名可达 |
| `checkSearch` | bool | App / **true** | 校验搜索 |
| `checkDiscovery` | bool | App / **false** | 校验发现（默认不修发现） |
| `checkInfo` | bool | App / **true** | 校验详情页 |
| `checkCategory` | bool | App / **true** | 校验目录 |
| `checkContent` | bool | App / **true** | 校验正文 |
| `wSourceComment` | bool | App | 是否写入校验备注（可选） |

`get_check_progress` 的 snapshot 含 `checkFlags`（进行中任务的实际开关快照）与
`lastProgressAt`（上次有结果推进的时间，用于卡住检测）。
PC 修复脚本通过 `scripts/repair_check.py` 的 `check_args()` 统一传上述默认值。

#### 获取替换规则

```
URL = http://127.0.0.1:1234/getReplaceRules
Method = GET
```

#### 替换规则管理

请求BODY内容为`JSON`字符串，  
替换规则参考[这个文件](/app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt)。

##### 删除

```
URL = http://127.0.0.1:1234/deleteReplaceRule
Method = POST
Body = [ReplaceRule]
```
##### 插入

```
URL = http://127.0.0.1:1234/saveReplaceRule
Method = POST
Body = [ReplaceRule]
```

##### 测试

返回测试文本text替换结果

```
URL = http://127.0.0.1:1234/testReplaceRule
Method = POST
Body = { rule: [ReplaceRule], text: [String] }
```

#### 搜索在线书籍

若想获取对应的书籍的目录正文 请先**插入书籍**以启用缓存，如果试读后决定不添加到书籍，请**删除书籍**

```
URL = ws://127.0.0.1:1235/searchBook
Message = { key: [String] }
```

#### 插入书籍

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/Book.kt)。

```
URL = http://127.0.0.1:1234/saveBook
Method = POST
```

#### 删除书籍

```
URL = http://127.0.0.1:1234/deleteBook
Method = POST
```

#### 获取所有书籍

```
URL = http://127.0.0.1:1234/getBookshelf
Method = GET
```

获取APP内的所有书籍。

#### 获取书籍章节列表

```
URL = http://127.0.0.1:1234/getChapterList?url=xxx
Method = GET
```

获取指定图书的章节列表。

#### 获取书籍内容

```
URL = http://127.0.0.1:1234/getBookContent?url=xxx&index=1
Method = GET
```

获取指定图书的第`index`章节的文本内容。

#### 获取封面

```
URL = http://127.0.0.1:1234/cover?path=xxxxx
Method = GET
```

#### 获取正文图片

```
URL = http://127.0.0.1:1234/image?url=${bookUrl}&path=${picUrl}&width=${width}
Method = GET
```

#### 保存书籍进度

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookProgress.kt)。

```
URL = http://127.0.0.1:1234/saveBookProgress
Method = POST
```

### [Content Provider](/app/src/main/java/io/legado/app/api/ReaderProvider.kt)


* 需声明`io.legado.READ_WRITE`权限
* `providerHost`为`包名.readerProvider`, 如`io.legado.app.release.readerProvider`,不同包的地址不同,防止冲突安装失败
* 以下出现的`providerHost`请自行替换

#### 插入单个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)

```
URL = content://providerHost/bookSource/insert
URL = content://providerHost/rssSource/insert
Method = insert
```

#### 插入多个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = content://providerHost/bookSources/insert
URL = content://providerHost/rssSources/insert
Method = insert
```

#### 获取书源or订阅源

获取指定URL对应的书源信息。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/bookSource/query?url=xxx
URL = content://providerHost/rssSource/query?url=xxx
Method = query
```

#### 获取所有书源or订阅源

获取APP内的所有订阅源。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/bookSources/query
URL = content://providerHost/rssSources/query
Method = query
```

#### 删除多个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = content://providerHost/bookSources/delete
URL = content://providerHost/rssSources/delete
Method = delete
```

#### 插入书籍

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/Book.kt)。

```
URL = content://providerHost/book/insert
Method = insert
```

#### 获取所有书籍

获取APP内的所有书籍。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/books/query
Method = query
```

#### 获取书籍章节列表

获取指定图书的章节列表。   
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/book/chapter/query?url=xxx
Method = query
```

#### 获取书籍内容

获取指定图书的第`index`章节的文本内容。     
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/book/content/query?url=xxx&index=1
Method = query
```

#### 获取封面

```
URL = content://providerHost/book/cover/query?path=xxxx
Method = query
```
