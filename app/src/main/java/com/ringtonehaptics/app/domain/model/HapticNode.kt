package com.ringtonehaptics.app.domain.model

data class HapticNode(
    val id: Long = System.nanoTime(),
    val timestampMs: Long,
    val intensity: Float, // 0.0f to 1.0f
    val durationMs: Int = 80, // Duration of the haptic impulse in ms
    val isAutoDetected: Boolean = true
)
