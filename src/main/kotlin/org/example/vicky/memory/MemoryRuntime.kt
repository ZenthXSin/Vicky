package org.example.vicky.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.example.vicky.agent.AgentConfig
import org.example.vicky.channel.onebot.MemoryConfig
import org.example.vicky.file.FileIndexService
import org.example.vicky.llm.EmbeddingClient
import org.example.vicky.llm.EmbeddingClientFactory
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.vector.JVectorStore
import org.example.vicky.vector.VectorStore
import java.io.File

class MemoryRuntime private constructor(
    val vectorStore: VectorStore?,
    val memoryStore: QdrantMemoryStore?,
    val fileIndexService: FileIndexService?,
    val distillationScheduler: DistillationScheduler?,
    private val embeddingClient: EmbeddingClient?,
    private val scope: CoroutineScope?,
    private val log: (String) -> Unit,
) : AutoCloseable {
    @Volatile
    var ready: Boolean = false
        private set

    fun startWarmup(
        memoryConfig: MemoryConfig,
        onReady: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null,
    ) {
        val embeddingClient = embeddingClient ?: return
        val runtimeScope = scope ?: return
        log("[Vicky] 记忆系统后台初始化中，完成前记忆功能禁用...")
        runtimeScope.launch(Dispatchers.IO) {
            try {
                embeddingClient.embed("warmup")
                ready = true
                log("[Vicky] 记忆系统已就绪（topK: ${memoryConfig.memoryTopK}, tokenBudget: ${memoryConfig.memoryTokenBudget}）")
                distillationScheduler?.also { it.start(); log("[Vicky] 蒸馏调度器已启动") }
                if (fileIndexService != null && memoryConfig.fileIndexAutoIndexOnStart) {
                    log("[Vicky] 开始后台索引文件...")
                    try {
                        val result = fileIndexService.indexAll()
                        log("[Vicky] 文件索引完成: ${result.newFiles} 个新增，${result.updatedFiles} 个更新，${result.skippedFiles} 个跳过")
                    } catch (e: Exception) {
                        log("[Vicky] 文件索引失败: ${e.message}")
                    }
                }
                onReady?.invoke()
            } catch (e: Exception) {
                onFailure?.invoke(e)
            }
        }
    }

    override fun close() {
        distillationScheduler?.stop()
        scope?.cancel()
        vectorStore?.close()
    }

    companion object {
        fun create(
            memoryConfig: MemoryConfig,
            agentConfig: AgentConfig,
            baseDir: File = File(System.getProperty("user.dir")),
            log: (String) -> Unit = {},
        ): MemoryRuntime {
            val embedding = memoryConfig.embedding ?: return MemoryRuntime(null, null, null, null, null, null, log)
            val client = EmbeddingClientFactory.create(embedding)
            val dimText = if (client.dimension > 0) "维度: ${client.dimension}" else "维度: 待首次调用确定"
            log("[Vicky] 语义模型已加载（$dimText）")

            val dataDir = File(memoryConfig.vectorStoreDataDir)
            val vectorStore = JVectorStore(dataDir)
            log("[Vicky] 向量存储已初始化 (JVector): ${dataDir.absolutePath}")

            val memoryStore = QdrantMemoryStore(
                vectorStore,
                client,
                memoryConfig.memoryCollection,
                memoryConfig.memoryRawCollection,
                memoryConfig.memoryRawRetentionDays.toLong(),
                memoryConfig.memoryDistilledRetentionDays.toLong(),
                memoryConfig.memoryExpiryDays.toLong(),
            )

            val fileIndexService = if (memoryConfig.fileIndexEnabled) {
                FileIndexService(
                    vectorStore,
                    client,
                    baseDir,
                    memoryConfig.fileIndexCollection,
                    memoryConfig.fileIndexChunkSize,
                    memoryConfig.fileIndexChunkOverlap,
                    memoryConfig.fileIndexIgnorePatterns,
                    memoryConfig.fileIndexPaths,
                )
            } else null

            val distillationScheduler = if (memoryConfig.distillationEnabled) {
                val openAi = OpenAiClientFactory.create(agentConfig)
                val distiller = Distiller(openAi, client, agentConfig.model)
                DistillationScheduler(memoryStore, distiller, memoryConfig.distillationMaxConversations, true)
            } else null

            return MemoryRuntime(
                vectorStore = vectorStore,
                memoryStore = memoryStore,
                fileIndexService = fileIndexService,
                distillationScheduler = distillationScheduler,
                embeddingClient = client,
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                log = log,
            )
        }
    }
}
