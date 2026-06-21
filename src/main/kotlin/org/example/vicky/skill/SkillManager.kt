package org.example.vicky.skill

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局 Skill 管理单例。
 *
 * 启动时 [load] 一次，扫描 `<rootDir>/<skill-name>/SKILL.md`。
 * 之后 enable/disable/delete 等管理操作通过本对象的方法直接生效（线程安全）。
 *
 * 与 [org.example.vicky.config.ConfigData.skillStates] 配合：
 * - [load] 时把 `skillStates` 注入每个 Skill 的 enabled。
 * - 调用方负责把 [getStates] 写回 config.json（参考 Agent 里 `persistToolStates` 模式）。
 */
object SkillManager {
    private val byName = ConcurrentHashMap<String, Skill>()
    @Volatile private var rootDir: File? = null

    /** 加载根目录下所有 skill。重复调用等价于 [reload]。 */
    fun load(rootDir: File, persistedStates: Map<String, Boolean> = emptyMap()) {
        this.rootDir = rootDir
        if (!rootDir.exists()) rootDir.mkdirs()
        byName.clear()
        scan(rootDir, persistedStates)
    }

    /** 重新扫描根目录。保留禁用状态（按当前 enabled 字段）。 */
    fun reload() {
        val root = rootDir ?: return
        val states = byName.mapValues { it.value.enabled }
        byName.clear()
        scan(root, states)
    }

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

    /** 物理删除 skill 目录。删除前要求 skill 必须存在。 */
    fun delete(name: String): Boolean {
        val skill = byName.remove(name) ?: return false
        return runCatching { skill.dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 当前所有 skill 的启用状态快照（用于持久化到 config.json）。 */
    fun getStates(): Map<String, Boolean> =
        byName.values.associate { it.name to it.enabled }

    /** 根目录路径（可能为 null，如果还没 load 过）。 */
    fun rootDir(): File? = rootDir

    private fun scan(root: File, persistedStates: Map<String, Boolean>) {
        val children = root.listFiles { f -> f.isDirectory } ?: return
        for (dir in children) {
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.isFile) continue
            val text = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrElse {
                println("[Vicky][skill] 读取失败: ${skillFile.absolutePath}: ${it.message}")
                continue
            }
            val parsed = SkillFrontmatterParser.parse(text)
            val name = parsed.meta["name"]?.trim()
            val description = parsed.meta["description"]?.trim()
            if (name.isNullOrBlank() || description.isNullOrBlank()) {
                println("[Vicky][skill] 跳过 ${dir.name}: SKILL.md 缺 name/description")
                continue
            }
            val enabled = persistedStates[name] ?: true
            byName[name] = Skill(name, description, parsed.body, dir, enabled)
        }
        val activeCount = byName.values.count { it.enabled }
        val disabledCount = byName.size - activeCount
        if (byName.isNotEmpty()) {
            println("[Vicky][skill] 加载完成: $activeCount 启用 / $disabledCount 禁用 (共 ${byName.size})")
        }
    }
}
