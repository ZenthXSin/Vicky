package org.example.vicky.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.example.vicky.llm.EmbeddingClient
import org.example.vicky.vector.VectorRecord
import org.example.vicky.vector.VectorStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 文件索引结果。
 */
data class FileSearchResult(
    val path: String,
    val chunk: String,
    val score: Float,
    val startLine: Int,
    val endLine: Int,
)

/**
 * 文件索引服务。将文件内容分块后存入向量存储，支持语义搜索。
 *
 * 特性：
 * - 增量索引：只索引新增或修改过的文件（通过 last_modified 判断）
 * - 缓存：已索引的文件信息缓存在内存中，重启时快速判断
 */
class FileIndexService(
    private val vectorStore: VectorStore,
    private val embeddingClient: EmbeddingClient,
    private val baseDir: File,
    private val collectionName: String = "vicky_files",
    private val chunkSize: Int = 500,
    private val chunkOverlap: Int = 50,
    private val ignorePatterns: List<String> = listOf(".git", ".gradle", "build", "node_modules"),
    /** 自动索引的子目录列表（相对 [baseDir] 或绝对路径）。空 = 索引整个 [baseDir]。 */
    private val indexPaths: List<String> = emptyList(),
) {
    private var initialized = false

    /** 文件索引缓存：path -> lastModified */
    private val indexedFiles = ConcurrentHashMap<String, Long>()

    private suspend fun ensureInitialized() {
        if (initialized) return
        // 先调用一次 embed 确定维度（首次调用后 dimension 会回填）
        if (embeddingClient.dimension == -1) {
            try {
                val result = embeddingClient.embed("initialization")
                if (result.isNotEmpty() && embeddingClient.dimension == -1) {
                    println("[Vicky] 警告：维度初始化失败，使用默认值 384")
                }
            } catch (e: Exception) {
                println("[Vicky] 警告：维度初始化失败: ${e.message}")
            }
        }
        val dim = if (embeddingClient.dimension == -1) {
            println("[Vicky] 使用默认维度 384")
            384
        } else {
            embeddingClient.dimension
        }
        vectorStore.ensureCollection(collectionName, dim)
        loadIndexCache()
        initialized = true
    }

    /**
     * 从 Qdrant 加载已索引文件的缓存。分页加载，避免大集合 OOM。
     */
    private suspend fun loadIndexCache() {
        var loaded = 0
        while (true) {
            val batch = vectorStore.scrollPayloadOnly(collectionName, 1000, null)
            if (batch.isEmpty()) break
            for (record in batch) {
                val path = record.payload["path"] as? String ?: continue
                val lastModified = (record.payload["last_modified"] as? Double)?.toLong() ?: 0L
                indexedFiles[path] = lastModified
            }
            loaded += batch.size
            if (batch.size < 1000) break
        }
        println("[Vicky] Loaded ${indexedFiles.size} indexed files from cache")
    }

    /**
     * 增量索引整个根目录。只处理新增或修改过的文件。
     * 并行处理多个文件，提高索引速度。
     *
     * @param parallelism 并行度（同时处理的文件数），默认 4
     * @param onProgress 进度回调：(当前文件数, 成功文件数, 跳过文件数)
     */
    suspend fun indexAll(
        parallelism: Int = 4,
        onProgress: ((current: Int, success: Int, skipped: Int) -> Unit)? = null,
    ): IndexResult {
        ensureInitialized()
        val newIndexCount = AtomicInteger(0)
        val updatedIndexCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val currentIndex = AtomicInteger(0)
        val semaphore = Semaphore(parallelism)

        val textFiles = collectRoots().asSequence()
            .flatMap { it.walkTopDown() }
            .filter { file ->
                file.isFile && !isIgnored(file) && isTextFile(file)
            }
            .distinctBy { it.absolutePath }
            .toList()

        coroutineScope {
            textFiles.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val relativePath = relativize(file)
                        try {
                            val action = indexFileIfNeeded(relativePath, file)
                            when (action) {
                                IndexAction.NEW -> newIndexCount.incrementAndGet()
                                IndexAction.UPDATED -> updatedIndexCount.incrementAndGet()
                                IndexAction.SKIPPED -> skippedCount.incrementAndGet()
                            }
                        } catch (e: Exception) {
                            println("[Vicky] 索引文件失败: $relativePath: ${e.message}")
                        }
                        val current = currentIndex.incrementAndGet()
                        onProgress?.invoke(current, newIndexCount.get() + updatedIndexCount.get(), skippedCount.get())
                    }
                }
            }.awaitAll()
        }

        return IndexResult(
            newFiles = newIndexCount.get(),
            updatedFiles = updatedIndexCount.get(),
            skippedFiles = skippedCount.get(),
            totalChunks = indexedFiles.size,
        )
    }

    /**
     * 检查文件是否需要索引，如果需要则执行索引。
     */
    private suspend fun indexFileIfNeeded(relativePath: String, file: File): IndexAction {
        val currentLastModified = file.lastModified()
        val cachedLastModified = indexedFiles[relativePath]

        // 已索引且未修改 → 跳过
        if (cachedLastModified != null && cachedLastModified >= currentLastModified) {
            return IndexAction.SKIPPED
        }

        // 需要索引（新增或修改）
        val chunks = indexFile(relativePath)
        return if (cachedLastModified == null) IndexAction.NEW else IndexAction.UPDATED
    }

    /**
     * 索引单个文件。
     */
    suspend fun indexFile(relativePath: String): Int {
        ensureInitialized()
        val file = File(baseDir, relativePath)
        if (!file.exists() || !file.isFile) return 0
        if (isIgnored(file)) return 0
        if (!isTextFile(file)) return 0

        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return 0
        if (content.isBlank()) return 0

        // 先删除旧索引
        removeFile(relativePath)

        val chunks = splitIntoChunks(content)
        if (chunks.isEmpty()) return 0

        // 批量 embedding，每批最多 32 条，避免请求过大
        val texts = chunks.map { it.first }
        val batchSize = 4
        val vectors = mutableListOf<FloatArray>()
        for (i in texts.indices step batchSize) {
            val batch = texts.subList(i, (i + batchSize).coerceAtMost(texts.size))
            vectors.addAll(embeddingClient.embed(batch))
        }

        val records = chunks.mapIndexed { index, (chunk, startLine, endLine) ->
            VectorRecord(
                id = java.util.UUID.randomUUID().toString(),
                vector = vectors[index],
                payload = mapOf(
                    "path" to relativePath,
                    "chunk" to chunk,
                    "start_line" to startLine,
                    "end_line" to endLine,
                    "file_size" to file.length(),
                    "last_modified" to file.lastModified(),
                ),
            )
        }

        vectorStore.upsert(collectionName, records)
        indexedFiles[relativePath] = file.lastModified()
        return records.size
    }

    /**
     * 语义搜索已索引的文件。
     */
    suspend fun search(query: String, topK: Int = 5): List<FileSearchResult> {
        ensureInitialized()
        val vector = embeddingClient.embed(query)
        val results = vectorStore.search(collectionName, vector, topK)

        return results.map { result ->
            val payload = result.payload
            FileSearchResult(
                path = payload["path"] as? String ?: "",
                chunk = payload["chunk"] as? String ?: "",
                score = result.score,
                startLine = (payload["start_line"] as? Double)?.toInt() ?: 0,
                endLine = (payload["end_line"] as? Double)?.toInt() ?: 0,
            )
        }
    }

    /**
     * 删除文件索引。
     */
    suspend fun removeFile(relativePath: String) {
        ensureInitialized()
        vectorStore.deleteByFilter(collectionName, mapOf("path" to relativePath))
        indexedFiles.remove(relativePath)
    }

    /**
     * 检查文件是否已索引。
     */
    suspend fun isIndexed(relativePath: String): Boolean {
        ensureInitialized()
        return indexedFiles.containsKey(relativePath)
    }

    /**
     * 获取已索引文件数量。
     */
    fun getIndexedFileCount(): Int = indexedFiles.size

    /**
     * 解析配置的 [indexPaths] 为实际待遍历的根目录列表。
     * - 空 / 全空 → 返回 `[baseDir]`，等于扫描整个工作目录（旧行为）
     * - 相对路径相对 [baseDir] 解析；绝对路径直接用
     * - 不存在或不是目录的条目跳过并打印告警
     */
    private fun collectRoots(): List<File> {
        val cleaned = indexPaths.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return listOf(baseDir)
        return cleaned.mapNotNull { p ->
            val f = File(p).let { if (it.isAbsolute) it else File(baseDir, p) }
            when {
                !f.exists()      -> { println("[Vicky] 索引目录不存在，跳过: ${f.absolutePath}"); null }
                !f.isDirectory   -> { println("[Vicky] 索引路径不是目录，跳过: ${f.absolutePath}"); null }
                else             -> f
            }
        }
    }

    /** 文件相对 [baseDir] 的路径；不在 [baseDir] 下时退化为绝对路径。 */
    private fun relativize(file: File): String {
        val basePath = baseDir.toPath().toAbsolutePath().normalize()
        val filePath = file.toPath().toAbsolutePath().normalize()
        return if (filePath.startsWith(basePath)) {
            basePath.relativize(filePath).toString()
        } else {
            filePath.toString()
        }
    }

    private fun splitIntoChunks(content: String): List<Triple<String, Int, Int>> {
        val lines = content.lines()
        val chunks = mutableListOf<Triple<String, Int, Int>>()

        var i = 0
        while (i < lines.size) {
            val startLine = i + 1
            var charCount = 0
            var endLine = i

            while (endLine < lines.size && charCount < chunkSize) {
                charCount += lines[endLine].length + 1
                endLine++
            }

            val chunkContent = lines.subList(i, endLine).joinToString("\n")
            if (chunkContent.isNotBlank()) {
                chunks.add(Triple(chunkContent, startLine, endLine))
            }

            // 下一个 chunk 从当前 end 回退 chunkOverlap 行，实现重叠
            val advance = (endLine - i).coerceAtLeast(1)
            val overlap = chunkOverlap.coerceAtMost(advance - 1)
            i += advance - overlap
        }

        return chunks
    }

    private fun isIgnored(file: File): Boolean {
        val path = file.absolutePath
        return ignorePatterns.any { pattern ->
            val norm = if (pattern.contains('/') || pattern.contains('\\')) {
                pattern.replace('/', File.separatorChar).replace('\\', File.separatorChar)
            } else pattern
            path.contains(File.separator + norm + File.separator) ||
                path.endsWith(File.separator + norm)
        }
    }

    private fun isTextFile(file: File): Boolean {
        val textExtensions = setOf(
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
            "md", "txt", "json", "yaml", "yml", "toml", "xml",
            "html", "css", "scss", "less", "sql", "sh", "bat",
            "gradle", "properties", "cfg", "conf", "ini",
        )
        return file.extension.lowercase() in textExtensions
    }
}

/**
 * 索引动作。
 */
enum class IndexAction {
    NEW,      // 新文件
    UPDATED,  // 已修改
    SKIPPED,  // 已索引且未修改
}

/**
 * 索引结果。
 */
data class IndexResult(
    val newFiles: Int,
    val updatedFiles: Int,
    val skippedFiles: Int,
    val totalChunks: Int,
)
