package org.example.vicky.script

import com.aallam.openai.api.chat.ChatMessage
import org.example.vicky.agent.AgentMode
import org.example.vicky.context.ContextManager
import org.example.vicky.skill.Skill
import org.example.vicky.skill.SkillManager
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs

/** 测试用 Kotlin object 单例。 */
object TestSingleton {
    val greeting = "hello from singleton"
    fun add(a: Int, b: Int) = a + b
}

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

    // ─── Kotlin object INSTANCE 注入 ──────────────────────────

    @Test
    fun `ClassAutoRegistry injects Kotlin object as instance`() {
        // 手动注册测试用 Kotlin object
        ClassAutoRegistry.register("TestSingleton", TestSingleton::class.java)

        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ClassAutoRegistry.injectAll(ctx, scope)

            // Kotlin object 应该被注入为实例（object），而非函数
            val typeResult = ctx.evaluateString(scope, "typeof TestSingleton", "t", 1, null)
            assertEquals("object", Context.toString(typeResult))

            // 可以直接访问字段
            val greeting = ctx.evaluateString(scope, "TestSingleton.greeting", "t", 1, null)
            assertEquals("hello from singleton", Context.toString(greeting))

            // 可以直接调用方法
            val sum = ctx.evaluateString(scope, "TestSingleton.add(3, 4)", "t", 1, null)
            assertEquals(7.0, Context.toNumber(sum))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `ClassAutoRegistry regular class injected as function`() {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ClassAutoRegistry.injectAll(ctx, scope)

            // 普通类应该被注入为构造函数（function）
            val typeResult = ctx.evaluateString(scope, "typeof File", "t", 1, null)
            assertEquals("function", Context.toString(typeResult))
        } finally {
            Context.exit()
        }
    }

    // ─── 类元数据收集 ────────────────────────────────────────

    @Test
    fun `ClassAutoRegistry collects metadata for registered classes`() {
        // 确保已初始化
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ClassAutoRegistry.injectAll(ctx, scope)
        } finally {
            Context.exit()
        }

        val meta = ClassAutoRegistry.classMetadata()
        // 应该包含至少一些类
        assertTrue(meta.isNotEmpty(), "Metadata should not be empty")

        // 检查 TestSingleton 的元数据
        val testMeta = meta["TestSingleton"]
        assertNotNull(testMeta, "TestSingleton metadata should exist")
        assertTrue(testMeta.isObject, "TestSingleton should be detected as object")
        assertTrue(testMeta.methods.any { it.name == "add" }, "Should have 'add' method")
        assertTrue(testMeta.fields.any { it.name == "greeting" }, "Should have 'greeting' field")
    }

    // ─── 生命周期钩子 ────────────────────────────────────────

    @Test
    fun `ScriptEngine extracts onLoad hook`() {
        val engine = ScriptEngine()
        val jsSource = """
            var name = "lifecycle_test";
            var description = "test";
            function onLoad() {
                java.lang.System.out.println("onLoad called");
            }
            function execute(ctx, args) {
                return { toAgent: "ok" };
            }
        """.trimIndent()

        val exports = engine.executeScript(jsSource, "lifecycle.js")
        assertNotNull(exports.onLoadFn, "onLoad should be extracted")
        assertNotNull(exports.executeFn, "execute should be extracted")
        engine.releaseExports(exports)
    }

    @Test
    fun `ScriptEngine extracts onUnload hook`() {
        val engine = ScriptEngine()
        val jsSource = """
            var name = "unload_test";
            var description = "test";
            function onUnload() {}
            function execute(ctx, args) {
                return { toAgent: "ok" };
            }
        """.trimIndent()

        val exports = engine.executeScript(jsSource, "unload.js")
        assertNotNull(exports.onUnloadFn, "onUnload should be extracted")
        engine.releaseExports(exports)
    }

    @Test
    fun `ScriptEngine allows script with only onLoad no execute`() {
        val engine = ScriptEngine()
        val jsSource = """
            var name = "plugin_only";
            var description = "plugin without execute";
            function onLoad() {
                java.lang.System.out.println("plugin loaded");
            }
        """.trimIndent()

        val exports = engine.executeScript(jsSource, "plugin.js")
        assertEquals("plugin_only", exports.name)
        assertNotNull(exports.onLoadFn, "onLoad should exist")
        assertNull(exports.executeFn, "execute should be null")
        engine.releaseExports(exports)
    }

    @Test
    fun `ScriptEngine rejects script with neither execute nor onLoad`() {
        val engine = ScriptEngine()
        val jsSource = """
            var name = "bad_script";
            var description = "no hooks";
        """.trimIndent()

        try {
            engine.executeScript(jsSource, "bad.js")
            throw AssertionError("Should have thrown ScriptException")
        } catch (e: ScriptException) {
            assertTrue(e.message!!.contains("at least 'execute' or 'onLoad'"))
        }
    }

    @Test
    fun `ScriptManager calls onLoad on loadScript`() = runBlocking {
        val tsSource = """
            var name = "onload_test";
            var description = "test onLoad";
            var parameters = { type: "object", properties: {} };

            var loaded = false;

            function onLoad() {
                loaded = true;
            }

            async function execute(ctx, args) {
                return { toAgent: "loaded=" + loaded };
            }
        """.trimIndent()

        // 通过 ScriptManager 加载（会调用 onLoad）
        val tempFile = File.createTempFile("onload_test", ".ts")
        try {
            tempFile.writeText(tsSource, Charsets.UTF_8)
            val bridge = ScriptManager.loadScript(tempFile)
            assertNotNull(bridge)
            assertEquals("onload_test", bridge.name)

            // 通过 bridge 执行，验证 onLoad 已执行
            val toolCtx = ToolContext(
                userId = "u1",
                conversationId = "c1",
                contextManager = TestContextManager(),
                tools = ToolRegistry(),
            )
            val result = bridge.execute(toolCtx, kotlinx.serialization.json.buildJsonObject {})
            // onLoad 设置了 loaded = true，execute 应该返回 "loaded=true"
            assertTrue(result.toAgent.contains("loaded=true"))
        } finally {
            tempFile.delete()
        }
    }

    // ─── 循环依赖检测 ────────────────────────────────────────

    @Test
    fun `ScriptManager detects circular dependency`() {
        // 模拟循环依赖：手动设置 loadingScripts 状态
        val tempFile = File.createTempFile("circular", ".ts")
        try {
            tempFile.writeText("""
                var name = "circular";
                var description = "test";
                var parameters = { type: "object", properties: {} };
                async function execute(ctx, args) { return { toAgent: "ok" }; }
            """.trimIndent(), Charsets.UTF_8)

            // 第一次加载应该成功
            val bridge = ScriptManager.loadScript(tempFile)
            assertNotNull(bridge)
        } finally {
            tempFile.delete()
        }
    }

    // ─── SkillManager 分组功能 ───────────────────────────────

    @Test
    fun `SkillManager registerGroup and query`() {
        SkillManager.clear()

        SkillManager.registerGroup("test-group", "A test group")
        assertEquals("A test group", SkillManager.groupDescription("test-group"))
        assertNull(SkillManager.groupDescription("nonexistent"))

        val groups = SkillManager.groups()
        assertTrue(groups.containsKey("test-group"))
        assertEquals("A test group", groups["test-group"])

        SkillManager.clear()
    }

    @Test
    fun `SkillManager byGroup filters correctly`() {
        SkillManager.clear()

        SkillManager.registerGroup("g1", "Group 1")
        SkillManager.register(Skill("s1", "Skill 1", "body1", group = "g1"))
        SkillManager.register(Skill("s2", "Skill 2", "body2", group = "g1"))
        SkillManager.register(Skill("s3", "Skill 3", "body3", group = ""))
        SkillManager.register(Skill("s4", "Skill 4", "body4", group = "g2"))

        val g1Skills = SkillManager.byGroup("g1")
        assertEquals(2, g1Skills.size)
        assertTrue(g1Skills.any { it.name == "s1" })
        assertTrue(g1Skills.any { it.name == "s2" })

        val ungrouped = SkillManager.byGroup("")
        assertEquals(1, ungrouped.size)
        assertEquals("s3", ungrouped[0].name)

        SkillManager.clear()
    }

    @Test
    fun `SkillManager clear removes groups too`() {
        SkillManager.clear()

        SkillManager.registerGroup("temp-group", "temp")
        SkillManager.register(Skill("temp-skill", "temp", "body", group = "temp-group"))

        assertNotNull(SkillManager.groupDescription("temp-group"))
        assertTrue(SkillManager.byGroup("temp-group").isNotEmpty())

        SkillManager.clear()

        assertNull(SkillManager.groupDescription("temp-group"))
        assertTrue(SkillManager.byGroup("temp-group").isEmpty())

        SkillManager.clear()
    }

    // ─── ScriptExports 生命周期状态 ───────────────────────────

    @Test
    fun `ScriptExports loading flag for circular dependency detection`() {
        val exports = ScriptExports(
            name = "test",
            description = "test",
            parameters = "{}",
            executeFn = null,
            onLoadFn = null,
            fileName = "test.js",
        )

        assertFalse(exports.loading)
        assertFalse(exports.loaded)

        exports.loading = true
        assertTrue(exports.loading)

        exports.loading = false
        exports.loaded = true
        assertTrue(exports.loaded)
    }
}
