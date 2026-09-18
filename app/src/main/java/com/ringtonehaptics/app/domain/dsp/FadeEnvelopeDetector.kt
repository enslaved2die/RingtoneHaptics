package com.ringtonehaptics.app.domain.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class FadeConfig(
    val hopMs: Float = 15.0f,                       // envelope sampling resolution
    val smoothingAlpha: Float = 0.85f,               // one-pole envelope smoother
    val windowsMs: List<Long> = listOf(3000L, 1500L, 500L), // largest first: preferred on simultaneous match
    val riseThresholdDbPerSec: Float = 3.0f,
    val mergeGapMs: Long = 200L,
    val minDurationMs: Long = 400L,
    val minDeltaDb: Float = 6.0f
)

/** A detected smooth loudness ramp - a crescendo (fade-up/riser) or decrescendo (fade-down/drop). */
data class FadeSpan(
    val startMs: Long,
    val endMs: Long,
    val isCrescendo: Boolean,
    val deltaDb: Float
)

/**
 * Energy-slope (dB/sec) fade detection over multi-scale sliding windows. Traditional peak/onset
 * detectors only fire on sudden spikes and miss smooth build-ups and drops entirely - this looks
 * at the rate of change of the smoothed loudness envelope instead, which is what a crescendo or
 * decrescendo actually is.
 */
class FadeEnvelopeDetector {

    fun detect(pcmSamples: FloatArray, sampleRate: Int, config: FadeConfig = FadeConfig()): List<FadeSpan> {
        val hopSamples = ((config.hopMs / 1000.0f) * sampleRate).toInt().coerceAtLeast(1)
        val numSteps = pcmSamples.size / hopSamples
        if (numSteps < 4) return emptyList()

        // 1. Smoothed RMS energy envelope, one value per hop
        val env = FloatArray(numSteps)
        var smoothed = 0.0f
        val alpha = config.smoothingAlpha
        for (n in 0 until numSteps) {
            val start = n * hopSamples
            val end = (start + hopSamples).coerceAtMost(pcmSamples.size)
            var sumSq = 0.0f
            for (i in start until end) sumSq += pcmSamples[i] * pcmSamples[i]
            val rms = sqrt(sumSq / (end - start).coerceAtLeast(1))
            smoothed = smoothed * alpha + rms * (1.0f - alpha)
            env[n] = smoothed
        }

        val envDb = FloatArray(numSteps) { 20.0f * log10(env[it] + 1e-6f) }
        val windowSteps = config.windowsMs.map { (it / config.hopMs).toInt().coerceAtLeast(1) }

        // 2. Per-step direction: largest window whose dB/sec slope clears the threshold wins
        val direction = IntArray(numSteps) // -1 = falling, 0 = none, 1 = rising
        for (n in 0 until numSteps) {
            for ((wi, wSteps) in windowSteps.withIndex()) {
                if (n - wSteps < 0) continue
                val windowSec = config.windowsMs[wi] / 1000.0f
                val slopeDbPerSec = (envDb[n] - envDb[n - wSteps]) / windowSec
                if (slopeDbPerSec > config.riseThresholdDbPerSec) {
                    direction[n] = 1
                    break
                } else if (slopeDbPerSec < -config.riseThresholdDbPerSec) {
                    direction[n] = -1
                    break
                }
            }
        }

        // 3. Collapse into contiguous same-direction runs
        data class Run(var startStep: Int, var endStep: Int, val dir: Int)
        val runs = mutableListOf<Run>()
        var i = 0
        while (i < numSteps) {
            val d = direction[i]
            if (d != 0) {
                var j = i
                while (j < numSteps && direction[j] == d) j++
                runs.add(Run(i, j - 1, d))
                i = j
            } else {
                i++
            }
        }
        if (runs.isEmpty()) return emptyList()

        // 4. Merge adjacent same-direction runs separated by a short gap (flutter rejection)
        val mergeGapSteps = (config.mergeGapMs / config.hopMs).toInt().coerceAtLeast(1)
        val merged = mutableListOf<Run>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.dir == run.dir && (run.startStep - last.endStep) <= mergeGapSteps) {
                last.endStep = run.endStep
            } else {
                merged.add(run)
            }
        }

        // 5. Filter by minimum duration and minimum total dB delta, emit spans
        val spans = mutableListOf<FadeSpan>()
        for (run in merged) {
            val startMs = (run.startStep * config.hopMs).toLong()
            val endMs = (run.endStep * config.hopMs).toLong()
            val deltaDb = envDb[run.endStep] - envDb[run.startStep]
            if ((endMs - startMs) >= config.minDurationMs && abs(deltaDb) >= config.minDeltaDb) {
                spans.add(
                    FadeSpan(
                        startMs = startMs,
                        endMs = endMs,
                        isCrescendo = run.dir > 0,
                        deltaDb = deltaDb
                    )
                )
            }
        }

        return spans
    }
}
