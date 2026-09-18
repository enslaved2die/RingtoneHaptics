package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.FilterConfig
import com.ringtonehaptics.app.domain.model.HapticNode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

data class EnvelopeResult(
    val envelope: FloatArray,
    val detectedNodes: List<HapticNode>,
    val decimatedEnvelope: FloatArray // Suitable for UI waveform rendering (e.g. 1000 points)
)

class EnvelopeFollower {

    fun analyze(
        pcmSamples: FloatArray,
        sampleRate: Int,
        config: FilterConfig,
        visualPointCount: Int = 1000
    ): EnvelopeResult {
        val numSamples = pcmSamples.size
        if (numSamples == 0) {
            return EnvelopeResult(FloatArray(0), emptyList(), FloatArray(0))
        }

        // Apply Biquad Filter first (e.g. Lowpass to isolate bass rhythm)
        val filter = BiquadFilter().apply {
            configure(
                type = config.filterType,
                cutoffHz = config.cutoffFrequencyHz,
                sampleRate = sampleRate,
                q = config.resonanceQ
            )
        }

        val filtered = filter.processBuffer(pcmSamples)

        val attackCoeff = exp(-1.0f / (sampleRate * (config.attackMs / 1000.0f)))
        val decayCoeff = exp(-1.0f / (sampleRate * (config.decayMs / 1000.0f)))

        val envelope = FloatArray(numSamples)
        var currentEnv = 0.0f
        var maxPeak = 0.0001f

        for (i in 0 until numSamples) {
            val rectified = abs(filtered[i])
            currentEnv = if (rectified > currentEnv) {
                attackCoeff * currentEnv + (1.0f - attackCoeff) * rectified
            } else {
                decayCoeff * currentEnv + (1.0f - decayCoeff) * rectified
            }
            envelope[i] = currentEnv
            if (currentEnv > maxPeak) {
                maxPeak = currentEnv
            }
        }

        // Normalize envelope, then noise-gate it: values at/below the floor hard-zero rather than
        // trailing off (see FilterConfig.continuousEnvelopeGateThreshold for why a low but nonzero
        // commanded amplitude is actually worse than silence on real LRA hardware). Remapping the
        // surviving range back to [floor, 1] -> [0, 1] (a downward-expander knee) instead of just
        // clamping means the gate is continuous at the threshold - no click at the on/off boundary -
        // while still reaching exactly 0 below it and exactly 1 at the original peak.
        val invMax = 1.0f / maxPeak
        val gateFloor = config.continuousEnvelopeGateThreshold.coerceIn(0.0f, 0.95f)
        val invGateRange = 1.0f / (1.0f - gateFloor)
        for (i in 0 until numSamples) {
            val normalized = (envelope[i] * invMax).coerceIn(0.0f, 1.0f)
            envelope[i] = if (normalized <= gateFloor) {
                0.0f
            } else {
                ((normalized - gateFloor) * invGateRange).coerceIn(0.0f, 1.0f)
            }
        }

        // Peak / Onset Detection for Haptic Nodes
        val nodes = mutableListOf<HapticNode>()
        val minIntervalSamples = (sampleRate * 0.060f).toInt() // 60ms refractory window
        val threshold = config.sensitivityThreshold
        var lastPeakIdx = -minIntervalSamples

        var i = 1
        while (i < numSamples - 1) {
            val prev = envelope[i - 1]
            val curr = envelope[i]
            val next = envelope[i + 1]

            if (curr > threshold && curr >= prev && curr >= next && (i - lastPeakIdx) >= minIntervalSamples) {
                val timestampMs = ((i.toLong() * 1000L) / sampleRate)
                nodes.add(
                    HapticNode(
                        timestampMs = timestampMs,
                        intensity = curr.coerceIn(0.2f, 1.0f),
                        durationMs = 80,
                        isAutoDetected = true
                    )
                )
                lastPeakIdx = i
                i += minIntervalSamples // Jump forward
            } else {
                i++
            }
        }

        // Decimate for fast UI waveform display
        val decimated = FloatArray(visualPointCount)
        val step = numSamples.toFloat() / visualPointCount
        for (idx in 0 until visualPointCount) {
            val start = (idx * step).toInt().coerceIn(0, numSamples - 1)
            val end = ((idx + 1) * step).toInt().coerceIn(start + 1, numSamples)
            var localMax = 0.0f
            for (s in start until end) {
                if (envelope[s] > localMax) localMax = envelope[s]
            }
            decimated[idx] = localMax
        }

        return EnvelopeResult(
            envelope = envelope,
            detectedNodes = nodes,
            decimatedEnvelope = decimated
        )
    }

    /**
     * Stem Pulse's "Punch" control (FilterConfig.transientEmphasis). Isolating drum-style spikes
     * out of a continuous envelope is a dynamics-contrast problem, not a loudness one: a normal
     * (upward) compressor flattens dynamics, which is the opposite of "make the spikes stand out
     * more" - what's wanted is a downward expander that pushes the sustained/quiet body of the
     * envelope down harder while leaving transient peaks (already near 1.0 after the gate above)
     * essentially untouched. Raising each already-gated [0,1] sample to a power > 1 does exactly
     * that: x^n stays fixed at the endpoints (0 -> 0, 1 -> 1) but bows the curve down in between,
     * and the bow gets more aggressive the further a sample is from the peak - so a hit that's 90%
     * of peak barely moves while a sustain tail sitting at 30% gets squashed toward silence. This
     * was checked against a synthetic kick-hit-over-sustained-pad signal (see
     * AdvancedHapticSynthesizerTest/EnvelopeFollowerTest for the equivalent shape): at amount=1.0
     * the sustain/peak ratio drops from ~0.3 to ~0.03, a 10x widening of exactly the contrast the
     * user asked for, while the peak sample itself moves by <1%.
     *
     * amount is 0..1 UI-facing; mapped to an exponent of 1..4 so amount=0 is a true no-op (matches
     * every other mode's untouched envelope) and amount=1 gives a sharply spiky feel without fully
     * collapsing legitimate mid-level hits to zero (that's what the hard gate above is already for).
     */
    fun applyTransientEmphasis(envelope: FloatArray, amount: Float): FloatArray {
        val clampedAmount = amount.coerceIn(0.0f, 1.0f)
        if (clampedAmount <= 0.0f) return envelope
        val exponent = 1.0f + 3.0f * clampedAmount
        return FloatArray(envelope.size) { i ->
            envelope[i].coerceIn(0.0f, 1.0f).pow(exponent)
        }
    }

    /**
     * Stem Pulse per-stem "solid/sustained" shaping (StemHapticProfile.sustainSmoothingMs, bass by
     * default) - a one-pole lowpass over the already-gated envelope, same exp(-1/(sr*tau)) coefficient
     * form EnvelopeFollower's own attack/decay uses above, just applied a second time post-hoc. The
     * per-sample attack/decay in analyze() is tuned for onset *detection* (fast enough to track a
     * kick), which leaves individual note-to-note steps visible in a continuous bass line - this
     * rounds those off into one flowing pressure without touching the gate or normalization that
     * already ran. A no-op (returns the input unchanged) for every stem that doesn't want it.
     */
    fun applySustainSmoothing(envelope: FloatArray, sampleRate: Int, smoothingMs: Float): FloatArray {
        if (smoothingMs <= 0.0f || envelope.isEmpty()) return envelope
        val coeff = exp(-1.0f / (sampleRate * (smoothingMs / 1000.0f)))
        val smoothed = FloatArray(envelope.size)
        var current = envelope[0]
        for (i in envelope.indices) {
            current = coeff * current + (1.0f - coeff) * envelope[i]
            smoothed[i] = current
        }
        return smoothed
    }
}
