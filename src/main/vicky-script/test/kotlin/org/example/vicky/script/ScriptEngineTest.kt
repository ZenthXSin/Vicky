package org.example.vicky.script

import com.aallam.openai.api.chat.ChatMessage
import org.example.vicky.agent.AgentMode
import org.example.vicky.context.ContextManager
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private class TestContextManager : ContextManager {
    private val stores = mutableMapOf<String, MutableList<ChatMessage>>()
    override fun history(conversationId: String) = stores.getOrPut(conversationId) { mutableListOf() }
    override fun buildSystemPrompt(mode: AgentMode, tools: ToolRegistry) = "test"
    override suspend fun ensureContextBudget(history: MutableList<ChatMessage>) {}
    override fun compactOldToolRounds(history: MutableList<ChatMessage>) {}
    override fun clear(conversationId: String) { stores.remove(conversationId) }
    override fun trimIfNeeded(conversationId: String) {}
}

class ScriptEngineTest {

    @Test
    fun `Rhino can evaluate basic JS`() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            val result = ctx.evaluateString(scope, "1 + 2", "test.js", 1, null)
            assertEquals(3.0, Context.toNumber(result))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `Rhino can evaluate string operations`() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            val result = ctx.evaluateString(scope, "'hello' + ' ' + 'world'", "test.js", 1, null)
            assertEquals("hello world", Context.toString(result))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `Rhino can set and get global properties`() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ScriptableObject.putProperty(scope, "testName", "Vicky")
            ScriptableObject.putProperty(scope, "testNum", 42)
            assertEquals("Vicky", Context.toString(ctx.evaluateString(scope, "testName", "t", 1, null)))
            assertEquals(42.0, Context.toNumber(ctx.evaluateString(scope, "testNum", "t", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `Rhino can call Java functions from JS`() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            val fn = object : org.mozilla.javascript.BaseFunction() {
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                    return "Hello, ${Context.toString(args[0])}!"
                }
            }
            ScriptableObject.putProperty(scope, "greet", fn)
            val result = ctx.evaluateString(scope, "greet('World')", "t", 1, null)
            assertEquals("Hello, World!", Context.toString(result))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `ScriptEngine executeScript extracts exports`() {
        val engine = ScriptEngine()
        val jsSource = """
            var name = "test_tool";
            var description = "A test tool";
            var parameters = {"type":"object","properties":{"msg":{"type":"string"}},"required":["msg"]};
            function execute(ctx, args) {
                return { toAgent: "echo: " + args.msg, userReply: args.msg };
            }
        """.trimIndent()

        val exports = engine.executeScript(jsSource, "test.js")
        assertEquals("test_tool", exports.name)
        assertEquals("A test tool", exports.description)
        assertNotNull(exports.parameters)
        assertNotNull(exports.executeFn)
        engine.releaseExports(exports)
    }

    @Test
    fun `ScriptEngine can call execute function`() {
        val engine = ScriptEngine()
        val jsSource = """
            var name = "echo";
            var description = "Echo tool";
            var parameters = {"type":"object","properties":{}};
            function execute(ctx, args) {
                return { toAgent: "echoed: " + args.msg };
            }
        """.trimIndent()

        val exports = engine.executeScript(jsSource, "echo.js")
        val toolCtx = ToolContext(
            userId = "test-user",
            conversationId = "test-conv",
            contextManager = TestContextManager(),
            tools = ToolRegistry(),
        )
        val args = kotlinx.serialization.json.buildJsonObject {
            put("msg", kotlinx.serialization.json.JsonPrimitive("hello"))
        }
        val result = engine.callExecute(exports, toolCtx, args)
        assertEquals("echoed: hello", Context.toString(result["toAgent"]))
        engine.releaseExports(exports)
    }

    @Test
    fun `ClassAutoRegistry injects classes`() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ClassAutoRegistry.injectAll(ctx, scope)

            val result = ctx.evaluateString(scope, "typeof File", "t", 1, null)
            assertEquals("function", Context.toString(result))

            val javaResult = ctx.evaluateString(scope, "typeof Java", "t", 1, null)
            assertEquals("object", Context.toString(javaResult))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `ClassAutoRegistry File class works in JS`() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ClassAutoRegistry.injectAll(ctx, scope)

            val result = ctx.evaluateString(scope, "var f = new File('.'); f.getPath()", "t", 1, null)
            assertEquals(".", Context.toString(result))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `ScriptToolBridge parses parameters JSON`() {
        val params = ScriptToolBridge.parseParameters("""
            {"type":"object","properties":{"x":{"type":"integer"}},"required":["x"]}
        """.trimIndent())
        assertEquals("object", params["type"]?.toString()?.removeSurrounding("\""))
        assertNotNull(params["properties"])
    }

    @Test
    fun `ScriptEngine can compile TypeScript to JS`() {
        val engine = ScriptEngine()
        val tsSource = """
            var name: string = "ts_tool";
            var description: string = "A TypeScript tool";
            var parameters = {
                type: "object",
                properties: { msg: { type: "string" } },
                required: ["msg"]
            };

            async function execute(ctx: any, args: Record<string, any>): Promise<any> {
                const greeting: string = "Hello, " + args.msg;
                return { toAgent: greeting };
            }
        """.trimIndent()

        val jsOutput = engine.compileTs(tsSource, "ts_tool.ts")
        assertNotNull(jsOutput)
        // TS 类型注解应被移除
        assert(!jsOutput.contains(": string")) { "TS type annotations should be stripped" }
        assert(jsOutput.contains("name")) { "Should contain variable name" }
        assert(jsOutput.contains("execute")) { "Should contain execute function" }
    }

    @Test
    fun `Full pipeline - compile TS then execute`() {
        val engine = ScriptEngine()
        val tsSource = """
            var name: string = "greet";
            var description: string = "Greeting tool";
            var parameters = {
                type: "object",
                properties: { who: { type: "string" } },
                required: ["who"]
            };

            async function execute(ctx: any, args: Record<string, any>): Promise<any> {
                return { toAgent: "Hi " + args.who, userReply: "你好" + args.who };
            }
        """.trimIndent()

        // 1. 编译 TS → JS
        val js = engine.compileTs(tsSource, "greet.ts")

        // 2. 执行 JS 提取导出
        val exports = engine.executeScript(js, "greet.ts")
        assertEquals("greet", exports.name)
        assertEquals("Greeting tool", exports.description)

        // 3. 调用 execute
        val toolCtx = ToolContext(
            userId = "u1",
            conversationId = "c1",
            contextManager = TestContextManager(),
            tools = ToolRegistry(),
        )
        val args = kotlinx.serialization.json.buildJsonObject {
            put("who", kotlinx.serialization.json.JsonPrimitive("World"))
        }
        val result = engine.callExecute(exports, toolCtx, args)
        assertEquals("Hi World", Context.toString(result["toAgent"]))
        engine.releaseExports(exports)
    }

    @Test
    fun `Full pipeline - hello_ts test script`() {
        val engine = ScriptEngine()
        val tsSource = ScriptEngineTest::class.java.classLoader
            ?.getResourceAsStream("scripts/hello.ts")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return // skip if test resource not found

        val js = engine.compileTs(tsSource, "hello.ts")
        val exports = engine.executeScript(js, "hello.ts")
        assertEquals("hello", exports.name)
        assertEquals("打招呼工具", exports.description)

        val toolCtx = ToolContext(
            userId = "u1",
            conversationId = "c1",
            contextManager = TestContextManager(),
            tools = ToolRegistry(),
        )
        val args = kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive("测试用户"))
        }
        val result = engine.callExecute(exports, toolCtx, args)
        assert(Context.toString(result["toAgent"]).contains("greeted 测试用户"))
        engine.releaseExports(exports)
    }
}
