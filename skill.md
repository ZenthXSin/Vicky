# Vicky 脚本编写指南

## 概述

Vicky 的脚本系统基于 **TypeScript**（通过 Rhino JS 引擎 + 内置 tsc 编译器）。每个 `.ts` 脚本可以：

1. **定义工具（Tool）** — 被 AI Agent 调用
2. **作为插件** — 通过 `onLoad`/`onUnload` 钩子在加载/卸载时执行自定义逻辑
3. **访问运行时** — 所有 Kotlin object 单例自动注入，可直接操作 Agent、Bot、配置等

脚本文件存放在 `config/scripts/` 目录下。

---

## 脚本结构

每个脚本**必须导出** `name` 和至少一个钩子/函数：

- `name` — 工具名称（必须）
- `description` — 工具描述
- `parameters` — JSON Schema 参数定义
- `execute(ctx, args)` — 工具执行函数
- `onLoad()` — 脚本加载时调用（可选）
- `onUnload()` — 脚本卸载时调用（可选）

`execute` 和 `onLoad` 至少要有一个。纯插件模式可以只有 `onLoad` 而没有 `execute`。

```typescript
// 1. name — 工具名称（必须，字符串）
var name: string = "my_tool";

// 2. description — 工具描述（必须，字符串，告诉 AI 何时使用此工具）
var description: string = "做什么用的，一句话说清楚";

// 3. parameters — JSON Schema 格式的参数定义（必须，对象）
var parameters = {
    type: "object",
    properties: {
        param1: { type: "string", description: "参数说明" },
        param2: { type: "integer", description: "数字参数" }
    },
    required: ["param1"]  // 可选，列出必填参数
};

// 4. execute — 执行函数（必须，async function）
async function execute(ctx: any, args: any): Promise<any> {
    // args.param1  — 用户传入的参数
    // ctx.userId   — 调用者 ID
    // ctx.conversationId — 会话 ID
    // ctx.groupId  — 群聊 ID（如果有）

    return {
        toAgent: "返回给 AI 的内容（必填）",
        userReply: "直接发给用户的消息（可选）",
        endTurn: false  // 可选，true 则 AI 不再继续推理
    };
}
```

---

## 返回值说明

`execute` 函数必须返回一个对象，包含以下字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `toAgent` | string | **是** | 返回给 AI Agent 继续推理的内容 |
| `userReply` | string | 否 | 直接发送给用户的消息（绕过 AI） |
| `endTurn` | boolean | 否 | 设为 `true` 让 AI 立即结束本轮，不再调用其他工具 |

---

## 可用的 Java 类（无需 import）

脚本运行在 Rhino JS 引擎中，以下 Java 类已自动注入，可直接使用：

### 文件操作
- `File` — `new File("/path")` 或 `File("/path")`
- `Path` — `Path.of("/path")`
- `Files` — `Files.readString(path)`, `Files.writeString(path, content)`, `Files.exists(path)` 等

### 日期时间
- `LocalDateTime` — `LocalDateTime.now()`, `LocalDateTime.of(2024, 1, 1, 0, 0)`
- `LocalDate` — `LocalDate.now()`
- `LocalTime` — `LocalTime.now()`
- `Instant` — `Instant.now()`
- `Duration` — `Duration.ofMinutes(5)`
- `Period` — `Period.ofDays(7)`
- `Date` — `new Date()`

### 集合
- `ArrayList` — `new ArrayList()`
- `HashMap` — `new HashMap()`
- `HashSet` — `new HashSet()`

### 其他
- `UUID` — `UUID.randomUUID().toString()`
- `StringBuilder` — `new StringBuilder()`
- `String` — Java String 类

### 兜底：Java.type()
如果需要的类不在上面列表中，可以用 `Java.type()` 加载任意全限定类：

```typescript
var HttpURLConnection = Java.type("java.net.HttpURLConnection");
var URL = Java.type("java.net.URL");
```

---

## 运行时访问（自动注入）

所有 Kotlin `object` 单例在脚本中**自动可用**，无需 import：

| 脚本中使用 | 说明 |
|------------|------|
| `AgentManager` | 获取/管理 Agent 实例 |
| `ConfigManager` | 加载/保存配置 |
| `SkillManager` | 管理技能 |
| `ScriptManager` | 管理脚本 |
| `MiraiToolImpl` | `.bot` 获取 Mirai Bot 实例 |
| `BuiltinToolImpl` | `.memoryStore` 等内置工具状态 |
| `OpenAiClientFactory` | 创建 LLM 客户端 |
| `EmbeddingClientFactory` | 创建 Embedding 客户端 |

**访问 Bot 实例：**
```typescript
var bot = MiraiToolImpl.bot;
// bot 是 net.mamoe.mirai.Bot 实例，可调用所有 Mirai API
```

**访问 Agent：**
```typescript
var agents = AgentManager.all();
var agent = agents[0];
var tools = agent.getTools();
```

---

## 生命周期钩子

脚本可导出 `onLoad()` 和 `onUnload()` 函数：

```typescript
var name = "my_plugin";
var description = "示例插件";

function onLoad() {
    // 脚本加载时执行
    java.lang.System.out.println("Plugin loaded!");
}

function onUnload() {
    // 脚本卸载时清理资源
}

// execute 可选
async function execute(ctx, args) {
    return { toAgent: "ok" };
}
```

**安全机制：**
- `onLoad` 超时 10 秒，超时则加载失败
- `onLoad` 异常 → 脚本不注册为工具
- `onUnload` 异常 → 强制继续卸载，不阻止清理
- 循环依赖检测 → 防止脚本互相调用导致栈溢出

---

## 重要限制

### 1. 没有 `console`
Rhino 环境**没有** `console.log`。调试输出用 `java.lang.System.out.println`：

```typescript
// 错误 ❌
console.log("debug");

// 正确 ✅
java.lang.System.out.println("debug");
```

> 注意：`java.lang` 包下的类需要通过 `Java.type()` 访问，或用全限定名。`System` 类不在自动注入列表中。

### 2. 没有 Node.js 模块
`fs`, `http`, `path`, `os`, `process` 等 Node.js 内置模块**不可用**。

文件操作用 Java 的 `File` / `Files` / `Path`。

### 3. 没有 `require` / `import`
脚本是独立的，不能导入其他模块或脚本。

### 4. 没有 `fetch` / `XMLHttpRequest`
HTTP 请求需要通过 Java 的 `HttpURLConnection` 实现（见下方示例）。

### 5. TypeScript 限制
- 编译目标默认 ES5，模块 ES2015
- `async/await` 可用（有内置 Promise polyfill）
- 类型注解仅用于编译期检查，运行时不存在
- 不支持 `interface` 导出（会被忽略）

### 6. 参数类型
`args` 中的参数值由 JSON 传入，类型自动转换：
- JSON string → JS string
- JSON number → JS int 或 double
- JSON boolean → JS boolean
- JSON object/array → JS 字符串化的 JSON（需要 `JSON.parse`）

---

## 示例

### 示例 1：基础问候工具

```typescript
var name: string = "hello";
var description: string = "向用户打招呼";
var parameters = {
    type: "object",
    properties: {
        name: { type: "string", description: "用户名" }
    },
    required: ["name"]
};

async function execute(ctx: any, args: any): Promise<any> {
    return {
        toAgent: "greeted " + args.name,
        userReply: "你好，" + args.name + "！"
    };
}
```

### 示例 2：读取文件

```typescript
var name: string = "file_read";
var description: string = "读取指定路径的文件内容";
var parameters = {
    type: "object",
    properties: {
        path: { type: "string", description: "文件绝对路径" }
    },
    required: ["path"]
};

async function execute(ctx: any, args: any): Promise<any> {
    var f = new File(args.path);
    if (!f.exists()) {
        return { toAgent: "error: 文件不存在: " + args.path };
    }
    var content = Files.readString(f.toPath());
    return { toAgent: content };
}
```

### 示例 3：写入文件

```typescript
var name: string = "file_write";
var description: string = "将内容写入指定文件";
var parameters = {
    type: "object",
    properties: {
        path: { type: "string", description: "文件路径" },
        content: { type: "string", description: "要写入的内容" }
    },
    required: ["path", "content"]
};

async function execute(ctx: any, args: any): Promise<any> {
    var f = new File(args.path);
    // 确保父目录存在
    var parent = f.getParentFile();
    if (parent != null && !parent.exists()) {
        parent.mkdirs();
    }
    Files.writeString(f.toPath(), args.content);
    return {
        toAgent: "wrote " + args.content.length + " chars to " + args.path,
        userReply: "文件已写入: " + args.path
    };
}
```

### 示例 4：列出目录文件

```typescript
var name: string = "list_dir";
var description: string = "列出指定目录下的文件和子目录";
var parameters = {
    type: "object",
    properties: {
        path: { type: "string", description: "目录路径" },
        pattern: { type: "string", description: "文件名过滤（可选，如 '*.ts'）" }
    },
    required: ["path"]
};

async function execute(ctx: any, args: any): Promise<any> {
    var dir = new File(args.path);
    if (!dir.exists() || !dir.isDirectory()) {
        return { toAgent: "error: 目录不存在: " + args.path };
    }

    var files;
    if (args.pattern) {
        // Java FilenameFilter 不能直接在 Rhino 中用 lambda，用字符串过滤
        files = dir.listFiles();
        var filtered = [];
        var regex = args.pattern.replace("*", ".*");
        for (var i = 0; i < files.length; i++) {
            if (files[i].getName().matches(regex)) {
                filtered.push(files[i]);
            }
        }
        files = filtered;
    } else {
        files = dir.listFiles();
    }

    var sb = new StringBuilder();
    for (var i = 0; i < files.length; i++) {
        var prefix = files[i].isDirectory() ? "[DIR] " : "[FILE] ";
        sb.append(prefix + files[i].getName() + "\n");
    }
    return { toAgent: sb.toString() || "(空目录)" };
}
```

### 示例 5：HTTP GET 请求

```typescript
var name: string = "http_get";
var description: string = "发送 HTTP GET 请求并返回响应内容";
var parameters = {
    type: "object",
    properties: {
        url: { type: "string", description: "请求 URL" }
    },
    required: ["url"]
};

async function execute(ctx: any, args: any): Promise<any> {
    var URL = Java.type("java.net.URL");
    var BufferedReader = Java.type("java.io.BufferedReader");
    var InputStreamReader = Java.type("java.io.InputStreamReader");

    var url = new URL(args.url);
    var conn = url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);

    var code = conn.getResponseCode();
    var reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
    var sb = new StringBuilder();
    var line;
    while ((line = reader.readLine()) != null) {
        sb.append(line + "\n");
    }
    reader.close();

    return {
        toAgent: "HTTP " + code + ":\n" + sb.toString()
    };
}
```

### 示例 6：JSON 处理

```typescript
var name: string = "json_query";
var description: string = "解析 JSON 字符串并提取指定字段";
var parameters = {
    type: "object",
    properties: {
        json_str: { type: "string", description: "JSON 字符串" },
        field: { type: "string", description: "要提取的字段名（支持点号路径如 'a.b.c'）" }
    },
    required: ["json_str", "field"]
};

async function execute(ctx: any, args: any): Promise<any> {
    var obj = JSON.parse(args.json_str);
    var fields = args.field.split(".");
    var current = obj;
    for (var i = 0; i < fields.length; i++) {
        if (current == null) {
            return { toAgent: "error: 字段 '" + args.field + "' 不存在" };
        }
        current = current[fields[i]];
    }
    return {
        toAgent: typeof current === "object" ? JSON.stringify(current) : String(current)
    };
}
```

### 示例 7：UUID 生成器

```typescript
var name: string = "gen_uuid";
var description: string = "生成一个随机 UUID";
var parameters = {
    type: "object",
    properties: {
        count: { type: "integer", description: "生成数量（默认 1）" }
    }
};

async function execute(ctx: any, args: any): Promise<any> {
    var count = args.count || 1;
    var uuids = [];
    for (var i = 0; i < count; i++) {
        uuids.push(UUID.randomUUID().toString());
    }
    return {
        toAgent: uuids.join("\n")
    };
}
```

### 示例 8：带条件判断的工具 — 结束推理

```typescript
var name: string = "confirm_action";
var description: string = "确认是否执行某操作，用户回复 yes/no";
var parameters = {
    type: "object",
    properties: {
        action: { type: "string", description: "要确认的操作描述" }
    },
    required: ["action"]
};

async function execute(ctx: any, args: any): Promise<any> {
    return {
        toAgent: "已向用户发送确认请求: " + args.action,
        userReply: "请确认是否执行: " + args.action + "\n回复 yes 或 no",
        endTurn: true  // 停止 AI 推理，等待用户回复
    };
}
```

### 示例 9：使用 HashMap 统计词频

```typescript
var name: string = "word_count";
var description: string = "统计文本中各单词出现次数";
var parameters = {
    type: "object",
    properties: {
        text: { type: "string", description: "要统计的文本" }
    },
    required: ["text"]
};

async function execute(ctx: any, args: any): Promise<any> {
    var words = args.text.toLowerCase().split(/\\s+/);
    var map = new HashMap();
    for (var i = 0; i < words.length; i++) {
        var w = words[i].replace(/[^a-zA-Z\u4e00-\u9fff]/g, "");
        if (w.length > 0) {
            var count = map.get(w) || 0;
            map.put(w, count + 1);
        }
    }

    var sb = new StringBuilder();
    var entries = map.entrySet().iterator();
    while (entries.hasNext()) {
        var e = entries.next();
        sb.append(e.getKey() + ": " + e.getValue() + "\n");
    }
    return { toAgent: sb.toString() || "(无有效单词)" };
}
```

### 示例 10：访问调用者上下文

```typescript
var name: string = "whoami";
var description: string = "返回当前调用者信息";
var parameters = {
    type: "object",
    properties: {}
};

async function execute(ctx: any, args: any): Promise<any> {
    var info = "userId: " + ctx.userId
        + "\nconversationId: " + ctx.conversationId
        + "\ngroupId: " + (ctx.groupId || "(无)");
    return {
        toAgent: info
    };
}
```

### 示例 11：访问运行时实例

```typescript
var name: string = "agent_info";
var description: string = "查看当前运行中的 Agent 信息";
var parameters = {
    type: "object",
    properties: {}
};

async function execute(ctx: any, args: any): Promise<any> {
    // AgentManager 是 Kotlin object 单例，自动注入
    var agents = AgentManager.all();
    var sb = new StringBuilder();
    sb.append("Agents: " + agents.size() + "\n");
    for (var i = 0; i < agents.size(); i++) {
        var a = agents.get(i);
        sb.append("  " + a.getId() + " (" + a.getName() + ")\n");
    }

    // ConfigManager 也是自动注入的
    var result = ConfigManager.loadOrCreate();
    var config = result.getConfig();
    sb.append("Model: " + config.getModel() + "\n");
    sb.append("Mode: " + config.getMode() + "\n");

    return { toAgent: sb.toString() };
}
```

### 示例 12：访问 Mirai Bot 发送消息

```typescript
var name: string = "send_group_msg";
var description: string = "通过 Mirai Bot 直接发送群消息";
var parameters = {
    type: "object",
    properties: {
        groupId: { type: "string", description: "群号" },
        text: { type: "string", description: "消息内容" }
    },
    required: ["groupId", "text"]
};

async function execute(ctx: any, args: any): Promise<any> {
    // MiraiToolImpl 是 Kotlin object，.bot 字段持有 Mirai Bot 实例
    var bot = MiraiToolImpl.bot;
    if (bot == null) {
        return { toAgent: "error: Bot 未连接" };
    }
    var group = bot.getGroup(java.lang.Long.parseLong(args.groupId));
    if (group == null) {
        return { toAgent: "error: 群不存在: " + args.groupId };
    }
    group.sendMessage(args.text);
    return { toAgent: "sent to group " + args.groupId, userReply: "已发送。" };
}
```

### 示例 13：插件模式 — onLoad 注册事件监听

```typescript
var name: string = "auto_reply";
var description: string = "自动回复插件";
var parameters = { type: "object", properties: {} };

function onLoad() {
    java.lang.System.out.println("[auto_reply] 插件已加载");
    // 在 onLoad 中可以做任何初始化工作
    // 比如注册额外工具、读取配置等
}

function onUnload() {
    java.lang.System.out.println("[auto_reply] 插件已卸载");
}

async function execute(ctx: any, args: any): Promise<any> {
    return { toAgent: "auto_reply plugin is active" };
}
```

### 示例 14：纯插件模式（不定义 execute）

```typescript
var name: string = "startup_task";
var description: string = "启动时执行一次性的初始化任务";

function onLoad() {
    // 脚本加载时执行，不注册为工具
    var agents = AgentManager.all();
    java.lang.System.out.println("[startup] 发现 " + agents.size() + " 个 Agent");

    // 读取配置
    var config = ConfigManager.loadOrCreate().getConfig();
    java.lang.System.out.println("[startup] 模型: " + config.getModel());
}

function onUnload() {
    java.lang.System.out.println("[startup] 清理完成");
}
```

---

## manage_scripts 工具用法

通过 `manage_scripts` 工具管理脚本的生命周期：

| action | 参数 | 说明 |
|--------|------|------|
| `list` | 无 | 列出所有已加载的脚本 |
| `load` | `name` | 加载指定 `.ts` 文件 |
| `unload` | `name` | 卸载已加载的脚本 |
| `reload` | `name` | 重载脚本（先卸载再加载） |
| `load_all` | 无 | 加载 scripts 目录下所有 `.ts` 文件 |
| `create` | `name`, `content`, `auto_load`(可选) | 创建新脚本并自动加载 |
| `view` | `name` | 查看脚本源码 |
| `diagnose` | `name` | 编译检查，不加载（显示行号错误） |
| `run` | `name`, `args` | 一次性执行，不注册为工具 |

> `name` 参数可以省略 `.ts` 后缀，系统会自动补全。

### 创建脚本的工作流

1. 用 `create` 传入脚本内容（`name` 必须以 `.ts` 结尾）
2. 系统自动编译 TypeScript → JavaScript
3. 自动执行并注册为工具
4. 如果出错，用 `view` 查看源码，修改后用 `reload` 重载

### 常见错误及排查

| 错误信息 | 原因 | 修复 |
|----------|------|------|
| `Script must export 'name'` | 缺少 `var name = "..."` | 添加 name 变量 |
| `Script must export 'execute' function` | 缺少 `execute` 函数 | 添加 execute 函数 |
| `Cannot create XXX: no matching constructor` | Java 类构造函数参数不匹配 | 检查构造函数签名 |
| `XXX is not defined` | 使用了不存在的变量/类 | 检查拼写，或用 `Java.type()` 加载 |
| 脚本创建成功但调用无响应 | `execute` 函数没有 return | 确保 return 了 `{ toAgent: ... }` |

---

## 最佳实践

1. **description 要清晰** — AI 根据 description 决定何时调用你的工具，写得越准确越好
2. **处理错误** — 用 `if` 检查输入，返回 `{ toAgent: "error: ..." }` 而不是抛异常
3. **toAgent 必填** — 即使有 `userReply`，也必须提供 `toAgent`
4. **避免副作用** — 脚本应该是幂等的，相同输入产生相同输出
5. **保持简短** — 脚本运行在 Rhino 中，性能有限，复杂逻辑尽量拆分
