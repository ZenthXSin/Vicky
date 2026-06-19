package org.example.vicky.agent

/**
 * 语义模型（embedding）配置。
 *
 * 内置（[Builtin]）与外置（[External]）二选一，互斥。
 * - [Builtin]：进程内本地推理（DJL + HuggingFace sentence-transformers）。
 * - [External]：OpenAI 协议兼容的远程 embeddings 端点。
 */
sealed class EmbeddingConfig {
    /**
     * 内置：本地 Java/Kotlin 模型。
     *
     * 加载优先级：[modelPath] > [endpoint] > 官方 huggingface.co。
     *
     * @property model     HuggingFace 模型 id，例如 "sentence-transformers/all-MiniLM-L6-v2"。
     * @property modelPath 本地模型目录（非空时直接离线加载，不走网络）。
     *                    目录需含 tokenizer.json + 模型权重（model.safetensors / pytorch_model.bin / traced_model.pt）。
     * @property endpoint HuggingFace 镜像端点，如 "https://hf-mirror.com"，仅 [modelPath] 为空时生效。
     * @property proxy    HTTP 代理 URL（如 "http://127.0.0.1:7892"），用于远程下载时穿越网络。
     */
    data class Builtin(
        val model: String,
        val modelPath: String = "",
        val endpoint: String = "",
        val proxy: String = "",
    ) : EmbeddingConfig()

    /**
     * 外置：OpenAI 协议兼容端点。
     *
     * @property baseUrl 例如 "https://api.openai.com/v1/"。
     * @property apiKey  API key。
     * @property model   模型 id，例如 "text-embedding-3-small"。
     */
    data class External(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    ) : EmbeddingConfig()
}
