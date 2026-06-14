# Vicky

一个精简、易维护的 Kotlin Agent 框架，对接 **OpenAI 通用协议**（兼容任意 OpenAI 协议端点：OpenAI / DeepSeek / one-api / 本地推理服务等）。

底层用 [aallam/openai-kotlin](https://github.com/aallam/openai-kotlin) 作 API 客户端，agent 主循环、模式、权限、工具注册全部自写，方便按需改造。

## 特性

- **三种内置处理模式（可自定义）**
  - `SILENT`（模式一）：传工具；agent 自身的文本回复**不**输出给 user，只有工具产生的 user-facing 回复会发出。
  - `VERBOSE`（模式二）：传工具；在 SILENT 基础上，额外把 agent 的文本回复也发给 user。
  - `CHAT`（模式三）：纯聊天；**不**传工具、不拼工具列表，agent 文本直接发给 user。
  - 模式是可继承的抽象类 `AgentMode`，可像自定义工具一样定义自己的模式（见下文）。
- **工具调用双输出**：每个工具返回 `ToolResult(toAgent, userReply)`，`toAgent` 喂回 agent 继续推理，`userReply`（可选）**实时**推送给 user —— 每完成一次工具调用立刻输出，再进入下一轮。
- **步数限制**：`maxSteps` 防止死循环，用尽后静默结束。
- **权限系统**：`ToolAuthorizer.allow(userId, toolName)` 框架层鉴权；工具 `execute(userId, args)` 第一参数即 userId，可二次校验。
- **运行时增删工具**：`registerTool` / `unregisterTool`，下一轮自动反映到 system prompt 与 tools schema。
- **自动拼接上下文**：每轮重建 system prompt = `agentMd` + 内置安全防护 + 模式说明 + 可用工具摘要。
- **内置安全防护**：始终拼接、无法被 `agentMd` 关闭，抵御提示词反取 / 注入 / 越狱。
- **入口/出口外部提供**：`Agent` 是抽象类，子类提供 `sink`（出口）与 `authorizer`（权限），外部调 `agent.receive(...)` 作入口。
- **无持久化**：会话历史仅存内存，进程退出即丢。

## 项目结构

```
src/main/kotlin/org/example/vicky/
  agent/
    Agent.kt           # 抽象基类 + 主循环（核心）
    AgentConfig.kt     # 模型 / baseUrl / apiKey / maxSteps / mode / agentMd
    AgentMode.kt       # SILENT(模式1) / VERBOSE(模式2)
  io/
    InboundMessage.kt  # 入站: userId + content + conversationId
    OutboundMessage.kt # 出站 sealed: AgentReply / ToolReply
    MessageSink.kt     # 出口 fun interface
  tool/
    Tool.kt            # 工具抽象类
    ToolResult.kt      # toAgent + 可选 userReply
    ToolAuthorizer.kt  # 权限 fun interface
    ToolRegistry.kt    # 线程安全注册表，运行时增删
  context/
    ContextBuilder.kt  # system prompt 拼接 + 内置安全防护
    ConversationStore.kt # 内存级会话历史
  llm/
    OpenAiClientFactory.kt # 封装 baseUrl / timeout / 关闭啰嗦日志
  examples/
    ConsoleMain.kt     # 控制台示例：Echo / Shutdown / Ping 工具
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
        model = ModelId("deepseek-v4-flash"),
        apiKey = "sk-...",
        baseUrl = "http://192.168.0.108:3000/v1", // 兼容 OpenAI 协议端点；null = 官方
        mode = AgentMode.SILENT,
        maxSteps = 6,
        agentMd = "你是 Vicky，一个简洁的助手。", // 基础人设，直接内联文本
    )
)
agent.registerTool(MyTool())            // 运行时注册
agent.receive(InboundMessage("user1", "ping 一下 192.168.0.108:3000"))
```

控制台示例：`./gradlew run`（在 `ConsoleMain.kt` 里填好 apiKey / baseUrl / model）。

## 自定义工具

```kotlin
class PingTool : Tool() {
    override val name = "ping"
    override val description = "测量到指定主机的网络延迟。"
    override val parameters = buildJsonObject {      // OpenAI function JSON Schema
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("host") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("host")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        // userId 用于鉴权 / 审计；args 是 LLM 给的参数
        val result = doPing(args["host"]!!.jsonPrimitive.content)
        return ToolResult(toAgent = result, userReply = result) // userReply 实时发给 user
    }
}
```

## 自定义模式

模式是抽象类 `AgentMode`，控制三个开关 + 一段注入提示词。像自定义工具一样继承它即可：

```kotlin
object ReviewMode : AgentMode() {
    override val name = "REVIEW"
    override val toolsEnabled = true    // 是否拼工具列表并传给模型
    override val emitAgentText = false  // 是否把 agent 文本发给 user
    override val instructions =          // 注入 system prompt 的「# Output rules」正文
        "You are reviewing code. Only speak to the user through tools."
}

val agent = MyAgent(AgentConfig(model = ..., apiKey = ..., mode = ReviewMode))
```

三种内置模式（`AgentMode.SILENT/VERBOSE/CHAT`）对照：

| 模式 | `toolsEnabled` | `emitAgentText` | 工具 userReply |
|------|----------------|-----------------|----------------|
| SILENT | ✅ | ❌ | ✅ |
| VERBOSE | ✅ | ✅ | ✅ |
| CHAT | ❌ | ✅ | —（无工具） |

## system prompt 拼接顺序

1. `agentMd` —— 人设/指令（`AgentConfig`，直接内联文本）
2. `# Security` —— 内置安全防护（`ContextBuilder.SECURITY_GUARD`，固定拼接）
3. `# Output rules` —— 当前模式说明（`mode.instructions`）
4. `# Available tools` —— 当前已注册工具的名字 + 简介（仅当 `mode.toolsEnabled`）

工具的完整 JSON Schema 另通过 `ChatCompletionRequest.tools` 字段传给模型。

## 主循环

```
oaiTools = if (mode.toolsEnabled) buildTools() else emptyList()
for step in 0 until maxSteps:
    resp = chat.completion(history + oaiTools)
    if resp.toolCalls 非空:
        for call in toolCalls:
            鉴权 -> 执行工具 -> result
            history += assistantToolCall
            history += toolMessage(result.toAgent)        # 输出1: 给 agent
            result.userReply?.let { sink.emit(ToolReply) } # 输出2: 实时给 user
        continue
    else:
        if mode.emitAgentText: sink.emit(AgentReply(resp.content))  # SILENT 不发
        break
```
