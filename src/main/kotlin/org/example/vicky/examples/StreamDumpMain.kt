package org.example.vicky.examples

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import org.example.vicky.config.ConfigManager

/**
 * 抓 LLM 流式 SSE 原始输出，绕过 openai-kotlin SDK。
 * 用来排查"流式 tool_call 字段被 SDK 漏解析"之类问题。
 *
 * 用法：直接 run。它会读取 config/config.json 里的 baseUrl/apiKey/model，
 * 发一个带 file_list 工具的最小请求，按行打印服务端推过来的 SSE。
 */
fun main() = runBlocking {
    System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))

    val cfg = ConfigManager.loadOrCreate().config
    val baseUrl = (cfg.baseUrl ?: "https://api.openai.com/v1/").let {
        if (it.endsWith("/")) it else "$it/"
    }
    val url = baseUrl + "chat/completions"

    val body = """
        {
          "model": "${cfg.model}",
          "stream": true,
          "tools": [
            {
              "type": "function",
              "function": {
                "name": "file_list",
                "description": "List directory contents.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "Relative directory path." }
                  }
                }
              }
            }
          ],
          "messages": [
            { "role": "user", "content": "调用 file_list 工具查看当前目录" }
          ]
        }
    """.trimIndent()

    println("=== POST $url")
    println("=== model=${cfg.model}")
    println("=== body=$body")
    println("=== ---- raw SSE ----")

    val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    var lineNo = 0
    try {
        client.preparePost(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${cfg.apiKey}")
                append(HttpHeaders.Accept, "text/event-stream")
            }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { resp ->
            println("=== HTTP ${resp.status}")
            resp.headers.entries().forEach { (k, v) -> println("=== H $k: ${v.joinToString()}") }
            println("=== ---- body ----")
            val ch = resp.bodyAsChannel()
            while (true) {
                val line = ch.readUTF8Line() ?: break
                lineNo++
                println("[$lineNo] $line")
            }
        }
    } catch (e: Throwable) {
        println("=== EXCEPTION after $lineNo lines: ${e::class.simpleName}: ${e.message}")
    } finally {
        client.close()
        println("=== ---- done ($lineNo lines) ----")
    }
}
