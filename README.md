# Vicky | 未奇

![未奇(AI生成)](src/main/resources/vicky.png)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.zenthxsin/vicky-core)](https://central.sonatype.com/artifact/io.github.zenthxsin/vicky-core)
[![Release](https://img.shields.io/github/v/release/zenthxsin/vicky)](https://github.com/zenthxsin/vicky/releases)
[![License](https://img.shields.io/github/license/zenthxsin/vicky)](https://github.com/zenthxsin/vicky/blob/main/LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/zenthxsin/vicky/release.yml?branch=master)](https://github.com/zenthxsin/vicky/actions)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://www.oracle.com/java/)

灵感来源于 **SCP: 4K** 中**黑洞纪元**的舰载**人工智能** -- **未奇**

一个精简、易维护的 Kotlin Agent 框架，对接 **OpenAI 通用协议**（兼容 OpenAI / DeepSeek / one-api / 本地推理服务等）。

底层用 [aallam/openai-kotlin](https://github.com/aallam/openai-kotlin) 作 API 客户端，agent 主循环、模式、权限、工具注册全部自写，方便按需改造。

## 模块结构

项目由三个独立模块 + 一个根应用组成：

```
src/main/
├── vicky-core/          # 核心框架（Maven: io.github.zenthxsin:vicky-core）
│   └── kotlin/org/example/vicky/
│       ├── agent/           # Agent 基类、配置、模式、管理器
│       ├── annotations/     # @VickyTool @ToolParam @ToolGroup 注解
│       ├── context/         # ContextManager 接口
│       ├── io/              # InboundMessage / OutboundMessage / MessageSink
│       ├── llm/             # OpenAI 客户端工厂
│       ├── skill/           # Skill 数据模型 + SkillManager
│       └── tool/            # Tool 抽象、ToolRegistry、ToolContext、ToolResult、ToolAuthorizer
│
├── vicky-ksp/           # KSP 注解处理器（Maven: io.github.zenthxsin:vicky-ksp）
│   └── src/main/kotlin/org/example/vicky/ksp/
│       ├── VickyToolProcessor.kt       # @VickyTool 注解处理 → 生成 Tool 子类
│       └── VickyToolProcessorProvider.kt
│
├── vicky-script/        # 动态脚本模块（Maven: io.github.zenthxsin:vicky-script）
│   ├── kotlin/org/example/vicky/script/
│   │   ├── ScriptEngine.kt          # Rhino JS 引擎 + TS 编译 + Promise polyfill
│   │   ├── ScriptManager.kt         # 脚本生命周期 + 热加载
│   │   ├── ScriptToolBridge.kt      # JS Tool → vicky Tool 适配
│   │   ├── ClassAutoRegistry.kt     # classpath 类自动注入到 JS 全局
│   │   └── ScriptConfig.kt          # 配置模型
│   └── resources/
│       └── typescript.js            # 内嵌 TypeScript 4.9.5 编译器
│
└── kotlin/org/example/vicky/    # 根应用（OneBot 机器人 + 内置工具实现）
    ├── agent/EmbeddingConfig.kt
    ├── buffer/MessageBuffer.kt
    ├── channel/onebot/          # OneBot WebSocket + Mirai 工具集（注解式）
    ├── config/ConfigManager.kt
    ├── context/                 # ContextBuilder / ContextCompactor / ConversationStore / DefaultContextManager
    ├── examples/                # ConsoleMain / StreamDumpMain / MindustryMITToolImpl
    ├── file/FileIndexService.kt
    ├── llm/                     # EmbeddingClient / OpenAiEmbeddingClient / EmbeddingClientFactory
    ├── logging/SilentMiraiLoggerFactory.kt
    ├── memory/                  # Memory / RawMemory / MemoryStore / QdrantMemoryStore / Distiller / DistillationScheduler
    ├── skill/SkillFrontmatterParser.kt / SkillLoader.kt
    ├── tool/builtin/            # 注解式内置工具（BuiltinToolImpl / InvokeSkillTool / ManageSkillsTool / ToolManagementTool）
    ├── tool/file/FileDownloader.kt
    └── vector/VectorStore.kt / QdrantVectorStore.kt
```

## 核心能力

### 三种处理模式（可自定义）

| 模式 | `toolsEnabled` | `emitAgentText` | 工具 `userReply` |
|------|:--------------:|:---------------:|:----------------:|
| `SILENT` | ✅ | ❌ | ✅ |
| `VERBOSE` | ✅ | ✅ | ✅ |
| `CHAT` | ❌ | ✅ | —（无工具） |

模式是可继承的抽象类 `AgentMode`，可自定义：

```kotlin
object ReviewMode : AgentMode() {
    override val name = "REVIEW"
    override val toolsEnabled = true
    override val emitAgentText = false
    override val instructions = "You are reviewing code. Only speak to the user through tools."
}
```

### 工具系统

- **工具调用双输出**：每个工具返回 `ToolResult(toAgent, userReply)`，`toAgent` 喂回 agent 继续推理，`userReply`（可选）实时推送给 user。
- **步数耗尽汇报**：`maxSteps` 用尽时追加系统提示，让模型整理已有信息并向用户汇报。
- **权限系统**：`ToolAuthorizer.allow(userId, toolName)` 框架层鉴权。
- **运行时增删工具**：`registerTool` / `unregisterTool`，下一轮自动反映到 system prompt。

### 注解式工具定义（vicky-ksp）

使用 `@VickyTool`、`@ToolParam`、`@ToolGroup` 注解定义工具，KSP 自动生成 `Tool` 子类和 `ToolRegistry`：

```kotlin
@ToolGroup(name = "my_tools")
object MyTools {

    @VickyTool(name = "ping", description = "Ping a host and measure latency.")
    suspend fun ping(
        @ToolParam(description = "Host to ping.") host: String,
        @ToolParam(description = "Timeout in ms.", required = false) timeout: Int = 5000,
    ): ToolResult {
        val result = doPing(host, timeout)
        return ToolResult(toAgent = result, userReply = result)
    }

    // ToolContext 注入：访问会话历史、工具注册表、消息缓冲区等
    @VickyTool(name = "clear", description = "Clear conversation context.")
    fun clear(ctx: ToolContext): ToolResult {
        ctx.contextManager.clear(ctx.conversationId)
        return ToolResult(toAgent = "done", userReply = "上下文已清除。")
    }

    // userId 注入：自动接收调用者 ID，不进入参数 Schema
    @VickyTool(name = "whoami", description = "Get caller user ID.")
    fun whoami(userId: String): ToolResult =
        ToolResult(toAgent = "userId=$userId")
}
```

**特性：**
- `@ToolParam(required = false)` 或 Kotlin 默认值 → 可选参数
- `userId: String` 参数 → 自动注入调用者 ID，不进入 JSON Schema
- `ToolContext` 参数 → 注入运行时上下文（会话历史、工具注册表、消息缓冲区等）
- 返回值是 `ToolResult` 直接使用，否则自动 `toString()` 包装
- 生成代码位于 `org.example.vicky.generated` 包

### 技能系统（Skill）

技能是给 LLM 看的操作指南，通过 `SKILL.md` 文件定义：

```
config/skills/
├── code-review/
│   └── SKILL.md
└── translate/
    └── SKILL.md
```

`SKILL.md` 格式：

```markdown
---
name: code-review
description: 代码审查技能
---

你是一个代码审查专家。当用户请求代码审查时...

## 审查步骤
1. 检查代码风格
2. 检查潜在 bug
...
```

**相关工具：**
- `invoke_skill` — 加载技能全文，LLM 按指南操作
- `manage_skills` — list / enable / disable / delete 技能

### 语义记忆系统（Qdrant）

- **长时记忆（RAG）**：对话知识持久化到 Qdrant 向量数据库，每轮自动 recall 注入 system prompt。
- **记忆蒸馏**：定时（默认每天凌晨 2:00）将原始对话通过 LLM 压缩为精炼记忆。
- **双层存储**：原始对话（RawMemory）+ 蒸馏记忆（Memory），原始信息保留用于回溯。
- **语义搜索**：基于向量相似度的记忆检索，支持按用户过滤。

**相关工具：**
- `memory_store` — 手动存储重要信息到长期记忆
- `memory_search` — 语义搜索记忆
- `memory_distill` — 手动触发记忆蒸馏

### 文件语义搜索

- **自动索引**：Agent 启动时自动索引根目录下的文本文件（增量索引，只处理新增/修改的文件）。
- **分块存储**：文件按段落分块，向量化后存入 Qdrant。
- **语义搜索**：`file_search` 工具支持按语义搜索已索引的文件。

**相关工具：**
- `file_search` — 语义搜索已索引的文件
- `file_index` — 手动触发后台文件索引

### 动态脚本系统（vicky-script）

vicky-script 提供 TypeScript 脚本的编译、执行和 Tool 桥接能力。模块本身不扫描目录、不监控文件，由调用方决定何时加载。

**核心 API：**
- `ScriptManager.loadScript(file)` — 编译并执行 TS 文件，返回 `ScriptToolBridge`（即 `Tool`）
- `ScriptManager.loadScriptFromSource(tsSource, fileName)` — 从源码字符串加载
- `ScriptManager.loadAndRegister(file, registry)` — 加载并注册到 ToolRegistry
- `ScriptManager.unloadScript(fileName, registry)` — 卸载并从 registry 移除
- `ScriptManager.reloadScript(file, registry)` — 重载脚本

**特性：**
- **TypeScript 编译**：内嵌 TypeScript 4.9.5 编译器，自动将 `.ts` 编译为 `.js`
- **类自动注入**：classpath 上的类（Vicky 框架类、Java 标准库等）在脚本中直接可用，无需 import
- **全量 API 访问**：脚本可访问 ToolContext（会话历史、工具注册表、消息缓冲区等），能力等同原生 Kotlin Tool

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

脚本中可直接使用注入的类（无需 import）：

```typescript
// Java 标准库
var f = new File("./config/config.json");
var content = Files.readString(f.toPath());
var now = LocalDateTime.now();

// Vicky 框架类
var result = ToolResult({ toAgent: "ok" });
var msg = InboundMessage({ userId: "123", content: "hi" });

// kotlinx.serialization.json
var obj = buildJsonObject({ put("key", JsonPrimitive("value")) });
```

根应用提供 `manage_scripts` 工具，Agent 可通过该工具按需加载/卸载/重载脚本。

### 上下文管理

- **自动拼接上下文**：每轮重建 system prompt = `agentMd` + 内置安全防护 + 模式说明 + 可用工具摘要 + 记忆。
- **上下文压缩**：`ContextCompactor` 在会话历史超出 token 上限时自动调用 LLM 生成摘要。
- **内置安全防护**：始终拼接、无法被 `agentMd` 关闭，抵御提示词反取 / 注入 / 调试输出。

### 调试输出

- `debug = true`：框架运行日志（每步推理、工具调用、上下文清除等）。
- `think = true`：agent 中间思考文本。

两者都通过 `MessageSink` 推送（`OutboundMessage.Debug` / `Think`），由外部决定如何展示。

## 使用方式

### 作为 Maven 依赖

```kotlin
dependencies {
    implementation("io.github.zenthxsin:vicky-core:xxx")
    // 可选：KSP 注解处理器
    ksp("io.github.zenthxsin:vicky-ksp:xxx")
    // 可选：动态脚本支持
    implementation("io.github.zenthxsin:vicky-script:xxx")
}
```

### 编写 Agent 子类

```kotlin
class MyAgent(config: AgentConfig) : Agent(config) {
    override val contextManager = DefaultContextManager(
        store = ConversationStore(),
        builder = ContextBuilder(config.agentMd),
        compactor = ContextCompactor(config, OpenAiClientFactory.create(config)),
    )

    override val sink = MessageSink { out ->
        when (out) {
            is OutboundMessage.AgentReply -> println("[agent] ${out.content}")
            is OutboundMessage.ToolReply  -> println("[tool] ${out.content}")
            is OutboundMessage.Debug      -> println("[debug] ${out.content}")
            is OutboundMessage.Think      -> println("[think] ${out.content}")
        }
    }

    override val authorizer = ToolAuthorizer { userId, toolName ->
        if (toolName == "shutdown") userId == "admin" else true
    }
}
```

### 配置并运行

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
        builtinTools = true,
    )
)
agent.receive(InboundMessage("user1", "ping 192.168.0.108"))
```

## 配置参数

根应用使用 `config/config.json` 配置文件，参见 [ROOT-MODULE.md](ROOT-MODULE.md)。

### AgentConfig

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `model` | — | LLM 模型 ID |
| `apiKey` | — | API 密钥 |
| `baseUrl` | null | OpenAI 兼容端点，null = 官方 |
| `maxSteps` | 8 | 单次 receive 最大推理轮数 |
| `maxMemoryRounds` | 50 | 最多保留多少轮用户消息 |
| `maxContextLength` | 0 | 上下文 token 上限，0 = 不限制 |
| `mode` | SILENT | 运行模式：SILENT / VERBOSE / CHAT |
| `temperature` | null | 采样温度 |
| `agentMd` | "You are a helpful assistant." | system prompt 人设/指令 |
| `debug` | false | 框架运行日志 |
| `think` | false | Agent 中间思考文本 |
| `streaming` | true | 是否使用流式请求 |
| `builtinTools` | true | 是否自动注册内置工具 |

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
         if result.endTurn → return
         continue
     else:
         emit(AgentReply)
         return
4. 步数耗尽：注入系统提示，让模型整理现状并向用户汇报
5. finally: 保存原始记忆到 Qdrant
```

## 测试

```bash
./gradlew test
```

运行脚本模块测试：
```bash
./gradlew :src:main:vicky-script:test
```

## 许可证

Apache License 2.0
