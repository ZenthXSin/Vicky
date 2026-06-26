package org.example.vicky.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.Context
import org.mozilla.javascript.EcmaError
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Rhino JS 引擎封装：负责 TS→JS 编译、JS 执行、导出提取。
 */
class ScriptEngine {
    private val json = Json { isLenient = true }

    /**
     * 编译 TypeScript → JavaScript。
     */
    fun compileTs(tsSource: String, fileName: String, options: TsCompilerOptions = TsCompilerOptions()): String {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1 // 解释模式，兼容所有平台
            val scope = ctx.initStandardObjects()

            val tscCode = loadTypeScriptCompiler()
            ctx.evaluateString(scope, tscCode, "typescript.js", 1, null)

            val escaped = escapeForTemplateLiteral(tsSource)
            val result = ctx.evaluateString(
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
            return Context.toString(result)
        } finally {
            Context.exit()
        }
    }

    /**
     * 执行编译后的 JS 代码，提取导出的 Tool 定义。
     */
    fun executeScript(jsSource: String, fileName: String): ScriptExports {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ClassAutoRegistry.injectAll(ctx, scope)

            // 注入 Promise polyfill（Rhino 原生不支持 Promise，TS async 编译产物需要它）
            ctx.evaluateString(scope, PROMISE_POLYFILL, "promise-polyfill", 1, null)

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
                ?: throw ScriptException("Script must export 'name': $fileName")
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

            if (executeFn == null && onLoadFn == null) {
                throw ScriptException("Script must export at least 'execute' or 'onLoad': $fileName")
            }

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
        val ctx = exports.rhinoContext ?: throw ScriptException("Script context not available")

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
                            intVal != null -> ScriptableObject.putProperty(jsArgs, key, intVal)
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

    fun releaseExports(exports: ScriptExports) {
        // Rhino 不需要显式释放
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
