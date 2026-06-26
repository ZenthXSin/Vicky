package org.example.vicky.vibe.pipeline

import kotlinx.serialization.Serializable

@Serializable
data class StageOutput(
    val summary: String = "",
    val output: String = "",
    val tasks: List<StageTaskProposal> = emptyList(),
    val pass: Boolean? = null,
)

@Serializable
data class StageTaskProposal(
    val subject: String,
    val role: String? = null,
    val blockedBy: List<String> = emptyList(),
)
