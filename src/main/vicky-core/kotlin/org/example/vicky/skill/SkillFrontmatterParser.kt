package org.example.vicky.skill

/**
 * SKILL.md frontmatter 解析器。
 *
 * 支持格式：
 * ```
 * ---
 * name: my-skill
 * description: do something useful
 * ---
 * 正文 ...
 * ```
 *
 * 仅识别简单的 `key: value` 行（不支持嵌套、列表、引号转义）。
 * 缺失 frontmatter 时整段文本视作 body，meta 为空。
 */
object SkillFrontmatterParser {
    data class Parsed(val meta: Map<String, String>, val body: String)

    private val DELIM = "---"

    fun parse(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n").trimStart('﻿')
        val lines = normalized.split("\n")
        if (lines.isEmpty() || lines.first().trim() != DELIM) {
            return Parsed(emptyMap(), normalized)
        }
        val endIdx = (1 until lines.size).firstOrNull { lines[it].trim() == DELIM }
            ?: return Parsed(emptyMap(), normalized)
        val meta = mutableMapOf<String, String>()
        for (i in 1 until endIdx) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim().trim('"', '\'')
            if (key.isNotEmpty()) meta[key] = value
        }
        val body = lines.drop(endIdx + 1).joinToString("\n").trimStart('\n')
        return Parsed(meta, body)
    }
}
