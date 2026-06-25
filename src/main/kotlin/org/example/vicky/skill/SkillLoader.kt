package org.example.vicky.skill

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 从文件系统加载 skill 并注册到 [SkillManager]。
 *
 * 目录结构：`<rootDir>/<skill-name>/SKILL.md`
 */
object SkillLoader {
    private var rootDir: File? = null
    private val dirByName = ConcurrentHashMap<String, File>()

    /** 扫描 rootDir 下所有 SKILL.md，注册到 [SkillManager]。 */
    fun load(rootDir: File, persistedStates: Map<String, Boolean> = emptyMap()) {
        this.rootDir = rootDir
        if (!rootDir.exists()) rootDir.mkdirs()
        dirByName.clear()
        SkillManager.clear()
        scan(rootDir, persistedStates)
    }

    /** 重新扫描。保留禁用状态。 */
    fun reload() {
        val root = rootDir ?: return
        val states = SkillManager.getStates()
        dirByName.clear()
        SkillManager.clear()
        scan(root, states)
    }

    /** 物理删除 skill 目录并从 [SkillManager] 注销。 */
    fun delete(name: String): Boolean {
        val dir = dirByName.remove(name) ?: return false
        SkillManager.unregister(name)
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 根目录路径。 */
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
            dirByName[name] = dir
            SkillManager.register(Skill(name, description, parsed.body, enabled))
        }
        val activeCount = SkillManager.all().size
        val totalCount = SkillManager.allIncludingDisabled().size
        val disabledCount = totalCount - activeCount
        if (totalCount > 0) {
            println("[Vicky][skill] 加载完成: $activeCount 启用 / $disabledCount 禁用 (共 $totalCount)")
        }
    }
}
