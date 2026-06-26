package org.example.vicky.vibe.pipeline

import kotlinx.serialization.Serializable

@Serializable
data class StageInput(
    val request: String,
    val previousResults: String = "",
    val tasks: String = "",
)
