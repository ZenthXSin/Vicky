package org.example.vicky.skill

import java.util.concurrent.ConcurrentHashMap

/**
 * 全局 Skill 管理单例（动态注册）。
 *
 * 外部（如文件加载器）通过 [register] 注入 skill，之后 enable/disable 等管理操作通过本对象直接生效。
 *
 * 与 [org.example.vicky.config.ConfigData.skillStates] 配合：
 * - 注册时把 `skillStates` 注入每个 Skill 的 enabled。
 * - 调用方负责把 [getStates] 写回 config.json。
 */
object SkillManager {
    private val byName = ConcurrentHashMap<String, Skill>()

    /** 注册一个 skill。同名覆盖。 */
    fun register(skill: Skill) {
        byName[skill.name] = skill
    }

    /** 批量注册。 */
    fun registerAll(skills: Collection<Skill>) {
        for (s in skills) byName[s.name] = s
    }

    /** 注销一个 skill。返回是否存在。 */
    fun unregister(name: String): Boolean = byName.remove(name) != null

    /** 仅返回启用的 skill。 */
    fun all(): List<Skill> = byName.values.filter { it.enabled }.sortedBy { it.name }

    /** 含禁用的全部 skill。 */
    fun allIncludingDisabled(): List<Skill> = byName.values.sortedBy { it.name }

    /** 按名查找，仅返回启用的。禁用或不存在均返回 null。 */
    fun get(name: String): Skill? = byName[name]?.takeIf { it.enabled }

    /** 不论启用与否按名查找。 */
    fun find(name: String): Skill? = byName[name]

    /** 切换启用状态。返回新状态；skill 不存在返回 null。 */
    fun setEnabled(name: String, enabled: Boolean): Boolean? {
        val current = byName[name] ?: return null
        byName[name] = current.copy(enabled = enabled)
        return enabled
    }

    /** 当前所有 skill 的启用状态快照（用于持久化到 config.json）。 */
    fun getStates(): Map<String, Boolean> =
        byName.values.associate { it.name to it.enabled }

    /** 清空所有 skill。 */
    fun clear() {
        byName.clear()
    }
}
