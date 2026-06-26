package org.example.vicky.vibe.pipeline

import org.example.vicky.vibe.role.AgentRole

class Pipeline(val stages: List<PipelineStage>) {
    companion object {
        fun default(): Pipeline = Pipeline(
            listOf(
                PipelineStage(AgentRole.GENERAL, "接收请求，拆分任务"),
                PipelineStage(AgentRole.PLANNING, "设计方案，确定步骤和依赖"),
                PipelineStage(AgentRole.INVESTIGATION, "搜索、探索、收集信息"),
                PipelineStage(AgentRole.WRITING, "执行具体操作"),
                PipelineStage(AgentRole.REVIEW, "审查结果，决定是否通过"),
            )
        )
    }
}
