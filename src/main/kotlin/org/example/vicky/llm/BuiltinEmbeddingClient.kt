package org.example.vicky.llm

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.types.DataType
import ai.djl.ndarray.types.Shape
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.vicky.agent.EmbeddingConfig
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt

/**
 * 内置 embedding：DJL + HuggingFace sentence-transformers，进程内本地推理。
 *
 * - 模型 id 形如 "sentence-transformers/all-MiniLM-L6-v2"，会从 HuggingFace 自动下载到 ~/.djl.ai/
 * - 输出做 mean pooling（attention mask 加权）+ L2 归一化，与 sentence-transformers 默认行为一致
 * - 推理实例非线程安全，外部用 [mutex] 串行化
 */
class BuiltinEmbeddingClient(private val config: EmbeddingConfig.Builtin) : EmbeddingClient {

    @Volatile
    override var dimension: Int = -1
        private set

    private val mutex = Mutex()

    private val model: ZooModel<List<String>, Array<FloatArray>> by lazy {
        if (config.modelPath.isNotBlank()) {
            loadFromLocal(config.modelPath)
        } else {
            if (config.endpoint.isNotBlank()) {
                System.setProperty("HF_ENDPOINT", config.endpoint)
                println("[Vicky] 使用 HuggingFace 镜像: ${config.endpoint}")
            }
            if (config.proxy.isNotBlank()) configureProxy(config.proxy)
            loadFromRemote()
        }
    }

    /** 离线：从本地目录加载 tokenizer + 模型权重，不触发任何网络请求。 */
    private fun loadFromLocal(path: String): ZooModel<List<String>, Array<FloatArray>> {
        val dir = Paths.get(path).toAbsolutePath()
        require(dir.toFile().isDirectory) {
            "modelPath 不是有效目录: $dir"
        }
        println("[Vicky] 离线加载语义模型: $dir")

        val tokenizer = HuggingFaceTokenizer.newInstance(dir)
        val translator = SentenceTransformerTranslator(tokenizer)

        val criteria = Criteria.builder()
            .setTypes(List::class.java as Class<List<String>>, Array<FloatArray>::class.java)
            .optModelPath(dir)
            .optTranslator(translator)
            .optEngine("PyTorch")
            .build()
        val zoo = criteria.loadModel()
        warmUpDimension(zoo)
        return zoo
    }

    /** 在线：通过 Java HTTP（尊重 JVM 代理）下载 tokenizer.json 到本地缓存，然后让 DJL 加载模型 .pt。 */
    private fun loadFromRemote(): ZooModel<List<String>, Array<FloatArray>> {
        println("[Vicky] 正在加载语义模型: ${config.model}")

        // 自己下 tokenizer.json，避开 HuggingFaceTokenizer 内置的 Rust 原生下载器（不走 JVM 代理）
        val tokenizerPath = ensureTokenizerLocally()
        val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath)
        val translator = SentenceTransformerTranslator(tokenizer)

        println("[Vicky] 正在下载模型权重（首次需要数十 MB，请耐心等待）...")

        val originalOut = System.out
        val progressStream = PrintStream(object : java.io.OutputStream() {
            private val buffer = StringBuilder()
            override fun write(b: Int) {
                buffer.append(b.toChar())
                if (b == '\n'.code || buffer.length > 100) {
                    val line = buffer.toString().trim()
                    if (line.contains("Download") || line.contains("download") || line.contains("%")) {
                        originalOut.println("[Vicky] 模型下载: $line")
                    }
                    buffer.clear()
                }
            }
        })

        val oldOut = System.out
        System.setOut(progressStream)
        val zoo = try {
            val criteria = Criteria.builder()
                .setTypes(List::class.java as Class<List<String>>, Array<FloatArray>::class.java)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/${config.model}")
                .optTranslator(translator)
                .optEngine("PyTorch")
                .build()
            criteria.loadModel()
        } finally {
            System.setOut(oldOut)
        }
        warmUpDimension(zoo)
        return zoo
    }

    /**
     * 把 tokenizer.json 拉到 config/models/<sanitized>/tokenizer.json，存在且看起来有效就跳过。
     * 用 Java HttpURLConnection（尊重 https.proxyHost 系统属性）。
     */
    private fun ensureTokenizerLocally(): Path {
        val sanitized = config.model.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val cacheDir = Paths.get(System.getProperty("user.dir"), "config", "models", sanitized)
        val tokenizerFile = cacheDir.resolve("tokenizer.json")
        if (Files.exists(tokenizerFile) && looksLikeValidJson(tokenizerFile)) {
            return tokenizerFile
        }
        Files.createDirectories(cacheDir)
        val base = config.endpoint.ifBlank { "https://hf-mirror.com" }.trimEnd('/')
        val url = "$base/${config.model}/resolve/main/tokenizer.json"
        println("[Vicky] 下载 tokenizer.json: $url")
        downloadFile(url, tokenizerFile)
        val size = Files.size(tokenizerFile)
        println("[Vicky] tokenizer.json 已保存（$size 字节）")
        require(size > 0 && looksLikeValidJson(tokenizerFile)) {
            "tokenizer.json 下载内容无效（$size 字节）。请检查网络/代理/镜像，或手工下载放到 ${tokenizerFile.toAbsolutePath()}"
        }
        return tokenizerFile
    }

    private fun looksLikeValidJson(file: Path): Boolean {
        if (Files.size(file) < 8) return false
        val head = ByteArray(8)
        Files.newInputStream(file).use { it.read(head) }
        // tokenizer.json 头部要么 '{'，要么 BOM 后跟 '{'
        return head[0] == '{'.code.toByte() ||
            (head.size >= 4 && head[3] == '{'.code.toByte())
    }

    private fun downloadFile(urlStr: String, dest: Path) {
        var current = urlStr
        var hops = 0
        while (true) {
            val conn = URI(current).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = false  // 手动处理跨协议重定向
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Vicky)")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location")
                    ?: error("HTTP $code 重定向但无 Location 头: $current")
                conn.disconnect()
                current = if (loc.startsWith("http")) loc else URI(current).resolve(loc).toString()
                hops++
                continue
            }
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                conn.disconnect()
                error("HTTP $code 下载失败: $current\n响应: ${body?.take(200)}")
            }
            conn.inputStream.use { input ->
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            conn.disconnect()
            return
        }
    }

    private fun warmUpDimension(zoo: ZooModel<List<String>, Array<FloatArray>>) {
        runCatching {
            println("[Vicky] 正在预热模型以确定维度...")
            val result = zoo.newPredictor().use { it.predict(listOf("warmup")) }
            if (result.isNotEmpty()) {
                dimension = result[0].size
                println("[Vicky] 模型维度已确定: $dimension")
            }
        }.onFailure {
            println("[Vicky] 警告：预热推理失败，维度将在首次 embed 后自动设置: ${it.message}")
        }
    }

    init {
        println("[Vicky] 正在加载语义模型...")
        try {
            model  // 构造时立即触发模型加载和预热，确保 dimension 已设置
            println("[Vicky] 语义模型加载完成，维度: $dimension")
        } catch (e: Exception) {
            println("[Vicky] 语义模型加载失败: ${e.message}")
        }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val vectors = withContext(Dispatchers.IO) {
            mutex.withLock {
                model.newPredictor().use { it.predict(texts) }
            }
        }
        if (vectors.isNotEmpty() && dimension == -1) dimension = vectors[0].size
        return vectors.toList()
    }

    /**
     * sentence-transformers 风格的 translator：
     * 输入一批字符串 -> 分词 -> [last_hidden_state, attention_mask]
     * 输出按 attention_mask mean-pool + L2 normalize 后的 float[][]
     */
    private class SentenceTransformerTranslator(
        private val tokenizer: HuggingFaceTokenizer,
    ) : Translator<List<String>, Array<FloatArray>> {

        override fun processInput(ctx: TranslatorContext, input: List<String>): NDList {
            val encodings = tokenizer.batchEncode(input)
            val manager: NDManager = ctx.ndManager
            val batch = encodings.size
            val seqLen = encodings.maxOf { it.ids.size }

            val ids = LongArray(batch * seqLen)
            val mask = LongArray(batch * seqLen)
            for (i in 0 until batch) {
                val e = encodings[i]
                for (j in e.ids.indices) {
                    ids[i * seqLen + j] = e.ids[j]
                    mask[i * seqLen + j] = e.attentionMask[j]
                }
            }
            val shape = Shape(batch.toLong(), seqLen.toLong())
            val idsNd = manager.create(ids, shape)
            val maskNd = manager.create(mask, shape)
            return NDList(idsNd, maskNd)
        }

        override fun processOutput(ctx: TranslatorContext, list: NDList): Array<FloatArray> {
            val hidden: NDArray = list[0]

            // 根据实际输出维度做 pooling，统一归结为 [batchSize, hiddenSize]
            val pooled: NDArray = when (hidden.shape.dimension()) {
                1 -> hidden.reshape(Shape(1, hidden.shape[0]))  // [hidden] → [1, hidden]
                2 -> hidden                                      // [batch, hidden] 已 pooled
                3 -> hidden.mean(intArrayOf(1))                 // [batch, seq, hidden] → [batch, hidden]
                else -> error("Unexpected model output shape: ${hidden.shape}")
            }

            // L2 normalize
            val norm = pooled.pow(2.0).sum(intArrayOf(1), true).sqrt().clip(1e-12, 1e30)
            val normalized = pooled.div(norm)

            val flatArray = normalized.toFloatArray()
            val batchSize = normalized.shape[0].toInt()
            val hiddenSize = normalized.shape[1].toInt()

            return Array(batchSize) { i ->
                FloatArray(hiddenSize) { j ->
                    flatArray[i * hiddenSize + j]
                }
            }
        }

        override fun getBatchifier() = null
    }

    @Suppress("unused")
    private fun l2Normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).coerceAtLeast(1e-12)
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    companion object {
        @Volatile
        private var configuredProxy: String? = null

        /**
         * 配置 HTTP 代理（在模型加载前调用）。**幂等**：同一 proxy 重复调用不再打印。
         *
         * 同时设置 JVM 系统属性（给 Java HTTP 用）和**进程环境变量**（给原生 Rust tokenizer 用）。
         * 环境变量注入走反射，JDK 17+ 需要 JVM 参数：
         *   --add-opens java.base/java.util=ALL-UNNAMED
         *   --add-opens java.base/java.lang=ALL-UNNAMED
         * 注意：Windows 上反射注入只改 JVM 内 map，不改 OS 进程环境块；原生代码看不到。
         *      所以 Windows + 原生 tokenizer 下载，请优先用 ensureTokenizerLocally 走 Java HTTP。
         */
        fun configureProxy(proxy: String) {
            if (proxy.isBlank()) return
            if (configuredProxy == proxy) return
            try {
                val uri = URI(proxy)
                val host = uri.host ?: return
                val port = if (uri.port > 0) uri.port else 80

                System.setProperty("http.proxyHost", host)
                System.setProperty("http.proxyPort", port.toString())
                System.setProperty("https.proxyHost", host)
                System.setProperty("https.proxyPort", port.toString())

                setEnv(mapOf(
                    "HTTP_PROXY"  to proxy,
                    "HTTPS_PROXY" to proxy,
                    "http_proxy"  to proxy,
                    "https_proxy" to proxy,
                ))

                configuredProxy = proxy
                println("[Vicky] 代理已配置: $proxy")
            } catch (e: Exception) {
                println("[Vicky] 代理配置失败: ${e.message}")
            }
        }

        /**
         * 反射注入进程环境变量。需要 --add-opens java.base/java.util=ALL-UNNAMED 等。
         * 失败时打印一次警告，不抛异常——Java HTTP 部分仍可用。
         */
        @Suppress("UNCHECKED_CAST")
        internal fun setEnv(extra: Map<String, String>) {
            var injected = false
            // 1) System.getenv() 返回的 UnmodifiableMap 内层 m
            try {
                val env = System.getenv()
                val field = env.javaClass.getDeclaredField("m")
                field.isAccessible = true
                (field.get(env) as MutableMap<String, String>).putAll(extra)
                injected = true
            } catch (_: Throwable) {}
            // 2) ProcessEnvironment 的两个静态 map（Windows 用 theCaseInsensitiveEnvironment）
            try {
                val pe = Class.forName("java.lang.ProcessEnvironment")
                listOf("theEnvironment", "theCaseInsensitiveEnvironment").forEach { name ->
                    runCatching {
                        val f = pe.getDeclaredField(name)
                        f.isAccessible = true
                        (f.get(null) as MutableMap<String, String>).putAll(extra)
                        injected = true
                    }
                }
            } catch (_: Throwable) {}
            if (!injected) {
                println("[Vicky] 警告：环境变量注入失败。原生下载器（tokenizer）将看不到代理。")
                println("[Vicky] 请在 JVM 启动参数添加：--add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED")
                println("[Vicky] 或者直接把模型下载到本地，配置 embedding.builtin.modelPath 指向该目录。")
            }
        }
    }
}
