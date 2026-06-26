package org.example.vicky.script

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.EcmaError
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Rhino JS 引擎封装：负责 TS→JS 编译、JS 执行、导出提取。
 */
class ScriptEngine {
    private val json = Json { isLenient = true }

    /**
     * 编译 TypeScript → JavaScript。
     * 在独立大栈线程中执行，避免 Rhino 深度 AST 递归导致 StackOverflow。
     */
    fun compileTs(tsSource: String, fileName: String, options: TsCompilerOptions = TsCompilerOptions()): String {
        var result: String? = null
        var error: Throwable? = null

        val thread = Thread(null, {
            val ctx = Context.enter()
            try {
                ctx.optimizationLevel = -1
                val scope = ctx.initStandardObjects()

                val tscCode = loadTypeScriptCompiler()
                ctx.evaluateString(scope, tscCode, "typescript.js", 1, null)

                val escaped = escapeForTemplateLiteral(tsSource)
                val jsResult = ctx.evaluateString(
                    scope,
                    """
                    (function() {
                        var result = ts.transpileModule(`$escaped`, {
                            compilerOptions: {
                                target: ts.ScriptTarget.${options.target},
                                module: ts.ModuleKind.${options.module},
                                strict: ${options.strict},
                                esModuleInterop: true,
                                skipLibCheck: true
                            },
                            fileName: "$fileName"
                        });
                        return result.outputText;
                    })()
                    """.trimIndent(),
                    fileName, 1, null
                )
                result = Context.toString(jsResult)
            } catch (e: Throwable) {
                error = e
            } finally {
                Context.exit()
            }
        }, "ts-compile-${fileName}", COMPILER_STACK_SIZE)

        thread.start()
        thread.join()
        error?.let { throw it }
        return result ?: throw ScriptException("TypeScript compilation returned null for $fileName")
    }

    /**
     * 执行编译后的 JS 代码，提取导出的 Tool 定义。
     */
    fun executeScript(jsSource: String, fileName: String): ScriptExports {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            injectPrintln(ctx, scope)
            injectTimers(ctx, scope)
            ClassAutoRegistry.injectAll(ctx, scope)

            // 注入 Promise polyfill（Rhino 原生不支持 Promise，TS async 编译产物需要它）
            ctx.evaluateString(scope, PROMISE_POLYFILL, "promise-polyfill", 1, null)

            // 注入 coroutine 对象（launch/delay 真协程支持）
            val scriptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            injectCoroutine(ctx, scope, scriptScope)

            ctx.evaluateString(
                scope,
                """
                var __script_result = (function() {
                    $jsSource
                    return {
                        name: typeof name !== 'undefined' ? name : null,
                        description: typeof description !== 'undefined' ? description : null,
                        parameters: typeof parameters !== 'undefined' ? parameters : null,
                        execute: typeof execute !== 'undefined' ? execute : null,
                        onLoad: typeof onLoad !== 'undefined' ? onLoad : null,
                        onUnload: typeof onUnload !== 'undefined' ? onUnload : null
                    };
                })();
                """.trimIndent(),
                fileName, 1, null
            )

            val result = ScriptableObject.getProperty(scope, "__script_result") as ScriptableObject
            val name = Context.toString(ScriptableObject.getProperty(result, "name"))
                .takeIf { it != "null" && it != "undefined" }
                ?: fileName.removeSuffix(".ts") // 兜底：用文件名作为工具名
            val description = Context.toString(ScriptableObject.getProperty(result, "description"))
                .takeIf { it != "null" && it != "undefined" } ?: ""

            val paramsRaw = ScriptableObject.getProperty(result, "parameters")
            val parameters = if (paramsRaw != null && paramsRaw != Undefined.instance) {
                ctx.evaluateString(scope, "JSON.stringify(__script_result.parameters)", "params", 1, null)
                    .let { Context.toString(it) }
            } else """{"type":"object","properties":{}}"""

            val executeFn = ScriptableObject.getProperty(result, "execute")
                .takeIf { it != null && it != Undefined.instance }
            val onLoadFn = ScriptableObject.getProperty(result, "onLoad")
                .takeIf { it != null && it != Undefined.instance }
            val onUnloadFn = ScriptableObject.getProperty(result, "onUnload")
                .takeIf { it != null && it != Undefined.instance }

            // execute 和 onLoad 都是可选的，脚本可以只跑顶层代码

            return ScriptExports(
                name = name,
                description = description,
                parameters = parameters,
                executeFn = executeFn,
                onLoadFn = onLoadFn,
                onUnloadFn = onUnloadFn,
                fileName = fileName,
                rhinoScope = scope,
                rhinoContext = ctx,
                coroutineScope = scriptScope,
            )
        } catch (e: ScriptException) {
            Context.exit()
            throw e
        } catch (e: EcmaError) {
            Context.exit()
            val line = if (e.lineNumber() > 0) " (line ${e.lineNumber()})" else ""
            throw ScriptException("Runtime error in $fileName$line: ${e.name}: ${e.errorMessage}", e)
        } catch (e: EvaluatorException) {
            Context.exit()
            val line = if (e.lineNumber() > 0) " (line ${e.lineNumber()})" else ""
            throw ScriptException("Evaluation error in $fileName$line: ${e.message}", e)
        } catch (e: Exception) {
            Context.exit()
            throw ScriptException("Failed to execute script $fileName: ${e.message}", e)
        }
        // 注意：不 exit ctx，因为 executeFn 引用了 scope
    }

    /**
     * 调用脚本的 execute 函数。
     */
    fun callExecute(
        exports: ScriptExports,
        toolCtx: org.example.vicky.tool.ToolContext,
        args: JsonObject,
    ): Map<String, Any?> {
        val fn = exports.executeFn as Function
        val scope = exports.rhinoScope ?: throw ScriptException("Script scope not available")
        // Rhino Context 是 ThreadLocal，callExecute 可能在不同线程调用，
        // 必须在当前线程 enter/exit 自己的 Context。
        val ctx = Context.enter()
        try {
            // 构建 JS args 对象
            val jsArgs = ctx.newObject(scope)
            for ((key, value) in args) {
                when (value) {
                    is JsonPrimitive -> {
                        if (value.isString) {
                            ScriptableObject.putProperty(jsArgs, key, value.content)
                        } else {
                            val intVal = value.content.toIntOrNull()
                            val doubleVal = value.content.toDoubleOrNull()
                            val boolVal = value.content.toBooleanStrictOrNull()
                            when {
                                // 用 Double 传递所有数字，避免 Java Integer 等装箱类型
                                // 在 JS 引擎中 Number(javaObj) 返回 NaN 的问题
                                intVal != null -> ScriptableObject.putProperty(jsArgs, key, intVal.toDouble())
                                doubleVal != null -> ScriptableObject.putProperty(jsArgs, key, doubleVal)
                                boolVal != null -> ScriptableObject.putProperty(jsArgs, key, boolVal)
                                else -> ScriptableObject.putProperty(jsArgs, key, value.content)
                            }
                        }
                    }
                    else -> ScriptableObject.putProperty(jsArgs, key, value.toString())
                }
            }

            // 构建 ctx 对象
            val jsCtx = ctx.newObject(scope)
            ScriptableObject.putProperty(jsCtx, "userId", toolCtx.userId)
            ScriptableObject.putProperty(jsCtx, "conversationId", toolCtx.conversationId)
            ScriptableObject.putProperty(jsCtx, "groupId", toolCtx.groupId)

            // 辅助：获取 Mirai Bot 实例
            fun getMiraiBot(): Any? {
                val miraiClass = Class.forName("org.example.vicky.channel.onebot.MiraiToolImpl")
                val instance = miraiClass.getDeclaredField("INSTANCE").get(null)
                return miraiClass.getMethod("getBot").invoke(instance)
            }

            // ctx.sendGroupMessage(groupId, text) — 发送群消息
            val sendGroupFn = object : BaseFunction() {
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                    if (args.size < 2) return "error: sendGroupMessage(groupId, text) requires 2 args"
                    return try {
                        val bot = getMiraiBot() ?: return "error: Bot 未连接"
                        val group = bot.javaClass.getMethod("getGroup", Long::class.javaPrimitiveType)
                            .invoke(bot, Context.toString(args[0]).toLong())
                        if (group == null) "error: 群不存在: ${args[0]}"
                        else {
                            group.javaClass.getMethod("sendMessage", String::class.java).invoke(group, Context.toString(args[1]))
                            "ok"
                        }
                    } catch (e: Exception) { "error: ${e.message}" }
                }
            }
            ScriptableObject.putProperty(jsCtx, "sendGroupMessage", sendGroupFn)

            // ctx.sendMessage(targetId, text) — 发送私聊消息（好友/陌生人）
            val sendPrivateFn = object : BaseFunction() {
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                    if (args.size < 2) return "error: sendMessage(targetId, text) requires 2 args"
                    return try {
                        val bot = getMiraiBot() ?: return "error: Bot 未连接"
                        val targetId = Context.toString(args[0]).toLong()
                        val text = Context.toString(args[1])
                        // 尝试 getFriend，失败则 getStranger
                        var contact = try {
                            bot.javaClass.getMethod("getFriend", Long::class.javaPrimitiveType).invoke(bot, targetId)
                        } catch (_: Exception) { null }
                        if (contact == null) {
                            contact = try {
                                bot.javaClass.getMethod("getStranger", Long::class.javaPrimitiveType).invoke(bot, targetId)
                            } catch (_: Exception) { null }
                        }
                        if (contact == null) "error: 无法找到联系人: $targetId"
                        else {
                            contact.javaClass.getMethod("sendMessage", String::class.java).invoke(contact, text)
                            "ok"
                        }
                    } catch (e: Exception) { "error: ${e.message}" }
                }
            }
            ScriptableObject.putProperty(jsCtx, "sendMessage", sendPrivateFn)

            // ctx.setTimer(intervalMs, callback) — 创建定时器，返回 timer 对象
            val setTimerFn = object : BaseFunction() {
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                    if (args.size < 2) return "error: setTimer(intervalMs, callback) requires 2 args"
                    val intervalMs = (args[0] as? Number)?.toLong() ?: Context.toString(args[0]).toLong()
                    val callback = args[1] as? org.mozilla.javascript.Function ?: return "error: callback must be a function"

                    val timer = java.util.Timer("script-timer-${System.currentTimeMillis()}", true)
                    val task = object : java.util.TimerTask() {
                        override fun run() {
                            val threadCtx = Context.enter()
                            try {
                                callback.call(threadCtx, scope, scope, emptyArray())
                            } catch (e: Exception) {
                                println("[Vicky][script] timer callback error: ${e.message}")
                            } finally {
                                Context.exit()
                            }
                        }
                    }
                    timer.schedule(task, intervalMs, intervalMs)

                    // 返回一个可控制的 timer 对象
                    val timerObj = ctx.newObject(scope)
                    ScriptableObject.putProperty(timerObj, "cancel", object : BaseFunction() {
                        override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                            timer.cancel()
                            return "cancelled"
                        }
                    })
                    ScriptableObject.putProperty(timerObj, "interval", intervalMs)
                    return timerObj
                }
            }
            ScriptableObject.putProperty(jsCtx, "setTimer", setTimerFn)

            var result = fn.call(ctx, scope, scope, arrayOf(jsCtx, jsArgs))

            // 解包 Promise：TS async 编译产物返回 Promise，polyfill 同步解析，通过 then 提取值
            result = unwrapPromise(ctx, scope, result)

            if (result is ScriptableObject) {
                val map = mutableMapOf<String, Any?>()
                for (id in result.ids) {
                    val key = Context.toString(id)
                    val value = ScriptableObject.getProperty(result, key)
                    map[key] = if (value == Undefined.instance) null else value
                }
                return map
            }
            return mapOf("toAgent" to Context.toString(result))
        } finally {
            Context.exit()
        }
    }

    /**
     * 带行号信息的 execute 调用，供 diagnose / run 使用。
     */
    fun callExecuteSafe(
        exports: ScriptExports,
        toolCtx: org.example.vicky.tool.ToolContext,
        args: JsonObject,
    ): Map<String, Any?> {
        return try {
            callExecute(exports, toolCtx, args)
        } catch (e: EcmaError) {
            val line = if (e.lineNumber() > 0) " (line ${e.lineNumber()})" else ""
            mapOf("toAgent" to "Runtime error$line: ${e.name}: ${e.errorMessage}", "error" to true)
        } catch (e: EvaluatorException) {
            val line = if (e.lineNumber() > 0) " (line ${e.lineNumber()})" else ""
            mapOf("toAgent" to "Evaluation error$line: ${e.message}", "error" to true)
        } catch (e: Exception) {
            mapOf("toAgent" to "Error: ${e.message}", "error" to true)
        }
    }

    /** 注入 println / print 到脚本 scope（Rhino 1.8 initStandardObjects 不含 shell 函数）。 */
    private fun injectPrintln(ctx: Context, scope: ScriptableObject) {
        val out = System.out
        val printlnFn = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                val line = args.joinToString(" ") { if (it == Undefined.instance) "undefined" else Context.toString(it) }
                out.println(line)
                return Undefined.instance
            }
        }
        val printFn = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                val line = args.joinToString(" ") { if (it == Undefined.instance) "undefined" else Context.toString(it) }
                out.print(line)
                return Undefined.instance
            }
        }
        ScriptableObject.putProperty(scope, "println", printlnFn)
        ScriptableObject.putProperty(scope, "print", printFn)
    }

    /** 注入 setInterval / clearInterval / setTimeout / clearTimeout 到全局 scope。 */
    private fun injectTimers(ctx: Context, scope: ScriptableObject) {
        val timers = ConcurrentHashMap<Int, java.util.Timer>()
        val timerId = java.util.concurrent.atomic.AtomicInteger(0)

        // setInterval(callback, ms) → id
        ScriptableObject.putProperty(scope, "setInterval", object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.size < 2) return Undefined.instance
                val callback = args[0] as? org.mozilla.javascript.Function ?: return Undefined.instance
                val ms = (args[1] as? Number)?.toLong() ?: Context.toString(args[1]).toLong()
                val id = timerId.incrementAndGet()
                val timer = java.util.Timer("setInterval-$id", true)
                timer.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        val threadCtx = Context.enter()
                        try {
                            callback.call(threadCtx, scope, scope, emptyArray())
                        } catch (e: Exception) {
                            println("[Vicky][script] setInterval callback error: ${e.message}")
                        } finally {
                            Context.exit()
                        }
                    }
                }, ms, ms)
                timers[id] = timer
                return id
            }
        })

        // clearInterval(id)
        ScriptableObject.putProperty(scope, "clearInterval", object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.isEmpty()) return Undefined.instance
                val id = (args[0] as? Number)?.toInt() ?: Context.toString(args[0]).toIntOrNull() ?: return Undefined.instance
                timers.remove(id)?.cancel()
                return Undefined.instance
            }
        })

        // setTimeout(callback, ms) → id
        ScriptableObject.putProperty(scope, "setTimeout", object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.size < 2) return Undefined.instance
                val callback = args[0] as? org.mozilla.javascript.Function ?: return Undefined.instance
                val ms = (args[1] as? Number)?.toLong() ?: Context.toString(args[1]).toLong()
                val id = timerId.incrementAndGet()
                val timer = java.util.Timer("setTimeout-$id", true)
                timer.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        val threadCtx = Context.enter()
                        try {
                            callback.call(threadCtx, scope, scope, emptyArray())
                        } catch (e: Exception) {
                            println("[Vicky][script] setTimeout callback error: ${e.message}")
                        } finally {
                            Context.exit()
                            timers.remove(id)
                        }
                    }
                }, ms)
                timers[id] = timer
                return id
            }
        })

        // clearTimeout(id)
        ScriptableObject.putProperty(scope, "clearTimeout", object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.isEmpty()) return Undefined.instance
                val id = (args[0] as? Number)?.toInt() ?: Context.toString(args[0]).toIntOrNull() ?: return Undefined.instance
                timers.remove(id)?.cancel()
                return Undefined.instance
            }
        })
    }

    /**
     * 注入 coroutine 对象到脚本 scope，提供真协程支持。
     * - coroutine.launch(callback) — 启动后台协程，callback 内可调用 this.delay(ms)
     * - callback 内 this.delay(ms) — 非阻塞延迟
     * - callback 内 this.launch(fn) — 嵌套启动子协程
     */
    private fun injectCoroutine(ctx: Context, scope: ScriptableObject, scriptScope: kotlinx.coroutines.CoroutineScope) {
        val coroutineObj = ctx.newObject(scope)

        // coroutine.launch(callback) — 启动后台协程
        ScriptableObject.putProperty(coroutineObj, "launch", object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.isEmpty() || args[0] !is org.mozilla.javascript.Function) {
                    return "error: launch requires a callback function"
                }
                val callback = args[0] as org.mozilla.javascript.Function
                scriptScope.launch(Dispatchers.Default) {
                    // 为本次协程创建 scope 对象，绑定 delay/launch
                    val threadCtx = Context.enter()
                    try {
                        val coScope = threadCtx.newObject(scope)
                        ScriptableObject.putProperty(coScope, "delay", object : BaseFunction() {
                            override fun call(cx: Context, sc: Scriptable, tObj: Scriptable, dArgs: Array<out Any?>): Any? {
                                if (dArgs.isEmpty()) return "error: delay(ms) requires 1 arg"
                                val ms = (dArgs[0] as? Number)?.toLong() ?: Context.toString(dArgs[0]).toLong()
                                runBlocking { delay(ms) }
                                return "delayed ${ms}ms"
                            }
                        })
                        ScriptableObject.putProperty(coScope, "launch", object : BaseFunction() {
                            override fun call(cx: Context, sc: Scriptable, tObj: Scriptable, lArgs: Array<out Any?>): Any? {
                                if (lArgs.isEmpty() || lArgs[0] !is org.mozilla.javascript.Function) return "error: launch requires callback"
                                val sub = lArgs[0] as org.mozilla.javascript.Function
                                scriptScope.launch(Dispatchers.Default) {
                                    val subCtx = Context.enter()
                                    try {
                                        sub.call(subCtx, scope, coScope, emptyArray())
                                    } catch (e: Exception) {
                                        println("[Vicky][script] coroutine error: ${e.message}")
                                    } finally {
                                        Context.exit()
                                    }
                                }
                                return "launched"
                            }
                        })
                        callback.call(threadCtx, scope, coScope, emptyArray())
                    } catch (e: Exception) {
                        println("[Vicky][script] coroutine error: ${e.message}")
                    } finally {
                        Context.exit()
                    }
                }
                return "launched"
            }
        })

        ScriptableObject.putProperty(scope, "coroutine", coroutineObj)
    }

    fun releaseExports(exports: ScriptExports) {
        // 释放 executeScript 中 enter 的 Rhino Context（ThreadLocal），
        // 避免长期运行时泄漏。callExecute 使用自己的 Context，不受影响。
        exports.rhinoContext?.let {
            try { Context.exit() } catch (_: Exception) {}
        }
    }

    private fun loadTypeScriptCompiler(): String {
        val stream = ScriptEngine::class.java.classLoader?.getResourceAsStream("typescript.js")
            ?: throw ScriptException("typescript.js not found in resources")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * 解包 Promise 对象。polyfill 同步解析，通过 then 提取已解析的值。
     */
    private fun unwrapPromise(ctx: Context, scope: ScriptableObject, value: Any?): Any? {
        if (value !is ScriptableObject) return value
        // 检查是否是 Promise（有 then 方法）
        val thenProp = ScriptableObject.getProperty(value, "then")
        if (thenProp == null || thenProp == Undefined.instance) return value
        // 用 JS 代码调用 then，避免 Rhino Function.call 签名问题
        ScriptableObject.putProperty(scope, "__promise_to_resolve", value)
        ScriptableObject.putProperty(scope, "__resolved_value", Undefined.instance)
        ctx.evaluateString(scope, """
            (function() {
                try {
                    __promise_to_resolve.then(function(v) { __resolved_value = v; });
                } catch(e) {}
            })()
        """.trimIndent(), "unwrap-promise", 1, null)
        val resolved = ScriptableObject.getProperty(scope, "__resolved_value")
        // 清理临时变量
        ScriptableObject.deleteProperty(scope, "__promise_to_resolve")
        ScriptableObject.deleteProperty(scope, "__resolved_value")
        if (resolved == null || resolved == Undefined.instance) return value
        return unwrapPromise(ctx, scope, resolved) // 递归解包嵌套 Promise
    }

    private fun escapeForTemplateLiteral(s: String): String =
        s.replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\\$")

    companion object {
        /** TypeScript 编译线程栈大小（默认 1MB 栈不够 tsc 的深度递归） */
        private const val COMPILER_STACK_SIZE = 8 * 1024 * 1024L // 8MB

        /** 精简版 Promise polyfill，供 Rhino 执行 TS 编译产物中的 async 代码 */
        private const val PROMISE_POLYFILL = """
if (typeof Promise === 'undefined') {
    var Promise = function(executor) {
        var state = 'pending';
        var value = null;
        var callbacks = [];
        function resolve(val) {
            if (state !== 'pending') return;
            state = 'fulfilled'; value = val;
            for (var i = 0; i < callbacks.length; i++) callbacks[i][0](val);
        }
        function reject(err) {
            if (state !== 'pending') return;
            state = 'rejected'; value = err;
            for (var i = 0; i < callbacks.length; i++) callbacks[i][1](err);
        }
        this.then = function(onFulfilled, onRejected) {
            return new Promise(function(res, rej) {
                var handle = [function(v) { try { res(onFulfilled ? onFulfilled(v) : v); } catch(e) { rej(e); } },
                              function(e) { try { rej(onRejected ? onRejected(e) : e); } catch(ex) { rej(ex); } }];
                if (state === 'fulfilled') handle[0](value);
                else if (state === 'rejected') handle[1](value);
                else callbacks.push(handle);
            });
        };
        this.catch = function(onRejected) { return this.then(null, onRejected); };
        try { executor(resolve, reject); } catch(e) { reject(e); }
    };
    Promise.resolve = function(v) { return new Promise(function(r) { r(v); }); };
    Promise.reject = function(v) { return new Promise(function(_, r) { r(v); }); };
    Promise.all = function(arr) {
        return new Promise(function(resolve, reject) {
            var results = []; var count = 0;
            for (var i = 0; i < arr.length; i++) {
                (function(idx) {
                    arr[idx].then(function(val) { results[idx] = val; count++; if (count === arr.length) resolve(results); }, reject);
                })(i);
            }
            if (arr.length === 0) resolve(results);
        });
    };
}
"""
    }
}

class ScriptException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
