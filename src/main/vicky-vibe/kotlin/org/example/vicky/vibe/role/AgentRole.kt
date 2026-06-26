package org.example.vicky.vibe.role

enum class AgentRole(val id: String, val label: String) {
    GENERAL("general", "综合"),
    PLANNING("planning", "规划"),
    INVESTIGATION("investigation", "调查"),
    WRITING("writing", "编写"),
    REVIEW("review", "复查");

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): AgentRole? = byId[id]
    }
}
