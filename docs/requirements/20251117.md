# 简述

我现在希望开发一个，能够自动读取服务器 world 文件夹下的 advancemtns/ 和 stats/ 以及 Mod MTR 的文件夹 mtr/ 的数据，并且同步到我的后端的 Bukkit 插件。以 1.16.5 - 1.20.1 兼容为核心。

开发准则：**必须**使用 Bukkit API；必须**全部使用异步**，不阻塞 Minecraft 主线程。

Examples 已经提供，请参考 examples/。参照下文，生成详细的 TODO List 在 docs/milestone/20251117 下，逐个完成，逐个修复。目前项目已经搭建了最基本的 1.16.5 架构，如果遇到任何疑问，立刻询问我。记得配置 SystemProxy 7890，我已经启动 Clash。

## 描述

### 主要技术和传输流程

- 我们的插件读取到数据以后，将数据以 Key-Value 的形式直接存储到 SQLite 数据库内。随后，使用 SocketIO 建立一个 WebSocket 服务器，由我的后端进行连接，实时查询。【解耦合，后端也会配置 Redis 缓存】数据库的 SQLite db 文件存储到插件配置文件同级目录下。

- 具体来说，我们的插件需要一个配置文件，默认配置 port、key 和 interval_time、version。key 是一个 64 位的随机字符，需要后端连接的时候附带。如果插件第一次启动（也就是无 key），那么需要自动在配置文件内生成 key。interval_time 是拉取时间。插件自动更新数据到 SQLite 内的时间；version 是数据库版本。插件自动更新，禁止用户修改。

- SocketIO 内，暂时规定这几种 command，如果有必要，你自行添加：强制更新数据到 SQLite 内、获取某个指定 uuid 用户的 advancements、stats 的 value（要支持联查）查询、支持查询服务器在线玩家名单（包括生命数值、游戏模式）、服务器当前时间（以及时间是否锁死：gamerule dodayrecycle）。

- 通过 Diff 的方式添加数据到 SQLite 中，通过检测文件最近编辑时间来判断是否编辑；至于 MTR 数据可以单独记录一个表，老早以前的数据（比如 2 年前），就第一次读取的时候存入即可；后续正常运行的时候，继续通过文件的最近编辑时间来判断有没有对比；但是没有必要把几年前的都扫一遍，只需要扫日期最新的前 2 个文件。

### 存储结构

#### 游戏数据

当玩家加入游戏、退出游戏的时候需要记录。加入/退出的时候都需要记录 IP、UUID、加入离开的世界、加入离开的维度、加入离开的坐标。

其余游戏数据不需要。

#### 成就信息

Key Value 格式的 JSON，直接读取即可，你直接搜索 Minecrtaft World Advancement 都行。

#### 统计信息

Key Value 格式的 JSON，直接读取即可，你直接搜索 Minecrtaft World Advancement 都行。

#### MTR 数据

> MTR 数据可能没装 Mod 的拿不到，做好 try-catch。

我们暂时只需要读取 CSV 格式的 logs 数据，需要把这些数据存入 SQLite 数据库。

由于 CSV 命名是区分数据的命名的，我觉得你只需要标记一下哪个文件查过了就行了，不用反复查。

此外，MTR Logs 文件很多，记得不要一下子全部都读到 IO，慢慢来，分批来，全部读取 10 分钟都没事。

路径是 mtr/minecraft/overworld/logs/20230807-154307095.csv。内部格式一般为：

```
Timestamp,Player Name,Player UUID,Class,ID,Name,Position,Change,Old Data,New Data
"2023-08-21T23:32:36.375+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-5652, 65, 9402)
(-5954, 65, 8908)","CREATE","{}","{
  ""type"": ""OBSIDIAN"",
  ""one_way"": true
}"
"2023-08-21T23:34:59.700+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-5652, 65, 9427)
(-5954, 65, 8908)","CREATE","{}","{
  ""type"": ""OBSIDIAN"",
  ""one_way"": true
}"
"2023-08-22T00:22:09.884+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6184, 65, 8743)
(-6192, 65, 8715)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:22:11.919+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6178, 65, 8767)
(-6192, 65, 8715)","DELETE","{
  ""type"": ""STONE"",
  ""one_way"": false
}","{}"
"2023-08-22T00:22:18.974+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6178, 65, 8767)
(-6184, 65, 8743)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:23:03.715+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.block.BlockNode","","","(-6163, 65, 8778)","DELETE","{
  ""type"": ""TRAIN""
}","{}"
"2023-08-22T00:23:18.043+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.block.BlockNode","","","(-6163, 65, 8774)","DELETE","{
  ""type"": ""TRAIN""
}","{}"
"2023-08-22T00:23:28.188+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.block.BlockNode","","","(-6163, 65, 8792)","DELETE","{
  ""type"": ""TRAIN""
}","{}"
"2023-08-22T00:23:36.679+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6157, 65, 8817)
(-6184, 65, 8743)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:23:50.638+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.block.BlockNode","","","(-6172, 65, 8772)","DELETE","{
  ""type"": ""TRAIN""
}","{}"
"2023-08-22T00:23:51.593+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.block.BlockNode","","","(-6172, 65, 8772)","DELETE","{
  ""type"": ""TRAIN""
}","{}"
"2023-08-22T00:23:58.013+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6163, 65, 8808)
(-6172, 65, 8772)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:24:02.465+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6163, 65, 8808)
(-6164, 65, 8817)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:24:12.555+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6164, 65, 8817)
(-6172, 65, 8772)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:24:16.274+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.block.BlockNode","","","(-6163, 65, 8808)","DELETE","{
  ""type"": ""TRAIN""
}","{}"
"2023-08-22T00:24:25.836+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6172, 65, 8772)
(-6184, 65, 8743)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:24:33.053+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.block.BlockNode","","","(-6157, 65, 8817)","DELETE","{
  ""type"": ""TRAIN""
}","{}"
"2023-08-22T00:24:39.123+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6157, 65, 8817)
(-6172, 65, 8772)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:25:43.940+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6192, 65, 8763)
(-6192, 65, 8817)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:29:21.233+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6157, 65, 8826)
(-6157, 65, 9001)","CREATE","{}","{
  ""type"": ""SIDING"",
  ""one_way"": false
}"
"2023-08-22T00:29:39.904+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Depot","[-2818013005547544212]","","","CREATE","{}","{
  ""id"": -2818013005547544212,
  ""transport_mode"": ""TRAIN"",
  ""name"": """",
  ""color"": 0,
  ""x_min"": -6116,
  ""z_min"": 9014,
  ""x_max"": -6223,
  ""z_max"": 8790,
  ""route_ids"": [],
  ""use_real_time"": false,
  ""repeat_infinitely"": false,
  ""cruising_altitude"": 256,
  ""frequencies"": [
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0
  ],
  ""departures"": []
}"
"2023-08-22T00:29:49.998+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Depot","[-2818013005547544212]","颐恒西动车所","","EDIT","{
  ""name"": """",
  ""color"": 0
}","{
  ""name"": ""颐恒西动车所"",
  ""color"": 323615
}"
"2023-08-22T00:30:04.850+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Siding","[7156004386428816591]","1","","EDIT","{
  ""train_custom_id"": ""sp1900"",
  ""train_type"": ""train_24_2""
}","{
  ""train_custom_id"": ""d51_s_train_small"",
  ""train_type"": ""train_19_2""
}"
"2023-08-22T00:30:25.272+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Siding","[7156004386428816591]","1","","EDIT","{
  ""train_custom_id"": ""d51_s_train_small"",
  ""train_type"": ""train_19_2""
}","{
  ""train_custom_id"": ""mtr_custom_train_london_underground_1996_roundel"",
  ""train_type"": ""london_underground_1996""
}"
"2023-08-22T00:30:35.971+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Siding","[7156004386428816591]","1","","EDIT","{
  ""train_custom_id"": ""mtr_custom_train_london_underground_1996_roundel"",
  ""train_type"": ""london_underground_1996""
}","{
  ""train_custom_id"": ""class_802_tpe"",
  ""train_type"": ""train_24_2""
}"
"2023-08-22T00:30:47.924+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Siding","[7156004386428816591]","1","","EDIT","{
  ""train_custom_id"": ""class_802_tpe""
}","{
  ""train_custom_id"": ""class_802_gwr""
}"
"2023-08-22T00:31:44.656+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6157, 65, 8817)
(-6157, 65, 8826)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
"2023-08-22T00:32:18.710+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Route","[290750977308666348]","","","CREATE","{}","{
  ""id"": 290750977308666348,
  ""transport_mode"": ""TRAIN"",
  ""name"": """",
  ""color"": 0,
  ""platform_ids"": [
    644171082962703400
  ],
  ""custom_destinations"": [
    """"
  ],
  ""route_type"": ""NORMAL"",
  ""is_light_rail_route"": false,
  ""is_route_hidden"": false,
  ""disable_next_station_announcements"": false,
  ""light_rail_route_number"": """",
  ""circular_state"": ""NONE""
}"
"2023-08-22T00:32:19.733+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Route","[290750977308666348]","氢西城际铁路_测试","","EDIT","{
  ""name"": """",
  ""color"": 0
}","{
  ""name"": ""氢西城际铁路_测试"",
  ""color"": 15100253
}"
"2023-08-22T00:32:29.095+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Depot","[-2818013005547544212]","颐恒西动车所","","EDIT","{
  ""route_ids"": []
}","{
  ""route_ids"": [
    290750977308666348
  ]
}"
"2023-08-22T00:32:58.795+08:00","larker_package","ae55f5de-602b-386c-b44a-53a171eb21d3","mtr.data.Rail","","","(-6203, 79, 8182)
(-6208, 80, 8188)","CREATE","{}","{
  ""type"": ""STONE"",
  ""one_way"": false
}"
```

此外，还有别的维度、Mod 世界需要扫描。例如：world/mtr/urushi/kakuriyo/logs；world/minecraft/plotworld/logs…… 更具体的用例，请访问 examples/ 文件夹。

### 附加内容

- 自动批量读取完数据后，在 Console 中 Log 一条读取情况和用时和 Diff 情况。

- Console Log 信息需要十分完善。

- SQLite 和插件设计上，要支持数据库更新跟随插件更新，插件可能会更新表结构。

- 使用 Bukkit 提供的 World Folder API，不要自己手动推算。

- 最后，在 docs/milestone/20251117 文件夹下创建一份给后端接入的 SocketIO 接口标准。
