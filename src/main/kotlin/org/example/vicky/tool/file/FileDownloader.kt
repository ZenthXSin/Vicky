package org.example.vicky.tool.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.vicky.config.ConfigManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Base64

const val MAX_DOWNLOAD_BYTES: Long = 100L * 1024 * 1024

/** 下载目录 = configDir/tmp，不存在则建。 */
fun ensureTmpDir(): File {
    val dir = File(ConfigManager.getConfigDir(), "tmp")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

/** 沙盒路径校验：拒绝 tmp 根之外的目标。 */
fun safeTmpPath(tmpDir: File, path: String): File? {
    val canonicalBase = tmpDir.canonicalFile
    val target = File(canonicalBase, path).canonicalFile
    if (!target.path.startsWith(canonicalBase.path + File.separator) &&
        target.path != canonicalBase.path
    ) return null
    return target
}

/**
 * 把 [sourceUrl] 或 [base64] 内容写到 [tmpDir]/savePath 或 [tmpDir]/defaultName。
 * 大小上限 [MAX_DOWNLOAD_BYTES]。返回成功的目标文件，或 Result.failure 携带错误信息。
 */
suspend fun saveToTmp(
    tmpDir: File,
    sourceUrl: String?,
    base64: String?,
    defaultName: String,
    savePath: String,
): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val rel = savePath.ifBlank { defaultName }.ifBlank { "download.bin" }
        val target = safeTmpPath(tmpDir, rel)
            ?: throw IllegalArgumentException("savePath '$rel' escapes tmp directory.")
        target.parentFile?.mkdirs()

        when {
            !sourceUrl.isNullOrBlank() && (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) -> {
                downloadHttp(sourceUrl, target)
            }
            !sourceUrl.isNullOrBlank() && sourceUrl.startsWith("file:") -> {
                val src = Paths.get(URI(sourceUrl))
                val sz = Files.size(src)
                if (sz > MAX_DOWNLOAD_BYTES) {
                    throw IllegalStateException("File too large: $sz bytes > $MAX_DOWNLOAD_BYTES.")
                }
                Files.copy(src, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            !base64.isNullOrBlank() -> {
                val bytes = Base64.getDecoder().decode(base64)
                if (bytes.size > MAX_DOWNLOAD_BYTES) {
                    throw IllegalStateException("Base64 too large: ${bytes.size} bytes > $MAX_DOWNLOAD_BYTES.")
                }
                target.writeBytes(bytes)
            }
            else -> throw IllegalArgumentException("No usable source (url/base64 both empty).")
        }
        target
    }
}

private fun downloadHttp(urlStr: String, target: File) {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        requestMethod = "GET"
    }
    conn.connect()
    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code from $urlStr")
        }
        val advertised = conn.contentLengthLong
        if (advertised > MAX_DOWNLOAD_BYTES) {
            throw IllegalStateException("Content-Length $advertised > $MAX_DOWNLOAD_BYTES.")
        }
        conn.inputStream.use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > MAX_DOWNLOAD_BYTES) {
                        output.close()
                        target.delete()
                        throw IllegalStateException("Stream exceeded $MAX_DOWNLOAD_BYTES bytes; aborted.")
                    }
                    output.write(buf, 0, n)
                }
            }
        }
    } finally {
        conn.disconnect()
    }
}
