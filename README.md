# Vicky | 未奇

![未奇(AI生成)](src/main/resources/vicky.png)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.zenthxsin/vicky)](https://central.sonatype.com/artifact/io.github.zenthxsin/vicky)
[![Release](https://img.shields.io/github/v/release/zenthxsin/vicky)](https://github.com/zenthxsin/vicky/releases)
[![License](https://img.shields.io/github/license/zenthxsin/vicky)](https://github.com/zenthxsin/vicky/blob/main/LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/zenthxsin/vicky/release.yml?branch=main)](https://github.com/zenthxsin/vicky/actions)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/)

灵感来源于**SCP: 4K**中**黑洞纪元**的舰载**人工智能** -- **未奇**

一个精简、易维护的 Kotlin Agent 框架，对接 **OpenAI 通用协议**（兼容任意 OpenAI 协议端点：OpenAI / DeepSeek / one-api / 本地推理服务等）。

底层用 [aallam/openai-kotlin](https://github.com/aallam/openai-kotlin) 作 API 客户端，agent 主循环、模式、权限、工具注册全部自写，方便按需改造。

## 特性

### 核心能力

- **三种内置处理模式（可自定义）**
  - `SILENT`：传工具；agent 文本**不**输出给 user，只有工具产生的 `userReply` 会发出。
  - `VERBOSE`：传工具；agent 文本和工具 `userReply` 都发给 user。
  - `CHAT`：纯聊天；不传工具，agent 文本直接发给 user。
  - 模式是可继承的抽象类 `AgentMode`，可自定义新模式。
- **工具调用双输出**：每个工具返回 `ToolResult(toAgent, userReply)`，`toAgent` 喂回 agent 继续推理，`userReply`（可选）**实时**推送给 user。
- **步数耗尽汇报**：`maxSteps` 用尽时追加系统提示，让模型整理已有信息并向用户汇报。
- **单次无状态**：`receive(clearContextAfter = true)` 本轮结束后自动清空该会话上下文。
- **权限系统**：`ToolAuthorizer.allow(userId, toolName)` 框架层鉴权。
- **运行时增删工具**：`registerTool` / `unregisterTool`，下一轮自动反映到 system prompt。
- **上下文压缩**：`ContextCompactor` 在会话历史超出 token 上限时自动调用 LLM 生成摘要。
- **自动拼接上下文**：每轮重建 system prompt = `agentMd` + 内置安全防护 + 模式说明 + 可用工具摘要 + 记忆。
- **内置安全防护**：始终拼接、无法被 `agentMd` 关闭，抵御提示词反取 / 注入 / 越狱。

### 语义记忆系统（Qdrant）

- **长时记忆（RAG）**：对话知识持久化到 Qdrant 向量数据库，每轮自动 recall 注入 system prompt。
- **记忆蒸馏**：定时（默认每天凌晨 2:00）将原始对话通过 LLM 压缩为精炼记忆。
- **双层存储**：原始对话（RawMemory）+ 蒸馏记忆（Memory），原始信息保留用于回溯。
- **语义搜索**：基于向量相似度的记忆检索，支持按用户过滤。
- **手动工具**：
  - `memory_store`：手动存储重要信息到长期记忆。
  - `memory_search`：语义搜索记忆。
  - `memory_distill`：手动触发记忆蒸馏。

### 文件语义搜索

- **自动索引**：Agent 启动时自动索引根目录下的文本文件（增量索引，只处理新增/修改的文件）。
- **分块存储**：文件按段落分块，向量化后存入 Qdrant。
- **语义搜索**：`file_search` 工具支持按语义搜索已索引的文件，返回路径 + 内容片段 + 相关度分数。
- **后台异步**：文件索引在后台线程执行，不阻塞 Agent 启动。

### 内置工具

`AgentConfig.builtinTools = true`（默认）自动注册：

| 工具 | 说明 |
|------|------|
| `clear_context` | 清除当前会话上下文，下一条消息起生效 |
| `github` | 浏览 GitHub 仓库；传 `repo` + `path`，支持 `GITHUB_TOKEN` 环境变量 |
| `file_read` | 读取本地文本文件内容 |
| `file_write` | 写入本地文本文件（支持追加） |
| `file_list` | 列出目录内容，支持 glob 过滤 |
| `file_search` | 语义搜索已索引的文件 |
| `memory_store` | 手动存储记忆 |
| `memory_search` | 语义搜索记忆 |
| `memory_distill` | 手动触发记忆蒸馏 |

### 动态脚本系统（vicky-script）

- **TypeScript 脚本**：用户编写 `.ts` 文件定义 Tool，无需编译宿主应用。
- **热加载**：修改脚本后自动重载，无需重启 Agent。
- **类自动注入**：classpath 上的类（Vicky 框架类、Java 标准库等）在脚本中直接可用，无需 import。
- **全量 API 访问**：脚本可访问 ToolContext（会话历史、工具注册表、消息缓冲区等），能力等同原生 Kotlin Tool。
- **内嵌 TypeScript 编译器**：自动将 `.ts` 编译为 `.js`，用户无需安装 Node.js。

使用示例（`config/scripts/hello.ts`）：

```typescript
var name = "hello";
var description = "打招呼工具";
var parameters = {
    type: "object",
    properties: { name: { type: "string", description: "用户名" } },
    required: ["name"]
};

async function execute(ctx, args) {
    return {
        toAgent: "greeted " + args.name,
        userReply: "你好 " + args.name + "！"
    };
}
```

脚本中可直接使用注入的类：

```typescript
var f = new File("./config/config.json");
var content = Files.readString(f.toPath());
var now = LocalDateTime.now();
```

### 调试输出

- `debug = true`：框架运行日志（每步推理、工具调用、上下文清除等）。
- `think = true`：agent 中间思考文本。

两者都通过 `MessageSink` 推送（`OutboundMessage.Debug` / `Think`），由外部决定如何展示。

## 项目结构

```
src/main/kotlin/org/example/vicky/
  agent/
    Agent.kt              # 抽象基类 + 主循环（核心）
    AgentConfig.kt        # 配置数据类（模型/Qdrant/记忆/蒸馏/文件索引）
    AgentMode.kt          # SILENT / VERBOSE / CHAT + 可继承自定义
    EmbeddingConfig.kt    # 语义模型配置（内置/外置互斥）
  io/
    InboundMessage.kt     # 入站: userId + content + conversationId
    OutboundMessage.kt    # 出站 sealed: AgentReply / ToolReply / Debug / Think
    MessageSink.kt        # 出口 fun interface
  tool/
    Tool.kt               # 工具抽象类（支持 ToolContext 扩展重载）
    ToolContext.kt        # 工具运行时上下文
    ToolResult.kt         # toAgent + 可选 userReply
    ToolAuthorizer.kt     # 权限 fun interface
    ToolRegistry.kt       # 线程安全注册表，运行时增删
    builtin/
      BuiltinTools.kt     # 内置工具集合入口
      ClearContextTool.kt # 清除会话上下文
      GithubTool.kt       # 访问 GitHub 仓库
      FileTools.kt        # 文件读/写/列目录
      FileSearchTool.kt   # 文件语义搜索
      MemoryStoreTool.kt  # 手动存储记忆
      MemorySearchTool.kt # 语义搜索记忆
      MemoryDistillTool.kt # 手动触发记忆蒸馏
  context/
    ContextBuilder.kt     # system prompt 拼接 + 内置安全防护
    ContextCompactor.kt   # 上下文压缩/摘要（超出 token 上限时自动触发）
    ConversationStore.kt  # 内存级会话历史
  llm/
    OpenAiClientFactory.kt   # LLM 客户端工厂
    EmbeddingClient.kt        # 语义向量客户端接口
    EmbeddingClientFactory.kt # Embedding 客户端工厂
    OpenAiEmbeddingClient.kt  # OpenAI 协议 Embedding 实现
    BuiltinEmbeddingClient.kt # 本地 DJL + HuggingFace Embedding 实现
  vector/
    VectorStore.kt        # 向量存储抽象接口
    QdrantVectorStore.kt  # Qdrant 实现
  memory/
    Memory.kt             # 蒸馏记忆数据模型
    RawMemory.kt          # 原始对话记忆数据模型
    MemoryStore.kt        # 记忆存储抽象接口
    QdrantMemoryStore.kt  # Qdrant 实现
    Distiller.kt          # 记忆蒸馏逻辑（LLM 压缩对话）
    DistillationScheduler.kt # 定时蒸馏调度器
  file/
    FileIndexService.kt   # 文件分块 + 向量化 + 增量索引
  channel/
    onebot/
      OneBot.kt           # OneBot WebSocket 连接（QQ 机器人）
      MiraiTool.kt        # Mirai 工具基类
      MiraiTools.kt       # Mirai 工具集
      MessageBuffer.kt    # 消息缓冲
      GetMessagesTool.kt  # 获取群聊消息工具
  config/
    ConfigManager.kt      # JSON 配置加载/生成
  examples/
    ConsoleMain.kt        # 控制台示例

src/main/vicky-script/    # 动态脚本模块
  kotlin/org/example/vicky/script/
    ScriptEngine.kt        # Rhino JS 引擎 + TS 编译
    ScriptManager.kt       # 脚本生命周期 + 热加载
    ScriptToolBridge.kt    # JS Tool → vicky Tool 适配
    ClassAutoRegistry.kt   # 类自动注入
    ScriptConfig.kt        # 配置模型
  resources/
    typescript.js           # TypeScript 编译器

src/main/vicky-ksp/        # KSP 注解处理器模块
  kotlin/org/example/vicky/ksp/
    VickyToolProcessor.kt  # @VickyTool 注解处理
```

## 快速开始

从Maven依赖:

```kotlin
dependencies {
    implementation 'io.github.zenthxsin:vicky:xxx'
}
```

### 1. 运行

### 1. 直接运行 JAR（推荐）**
```bash
java -jar 从releases获取的包.jar
```

### 2. 写一个 Agent 子类

```kotlin
class MyAgent(config: AgentConfig) : Agent(config) {
    override val sink = MessageSink { out ->
        when (out) {
            is OutboundMessage.AgentReply -> sendToUser(out.content)
            is OutboundMessage.ToolReply  -> sendToUser(out.content)
            is OutboundMessage.Debug      -> log(out.content)
            is OutboundMessage.Think      -> log(out.content)
        }
    }
    override val authorizer = ToolAuthorizer { userId, toolName ->
        if (toolName == "shutdown") userId == "admin" else true
    }
}
```

### 3. 配置并运行

```kotlin
val agent = MyAgent(
    AgentConfig(
        model        = ModelId("deepseek-v4-flash"),
        apiKey       = "sk-...",
        baseUrl      = "http://192.168.0.108:3000/v1",
        mode         = AgentMode.SILENT,
        maxSteps     = 6,
        agentMd      = "你是 Vicky，一个简洁的助手。",
        debug        = false,
        think        = false,
        builtinTools = true,
    )
)
agent.registerTool(MyTool())
agent.receive(InboundMessage("user1", "ping 192.168.0.108"))
```

控制台示例：`./gradlew run`（在 `ConsoleMain.kt` 里填好 apiKey / baseUrl / model）。

## 启用记忆系统

### 1. 配置 Qdrant

启动 Qdrant：
```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

### 2. 配置 Embedding

在 `config/config.json` 中配置语义模型：

```json
{
    "embedding": {
        "enabled": true,
        "type": "external",
        "external": {
            "baseUrl": "https://api.openai.com/v1",
            "apiKey": "sk-...",
            "model": "text-embedding-3-small"
        }
    }
}
```

### 3. 启用 Qdrant 和记忆

```json
{
    "qdrant": {
        "enabled": true,
        "host": "localhost",
        "httpPort": 6333
    },
    "memory": {
        "enabled": true,
        "topK": 5,
        "tokenBudget": 800
    }
}
```

### 4. 使用记忆工具

- **存储记忆**：告诉 Agent "记一下，XXX 是我的同事"
- **搜索记忆**：Agent 会自动 recall 相关记忆
- **手动搜索**：Agent 调用 `memory_search` 工具
- **手动蒸馏**：Agent 调用 `memory_distill` 工具

## 启用文件语义搜索

在 `config/config.json` 中启用：

```json
{
    "memory": {
        "fileIndexEnabled": true,
        "fileIndexChunkSize": 500,
        "fileIndexChunkOverlap": 50
    }
}
```

启动时 Agent 会自动索引根目录下的文本文件。使用 `file_search` 工具进行语义搜索。

## 配置参数

### 基础配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `model` | "deepseek-v4-flash" | LLM 模型 ID |
| `apiKey` | "" | API 密钥 |
| `baseUrl` | null | OpenAI 兼容端点，null = 官方 |
| `maxSteps` | 8 | 单次 receive 最大推理轮数 |
| `maxMemoryRounds` | 50 | 最多保留多少轮用户消息 |
| `maxContextLength` | 0 | 上下文 token 上限，0 = 不限制 |
| `mode` | "SILENT" | 运行模式：SILENT/VERBOSE/CHAT |
| `debug` | false | 框架运行日志 |
| `think` | false | Agent 中间思考文本 |

### Embedding 配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `embedding.enabled` | false | 是否启用语义模型 |
| `embedding.type` | "builtin" | builtin（本地）或 external（远程） |
| `embedding.external.baseUrl` | "" | Embedding API 端点 |
| `embedding.external.apiKey` | "" | API 密钥 |
| `embedding.external.model` | "" | 模型 ID |
| `embedding.builtin.model` | "sentence-transformers/all-MiniLM-L6-v2" | 本地模型 |
| `embedding.builtin.proxy` | "" | 代理地址 |

### Qdrant 配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `qdrant.enabled` | false | 是否启用 Qdrant |
| `qdrant.host` | "localhost" | Qdrant 地址 |
| `qdrant.httpPort` | 6333 | HTTP 端口 |
| `qdrant.grpcPort` | 6334 | gRPC 端口 |

### 记忆配置

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

## 自定义工具

### 继承方式

```kotlin
class PingTool : Tool() {
    override val name = "ping"
    override val description = "测量到指定主机的网络延迟。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("host") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("host")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val result = doPing(args["host"]!!.jsonPrimitive.content)
        return ToolResult(toAgent = result, userReply = result)
    }
}
```

### 函数式工厂（无需继承）

```kotlin
val nowTool = tool(
    name = "now",
    description = "Return the current local date-time.",
    parameters = buildJsonObject { put("type", "object") },
) { _, _ ->
    val now = java.time.LocalDateTime.now().toString()
    ToolResult(toAgent = now, userReply = "现在时间：$now")
}
agent.registerTool(nowTool)
```

### 访问框架上下文（`ToolContext`）

```kotlin
class MyTool : Tool() {
    // ...
    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        ctx.store.clear(ctx.conversationId)
        return ToolResult(toAgent = "done")
    }
}
```

## 自定义模式

```kotlin
object ReviewMode : AgentMode() {
    override val name = "REVIEW"
    override val toolsEnabled = true
    override val emitAgentText = false
    override val instructions = "You are reviewing code. Only speak to the user through tools."
}

val agent = MyAgent(AgentConfig(model = ..., apiKey = ..., mode = ReviewMode))
```

三种内置模式对照：

| 模式 | `toolsEnabled` | `emitAgentText` | 工具 `userReply` |
|------|:--------------:|:---------------:|:----------------:|
| SILENT  | ✅ | ❌ | ✅ |
| VERBOSE | ✅ | ✅ | ✅ |
| CHAT    | ❌ | ✅ | —（无工具） |

## system prompt 拼接顺序

1. `agentMd` —— 人设/指令
2. `# Security` —— 内置安全防护（固定拼接，不可关闭）
3. `# Memory` —— 长期记忆 recall（如果启用）
4. `# Output rules` —— 当前模式说明
5. `# Available tools` —— 已注册工具名 + 简介

## 主循环

```
1. recall 蒸馏记忆 → 注入 system prompt
2. 用户消息追加到 history
3. for step in 0 until maxSteps:
     resp = chat.completion(history + tools)
     if resp.toolCalls 非空:
         执行工具 → result
         history += assistantToolCall + toolMessage(result.toAgent)
         result.userReply?.let { emit(ToolReply) }
         continue
     else:
         emit(AgentReply)
         return
4. 步数耗尽：注入系统提示，让模型整理现状并向用户汇报
5. finally: 保存原始记忆到 Qdrant
```

## 架构图

```
                    ┌─────────────────────────────────────┐
                    │              Agent                  │
                    │  receive() ──→ Recall ──→ 注入 prompt│
                    │                  ↑                   │
                    │         EmbeddingClient              │
                    │         (External / Builtin)         │
                    └──────────┬──────────────────────────┘
                               │
                    ┌──────────▼──────────────────────────┐
                    │         QdrantVectorStore           │
                    │  ┌──────────┐  ┌─────────────────┐  │
                    │  │ Qdrant   │  │ vicky_memories  │  │
                    │  │ vicky_   │  │ vicky_files     │  │
                    │  │ files    │  │ vicky_raw       │  │
                    │  └──────────┘  └─────────────────┘  │
                    └─────────────────────────────────────┘
```

## OneBot 通道（QQ 机器人）

Vicky 内置 OneBot 通道，可直接连接 QQ 机器人（通过 [NapCat](https://github.com/NapNeko/NapCatQQ)、[Lagrange](https://github.com/LagrangeDev/Lagrange.Core) 等 OneBot 协议实现）。

### 配置

在 `config/config.json` 中配置：

```json
{
    "oneBot": {
        "url": "ws://127.0.0.1:3001",
        "token": "",
        "adminList": ["488254306"]
    }
}
```

| 参数 | 说明 |
|------|------|
| `url` | OneBot WebSocket 地址 |
| `token` | 认证 token |
| `adminList` | 管理员 QQ 号列表（可使用受限工具） |

### 功能

- **私聊**：所有私聊消息触发 Agent
- **群聊**：@机器人 时触发 Agent
- **消息缓冲**：自动缓存近期群聊消息，Agent 可通过工具获取上下文
- **管理员权限**：`adminList` 中的用户可使用受限工具

### Mirai 工具集

内置以下 Mirai 工具（需管理员权限）：

| 工具 | 说明 |
|------|------|
| `bot_info` | 获取机器人信息 |
| `contacts` | 获取联系人列表 |
| `group_info` | 获取群信息 |
| `group_members` | 获取群成员列表 |
| `user_profile` | 获取用户资料 |
| `send_message` | 发送消息 |
| `group_manage` | 群管理操作 |
| `friend_manage` | 好友管理操作 |
| `group_quit` | 退出群聊 |
| `group_announcements` | 群公告管理 |
| `at` | @某人 |
| `send_image` | 发送图片 |
| `send_video` | 发送视频 |
| `friend_request` | 好友请求处理 |
| `group_invite` | 群邀请处理 |
| `member_join_request` | 加群请求处理 |
| `set_name_card` | 设置群名片 |
| `roaming_messages` | 漫游消息 |
| `group_files` | 群文件管理 |
| `get_messages` | 获取消息历史 |
| `recall` | 撤回消息 |

## 测试

```bash
./gradlew test
```

运行记忆系统测试：
```bash
./gradlew test --tests "org.example.vicky.memory.MemorySystemTest"
```

运行记忆工具测试：
```bash
./gradlew test --tests "org.example.vicky.tool.builtin.MemoryToolsTest"
```

## 许可证

Apache License 2.0
