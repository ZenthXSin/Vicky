package org.example.vicky.vibe

import kotlinx.coroutines.runBlocking
import org.example.vicky.config.ConfigManager
import org.example.vicky.vibe.orchestrator.VibeOrchestrator
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VibeCodeCliIntegrationTest {
    @Test
    fun `vibe runner can complete a real llm turn from current config`(): Unit = runBlocking {
        val loadResult = ConfigManager.loadOrCreate()
        require(!loadResult.firstRun) { "config.json was just created; fill it before running this integration test" }
        require(loadResult.config.apiKey.isNotBlank()) { "config.json apiKey is blank" }

        val config = ConfigManager.toAgentConfig(loadResult.config, loadResult.agentMd).copy(
            builtinTools = false,
            maxSteps = 2,
            debug = false,
            think = false,
        )
        val result = VibeOrchestrator(config).execute("用一句中文回答：Vibe Code CLI 集成测试是否已经跑通？不要调用工具。")

        assertTrue(result.stages.isNotEmpty())
        assertTrue(result.success, result.stages.lastOrNull()?.output ?: "no output")
        assertNotNull(result.stages.last().output.takeIf { it.isNotBlank() })
    }
}
