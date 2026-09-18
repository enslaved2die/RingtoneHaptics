package com.ringtonehaptics.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class HapticGenerationMode(val displayName: String) {
    AUTOMATIC("Automatic"),
    BEATMATCHING("Beatmatching"),
    DYNAMIC_ENVELOPE("Dynamic Envelope"),
    BEAT_AND_ENVELOPE("Beat + Envelope"),
    MANUAL("Manual"),
    // Envelope-only, like DYNAMIC_ENVELOPE, but the continuous envelope is followed straight off
    // a single user-chosen ML stem's own PCM instead of the full mix - see EditorViewModel's
    // effectiveContinuousEnvelope/stemPcm. No discrete clips of its own: the point is to feel that
    // isolated instrument/voice directly as a continuous background, same as GROOVE_BASS's blend.
    STEM_PULSE("Stem Pulse")
}

/** Drum-classified percussion (kick/snare/tom/hihat/cymbal) is generated in these modes. */
fun HapticGenerationMode.usesPercussion(): Boolean = when (this) {
    HapticGenerationMode.AUTOMATIC, HapticGenerationMode.BEATMATCHING, HapticGenerationMode.BEAT_AND_ENVELOPE -> true
    HapticGenerationMode.DYNAMIC_ENVELOPE, HapticGenerationMode.MANUAL, HapticGenerationMode.STEM_PULSE -> false
}

/** Groove/bass + dynamic-envelope (fade/crescendo) layers, boosted to feel more present in non-Automatic modes. */
fun HapticGenerationMode.usesEnvelopeLayers(): Boolean = when (this) {
    HapticGenerationMode.AUTOMATIC, HapticGenerationMode.DYNAMIC_ENVELOPE, HapticGenerationMode.BEAT_AND_ENVELOPE -> true
    // STEM_PULSE stays out of this too - its continuous envelope is a wholly separate signal path
    // (see effectiveContinuousEnvelope), not one of the discrete groove-bass/fade clip generators.
    HapticGenerationMode.BEATMATCHING, HapticGenerationMode.MANUAL, HapticGenerationMode.STEM_PULSE -> false
}

/** Lead-cadence (harmonic punch detection) is a third layer distinct from "beat" or "envelope" -
 * only Automatic's "generate everything" pipeline includes it. */
fun HapticGenerationMode.usesLeadCadence(): Boolean = this == HapticGenerationMode.AUTOMATIC

/**
 * Tunes envelope-layer generation for modes where those layers are the whole point (Dynamic
 * Envelope, Beat+Envelope), never for Automatic, which keeps its tuned defaults exactly.
 *
 * blendContinuousEnvelope is pushed toward ZERO here, not boosted - this was backwards until a
 * direct reverse-engineering of the most-loved real result this app has produced (an
 * "energysound-powerful-percussion" export) found it was built almost entirely from discrete
 * SWELL/RUMBLE/groove clips with near-zero correlation to any continuous envelope blend (i.e. it
 * was made with this near 0, not near 1). Independently, Google's own stock Pixel ringtones (audio-
 * coupled haptics, ANDROID_HAPTIC tagged) show the same signature: their haptic channels are only
 * ~10-31% active by RMS threshold, not a near-continuous background - sparse, front-loaded,
 * decaying discrete events, not constant buzz. A high blend here was previously tuned against a
 * completely different reference (a Telegram bot's always-on style) that doesn't match what this
 * app's own best output - or Google's own designs - actually do for discrete-event-driven modes.
 */
fun FilterConfig.boostedForEnvelopeMode(): FilterConfig = copy(
    blendContinuousEnvelope = 0.10f,
    attackMs = (attackMs * 0.5f).coerceAtLeast(2f),
    sensitivityThreshold = (sensitivityThreshold * 0.7f).coerceAtLeast(0.15f)
)
