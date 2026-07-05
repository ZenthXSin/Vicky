package org.example.vicky.vibe.turn

import org.example.vicky.agent.ChatCompletionRunner
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.vibe.engine.VibeMessagePipeline

class VibeTurnRunner {
    fun createCompletionRunner(request: VibeTurnRequest): ChatCompletionRunner =
        ChatCompletionRunner(OpenAiClientFactory.create(request.config), request.config)

    suspend fun run(request: VibeTurnRequest): VibeTurnResult {
        val runner = createCompletionRunner(request)
        return VibeMessagePipeline(request, runner).run()
    }
}
