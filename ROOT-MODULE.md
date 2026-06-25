# 根应用模块

根模块是 Vicky 的开箱即用应用，基于 vicky-core + vicky-script 构建，提供 OneBot QQ 机器人通道和完整的内置工具集。

## 快速开始

### 1. 直接运行 JAR（推荐）

```bash
java -jar Vicky-x.x.x.jar
```

首次运行会在 `config/` 目录下生成配置文件，修改 `config.json` 和 `AGENT.md` 后重新运行。

### 2. 控制台模式

`ConsoleMain.kt` 提供本地控制台交互，用于开发调试：

```bash
./gradlew run
```

## 配置文件

`config/config.json`：

```json
{
    "model": "deepseek-v4-flash",
    "apiKey": "sk-...",
    "baseUrl": "http://192.168.0.108:3000/v1",
    "maxSteps": 8,
    "mode": "SILENT",
    "debug": false,
    "think": false,
    "streaming": true,
    "builtinTools": true,
    "agentMd": "AGENT.md",

    "oneBot": {
        "url": "ws://127.0.0.1:3001",
        "token": "",
        "adminList": ["488254306"],
        "groupWhitelist": [],
        "userWhitelist": []
    },

    "embedding": {
        "enabled": false,
        "type": "external",
        "external": {
            "baseUrl": "https://api.openai.com/v1",
            "apiKey": "sk-...",
            "model": "text-embedding-3-small"
        }
    },

    "qdrant": {
        "enabled": false,
        "host": "localhost",
        "httpPort": 6333,
        "grpcPort": 6334
    },

    "memory": {
        "enabled": false,
        "topK": 5,
        "tokenBudget": 800,
        "maxPerUser": 500,
        "expiryDays": 90,
        "rawRetentionDays": 30,
        "distilledRetentionDays": 7,
        "collection": "vicky_memories",
        "rawCollection": "vicky_memories_raw",
        "distillationEnabled": true,
        "distillationSchedule": "0 2 * * *",
        "distillationMaxConversations": 10,
        "fileIndexEnabled": false,
        "fileIndexCollection": "vicky_files",
        "fileIndexChunkSize": 500,
        "fileIndexChunkOverlap": 50,
        "fileIndexAutoIndexOnStart": true
    }
}
```

## OneBot 通道（QQ 机器人）

Vicky 内置 OneBot 通道，可直接连接 QQ 机器人（通过 [NapCat](https://github.com/NapNeko/NapCatQQ)、[Lagrange](https://github.com/LagrangeDev/Lagrange.Core) 等 OneBot 协议实现）。

### 功能

- **私聊**：所有私聊消息触发 Agent
- **群聊**：@机器人 时触发 Agent
- **消息缓冲**：自动缓存近期群聊消息，Agent 可通过 `get_messages` 工具获取上下文
- **管理员权限**：`adminList` 中的用户可使用受限工具
- **白名单**：`groupWhitelist` / `userWhitelist` 控制哪些群/用户可以触发 Agent

### OneBot 配置

| 参数 | 说明 |
|------|------|
| `url` | OneBot WebSocket 地址 |
| `token` | 认证 token |
| `adminList` | 管理员 QQ 号列表 |
| `groupWhitelist` | 允许触发 Agent 的群号列表 |
| `userWhitelist` | 允许触发 Agent 的用户 QQ 号列表 |

## 内置工具（注解式）

`builtinTools = true`（默认）时自动注册。工具通过 `@VickyTool` 注解定义在 `BuiltinToolImpl` 中。

### 基础工具

| 工具 | 说明 |
|------|------|
| `clear_context` | 清除当前会话上下文 |
| `github` | 浏览 GitHub 仓库；传 `repo` + `path`，支持 `GITHUB_TOKEN` 环境变量 |
| `file_read` | 读取本地文本文件（截断 20K 字符） |
| `file_write` | 写入本地文本文件（支持追加） |
| `file_list` | 列出目录内容，支持 glob 过滤 |
| `web_download_file` | 下载 HTTP(S) 文件到 config/tmp |
| `file_extract` | 解压 .zip/.jar 文件（含 Zip Slip 防护） |
| `manage_tools` | 列出/启用/禁用 工具（运行时开关） |

### 记忆工具

| 工具 | 说明 |
|------|------|
| `memory_store` | 手动存储信息到长期记忆 |
| `memory_search` | 语义搜索记忆 |
| `memory_distill` | 手动触发记忆蒸馏 |
| `file_search` | 语义搜索已索引的文件 |
| `file_index` | 手动触发后台文件索引 |

### 技能工具

| 工具 | 说明 |
|------|------|
| `invoke_skill` | 加载技能全文指南 |
| `manage_skills` | 🔒 list / enable / disable / delete 技能 |

### 脚本工具

| 工具 | 说明 |
|------|------|
| `manage_scripts` | 🔒 list / load / unload / reload / load_all 脚本 |

## Mirai 工具集（OneBot 通道专用）

通过 OneBot/Mirai 连接时自动注册。需要管理员权限的工具用 🔒 标记。

### 信息查询

| 工具 | 说明 |
|------|------|
| `bot_info` | 获取机器人信息 |
| `contacts` | 获取联系人列表（friends/groups/strangers） |
| `group_info` | 获取群详细信息 |
| `group_members` | 获取群成员列表 |
| `user_profile` | 获取用户资料 |
| `get_messages` | 查询消息缓冲区（text/media/raw） |
| `roaming_messages` | 查询漫游历史消息 |
| `group_files` | 群文件管理（list/info/delete/rename/url） |

### 消息操作

| 工具 | 说明 |
|------|------|
| `send_message` | 🔒 发送消息（group/friend/temp） |
| `at_member` | @某人 |
| `reply_message` | 回复（引用）特定消息 |
| `recall_message` | 🔒 撤回消息 |
| `send_image` | 🔒 发送图片 |
| `send_video` | 🔒 发送视频 |
| `essence_message` | 🔒 精华消息管理（list/add/remove） |

### 群管理

| 工具 | 说明 |
|------|------|
| `group_manage` | 🔒 群管理（mute/kick/set_admin/rename/mute_all/title） |
| `group_quit` | 退出群聊 |
| `group_announcements` | 群公告管理（list/publish/delete） |
| `set_name_card` | 🔒 设置群名片 |
| `group_whitelist_add` | 🔒 将当前群加入 Agent 回复白名单 |

### 好友/请求管理

| 工具 | 说明 |
|------|------|
| `friend_manage` | 🔒 好友管理（delete/remark） |
| `friend_request` | 好友请求处理（list/accept/reject） |
| `group_invite` | 群邀请处理（accept/ignore） |
| `member_join_request` | 加群请求处理（accept/reject/ignore） |

## Embedding 配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `embedding.enabled` | false | 是否启用语义模型 |
| `embedding.type` | "builtin" | builtin（本地）或 external（远程） |
| `embedding.external.baseUrl` | "" | Embedding API 端点 |
| `embedding.external.apiKey` | "" | API 密钥 |
| `embedding.external.model` | "" | 模型 ID |
| `embedding.builtin.model` | "sentence-transformers/all-MiniLM-L6-v2" | 本地模型 |
| `embedding.builtin.proxy` | "" | 代理地址 |

## Qdrant 配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `qdrant.enabled` | false | 是否启用 Qdrant |
| `qdrant.host` | "localhost" | Qdrant 地址 |
| `qdrant.httpPort` | 6333 | HTTP 端口 |
| `qdrant.grpcPort` | 6334 | gRPC 端口 |

## 记忆配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `memory.enabled` | false | 是否启用记忆系统 |
| `memory.topK` | 5 | 每轮 recall 检索条数 |
| `memory.tokenBudget` | 800 | 记忆注入 token 预算 |
| `memory.maxPerUser` | 500 | 每用户最大蒸馏记忆数 |
| `memory.expiryDays` | 90 | 蒸馏记忆过期天数 |
| `memory.rawRetentionDays` | 30 | 未蒸馏原始记忆保留天数 |
| `memory.distilledRetentionDays` | 7 | 已蒸馏原始记忆保留天数 |
| `memory.collection` | "vicky_memories" | 蒸馏记忆 collection |
| `memory.rawCollection` | "vicky_memories_raw" | 原始记忆 collection |

### 蒸馏配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `memory.distillationEnabled` | true | 是否启用定时蒸馏 |
| `memory.distillationSchedule` | "0 2 * * *" | cron 表达式（默认凌晨 2:00） |
| `memory.distillationMaxConversations` | 10 | 每次蒸馏最多处理对话组数 |

### 文件索引配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `memory.fileIndexEnabled` | false | 是否启用文件索引 |
| `memory.fileIndexCollection` | "vicky_files" | 文件索引 collection |
| `memory.fileIndexChunkSize` | 500 | 分块大小（字符） |
| `memory.fileIndexChunkOverlap` | 50 | 分块重叠（字符） |
| `memory.fileIndexAutoIndexOnStart` | true | 启动时自动索引 |

## 示例

### ConsoleMain

控制台交互示例，注册了 `echo`、`shutdown`、`now` 三个示例工具。

### StreamDumpMain

流式输出调试示例。

### MindustryMITToolImpl

Mindustry 游戏工具集示例（`@ToolGroup(name = "mindustry")`），展示注解式工具用法。

## 启用 Qdrant 记忆系统

### 1. 启动 Qdrant

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

### 2. 配置 Embedding

在 `config/config.json` 中配置语义模型（external 或 builtin 二选一）。

### 3. 启用 Qdrant 和记忆

在 `config/config.json` 中设置 `qdrant.enabled: true` 和 `memory.enabled: true`。

### 4. 使用

- **存储记忆**：告诉 Agent "记一下，XXX 是我的同事"
- **搜索记忆**：Agent 会自动 recall 相关记忆
- **手动搜索**：Agent 调用 `memory_search` 工具
- **手动蒸馏**：Agent 调用 `memory_distill` 工具
