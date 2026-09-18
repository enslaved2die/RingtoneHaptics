package com.ringtonehaptics.app.domain.model

import com.ringtonehaptics.app.domain.ml.DrumClass
import kotlinx.serialization.Serializable

/**
 * A saved editing session: enough to reopen a track and its haptic clips without re-running the
 * (possibly multi-minute, ML-enhanced) analysis pipeline. Raw audio/waveform/stem PCM is never
 * persisted here - only [sourceUri] (re-decoded on open) and the derived clip/config state.
 */
@Serializable
data class HapticProject(
    val id: String,
    val name: String,
    val sourceUri: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val hapticClips: List<HapticClip>,
    val filterConfig: FilterConfig,
    val beatGrid: BeatGridInfo,
    val selectedAudioStem: String? = null,
    // Empty = "all classes" (see EditorUiState.selectedDrumClasses) - matches selectedAudioStem's
    // own null-means-everything convention instead of persisting a redundant "is this filtered" flag.
    val selectedDrumClasses: Set<DrumClass> = emptySet(),
    val wasMlEnhanced: Boolean = false
)

/** Lightweight listing entry - avoids parsing every clip just to show the launch-screen list. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val durationMs: Long,
    val clipCount: Int
)
