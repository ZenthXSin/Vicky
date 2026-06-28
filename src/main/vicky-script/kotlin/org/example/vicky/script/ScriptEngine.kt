package org.example.vicky.script

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
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
            injectExtend(ctx, scope)

            // 注入 callReceive —— 调用 Kotlin suspend fun receive(InboundMessage, ...) 的便捷包装
            injectCallReceive(ctx, scope)

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
            val dumpPath = dumpDebugJs(fileName, jsSource)
            if (dumpPath != null) {
                throw ScriptException("${e.message}\n  (compiled JS dumped to $dumpPath)", e)
            }
            throw e
        } catch (e: EcmaError) {
            Context.exit()
            val line = if (e.lineNumber() > 0) " (line ${e.lineNumber()})" else ""
            val ctxSnippet = snippetAround(jsSource, e.lineNumber())
            val dumpPath = dumpDebugJs(fileName, jsSource)
            val rhinoTrace = try { e.scriptStackTrace.takeIf { it.isNotBlank() }?.let { "\n  ---- Rhino stack ----\n$it" } ?: "" } catch (_: Exception) { "" }
            val javaCause = e.cause?.let { "\n  ---- caused by Java exception ----\n  ${it.javaClass.name}: ${it.message}" } ?: ""
            throw ScriptException("Runtime error in $fileName$line: ${e.name}: ${e.errorMessage}$ctxSnippet$rhinoTrace$javaCause${if (dumpPath != null) "\n  (compiled JS dumped to $dumpPath)" else ""}", e)
        } catch (e: EvaluatorException) {
            Context.exit()
            val line = if (e.lineNumber() > 0) " (line ${e.lineNumber()})" else ""
            val ctxSnippet = snippetAround(jsSource, e.lineNumber())
            val dumpPath = dumpDebugJs(fileName, jsSource)
            throw ScriptException("Evaluation error in $fileName$line: ${e.message}$ctxSnippet${if (dumpPath != null) "\n  (compiled JS dumped to $dumpPath)" else ""}", e)
        } catch (e: Exception) {
            Context.exit()
            dumpDebugJs(fileName, jsSource)
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

    /** 注入 println / print / console 到脚本 scope（Rhino 1.8 initStandardObjects 不含 shell 函数）。 */
    private fun injectPrintln(ctx: Context, scope: ScriptableObject) {
        val out = System.out
        val err = System.err
        fun joinArgs(args: Array<out Any?>): String =
            args.joinToString(" ") { if (it == Undefined.instance) "undefined" else Context.toString(it) }

        val printlnFn = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                out.println(joinArgs(args)); return Undefined.instance
            }
        }
        val printFn = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                out.print(joinArgs(args)); return Undefined.instance
            }
        }
        ScriptableObject.putProperty(scope, "println", printlnFn)
        ScriptableObject.putProperty(scope, "print", printFn)

        // console.{log,info,warn,error,debug} — TS 编译产物常用
        val consoleObj = ctx.newObject(scope)
        fun logFn(target: java.io.PrintStream, prefix: String = "") = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                target.println(prefix + joinArgs(args)); return Undefined.instance
            }
        }
        ScriptableObject.putProperty(consoleObj, "log", logFn(out))
        ScriptableObject.putProperty(consoleObj, "info", logFn(out))
        ScriptableObject.putProperty(consoleObj, "debug", logFn(out))
        ScriptableObject.putProperty(consoleObj, "warn", logFn(err))
        ScriptableObject.putProperty(consoleObj, "error", logFn(err))
        ScriptableObject.putProperty(scope, "console", consoleObj)
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

    /**
     * 注入 `extend(BaseClass, jsImpl, ...ctorArgs)` —— 用 Rhino 的 JavaAdapter 动态生成
     * 抽象类/接口的具体子类实例，把抽象方法路由到 jsImpl 上的同名 JS 函数。
     *
     * 用途：脚本里 `new MyAgent extends Agent {}` 这种 ES5 原型链继承没法真正继承 Java 抽象类，
     * 应改写为 `extend(Agent, { getContextManager: ..., getSink: ..., getAuthorizer: ... }, config, openAi)`。
     */
    private fun injectExtend(ctx: Context, scope: ScriptableObject) {
        val funcProto = ScriptableObject.getFunctionPrototype(scope)
        val fn = object : BaseFunction(scope, funcProto) {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.size < 2) throw ScriptException("extend(BaseClass, impl, [...ctorArgs]) requires at least 2 args")
                val baseCls = ClassAutoRegistry.extractClass(args[0])
                    ?: throw ScriptException("extend: first arg must be a Java class reference (got ${args[0]?.javaClass?.simpleName})")
                val impl = args[1] as? Scriptable
                    ?: throw ScriptException("extend: second arg must be a JS object (got ${args[1]?.javaClass?.simpleName})")
                val njc = org.mozilla.javascript.NativeJavaClass(s, baseCls)
                val adapterArgs: Array<Any?> = (listOf<Any?>(njc, impl) + args.drop(2)).toTypedArray()
                val raw = invokeJsCreateAdapter(cx, s, adapterArgs)
                // 包装返回值：拦截 suspend 方法调用，让 agent.receive(msg) 直接可用
                return wrapSuspendMethods(cx, s, raw)
            }
            override fun construct(cx: Context, s: Scriptable, args: Array<out Any?>): Scriptable {
                return call(cx, s, s, args) as? Scriptable
                    ?: throw ScriptException("extend: JavaAdapter did not return a Scriptable")
            }
        }
        ScriptableObject.putProperty(scope, "extend", fn)
    }

    /** 反射调用 Rhino 包私有的 JavaAdapter.js_createAdapter — 避免引入 Rhino 内部 API 的编译期依赖。 */
    private fun invokeJsCreateAdapter(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val cls = Class.forName("org.mozilla.javascript.JavaAdapter")
        val method = cls.getDeclaredMethod(
            "js_createAdapter",
            Context::class.java,
            Scriptable::class.java,
            Array<Any?>::class.java
        )
        method.isAccessible = true
        return try {
            method.invoke(null, cx, scope, args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    /**
     * 如果 raw 是 NativeJavaObject 且底层类有 suspend 方法（JVM 签名末参 Continuation），
     * 包一层 NativeJavaObjectProxy，让脚本直接 `agent.receive(msg)` 可用。
     */
    private fun wrapSuspendMethods(cx: Context, scope: Scriptable, raw: Any?): Any? {
        if (raw !is org.mozilla.javascript.NativeJavaObject) return raw
        val javaObj = raw.unwrap() ?: return raw
        val cls = javaObj.javaClass
        // 收集所有 suspend 方法：key=方法名, value=方法引用
        val suspendMethods = mutableMapOf<String, java.lang.reflect.Method>()
        for (m in cls.methods) {
            val params = m.parameterTypes
            if (params.isNotEmpty() && params.last() == kotlin.coroutines.Continuation::class.java) {
                suspendMethods.putIfAbsent(m.name, m)
            }
        }
        if (suspendMethods.isEmpty()) return raw
        return NativeJavaObjectProxy(scope, javaObj, suspendMethods)
    }

    /**
     * NativeJavaObject 子类：对 suspend 方法返回 JS 桥接函数（runBlocking 调用），
     * 其余属性/方法全部委托给 NativeJavaObject 原生的 Java 反射机制。
     * 继承 NativeJavaObject 保证 JavaAdapter 内部类型检查通过。
     */
    private class NativeJavaObjectProxy(
        scope: Scriptable,
        javaObj: Any,
        private val suspendMethods: Map<String, java.lang.reflect.Method>,
    ) : org.mozilla.javascript.NativeJavaObject(scope, javaObj, javaObj.javaClass) {

        override fun get(name: String, start: Scriptable): Any? {
            val method = suspendMethods[name]
            if (method != null) return createBridge(method)
            val accessor = super.get(name, start)
            // 方法调用器：包装返回值，使返回的 Java 对象若含 suspend 方法也能被代理
            if (accessor is org.mozilla.javascript.Function) {
                val proxy = this
                return object : BaseFunction(parentScope ?: proxy, ScriptableObject.getFunctionPrototype(parentScope ?: proxy)) {
                    override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                        val result = accessor.call(cx, s, thisObj, args)
                        return autoWrap(result)
                    }
                }
            }
            return accessor
        }

        private fun autoWrap(value: Any?): Any? {
            if (value !is org.mozilla.javascript.NativeJavaObject) return value
            val inner = value.unwrap() ?: return value
            val suspends = mutableMapOf<String, java.lang.reflect.Method>()
            for (m in inner.javaClass.methods) {
                val p = m.parameterTypes
                if (p.isNotEmpty() && p.last() == kotlin.coroutines.Continuation::class.java)
                    suspends.putIfAbsent(m.name, m)
            }
            return if (suspends.isNotEmpty()) NativeJavaObjectProxy(parentScope ?: this, inner, suspends) else value
        }

        override fun has(name: String, start: Scriptable): Boolean =
            suspendMethods.containsKey(name) || super.has(name, start)

        private fun createBridge(method: java.lang.reflect.Method): BaseFunction {
            val funcProto = ScriptableObject.getFunctionPrototype(parentScope ?: this)
            val declaringClass = method.declaringClass
            // suspend 方法：末参 Continuation，$default 签名 = (receiver, ...params, Continuation, int mask, DefaultConstructorMarker)
            // 普通方法：$default 签名 = (receiver, ...params, int mask, DefaultConstructorMarker)
            val isSuspend = method.parameterTypes.lastOrNull() == kotlin.coroutines.Continuation::class.java
            val paramCountNoCont = if (isSuspend) method.parameterCount - 1 else method.parameterCount
            val defaultMaskCount = (paramCountNoCont + 31) / 32
            val expectedDefaultParamCount = paramCountNoCont + (if (isSuspend) 1 else 0) + defaultMaskCount + 1
            val defaultMethod = declaringClass.methods.firstOrNull {
                it.name == "${method.name}\$default" &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == expectedDefaultParamCount
            }

            return object : BaseFunction(parentScope ?: this, funcProto) {
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                    return try {
                        fun unwrap(v: Any?): Any? = if (v is org.mozilla.javascript.Wrapper) v.unwrap() else v
                        val realObj = unwrap(this@NativeJavaObjectProxy) ?: this@NativeJavaObjectProxy
                        val userArgs = args.map { unwrap(it) }.toMutableList()

                        runBlocking {
                            val deferred = kotlinx.coroutines.CompletableDeferred<Any?>()
                            val bridgeCont = object : kotlin.coroutines.Continuation<Any?> {
                                override val context: kotlin.coroutines.CoroutineContext =
                                    kotlin.coroutines.EmptyCoroutineContext
                                override fun resumeWith(result: Result<Any?>) {
                                    result.fold(
                                        onSuccess = { deferred.complete(it) },
                                        onFailure = { deferred.completeExceptionally(it) }
                                    )
                                }
                            }
                            val invokeResult = if (defaultMethod != null && userArgs.size < paramCountNoCont) {
                                val mask = computeDefaultMask(userArgs.size, paramCountNoCont)
                                val values = buildDefaultArgs(userArgs, paramCountNoCont, method.parameterTypes, bridgeCont, isSuspend, defaultMaskCount, mask)
                                defaultMethod.invoke(null, *values)
                            } else {
                                val realArgs = userArgs.toMutableList()
                                if (isSuspend) {
                                    while (realArgs.size < paramCountNoCont) realArgs.add(defaultPrimitive(method.parameterTypes[realArgs.size]))
                                    realArgs.add(bridgeCont)
                                }
                                method.invoke(realObj, *realArgs.toTypedArray())
                            }
                            if (invokeResult !== COROUTINE_SUSPENDED) deferred.complete(invokeResult)
                            deferred.await()
                        }
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw ScriptException("${method.name} failed: ${e.cause?.message ?: e.message}", e.cause ?: e)
                    } catch (e: ScriptException) {
                        throw e
                    } catch (e: Exception) {
                        throw ScriptException("${method.name} failed: ${e.message}", e)
                    }
                }
            }
        }

        /** 构建 $default 方法参数：[receiver, ...userValues, ...defaults, (continuation?), ...masks, null] */
        private fun buildDefaultArgs(
            userArgs: List<Any?>,
            paramCount: Int,
            paramTypes: Array<Class<*>>,
            cont: kotlin.coroutines.Continuation<Any?>,
            isSuspend: Boolean,
            maskCount: Int,
            mask: IntArray,
        ): Array<Any?> {
            val values = arrayOfNulls<Any?>(paramCount)
            for (i in 0 until paramCount) {
                values[i] = if (i < userArgs.size) userArgs[i] else defaultPrimitive(paramTypes[i])
            }
            val result = mutableListOf<Any?>()
            result.add(null) // receiver（$default 是 static 方法，第一个参数是 receiver）
            result.addAll(values)
            if (isSuspend) result.add(cont)
            for (m in mask) result.add(m)
            result.add(null) // DefaultConstructorMarker
            return result.toTypedArray()
        }

        /** 计算 bitmask：已提供的参数位 = 0，未提供的参数位 = 1（使用默认值） */
        private fun computeDefaultMask(provided: Int, total: Int): IntArray {
            val maskCount = (total + 31) / 32
            val masks = IntArray(maskCount)
            for (i in provided until total) {
                masks[i / 32] = masks[i / 32] or (1 shl (i % 32))
            }
            return masks
        }

        /** 原始类型的默认值（避免 null 传给 boolean/int 等导致 NPE） */
        private fun defaultPrimitive(t: Class<*>): Any? = when (t) {
            Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
            Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
            Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
            Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> 0.toByte()
            Short::class.javaPrimitiveType, java.lang.Short::class.java -> 0.toShort()
            Char::class.javaPrimitiveType, java.lang.Character::class.java -> ' '
            else -> null
        }
    }

    /**
     * 注入 `callReceive(agent, msg, replySink?, clearContextAfter?)` —— 调用 Kotlin suspend fun receive。
     * suspend fun 在 JVM 编译后多出 Continuation 参数，JS 直接调用会找不到方法。
     * 此包装通过反射调用 suspend 方法，用 runBlocking + CompletableDeferred 桥接协程。
     */
    private fun injectCallReceive(ctx: Context, scope: ScriptableObject) {
        val funcProto = ScriptableObject.getFunctionPrototype(scope)
        val fn = object : BaseFunction(scope, funcProto) {
            override fun call(cx: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                if (args.size < 2) throw ScriptException("callReceive(agent, msg, [replySink], [clearContextAfter]) requires at least 2 args")
                fun unwrap(v: Any?): Any? = if (v is org.mozilla.javascript.Wrapper) v.unwrap() else v
                val agent = unwrap(args[0]) ?: throw ScriptException("callReceive: agent is null")
                val msg = unwrap(args[1]) ?: throw ScriptException("callReceive: msg is null")
                val replySink = if (args.size > 2 && args[2] != Undefined.instance) unwrap(args[2]) else null
                val clearContext = if (args.size > 3 && args[3] != Undefined.instance) Context.toBoolean(args[3]) else false

                return try {
                    val cls = agent.javaClass
                    val receiveMethod = cls.methods.firstOrNull {
                        it.name == "receive" && it.parameterCount == 4
                    } ?: throw ScriptException("callReceive: cannot find suspend receive method on ${cls.simpleName}")

                    runBlocking {
                        val deferred = kotlinx.coroutines.CompletableDeferred<Any?>()
                        val bridgeCont = object : kotlin.coroutines.Continuation<Any?> {
                            override val context: kotlin.coroutines.CoroutineContext =
                                kotlin.coroutines.EmptyCoroutineContext
                            override fun resumeWith(result: Result<Any?>) {
                                result.fold(
                                    onSuccess = { deferred.complete(it) },
                                    onFailure = { deferred.completeExceptionally(it) }
                                )
                            }
                        }
                        val invokeResult = receiveMethod.invoke(agent, msg, replySink, clearContext, bridgeCont)
                        if (invokeResult !== COROUTINE_SUSPENDED) deferred.complete(invokeResult)
                        deferred.await()
                    }
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw ScriptException("callReceive failed: ${e.cause?.message ?: e.message}", e.cause ?: e)
                } catch (e: ScriptException) {
                    throw e
                } catch (e: Exception) {
                    throw ScriptException("callReceive failed: ${e.message}", e)
                }
            }
        }
        ScriptableObject.putProperty(scope, "callReceive", fn)
    }

    /** Dump 编译后 JS 到 config/scripts/.debug/，方便对照行号定位运行时错误。 */
    private fun dumpDebugJs(fileName: String, jsSource: String): String? = try {
        val dir = java.io.File("config/scripts/.debug").apply { mkdirs() }
        val out = java.io.File(dir, fileName.removeSuffix(".ts") + ".js")
        out.writeText(jsSource, Charsets.UTF_8)
        out.absolutePath
    } catch (_: Exception) { null }

    /** 返回错误行附近的代码片段。errorLine 已包含 wrapper 偏移（wrapper 第 1 行是 `var __script_result = (function() {`）。 */
    private fun snippetAround(jsSource: String, errorLine: Int): String {
        if (errorLine <= 1) return ""
        val lines = jsSource.split('\n')
        // wrapper 在 jsSource 前面包了 1 行，所以 jsSource 行号 = errorLine - 1
        val jsLine = errorLine - 1
        if (jsLine < 1 || jsLine > lines.size) return ""
        val from = (jsLine - 2).coerceAtLeast(1)
        val to = (jsLine + 2).coerceAtMost(lines.size)
        return buildString {
            append("\n  ---- compiled JS around line ", jsLine, " ----")
            for (i in from..to) {
                val marker = if (i == jsLine) ">> " else "   "
                append("\n  ", marker, i, ": ", lines[i - 1])
            }
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

        init {
            if (!org.mozilla.javascript.ContextFactory.hasExplicitGlobal()) {
                org.mozilla.javascript.ContextFactory.initGlobal(object : org.mozilla.javascript.ContextFactory() {
                    private val wf = object : org.mozilla.javascript.WrapFactory() {
                        override fun wrap(cx: Context, scope: Scriptable, obj: Any?, staticType: Class<*>?) =
                            if (obj is String) obj else super.wrap(cx, scope, obj, staticType)
                    }
                    override fun makeContext() = super.makeContext().also { it.wrapFactory = wf }
                })
            }
        }

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
