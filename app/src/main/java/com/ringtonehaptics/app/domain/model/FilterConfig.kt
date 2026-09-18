package com.ringtonehaptics.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class FilterType {
    LOWPASS,
    HIGHPASS,
    BANDPASS
}

@Serializable
data class FilterConfig(
    val filterType: FilterType = FilterType.LOWPASS,
    val cutoffFrequencyHz: Float = 140.0f,      // Range: 40Hz - 400Hz for lowpass
    val resonanceQ: Float = 0.707f,             // Butterworth Q
    val sensitivityThreshold: Float = 0.40f,    // 0.05 to 0.95 for peak detection
    val resonantFrequencyHz: Float = 160.0f,    // Pixel LRA actuator resonance (120Hz - 250Hz)
    val masterGainDb: Float = -4.0f,            // Headroom to prevent motor bottoming out (-12 to 0 dB)
    val attackMs: Float = 8.0f,
    val decayMs: Float = 60.0f,
    // Default raised from 0.35 to 0.85: measured against a reference audio-coupled-haptics
    // ringtone (Telegram's @pixelhapticbot), whose haptic channel is a near-continuous bass
    // envelope (84% of the track active, RMS 0.163) rather than sparse discrete pulses (we were at
    // 5% active). A weak background blend read as "barely there" next to that; 0.85 matches its
    // measured RMS almost exactly for the same source track. See synthesizeChannel2's headroom
    // multiplier for the other half of that tuning.
    val blendContinuousEnvelope: Float = 0.85f, // 0.0 = only discrete pulses, 1.0 = full continuous envelope
    // Floor below which the continuous envelope hard-gates to true zero instead of trailing off.
    // EnvelopeFollower's attack/decay smoothing asymptotically approaches zero but never quite
    // reaches it, and the normalize-to-peak step scales that residual up along with everything
    // else - so quiet sections (silence, room tone, breath) were producing a small but nonzero
    // continuous sample on every playback/export path. That's worse than silence on a real LRA:
    // most motors have a minimum effective drive threshold below which the commanded waveform
    // reads as an unpleasant low buzz/rattle rather than a quieter version of the same feel, not a
    // linear "quieter is always better" response. 0.12 (12% of the track's peak envelope) clears
    // that residual/noise-floor band while leaving genuinely quiet-but-real passages (e.g. a soft
    // verse) above the gate.
    val continuousEnvelopeGateThreshold: Float = 0.12f,
    // Stem Pulse only (see EditorViewModel.effectiveContinuousEnvelopeFor and
    // EnvelopeFollower.applyTransientEmphasis) - 0 leaves the gated envelope untouched, higher
    // values push a power-curve expansion that suppresses sustained/quiet stem content further
    // while leaving peaks near 1.0 unchanged, so a stem like "drums" reads as isolated kick/snare
    // spikes instead of an undifferentiated continuous buzz. Not applied to any other mode's
    // envelope blend - see the FilterSheet slider that only shows for STEM_PULSE.
    val transientEmphasis: Float = 0.0f
)
