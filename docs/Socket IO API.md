# Hydroline Beacon SocketIO 接口标准（2025-11-17，已修订）

## 重要注意（先读）

- 插件使用的 Socket.IO 实现为 `netty-socketio 2.0.9`（兼容 Socket.IO v1/v2 协议）。强烈建议客户端使用 `socket.io-client@2.x`（例如 `2.4.0`）。使用 `socket.io-client@4.x` 会导致 ACK 回调参数不兼容，出现空响应或不触发回调的情况。
- 配置文件：`src/main/resources/config.yml`（部署后位于插件目录的 `config.yml`）包含 `port` 与 `key`。请务必将 `key` 设为一个随机且安全的字符串（建议 64 字节），否则所有请求将返回 `INVALID_KEY`。

## 通用约定

- Namespace：`/`（默认）
- 事件命名：使用 `snake_case`。
- 所有请求 payload 必须包含字段：`key`（插件配置中的密钥）。
- 所有响应均为单个 JSON 对象；若错误则 `success: false` 并包含 `error` 字段。成功时 `success: true` 并包含事件相关数据。
- ACK 语义：服务端使用单次 ACK（v1/v2 语义），客户端回调会收到该 Map 作为唯一参数（非 error-first）。建议客户端为每次 emit 设置超时（推荐 8-10 秒）。

## 配置与调度说明（重要）

- 配置文件：`plugins/Hydroline-Beacon/config.yml`（源码位于 `src/main/resources/config.yml`）。
- `interval_time`：单位为 tick（1 秒 = 20 tick）。默认值 `200` 表示 10 秒。
- 周期任务：插件内部有两类周期扫描任务（Advancements/Stats 与 MTR Logs）。两者以相同周期运行，但默认“半周期错峰”调度：
  - Advancements/Stats：初始延迟 = `interval_time`，周期 = `interval_time`。
  - MTR Logs：初始延迟 = `interval_time / 2`，周期 = `interval_time`。
- 现象说明：在默认错峰下，你会看到“每 5 秒出现一条扫描日志（两类任务交替）”，但同一类任务的实际周期仍是 `interval_time`（例如 200 tick = 10 秒）。
- 调整建议：
  - 想减少总日志频率为“每 `interval_time` 才有一条”：可将 MTR 任务的初始延迟改为与另一任务一致（需要修改源码 `ScanScheduler`）。
  - 想让任务更快或更慢：直接改 `interval_time`；记得将秒换算成 tick（秒 × 20）。

## 全事件清单（详尽说明）

1. force_update

- 描述：触发一轮 Advancements + Stats + MTR 的全量 Diff 扫描并写入数据库（异步执行）。
- 请求：

```json
{ "key": "<your-64-char-secret-key>" }
```

- ACK（立即返回，表示已入队）：

```json
{ "success": true, "queued": true }
```

- 说明：`queued: true` 表示扫描任务已排入后台执行，ACK 不等待扫描完成。若需要检测扫描结果，请在扫描完成后查询数据库或实现新的状态事件（插件当前未提供进度事件）。

2. get_player_advancements

- 描述：获取指定玩家的所有 Advancement 条目（来自 `player_advancements` 表）。支持通过 `playerUuid` 或 `playerName` 查询（两者至少给一个，`playerUuid` 优先）。
- 请求：

```json
{ "key": "<key>", "playerUuid": "<uuid>", "playerName": "<name>" }
```

- ACK 成功示例：

```json
{
  "success": true,
  "player_uuid": "<uuid>",
  "advancements": {
    "minecraft:story/root": "{\"done\":true,\"criteria\":{...}}",
    "mod:x_custom_adv": "{...}"
  }
}
```

- 关键说明：
  - `advancements` 的每个 value 是一个 **JSON 字符串**（UTF-8 bytes 存储）。客户端需 `JSON.parse()` 或等效解析。不要假设它已是对象。
  - 键为 Advancement ID，例如 `minecraft:story/root` 或 mod 提供的 ID。

3. get_player_stats

- 描述：获取指定玩家的所有 stats 条目（来自 `player_stats` 表）。支持 `playerUuid` 或 `playerName`。
- 请求：

```json
{ "key": "<key>", "playerUuid": "<uuid>", "playerName": "<name>" }
```

- ACK 成功示例：

```json
{
  "success": true,
  "player_uuid": "<uuid>",
  "stats": {
    "minecraft:mined:stone": 12345,
    "stats:minecraft:broken": 0
  }
}
```

- 关键说明：
  - 存储格式为：`category + ":" + statName`，但实际 category 字段可能本身包含 `:`，因此客户端请按最后一个冒号或按约定拆分（具体拆法由接入方需求决定）。不要对 `stats` key 做过于严格的硬编码解析。
  - value 为整型（long）。

4. list_online_players

- 描述：返回当前在线玩家的列表与基础信息（在 Bukkit 主线程读取）。
- 请求： `{ "key": "<key>" }`
- ACK 成功示例：

```json
{
  "success": true,
  "players": [
    {
      "uuid": "...",
      "name": "player1",
      "health": 20.0,
      "max_health": 20.0,
      "game_mode": "SURVIVAL",
      "world": "world"
    }
  ]
}
```

- 说明：若无玩家在线返回 `players: []`。

5. get_server_time

- 描述：返回主世界（插件取 `Bukkit.getWorlds()` 的第一项）时间相关数据。
- 请求： `{ "key": "<key>" }`
- ACK 成功示例：

```json
{
  "success": true,
  "world": "world",
  "time": 9370,
  "full_time": 33370,
  "do_daylight_cycle": "true"
}
```

- 说明：`do_daylight_cycle` 为字符串（"true" / "false"）；`time` / `full_time` 为 long。
  - 若服务器当前没有可用世界（极少数边界场景），将返回 `world: null, time: null, full_time: null, do_daylight_cycle: null`。

6. get_player_mtr_logs

- 描述：查询 MTR 变更日志（表 `mtr_logs`），支持多条件过滤与分页。
- 请求（字段均可选，除 `key`）：

```json
{
  "key": "<key>",
  "playerUuid": "<uuid>",
  "playerName": "<name>",
  "singleDate": "2025-02-13",
  "startDate": "2025-02-01",
  "endDate": "2025-02-29",
  "dimensionContext": "overworld|the_nether|the_end|...",
  "entryId": "<entry id>",
  "changeType": "ADD|REMOVE|UPDATE",
  "page": 1,
  "pageSize": 50
}
```

- 约束与说明：
  - `singleDate` 与 `startDate/endDate` 互斥；日期格式为 `YYYY-MM-DD`，服务器按本地时区做整日范围。
  - 若请求页超出范围，会自动重置到第 1 页并返回有效数据。
- ACK 成功示例：

```json
{
  "success": true,
  "total": 1234,
  "page": 1,
  "page_size": 50,
  "records": [
    {
      "id": 98765,
      "timestamp": "2025-02-13 19:38:31 +0800",
      "player_name": "Steve",
      "player_uuid": "...",
      "class_name": "TrackStation",
      "entry_id": "station_001",
      "entry_name": "Central",
      "position": "x=...,y=...,z=...",
      "change_type": "ADD",
      "old_data": null,
      "new_data": "{...}",
      "source_file_path": "logs/mtr/...csv",
      "source_line": 42,
      "dimension_context": "overworld"
    }
  ]
}
```

7. get_mtr_log_detail

- 描述：按 `id` 返回单条 MTR 日志详情。
- 请求：

```json
{ "key": "<key>", "id": 98765 }
```

- ACK 成功示例：

```json
{
  "success": true,
  "log": {
    /* 同上 records[0] 结构 */
  }
}
```

8. get_player_sessions

- 描述：查询玩家进出服会话记录（表 `player_sessions`），支持按玩家、事件类型、日期或时间戳范围过滤，并分页。
- 请求（除 `key` 外均可选）：

```json
{
  "key": "<key>",
  "playerUuid": "<uuid>",
  "playerName": "<name>",
  "eventType": "JOIN|QUIT",
  "singleDate": "2025-11-18", // 与 startDate/endDate 互斥
  "startDate": "2025-11-01", // 与 singleDate 互斥
  "endDate": "2025-11-18", // 与 singleDate 互斥
  "startAt": 1731907200000, // epoch 毫秒；与 startDate/endDate 互斥
  "endAt": 1734575999999, // epoch 毫秒；与 startDate/endDate 互斥
  "page": 1,
  "pageSize": 50
}
```

- 约束与说明：
  - `singleDate` 与 `startDate/endDate` 互斥；`startDate/endDate` 与 `startAt/endAt` 也互斥。

9. get_status（心跳/状态）

- `eventType` 仅接受 `JOIN` 或 `QUIT`（大小写不敏感）。
- ACK 成功示例：

```json
{
  "success": true,
  "total": 120,
  "page": 1,
  "page_size": 50,
  "records": [
    {
      "id": 1001,
      "event_type": "JOIN",
      "occurred_at": 1731910800123,
      "player_uuid": "...",
      "player_name": "Steve",
      "player_ip": "203.0.113.10",
      "world_name": "world",
      "dimension_key": "NORMAL",
      "x": -12.3,
      "y": 64.0,
      "z": 88.9
    }
  ]
}
```

9. get_status（心跳/状态）

- 描述：返回用于心跳检测的状态快照，包括配置的扫描间隔、服务器人数信息与数据库累计条目数。
- 请求：`{ "key": "<key>" }`
- ACK 成功示例：

```json
{
  "success": true,
  "interval_time_ticks": 200,
  "interval_time_seconds": 10.0,
  "server_max_players": 20,
  "online_player_count": 3,
  "mtr_logs_total": 68967,
  "stats_total": 120345,
  "advancements_total": 34567
}
```

- 说明：
  - `interval_time_ticks` 来自插件配置（1 秒 = 20 tick）；并同时提供换算的 `interval_time_seconds`。
  - `server_max_players` 为服务器最大人数容量；`online_player_count` 为当前在线玩家数。
  - 三个累计值来源于 SQLite 数据库：`mtr_logs`、`player_stats`、`player_advancements` 的总行数（非去重玩家数）。

10. get_player_nbt（玩家 NBT 原始体）

- 描述：按玩家返回 `playerdata/*.dat` 的 NBT 原始数据（已转换为 JSON 对象），并在 SQLite 内缓存 X 分钟。
- 请求：

```json
{ "key": "<key>", "playerUuid": "<uuid>", "playerName": "<name>" }
```

- ACK 成功示例：

```json
{
  "success": true,
  "player_uuid": "<uuid>",
  "nbt": {
    "bukkit": { "lastKnownName": "Steve" },
    "Pos": [0.0, 64.0, 0.0],
    "Health": 20.0,
    "Inventory": [
      /* ... */
    ]
  }
}
```

- 说明：
  - 缓存时长由 `config.yml` 的 `nbt_cache_ttl_minutes` 控制（默认 10）。超时后首次查询会自动重载并刷新缓存。
  - 若找不到对应的 `playerdata/<uuid>.dat` 文件，返回 `success: true, nbt: null`（不视为错误）。
  - 插件会从 NBT 的 `bukkit.lastKnownName` 以及 `firstPlayed`/`lastPlayed` 自动更新 `player_identities` 表，实现 UUID 与玩家名及首末登录时间的缓存。

11. lookup_player_identity（玩家身份查询）

- 描述：查询 `player_identities` 表中的 UUID ↔ 玩家名映射，同时返回玩家首登/末登时间戳与记录更新时间。支持通过 `playerUuid` 或 `playerName` 查询，建议至少提供其中一项（若两者都提供而记录中名称不同，响应以数据库为准）。
- 请求：

```json
{ "key": "<key>", "playerUuid": "<uuid>", "playerName": "<name>" }
```

- ACK 成功示例：

```json
{
  "success": true,
  "identity": {
    "player_uuid": "<uuid>",
    "player_name": "Steve",
    "first_played": 1708236523123,
    "last_played": 1708890123456,
    "last_updated": 1708891123999
  }
}
```

- 字段含义：
  - `first_played` / `last_played`：来自玩家 `playerdata` NBT 的毫秒时间戳（若无法解析则为 `null`）。
  - `last_updated`：插件写入该行的本地时间戳，便于判断数据新旧。
  - 若查无记录返回 `success: false, error: "NOT_FOUND"`。

## 错误与状态碼

- INVALID_KEY：密钥校验失败（客户端应立即停止并报告凭证问题）。
- DB_ERROR: <detail>：数据库访问时发生错误（一般为 SQLite 读写/锁或 SQL 异常）。
- INTERNAL_ERROR: <detail>：内部执行错误（如线程/调度异常）。
- INVALID_ARGUMENT: <detail>：请求参数非法或互斥条件冲突（例如同时提供 `singleDate` 与 `startDate/endDate`）。
- 响应示例：

```json
{ "success": false, "error": "INVALID_KEY" }
```

## ACK / 超时 与 客户端建议

- ACK 语义：服务端对每个事件通过 `ackSender.sendAckData(Map)` 返回单个 Map。客户端回调会得到该 Map 作为唯一参数（socket.io v2 风格）。
- 建议客户端设置 ACK 超时（8-10s），超时后按策略重试或报警。
- 对 `force_update` 不要期望 ACK 表示数据写入完成；ACK 仅表示任务已接受。

## 客户端实现与版本建议

- Node.js：`socket.io-client@2.4.0`（推荐锁定到 2.x）。使用 `transports: ['websocket']` 可避免长轮询。
- Python：`python-socketio` 客户端（5.x 系列可工作），使用同步等待回调或 callback 机制。
- 示例（Node.js 简短）:

```js
// 使用 socket.io-client@2.4.0
const io = require("socket.io-client");
const socket = io("http://127.0.0.1:48080", {
  transports: ["websocket"],
  timeout: 10000,
socket.emit("get_server_time", { key: process.env.BEACON_KEY }, (resp) => {
  if (!resp) return console.error("empty ack");
  if (!resp.success) return console.error("err", resp.error);
  console.log(resp);
});
```

示例（Python 简短）:

```py
import socketio
sio = socketio.Client()
sio.connect('http://127.0.0.1:48080', transports=['websocket'])
def ack(resp):
    print(resp)
sio.emit('get_server_time', {'key': '...'}, callback=ack)
```

## 性能、频率与运维建议

- `get_server_time`：可每秒调用一次或更慢。
- `list_online_players`：建议 ≥2-5s。
- 玩家級的 `get_player_advancements` / `get_player_stats`：建议 ≥30s，且仅在需要时调用。
- `force_update`：仅管理员或 CI/运维触发，避免短时间內多次调用（建议最少 60s 間隔）。
- 周期扫描：默认 `interval_time: 200 tick`（约 10 秒），两类扫描半周期交错执行，因此总体日志显示约每 5 秒一条；请按实际机器负载与数据规模调整。
- 建议客户端实现指数退避重试；對 `INVALID_KEY` 不重试，而是报警並人工干预。

## 安全建议

- 不要在版本庫或公開日志中泄露 `key`。
- 若對外暴露該端口，建議使用反向代理（NGINX/Caddy）並開啟 TLS/HTTPS。
- 可在代理層實現 IP 白名单或额外认证。

## 数据格式细节（总结）

- Advancements: Map<advId, rawJsonString>（客户端需要 JSON.parse）。
- Stats: Map<composedKey, long>，composedKey 为 category 与 statName 用冒号拼接，category 可能包含冒号本身。
- MTR Logs: 见 `get_player_mtr_logs`/`get_mtr_log_detail` 返回结构；`timestamp` 为文本时间戳（CSV 原样）。
- Player Sessions: `occurred_at` 为 epoch 毫秒；`event_type` 为 `JOIN`/`QUIT`。

## 建议的文档变更清单（维护者用）

1. 在文档顶部加入“必须使用 socket.io-client@2.x”说明。
2. 将 `force_update` 的响应示例更新为包含 `queued` 并说明其含义。
3. 明确 `advancements` 值为 JSON 字符串并给出解析示例。
4. 详细列出 `INVALID_KEY`、`DB_ERROR`、`INTERNAL_ERROR` 等错误码。
5. 增加客户端示例（Node/Python）并附带 ACK 超时建议。
6. 新增 `get_player_mtr_logs`/`get_mtr_log_detail`/`get_player_sessions` 的规范与示例。
