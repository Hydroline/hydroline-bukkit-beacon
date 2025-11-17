# Hydroline Beacon SocketIO 接口标准（2025-11-17）

## 通用约定

- Namespace：`/`（默认）
- 所有事件统一使用 snake_case 命名。
- 所有请求 payload 都必须包含字段：`key`，值为插件配置中的 64 位随机字符串。
- 所有响应均返回一个 JSON 对象：
  - `success`: `true | false`
  - `error`: 出错时的错误信息字符串（可选）
  - 其余字段为对应事件的数据。

## 事件：`force_update`

- 功能：立即触发一轮 Advancements/Stats/MTR 数据扫描并写入 SQLite。
- 请求：

```json
{
  "key": "your-64-char-secret-key"
}
```

- 响应（ACK）：

```json
{
  "success": true
}
```

> 说明：该事件会在后台异步执行一次 `advancements + stats + MTR` 全量 Diff 扫描，详见插件日志中的耗时与 Diff 信息。

## 事件：`get_player_advancements`

- 功能：查询指定玩家 UUID 的 Advancements key/value 数据。
- 请求：

```json
{
  "key": "your-64-char-secret-key",
  "playerUuid": "ae55f5de-602b-386c-b44a-53a171eb21d3"
}
```

- 响应：

```json
{
  "success": true,
  "player_uuid": "ae55f5de-602b-386c-b44a-53a171eb21d3",
  "advancements": {
    "minecraft:story/root": "{ \"done\": true, ... }",
    "minecraft:adventure/adventuring_time": "{ \"progress\": 12, ... }"
  }
}
```

> 说明：`advancements` 为 Map 结构，key 为 Advancement ID，value 为对应 JSON 的字符串表示（后端可自行二次解析）。

## 事件：`get_player_stats`

- 功能：查询指定玩家 UUID 的 Stats key/value 数据。
- 请求：

```json
{
  "key": "your-64-char-secret-key",
  "playerUuid": "ae55f5de-602b-386c-b44a-53a171eb21d3"
}
```

- 响应：

```json
{
  "success": true,
  "player_uuid": "ae55f5de-602b-386c-b44a-53a171eb21d3",
  "stats": {
    "minecraft:mined:stone": 12345,
    "minecraft:killed:creeper": 12
  }
}
```

> 说明：`stats` 的 key 为 `category:stat_key` 形式，value 为整型数值。

## 事件：`list_online_players`

- 功能：查询当前在线玩家列表、生命值、游戏模式等。
- 请求：

```json
{
  "key": "your-64-char-secret-key"
}
```

- 响应：

```json
{
  "success": true,
  "players": [
    {
      "uuid": "ae55f5de-602b-386c-b44a-53a171eb21d3",
      "name": "larker_package",
      "health": 20.0,
      "max_health": 20.0,
      "game_mode": "SURVIVAL",
      "world": "world"
    }
  ]
}
```

> 说明：玩家信息从 Bukkit 主线程同步读取，SocketIO 线程通过调度回调获取，确保不阻塞主线程。

## 事件：`get_server_time`

- 功能：查询服务器当前时间以及是否锁死时间（`gamerule doDaylightCycle`）。
- 请求：

```json
{
  "key": "your-64-char-secret-key"
}
```

- 响应：

```json
{
  "success": true,
  "world": "world",
  "time": 6000,
  "full_time": 1234567,
  "do_daylight_cycle": "true"
}
```

> 说明：
> - 默认选取服务器世界列表中的第一个世界作为时间基准（通常为主世界 `world`）。
> - `do_daylight_cycle` 为字符串 `"true"` 或 `"false"`，对应 Minecraft Gamerule 配置。

## 错误示例

当 `key` 校验失败时，各事件统一返回：

```json
{
  "success": false,
  "error": "INVALID_KEY"
}
```

当出现数据库错误或内部异常时，返回：

```json
{
  "success": false,
  "error": "DB_ERROR: <具体错误信息>"
}
```

