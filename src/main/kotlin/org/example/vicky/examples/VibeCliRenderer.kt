package org.example.vicky.examples

import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.pipeline.PipelineStage
import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.status.StatusObserver
import org.example.vicky.vibe.task.VibeTask
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * Claude Code 风格的 Vibe CLI 输出渲染器。
 * 同时实现 MessageSink 和 StatusObserver，统一处理消息和阶段事件。
 *
 * 流式输出：AgentReplyDelta 逐字追加；AgentReplyDone 换行结束。
 * 非 TTY 环境自动降级为普通 println。
 */
class VibeCliRenderer(
    private val model: String,
    private val cwd: String = System.getProperty("user.dir") ?: ".",
) : StatusObserver {
    private val isTty: Boolean = run {
        try { System.console() != null } catch (_: Throwable) { false }
    }
    private val out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)

    private var promptTokensTotal = 0
    private var completionTokensTotal = 0
    private var currentStage: String? = null
    private var deltaActive = false

    fun printHeader() {
        val width = 62
        val border = "─".repeat(width)
        out.println("╭$border╮")
        out.println("│" + " ".repeat(width) + "│")
        out.println("│" + center(model, width) + "│")
        out.println("│" + center("API Usage Billing", width) + "│")
        out.println("│" + center(abbrevPath(cwd), width) + "│")
        out.println("│" + " ".repeat(width) + "│")
        out.println("╰$border╯")
        out.println()
    }

    val sink: MessageSink = MessageSink { out ->
        when (out) {
            is OutboundMessage.AgentReplyDelta -> {
                deltaActive = true
                this.out.print(out.content)
                this.out.flush()
            }
            is OutboundMessage.AgentReplyDone -> {
                if (deltaActive) {
                    this.out.println()
                    deltaActive = false
                }
            }
            is OutboundMessage.AgentReply -> {
                if (deltaActive) { this.out.println(); deltaActive = false }
                if (out.content.isNotBlank()) this.out.println(out.content)
            }
            is OutboundMessage.ToolReply -> {
                if (deltaActive) { this.out.println(); deltaActive = false }
                if (out.content.isNotBlank()) {
                    this.out.println("  ⎿  ${out.toolName}: ${out.content.lines().first().take(80)}")
                }
            }
            is OutboundMessage.Think -> {
                if (deltaActive) { this.out.println(); deltaActive = false }
                val line = formatThink(out.content)
                if (line.isNotBlank()) this.out.println(line)
            }
            is OutboundMessage.Debug -> {
                if (deltaActive) { this.out.println(); deltaActive = false }
                val line = formatDebug(out.content)
                if (line.isNotBlank()) this.out.println(line)
            }
            is OutboundMessage.TokenUsage -> {
                promptTokensTotal += out.promptTokens
                completionTokensTotal += out.completionTokens
                val usageLine = "  ⎿  ${promptTokensTotal}tk in  ${completionTokensTotal}tk out"
                if (isTty) {
                    this.out.print("\r$usageLine\r")
                    this.out.flush()
                } else {
                    this.out.println(usageLine)
                }
            }
        }
    }

    // StatusObserver

    override fun onStageStart(stage: PipelineStage, index: Int, total: Int) {
        if (deltaActive) { out.println(); deltaActive = false }
        currentStage = stage.role.label
        out.println("● 处理 ${stage.role.label} (${ index + 1}/$total)…")
    }

    override fun onStageComplete(stage: PipelineStage, output: StageOutput, index: Int, total: Int) {
        if (output.summary.isNotBlank()) out.println("  ⎿  ${output.summary}")
    }

    override fun onTaskUpdate(task: VibeTask) {
        out.println("· ${task.subject} (${task.status})")
    }

    override fun onPipelineComplete(result: OrchestratorResult) {
        out.println()
        out.println("· done  elapsed=${result.elapsed}ms  tasks=${result.tasks.size}")
    }

    override fun onError(error: String, stage: PipelineStage?) {
        if (deltaActive) { out.println(); deltaActive = false }
        out.println("  ⎿  error: ${stage?.role?.label ?: "vibe"}: $error")
    }

    fun printCompacted() {
        out.println()
        out.println("✻ Conversation compacted (ctrl+o for history)")
        out.println()
    }

    // ─── helpers ─────────────────────────────────────────────

    private fun center(s: String, width: Int): String {
        if (s.length >= width) return s.take(width)
        val pad = (width - s.length) / 2
        return " ".repeat(pad) + s + " ".repeat(width - s.length - pad)
    }

    private fun abbrevPath(path: String): String {
        val home = System.getProperty("user.home") ?: return path
        return if (path.startsWith(home)) "~" + path.removePrefix(home) else path
    }

    private fun formatThink(content: String): String = when {
        content.startsWith("Use Tool: ") -> "  Outil `${content.removePrefix("Use Tool: ")}`"
        content.isBlank() -> ""
        else -> "● ${content.lines().first().trim().take(100)}"
    }

    private fun formatDebug(content: String): String = when {
        content.startsWith("[task:") -> "· ${content.removePrefix("[").removeSuffix("]")}"
        content.contains("requesting completion") -> "● 规划下一步…"
        content.contains("maxSteps") -> "  ⎿  已达到步数上限，准备收尾总结。"
        content.contains("vibe failed") -> "  ⎿  $content"
        else -> ""
    }
}
