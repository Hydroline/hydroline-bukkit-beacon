# 2025-11-17 需求拆分 TODO 列表

> 目标：实现一个在 1.16.5 - 1.20.1 兼容的 Bukkit 插件，异步读取 world 下 `advancements/`、`stats/` 与 MTR `mtr/` 日志数据，存入 SQLite，并通过 SocketIO 提供查询接口。

## 0. 基础准备与总体架构
- [x] 阅读 `docs/requirements/20251117.md` 和 `examples/` 示例，确认所有输入输出格式与路径约定。
- [x] 通读 `src/` 现有 1.16.5 架构（主类、监听器、任务调度等），整理现状与可复用点。
- [x] 设计插件整体分层结构（配置管理、数据采集、数据存储、SocketIO 服务、定时任务），在本文件中简单记录。
- [x] 梳理全局异步策略：哪些逻辑必须在主线程执行（如 Bukkit API 获取 world、玩家列表），哪些逻辑全部放入异步任务。
- [x] 明确异步任务分组：Advancements/Stats 扫描一组任务，MTR 日志扫描单独一组任务，避免单一长时间任务。

## 1. 配置文件设计与实现
- [x] 设计配置文件结构（`port`、`key`、`interval_time`、`version` 及其他需要的开关项）并在本文件中简单描述。
- [x] 使用 Bukkit 默认数据目录，在 `getDataFolder()/config.yml` 下存放配置文件。
- [x] 在插件启动时加载配置文件，若不存在则自动生成默认配置。
- [x] 若配置中 `key` 为空（首次启动），自动生成 64 位随机字符串并写回配置文件（可以是随机字母数字组合）。
- [x] 将 `interval_time` 单位固定为 `tick`（20 tick = 1 秒），并在配置中说明；禁止用户修改 `version`，必要时在启动时强制覆盖。
- [x] 为 SocketIO 选择一个合理的默认 `port`，允许用户在配置中修改。
- [x] 预留数据库版本迁移入口（例如配置中的 `version` 与 SQLite 中的 schema 版本字段对齐）。

## 2. SQLite 存储层设计
- [x] 选型并集成 SQLite 驱动（确认 JAR 获取方式，与 Gradle 配置），保证在 1.16.5 - 1.20.1 均可正常加载。
- [x] 在插件数据目录下创建 SQLite 文件（与配置文件同级），封装获取连接的工具类，确保线程安全。
- [x] 设计并记录表结构（游戏数据、成就信息、统计信息、MTR 数据、MTR 文件扫描标记、数据库版本信息等）。
  - [x] 游戏数据表：记录玩家加入/退出时间、IP、UUID、世界名、维度标识、坐标等。
  - [x] 成就信息表：以 key-value 形式存储 per-player 的 advancements。
  - [x] 统计信息表：以 key-value 形式存储 per-player 的 stats。
  - [x] MTR 日志表：存储 CSV 各字段（时间戳、玩家名、UUID、Class、ID、Name、Position、Change、Old Data、New Data 等），外加来源文件路径、行号等上下文字段。
  - [x] MTR 文件索引表：记录每个已处理 CSV 文件路径、最近修改时间、最后处理时间、是否已处理等，用于 Diff 与跳过已处理文件。
  - [x] 数据库版本表：记录当前 schema 版本及变更时间。
- [x] 编写建表和初始 schema 初始化逻辑（在插件启动或首次连接 SQLite 时执行）。
- [x] 设计基础的增删改查接口（DAO 或轻量封装），保证全部在异步线程中使用。

## 3. 世界目录定位与文件访问（Bukkit API）
- [x] 使用 Bukkit API 获取服务器 world 列表及 world folder（不要手写路径拼接）。
- [x] 为每个需要的世界确定以下目录（可能因 Mod/多维度而不同，参考 `examples/`）：
  - [x] `advancements/` 目录路径。
  - [x] `stats/` 目录路径。
  - [x] MTR 相关 `mtr/**/logs` 目录路径（例如 `world/mtr/minecraft/overworld/logs`、`world/mtr/urushi/kakuriyo/logs`、`world/minecraft/plotworld/logs` 等）。
- [x] 在每个 world 文件夹下递归查找所有 `logs/*.csv` 文件，并从路径中解析维度/子世界标识（例如 `minecraft/overworld`、`urushi/kakuriyo` 等）以存入数据库。
- [x] 封装一个“世界文件系统访问”工具类：提供通过 Bukkit `World` + 相对路径获取 `File`/`Path` 的统一方法。

## 4. 玩家在线/离线事件与游戏数据记录
- [x] 注册并实现玩家加入事件监听（如 `PlayerJoinEvent`），在事件中获取 IP、UUID、世界、坐标等。
- [x] 注册并实现玩家离开事件监听（如 `PlayerQuitEvent`），记录离开时对应信息。
- [x] 在事件中仅收集必要数据并投递到异步任务中，避免在事件回调中做任何 IO 操作。
- [x] 在异步任务中写入 SQLite 游戏数据表，确保插入失败时记录错误日志。

## 5. Advancements/Stats 文件读取与 Diff 更新
- [x] 研究 Minecraft world 下 `advancements/` 与 `stats/` JSON 文件结构（以 UUID 为文件名的 player 文件），结合 `examples/` 验证。
- [x] 设计 Advancements / Stats 的内部数据结构（例如 `player_uuid`、`key`、`value`、`last_updated_time`）。
- [x] 实现基于文件最后编辑时间的 Diff 机制。
  - [x] 记录每个玩家 advancements/stats 文件的最后处理时间与上次修改时间（可存入 SQLite）。
  - [x] 定时（根据 `interval_time`，单位为 tick）扫描相关目录，只处理被修改过的文件。
  - [x] 对单个文件，解析 JSON 并将所有 key-value 写入 SQLite（插入或更新）。
- [x] 确保整个扫描/解析/写入过程都在异步任务中执行，不阻塞主线程。
- [x] 完成一次全量扫描后，在 Console 输出本轮扫描耗时、处理文件数量、插入/更新条数等信息。

## 6. MTR CSV 日志读取与增量同步
- [x] 根据 `examples/world/mtr/...` 分析 MTR CSV 格式，确认字段顺序与可能的特殊字符（换行、多行 JSON 字段等）。
- [x] 设计稳健的 CSV 解析方案（考虑引号、多行字段），避免简单 `split(",")` 造成解析错误。
- [x] 利用 MTR 文件索引表记录每个 CSV 文件的处理状态：首次启动时允许较长时间的全量扫描，后续仅处理最近修改且未处理过的文件（如最近日期的前 2 个文件）。
- [x] 控制每次扫描处理的文件数量或时间片，避免一次性 IO 过大（MTR Logs 很多，总体读取耗时 10 分钟内可接受，但应分批完成）。
- [x] 将解析出的每条日志写入 MTR 日志表，并关联来源文件与上下文信息。
- [x] 将 MTR 日志扫描逻辑放入独立的异步任务组，按 `interval_time` 或单独配置定期执行。

## 7. SocketIO 服务与接口标准
- [x] 设计 SocketIO 服务整体结构：在插件内启动 SocketIO 服务器（端口来自配置），使用固定 namespace（如需要）。
- [x] 统一事件命名风格为下划线风格（snake_case），例如：`force_update`、`get_player_advancements`、`get_player_stats`、`list_online_players`、`get_server_time` 等。
- [x] 设计请求/响应 JSON 结构（包含 `key` 校验字段、command/payload 格式），并兼容后端的使用习惯。
- [x] 实现以下基础命令（后续可扩展）：
  - [x] 强制更新数据到 SQLite 内（触发一次 Adv/Stats/MTR 异步同步）。
  - [x] 获取指定 UUID 用户的 advancements / stats 的 value（支持联查）。
  - [x] 查询服务器在线玩家名单（包括生命数值、游戏模式）。
  - [x] 查询服务器当前时间及时间是否锁死（`gamerule doDaylightCycle`）。
- [x] 在 `docs/milestone/20251117` 下创建/维护一份给后端接入的 SocketIO 接口标准文档，记录所有事件、字段和示例。

