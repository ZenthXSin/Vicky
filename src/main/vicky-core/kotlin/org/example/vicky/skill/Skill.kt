package org.example.vicky.skill

/**
 * 技能数据。
 * - [name] / [description] 必填。
 * - [body] 给 LLM 看的操作指南。
 * - [enabled] 软禁用状态。
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val group: String = "",
    val enabled: Boolean = true,
)
