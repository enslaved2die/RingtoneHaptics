package com.ringtonehaptics.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BeatGridInfo(
    val bpm: Int = 120,
    val firstBeatMs: Long = 0L,
    val beatIntervalMs: Long = 500L,
    val beatTimestamps: List<Long> = emptyList(),
    val downbeatTimestamps: Set<Long> = emptySet()
)
