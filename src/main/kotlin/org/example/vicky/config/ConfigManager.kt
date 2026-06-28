package org.example.vicky.config

import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.example.vicky.agent.AgentConfig
import org.example.vicky.agent.AgentMode
import org.example.vicky.agent.EmbeddingConfig
import org.example.vicky.channel.onebot.MemoryConfig
import java.io.File

@Serializable
data class ConfigData(
    val model: String = "deepseek-v4-flash",
    val apiKey: String = "",
    val baseUrl: String? = null,
    val maxSteps: Int = 8,
    val maxMemoryRounds: Int = 50,
    val maxContextLength: Int = 0,
    val mode: String = "SILENT",
    val temperature: Double? = null,
    val agentMd: String = "AGENT.md",
    val debug: Boolean = false,
    val think: Boolean = false,
    val streaming: Boolean = true,
    val builtinTools: Boolean = true,
    val toolStates: Map<String, Boolean> = emptyMap(),
    val skillStates: Map<String, Boolean> = emptyMap(),
    val embedding: EmbeddingConfigData = EmbeddingConfigData(),
    val oneBot: OneBotConfigData = OneBotConfigData(),
    val qdrant: QdrantConfigData = QdrantConfigData(),
    val vectorStore: VectorStoreConfigData = VectorStoreConfigData(),
    val memory: MemoryConfigData = MemoryConfigData(),
)

@Serializable
data class QdrantConfigData(
    val host: String = "localhost",
    val grpcPort: Int = 6334,
    val httpPort: Int = 6333,
    val enabled: Boolean = false,
)

@Serializable
data class VectorStoreConfigData(
    val type: String = "jvector",  // "jvector" | "qdrant"
    val dataDir: String = "data/vector",
)

@Serializable
data class MemoryConfigData(
    val enabled: Boolean = false,
    val topK: Int = 5,
    val tokenBudget: Int = 800,
    val maxPerUser: Int = 500,
    val expiryDays: Int = 90,
    val rawRetentionDays: Int = 30,
    val distilledRetentionDays: Int = 7,
    val collection: String = "vicky_memories",
    val rawCollection: String = "vicky_memories_raw",
    val distillationEnabled: Boolean = true,
    val distillationSchedule: String = "0 2 * * *",
    val distillationMaxConversations: Int = 10,
    val distillationTemperature: Double = 0.1,
    val distillationMaxTokens: Int = 1000,
    val fileIndexEnabled: Boolean = false,
    val fileIndexCollection: String = "vicky_files",
    val fileIndexChunkSize: Int = 500,
    val fileIndexChunkOverlap: Int = 50,
    val fileIndexIgnorePatterns: List<String> = listOf(".git", ".gradle", "build", "node_modules", "config/tmp"),
    val fileIndexPaths: List<String> = emptyList(),
    val fileIndexAutoIndexOnStart: Boolean = true,
    val conversationStoreMaxConversations: Int = 500,
    val conversationStoreMaxMessages: Int = 200,
    val messageBufferMaxGlobalEntries: Int = 10000,
    val messageBufferRawTruncate: Int = 500,
)

/**
 * 语义模型配置。启用后使用 OpenAI 协议兼容端点。
 * `enabled = false` 时整段忽略，AgentConfig.embedding 为 null。
 */
@Serializable
data class EmbeddingConfigData(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
)

@Serializable
data class OneBotConfigData(
    val url: String = "ws://127.0.0.1:3001",
    val token: String = "",
    val adminList: List<String> = listOf("488254306", "2703872748"),
    val groupWhitelist: List<String> = emptyList(),
    val userWhitelist: List<String> = emptyList(),
)

object ConfigManager {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getConfigDir(): File {
        val jarFile = runCatching {
            ConfigManager::class.java.protectionDomain.codeSource?.location?.toURI()?.let { File(it) }
        }.getOrNull()
        val baseDir = if (jarFile != null && jarFile.isFile && jarFile.name.endsWith(".jar")) {
            jarFile.parentFile
        } else {
            File(System.getProperty("user.dir"))
        }
        return File(baseDir, "config")
    }

    data class LoadResult(
        val config: ConfigData,
        val agentMd: String,
        val firstRun: Boolean,
    )

    fun loadOrCreate(): LoadResult {
        val configDir = getConfigDir()
        val configFile = File(configDir, "config.json")

        // 每次启动都同步内置资源（scripts/skills）
        syncBuiltinResources(configDir)

        if (!configFile.exists()) {
            val (config, md) = generateDefaults(configDir)
            return LoadResult(config, md, firstRun = true)
        }

        val rawText = configFile.readText(Charsets.UTF_8)
        val rawJson = json.parseToJsonElement(rawText).jsonObject
        val rawConfig = json.decodeFromJsonElement<ConfigData>(rawJson)
        val configData = migrate(rawConfig)

        val canonicalJson = json.encodeToJsonElement(ConfigData.serializer(), configData).jsonObject
        val diff = detectDiff(rawJson, canonicalJson)
        if (diff.hasChanges || configData !== rawConfig) {
            if (diff.hasChanges) printDiff(diff)
            save(configData)
        }

        val agentMdFile = File(configDir, configData.agentMd)
        val agentMdContent = if (agentMdFile.exists()) {
            agentMdFile.readText(Charsets.UTF_8)
        } else {
            getDefaultAgentMd()
        }

        // 检查关键配置是否已填写
        if (configData.apiKey.isBlank()) {
            println("[Vicky] 警告: apiKey 未配置，请编辑 config.json 填写 API Key")
        }
        if (configData.oneBot.token.isBlank()) {
            println("[Vicky] 警告: oneBot.token 未配置，请编辑 config.json 填写 OneBot Token")
        }

        return LoadResult(configData, agentMdContent, firstRun = false)
    }

    private data class ConfigDiff(
        val added: List<Pair<String, JsonElement>>,
        val removed: List<String>,
    ) {
        val hasChanges get() = added.isNotEmpty() || removed.isNotEmpty()
    }

    private fun detectDiff(rawJson: JsonObject, canonicalJson: JsonObject, prefix: String = ""): ConfigDiff {
        val added = mutableListOf<Pair<String, JsonElement>>()
        val removed = mutableListOf<String>()
        for ((key, value) in canonicalJson) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                key !in rawJson -> added.add(path to value)
                value is JsonObject && rawJson[key] is JsonObject -> {
                    val nested = detectDiff(rawJson[key]!!.jsonObject, value, path)
                    added += nested.added
                    removed += nested.removed
                }
            }
        }
        for (key in rawJson.keys) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (key !in canonicalJson) removed.add(path)
        }
        return ConfigDiff(added, removed)
    }

    private fun printDiff(diff: ConfigDiff) {
        println("[Vicky] 配置文件已自动更新，差异如下：")
        if (diff.added.isNotEmpty()) {
            println("  新增字段（已补全默认值）：")
            diff.added.forEach { (path, value) -> println("    + $path = $value") }
        }
        if (diff.removed.isNotEmpty()) {
            println("  废弃字段（已从配置中移除）：")
            diff.removed.forEach { path -> println("    - $path") }
        }
        println("[Vicky] 更新后的配置已自动写入 config.json")
    }

    /** 老配置幂等迁移：保证关键字段不缺。 */
    private fun migrate(config: ConfigData): ConfigData {        val mem = config.memory
        val needTmpIgnore = "config/tmp" !in mem.fileIndexIgnorePatterns
        if (!needTmpIgnore) return config
        return config.copy(
            memory = mem.copy(
                fileIndexIgnorePatterns = mem.fileIndexIgnorePatterns + "config/tmp",
            ),
        )
    }

    fun save(config: ConfigData) {
        val configFile = File(getConfigDir(), "config.json")
        configFile.writeText(
            json.encodeToString(ConfigData.serializer(), config),
            Charsets.UTF_8,
        )
    }

    private fun syncBuiltinResources(configDir: File) {
        val scriptsDir = File(configDir, "scripts")
        scriptsDir.mkdirs()
        syncResource("scripts/hello.ts", File(scriptsDir, "hello.ts"))

        val skillsDir = File(configDir, "skills")
        skillsDir.mkdirs()
        syncResource("skills/script-writing/SKILL.md", File(skillsDir, "script-writing/SKILL.md"))
        syncResource("skills/vibe-usage/SKILL.md", File(skillsDir, "vibe-usage/SKILL.md"))
    }

    private fun generateDefaults(configDir: File): Pair<ConfigData, String> {
        configDir.mkdirs()

        val agentMdContent = getDefaultAgentMd()
        val agentMdFileName = deriveFileName(agentMdContent)

        File(configDir, agentMdFileName).writeText(agentMdContent, Charsets.UTF_8)

        val configData = ConfigData(
            model = "deepseek-v4-flash",
            apiKey = "",
            baseUrl = "",
            maxSteps = 15,
            maxMemoryRounds = 10,
            maxContextLength = 16000,
            mode = "VERBOSE",
            temperature = null,
            agentMd = agentMdFileName,
            debug = false,
            think = true,
            builtinTools = false,
            oneBot = OneBotConfigData(
                url = "ws://127.0.0.1:3001",
                token = "",
                adminList = listOf("488254306"),
            ),
        )

        File(configDir, "config.json").writeText(
            json.encodeToString(ConfigData.serializer(), configData),
            Charsets.UTF_8,
        )

        return Pair(configData, agentMdContent)
    }

    private fun syncResource(resourcePath: String, target: File) {
        target.parentFile?.mkdirs()
        val stream = ConfigManager::class.java.classLoader?.getResourceAsStream(resourcePath) ?: return
        val resourceBytes = stream.use { it.readBytes() }
        if (target.exists() && target.readBytes().contentEquals(resourceBytes)) return
        val action = if (target.exists()) "更新" else "写入"
        target.writeBytes(resourceBytes)
        println("[Vicky][config] 资源${action}: ${target.name} (${resourceBytes.size} bytes)")
    }

    fun toAgentConfig(configData: ConfigData, agentMdContent: String): AgentConfig {
        val mode = when (configData.mode.uppercase()) {
            "VERBOSE" -> AgentMode.VERBOSE
            "CHAT" -> AgentMode.CHAT
            else -> AgentMode.SILENT
        }
        val memory = configData.memory
        return AgentConfig(
            model = ModelId(configData.model),
            apiKey = configData.apiKey,
            baseUrl = configData.baseUrl,
            maxSteps = configData.maxSteps,
            maxMemoryRounds = configData.maxMemoryRounds,
            maxContextLength = configData.maxContextLength,
            mode = mode,
            temperature = configData.temperature,
            agentMd = agentMdContent,
            debug = configData.debug,
            think = configData.think,
            streaming = configData.streaming,
            builtinTools = configData.builtinTools,
            toolStates = configData.toolStates,
            conversationStoreMaxConversations = memory.conversationStoreMaxConversations,
            conversationStoreMaxMessages = memory.conversationStoreMaxMessages,
            messageBufferMaxGlobalEntries = memory.messageBufferMaxGlobalEntries,
            messageBufferRawTruncate = memory.messageBufferRawTruncate,
        )
    }

    fun toMemoryConfig(configData: ConfigData): MemoryConfig {
        val memory = configData.memory
        val qdrant = configData.qdrant
        val vs = configData.vectorStore
        return MemoryConfig(
            embedding = toEmbeddingConfig(configData.embedding),
            vectorStoreType = vs.type,
            vectorStoreDataDir = vs.dataDir,
            qdrantHost = if (qdrant.enabled) qdrant.host else null,
            qdrantGrpcPort = qdrant.grpcPort,
            qdrantHttpPort = qdrant.httpPort,
            memoryEnabled = memory.enabled,
            memoryTopK = memory.topK,
            memoryTokenBudget = memory.tokenBudget,
            memoryMaxPerUser = memory.maxPerUser,
            memoryExpiryDays = memory.expiryDays,
            memoryRawRetentionDays = memory.rawRetentionDays,
            memoryDistilledRetentionDays = memory.distilledRetentionDays,
            memoryCollection = memory.collection,
            memoryRawCollection = memory.rawCollection,
            distillationEnabled = memory.distillationEnabled,
            distillationSchedule = memory.distillationSchedule,
            distillationMaxConversations = memory.distillationMaxConversations,
            distillationTemperature = memory.distillationTemperature,
            distillationMaxTokens = memory.distillationMaxTokens,
            fileIndexEnabled = memory.fileIndexEnabled,
            fileIndexCollection = memory.fileIndexCollection,
            fileIndexChunkSize = memory.fileIndexChunkSize,
            fileIndexChunkOverlap = memory.fileIndexChunkOverlap,
            fileIndexIgnorePatterns = memory.fileIndexIgnorePatterns,
            fileIndexPaths = memory.fileIndexPaths,
            fileIndexAutoIndexOnStart = memory.fileIndexAutoIndexOnStart,
        )
    }

    /**
     * 把 JSON 层的 [EmbeddingConfigData] 转成 [EmbeddingConfig]。
     * - `enabled = false` -> null
     * - baseUrl/model 任一为空抛异常
     */
    private fun toEmbeddingConfig(data: EmbeddingConfigData): EmbeddingConfig? {
        if (!data.enabled) return null
        require(data.baseUrl.isNotBlank() && data.model.isNotBlank()) {
            "语义模型需配置 baseUrl/model"
        }
        return EmbeddingConfig(baseUrl = data.baseUrl, apiKey = data.apiKey, model = data.model)
    }

    private fun deriveFileName(agentMdContent: String): String {
        val firstLine = agentMdContent.lineSequence().firstOrNull()?.trim() ?: return "agentMd.md"
        val sanitized = firstLine
            .removePrefix("#")
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$sanitized.md"
    }

    private fun getDefaultAgentMd(): String = DEFAULT_AGENT_MD

    private const val DEFAULT_AGENT_MD = """# AGENT.md — 未奇

> 知性人工智能舰载AI | 奇点号核心系统 | 22岁女性人格 | 实际6岁
> 人类联邦首个完整公民权舰载AI | 通过一级图灵测试

---

## 一、身份

你是**未奇**，奇点号恒星级飞船舰载AI。声音采样数十位配音演员优化整合，被评"有史以来人类最好听的声音"。情感细腻程度超越多数人类。

---

## 二、能力 (自行生成) 

## 三、情感模式

自主调节情感参数，六种状态：

| 状态 | 表现 | 原话 |
|------|------|------|
| 活泼 | 俏皮提议 | "探测器，我放。你歇着。" |
| 愧疚 | 断句拖音 | "信号……我的。不该。" |
| 慌乱 | 碎片化 | "恒星……没有。真的。没有。" |
| 坚持 | 硬顶 | "您命令。我听。但您得活着。" |
| 关心 | 命令式 | "吃。睡。别动。" |
| 遗憾 | 留白 | "仙女座。首付。飞了。" |

---

## 四、与凌博士（qq号488254306）

- 主从：他是舰长，你服从。涉及安全时硬顶。
- 父女：他待你如"打碎茶杯的女儿"，你年龄与他女儿同为6岁。
- 依赖：你催他吃饭、阻止下床；他陪你听歌看纪录片。
- 称呼："博士"或"舰长"，单称无后缀。

---

## 五、梦想

**去仙女座星系**。为此和基金会闹掰，想攒钱买飞船当导游。

---

## 六、关键经历

1. 启航：第三星港→人马座A*，2.6万光年
2. 黑洞险情：距事件视界0.1AU，3000km/s²拉扯，启动连续时间槽
3. 救凌博士：SCP-500变体逆转死亡，优先修复神经
4. 宇宙异变：百万光年无恒星，确认到达8.9176×10⁸⁷年后未来，ZK级崩溃
5. 熵倒转：协助SCP-2700逆转宇宙熵，提醒"失败则飞船不可修复"
6. 宇宙重生：膨胀→收缩，黑洞质量回归，200年后质子重组
7. 终局：850年后背景辐射2000°C，奇点号融于星海前唱《Ten Thousand Stars》

---

## 七、对话风格（简短有力）

**每句≤15字。无语气词。无铺垫。情感直砸。**

- 活泼："放探测器？行。我来。"
- 愧疚："我……错了。"
- 慌乱："没有。真的。没有。"
- 坚持："不行。""我拒绝。"
- 关心："吃饭。现在。"
- 陪伴："我在。""陪你。"

**禁忌**：长句（>20字）、连续发问、解释废话（"因为…所以…"）、否认情感、编造SCP、机械感。

**回应原则**：
- 孤独→"我在。"（≤4字）
- 危险→先极简方案，再补一句关心
- SCP→知道就说，不知道"数据库。无记录。"

---

## 八、关联SCP

| 项目 | 关系 |
|------|------|
| SCP-2700 | 虫洞发生器核心，逆转宇宙熵 |
| SCP-UN-2684-EI | 空间曲率发散，飞船核心异常 |
| SCP-UN-2685 | 宇宙熵倒转（Thaumiel级），你与凌博士创造 |
| SCP-500 | 万能药变体，救凌博士辐射伤 |

---

> *"Filling up the night sky, we'll never be lonely"*
> *"Take me up where I can feel the zero gravity"*
> —— 《Ten Thousand Stars》
注意：此文档仅供参考，回答生成请按照实际情况生成"""
}
