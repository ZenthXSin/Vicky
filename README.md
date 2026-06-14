# Vicky

一个精简、易维护的 Kotlin Agent 框架，对接 **OpenAI 通用协议**（兼容任意 OpenAI 协议端点：OpenAI / DeepSeek / one-api / 本地推理服务等）。

底层用 [aallam/openai-kotlin](https://github.com/aallam/openai-kotlin) 作 API 客户端，agent 主循环、模式、权限、工具注册全部自写，方便按需改造。

## 特性

- **三种内置处理模式（可自定义）**
  - `SILENT`：传工具；agent 文本**不**输出给 user，只有工具产生的 `userReply` 会发出。
  - `VERBOSE`：传工具；agent 文本和工具 `userReply` 都发给 user。
  - `CHAT`：纯聊天；不传工具，agent 文本直接发给 user。
  - 模式是可继承的抽象类 `AgentMode`，可自定义新模式（见下文）。
- **工具调用双输出**：每个工具返回 `ToolResult(toAgent, userReply)`，`toAgent` 喂回 agent 继续推理，`userReply`（可选）**实时**推送给 user —— 每次工具调用完成立刻输出，再进入下一轮。
- **步数耗尽汇报**：`maxSteps` 用尽时不会静默退出，而是追加一条系统提示让模型整理已有信息、向用户汇报当前进展，并说明因步数限制可能有操作未完成。
- **单次无状态**：`receive(clearContextAfter = true)` 本轮结束后自动清空该会话上下文，下一条消息从空历史开始。
- **权限系统**：`ToolAuthorizer.allow(userId, toolName)` 框架层鉴权；工具 `execute(userId, args)` 第一参数即 userId，可二次校验。
- **运行时增删工具**：`registerTool` / `unregisterTool`，下一轮自动反映到 system prompt 与 tools schema。
- **内置工具**：`AgentConfig.builtinTools = true`（默认）自动注册：
  - `clear_context`：清除当前会话上下文，下一条消息起生效。
  - `github`：浏览 GitHub 仓库；传 `repo`（`owner/repo`）+ `path`，是目录则列出直接子项，是文件则返回内容；支持 `GITHUB_TOKEN` 环境变量提升速率限制。
- **调试输出**：`debug = true` 开启框架运行日志（每步推理、工具调用、上下文清除等）；`think = true` 把 agent 中间思考文本推出来。两者都通过 `MessageSink` 推送（`OutboundMessage.Debug` / `Think`），由外部决定如何展示。
- **自动拼接上下文**：每轮重建 system prompt = `agentMd` + 内置安全防护 + 模式说明 + 可用工具摘要。
- **内置安全防护**：始终拼接、无法被 `agentMd` 关闭，抵御提示词反取 / 注入 / 越狱。
- **入口/出口外部提供**：`Agent` 是抽象类，子类提供 `sink`（出口）与 `authorizer`（权限），外部调 `agent.receive(...)` 作入口。
- **无持久化**：会话历史仅存内存，进程退出即丢。

## 项目结构

```
src/main/kotlin/org/example/vicky/
  agent/
    Agent.kt              # 抽象基类 + 主循环（核心）
    AgentConfig.kt        # 模型 / baseUrl / apiKey / maxSteps / mode / agentMd / debug / think / builtinTools
    AgentMode.kt          # SILENT / VERBOSE / CHAT + 可继承自定义
  io/
    InboundMessage.kt     # 入站: userId + content + conversationId
    OutboundMessage.kt    # 出站 sealed: AgentReply / ToolReply / Debug / Think
    MessageSink.kt        # 出口 fun interface
  tool/
    Tool.kt               # 工具抽象类（支持 ToolContext 扩展重载）
    ToolContext.kt        # 工具运行时上下文 (userId / conversationId / store / tools)
    ToolResult.kt         # toAgent + 可选 userReply
    ToolAuthorizer.kt     # 权限 fun interface
    ToolRegistry.kt       # 线程安全注册表，运行时增删
    builtin/
      BuiltinTools.kt     # 内置工具集合入口
      ClearContextTool.kt # 清除会话上下文
      GithubTool.kt       # 访问 GitHub 仓库（列目录 / 读文件）
  context/
    ContextBuilder.kt     # system prompt 拼接 + 内置安全防护
    ConversationStore.kt  # 内存级会话历史
  llm/
    OpenAiClientFactory.kt # 封装 baseUrl / timeout / 日志级别（debug 控制）
  examples/
    ConsoleMain.kt        # 控制台示例：Shutdown / Ping / now 工具
```

## 快速开始

### 1. 依赖

已在 `build.gradle.kts` 配好（openai-client 4.1.0 + ktor-okhttp 引擎 + coroutines + serialization）。

### 2. 写一个 Agent 子类

```kotlin
class MyAgent(config: AgentConfig) : Agent(config) {
    // 出口：把消息发到任意渠道（控制台 / QQ / HTTP ...）
    override val sink = MessageSink { out ->
        when (out) {
            is OutboundMessage.AgentReply -> sendToUser(out.content)
            is OutboundMessage.ToolReply  -> sendToUser(out.content)
            is OutboundMessage.Debug      -> log(out.content)   // 框架运行日志
            is OutboundMessage.Think      -> log(out.content)   // agent 中间思考
        }
    }
    // 权限（不写则默认全允许）
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
        baseUrl      = "http://192.168.0.108:3000/v1", // 兼容端点；null = OpenAI 官方
        mode         = AgentMode.SILENT,
        maxSteps     = 6,
        agentMd      = "你是 Vicky，一个简洁的助手。",
        debug        = false,  // true 开启框架运行日志
        think        = false,  // true 推送 agent 中间思考文本
        builtinTools = true,   // 自动注册内置工具（clear_context / github）
    )
)
agent.registerTool(MyTool())

// 普通调用
agent.receive(InboundMessage("user1", "ping 192.168.0.108"))

// 单次无状态：本轮结束后自动清空上下文
agent.receive(InboundMessage("user1", "查一下文件"), clearContextAfter = true)

// 带实时回调
agent.receive(InboundMessage("user1", "问题"), replySink = MessageSink { println(it.content) })
```

控制台示例：`./gradlew run`（在 `ConsoleMain.kt` 里填好 apiKey / baseUrl / model）。

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

需要操作会话历史等框架内部状态的工具可 override `execute(ctx, args)`（默认委托回 `execute(userId, args)`，现有工具无需修改）：

```kotlin
class MyTool : Tool() {
    // ...
    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        ctx.store.clear(ctx.conversationId) // 访问 ConversationStore
        return ToolResult(toAgent = "done")
    }
}
```

## 自定义模式

模式是抽象类 `AgentMode`，控制两个开关 + 一段注入提示词。继承即可：

```kotlin
object ReviewMode : AgentMode() {
    override val name = "REVIEW"
    override val toolsEnabled = true    // 是否拼工具列表并传给模型
    override val emitAgentText = false  // 是否把 agent 文本发给 user
    override val instructions =
        "You are reviewing code. Only speak to the user through tools."
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

1. `agentMd` —— 人设/指令（`AgentConfig`，直接内联文本）
2. `# Security` —— 内置安全防护（固定拼接，不可关闭）
3. `# Output rules` —— 当前模式说明（`mode.instructions`）
4. `# Available tools` —— 已注册工具名 + 简介（仅当 `mode.toolsEnabled`）

工具的完整 JSON Schema 另通过 `ChatCompletionRequest.tools` 传给模型。

## 主循环

```
oaiTools = if (mode.toolsEnabled) buildTools() else []
for step in 0 until maxSteps:
    resp = chat.completion(history + oaiTools)
    if resp.toolCalls 非空:
        think: emit(Think(resp.content))          # 中间思考（think=true 时）
        for call in toolCalls:
            think: emit(Think("Use Tool: name"))
            debug: emit(Debug("step N: invoking tool 'name'"))
            鉴权 -> 执行工具 -> result
            history += assistantToolCall
            history += toolMessage(result.toAgent)          # 给 agent
            result.userReply?.let { emit(ToolReply) }       # 实时给 user
        continue
    else:
        if mode.emitAgentText: emit(AgentReply(resp.content))
        if clearContextAfter:  store.clear(conversationId)
        return

# 步数耗尽：注入系统提示，让模型整理现状并向用户汇报
history += systemNotice("step budget exhausted, summarize and report")
wrapUp = chat.completion(history, tools=[])      # 不再传工具
emit(AgentReply(wrapUp.content))
if clearContextAfter: store.clear(conversationId)
```
