package com.ringtonehaptics.app.domain.model

/**
 * Per-stem haptic "character" for Stem Pulse's continuous envelope blend (see
 * EditorViewModel.effectiveContinuousEnvelopeFor and AdvancedHapticSynthesizer.synthesizeChannel2's
 * continuous-blend section). Stem Pulse used to feed every stem through the exact same carrier
 * frequency and (absent user input) an unshaped envelope, so "drums" and "bass" - two signals with
 * completely different physical character - produced the same undifferentiated buzz. This profile
 * is the fix: each stem gets tuned defaults for the same three levers this codebase's discrete
 * clip patterns already use to distinguish "sharp/crisp" from "solid/rounded" (see THUMP vs
 * SNAP_CLICK vs RUMBLE in AdvancedHapticSynthesizer), applied to the continuous path instead.
 *
 * carrierFreqRatio: multiplies FilterConfig.resonantFrequencyHz (then still coerced to the LRA's
 * 100-250Hz usable band downstream, same as every discrete clip's carrierFreqHz). This mirrors
 * precedent already in this file's sibling AdvancedHapticAnalyzer: RUMBLE (the existing continuous
 * "solid background" pattern, used for GROOVE_BASS) drives at a fixed 140Hz against a 160Hz default
 * resonance - i.e. ~0.875x, deliberately sub-resonant. Driving below resonance on a real LRA rolls
 * off peak output and lengthens the mechanical settling time (the actuator "rings" more before it
 * catches up to the drive signal), which is exactly what reads as rounder/less percussive - the
 * right physical character for a sustained bass line. Driving AT resonance instead (ratio 1.0)
 * gives the LRA its maximum instantaneous output and fastest response, i.e. maximum punch - the
 * same choice SNAP_CLICK/THUMP/DROP already make for discrete "hit" patterns - so drums get 1.0.
 *
 * transientEmphasisBase: the floor fed into EnvelopeFollower.applyTransientEmphasis before the
 * user's own Punch slider (FilterConfig.transientEmphasis) is combined on top - see
 * EditorViewModel.combinedTransientAmount. High for drums (isolate short kick/snare-style spikes
 * out of the continuous signal, per applyTransientEmphasis's own doc comment), zero for bass (a
 * downward expander is the wrong shape for "solid" - it would carve gaps into what should read as
 * one continuous pressure), a mild middle value for other/vocals.
 *
 * sustainSmoothingMs: extra one-pole smoothing (see EnvelopeFollower.applySustainSmoothing)
 * applied to the envelope BEFORE transient emphasis. FilterConfig's attack/decay coefficients are
 * tuned for onset detection (fast, ~8/60ms), which leaves individual bass notes/pick attacks
 * visible as small steps - fine for drums (where that responsiveness is wanted) but the opposite
 * of "solid" for a sustained bass stem. 0 = no-op.
 */
data class StemHapticProfile(
    val carrierFreqRatio: Float,
    val transientEmphasisBase: Float,
    val sustainSmoothingMs: Float
)

private val STEM_PULSE_DRUMS_PROFILE = StemHapticProfile(
    carrierFreqRatio = 1.0f,
    transientEmphasisBase = 0.65f,
    sustainSmoothingMs = 0f
)

private val STEM_PULSE_BASS_PROFILE = StemHapticProfile(
    carrierFreqRatio = 0.875f,
    transientEmphasisBase = 0.0f,
    sustainSmoothingMs = 40f
)

private val STEM_PULSE_MIDRANGE_PROFILE = StemHapticProfile(
    carrierFreqRatio = 1.0f,
    transientEmphasisBase = 0.25f,
    sustainSmoothingMs = 15f
)

/** Neutral/no-op profile - full-mix envelope (no stem selected, or ML separation hasn't run yet),
 * reproducing exactly what Stem Pulse did before per-stem profiles existed. */
val STEM_PULSE_DEFAULT_PROFILE = StemHapticProfile(
    carrierFreqRatio = 1.0f,
    transientEmphasisBase = 0.0f,
    sustainSmoothingMs = 0f
)

fun stemHapticProfileFor(stemName: String?): StemHapticProfile = when (stemName) {
    "drums" -> STEM_PULSE_DRUMS_PROFILE
    "bass" -> STEM_PULSE_BASS_PROFILE
    "other", "vocals" -> STEM_PULSE_MIDRANGE_PROFILE
    else -> STEM_PULSE_DEFAULT_PROFILE
}

/**
 * Combines a stem's default transient-emphasis floor with the user-facing Punch slider
 * (FilterConfig.transientEmphasis) as an override that can only push emphasis UP, never down -
 * "Punch" stays purely additive/boosting, matching its existing UI framing as an intensifier, and
 * a user who wants a spikier bass can still get one at Punch=1 (1 - (1-0)*(1-1) = 1), while Punch=0
 * leaves every stem at its own tuned default instead of collapsing them all to the same value.
 */
fun combinedTransientAmount(stemBase: Float, userPunch: Float): Float {
    val base = stemBase.coerceIn(0.0f, 1.0f)
    val punch = userPunch.coerceIn(0.0f, 1.0f)
    return 1.0f - (1.0f - base) * (1.0f - punch)
}
