package org.example.vicky.tool

/** 工具调用权限校验。返回 false 时框架会直接给 agent 一条 "permission denied"。 */
fun interface ToolAuthorizer {
    fun allow(userId: String, toolName: String): Boolean

    companion object {
        val ALLOW_ALL: ToolAuthorizer = ToolAuthorizer { _, _ -> true }
    }
}
