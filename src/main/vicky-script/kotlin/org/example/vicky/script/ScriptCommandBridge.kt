package org.example.vicky.script

import org.example.vicky.command.Command
import org.example.vicky.command.CommandContext
import org.example.vicky.command.CommandResult
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * 将 JS `commands` 数组元素包装为 Kotlin [Command]。
 *
 * 脚本端格式：
 * ```ts
 * export const commands = [{
 *   name: "myCmd",
 *   description: "...",
 *   adminOnly: false,
 *   execute: (ctx, args) => "reply text"
 * }]
 * ```
 */
class ScriptCommandBridge(
    private val exports: ScriptExports,
    cmdDef: Any?,
) : Command() {

    private val cmdObj = cmdDef as? NativeObject
        ?: throw ScriptException("commands[] element must be an object")

    override val name: String = run {
        val v = ScriptableObject.getProperty(cmdObj, "name")
        if (v == null || v == Undefined.instance) throw ScriptException("command must have a name")
        Context.toString(v)
    }

    override val description: String = run {
        val v = ScriptableObject.getProperty(cmdObj, "description")
        if (v == null || v == Undefined.instance) "" else Context.toString(v)
    }

    override val adminOnly: Boolean = run {
        val v = ScriptableObject.getProperty(cmdObj, "adminOnly")
        if (v == null || v == Undefined.instance) false else Context.toBoolean(v)
    }

    private val executeFn: Function? = run {
        val v = ScriptableObject.getProperty(cmdObj, "execute")
        if (v != null && v != Undefined.instance && v is Function) v else null
    }

    override suspend fun execute(ctx: CommandContext, args: String): CommandResult {
        val fn = executeFn ?: return CommandResult(reply = "Command '$name' has no execute function.")
        val scope = exports.rhinoScope ?: return CommandResult(reply = "Script scope unavailable.")

        return try {
            val rhinoCtx = Context.enter()
            val reply = try {
                val jsCtx = rhinoCtx.newObject(scope) as NativeObject
                ScriptableObject.putProperty(jsCtx, "userId", ctx.userId)
                ScriptableObject.putProperty(jsCtx, "conversationId", ctx.conversationId)
                ScriptableObject.putProperty(jsCtx, "groupId", ctx.groupId)
                ScriptableObject.putProperty(jsCtx, "isAdmin", ctx.isAdmin)
                val result = fn.call(rhinoCtx, scope, scope, arrayOf(jsCtx, args))
                if (result == null || result == Undefined.instance) null else Context.toString(result)
            } finally {
                Context.exit()
            }
            CommandResult(reply = reply)
        } catch (e: Exception) {
            CommandResult(reply = "Script command '$name' error: ${e.message}")
        }
    }
}
