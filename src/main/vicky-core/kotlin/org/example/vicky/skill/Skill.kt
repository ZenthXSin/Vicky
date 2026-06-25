package org.example.vicky.skill

import java.io.File

/**
 * 一个 skill = 一个目录 + SKILL.md。
 * - [name] / [description] 来自 frontmatter，必填。
 * - [body] 是 SKILL.md 中 frontmatter 之后的全部内容（给 LLM 看的操作指南）。
 * - [dir] skill 所在目录，删除时整目录递归删。
 * - [enabled] 软禁用状态，由 [org.example.vicky.config.ConfigData.skillStates] 持久化。
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val dir: File,
    val enabled: Boolean,
)
