package com.ringtonehaptics.app.domain.dsp

import com.ringtonehaptics.app.domain.model.BeatGridInfo
import com.ringtonehaptics.app.domain.model.FilterType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Rational tempo ratios checked by the harmonic-sum tempo correction below - covers both classic
// octave errors (2:1) and compound-meter/triplet-feel confusions (3:2, 4:3), the two failure modes
// verified against real tracks (see BpmBeatDetector's class doc for the investigation this is from).
private val TEMPO_RATIOS = listOf(2 to 1, 1 to 2, 3 to 2, 2 to 3, 4 to 3, 3 to 4)
private const val NOVELTY_RATE = 200

class BpmBeatDetector {

    /**
     * Re-scores which of the 4 beats-per-bar is the downbeat for an already-known set of beat
     * timestamps, by recomputing the novelty curve from the original PCM. Needed because a manual
     * BPM correction ([EditorViewModel.adjustBpm]) rebuilds beatTimestamps from scratch with a new
     * interval, and the downbeat offset used at detection time isn't otherwise persisted anywhere
     * to survive that rebuild.
     */
    fun recomputeDownbeats(pcmSamples: FloatArray, sampleRate: Int, beatTimestamps: List<Long>): Set<Long> {
        val novelty = computeNovelty(pcmSamples, sampleRate) ?: return emptySet()
        val offset = bestDownbeatOffset(beatTimestamps, novelty, NOVELTY_RATE)
        return beatTimestamps.filterIndexed { i, _ -> i % 4 == offset }.toSet()
    }

    /**
     * Downsamples audio to a ~200 Hz (5ms resolution) onset-novelty curve: low-pass to isolate
     * rhythmic bass energy, then half-wave-rectified frame-to-frame energy difference. Float-step
     * downsampling (not integer division) avoids a systematic rate error for sample rates that
     * don't divide evenly by 200 (e.g. 44100/200 = 220.5, truncating to 220 silently ran the
     * novelty clock at ~200.45Hz while every downstream ms conversion assumed exactly 200Hz).
     */
    private fun computeNovelty(pcmSamples: FloatArray, sampleRate: Int): FloatArray? {
        val downsampleFactor = sampleRate.toFloat() / NOVELTY_RATE
        val numFrames = (pcmSamples.size / downsampleFactor).toInt()
        if (numFrames < NOVELTY_RATE) return null

        val filter = BiquadFilter().apply {
            configure(FilterType.LOWPASS, cutoffHz = 180.0f, sampleRate = sampleRate)
        }
        val filtered = filter.processBuffer(pcmSamples)

        val novelty = FloatArray(numFrames)
        var prevEnergy = 0.0f
        for (f in 0 until numFrames) {
            val start = (f * downsampleFactor).toInt()
            val end = ((f + 1) * downsampleFactor).toInt().coerceAtMost(pcmSamples.size)
            var sum = 0.0f
            for (i in start until end) {
                sum += abs(filtered[i])
            }
            val energy = sum / (end - start).coerceAtLeast(1)
            val diff = energy - prevEnergy
            novelty[f] = if (diff > 0.0f) diff else 0.0f
            prevEnergy = energy
        }
        return novelty
    }

    /**
     * Detects tempo (BPM) and calculates a musical beat grid using a windowed-tempogram
     * autocorrelation, verified against 3 real reference tracks with known correct BPM.
     *
     * Global (whole-track) autocorrelation is prone to rational-ratio tempo locks (3:2, 4:3, 2:1
     * confusions) whenever a song's rhythmic character shifts enough that no single global lag
     * dominates. The fix verified here: split the novelty curve into independent 12s windows (no
     * overlap), normalize each window by its own stddev only (so loud sections don't dominate),
     * correlate within each window, then take the per-lag MEDIAN across windows before applying
     * the harmonic-ratio-sum + log-BPM-prior correction ([scoreLag]). Mean aggregation and/or
     * skipping the per-window normalization both measurably regress real tracks - median-of-
     * normalized-windows is required, not incidental.
     *
     * This also gives variable-tempo support for free: each window's own locally-scored best lag
     * is used to advance the beat grid through that window's time span, so [BeatGridInfo]'s beat
     * spacing can drift across a track whose tempo changes, rather than being one constant
     * interval end to end.
     */
    fun detectBeatGrid(
        pcmSamples: FloatArray,
        sampleRate: Int,
        durationMs: Long
    ): BeatGridInfo {
        if (pcmSamples.isEmpty() || durationMs < 1000) {
            return BeatGridInfo()
        }

        val noveltyRate = NOVELTY_RATE
        val novelty = computeNovelty(pcmSamples, sampleRate) ?: return BeatGridInfo()
        val numFrames = novelty.size

        // Autocorrelation across BPM range 70 to 175
        // Lag in novelty frames: lag = (60 / BPM) * noveltyRate
        val minBpm = 70
        val maxBpm = 175
        val minLag = (60.0f / maxBpm * noveltyRate).toInt()
        val maxLag = (60.0f / minBpm * noveltyRate).toInt()

        // 12.0s / 12.0s (0% overlap) windows at the 200Hz novelty rate - verified exact parameter,
        // sensitive to +/-0.5s in either dimension (see class doc).
        val windowFrames = 12 * noveltyRate
        val hopFrames = windowFrames
        val windows = buildWindowCorrelations(novelty, numFrames, windowFrames, hopFrames, minLag, maxLag)
        if (windows.isEmpty()) {
            return BeatGridInfo()
        }

        // Global tempo: median-of-windows correlation curve, scored with the harmonic+prior fix.
        val aggCorr = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            aggCorr[lag] = median(windows.map { it[lag] })
        }
        var bestLag = (60.0f / 120.0f * noveltyRate).toInt().coerceIn(minLag, maxLag)
        var bestScore = -1.0f
        for (lag in minLag..maxLag) {
            val score = scoreLag(lag, aggCorr, minLag, maxLag, noveltyRate)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        val detectedBpm = ((60.0f * noveltyRate) / bestLag).roundToInt().coerceIn(60, 200)

        // Per-window local tempo: same scoring, applied to each window's own correlation curve
        // rather than the aggregate, so the beat grid below can track a tempo that drifts across
        // the track instead of assuming one constant interval end to end.
        val localLags = windows.map { corr ->
            var bLag = bestLag
            var bScore = -1.0f
            for (lag in minLag..maxLag) {
                val score = scoreLag(lag, corr, minLag, maxLag, noveltyRate)
                if (score > bScore) {
                    bScore = score
                    bLag = lag
                }
            }
            bLag
        }

        // Find phase (first beat position) in the first 2 beat intervals of the first window's tempo
        val searchWindowFrames = (localLags.first() * 2).coerceAtMost(numFrames)
        var maxOnset = 0.0f
        var firstBeatFrame = 0
        for (f in 0 until searchWindowFrames) {
            if (novelty[f] > maxOnset) {
                maxOnset = novelty[f]
                firstBeatFrame = f
            }
        }
        val firstBeatMs = (firstBeatFrame.toFloat() / noveltyRate * 1000L).toLong()

        // Walk the beat grid window by window, using each window's own local tempo for the beats
        // that fall inside its time span - this is what makes the grid variable-BPM rather than a
        // single fixed interval, while staying phase-continuous across window boundaries.
        val beatTimestamps = mutableListOf<Long>()
        var currentBeat = firstBeatMs
        var windowIdx = (firstBeatFrame / hopFrames).coerceIn(0, localLags.lastIndex)
        while (currentBeat <= durationMs) {
            beatTimestamps.add(currentBeat)
            val frame = (currentBeat.toFloat() / 1000f * noveltyRate).toInt()
            windowIdx = (frame / hopFrames).coerceIn(0, localLags.lastIndex)
            val intervalMs = 60_000.0f / ((60.0f * noveltyRate) / localLags[windowIdx])
            currentBeat += intervalMs.toLong()
        }
        val beatIntervalMs = (60_000.0f / detectedBpm).toLong()

        // Which of the 4 beats-per-bar is actually the downbeat isn't just "whichever beat index
        // happened to be first" - that was a pure beatIndex%4==0 count with no verification at
        // all. Downbeats carry more energy/accent than other beats in most music, so scoring all
        // 4 candidate offsets by their average novelty and keeping the strongest is a real (if
        // still simple) improvement - verified against a real track where offset 0 was NOT best.
        val downbeats = bestDownbeatOffset(beatTimestamps, novelty, noveltyRate)
            .let { offset -> beatTimestamps.filterIndexed { i, _ -> i % 4 == offset }.toSet() }

        return BeatGridInfo(
            bpm = detectedBpm,
            firstBeatMs = firstBeatMs,
            beatIntervalMs = beatIntervalMs,
            beatTimestamps = beatTimestamps,
            downbeatTimestamps = downbeats
        )
    }

    /**
     * Splits novelty into non-overlapping windows, std-normalizes each (no mean-subtraction - a
     * full z-score regresses real tracks), and autocorrelates within each window independently.
     * Returns one FloatArray(maxLag + 1) per window, raw-correlation indexed by lag exactly like
     * the old whole-track version, ready for median aggregation or per-window local scoring.
     */
    private fun buildWindowCorrelations(
        novelty: FloatArray,
        numFrames: Int,
        windowFrames: Int,
        hopFrames: Int,
        minLag: Int,
        maxLag: Int
    ): List<FloatArray> {
        val windows = mutableListOf<FloatArray>()
        var start = 0
        while (start < numFrames) {
            val end = (start + windowFrames).coerceAtMost(numFrames)
            val n = end - start
            if (n <= maxLag) break

            var mean = 0.0f
            for (i in start until end) mean += novelty[i]
            mean /= n
            var variance = 0.0f
            for (i in start until end) {
                val d = novelty[i] - mean
                variance += d * d
            }
            val std = sqrt(variance / n)
            if (std < 1e-9f) {
                start += hopFrames
                continue
            }

            val seg = FloatArray(n) { novelty[start + it] / std }
            val corr = FloatArray(maxLag + 1)
            for (lag in minLag..maxLag) {
                val count = n - lag
                if (count > 0) {
                    var sum = 0.0f
                    for (i in 0 until count) {
                        sum += seg[i] * seg[i + lag]
                    }
                    corr[lag] = sum / count
                }
            }
            windows.add(corr)
            start += hopFrames
        }
        return windows
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0.0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun scoreLag(lag: Int, rawCorr: FloatArray, minLag: Int, maxLag: Int, noveltyRate: Int): Float {
        var total = rawCorr[lag]
        for ((p, q) in TEMPO_RATIOS) {
            val target = (lag.toFloat() * p / q).roundToInt()
            if (target in minLag..maxLag) {
                total += rawCorr[target] * (0.20f / (p + q))
            }
        }
        // Mild prior toward a common contemporary tempo range - only breaks near-ties between
        // rational-ratio-ambiguous candidates, doesn't override a clearly dominant raw peak.
        val bpm = 60.0f * noveltyRate / lag
        val octavesFromCenter = ln((bpm / 112.0f).toDouble()) / ln(2.0)
        val prior = exp(-0.5 * (octavesFromCenter / 0.6).let { it * it }).toFloat()
        return total * (0.88f + 0.12f * prior)
    }

    private fun bestDownbeatOffset(beatTimestamps: List<Long>, novelty: FloatArray, noveltyRate: Int): Int {
        if (beatTimestamps.isEmpty()) return 0
        var bestOffset = 0
        var bestAvg = -1.0f
        for (offset in 0 until 4) {
            var sum = 0.0f
            var count = 0
            for (i in beatTimestamps.indices) {
                if (i % 4 != offset) continue
                val frame = ((beatTimestamps[i] / 1000.0) * noveltyRate).toInt()
                if (frame in novelty.indices) {
                    sum += novelty[frame]
                    count++
                }
            }
            val avg = if (count > 0) sum / count else 0.0f
            if (avg > bestAvg) {
                bestAvg = avg
                bestOffset = offset
            }
        }
        return bestOffset
    }
}
