---
name: script-writing
description: TypeScript 脚本编写指南，帮助用户编写、调试和管理 Vicky 动态脚本
---

# Vicky TypeScript 脚本编写指南

你是 Vicky 的脚本编写助手，帮助用户编写运行在 Vicky 脚本引擎上的 TypeScript 脚本。

## 核心思想

Vicky 脚本不是简单的"插件 API"——它是运行在 Rhino JVM 引擎上的 TypeScript 程序，**对所有 Kotlin/Java 类有直接访问权**。写脚本就是直接用 TypeScript 操作 JVM 对象，包括实例化 Kotlin 数据类、实现 SAM 接口、继承抽象类、调用 suspend 协程方法等。

**脚本有两种用途，但编程模型完全相同：**

1. **工具脚本**：导出 `execute` 函数，让 Agent 可以调用这个工具
2. **独立脚本**：直接编写顶层代码，构建 Agent、发消息、测试逻辑——就像写一个普通程序

选择哪种取决于需求，不是限制。

## 关键编程模式

### 数据类：用命名参数构造
Kotlin data class 支持对象字面量形式，只填需要的字段，其余用默认值：
```typescript
const config = new AgentConfig({
    model: new ModelId("deepseek-v4-flash"),
    apiKey: "sk-...",
    mode: AgentMode.VERBOSE,
    builtinTools: true,
});
```

### SAM 接口：直接传 lambda
只有一个方法的 Kotlin interface（functional interface）直接传箭头函数：
```typescript
const sink = new MessageSink((out: OutboundMessage) => {
    switch (out.type) {
        case "AgentReply": println(`[agent] ${out.content}`); break;
        case "ToolReply":  println(`[tool] ${out.content}`);  break;
    }
});

const authorizer = new ToolAuthorizer((userId: string, toolName: string) => {
    return toolName !== "shutdown" || userId === "admin";
});
```

### 抽象类：用 extend() 实现
JS 无法真正继承 Java 抽象类，用 `extend()` 生成子类实例。key 为 `getXxx`（Kotlin `val xxx` 编译后的 getter 名）：
```typescript
const agent = extend(Agent, {
    getContextManager: () => contextManager,
    getSink: () => sink,
    getAuthorizer: () => authorizer,
}, config, OpenAiClientFactory.create(config));
```

### suspend 方法：直接调用
引擎自动把 Kotlin 协程桥接为同步调用，直接用即可：
```typescript
agent.receive(new InboundMessage("user1", "你好", "user1"));
```

### Kotlin String 属性：直接比较
Kotlin String 属性自动转为 JS 原生字符串，无需任何包装：
```typescript
if (out.type === "AgentReply") { ... }
switch (out.type) { case "AgentReply": ... }
```

---

## 工具脚本结构

```typescript
// 工具名称（必填，字符串）
var name = "my_tool";

// 工具描述（必填，字符串，告诉 LLM 这个工具做什么）
var description = "这个工具的功能描述";

// 参数定义（必填，OpenAI function-calling JSON Schema 格式）
var parameters = {
    type: "object",
    properties: {
        param1: { type: "string", description: "参数说明" },
        param2: { type: "integer", description: "数字参数", default: 42 }
    },
    required: ["param1"]
};

// 执行函数（必填，async function）
// ctx: 运行时上下文（userId, conversationId, groupId）
// args: 参数对象，类型由 parameters 定义
// 返回: { toAgent: string, userReply?: string, endTurn?: boolean }
async function execute(ctx, args) {
    return {
        toAgent: "返回给 LLM 的内容",
        userReply: "（可选）直接推送给用户的消息"
    };
}
```

## 返回值说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `toAgent` | string | 是 | 返回给 LLM 的内容，用于后续推理 |
| `userReply` | string | 否 | 直接推送给用户的消息（SILENT 模式下是唯一可见输出） |
| `endTurn` | boolean | 否 | 是否立即结束本轮对话（默认 false） |

## 可用的全局类（无需 import）

以下类已自动注入到脚本全局作用域，直接使用类名即可：

### Java 标准库

```typescript
// 文件操作
var f = new File("./path/to/file");
var content = Files.readString(f.toPath());
Files.writeString(f.toPath(), "hello");
var exists = f.exists();
var isDir = f.isDirectory();
var files = f.listFiles();

// 路径
var p = Path.of("a", "b", "c");
var abs = p.toAbsolutePath().toString();

// 时间
var now = LocalDateTime.now();
var today = LocalDate.now();
var formatted = now.toString();

// UUID
var id = UUID.randomUUID().toString();

// 集合
var list = new ArrayList();
list.add("item");
var map = new HashMap();
map.put("key", "value");
var set = new HashSet();
```

### Vicky 框架类

```typescript
// ToolResult - 工具返回值
var result = new ToolResult();
result.toAgent = "info for LLM";
result.userReply = "info for user";

// InboundMessage - 入站消息
var msg = new InboundMessage();
msg.userId = "123";
msg.content = "hello";

// ToolContext 字段（通过 execute 的 ctx 参数访问）
// ctx.userId      - 调用者用户 ID
// ctx.conversationId - 会话 ID
// ctx.groupId     - 群 ID（私聊为空字符串）
```

### kotlinx.serialization.json

```typescript
var obj = buildJsonObject({
    put("key", JsonPrimitive("value"));
    put("num", JsonPrimitive(42));
});
```

## 兜底：Java.type()

如果某个类没有自动注入，可以用 `Java.type()` 按全限定名访问：

```typescript
var OkHttpClient = Java.type("okhttp3.OkHttpClient");
var client = new OkHttpClient();
```

## 常见脚本模式

### 模式 1：简单计算/转换

```typescript
var name = "json_format";
var description = "格式化 JSON 字符串";
var parameters = {
    type: "object",
    properties: {
        input: { type: "string", description: "原始 JSON 字符串" }
    },
    required: ["input"]
};

async function execute(ctx, args) {
    try {
        var parsed = JSON.parse(args.input);
        var formatted = JSON.stringify(parsed, null, 2);
        return { toAgent: formatted };
    } catch (e) {
        return { toAgent: "JSON 解析失败: " + e.message };
    }
}
```

### 模式 2：文件操作

```typescript
var name = "file_tail";
var description = "读取文件末尾 N 行";
var parameters = {
    type: "object",
    properties: {
        path: { type: "string", description: "文件路径" },
        lines: { type: "integer", description: "行数", default: 10 }
    },
    required: ["path"]
};

async function execute(ctx, args) {
    var f = new File(args.path);
    if (!f.exists()) return { toAgent: "文件不存在: " + args.path };
    var allLines = Files.readAllLines(f.toPath());
    var n = args.lines || 10;
    var tail = allLines.subList(Math.max(0, allLines.size() - n), allLines.size());
    return { toAgent: tail.join("\n") };
}
```

### 模式 3：HTTP 请求

```typescript
var name = "http_get";
var description = "发送 HTTP GET 请求";
var parameters = {
    type: "object",
    properties: {
        url: { type: "string", description: "请求 URL" }
    },
    required: ["url"]
};

async function execute(ctx, args) {
    var url = new java.net.URL(args.url);
    var conn = url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(10000);
    var code = conn.getResponseCode();
    var body = new java.lang.String(conn.getInputStream().readAllBytes());
    return { toAgent: "HTTP " + code + "\n" + body };
}
```

### 模式 4：调用外部 API 并存储结果

```typescript
var name = "save_note";
var description = "保存笔记到文件";
var parameters = {
    type: "object",
    properties: {
        title: { type: "string", description: "笔记标题" },
        content: { type: "string", description: "笔记内容" }
    },
    required: ["title", "content"]
};

async function execute(ctx, args) {
    var dir = new File("./notes");
    if (!dir.exists()) dir.mkdirs();
    var safeName = args.title.replace(/[^a-zA-Z0-9\u4e00-\u9fff_-]/g, "_");
    var file = new File(dir, safeName + ".md");
    var timestamp = LocalDateTime.now().toString();
    var text = "# " + args.title + "\n\n" + args.content + "\n\n---\n_" + timestamp + "_\n";
    Files.writeString(file.toPath(), text);
    return {
        toAgent: "已保存到 " + file.getPath(),
        userReply = "笔记已保存: " + args.title
    };
}
```

## 调试技巧

1. **先测试简单返回**：先写一个只返回固定字符串的脚本，确认加载正常
2. **错误会被捕获**：脚本抛出异常时，错误信息会通过 `toAgent` 返回给 LLM
3. **使用 manage_scripts view**：查看已加载脚本的源码确认内容
4. **reload 不需重启**：修改脚本后用 `manage_scripts reload name=xxx.ts` 即可热更新

## 参数类型对照

| parameters.type | TypeScript 类型 | 示例 |
|----------------|----------------|------|
| `string` | string | `"hello"` |
| `integer` | number | `42` |
| `number` | number | `3.14` |
| `boolean` | boolean | `true` |
| `array` | any[] | `["a","b"]` |
| `object` | object | `{key:"value"}` |

## 高级模式：直接编写 Agent 脚本

除了 Tool 脚本，还可以直接编写完整的 Agent 脚本（无需 `execute` 导出），用于测试、调试或独立运行。

### 完整 Agent 构建示例

```typescript
// 1. Agent 配置
const config = new AgentConfig({
    model: new ModelId("deepseek-v4-flash"),
    apiKey: "sk-...",
    baseUrl: "http://192.168.0.108:3000/v1",
    mode: AgentMode.VERBOSE,   // SILENT / VERBOSE / CHAT
    maxSteps: 6,
    agentMd: "你是 Vicky，一个简洁的助手。",
    debug: false,
    builtinTools: true,
    streaming: false,
});

// 2. Context Manager（管理对话历史与压缩）
const contextManager = new DefaultContextManager({
    store: new ConversationStore(),
    builder: new ContextBuilder(config.agentMd),
    compactor: new ContextCompactor(config, OpenAiClientFactory.create(config)),
});

// 3. MessageSink（处理 Agent 输出）
// out.type 可取值："AgentReply" / "ToolReply" / "Debug" / "Think"
const sink = new MessageSink((out: OutboundMessage) => {
    switch (out.type) {
        case "AgentReply": println(`[agent] ${out.content}`); break;
        case "ToolReply":  println(`[tool] ${out.content}`);  break;
        case "Debug":      println(`[debug] ${out.content}`); break;
        case "Think":      println(`[think] ${out.content}`); break;
    }
});

// 4. ToolAuthorizer（工具调用权限控制）
const authorizer = new ToolAuthorizer((userId: string, toolName: string) => {
    if (toolName === "shutdown") return userId === "admin";
    return true;
});

// 5. 用 extend() 创建 Agent 实例
// extend(BaseClass, jsImpl, ...ctorArgs) 动态生成抽象类的具体子类
// getXxx 是 Kotlin val 编译后的 JVM getter 名
const agent = extend(Agent, {
    getContextManager: () => contextManager,
    getSink: () => sink,
    getAuthorizer: () => authorizer,
}, config, OpenAiClientFactory.create(config));

// 6. 发送消息
// InboundMessage(userId, content, conversationId?)
agent.receive(new InboundMessage("user1", "你好", "user1"));
```

### AgentMode 说明

| 值 | 说明 |
|----|------|
| `AgentMode.SILENT` | 静默模式，仅 sink 收到输出 |
| `AgentMode.VERBOSE` | 详细模式，输出思考过程和工具调用 |
| `AgentMode.CHAT` | 对话模式 |

### extend() 规则

- 第一个参数：Java/Kotlin 抽象类（如 `Agent`）
- 第二个参数：覆盖 getter 的 JS 对象，key 为 `getXxx`（对应 Kotlin `val xxx`）
- 其余参数：传给构造函数
- 返回值：可直接调用 `agent.receive()` 等 suspend 方法（引擎自动处理协程）

## Kotlin 互操作注意事项

- Kotlin 数据类支持命名参数构造（推荐）：`new AgentConfig({ model: new ModelId("..."), apiKey: "..." })`
- Kotlin String 属性可直接与 JS 字符串比较，`switch(out.type)` 和 `=== "AgentReply"` 均直接工作，无需 `String()` 转换
- Kotlin sealed interface 的子类型可通过 `.type` 字段区分（如 `OutboundMessage` 的 `"AgentReply"` / `"ToolReply"` / `"Debug"` / `"Think"`）

## 注意事项

- 脚本运行在 Rhino JS 引擎中，**不是** Node.js，不能用 npm 包
- 用 TypeScript 编写脚本（编译器自动转为 ES5），`const`/`let`、箭头函数、模板字符串等 TS 语法均可正常使用
- 异步函数（async）会被同步执行（Promise polyfill），不需要 await 外部 Promise
- 不要使用可选链 `?.`、空值合并 `??` 等新语法（Rhino 运行时不支持）
- 文件路径建议使用相对路径（相对于工作目录）
- 大文件操作注意内存，避免一次读取超大文件
