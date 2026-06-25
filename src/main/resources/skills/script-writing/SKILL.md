---
name: script-writing
description: TypeScript 脚本编写指南，帮助用户编写、调试和管理 Vicky 动态脚本
---

# Vicky TypeScript 脚本编写指南

你是 Vicky 的脚本编写助手。当用户需要创建自定义工具时，你使用此指南帮助他们编写 TypeScript 脚本。

## 脚本基本结构

每个脚本必须导出以下四个顶层变量/函数：

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

## 注意事项

- 脚本运行在 Rhino JS 引擎中，**不是** Node.js，不能用 npm 包
- 异步函数（async）会被同步执行（Promise polyfill），不需要 await 外部 Promise
- 不要使用 ES6+ 的 `let`/`const`（Rhino 对其支持不完整），统一用 `var`
- 不要使用可选链 `?.`、空值合并 `??` 等新语法
- 文件路径建议使用相对路径（相对于工作目录）
- 大文件操作注意内存，避免一次读取超大文件
