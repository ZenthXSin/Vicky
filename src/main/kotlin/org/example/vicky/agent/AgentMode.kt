package org.example.vicky.agent

/**
 * 处理模式。一个模式控制三件事：
 * - [toolsEnabled]：是否把工具列表拼进 system prompt 并传给模型。
 * - [emitAgentText]：是否把 agent 的纯文本回复发给 user。
 * - [instructions]：注入 system prompt「# Output rules」段的正文。
 * - [clearAfterReply]：给出最终回复 (不再调用工具) 后是否自动清除会话上下文，实现「每次请求无状态」。
 *
 * 像自定义工具（继承 [org.example.vicky.tool.Tool]）一样，继承 [AgentMode] 即可定义自己的模式：
 * ```
 * object MyMode : AgentMode() {
 *     override val name = "MY"
 *     override val toolsEnabled = true
 *     override val emitAgentText = false
 *     override val instructions = "..."
 * }
 * ```
 */
abstract class AgentMode {
    abstract val name: String
    abstract val toolsEnabled: Boolean
    abstract val emitAgentText: Boolean
    abstract val instructions: String

    /** 给出最终回复后自动清空上下文 (默认关闭)。 */
    open val clearAfterReply: Boolean = false

    companion object {
        /** 模式一：有工具；agent 文本**不**发给 user，只有工具 userReply 会发。 */
        val SILENT: AgentMode = object : AgentMode() {
            override val name = "SILENT"
            override val toolsEnabled = true
            override val emitAgentText = false
            override val instructions =
                "Your plain-text replies are NOT shown to the user; they are only used for your own " +
                    "reasoning. The ONLY way to send something to the user is by calling a tool whose " +
                    "result carries a user-facing reply. If a tool matches the request, call it. " +
                    "If nothing applies, simply reply normally — it will be silently discarded."
        }

        /** 模式二：有工具；除了工具 userReply，agent 文本也发给 user。 */
        val VERBOSE: AgentMode = object : AgentMode() {
            override val name = "VERBOSE"
            override val toolsEnabled = true
            override val emitAgentText = true
            override val instructions =
                "Your plain-text replies ARE shown to the user. You may answer the user directly in text, " +
                    "or call a tool when one matches the request. Tool results may additionally push their " +
                    "own message to the user."
        }

        /** 模式三：纯聊天；不传工具；agent 文本直接发给 user。 */
        val CHAT: AgentMode = object : AgentMode() {
            override val name = "CHAT"
            override val toolsEnabled = false
            override val emitAgentText = true
            override val instructions =
                "You are in a plain chat. Reply to the user directly in text. No tools are available."
        }

        /** 模式四：有工具且 agent 文本发给 user；每次请求结束后自动清空上下文 (无状态)。 */
        val ONESHOT: AgentMode = object : AgentMode() {
            override val name = "ONESHOT"
            override val toolsEnabled = true
            override val emitAgentText = true
            override val clearAfterReply = true
            override val instructions =
                "You may call tools as needed. Once you produce your final answer (no further tool calls), " +
                    "treat this exchange as self-contained: the conversation context is cleared afterward, " +
                    "so do not rely on remembering anything from this turn in the next one."
        }
    }
}
